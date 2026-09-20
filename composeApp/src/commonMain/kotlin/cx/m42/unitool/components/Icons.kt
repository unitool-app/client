package cx.m42.unitool.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's icon pack, drawn by hand. The project pulls in no icon dependency, and at this handful
 * of shapes a few line calls cost far less than one — but drawing them ad hoc is what makes glyphs
 * disagree with each other, so they all share the geometry below instead.
 *
 * Every icon is authored on a unit square in fractional coordinates, stroked at one ratio with
 * round caps and joins, and sized by the call site rather than by itself. The proportions follow
 * GitHub's Octicons, which is what the glyph set here mirrors.
 */

/** The nominal box an icon is drawn in. Call sites override it; the proportions never change. */
val IconSize: Dp = 20.dp

/** Stroke width as a fraction of the box, so a glyph keeps its weight at any size. */
private const val StrokeRatio = 0.085f

/**
 * Sets up one icon's canvas and hands the body a scope where coordinates are fractions of the box
 * and the stroke is already the right weight. Keeping this in one place is what makes a burger and
 * a cog look like they came from the same pack.
 */
@Composable
private fun Icon(
    size: Dp,
    body: DrawScope.(IconScope) -> Unit,
) {
    Canvas(modifier = Modifier.size(size)) {
        body(IconScope(this.size.width, this.size.height))
    }
}

/**
 * Fractional coordinates for an icon body: [x] and [y] map 0f..1f onto the box, so a glyph is
 * written once and scales to whatever size it is asked for.
 */
private class IconScope(val w: Float, val h: Float) {
    fun x(fraction: Float) = w * fraction

    fun y(fraction: Float) = h * fraction

    fun at(xf: Float, yf: Float) = Offset(x(xf), y(yf))

    /** The shared stroke weight — every glyph in the pack draws with this and nothing else. */
    val strokeWidth: Float get() = w * StrokeRatio

    val stroke: Stroke
        get() = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
}

/** A straight run between two fractional points, in the pack's weight. */
private fun DrawScope.line(s: IconScope, tint: Color, x1: Float, y1: Float, x2: Float, y2: Float) {
    drawLine(tint, s.at(x1, y1), s.at(x2, y2), s.strokeWidth, StrokeCap.Round)
}

/** An outlined circle centred on a fractional point. */
private fun DrawScope.circle(s: IconScope, tint: Color, cx: Float, cy: Float, r: Float) {
    drawCircle(tint, radius = s.w * r, center = s.at(cx, cy), style = s.stroke)
}

/** The burger that opens the menu: three evenly spaced rules. */
@Composable
fun BurgerIcon(tint: Color, size: Dp = IconSize) {
    Icon(size) { s ->
        listOf(0.26f, 0.5f, 0.74f).forEach { y ->
            line(s, tint, 0.14f, y, 0.86f, y)
        }
    }
}

/** A left-pointing chevron for the back affordance. */
@Composable
fun BackIcon(tint: Color, size: Dp = IconSize) {
    Icon(size) { s ->
        val path = Path().apply {
            moveTo(s.x(0.62f), s.y(0.2f))
            lineTo(s.x(0.32f), s.y(0.5f))
            lineTo(s.x(0.62f), s.y(0.8f))
        }
        drawPath(path, tint, style = s.stroke)
    }
}

/** A cog, for the settings page. */
@Composable
fun SettingsIcon(tint: Color, size: Dp = IconSize) {
    Icon(size) { s ->
        circle(s, tint, 0.5f, 0.5f, 0.17f)

        val inner = 0.26f
        val outer = 0.4f
        repeat(8) { i ->
            // Eight spokes at 45° each, started at 22.5° so no tooth sits on the vertical axis —
            // one straight up reads as a stray mark rather than as part of the ring.
            val angle = (i * 45f + 22.5f) * (kotlin.math.PI.toFloat() / 180f)
            val dx = kotlin.math.cos(angle)
            val dy = kotlin.math.sin(angle)
            line(
                s, tint,
                0.5f + dx * inner, 0.5f + dy * inner,
                0.5f + dx * outer, 0.5f + dy * outer,
            )
        }
    }
}

/** A tick, for the chosen row of a list of choices. */
@Composable
fun CheckIcon(tint: Color, size: Dp = IconSize) {
    Icon(size) { s ->
        val path = Path().apply {
            moveTo(s.x(0.18f), s.y(0.52f))
            lineTo(s.x(0.42f), s.y(0.74f))
            lineTo(s.x(0.82f), s.y(0.26f))
        }
        drawPath(path, tint, style = s.stroke)
    }
}

/** The backspace arrow on the calculator's ⌫ key. */
@Composable
fun BackspaceIcon(tint: Color, size: Dp = IconSize) {
    Icon(size) { s ->
        val body = Path().apply {
            moveTo(s.x(0.34f), s.y(0.22f))
            lineTo(s.x(0.9f), s.y(0.22f))
            lineTo(s.x(0.9f), s.y(0.78f))
            lineTo(s.x(0.34f), s.y(0.78f))
            lineTo(s.x(0.08f), s.y(0.5f))
            close()
        }
        drawPath(body, tint, style = s.stroke)
        line(s, tint, 0.48f, 0.38f, 0.74f, 0.62f)
        line(s, tint, 0.74f, 0.38f, 0.48f, 0.62f)
    }
}
