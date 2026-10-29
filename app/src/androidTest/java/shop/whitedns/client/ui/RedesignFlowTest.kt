package shop.whitedns.client.ui

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import shop.whitedns.client.model.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Real production composables on an emulator; network/runtime states are explicit test fixtures. */
@RunWith(AndroidJUnit4::class)
class RedesignFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args = InstrumentationRegistry.getArguments()
    private val theme = args.getString("redesignTheme") ?: "light"
    private val language = args.getString("redesignLanguage") ?: "en"
    private val device = args.getString("redesignDevice") ?: "phone"
    private val strings get() = if (language == "fa") PersianStrings else EnglishStrings
    private val connection = ConnectionProfile(
        id = "review-connection", name = "Review connection", customServerDomain = "review.example.com",
        customServerEncryptionKey = "00112233445566778899aabbccddeeff", resolverProfileId = "review-resolvers",
    )
    private val resolver = ResolverProfile(id = "review-resolvers", name = "Review resolvers", resolverText = "1.1.1.1\n8.8.8.8")
    private val ui = mutableStateOf(WhiteDnsUiState(settings = WhiteDnsSettings(
        themeMode = theme, languageCode = language,
        connectionProfiles = listOf(connection), resolverProfiles = listOf(resolver),
        resolverText = resolver.resolverText, selectedResolverProfileId = resolver.id,
        batteryOptimizationWarningDismissed = true,
    ).selectConnectionProfile(connection.id)))

    private var batteryRequests = 0
    private var notificationRequests = 0
    private var notificationSettingsRequests = 0

    private fun show() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            WhiteDnsTheme(themeMode = ui.value.settings.themeMode, languageCode = ui.value.settings.languageCode) {
                WhiteDnsScreen(
                    uiState = ui.value,
                    onBatteryOptimizationClick = { batteryRequests++ }, onNotificationPermissionClick = { notificationRequests++ },
                    onNotificationSettingsClick = { notificationSettingsRequests++ },
                    onConnectClick = { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTING) },
                    onScanFileSelected = {}, onScanDefaultListSelected = {}, onScanStartClick = {},
                    onScanConnectionProfileChange = {}, onScanWorkerCountChange = {},
                    onScanStopClick = {}, onScanResumeClick = {}, onServerTestClick = {},
                    onSettingsChange = { ui.value = ui.value.copy(settings = it) },
                    onImportProfiles = { _, _ -> Result.failure(IllegalArgumentException("Review import failed. Check the profile link.")) },
                    onRefreshSubscriptions = { Result.success(Unit) },
                )
            }
        }
    }

    private fun reveal(node: SemanticsNodeInteraction) {
        val target = node.fetchSemanticsNode()
        var ancestor = target.parent
        var scrollable = false
        var insidePager = target.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
        var outerVerticalId: Int? = null
        while (ancestor != null) {
            if (ancestor.config.contains(SemanticsActions.ScrollBy)) scrollable = true
            if (ancestor.config.contains(SemanticsProperties.HorizontalScrollAxisRange)) insidePager = true
            if (insidePager && ancestor.config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
                outerVerticalId = ancestor.id
                break
            }
            ancestor = ancestor.parent
        }
        if (outerVerticalId != null) {
            val container = compose.onNode(SemanticsMatcher("Outer vertical scroll") { it.id == outerVerticalId }, useUnmergedTree = true)
            val delta = target.positionInRoot.y + target.size.height / 2f - container.fetchSemanticsNode().boundsInRoot.center.y
            container.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        } else if (scrollable) node.performScrollTo()
    }
    private fun press(node: SemanticsNodeInteraction) { reveal(node); node.performClick() }
    private fun click(text: String) {
        val profileActions = setOf(strings.profileBtnCreate, strings.profileBtnImport, strings.profileBtnScanQr,
            strings.profileBtnSaveCurrent, strings.profileBtnExportAll)
        if (text in profileActions && compose.onAllNodesWithText(text, ignoreCase = true).fetchSemanticsNodes().isEmpty()) {
            press(compose.onNodeWithTag("profile-add-options"))
        }
        press(compose.onAllNodesWithText(text, ignoreCase = true).onFirst())
    }
    private fun described(text: String) = press(compose.onNodeWithContentDescription(text))
    private fun tab(text: String) {
        val target = compose.onAllNodes(
            (hasText(text) or hasContentDescription(text)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                !hasAnyAncestor(hasTestTag("setup-card")) and
                !hasAnySibling(hasText(strings.profileTabResolver) or hasText(strings.profileTabSetting)),
        ).onLast()
        press(target)
        target.assertIsSelected()
    }
    private fun profileSection(text: String) = press(compose.onNode(
        hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            hasAnySibling(hasText(strings.profileTabResolver) or hasText(strings.profileTabSetting)),
    ))
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().assertIsDisplayed()
        android.os.SystemClock.sleep(120)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = "/sdcard/Download/whitedns-redesign-qa/final"
        val scale = instrumentation.targetContext.resources.configuration.fontScale
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        shell("mkdir -p $folder")
        shell("screencap -p $folder/$device-$theme-$language-$scale-$name.png")
    }

    private fun textLayout(text: String, ancestorTag: String? = null): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val matcher = if (ancestorTag == null) hasText(text) else hasText(text) and hasAnyAncestor(hasTestTag(ancestorTag))
        compose.onAllNodes(matcher, useUnmergedTree = true).onFirst()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun assertTextFits(text: String) {
        val layout = textLayout(text)
        // A cached paragraph can retain the wider parent width; check the actual laid-out lines.
        assertFalse("Text height clipped: $text", layout.didOverflowHeight)
        assertFalse("Text lines omitted: $text", layout.multiParagraph.didExceedMaxLines)
        repeat(layout.lineCount) { line ->
            assertFalse("Text ellipsized: $text", layout.isLineEllipsized(line))
            assertTrue("Text line clipped: $text", layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
        }
    }

    private fun assertFieldCopyAtLocaleEdge(text: String, ancestorTag: String? = null) {
        val layout = textLayout(text, ancestorTag)
        repeat(layout.lineCount) { line ->
            if (language == "fa") {
                assertEquals("Farsi field copy must align right: $text", layout.size.width.toFloat(), layout.getLineRight(line), 1f)
            } else {
                assertEquals("English field copy must align left: $text", 0f, layout.getLineLeft(line), 1f)
            }
        }
    }

    @Test fun profileAddMenuKeepsHeaderPlacementAndActions() {
        show()
        tab(strings.tabProfiles)
        val original = ui.value.settings
        val sections = listOf(strings.profileTabConnection, strings.profileTabResolver, strings.profileTabSetting)
        sections.forEachIndexed { index, section ->
            profileSection(section)
            val add = compose.onNodeWithTag("profile-add-options")
            reveal(add)
            add.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsEnabled()
            val title = compose.onNode(hasText(strings.tabProfiles) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .fetchSemanticsNode().boundsInRoot
            val button = add.fetchSemanticsNode().boundsInRoot
            assertEquals("Add must align beside the Profiles title", title.center.y, button.center.y, 1f)
            assertTrue("Header must follow locale direction", if (language == "fa") button.right <= title.left else button.left >= title.right)
            assertTextFits(if (language == "fa") "+ افزودن" else "+ Add")
            compose.onNodeWithText(strings.profileBtnCreate).assertDoesNotExist()
            capture("profiles-header-$index")
            press(add)
            val options = when (index) {
                0 -> listOf(strings.profileBtnCreate, strings.profileBtnImport, strings.profileBtnScanQr)
                1 -> listOf(strings.profileBtnCreate, strings.profileBtnSaveCurrent, strings.profileBtnExportAll)
                else -> listOf(strings.profileBtnCreate, strings.profileBtnImport)
            }
            options.forEach { compose.onNodeWithText(it).assertIsEnabled(); assertTextFits(it) }
            capture("profiles-add-menu-$index")
            click(strings.profileBtnCreate)
            compose.onAllNodes(hasSetTextAction()).onFirst().assertExists()
            capture("profiles-create-$index")
            click(strings.btnCancel)
            compose.runOnIdle { assertEquals("Cancel must preserve all saved settings", original, ui.value.settings) }
            if (index != 1) {
                click(strings.profileBtnImport)
                capture("profiles-import-$index")
                Espresso.pressBack()
            } else {
                click(strings.profileBtnSaveCurrent)
                capture("profiles-save-current")
                click(strings.btnCancel)
            }
            press(add)
            Espresso.pressBack()
            compose.onNodeWithTag("profile-add-menu").assertDoesNotExist()
        }
        profileSection(strings.profileTabConnection)
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTING) }
        compose.onNodeWithTag("profile-add-options").assertIsNotEnabled()
        profileSection(strings.profileTabResolver)
        press(compose.onNodeWithTag("profile-add-options"))
        compose.onNodeWithText(strings.profileBtnCreate).assertIsNotEnabled()
        compose.onNodeWithText(strings.profileBtnSaveCurrent).assertIsNotEnabled()
        compose.onNodeWithText(strings.profileBtnExportAll).assertIsEnabled()
        capture("profiles-add-menu-starting")
        Espresso.pressBack()
        profileSection(strings.profileTabSetting)
        compose.onNodeWithTag("profile-add-options").assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTED) }
        compose.onNodeWithTag("profile-add-options").assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.DISCONNECTED) }
        compose.onNodeWithTag("profile-add-options").assertIsEnabled()
    }

    @Test fun homePanelsKeepCompactHeadersAndCloseOnConnectReselect() {
        show()
        val before = ui.value.settings
        val titles = listOf(strings.selectorConnectionProfiles, strings.selectorResolverProfiles, strings.selectorSettingProfiles)
        val placeholders = listOf(strings.homeSelectorSearchConnections, strings.homeSelectorSearchResolvers, strings.homeSelectorSearchSettings)
        val selectedNames = listOf(connection.name, resolver.name, strings.setupDefaultAdvanced)
        for ((index, kind) in listOf("connection", "resolver", "setting").withIndex()) {
            press(compose.onNodeWithTag("home-option-$kind"))
            val header = compose.onNodeWithTag("home-selector-header").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("connect-content").fetchSemanticsNode().boundsInRoot
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            assertEquals("Header must sit directly below the app bar", 12f, (header.top - content.top) / density, 1f)
            assertTextFits(titles[index])
            assertTextFits(placeholders[index])
            assertFieldCopyAtLocaleEdge(selectedNames[index], "home-selector")
            assertEquals(18.sp, textLayout(titles[index]).layoutInput.style.fontSize)
            val close = compose.onNodeWithContentDescription(strings.cdCloseSelector)
            close.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val titleBounds = compose.onNodeWithText(titles[index]).fetchSemanticsNode().boundsInRoot
            val closeBounds = close.fetchSemanticsNode().boundsInRoot
            assertTrue("Close must follow locale direction", if (language == "fa") closeBounds.right <= titleBounds.left else closeBounds.left >= titleBounds.right)
            compose.onNodeWithTag("home-options").assertDoesNotExist()
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and isSelected()).assertExists()
            capture("panel-$kind")
            if (index == 0) {
                compose.onNode(hasSetTextAction()).performTextReplacement("no-such-profile")
                val noMatches = if (language == "fa") "هیچ پروفایلی با جست‌وجوی شما مطابقت ندارد." else "No profiles match your search."
                assertTextFits(noMatches)
                reveal(compose.onNodeWithText(noMatches))
                capture("panel-search-empty")
                click(if (language == "fa") "پاک کردن جست‌وجو" else "Clear search")
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                described(strings.cdCloseSelector)
            } else if (index == 1) {
                Espresso.pressBack()
            } else {
                // A real double tap must dismiss the panel and remain harmless on the restored home.
                compose.onNode((hasText(strings.tabConnect) or hasContentDescription(strings.tabConnect)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and isSelected())
                    .performTouchInput { doubleClick() }
            }
            compose.onNodeWithTag("home-selector").assertDoesNotExist()
            compose.runOnIdle { assertEquals(before, ui.value.settings) }
        }
        press(compose.onNodeWithTag("home-option-connection"))
        compose.onNode(hasSetTextAction()).performClick().performTextInput("draft search")
        tab(strings.tabConnect)
        compose.onNodeWithTag("home-selector").assertDoesNotExist()
        press(compose.onNodeWithTag("home-option-connection"))
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        tab(strings.tabProfiles)
        tab(strings.tabConnect)
        compose.onNodeWithTag("home-selector").assertDoesNotExist()
        compose.runOnIdle { assertEquals(before, ui.value.settings) }
        capture("panel-dismissed-home")
    }

    @Test fun homeEditorsKeepCloseAndNavigationReachableWithoutSavingDrafts() {
        show()
        val before = ui.value.settings
        val kinds = listOf("connection", "resolver", "setting")
        val labels = listOf(strings.sectionConnection, strings.sectionResolver, strings.homeSelectorSettingLabel)
        for ((index, kind) in kinds.withIndex()) {
            press(compose.onNodeWithTag("home-option-$kind"))
            described("${strings.cdEditPrefix} ${labels[index]}")
            compose.onNodeWithContentDescription(strings.btnClose).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("home-options").assertDoesNotExist()
            reveal(compose.onAllNodes(hasSetTextAction())[0])
            compose.onAllNodes(hasSetTextAction())[0].performClick().performTextReplacement("Unsaved draft")
            compose.waitForIdle()
            android.os.SystemClock.sleep(400)
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("connect-content").fetchSemanticsNode().boundsInRoot
            reveal(compose.onNodeWithText(strings.btnCancel))
            capture("panel-edit-$kind")
            val cancel = compose.onNodeWithText(strings.btnCancel).fetchSemanticsNode().boundsInRoot
            assertTrue("Editor actions must stay visible above the keyboard: cancel=$cancel viewport=$viewport",
                cancel.height > 0 && cancel.top >= viewport.top && cancel.bottom <= viewport.bottom + 1f)
            compose.onNode((hasText(strings.tabConnect) or hasContentDescription(strings.tabConnect)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and isSelected())
                .performTouchInput { click() }
            compose.onNodeWithTag("dialog-header").assertDoesNotExist()
            compose.runOnIdle { assertEquals(before, ui.value.settings) }
            press(compose.onNodeWithTag("home-option-$kind"))
            described("${strings.cdEditPrefix} ${labels[index]}")
            described(strings.btnClose)
            compose.onNodeWithTag("dialog-header").assertDoesNotExist()
            compose.runOnIdle { assertEquals(before, ui.value.settings) }
        }
        press(compose.onNodeWithTag("home-option-setting"))
        described("${strings.cdEditPrefix} ${strings.homeSelectorSettingLabel}")
        tab(strings.tabLogs)
        tab(strings.tabConnect)
        compose.onNodeWithTag("dialog-header").assertDoesNotExist()
        compose.runOnIdle { assertEquals(before, ui.value.settings) }
        for ((menu, name) in listOf(strings.menuAppSettings to "app-settings", strings.menuDonate to "support")) {
            described(strings.cdAppMenu)
            capture("panel-app-menu-$name")
            click(menu)
            compose.onNodeWithContentDescription(strings.btnClose).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            capture("panel-$name")
            tab(strings.tabConnect)
            compose.onNodeWithTag("dialog-header").assertDoesNotExist()
            compose.runOnIdle { assertEquals(before, ui.value.settings) }
        }
    }

    @Test fun homeSecondaryPanelsKeepNavigationAndCancellationSafe() {
        ui.value = ui.value.copy(settings = ui.value.settings.copy(
            connectionMode = "vpn", splitTunnelMode = WhiteDnsOptions.SplitTunnelModeInclude))
        show()
        val before = ui.value.settings
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.splitTunnelSelectApps).fetchSemanticsNodes()
                .any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        click(strings.splitTunnelSelectApps)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).onFirst().performClick()
        capture("panel-app-routing")
        tab(strings.tabConnect)
        compose.onNodeWithTag("dialog-header").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(before, ui.value.settings)
            ui.value = ui.value.copy(settings = ui.value.settings.copy(connectionMode = "proxy"), autoTuneTrialResults = listOf(
                AutoTuneTrialResult(WhiteDnsParallelTest.whiteDnsConfigId("iran-average"), "Review result", "127.0.0.1", 10808,
                    status = "ready", speedBytesPerSecond = 65536, pingMillis = 42, selected = true)))
        }
        val beforeSaveAs = ui.value.settings
        click(strings.saveSettingAs)
        capture("panel-save-result")
        tab(strings.tabConnect)
        compose.onNodeWithTag("dialog-header").assertDoesNotExist()
        compose.runOnIdle { assertEquals(beforeSaveAs, ui.value.settings) }
        click(strings.downloadTomlBtn)
        capture("panel-export")
        tab(strings.tabConnect)
        compose.onNodeWithTag("dialog-header").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(beforeSaveAs, ui.value.settings)
            ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTED,
                resolverRuntimeState = ResolverRuntimeState(activeResolvers = listOf("1.1.1.1"), validResolvers = listOf("1.1.1.1", "8.8.8.8")))
        }
        for ((label, name) in listOf(strings.resolverActiveResolvers to "active", strings.resolverValidResolvers to "valid")) {
            click(label)
            compose.onNodeWithContentDescription(strings.btnClose).assertHeightIsAtLeast(48.dp)
            capture("panel-runtime-$name")
            tab(strings.tabConnect)
            compose.onNodeWithTag("dialog-header").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(ConnectionStatus.CONNECTED, ui.value.connectionStatus)
                assertEquals(beforeSaveAs, ui.value.settings)
            }
        }
    }

    @Test fun homeOptionRowsKeepSelectionEditingAndTuningReachable() {
        val another = connection.copy(id = "review-alternative", name = "Another connection", customServerDomain = "another.invalid")
        ui.value = ui.value.copy(settings = ui.value.settings.upsertConnectionProfile(another).selectConnectionProfile(connection.id))
        show()
        val tags = listOf("connection", "resolver", "setting", "tuning")
        for (tag in tags) compose.onNodeWithTag("home-option-$tag").assertHeightIsAtLeast(48.dp)
        for (label in listOf(strings.sectionConnection, strings.sectionResolver, strings.homeSelectorSettingLabel, strings.groupNetworkTuning)) {
            assertTextFits(label.uppercase(java.util.Locale.ROOT))
        }
        reveal(compose.onNodeWithTag("home-options"))
        capture("home-options-overview")
        press(compose.onNodeWithTag("home-option-connection"))
        capture("home-connection-selector")
        click(another.name)
        compose.runOnIdle { assertEquals(another.id, ui.value.settings.selectedConnectionProfile().id) }
        press(compose.onNodeWithTag("home-option-connection"))
        described("${strings.cdEditPrefix} ${strings.sectionConnection}")
        capture("home-connection-edit")
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Edited from home")
        Espresso.closeSoftKeyboard()
        click(strings.btnSave)
        compose.runOnIdle {
            assertEquals("Edited from home", ui.value.settings.selectedConnectionProfile().name)
            assertEquals("another.invalid", ui.value.settings.selectedConnectionProfile().customServerDomain)
        }
        press(compose.onNodeWithTag("home-option-resolver"))
        capture("home-resolver-selector")
        described("${strings.cdEditPrefix} ${strings.sectionResolver}")
        capture("home-resolver-edit")
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Home resolvers")
        Espresso.closeSoftKeyboard()
        click(strings.btnSave)
        compose.runOnIdle {
            assertEquals("Home resolvers", ui.value.settings.selectedResolverProfile()?.name)
            assertEquals(resolver.resolverText, ui.value.settings.selectedResolverProfile()?.resolverText)
        }
        val beforeSettingEdit = ui.value.settings
        press(compose.onNodeWithTag("home-option-setting"))
        capture("home-setting-selector")
        described("${strings.cdEditPrefix} ${strings.homeSelectorSettingLabel}")
        capture("home-setting-edit")
        click(strings.btnCancel)
        compose.runOnIdle { assertEquals(beforeSettingEdit, ui.value.settings) }
        press(compose.onNodeWithTag("home-option-tuning"))
        reveal(compose.onNodeWithText(strings.parallelTest))
        capture("home-tuning-expanded")
        click(strings.parallelTest)
        compose.runOnIdle { assertTrue(ui.value.settings.autoTuneEnabled) }
        compose.onNodeWithTag("home-option-setting").assert(hasText(strings.homeSelectorUnsavedChanges, substring = true))
        press(compose.onNodeWithTag("home-option-tuning"))
        capture("home-tuning-collapsed")
        val settingRow = compose.onNodeWithTag("home-option-setting")
        reveal(settingRow)
        settingRow.performTouchInput { longClick() }
        compose.onNodeWithText(strings.btnCancel).assertExists()
        click(strings.btnCancel)
        compose.runOnIdle {
            assertTrue(ui.value.settings.autoTuneEnabled)
            ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTING)
        }
        for (tag in tags.take(3)) compose.onNodeWithTag("home-option-$tag").assertIsNotEnabled()
        reveal(compose.onNodeWithTag("home-options"))
        capture("home-options-starting")
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTED) }
        for (tag in tags.take(3)) compose.onNodeWithTag("home-option-$tag").assertDoesNotExist()
        press(compose.onNodeWithTag("home-option-tuning"))
        val parallelSwitch = compose.onNode(hasText(strings.parallelTest) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        parallelSwitch.assertIsNotEnabled()
        reveal(parallelSwitch)
        capture("home-options-connected")
    }

    @Test fun homeOptionRowsKeepLongNamesAndRtlCopyComplete() {
        val longConnection = if (language == "fa") "اتصال ذخیره‌شده برای شبکهٔ خانه و محل کار با نام کامل" else "Saved connection for home and work with a complete descriptive name"
        val longResolver = if (language == "fa") "فهرست ریزالورهای ذخیره‌شده برای اتصال شبکهٔ خانه و محل کار" else "Saved resolver list for the home and work network connection"
        ui.value = ui.value.copy(settings = ui.value.settings.copy(
            connectionProfiles = listOf(connection.copy(name = longConnection)),
            resolverProfiles = listOf(resolver.copy(name = longResolver))))
        show()
        assertTextFits(longConnection)
        assertTextFits(longResolver)
        reveal(compose.onNodeWithTag("home-option-connection"))
        capture("home-options-long-connection")
        press(compose.onNodeWithTag("home-option-connection"))
        assertTextFits(longConnection)
        assertTextFits(connection.customServerDomain)
        capture("home-options-long-selector")
        described(strings.cdCloseSelector)
        reveal(compose.onNodeWithTag("home-option-resolver"))
        capture("home-options-long-resolver")
    }

    @Test fun permissionNoticesKeepReadableCopyAndWorkingActions() {
        ui.value = ui.value.copy(
            settings = ui.value.settings.copy(connectionMode = "vpn", batteryOptimizationWarningDismissed = false),
            notificationsEnabled = false, batteryOptimizationIgnored = false,
        )
        show()
        for (text in listOf(strings.bannerNotificationBlockedTitle, strings.bannerNotificationBlockedBody, strings.bannerEnableNotification)) {
            assertTextFits(text)
        }
        reveal(compose.onNodeWithText(strings.bannerNotificationBlockedBody))
        capture("notice-notification")
        compose.onNodeWithText(strings.bannerEnableNotification).assertHeightIsAtLeast(48.dp)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(language))
        }
        val settingsDescription = context.createConfigurationContext(configuration)
            .getString(shop.whitedns.client.R.string.cd_notification_settings)
        compose.onNodeWithContentDescription(settingsDescription).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        described(settingsDescription)
        compose.runOnIdle {
            assertEquals(1, notificationSettingsRequests)
            assertEquals(0, notificationRequests)
        }
        click(strings.bannerEnableNotification)
        compose.runOnIdle {
            assertEquals(1, notificationRequests)
            ui.value = ui.value.copy(notificationsEnabled = true)
        }
        compose.onNodeWithText(strings.bannerNotificationBlockedTitle).assertDoesNotExist()
        for (text in listOf(strings.bannerBatteryTitle, strings.bannerBatteryBody, strings.bannerAllowBackground)) {
            assertTextFits(text)
        }
        reveal(compose.onNodeWithText(strings.bannerBatteryBody))
        capture("notice-battery")
        compose.onNodeWithText(strings.bannerAllowBackground).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(strings.cdDismissBatteryWarning).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        click(strings.bannerAllowBackground)
        compose.runOnIdle { assertEquals(1, batteryRequests) }
        described(strings.cdDismissBatteryWarning)
        compose.runOnIdle { assertTrue(ui.value.settings.batteryOptimizationWarningDismissed) }
        compose.onNodeWithText(strings.bannerBatteryTitle).assertDoesNotExist()
        for (text in listOf(strings.bannerFullVpnWarningTitle, strings.bannerFullVpnWarningBody)) assertTextFits(text)
        reveal(compose.onNodeWithText(strings.bannerFullVpnWarningBody))
        capture("notice-vpn-performance")
        described(strings.cdDismissVpnWarning)
        compose.runOnIdle { assertTrue(ui.value.settings.fullVpnPerformanceWarningDismissed) }
        compose.onNodeWithText(strings.bannerFullVpnWarningTitle).assertDoesNotExist()
    }

    @Test fun dismissedWarningsStayDismissedAcrossVpnProxyModeChanges() {
        ui.value = ui.value.copy(
            settings = ui.value.settings.copy(connectionMode = "proxy", batteryOptimizationWarningDismissed = false),
            notificationsEnabled = false, batteryOptimizationIgnored = false,
        )
        show()
        click(strings.connectionModeVpn)
        click(strings.bannerEnableNotification)
        click(strings.bannerAllowBackground)
        compose.runOnIdle {
            assertEquals(1, notificationRequests)
            assertEquals(1, batteryRequests)
            ui.value = ui.value.copy(notificationsEnabled = true, batteryOptimizationIgnored = true)
        }
        compose.onNodeWithTag("notification-notice").assertDoesNotExist()
        compose.onNodeWithTag("battery-notice").assertDoesNotExist()
        click(strings.connectionModeProxy)
        click(strings.connectionModeVpn)
        described(strings.cdDismissVpnWarning)
        compose.runOnIdle { assertTrue(ui.value.settings.fullVpnPerformanceWarningDismissed) }
        compose.onNodeWithTag("vpn-performance-notice").assertDoesNotExist()
        repeat(3) {
            click(strings.connectionModeProxy)
            click(strings.connectionModeVpn)
            compose.onNodeWithTag("notification-notice").assertDoesNotExist()
            compose.onNodeWithTag("battery-notice").assertDoesNotExist()
            compose.onNodeWithTag("vpn-performance-notice").assertDoesNotExist()
            compose.runOnIdle { assertTrue(ui.value.settings.fullVpnPerformanceWarningDismissed) }
        }
        capture("notices-granted-mode-cycle")
        compose.runOnIdle { ui.value = ui.value.copy(batteryOptimizationIgnored = false) }
        described(strings.cdDismissBatteryWarning)
        repeat(3) {
            click(strings.connectionModeProxy)
            click(strings.connectionModeVpn)
            compose.onNodeWithTag("battery-notice").assertDoesNotExist()
            compose.onNodeWithTag("vpn-performance-notice").assertDoesNotExist()
            compose.runOnIdle { assertTrue(ui.value.settings.batteryOptimizationWarningDismissed) }
        }
        capture("notices-dismissed-mode-cycle")
    }

    @Test fun scannerWarningsAndFailureFeedbackKeepCompleteText() {
        val failure = if (language == "fa")
            "اتصال به سرور برقرار نشد. آدرس سرور و تنظیمات شبکه را بررسی کنید و دوباره تلاش کنید."
        else "The server could not be reached. Check the server address and network settings, then try again."
        ui.value = ui.value.copy(settings = ui.value.settings.copy(connectionProfiles = listOf(
            connection.copy(customServerDomain = ""))), scanState = WhiteDnsScanState(
            status = WhiteDnsScanStatus.Failed, workerFailures = listOf(failure)))
        show()
        tab(strings.tabScan)
        val missing = "${connection.name} ${strings.scanProfileNeedsServer}"
        assertTextFits(missing)
        reveal(compose.onNodeWithText(missing))
        capture("notice-scan-configuration")
        assertTextFits(failure)
        reveal(compose.onAllNodesWithText(failure).onLast())
        capture("notice-scan-failure")
        compose.onNodeWithText(strings.scanBtnStart).assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTED,
            connectionVerification = ConnectionVerificationState(status = ConnectionVerificationStatus.Failed, message = failure)) }
        tab(strings.tabConnect)
        reveal(compose.onNodeWithText(failure))
        capture("notice-verification-failure")
        assertTextFits(failure)
    }

    @Test fun setupSliderCreatesAndSelectsANewConnectionFromFirstUse() {
        ui.value = ui.value.copy(settings = ui.value.settings.copy(
            connectionProfiles = listOf(ConnectionProfile.defaultProfile().copy(resolverProfileId = resolver.id)))
            .selectConnectionProfile(ConnectionProfile.DefaultId))
        show()
        compose.onNodeWithTag("setup-step-0").assertIsSelected()
        capture("setup-new-connection-overview")
        press(compose.onNodeWithTag("setup-action-0"))
        click(strings.btnCancel)
        compose.runOnIdle { assertEquals(1, ui.value.settings.normalizedConnectionProfiles().size) }
        press(compose.onNodeWithTag("setup-action-0"))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Created from setup")
        fields[1].performTextInput("setup-created.invalid")
        fields[2].performTextInput("00112233445566778899aabbccddeeff")
        Espresso.closeSoftKeyboard()
        click(strings.btnSave)
        compose.runOnIdle {
            val selected = ui.value.settings.selectedConnectionProfile()
            assertTrue("A newly saved setup profile must be selected", selected.id != ConnectionProfile.DefaultId)
            assertEquals("Created from setup", selected.name)
            assertEquals("setup-created.invalid", selected.customServerDomain)
            assertEquals(2, ui.value.settings.normalizedConnectionProfiles().size)
        }
        compose.onNodeWithTag("setup-card").assertDoesNotExist()
        compose.onNodeWithTag("connect-control").assertIsEnabled()
        capture("setup-new-connection-ready")
    }

    @Test fun setupSliderFollowsSavedProgressAndSupportsSwipeAndCancellation() {
        ui.value = ui.value.copy(settings = ui.value.settings.copy(connectionProfiles = listOf(
            connection.copy(customServerDomain = "", customServerEncryptionKey = ""))))
        show()
        compose.onNodeWithTag("setup-step-0").assertIsSelected()
        compose.onNodeWithTag("setup-step-1").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, if (language == "fa") "تکمیل‌شده" else "Complete"))
        val orb = compose.onNodeWithTag("connect-control").fetchSemanticsNode()
        val card = compose.onNodeWithTag("setup-card").fetchSemanticsNode()
        assertTrue("Setup must follow the Connect control", card.positionInRoot.y >= orb.positionInRoot.y + orb.size.height - 1f)
        capture("setup-overview")
        press(compose.onNodeWithTag("setup-action-0"))
        click(strings.btnCancel)
        compose.runOnIdle { assertTrue(ui.value.settings.selectedConnectionProfile().customServerDomain.isBlank()) }

        press(compose.onNodeWithTag("setup-step-1"))
        compose.onNodeWithTag("setup-step-1").assertIsSelected()
        capture("setup-resolvers-complete")
        val slider = compose.onNodeWithTag("setup-slider")
        reveal(slider)
        slider.performTouchInput {
            if (language == "fa") swipeRight() else swipeLeft()
        }
        compose.onNodeWithTag("setup-step-2").assertIsSelected()
        capture("setup-review-incomplete")
        press(compose.onNodeWithTag("setup-action-2"))
        compose.onNodeWithTag("setup-step-0").assertIsSelected()
        press(compose.onNodeWithTag("setup-action-0"))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[1].performTextInput("saved.example.com")
        fields[2].performTextInput("00112233445566778899aabbccddeeff")
        Espresso.closeSoftKeyboard()
        click(strings.btnSave)
        compose.onNodeWithTag("setup-card").assertDoesNotExist()
        compose.onNodeWithTag("connect-control").assertIsEnabled()
        compose.runOnIdle { assertEquals("saved.example.com", ui.value.settings.selectedConnectionProfile().customServerDomain) }
        capture("setup-ready-after-save")
        tab(strings.tabProfiles)
        Espresso.pressBack()
        compose.onNodeWithTag("setup-card").assertDoesNotExist()
        val another = connection.copy(id = "another-connection", name = "Another saved connection")
        compose.runOnIdle { ui.value = ui.value.copy(settings = ui.value.settings.copy(
            connectionProfiles = ui.value.settings.connectionProfiles + another)) }
        press(compose.onNodeWithTag("home-option-connection"))
        click(another.name)
        compose.onNodeWithTag("setup-card").assertDoesNotExist()
        compose.runOnIdle { assertEquals(another.id, ui.value.settings.selectedConnectionProfileId) }
    }

    @Test fun setupSliderHandlesResolverRecoveryDiscoveryAndConnectionStates() {
        val brokenResolver = resolver.copy(resolverText = "not-an-ip")
        ui.value = ui.value.copy(settings = ui.value.settings.copy(
            resolverProfiles = listOf(brokenResolver), resolverText = brokenResolver.resolverText))
        show()
        compose.onNodeWithTag("setup-step-1").assertIsSelected()
        press(compose.onNodeWithTag("setup-step-1"))
        capture("setup-resolver-invalid")
        press(compose.onNodeWithTag("setup-action-1"))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("Recovered resolvers")
        fields[1].performTextReplacement("1.1.1.1\n8.8.8.8")
        Espresso.closeSoftKeyboard()
        click(strings.btnSave)
        compose.onNodeWithTag("setup-card").assertDoesNotExist()
        compose.onNodeWithTag("connect-control").assertIsEnabled()
        compose.runOnIdle { assertTrue(validateResolverText(ui.value.settings.resolverText).isValid) }

        compose.runOnIdle { ui.value = ui.value.copy(settings = ui.value.settings.copy(
            connectionProfiles = listOf(connection.copy(customServerEncryptionKey = "")))) }
        compose.onNodeWithTag("setup-step-0").assertIsSelected()
        press(compose.onNodeWithTag("setup-step-0"))
        capture("setup-key-missing")
        compose.runOnIdle { ui.value = ui.value.copy(settings = ui.value.settings.copy(
            connectionProfiles = listOf(connection.copy(engine = DnsClientEngine.Soar,
                soarSettings = SoarProfileSettings(discovery = true))),
            resolverText = "", selectedResolverProfileId = "")) }
        compose.onNodeWithTag("setup-card").assertDoesNotExist()
        compose.onNodeWithTag("connect-control").assertIsEnabled()
        capture("setup-discovery")
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTING) }
        compose.onNodeWithTag("setup-card").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTED) }
        compose.onNodeWithTag("setup-card").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.DISCONNECTED) }
        compose.onNodeWithTag("setup-card").assertDoesNotExist()
        capture("setup-discovery-ready")
        compose.runOnIdle { ui.value = ui.value.copy(settings = ui.value.settings.copy(resolverText = "not-an-ip")) }
        compose.onNodeWithTag("setup-step-1").assertIsSelected()
        press(compose.onNodeWithTag("setup-step-1"))
        capture("setup-discovery-invalid-manual")
        assertTextFits(if (language == "fa") "ریزالورها را تنظیم کنید" else "Set up your resolvers")
    }

    @Test fun trafficValuesKeepTheirDirectionAndFitBesideLocalizedLabels() {
        ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTED,
            connectionStats = ConnectionStats(downloadSpeedBytesPerSecond = 65_536,
                uploadSpeedBytesPerSecond = 8_192, totalDataUsageBytes = 536_870_912))
        show()
        listOf("64 KB/s", "8.0 KB/s", "512 MB").forEach { text ->
            val layout = textLayout(text)
            assertEquals("Traffic quantities must keep number-before-unit order", TextDirection.Ltr,
                layout.layoutInput.style.textDirection)
            assertTextFits(text)
            assertEquals("Traffic quantity must not split across lines", 1, layout.lineCount)
        }
        listOf(strings.speedDown, strings.speedUp, strings.speedTotalUsage).forEach {
            assertTextFits(it)
        }
        val configuration = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        if (configuration.screenWidthDp >= 360 && configuration.fontScale <= 1.3f) {
            val tops = listOf(strings.speedDown, strings.speedUp, strings.speedTotalUsage).map {
                compose.onAllNodesWithText(it, useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.top
            }
            assertEquals("Ordinary text scales should keep a compact statistics row", tops.min(), tops.max(), 1f)
        }
        compose.onNodeWithText(strings.connectProgressConnected).assertDoesNotExist()
        compose.onNodeWithTag("home-traffic").performScrollTo()
        capture("traffic-layout-regression")
        listOf(strings.resolverActiveResolvers, strings.resolverValidResolvers).forEach {
            compose.onAllNodesWithText(it).onFirst().performScrollTo()
            assertTextFits(it)
        }
        for (status in listOf(ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTED)) {
            compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = status) }
            compose.onNodeWithTag("home-traffic").performScrollTo()
            val duplicateStatus = if (status == ConnectionStatus.CONNECTING) strings.btnConnecting
                else if (language == "fa") "متصل نیست" else "Not connected"
            compose.onNodeWithText(duplicateStatus).assertDoesNotExist()
            capture("home-traffic-${status.name.lowercase()}")
        }
        compose.onAllNodes(hasText("0 B/s") and hasAnyAncestor(hasTestTag("home-traffic")), useUnmergedTree = true)
            .assertCountEquals(2)
    }

    @Test fun modalTitlesAndTechnicalInputUseTheSameCompactTypeInBothLanguages() {
        show()
        tab(strings.tabProfiles)
        click(strings.profileBtnCreate)
        capture("create-stormdns")
        assertEquals(if (language == "fa") ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr,
            textLayout(strings.profileEncryptionKeyPlaceholder).getParagraphDirection(0))
        listOf(strings.profileFieldEngine, strings.profileFieldName, strings.profileFieldDomain,
            strings.profileFieldEncryptionKey, strings.profileFieldEncryptionMethod,
            strings.profileMyStormDnsPlaceholder, strings.profileDomainPlaceholder,
            strings.profileEncryptionKeyPlaceholder).forEach(::assertFieldCopyAtLocaleEdge)
        val fields = compose.onAllNodes(hasSetTextAction())
        val domain = "v1.example.com, v2.example.com"
        val key = "00112233445566778899aabbccddeeff"
        fields[1].performTextInput(domain)
        fields[2].performTextInput(key)
        listOf(domain, key).forEach {
            assertEquals("Entered technical values must preserve their LTR order", ResolvedTextDirection.Ltr,
                textLayout(it).getParagraphDirection(0))
        }
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        click(strings.profileBtnImport)
        assertEquals(18.sp, textLayout(strings.profileDialogImportConnection).layoutInput.style.fontSize)
        val placeholder = "https://panel.example.com/sub/...\nsoar://..."
        assertEquals(ResolvedTextDirection.Ltr, textLayout(placeholder).getParagraphDirection(0))
        assertFieldCopyAtLocaleEdge(placeholder)
        assertTextFits(strings.profileDialogImportConnection)
        capture("modal-type-regression")
        Espresso.pressBack()
        profileSection(strings.profileTabSetting)
        described(strings.cdSettingGuide)
        assertTextFits(strings.settingGuideTitle)
        capture("guide-type-regression")
        Espresso.pressBack()
        described(strings.cdAppMenu)
        click(strings.menuDonate)
        val address = "UQCVUC-eZzxNkVVewFp9pz43JKd0XIc55KCdC5gbwxJKiqoL"
        compose.onNodeWithText(address).performScrollTo()
        assertTextFits(address)
        assertEquals(TextDirection.Ltr, textLayout(address).layoutInput.style.textDirection)
        Espresso.pressBack()
        described(strings.cdAppMenu)
        click(strings.menuAppSettings)
        val themes = listOf(strings.themeModeDark, strings.themeModeLight, strings.themeModeAuto)
        (themes + listOf(strings.languageEn, strings.languageFa)).forEach(::assertTextFits)
        val configuration = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        if (configuration.screenWidthDp >= 360 && configuration.fontScale <= 1.3f) {
            val tops = themes.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
            assertEquals("Theme choices that fit should stay in one compact row", tops.min(), tops.max(), 1f)
        }
        val english = compose.onNodeWithText(strings.languageEn).fetchSemanticsNode().boundsInRoot
        val persian = compose.onNodeWithText(strings.languageFa).fetchSemanticsNode().boundsInRoot
        assertTrue("Language choices must keep English first in physical reading order",
            if (kotlin.math.abs(english.top - persian.top) < 1f) english.right <= persian.left
            else english.bottom <= persian.top)
        capture("compact-app-settings")
    }

    @Test fun captureProductionScreensAndDialogs() {
        show()
        capture("fixture-connect-idle")
        compose.onNodeWithText(connection.name).performScrollTo().performClick()
        capture("connection-selector")
        compose.onNode(hasSetTextAction()).performTextInput("no-such-connection")
        capture("connection-selector-empty")
        described(strings.cdCloseSelector)
        tab(strings.tabProfiles)
        capture("profiles-connection")
        click(strings.profileBtnCreate)
        compose.onNodeWithText(strings.btnSave).assertIsNotEnabled()
        capture("create-stormdns")
        click(strings.profileEngineStormDns)
        click(strings.profileEngineCottenDns)
        capture("create-cottendns")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assertContentDescriptionEquals(strings.profileFieldScanParallelism)
        click(strings.profileEngineCottenDns)
        click("Soar (experimental)")
        capture("create-soar")
        Espresso.pressBack()
        described(strings.connectionProfileMenuActions)
        capture("connection-actions")
        click(strings.profileMenuExport)
        listOf(strings.btnClose, strings.btnCopy, strings.btnShare).forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val qrImage = compose.onNodeWithContentDescription(strings.cdProfileQrCode).performScrollTo()
        var qrPreview = qrImage.fetchSemanticsNode()
        val qrScroller = generateSequence(qrPreview.parent) { it.parent }
            .first { it.config.contains(SemanticsActions.ScrollBy) }
        // Image semantics describe the bitmap inside its 8dp padded frame.
        // Include that frame when positioning and inspecting the screenshot.
        val framePadding = 8f * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val hiddenFrame = qrPreview.boundsInRoot.bottom + framePadding - qrScroller.boundsInRoot.bottom
        if (hiddenFrame > 0f) {
            compose.onNode(SemanticsMatcher("QR scroll viewport") { it.id == qrScroller.id }, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, hiddenFrame) }
            compose.waitForIdle()
            qrPreview = qrImage.fetchSemanticsNode()
        }
        assertEquals("Whole QR must fit inside its scroll viewport", qrPreview.size.height.toFloat(), qrPreview.boundsInRoot.height, 1f)
        assertEquals("Whole QR must fit horizontally", qrPreview.size.width.toFloat(), qrPreview.boundsInRoot.width, 1f)
        assertTrue("QR frame top must be visible", qrPreview.boundsInRoot.top - framePadding >= qrScroller.boundsInRoot.top - 1f)
        assertTrue("QR frame bottom must be visible", qrPreview.boundsInRoot.bottom + framePadding <= qrScroller.boundsInRoot.bottom + 1f)
        assertTrue("QR frame left must be visible", qrPreview.boundsInRoot.left - framePadding >= qrScroller.boundsInRoot.left - 1f)
        assertTrue("QR frame right must be visible", qrPreview.boundsInRoot.right + framePadding <= qrScroller.boundsInRoot.right + 1f)
        capture("export-connection-qr")
        compose.onNodeWithContentDescription(strings.profileFieldProfileLinkSingle).performScrollTo().performTouchInput { click() }
        val previewAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        previewAutomation.serviceInfo = previewAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        assertFalse("Read-only export preview must not open the keyboard", previewAutomation.windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        })
        compose.onNodeWithText(strings.btnClose).performTouchInput { click() }
        described(strings.connectionProfileMenuActions)
        click(strings.profileMenuDelete)
        capture("delete-connection-confirmation")
        Espresso.pressBack()
        click(strings.profileBtnImport)
        capture("import-connection")
        Espresso.pressBack()
        profileSection(strings.profileTabResolver)
        capture("profiles-resolver")
        click(strings.profileBtnCreate)
        capture("create-resolver")
        Espresso.pressBack()
        click(strings.profileBtnExportAll)
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(strings.btnShare).fetchSemanticsNodes()
                    .any { !it.config.contains(SemanticsProperties.Disabled) }
            }
        } catch (error: Throwable) {
            capture("export-resolvers-failure")
            throw error
        }
        compose.onNodeWithText(strings.btnShare).assertIsEnabled()
        capture("export-resolvers")
        Espresso.pressBack()
        profileSection(strings.profileTabSetting)
        capture("profiles-setting")
        described(strings.cdSettingGuide)
        capture("setting-guide")
        Espresso.pressBack()
        click(strings.profileBtnCreate)
        capture("create-setting")
        Espresso.pressBack()
        click(strings.profileBtnImport)
        capture("import-setting")
        Espresso.pressBack()
        tab(strings.tabScan)
        capture("fixture-scan-idle")
        tab(strings.tabLogs)
        capture("logs-empty")
        described(strings.cdAppMenu)
        capture("app-menu")
        click(strings.menuAppSettings)
        capture("app-settings")
        Espresso.pressBack()
        described(strings.cdAppMenu)
        click(strings.menuDonate)
        capture("donation")
        Espresso.pressBack()
    }

    @Test fun connectionAndScannerFixturesExposeProgressAndRecovery() {
        show()
        compose.runOnIdle { ui.value = ui.value.copy(
            connectionStatus = ConnectionStatus.CONNECTING,
            connectionProgress = ConnectionProgressState(phase = "mtu", percent = 45, completed = 9, total = 20),
        ) }
        capture("fixture-connect-progress")
        compose.runOnIdle { ui.value = ui.value.copy(
            connectionStatus = ConnectionStatus.CONNECTED,
            connectionStats = ConnectionStats(downloadBytes = 1048576, uploadBytes = 524288,
                downloadSpeedBytesPerSecond = 65536, uploadSpeedBytesPerSecond = 8192),
            resolverRuntimeState = ResolverRuntimeState(activeResolvers = listOf("1.1.1.1")),
            connectionLogs = listOf("Fixture: connection established", "Fixture: resolver selected"),
        ) }
        capture("fixture-connect-connected")
        compose.onNodeWithText(strings.resolverActiveResolvers).performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText(strings.btnClose).assertIsDisplayed()
        compose.onNodeWithText(strings.btnCopy).assertIsDisplayed()
        capture("fixture-active-resolvers")
        Espresso.pressBack()
        tab(strings.tabLogs)
        compose.onNodeWithText(strings.btnCopy).assertIsEnabled()
        capture("fixture-logs-populated")
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.DISCONNECTED,
            connectionLogs = listOf("Fixture: startup failed. Check the selected server and resolvers.")) }
        tab(strings.tabConnect)
        compose.onNodeWithText(ui.value.connectionLogs.first()).assertIsDisplayed()
        capture("fixture-connect-failed")
        click(if (language == "fa") "مشاهدهٔ گزارش اتصال" else "View connection logs")
        compose.onNodeWithText(ui.value.connectionLogs.first()).assertExists()
        tab(strings.tabScan)
        for (status in listOf(WhiteDnsScanStatus.Running, WhiteDnsScanStatus.Stopped, WhiteDnsScanStatus.Completed, WhiteDnsScanStatus.Failed)) {
            compose.runOnIdle { ui.value = ui.value.copy(scanState = WhiteDnsScanState(
                sessionId = "review-scan", status = status, sourceName = "Review fixture list",
                totalResolvers = 20, completedResolvers = if (status == WhiteDnsScanStatus.Completed) 20 else 12,
                validResolvers = 2, rejectedResolvers = if (status == WhiteDnsScanStatus.Completed) 18 else 10,
                validResolverEntries = listOf("1.1.1.1", "8.8.8.8"),
                message = if (status == WhiteDnsScanStatus.Failed) "Review fixture: network unavailable" else "",
            )) }
            capture("fixture-scan-$status")
            compose.onNodeWithText(strings.scanBtnSaveAs).performScrollTo()
            capture("fixture-scan-$status-details")
            if (status == WhiteDnsScanStatus.Completed) {
                click(strings.scanBtnSaveAs)
                capture("fixture-scan-save-as")
                Espresso.pressBack()
            }
        }
    }

    @Test fun invalidImportKeepsDraftAndExplainsRecovery() {
        show()
        tab(strings.tabProfiles)
        click(strings.profileBtnImport)
        compose.onNode(hasSetTextAction()).performTextInput("invalid-profile")
        Espresso.closeSoftKeyboard()
        compose.onAllNodesWithText(strings.btnImport).onLast().performClick()
        compose.onNodeWithText("Review import failed. Check the profile link.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("invalid-profile")
        capture("import-error")
        Espresso.pressBack()
        compose.onNode(hasText(strings.profileTabConnection) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            hasAnySibling(hasText(strings.profileTabResolver))).assertIsSelected()
    }

    @Test fun connectionCreationValidatesAndSavesWithoutChangingExistingProfile() {
        show()
        tab(strings.tabProfiles)
        click(strings.profileBtnCreate)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Added in review")
        fields[1].performTextInput("second.example.com")
        compose.onNodeWithText(strings.btnSave).assertIsNotEnabled()
        fields[2].performTextInput("00112233445566778899aabbccddeeff")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText(strings.btnSave).assertIsEnabled().performClick()
        compose.runOnIdle {
            val profiles = ui.value.settings.normalizedConnectionProfiles()
            assertEquals(connection, profiles.first { it.id == connection.id })
            assertEquals("Added in review", ui.value.settings.selectedConnectionProfile().name)
        }
        compose.onNodeWithText("Added in review").assertIsDisplayed()
        capture("created-connection")
    }
    @Test fun navigationKeepsProfileSectionAndBackReturnsToConnect() {
        show()
        tab(strings.tabProfiles)
        profileSection(strings.profileTabResolver)
        tab(strings.tabLogs)
        Espresso.pressBack()
        compose.onAllNodes((hasText(strings.tabConnect) or hasContentDescription(strings.tabConnect)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            !hasAnyAncestor(hasTestTag("setup-card"))).onLast().assertIsSelected()
        tab(strings.tabProfiles)
        compose.onNode(hasText(strings.profileTabResolver) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            hasAnySibling(hasText(strings.profileTabSetting))).assertIsSelected()
    }

    @Test fun resolverValidationAndSaveWorkWithKeyboardOpen() {
        show()
        tab(strings.tabProfiles)
        profileSection(strings.profileTabResolver)
        click(strings.profileBtnCreate)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Added resolvers")
        fields[1].performTextReplacement("999.1.1.1")
        compose.onNodeWithText(strings.btnSave).assertIsNotEnabled()
        fields[1].performTextReplacement("1.1.1.1\n8.8.8.8")
        compose.onNodeWithText(strings.btnSave).assertIsDisplayed().assertIsEnabled()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.waitUntil(3_000) {
            automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        val keyboard = android.graphics.Rect()
        automation.windows.first { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .getBoundsInScreen(keyboard)
        capture("resolver-keyboard-validation")
        for (label in listOf(strings.btnCancel, strings.btnSave)) {
            val node = compose.onNodeWithText(label).fetchSemanticsNode()
            val top = node.positionOnScreen.y
            val bottom = top + node.size.height
            org.junit.Assert.assertTrue("$label is covered by the keyboard: $top..$bottom / $keyboard",
                node.size.height > 0 && top >= 0 && bottom <= keyboard.top)
        }
        compose.onNodeWithText(strings.btnSave).performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("Added resolvers", ui.value.settings.normalizedResolverProfiles().last().name)
        }
    }

    @Test fun profilesCanBeReorderedWithAccessibilityActions() {
        val second = connection.copy(id = "review-second", name = "Second connection")
        ui.value = ui.value.copy(settings = ui.value.settings.copy(connectionProfiles = listOf(connection, second)))
        show()
        tab(strings.tabProfiles)
        val actions = compose.onNodeWithText(connection.name).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            actions.single { it.label == if (language == "fa") "انتقال به پایین" else "Move down" }.action()
        }
        compose.runOnIdle {
            assertEquals(second.id, ui.value.settings.normalizedConnectionProfiles().first().id)
            assertEquals(connection.id, ui.value.settings.selectedConnectionProfile().id)
        }
        capture("accessible-reorder")
    }

    @Test fun secondaryConfigurationSurfacesRemainReachable() {
        ui.value = ui.value.copy(settings = ui.value.settings.copy(
            connectionMode = "vpn", splitTunnelMode = WhiteDnsOptions.SplitTunnelModeInclude))
        show()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.splitTunnelSelectApps).fetchSemanticsNodes()
                .any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        click(strings.splitTunnelSelectApps)
        capture("split-app-picker")
        compose.onNode(hasSetTextAction()).performTextInput("no-such-app")
        compose.onNodeWithText(strings.splitTunnelNoAppsFound).assertExists()
        capture("split-app-picker-empty")
        compose.onNode(hasSetTextAction()).performTextClearance()
        Espresso.closeSoftKeyboard()
        val firstApp = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).onFirst()
        reveal(firstApp)
        firstApp.assertIsDisplayed().performClick()
        firstApp.assertIsOn()
        capture("split-app-draft-selected")
        click(strings.btnSave)
        compose.runOnIdle { assertEquals(1, ui.value.settings.splitTunnelPackages.size) }
        capture("split-app-selected")
        compose.runOnIdle { ui.value = ui.value.copy(settings = ui.value.settings.copy(connectionMode = "proxy")) }
        click(resolver.name)
        capture("resolver-selector")
        described(strings.cdCloseSelector)
        click(strings.setupDefaultAdvanced)
        capture("setting-selector")
        described(strings.cdCloseSelector)
        click(strings.groupNetworkTuning)
        click(strings.parallelTest)
        compose.onNodeWithText(strings.parallelTestDescription).performScrollTo()
        capture("parallel-config-selection")
        compose.runOnIdle { ui.value = ui.value.copy(autoTuneTrialResults = listOf(
            AutoTuneTrialResult(WhiteDnsParallelTest.whiteDnsConfigId("iran-average"), "Fixture balanced", "127.0.0.1", 10808,
                status = "ready", speedBytesPerSecond = 65536, pingMillis = 42, selected = true),
            AutoTuneTrialResult("review-failed", "Fixture failed", "127.0.0.1", 10809,
                status = "failed", message = "Fixture: server did not respond"),
        )) }
        compose.onAllNodesWithText(strings.saveSettingAs).onFirst().performScrollTo()
        assertTextFits("64 KB/s")
        assertEquals(TextDirection.ContentOrLtr, textLayout("64 KB/s").layoutInput.style.textDirection)
        capture("fixture-parallel-results")
        compose.onAllNodesWithText(strings.saveSettingAs).onLast().performScrollTo()
        capture("fixture-parallel-failed-result")
        click(strings.saveSettingAs)
        capture("fixture-parallel-save-as")
        Espresso.pressBack()
        click(strings.downloadTomlBtn)
        capture("export-connection-toml")
        Espresso.pressBack()
        tab(strings.tabProfiles)
        profileSection(strings.profileTabResolver)
        described(strings.resolverProfileMenuActions)
        capture("resolver-actions")
        Espresso.pressBack()
        profileSection(strings.profileTabSetting)
        described(strings.settingProfileMenuActions)
        capture("setting-actions")
        click(strings.profileMenuExport)
        capture("export-setting-toml")
        Espresso.pressBack()
        click(strings.profileBtnCreate)
        compose.onNodeWithText(strings.settingSocks5Auth).performScrollTo().performClick()
        compose.onNodeWithText(strings.settingUsername).performScrollTo()
        capture("advanced-proxy-auth")
        compose.onNodeWithText(strings.settingResolverTimeout).performScrollTo()
        capture("advanced-resolver-tuning")
        compose.onNodeWithText(strings.settingSocksUdpTimeout).performScrollTo()
        val pairedFields = listOf(strings.settingSocksUdpTimeout, strings.settingTerminalRetain).map {
            compose.onNode(hasSetTextAction() and hasContentDescription(it), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.bottom
        }
        assertEquals("Paired inputs must align when localized labels wrap", pairedFields[0], pairedFields[1], 1f)
        capture("advanced-transport-tuning")
        Espresso.pressBack()
    }

    @Test fun subscriptionAndServerTestStatesRemainUnderstandable() {
        val source = SubscriptionSource("https://review.example.com/sub/fixture", "fixture",
            updatedAt = 1_760_000_000_000L, upload = 524288, download = 1048576, total = 1073741824)
        ui.value = ui.value.copy(settings = ui.value.settings.copy(connectionProfiles = listOf(
            connection.copy(subscription = source), connection.copy(id = "review-other", name = "Other connection"))))
        show()
        tab(strings.tabProfiles)
        capture("fixture-subscription-quota")
        click(if (language == "fa") "تازه‌سازی ساب‌ها" else "Refresh subscriptions")
        compose.onNodeWithText(if (language == "fa") "ساب‌ها تازه‌سازی شدند" else "Subscriptions refreshed").assertExists()
        capture("fixture-subscription-refreshed")
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.CONNECTED,
            serverTestState = ServerTestState(results = listOf(
                ServerTestResult(connection.id, connection.name, connection.customServerDomain,
                    status = ServerTestStatus.Ready, speedBytesPerSecond = 65536, pingMillis = 42),
                ServerTestResult("review-other", "Other connection", "review.example.com",
                    status = ServerTestStatus.Failed, message = "Fixture: no response"),
            ))) }
        compose.onNodeWithText(connection.name).performScrollTo()
        capture("fixture-server-test-results")
        compose.runOnIdle { ui.value = ui.value.copy(serverTestState = ui.value.serverTestState.copy(isRunning = true)) }
        described("${strings.groupCustomConnections}: ${strings.connectionProfileMenuActions}")
        compose.onNodeWithText(strings.serverTestRunning).assertIsNotEnabled()
        capture("fixture-server-test-running")
        Espresso.pressBack()
        compose.runOnIdle { ui.value = ui.value.copy(connectionStatus = ConnectionStatus.DISCONNECTED,
            serverTestState = ServerTestState()) }
        click(strings.profileBtnImport)
        compose.onNode(hasSetTextAction()).performTextInput("http://review.invalid/sub")
        Espresso.closeSoftKeyboard()
        compose.onAllNodesWithText(strings.btnImport).onLast().assertIsNotEnabled()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performScrollTo()
        capture("import-http-consent")
        val consent = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).fetchSemanticsNode()
        org.junit.Assert.assertTrue("HTTP consent must have a readable name", consent.config[SemanticsProperties.Text].isNotEmpty())
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performClick()
        compose.onAllNodesWithText(strings.btnImport).onLast().assertIsEnabled()
        Espresso.pressBack()
    }

    @Test fun enabledActionsHaveNamesAndMinimumTouchTargets() {
        show()
        for (destination in listOf(strings.tabConnect, strings.tabProfiles, strings.tabScan, strings.tabLogs)) {
            tab(destination)
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            compose.onAllNodes(hasClickAction() and isEnabled()).fetchSemanticsNodes().forEach { node ->
                if (node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0) {
                    val size = node.size
                    org.junit.Assert.assertTrue("Short touch target: ${node.config}", size.height / density >= 47.5f)
                    org.junit.Assert.assertTrue("Narrow touch target: ${node.config}", size.width / density >= 47.5f)
                    org.junit.Assert.assertTrue("Unnamed action: ${node.config}",
                        !node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.isEmpty() ||
                        !node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.isEmpty())
                }
            }
        }
        tab(strings.tabConnect)
        Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(WhiteDnsConnectionOrb::class.java))
            .perform(androidx.test.espresso.action.ViewActions.click())
        compose.runOnIdle { assertEquals(ConnectionStatus.CONNECTING, ui.value.connectionStatus) }
    }

}
