package cx.m42.unitool

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
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
import cx.m42.superizer.host.storage.PrefsStorage
import cx.m42.superizer.runtime.Clock
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
     * Two of them, because the two audiences want different pictures and neither can be cropped out
     * of the other:
     *
     *  - [Phone] is the shape of a phone people actually hold — 412×915 dp, 20:9, what a Pixel
     *    reports. These are the screens in the README.
     *  - [PlayListing] is what the Play Console accepts, which is narrower: 16:9 or 9:16 and at
     *    least 1080 px on the short side. A 20:9 capture is rejected outright, so the listing gets
     *    its own render rather than a letterboxed copy.
     */
    private data class Viewport(
        val name: String,
        val widthDp: Int,
        val heightDp: Int,
        val scale: Float,
        val directory: String,
    ) {
        val widthPx: Int get() = (widthDp * scale).toInt()
        val heightPx: Int get() = (heightDp * scale).toInt()
    }

    private val Phone = Viewport("phone", 412, 915, 2f, "build/screenshots/phone")
    private val PlayListing = Viewport("play", 360, 640, 3f, "build/screenshots/play")

    /** The verbs a screenshot needs. By tag where there is one, by label where a human would look. */
    private class DriveScope(
        val tap: (String) -> Unit,
        val tapIcon: (String) -> Unit,
        val tapTag: (String) -> Unit,
        val typeInto: (String, String) -> Unit,
    )

    @OptIn(ExperimentalTestApi::class)
    private fun shot(
        viewport: Viewport,
        name: String,
        lang: String,
        offline: Boolean,
        clock: Clock?,
        drive: DriveScope.() -> Unit,
    ) {
        // A fresh store for every frame: a screenshot must show what a new install looks like,
        // not what the previous capture left behind.
        PrefsStorage.useDirectory(Files.createTempDirectory("unitool-shot").toFile())
        runDesktopComposeUiTest(width = viewport.widthPx, height = viewport.heightPx) {
            val superizer = testSuperizer(network = if (offline) offlineNetwork() else ratesNetwork()) {
                clock?.let { clock(it) }
                // These are phone screens, and a phone has a camera: without this the 2FA form
                // would be drawn as on desktop, with no scan button. Never opened by a capture.
                service(QrScanner.Key, PhoneCamera)
                // And a share sheet, which is what the 2FA export's main button needs to be drawn.
                service(FileSharer.Key, PhoneShareSheet)
            }
            superizer.settings.chooseLanguage(lang)
            setContent {
                // The window is in pixels; the app is laid out in dp. Providing the density here is
                // what makes an 824 px capture a 412 dp phone rather than a small tablet.
                CompositionLocalProvider(LocalDensity provides Density(viewport.scale)) {
                    UnitoolApp(superizer)
                }
            }
            waitForIdle()
            DriveScope(
                tap = { label -> onNodeWithText(label).performClick(); waitForIdle() },
                tapIcon = { label -> onNodeWithContentDescription(label).performClick(); waitForIdle() },
                tapTag = { tag ->
                    // Scrolled into view first: the 640 dp listing frame is short enough that a
                    // form's last button sits below the fold, and a click there lands on nothing.
                    // A node outside any scrollable has nowhere to scroll to, which is fine.
                    runCatching { onNodeWithTag(tag).performScrollTo() }
                    onNodeWithTag(tag).performClick()
                    waitForIdle()
                },
                typeInto = { tag, text -> onNodeWithTag(tag).performTextInput(text); waitForIdle() },
            ).drive()
            waitForIdle()

            // The pointer is still where the last tap was, and on desktop that draws a hover tint
            // no phone has. Moved off the window so the frame shows what a finger leaves behind.
            // A frame nobody tapped has no pointer to move, and the test API throws on that.
            runCatching { onRoot().performMouseInput { moveTo(Offset(1f, 1f)) } }
            waitForIdle()

            val directory = File(viewport.directory).apply { mkdirs() }
            ImageIO.write(
                onRoot().captureToImage().toAwtImage().withoutAlpha(),
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
            tapIcon("Активировать")
            typeInto("activate:promo", "SCI")
            tapTag("activate:apply")
        },
        Screen("05-converter") { addConverter(); tapTag(CONVERTER_TILE) },
        Screen("06-converter-offline", offline = true) { addConverter(); tapTag(CONVERTER_TILE) },
        Screen("07-activate") {
            tapIcon("Открыть меню")
            tapIcon("Активировать")
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
            tapIcon("Активировать")
            typeInto("activate:promo", SERVICE_CODE)
            tapTag("activate:apply")
        },
        Screen("11-test-app") {
            tapIcon("Открыть меню")
            tapIcon("Активировать")
            typeInto("activate:promo", cx.m42.apps.testapp.TestApp.PROMO_CODE)
            tapTag("activate:apply")
        },
        Screen("12-all-apps") { tapTag("home:add") },
        Screen("13-2fa-empty", clock = TwoFaClock) { openTwoFa() },
        Screen("14-2fa-list", clock = TwoFaClock) {
            openTwoFa()
            TwoFaLinks.forEach { addTwoFa(it) }
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
        Screen("22-2fa-import", clock = TwoFaClock) {
            openTwoFa()
            // One account already here, so the list shows both kinds of row.
            addTwoFa(TwoFaLinks.first())
            tapTag("2fa:add")
            // What `age -d` prints on the computer — the whole export at once.
            typeInto("2fa:link", TwoFaLinks.joinToString("\n"))
            tapTag("2fa:read-link")
        },
    )

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
        for (viewport in listOf(Phone, PlayListing)) {
            // Emptied first. A frame left over from a screen that no longer exists is worse than a
            // missing one: it is the picture somebody uploads to a store listing by mistake.
            File(viewport.directory).listFiles()?.forEach { if (it.name.endsWith(".png")) it.delete() }
            for (screen in screens) {
                shot(viewport, screen.name, screen.lang, screen.offline, screen.clock, screen.drive)
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
