package shop.whitedns.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import shop.whitedns.client.model.WhiteDnsOptions

class WhiteDnsVpnEventsTest {
    @Test
    fun eventsCarrySessionId() {
        val receivedEvents = mutableListOf<WhiteDnsVpnEvent>()
        val listener: (WhiteDnsVpnEvent) -> Unit = { event ->
            receivedEvents += event
        }

        WhiteDnsVpnEvents.addListener(listener)
        try {
            WhiteDnsVpnEvents.log("session-b", "log")
            WhiteDnsVpnEvents.ready("session-b", "ready")
            WhiteDnsVpnEvents.failed("session-b", "failed")
        } finally {
            WhiteDnsVpnEvents.removeListener(listener)
        }

        assertEquals(
            listOf(
                WhiteDnsVpnEvent.Log("session-b", "log"),
                WhiteDnsVpnEvent.Ready("session-b", "ready"),
                WhiteDnsVpnEvent.Failed("session-b", "failed"),
            ),
            receivedEvents,
        )
    }
}

class VpnRunnerRecoveryTest {
    @Test fun nativeFailureCannotBecomeReady() = runBlocking {
        var time = 0L
        assertFalse(awaitTun2proxyRunning(250, { time < 40 }, { time }, { time += 20 }))
    }

    @Test fun neverEnteredNativeCannotBecomeReady() = runBlocking {
        var time = 0L
        assertFalse(awaitTun2proxyRunning(250, { false }, { time }, { time += 20 }))
    }

    @Test fun readyRequiresStableLiveNativeRunner() = runBlocking {
        var time = 0L
        assertTrue(awaitTun2proxyRunning(250, { true }, { time }, { time += 20 }))
        assertEquals(100, time)
    }

    @Test fun briefStartupDoesNotCountTowardLaterReadiness() = runBlocking {
        var time = 0L
        assertTrue(awaitTun2proxyRunning(300, { time < 40 || time >= 120 }, { time }, { time += 20 }))
        assertEquals(220, time)
    }

    @Test fun cancellationIsNotConvertedToReadinessFailure() = runBlocking {
        var time = 0L
        try {
            awaitTun2proxyRunning(250, { true }, { time }, { throw CancellationException("stop") })
            fail("Cancellation must propagate to VPN teardown")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
    }

    @Test fun fullAndExcludedModesAlwaysBypassOwnEngineUid() {
        assertEquals(listOf("ours"), vpnApplicationRouting("all", listOf("other"), "ours").disallowedPackages)
        val excluded = vpnApplicationRouting(WhiteDnsOptions.SplitTunnelModeExclude, listOf("ours", " other ", "other"), "ours")
        assertEquals(listOf("ours", "other"), excluded.disallowedPackages)
        assertTrue(excluded.allowedPackages.isEmpty())
    }

    @Test fun includeModeKeepsOwnEngineOutsideVpnWithoutMixingLists() {
        val included = vpnApplicationRouting(WhiteDnsOptions.SplitTunnelModeInclude, listOf("ours", " other "), "ours")
        assertEquals(listOf("other"), included.allowedPackages)
        assertTrue(included.disallowedPackages.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyIncludeDoesNotSilentlyRouteEntireDevice() {
        vpnApplicationRouting(WhiteDnsOptions.SplitTunnelModeInclude, listOf("", "ours"), "ours")
    }

    @Test fun cliPreservesLongUdpLifetimeAndBoundsCapacityWithoutFatalOverflow() {
        val cli = buildTun2proxyCliArgs("socks5://u:p%40ss@[::1]:1080", 43, false, Tun2proxySettings())
        assertTrue(cli.contains("--tun-fd 43 --close-fd-on-drop false"))
        assertTrue(cli.contains("--udp-timeout 120"))
        assertTrue(cli.contains("--max-sessions 1024"))
        assertTrue(cli.contains("--ipv6-enabled"))
        assertTrue(cli.contains("socks5://u:p%40ss@[::1]:1080"))
        assertFalse(cli.contains("--exit-on-fatal-error"))
    }
}
