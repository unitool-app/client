package cx.m42.unitool

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.superizer.diagnostics.SelfTestResult
import cx.m42.superizer.host.storage.HostKeys
import cx.m42.superizer.host.storage.SafePrefs
import cx.m42.unitool.premium.LocalPremiumStore
import cx.m42.unitool.premium.Store
import cx.m42.unitool.settings.HostTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import cx.m42.superizer.host.storage.PrefsStorage
import cx.m42.superizer.lock.AuthAvailability
import cx.m42.superizer.lock.AuthOutcome
import cx.m42.superizer.runtime.Clock
import cx.m42.superizer.testing.FakeDeviceAuthenticator
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders Unitool to PNG files without a browser, an emulator or a window server — the desktop
 * target composes the same `commonMain` tree every other target does, so these are the screens a
 * phone shows, at a phone's size.
 *
 * Output lands in `composeApp/build/screenshots/`. Run it with:
 *
 * ```bash
 * ./gradlew :composeApp:desktopTest --tests '*Screenshots*'
 * ```
 *
 * A test can be green while the screen is blank, so these are meant to be *looked at* — a passing
 * `assertIsDisplayed` says a node exists, not that a human could read it.
 */
class Screenshots {

    /**
     * A viewport to render at, and the density that makes it one.
     *
     * Four of them, because the store audiences want different pictures and none can be cropped
     * out of another:
     *
     *  - [Phone] is the shape of a phone people actually hold — 412×915 dp, 20:9, what a Pixel
     *    reports. These are the screens in the README.
     *  - [PlayListing] is what the Play Console accepts, which is narrower: 16:9 or 9:16 and at
     *    least 1080 px on the short side. A 20:9 capture is rejected outright, so the listing gets
     *    its own render rather than a letterboxed copy.
     *  - [AppStoreListing] is Apple's 6.5-inch portrait slot: 414×896 dp at 3×, exactly
     *    1242×2688 px. It is rendered at its native aspect ratio rather than resized from Play.
     *  - [AppStoreIpad] is Apple's 13-inch iPad slot: 1032×1376 dp at 2×, exactly 2064×2752 px,
     *    what an iPad Pro 13-inch (M4) reports. A build that runs on iPad is not submitted without it.
     *
     * Each also names the store whose Premium offer it draws: the phone and Play frames are
     * Android's, so they show Google Play's. The App Store frames are all in English, whatever
     * language a screen is declared in.
     */
    private data class Viewport(
        val name: String,
        val widthDp: Int,
        val heightDp: Int,
        val scale: Float,
        val directory: String,
        val store: Store,
    ) {
        val widthPx: Int get() = (widthDp * scale).toInt()
        val heightPx: Int get() = (heightDp * scale).toInt()
    }

    private val Phone = Viewport("phone", 412, 915, 2f, "build/screenshots/phone", Store.GooglePlay)
    private val PlayListing = Viewport("play", 360, 640, 3f, "build/screenshots/play", Store.GooglePlay)
    private val AppStoreListing =
        Viewport("appstore-6.5", 414, 896, 3f, "build/screenshots/appstore-6.5", Store.AppStore)
    private val AppStoreIpad =
        Viewport("appstore-ipad-13", 1032, 1376, 2f, "build/screenshots/appstore-ipad-13", Store.AppStore)

    private fun labelFor(lang: String, label: String): String = if (lang == "en") {
        mapOf(
            "Открыть меню" to "Open menu",
            "Премиум" to "Premium",
            "Настройки" to "Settings",
            "Назад" to "Back",
        )[label] ?: label
    } else {
        label
    }

    /** The verbs a screenshot needs. By tag where there is one, by label where a human would look. */
    private class DriveScope(
        val tap: (String) -> Unit,
        val tapIcon: (String) -> Unit,
        val tapTag: (String) -> Unit,
        val typeInto: (String, String) -> Unit,
        val scroll: (String) -> Unit,
    )

    @OptIn(ExperimentalTestApi::class)
    private fun shot(
        viewport: Viewport,
        name: String,
        lang: String,
        offline: Boolean,
        clock: Clock?,
        device: FakeDeviceAuthenticator,
        kind: HostKind,
        drive: DriveScope.() -> Unit,
    ) {
        // A fresh store for every frame: a screenshot must show what a new install looks like,
        // not what the previous capture left behind.
        PrefsStorage.useDirectory(Files.createTempDirectory("unitool-shot").toFile())
        runDesktopComposeUiTest(width = viewport.widthPx, height = viewport.heightPx) {
            // A self-test that passed, stamped with this version: the host would otherwise run one at
            // start, on every frame, and the storage screens would show a different date each time.
            SafePrefs.put(HostKeys.SELF_TEST, Json.encodeToString(SelfTestResult.serializer(), PassedSelfTest))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            // These are phone screens, and a phone has a camera and a share sheet: without them the
            // 2FA form would be drawn as on desktop, with no scan button, and its export with no
            // main button. Never opened by a capture.
            val network = if (offline) offlineNetwork() else ratesNetwork()
            val tools = HostTools(qr = PhoneCamera, sharer = PhoneShareSheet, network = network)
            val security = kind.security(scope)
            val superizer = buildUnitool(scope, debug = true, security = security, tools = tools, network = network) {
                clock?.let { clock(it) }
                // A screen lock: the 2FA app is behind it (06), and the finger that answers the
                // sheet is this fake. A frame of the curtain itself sets it to say no.
                deviceAuthenticator(device)
                kind.install(this, tools)
            }
            kind.prepare(security)
            superizer.settings.chooseLanguage(lang)
            setContent {
                // The window is in pixels; the app is laid out in dp. Providing the density here is
                // what makes an 824 px capture a 412 dp phone rather than a small tablet.
                CompositionLocalProvider(
                    LocalDensity provides Density(viewport.scale),
                    LocalPremiumStore provides viewport.store,
                ) {
                    UnitoolApp(superizer, security)
                }
            }
            waitForIdle()
            DriveScope(
                tap = { label -> onNodeWithText(labelFor(lang, label)).performClick(); waitForIdle() },
                tapIcon = { label -> onNodeWithContentDescription(labelFor(lang, label)).performClick(); waitForIdle() },
                tapTag = { tag ->
                    // Scrolled into view first: the 640 dp listing frame is short enough that a
                    // form's last button sits below the fold, and a click there lands on nothing.
                    // A node outside any scrollable has nowhere to scroll to, which is fine.
                    runCatching { onNodeWithTag(tag).performScrollTo() }
                    onNodeWithTag(tag).performClick()
                    waitForIdle()
                },
                typeInto = { tag, text ->
                    runCatching { onNodeWithTag(tag).performScrollTo() }
                    onNodeWithTag(tag).performTextInput(text)
                    waitForIdle()
                },
                scroll = { tag -> onNodeWithTag(tag).performScrollTo(); waitForIdle() },
            ).drive()
            waitForIdle()

            // The pointer is still where the last tap was, and on desktop that draws a hover tint
            // no phone has. Moved off the window so the frame shows what a finger leaves behind.
            // A frame nobody tapped has no pointer to move, and the test API throws on that.
            runCatching { onRoot().performMouseInput { moveTo(Offset(1f, 1f)) } }
            waitForIdle()

            val directory = File(viewport.directory).apply { mkdirs() }
            // The whole scene rather than the root node: an alert is a layer of its own, above the root.
            ImageIO.write(
                captureToImage().toAwtImage().withoutAlpha(),
                "png",
                File(directory, "$name.png"),
            )
        }
    }

    /**
     * Drops the alpha channel, which Compose hands back whether or not anything is transparent.
     *
     * Play rejects a screenshot carrying one: the store composites listings onto backgrounds of its
     * own, so an image that claims to be see-through is one it cannot place. Redrawn onto opaque
     * white rather than merely re-tagged, so a genuinely translucent pixel resolves against the
     * app's own background instead of against black.
     */
    private fun BufferedImage.withoutAlpha(): BufferedImage {
        if (type == BufferedImage.TYPE_INT_RGB) return this
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { opaque ->
            opaque.createGraphics().apply {
                color = java.awt.Color.WHITE
                fillRect(0, 0, width, height)
                drawImage(this@withoutAlpha, 0, 0, null)
                dispose()
            }
        }
    }

    private data class Screen(
        val name: String,
        val lang: String = "ru",
        val offline: Boolean = false,
        /** Pinned for screens whose content is a function of time — a 2FA code is. */
        val clock: Clock? = null,
        /** The phone's screen lock, and what the person answers when it asks. */
        val lock: AuthAvailability = AuthAvailability.Available,
        val answer: AuthOutcome = AuthOutcome.Success,
        val kind: HostKind = HostKind.Phone(),
        val drive: DriveScope.() -> Unit,
    )

    /**
     * Every screen, declared once as data rather than written out twice, so a screen added for the
     * README cannot go missing from the store listing.
     */
    private val screens: List<Screen> = listOf(
        Screen("01-home") {},
        Screen("02-menu") { tapIcon("Открыть меню") },
        Screen("03-calculator") { tapTag(CALCULATOR_TILE) },
        Screen("04-calculator-scientific") {
            tapIcon("Открыть меню")
            tapIcon("Премиум")
            tapTag("activate:have-code")
            typeInto("activate:promo", "SCI")
            tapTag("activate:apply")
        },
        Screen("05-converter") { addConverter(); tapTag(CONVERTER_TILE) },
        Screen("06-converter-offline", offline = true) { addConverter(); tapTag(CONVERTER_TILE) },
        Screen("07-activate") {
            tapIcon("Открыть меню")
            tapIcon("Премиум")
        },
        Screen("08-settings") {
            tapIcon("Открыть меню")
            tapIcon("Настройки")
        },
        Screen("09-settings-en", lang = "en") {
            tapIcon("Open menu")
            tapIcon("Settings")
        },
        Screen("10-service-menu") {
            tapIcon("Открыть меню")
            tapIcon("Премиум")
            tapTag("activate:have-code")
            typeInto("activate:promo", SERVICE_CODE)
            tapTag("activate:apply")
        },
        Screen("11-test-app") {
            tapIcon("Открыть меню")
            tapIcon("Премиум")
            tapTag("activate:have-code")
            typeInto("activate:promo", cx.m42.apps.testapp.TestApp.PROMO_CODE)
            tapTag("activate:apply")
        },
        Screen("12-all-apps") { tapTag("home:add") },
        Screen("13-2fa-empty", clock = TwoFaClock) { openTwoFa() },
        Screen("14-2fa-list", clock = TwoFaClock) {
            openTwoFa()
            TwoFaLinks.forEach { addTwoFa(it) }
            // Every code is dots until tapped; one shown says what the app is for.
            tapTag("2fa:row-0")
        },
        Screen("15-2fa-add-link", clock = TwoFaClock) {
            openTwoFa()
            tapTag("2fa:add")
            typeInto("2fa:link", TwoFaLinks.first())
        },
        Screen("16-2fa-add-form", clock = TwoFaClock) {
            openTwoFa()
            tapTag("2fa:add")
            typeInto("2fa:link", TwoFaLinks.first())
            tapTag("2fa:read-link")
        },
        Screen("17-2fa-details", clock = TwoFaClock) {
            openTwoFa()
            addTwoFa(TwoFaLinks.first())
            tapTag("2fa:details-0")
        },
        Screen("18-2fa-list-en", lang = "en", clock = TwoFaClock) {
            tapTag("home:add")
            tapTag("catalog:row-2fa")
            tapIcon("Back")
            tapTag(TWOFA_TILE)
            TwoFaLinks.forEach { addTwoFa(it) }
            tapTag("2fa:row-0")
        },
        Screen("19-2fa-export", clock = TwoFaClock) {
            openTwoFa()
            TwoFaLinks.forEach { addTwoFa(it) }
            tapTag("2fa:export")
            typeInto("2fa:github", "octocat")
            tapTag("2fa:find-keys")
        },
        Screen("20-2fa-export-shared", clock = TwoFaClock) {
            openTwoFa()
            TwoFaLinks.forEach { addTwoFa(it) }
            tapTag("2fa:export")
            typeInto("2fa:github", "octocat")
            tapTag("2fa:find-keys")
            tapTag("2fa:share")
            // Scrolls down to the decrypt commands, and copies the first as a person would.
            tapTag("2fa:command-1")
        },
        Screen("21-2fa-transfer", clock = TwoFaClock) {
            openTwoFa()
            TwoFaLinks.forEach { addTwoFa(it) }
            tapTag("2fa:export")
            typeInto("2fa:github", "octocat")
            tapTag("2fa:find-keys")
            // Further down: the command that shows every account as one QR code for the new phone.
            tapTag("2fa:transfer-0")
        },
        // 06: the curtain, as it stays when the sheet is cancelled.
        Screen("23-2fa-locked", clock = TwoFaClock, answer = AuthOutcome.Cancelled) { openTwoFa() },
        // A phone with no screen lock: the app opens, under the host's banner (D140).
        Screen("24-2fa-no-screen-lock", clock = TwoFaClock, lock = AuthAvailability.NoScreenLock) {
            openTwoFa()
            addTwoFa(TwoFaLinks.first())
        },
        Screen("25-settings-protection") {
            tapIcon("Открыть меню")
            tapIcon("Настройки")
            tapTag("settings:lock-now")
        },
        Screen("22-2fa-import", clock = TwoFaClock) {
            openTwoFa()
            // One account already here, so the list shows both kinds of row.
            addTwoFa(TwoFaLinks.first())
            tapTag("2fa:add")
            // What `age -d` prints on the computer — the whole export at once.
            typeInto("2fa:link", TwoFaLinks.joinToString("\n"))
            tapTag("2fa:read-link")
        },
        // ssh-new step 6: the host's keys, on a phone with a chip — three keys at three levels.
        // SSH keys are not in Settings: their promo code opens them (done/idea/09).
        Screen("26-ssh-keys", kind = HostKind.Phone(PhoneKeys)) { openKeys() },
        Screen("27-key-card", kind = HostKind.Phone(PhoneKeys)) { openKeys(); tapTag("keys:row-0") },
        Screen("28-key-card-session", kind = HostKind.Phone(PhoneKeys)) { openKeys(); tapTag("keys:row-2") },
        Screen("29-import-sources", kind = HostKind.Phone(PhoneKeys)) { openKeys(); tapTag("keys:add") },
        Screen("30-import-inspect", kind = HostKind.Phone(PhoneKeys)) { importPasted("encrypted") },
        Screen("31-import-set-passphrase", kind = HostKind.Phone(PhoneKeys)) {
            importPasted("unencrypted")
            tapTag("import:add")
            typeInto("import:new-first", "short one")
        },
        Screen("32-import-refused", kind = HostKind.Phone(PhoneKeys)) { importPasted("-----BEGIN RSA PRIVATE KEY-----") },
        Screen("33-import-create", kind = HostKind.Phone(PhoneKeys)) { openKeys(); tapTag("keys:add"); tapTag("import:create") },
        // Storage & security in each status (06 §9): the chip (Ok), a key's passphrase (Info), no
        // backup (Attention, in all of them), a software key (Unsafe), no vault (Missing), a lost key (Broken).
        Screen("34-storage-phone") { openSettings(); tapTag("storage:open") },
        Screen("35-storage-desktop-key", kind = HostKind.Desktop(withKey = true)) { openSettings(); tapTag("storage:open") },
        Screen("36-storage-software-key", kind = HostKind.Phone(hardware = "software")) { openSettings(); tapTag("storage:open") },
        Screen("37-home-desktop-no-key", kind = HostKind.Desktop()) {},
        Screen("38-storage-desktop-no-key", kind = HostKind.Desktop()) { tapTag("home:storage-banner") },
        Screen("39-storage-key-lost", kind = HostKind.Phone(lost = true)) { tapTag("home:storage-banner") },
        Screen("40-2fa-not-protected", clock = TwoFaClock, kind = HostKind.Desktop()) {
            openTwoFa()
            addTwoFa(TwoFaLinks.first())
        },
        // ssh-new step 7: the backup, to octocat's keys — one of them one age cannot encrypt to.
        Screen("42-backup") {
            openSettings()
            tapTag("backup:open")
            typeInto("backup:github", "octocat")
            tapTag("backup:find-keys")
        },
        Screen("41-ssh-keys-en", lang = "en", kind = HostKind.Phone(PhoneKeys)) {
            tapIcon("Open menu")
            tapIcon("Premium")
            tapTag("activate:have-code")
            typeInto("activate:promo", "{ssh-keys}")
            tapTag("activate:apply")
        },
        // done/idea/09: the secret app, opened by its code — and the Activate screen with the camera button.
        Screen("43-secret-app") {
            tapIcon("Открыть меню")
            tapIcon("Премиум")
            tapTag("activate:have-code")
            typeInto("activate:promo", cx.m42.apps.testapp.SecretTestApp.PROMO_CODE)
            tapTag("activate:apply")
        },
        // .ai-notes/site/02: Settings → Website, and the question the phone asks before a browser is signed in.
        Screen("45-website-en", lang = "en") {
            tapIcon("Open menu")
            tapIcon("Settings")
            scroll("web:browsers")
        },
        Screen("46-website-confirm-en", lang = "en") {
            tapIcon("Open menu")
            tapIcon("Settings")
            tapTag("web:paste")
            typeInto("web:link", WEBSITE_LINK)
            tapTag("web:next")
        },
        // A purchase on the Premium mock-up: the store's kind of alert, since nothing is sold yet.
        Screen("44-premium-failed") {
            tapIcon("Открыть меню")
            tapIcon("Премиум")
            tapTag("premium:subscribe")
        },
    )

    private fun DriveScope.openSettings() {
        tapIcon("Открыть меню")
        tapIcon("Настройки")
    }

    /** The SSH keys screen, which only its promo code opens (done/idea/09). */
    private fun DriveScope.openKeys() {
        tapIcon("Открыть меню")
        tapIcon("Премиум")
        tapTag("activate:have-code")
        typeInto("activate:promo", "{ssh-keys}")
        tapTag("activate:apply")
    }

    /** SSH keys, Add a key, Paste: [text] decides what the fake keyring says it is. */
    private fun DriveScope.importPasted(text: String) {
        openKeys()
        tapTag("keys:add")
        tapTag("import:paste")
        typeInto("import:paste-field", text)
        tapTag("import:paste-next")
    }

    /** Like the converter, the 2FA app is added from the catalog before it can be opened. */
    private fun DriveScope.openTwoFa() {
        tapTag("home:add")
        tapTag("catalog:row-2fa")
        tapIcon("Назад")
        tapTag(TWOFA_TILE)
    }

    /** The path a person takes: paste the link from the site's QR, read it, save. */
    private fun DriveScope.addTwoFa(link: String) {
        tapTag("2fa:add")
        typeInto("2fa:link", link)
        tapTag("2fa:read-link")
        tapTag("2fa:save")
    }

    /** The converter is not on a fresh Home (D48); the way it gets there is the catalog. */
    private fun DriveScope.addConverter() {
        tapTag("home:add")
        tapTag("catalog:row-currency-converter")
        tapIcon("Назад")
    }

    @Test
    fun captureEveryScreen() {
        val requestedViewport = System.getenv("UNITOOL_SCREENSHOT_VIEWPORT")
        val viewports = listOf(Phone, PlayListing, AppStoreListing, AppStoreIpad).filter {
            requestedViewport == null || it.name == requestedViewport
        }
        require(viewports.isNotEmpty()) { "Unknown screenshot viewport: $requestedViewport" }
        for (viewport in viewports) {
            // Emptied first. A frame left over from a screen that no longer exists is worse than a
            // missing one: it is the picture somebody uploads to a store listing by mistake.
            File(viewport.directory).listFiles()?.forEach { if (it.name.endsWith(".png")) it.delete() }
            for (screen in screens) {
                val device = FakeDeviceAuthenticator(screen.lock, screen.answer)
                val language = if (viewport.store == Store.AppStore) "en" else screen.lang
                shot(viewport, screen.name, language, screen.offline, screen.clock, device, screen.kind, screen.drive)
            }
        }
    }
}

/**
 * 12 s into a 30 s window, so every code has 18 s left and none is drawn in its red last five.
 * 2023-11-14 22:13:42 UTC.
 */
private val TwoFaClock = Clock { 1_700_000_022_000 }

/** Stands in for the Android camera so the phone screenshots show what a phone shows. */
private object PhoneCamera : QrScanner {
    override suspend fun scan(): String? = null
}

/** The share sheet, accepting whatever it is handed and sending it nowhere. */
private object PhoneShareSheet : FileSharer {
    override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean = true
}

/** Made-up secrets for made-up accounts: what the screenshots show must not unlock anything. */
private val TwoFaLinks = listOf(
    "otpauth://totp/GitHub:octocat?secret=JBSWY3DPEHPK3PXP&issuer=GitHub",
    "otpauth://totp/Google:ivan.petrov@gmail.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Google",
    "otpauth://totp/%D0%93%D0%BE%D1%81%D1%83%D1%81%D0%BB%D1%83%D0%B3%D0%B8:%2B7%20900%20000-00-00" +
        "?secret=KRSXG5CTMVRXEZLUKN2XAZLSKNSWG4TFOQ&issuer=%D0%93%D0%BE%D1%81%D1%83%D1%81%D0%BB%D1%83%D0%B3%D0%B8",
    "otpauth://totp/AWS:admin@example.com?secret=MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U&issuer=AWS&digits=8&period=60",
)
