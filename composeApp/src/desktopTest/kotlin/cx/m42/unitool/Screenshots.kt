package cx.m42.unitool

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cx.m42.unitool.data.AppSettings
import cx.m42.unitool.i18n.AppLocale
import cx.m42.unitool.i18n.Lang
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders the app to PNG files without a browser, an emulator or a window server — the desktop
 * target composes the same `commonMain` tree every other target does, so these are the screens a
 * phone shows, at a phone's size.
 *
 * Output lands in `composeApp/build/screenshots/`. Run it with:
 *
 * ```bash
 * ./gradlew :composeApp:desktopTest --tests '*Screenshots*'
 * ```
 *
 * Kept in the test source set rather than as a script because it needs exactly what a UI test
 * needs: a composition it can drive, a clock it can settle, and the ability to tap a button before
 * capturing what that tap produced.
 */
class Screenshots {

    /**
     * A viewport to render at, and the density that makes it one.
     *
     * Two of them, because the two audiences want different pictures and neither can be cropped
     * out of the other:
     *
     * - [Phone] is the shape of a phone people actually hold — 412x915dp, 20:9, what a Pixel
     *   reports. These are the screens in the README.
     * - [PlayListing] is what the Play Console will accept, which is a narrower thing: sides
     *   between 320 and 3840px, at 16:9 or 9:16, and at least 1080px on the short side to be
     *   eligible for promotion on the store. A 20:9 capture is rejected — its long side is more
     *   than twice its short one — so the listing gets its own render at 360x640dp rather than a
     *   letterboxed copy of the other.
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

    private val Phone = Viewport("phone", 412, 915, 2f, "build/screenshots")
    private val PlayListing = Viewport("play", 360, 640, 3f, "build/screenshots/play")

    @OptIn(ExperimentalTestApi::class)
    private fun shot(
        viewport: Viewport,
        name: String,
        lang: Lang,
        drive: DriveScope.() -> Unit,
    ) {
        runDesktopComposeUiTest(width = viewport.widthPx, height = viewport.heightPx) {
            AppSettings.useInMemoryForTesting()
            AppLocale.useForTesting(lang)
            setContent {
                // The window is in pixels; the app is laid out in dp. Providing the density here is
                // what makes a 824px-wide capture a 412dp-wide phone rather than a small tablet.
                CompositionLocalProvider(LocalDensity provides Density(viewport.scale)) {
                    App()
                }
            }
            waitForIdle()
            DriveScope(
                tap = { label -> onNodeWithText(label).performClick(); waitForIdle() },
                tapIcon = { label -> onNodeWithContentDescription(label).performClick(); waitForIdle() },
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
     * Play rejects a screenshot carrying one: the store composites listings onto backgrounds of
     * its own, so an image that claims to be see-through is one it cannot place. Redrawing onto an
     * opaque white canvas rather than merely re-tagging the bytes, so a pixel that genuinely is
     * translucent resolves against the app's own background instead of against black.
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

    /** The two verbs a screenshot needs: press a labelled key, press an icon. */
    private class DriveScope(
        val tap: (String) -> Unit,
        val tapIcon: (String) -> Unit,
    )

    /**
     * Every screen, in both viewports. Declared once as data rather than written out twice, so a
     * screen added for the README cannot go missing from the store listing.
     */
    private val screens: List<Triple<String, Lang, DriveScope.() -> Unit>> = listOf(
        Triple("01-calculator", Lang.Ru) {},
        Triple("02-calculator-sum", Lang.Ru) {
            listOf("1", "2", "×", "8", "=").forEach(tap)
        },
        Triple("03-menu", Lang.Ru) {
            tapIcon("Открыть меню")
        },
        Triple("04-settings", Lang.Ru) {
            tapIcon("Открыть меню")
            tapIcon("Настройки")
        },
        Triple("05-settings-english", Lang.En) {
            tapIcon("Open menu")
            tapIcon("Settings")
        },
    )

    @Test
    fun captureEveryScreen() {
        for (viewport in listOf(Phone, PlayListing)) {
            for ((name, lang, drive) in screens) {
                shot(viewport, name, lang, drive)
            }
        }
    }
}
