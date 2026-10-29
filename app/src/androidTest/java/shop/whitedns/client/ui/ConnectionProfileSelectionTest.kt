package shop.whitedns.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import shop.whitedns.client.model.*

@RunWith(AndroidJUnit4::class)
class ConnectionProfileSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val oldProfile = ConnectionProfile(id = "old", name = "Older connection", customServerDomain = "old.example.com")
    private val newProfile = ConnectionProfile(id = "new", name = "New Soar connection", engine = DnsClientEngine.Soar,
        customServerDomain = "new.example.com")
    private val settings = mutableStateOf(WhiteDnsSettings(connectionProfiles = listOf(oldProfile, newProfile))
        .selectConnectionProfile(newProfile.id))

    private fun show(status: ConnectionStatus = ConnectionStatus.DISCONNECTED) {
        compose.setContent {
            WhiteDnsTheme(languageCode = "en") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionProfilesSettings(
                        settings = settings.value, activeConnectionProfileId = null,
                        connectionStatus = status, serverTestState = ServerTestState(),
                        onServerTestClick = {},
                        onSettingsChange = { settings.value = it },
                        onImportProfiles = { _, _ -> Result.success(Unit) },
                        onRefreshSubscriptions = { Result.success(Unit) },
                    )
                }
            }
        }
    }

    @Test fun tappingOlderCardSelectsItAndCanSwitchBack() {
        show()
        compose.onNodeWithText(oldProfile.name).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(oldProfile.id, settings.value.selectedConnectionProfileId) }
        compose.onNodeWithText(newProfile.name).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(newProfile.id, settings.value.selectedConnectionProfileId) }
    }

    @Test fun useMenuSelectsOlderProfileWithoutOpeningEditor() {
        show()
        compose.onAllNodesWithContentDescription("Connection profile actions")[0].performClick()
        compose.onNodeWithText("Use profile").performClick()
        compose.onNodeWithText(oldProfile.name).assertIsSelected()
        compose.runOnIdle { assertEquals(oldProfile.id, settings.value.selectedConnectionProfileId) }
    }

    @Test fun selectionIsBlockedWhileConnectionIsStarting() {
        show(ConnectionStatus.CONNECTING)
        compose.onNodeWithText(oldProfile.name).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(newProfile.id, settings.value.selectedConnectionProfileId) }
    }
}
