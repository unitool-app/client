package cx.m42.unitool.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal, monochrome design tokens inspired by shadcn/ui, Radix and Tailwind: a white surface,
 * near-black text and a single black primary accent — plus a small set of state colours. Colour is
 * reserved for *state* (успех, ошибка, внимание), never for chrome.
 *
 * Every screen reads its colours and its type scale from here and from nowhere else. That is the
 * whole of the app's look: there is no Material theme underneath, so a value not in this file is a
 * value some screen invented on its own.
 */
@Immutable
object AppTheme {
    val Background: Color = Color(0xFFFFFFFF)
    val Foreground: Color = Color(0xFF09090B)
    val Primary: Color = Color(0xFF18181B)
    val PrimaryForeground: Color = Color(0xFFFAFAFA)
    val Muted: Color = Color(0xFF71717A)
    val Border: Color = Color(0xFFE4E4E7)
    val Surface: Color = Color(0xFFFAFAFA)

    /**
     * The backdrop the slide-out menu sits on. A touch darker than [Surface] so the white screen
     * pushed on top of it reads as a card lifted off the page, and so the rounded corners of that
     * card have something to show against.
     */
    val MenuBackground: Color = Color(0xFFF4F4F5)

    /**
     * The backdrop of a screen built out of card blocks: a light grey page with white [Background]
     * cards on it, so the blocks read as lifted panels rather than as rules drawn on paper.
     * Screens that are one continuous surface keep [Background].
     */
    val PageBackground: Color = Color(0xFFF2F2F7)

    val Danger: Color = Color(0xFFDC2626)
    val Success: Color = Color(0xFF1A7F37)
    val Warning: Color = Color(0xFFD97706)
    val Disabled: Color = Color(0xFFA1A1AA)

    /** The blue reserved for "this needs a person" — kept apart from [Primary], which is chrome. */
    val Accent: Color = Color(0xFF0969DA)
    val AccentSubtle: Color = Color(0xFFDDF4FF)
    val DangerSubtle: Color = Color(0xFFFFEBE9)

    val Radius: Dp = 12.dp

    val Title: TextStyle = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold,
    )

    val Header: TextStyle = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
    )

    val Subtitle: TextStyle = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    )

    val Body: TextStyle = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    )

    val Label: TextStyle = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    )

    val ButtonLabel: TextStyle = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
    )

    /** Metadata scale — a footnote under a row, a build stamp. */
    val Footnote: TextStyle = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )

    /** The calculator's own scale: one line of digits, as large as a phone can carry. */
    val Display: TextStyle = TextStyle(
        fontSize = 56.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.Light,
    )

    /** A key's label — big enough to hit by eye, light enough not to shout. */
    val KeyLabel: TextStyle = TextStyle(
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Normal,
    )
}
