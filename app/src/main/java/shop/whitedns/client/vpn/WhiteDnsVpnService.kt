package shop.whitedns.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import shop.whitedns.client.MainActivity
import shop.whitedns.client.R
import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.canDiscoverSoarResolvers
import shop.whitedns.client.model.ResolvedWhiteDnsSettings
import shop.whitedns.client.model.ServerDomains
import shop.whitedns.client.model.StormDnsServerProfile
import shop.whitedns.client.model.WhiteDnsOptions
import shop.whitedns.client.model.canBootstrapResolversWithCottenMax
import shop.whitedns.client.model.usesCottenMax
import shop.whitedns.client.runtime.parseStormDnsConnectionProgressLine
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.WhiteDnsSettingsStore
import shop.whitedns.client.model.resolve
import shop.whitedns.client.model.runtimeConnectionSettings
import shop.whitedns.client.model.selectedConnectionProfile
import shop.whitedns.client.proxy.WhiteDnsProxyService
import shop.whitedns.client.runtime.RuntimeLaunchRequestStore
import shop.whitedns.client.runtime.WhiteDnsRuntimeStateStore
import shop.whitedns.client.runtime.WhiteDnsTrafficWarmup
import shop.whitedns.client.runtime.formatTrafficNotificationText
import shop.whitedns.client.runtime.parseStormDnsTrafficStatsLine
import shop.whitedns.client.storm.StormDnsProcessManager

class WhiteDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var foregroundStarted = false
    private var startJob: Job? = null
    private var shutdownJob: Job? = null
    private var keepaliveJob: Job? = null
    private var runtimeReady = false
    private var lastTrafficNotificationUpdateMillis = 0L
    private var currentSessionId = ""
    private val lifecycleGeneration = AtomicLong()
    @Volatile
    private var stopping = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stormDnsProcessManager by lazy {
        StormDnsProcessManager(applicationContext)
    }
    private val tun2SocksProcessManager by lazy {
        Tun2SocksProcessManager(applicationContext)
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ActionStop -> {
                requestShutdown()
                START_NOT_STICKY
            }
            else -> {
                try {
                    enterForeground("Preparing WhiteDNS")
                    startVpn(intent)
                    START_REDELIVER_INTENT
                } catch (error: Exception) {
                    logError("Failed to start foreground VPN service", error)
                    requestShutdown()
                    START_NOT_STICKY
                }
            }
        }
    }

    override fun onDestroy() {
        stopping = true
        lifecycleGeneration.incrementAndGet()
        startJob?.cancel()
        stopTrafficKeepalive()
        // Native shutdown and child-process joins can take seconds. Never hold
        // Android's main thread while the system destroys the VPN service.
        val oldInterface = vpnInterface
        vpnInterface = null
        Thread {
            runCatching { tun2SocksProcessManager.requestStop() }
            runCatching { oldInterface?.close() }
            runCatching { tun2SocksProcessManager.stop(Tun2proxyStopGracePeriodMillis) }
            runCatching { stormDnsProcessManager.stop() }
        }.apply {
            name = "whitedns-vpn-destroy-cleanup"
            isDaemon = true
            start()
        }
        exitForeground()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        ContextCompat.getMainExecutor(this).execute { requestShutdown() }
    }

    private fun requestShutdown() {
        stopping = true
        val generation = lifecycleGeneration.incrementAndGet()
        val activeStart = startJob
        val previousShutdown = shutdownJob
        activeStart?.cancel()
        shutdownJob = serviceScope.launch {
            previousShutdown?.join()
            activeStart?.join()
            // A newer connect request owns its own teardown/start sequence.
            if (lifecycleGeneration.get() != generation) return@launch
            stopVpn()
            if (lifecycleGeneration.get() != generation) return@launch
            exitForeground()
            stopSelf()
        }
    }

    private fun enterForeground(statusText: String) {
        createNotificationChannel()
        val notification = buildForegroundNotification(statusText)
        if (foregroundStarted) {
            updateForegroundNotification(statusText)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(NotificationId, notification)
        }
        foregroundStarted = true
    }

    private fun updateForegroundNotification(statusText: String) {
        if (!foregroundStarted) {
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NotificationId, buildForegroundNotification(statusText))
    }

    private fun exitForeground() {
        if (!foregroundStarted) {
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            NotificationChannelId,
            "WhiteDNS VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the active WhiteDNS VPN connection"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            pendingIntentFlags,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WhiteDnsVpnService::class.java).setAction(ActionStop),
            pendingIntentFlags,
        )

        return NotificationCompat.Builder(this, NotificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WhiteDNS VPN")
            .setContentText(statusText)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_notification, "Disconnect", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun startVpn(intent: Intent?) {
        val previousJob = startJob
        val previousShutdown = shutdownJob
        val generation = lifecycleGeneration.incrementAndGet()
        val sessionId = intent?.getStringExtra(ExtraSessionId).orEmpty()
        startJob = serviceScope.launch {
            previousJob?.cancelAndJoin()
            // A disconnect may already be inside native teardown. Join it
            // before new resources reuse the manager or a descriptor number.
            previousShutdown?.join()
            if (lifecycleGeneration.get() != generation) return@launch
            currentSessionId = sessionId
            try {
                val launchRequest = RuntimeLaunchRequestStore.load(applicationContext, sessionId)
                    ?: throw IllegalStateException("Runtime launch request is missing")
                val settings = launchRequest.settings.runtimeConnectionSettings()
                val resolvedSettings = settings.resolve()
                if (resolvedSettings.connectionMode != "vpn") {
                    throw IllegalStateException("VPN mode is not enabled")
                }
                if (resolvedSettings.resolverEntries.isEmpty() && !settings.canDiscoverSoarResolvers() && !launchRequest.serverProfile.canBootstrapResolversWithCottenMax(settings)) {
                    throw IllegalStateException("Resolvers are required to connect")
                }
                val serverProfile = launchRequest.serverProfile

                stopVpn()
                WhiteDnsProxyService.stop(applicationContext)
                waitForLocalPortToClose(resolvedSettings.listenPort)
                stopping = false
                runtimeReady = false
                lastTrafficNotificationUpdateMillis = 0L
                WhiteDnsRuntimeStateStore.markStarting(
                    context = applicationContext,
                    settings = settings,
                    sessionId = sessionId,
                    message = "Starting full-device VPN",
                )
                logInfo("Using custom ${DnsClientEngine.displayName(serverProfile.engine)} server")
                logInfo("Starting internal SOCKS bridge")
                startStormDnsAndVpn(sessionId, serverProfile, settings, resolvedSettings)
            } catch (error: CancellationException) {
                stopVpn()
                throw error
            } catch (error: Exception) {
                failAndStopVpn("Failed to start WhiteDNS VPN", error)
            }
        }
    }

    private suspend fun startStormDnsAndVpn(
        sessionId: String,
        serverProfile: StormDnsServerProfile,
        settings: WhiteDnsSettings,
        resolvedSettings: ResolvedWhiteDnsSettings,
    ) {
        val startupFailure = AtomicReference<String?>(null)
        val tunnelReady = java.util.concurrent.atomic.AtomicBoolean(
            !serverProfile.usesCottenMax() && DnsClientEngine.normalize(serverProfile.engine) != DnsClientEngine.SlowDns,
        )
        stormDnsProcessManager.start(serverProfile, settings) { line ->
            logInfo(line)
            if (parseStormDnsConnectionProgressLine(line)?.phase == "connected") tunnelReady.set(true)
            detectStormDnsStartupFailure(line)?.let { failure ->
                startupFailure.compareAndSet(null, failure)
            }
        }
        waitForProxyPort(
            listenPort = resolvedSettings.listenPort,
            startupFailure = { startupFailure.get() },
            tunnelReady = { tunnelReady.get() },
        )
        logInfo("SOCKS proxy is ready")
        startVpnRouting(sessionId, settings, resolvedSettings)
        monitorStormDnsProcess()
    }

    private suspend fun waitForProxyPort(
        listenPort: Int,
        startupFailure: () -> String?,
        tunnelReady: () -> Boolean,
    ) {
        while (true) {
            startupFailure()?.let { failure ->
                throw IllegalStateException("DNS engine startup failed: $failure")
            }
            if (!stormDnsProcessManager.isRunning()) {
                val exitCode = stormDnsProcessManager.exitCodeOrNull()
                throw IllegalStateException(
                    "DNS engine process exited before SOCKS was ready${exitCode?.let { " (exit code $it)" }.orEmpty()}",
                )
            }
            if (tunnelReady() && canConnectToLocalPort(listenPort)) {
                return
            }
            delay(500)
        }
    }

    private suspend fun waitForLocalPortToClose(port: Int) {
        val deadline = System.currentTimeMillis() + PreviousRuntimeStopTimeoutMillis
        while (canConnectToLocalPort(port)) {
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("Previous local proxy listener is still active on port $port")
            }
            delay(PreviousRuntimeStopPollMillis)
        }
    }

    private fun canConnectToLocalPort(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 300)
            }
            true
        }.getOrDefault(false)
    }

    private fun detectStormDnsStartupFailure(line: String): String? {
        val normalized = line.lowercase()
        return when {
            line.startsWith("SOAR_ERROR:") -> line.removePrefix("SOAR_ERROR:").trim()
            line.contains("SLOWDNS_ERROR:") -> line.substringAfter("SLOWDNS_ERROR:").trim()
            "no valid connections found after mtu testing" in normalized ||
                "mtu tests failed: no valid connections" in normalized ||
                "no valid connections after mtu testing" in normalized ->
                "No DNS resolver passed MTU testing"
            else -> null
        }
    }

    private suspend fun monitorStormDnsProcess() {
        while (true) {
            if (!tun2SocksProcessManager.isRunning()) {
                throw IllegalStateException("tun2proxy stopped while VPN was active")
            }
            if (!stormDnsProcessManager.isRunning()) {
                val exitCode = stormDnsProcessManager.exitCodeOrNull()
                throw IllegalStateException(
                    "DNS engine process exited while VPN was active${exitCode?.let { " (exit code $it)" }.orEmpty()}",
                )
            }
            delay(1_000)
        }
    }

    private suspend fun startVpnRouting(
        sessionId: String,
        settings: WhiteDnsSettings,
        resolvedSettings: ResolvedWhiteDnsSettings,
    ) {
        val socksHost = selectVpnSocksHost(resolvedSettings.listenIp)
        val socksPort = resolvedSettings.listenPort
        val socksUsername = if (resolvedSettings.socks5Authentication) {
            resolvedSettings.socksUsername
        } else {
            null
        }
        val socksPassword = if (resolvedSettings.socks5Authentication) {
            resolvedSettings.socksPassword
        } else {
            null
        }
        logInfo("Preparing Android VPN interface with virtual DNS")
        val tun = Builder()
            .setSession("WhiteDNS")
            .setMtu(VpnMtu)
            .addAddress(TunIpv4Address, TunIpv4PrefixLength)
            .addAddress(TunIpv6Address, 128)
            .addDnsServer(TunDnsServer)
            .addRoute(TunDnsServer, 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .apply {
                configureSplitTunnelApplications(
                    splitTunnelMode = resolvedSettings.splitTunnelMode,
                    splitTunnelPackages = resolvedSettings.splitTunnelPackages,
                )
            }
            .establish()
            ?: throw IllegalStateException("Failed to establish WhiteDNS VPN interface")

        vpnInterface = tun
        // tun2proxy is JNI in this process. Keep CLOEXEC set so a later
        // native-engine child cannot retain the TUN after VPN teardown.
        val tunFd = tun.fd
        logInfo("Routing device traffic to SOCKS $socksHost:$socksPort")
        tun2SocksProcessManager.start(
            tunFileDescriptor = tunFd,
            closeTunFileDescriptorOnDrop = false,
            socksHost = socksHost,
            socksPort = socksPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            onOutput = { line ->
                logInfo("tun2proxy: $line")
            },
            onExit = { exitCode ->
                if (stopping) {
                    Log.i(Tag, "tun2proxy stopped with code $exitCode")
                } else {
                    // Startup and the monitor own state transitions. An
                    // asynchronous failure callback used to race markReady.
                    logWarning("tun2proxy exited with code $exitCode")
                }
            },
        )
        if (!tun2SocksProcessManager.awaitRunning(Tun2proxyStartupTimeoutMillis)) {
            throw IllegalStateException("tun2proxy exited before VPN routing became ready")
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (stopping) throw CancellationException("VPN start was cancelled")
        updateForegroundNotification("Full-device VPN is active")
        runtimeReady = true
        WhiteDnsRuntimeStateStore.markReady(
            context = applicationContext,
            settings = settings,
            sessionId = sessionId,
            message = "Full-device VPN routing started",
        )
        reportReady("Full-device VPN routing started")
        startTrafficKeepalive(resolvedSettings)
    }

    private fun stopVpn(preserveFailure: Boolean = false) {
        stopping = true
        runtimeReady = false
        lastTrafficNotificationUpdateMillis = 0L
        stopTrafficKeepalive()
        runCatching { tun2SocksProcessManager.requestStop() }
        val interfaceToClose = vpnInterface
        vpnInterface = null
        runCatching { interfaceToClose?.close() }.onFailure { error ->
            Log.w(Tag, "Failed to close VPN interface", error)
        }
        runCatching {
            val stopped = tun2SocksProcessManager.stop(
                gracePeriodMillis = Tun2proxyStopGracePeriodMillis,
                signalNative = true,
            )
            if (!stopped) {
                Log.w(Tag, "tun2proxy did not stop before VPN interface close")
            }
        }.onFailure { error ->
            Log.w(Tag, "Failed to stop tun2proxy", error)
        }
        runCatching {
            stormDnsProcessManager.stop()
        }.onFailure { error ->
            Log.w(Tag, "Failed to stop DNS engine", error)
        }
        if (!preserveFailure) WhiteDnsRuntimeStateStore.markStopped(
            context = applicationContext,
            mode = WhiteDnsRuntimeStateStore.ModeVpn,
            sessionId = currentSessionId,
            message = "VPN service stopped",
        )
    }

    private fun startTrafficKeepalive(resolvedSettings: ResolvedWhiteDnsSettings) {
        stopTrafficKeepalive()
        if (!resolvedSettings.trafficWarmupEnabled) {
            return
        }
        keepaliveJob = serviceScope.launch {
            var successfulWarmupProbes = 0
            repeat(resolvedSettings.trafficWarmupProbeCount) { index ->
                if (!isActive || stopping) {
                    return@launch
                }
                if (WhiteDnsTrafficWarmup.runProbe(resolvedSettings)) {
                    successfulWarmupProbes += 1
                }
                if (index < resolvedSettings.trafficWarmupProbeCount - 1) {
                    delay(TrafficWarmupProbeSpacingMillis)
                }
            }
            if (successfulWarmupProbes > 0) {
                logInfo("Traffic warmup completed")
            }
            while (isActive && !stopping) {
                delay(resolvedSettings.trafficKeepaliveIntervalSeconds * 1_000L)
                WhiteDnsTrafficWarmup.runProbe(resolvedSettings)
            }
        }
    }

    private fun stopTrafficKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private fun selectVpnSocksHost(listenIp: String): String {
        val host = listenIp.trim().removeSurrounding("[", "]")
        return when (host) {
            "", "0.0.0.0" -> "127.0.0.1"
            "::" -> "::1"
            else -> host
        }
    }

    private fun Builder.configureSplitTunnelApplications(
        splitTunnelMode: String,
        splitTunnelPackages: List<String>,
    ) {
        val routing = vpnApplicationRouting(splitTunnelMode, splitTunnelPackages, packageName)
        routing.allowedPackages.forEach { requireAllowedApplication(it) }
        routing.disallowedPackages.forEach { requireDisallowedApplication(it, "Unable to bypass $it") }
        if (routing.allowedPackages.isNotEmpty()) {
            logInfo("Split tunnel routes ${routing.allowedPackages.size} selected app(s) through the VPN")
        } else {
            logInfo("WhiteDNS app traffic bypasses VPN routing")
            if (splitTunnelMode == WhiteDnsOptions.SplitTunnelModeExclude) {
                logInfo("Split tunnel bypasses ${routing.disallowedPackages.size - 1} selected app(s)")
            }
        }
    }

    private fun Builder.requireAllowedApplication(appPackage: String) {
        runCatching {
            addAllowedApplication(appPackage)
        }.onFailure { error ->
            throw IllegalStateException("Unable to route $appPackage through VPN", error)
        }
    }

    private fun Builder.requireDisallowedApplication(appPackage: String, message: String) {
        runCatching {
            addDisallowedApplication(appPackage)
        }.onFailure { error ->
            throw IllegalStateException(message, error)
        }
    }

    private fun logInfo(message: String) {
        Log.i(Tag, message)
        updateTrafficNotification(message)
        WhiteDnsVpnEvents.log(currentSessionId, message)
        sendVpnEvent(BroadcastTypeLog, message)
    }

    private fun logWarning(message: String) {
        Log.w(Tag, message)
        updateTrafficNotification(message)
        WhiteDnsVpnEvents.log(currentSessionId, message)
        sendVpnEvent(BroadcastTypeLog, message)
    }

    private fun updateTrafficNotification(message: String) {
        if (!runtimeReady) {
            return
        }
        val stats = parseStormDnsTrafficStatsLine(message) ?: return
        val now = System.currentTimeMillis()
        if (now - lastTrafficNotificationUpdateMillis < TrafficNotificationUpdateIntervalMillis) {
            return
        }
        lastTrafficNotificationUpdateMillis = now
        updateForegroundNotification(formatTrafficNotificationText(stats))
    }

    private fun logError(message: String, error: Throwable) {
        Log.e(Tag, message, error)
        reportFailure("$message: ${error.message ?: error::class.java.simpleName}")
    }

    private fun failAndStopVpn(message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(Tag, message)
        } else {
            Log.e(Tag, message, error)
        }
        runtimeReady = false
        lastTrafficNotificationUpdateMillis = 0L
        val failureMessage = if (error == null) {
            message
        } else {
            "$message: ${error.message ?: error::class.java.simpleName}"
        }
        WhiteDnsRuntimeStateStore.markFailed(
            context = applicationContext,
            mode = WhiteDnsRuntimeStateStore.ModeVpn,
            sessionId = currentSessionId,
            message = failureMessage,
        )
        updateForegroundNotification("VPN disconnected")
        reportFailure(failureMessage)
        stopVpn(preserveFailure = true)
        exitForeground()
        stopSelf()
    }

    private fun reportFailure(message: String) {
        WhiteDnsVpnEvents.failed(currentSessionId, message)
        sendVpnEvent(BroadcastTypeFailed, message)
    }

    private fun reportReady(message: String) {
        Log.i(Tag, message)
        WhiteDnsVpnEvents.ready(currentSessionId, message)
        sendVpnEvent(BroadcastTypeReady, message)
    }

    private fun sendVpnEvent(type: String, message: String) {
        sendBroadcast(
            Intent(BroadcastAction)
                .setPackage(packageName)
                .putExtra(BroadcastExtraType, type)
                .putExtra(BroadcastExtraSessionId, currentSessionId)
                .putExtra(BroadcastExtraMessage, message),
        )
    }

    companion object {
        private const val Tag = "WhiteDnsVpnService"
        const val BroadcastAction = "shop.whitedns.client.vpn.EVENT"
        const val BroadcastExtraType = "shop.whitedns.client.vpn.extra.TYPE"
        const val BroadcastExtraSessionId = "shop.whitedns.client.vpn.extra.SESSION_ID"
        const val BroadcastExtraMessage = "shop.whitedns.client.vpn.extra.MESSAGE"
        const val BroadcastTypeLog = "log"
        const val BroadcastTypeReady = "ready"
        const val BroadcastTypeFailed = "failed"
        private const val ActionStart = "shop.whitedns.client.vpn.START"
        private const val ActionStop = "shop.whitedns.client.vpn.STOP"
        private const val ExtraSessionId = "shop.whitedns.client.vpn.extra.SESSION_ID"
        const val TunIpv4Address = "172.19.0.1"
        private const val TunIpv4PrefixLength = 30
        private const val TunIpv6Address = "fd00:19::1"
        private const val TunDnsServer = "172.19.0.2"
        private const val VpnMtu = 1500
        private const val Tun2proxyStopGracePeriodMillis = 5_000L
        private const val Tun2proxyStartupTimeoutMillis = 2_000L
        private const val PreviousRuntimeStopTimeoutMillis = 3_000L
        private const val PreviousRuntimeStopPollMillis = 100L
        private const val TrafficNotificationUpdateIntervalMillis = 1_000L
        private const val TrafficWarmupProbeSpacingMillis = 300L
        private const val NotificationId = 3101
        private const val NotificationChannelId = "whitedns_vpn"

        fun start(
            context: Context,
            sessionId: String,
            serverProfile: StormDnsServerProfile? = null,
            settings: WhiteDnsSettings? = null,
        ) {
            val launchSettings = settings ?: WhiteDnsSettingsStore(context).load()
            val launchServerProfile = serverProfile
                ?: selectServerProfile(launchSettings)
                ?: throw IllegalStateException("No DNS server profile configured")
            RuntimeLaunchRequestStore.save(
                context = context,
                requestId = sessionId,
                serverProfile = launchServerProfile,
                settings = launchSettings,
            )
            val intent = Intent(context, WhiteDnsVpnService::class.java)
                .setAction(ActionStart)
                .putExtra(ExtraSessionId, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun selectServerProfile(settings: WhiteDnsSettings): StormDnsServerProfile? {
            val connectionProfile = settings.selectedConnectionProfile()
            val domain = ServerDomains.normalize(connectionProfile.customServerDomain)
            val encryptionKey = connectionProfile.customServerEncryptionKey.trim()
            if (domain.isBlank() || encryptionKey.isBlank()) {
                return null
            }
            return StormDnsServerProfile(
                id = "custom",
                label = "Custom ${DnsClientEngine.displayName(connectionProfile.engine)} Server",
                domain = domain,
                encryptionKey = encryptionKey,
                encryptionMethod = connectionProfile.customServerEncryptionMethod.coerceIn(0, 5),
                engine = connectionProfile.engine,
                cottenSettings = connectionProfile.cottenSettings,
                soarSettings = connectionProfile.soarSettings,
            )
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, WhiteDnsVpnService::class.java)
                        .setAction(ActionStop),
                )
            }.onFailure { error ->
                Log.w(Tag, "Failed to request VPN service stop", error)
                runCatching {
                    context.stopService(Intent(context, WhiteDnsVpnService::class.java))
                }.onFailure { stopError ->
                    Log.w(Tag, "Failed to stop VPN service", stopError)
                }
            }
        }

    }
}
