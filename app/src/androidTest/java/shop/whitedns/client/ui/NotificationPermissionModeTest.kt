package shop.whitedns.client.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import shop.whitedns.client.MainActivity
import shop.whitedns.client.model.WhiteDnsSettingsStore

/** Run with POST_NOTIFICATIONS denied and user-set/user-fixed cleared; restore platform flags externally. */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionModeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun waitForPackage(packageName: String) {
        compose.waitUntil(10_000) { automation.rootInActiveWindow?.packageName?.toString() == packageName }
    }

    private fun permissionButton(allow: Boolean): AccessibilityNodeInfo {
        var button: AccessibilityNodeInfo? = null
        compose.waitUntil(10_000) {
            val root = automation.rootInActiveWindow
            val id = if (allow) "permission_allow_button" else "permission_deny_button"
            button = root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/$id")
                ?.firstOrNull { it.isVisibleToUser }
            button != null
        }
        return checkNotNull(button)
    }

    private fun requestNotificationPermission() {
        compose.onNodeWithText(EnglishStrings.bannerEnableNotification).performScrollTo().performClick()
    }

    private fun withDeniedNotifications(block: (ActivityScenario<MainActivity>) -> Unit) {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED)
        val store = WhiteDnsSettingsStore(context)
        val original = store.load()
        val preferences = context.getSharedPreferences(
            MainActivity::class.java.name.removePrefix("${context.packageName}."), Context.MODE_PRIVATE,
        )
        val legacyKey = "notification_permission_requested"
        val hadLegacyKey = preferences.contains(legacyKey)
        val legacyValue = preferences.getBoolean(legacyKey, false)
        // Upgrading must also repair users already stuck with the old requested flag.
        preferences.edit().putBoolean(legacyKey, true).commit()
        store.save(original.copy(languageCode = "en", themeMode = "dark", connectionMode = "vpn",
            batteryOptimizationWarningDismissed = true, fullVpnPerformanceWarningDismissed = true))
        try {
            ActivityScenario.launch(MainActivity::class.java).use(block)
        } finally {
            store.save(original)
            preferences.edit().apply {
                if (hadLegacyKey) putBoolean(legacyKey, legacyValue) else remove(legacyKey)
            }.commit()
        }
    }

    @Test fun cancelledPromptCanBeRetriedAfterRecreationAndThenGranted() = withDeniedNotifications { scenario ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(2) {
            requestNotificationPermission()
            permissionButton(allow = true)
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            waitForPackage(context.packageName)
            compose.onNodeWithTag("notification-notice").assertIsDisplayed()
            scenario.recreate()
        }
        requestNotificationPermission()
        assertTrue(permissionButton(allow = false).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitForPackage(context.packageName)
        compose.onNodeWithTag("notification-notice").assertIsDisplayed()
        requestNotificationPermission()
        assertTrue(permissionButton(allow = true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitForPackage(context.packageName)
        compose.onNodeWithTag("notification-notice").assertDoesNotExist()
        assertTrue(NotificationManagerCompat.from(context).areNotificationsEnabled())
        capture("cancel-retry-granted")
    }

    @Test fun blockedPermissionKeepsExplicitSettingsRecovery() = withDeniedNotifications { scenario ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "pm set-permission-flags ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed",
        )).use { it.readBytes() }
        scenario.recreate()
        requestNotificationPermission()
        waitForPackage(context.packageName)
        compose.onNodeWithTag("notification-notice").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(shop.whitedns.client.R.string.cd_notification_settings))
            .performScrollTo().performClick()
        waitForPackage("com.android.settings")
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        waitForPackage(context.packageName)
        compose.onNodeWithTag("notification-notice").assertIsDisplayed()
        capture("blocked-settings-recovery")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val folder = "/sdcard/Download/whitedns-redesign-qa/mode-notices"
        for (command in listOf("mkdir -p $folder", "screencap -p $folder/native-notifications-$name.png")) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    @Test fun enteringVpnRechecksGrantedNotificationPermission() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED)
        val store = WhiteDnsSettingsStore(context)
        val original = store.load()
        store.save(original.copy(languageCode = "en", themeMode = "dark", connectionMode = "proxy",
            batteryOptimizationWarningDismissed = true, fullVpnPerformanceWarningDismissed = true))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    assertFalse(ViewModelProvider(it)[WhiteDnsViewModel::class.java].uiState.notificationsEnabled)
                }
                compose.onNodeWithText(EnglishStrings.connectionModeVpn).performScrollTo().performClick()
                compose.onNodeWithTag("notification-notice").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(EnglishStrings.bannerEnableNotification).assertIsEnabled()
                capture("denied")
                compose.onNodeWithText(EnglishStrings.connectionModeProxy).performScrollTo().performClick()
                // Model a platform grant landing after the permission callback/onResume refresh.
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
                )).use { it.readBytes() }
                assertTrue("The platform must confirm the notification grant",
                    NotificationManagerCompat.from(context).areNotificationsEnabled())
                repeat(3) {
                    compose.onNodeWithText(EnglishStrings.connectionModeVpn).performScrollTo().performClick()
                    compose.onNodeWithTag("notification-notice").assertDoesNotExist()
                    scenario.onActivity {
                        assertTrue(ViewModelProvider(it)[WhiteDnsViewModel::class.java].uiState.notificationsEnabled)
                    }
                    if (it == 2) capture("granted-mode-cycle")
                    compose.onNodeWithText(EnglishStrings.connectionModeProxy).performScrollTo().performClick()
                }
            }
        } finally {
            store.save(original)
        }
    }
}
