package cx.m42.unitool

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cx.m42.superizer.host.storage.PrefsStorage
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
        drive: DriveScope.() -> Unit,
    ) {
        // A fresh store for every frame: a screenshot must show what a new install looks like,
        // not what the previous capture left behind.
        PrefsStorage.useDirectory(Files.createTempDirectory("unitool-shot").toFile())
        runDesktopComposeUiTest(width = viewport.widthPx, height = viewport.heightPx) {
            val superizer = testSuperizer(network = if (offline) offlineNetwork() else ratesNetwork())
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
                tapTag = { tag -> onNodeWithTag(tag).performClick(); waitForIdle() },
                typeInto = { tag, text -> onNodeWithTag(tag).performTextInput(text); waitForIdle() },
            ).drive()
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
        val drive: DriveScope.() -> Unit,
    )

    /**
     * Every screen, declared once as data rather than written out twice, so a screen added for the
     * README cannot go missing from the store listing.
     */
    private val screens: List<Screen> = listOf(
        Screen("01-all-apps") {},
        Screen("02-menu") { tapIcon("Открыть меню") },
        Screen("03-calculator") { tapTag(CALCULATOR_TILE) },
        Screen("04-calculator-scientific") {
            tapIcon("Открыть меню")
            tapIcon("Активировать")
            typeInto("activate:promo", "SCI")
            tapTag("activate:apply")
        },
        Screen("05-converter") { tapTag(CONVERTER_TILE) },
        Screen("06-converter-offline", offline = true) { tapTag(CONVERTER_TILE) },
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
    )

    @Test
    fun captureEveryScreen() {
        for (viewport in listOf(Phone, PlayListing)) {
            // Emptied first. A frame left over from a screen that no longer exists is worse than a
            // missing one: it is the picture somebody uploads to a store listing by mistake.
            File(viewport.directory).listFiles()?.forEach { if (it.name.endsWith(".png")) it.delete() }
            for (screen in screens) {
                shot(viewport, screen.name, screen.lang, screen.offline, screen.drive)
            }
        }
    }
}
