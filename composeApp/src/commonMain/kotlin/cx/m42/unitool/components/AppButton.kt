package cx.m42.unitool.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composeunstyled.Button
import com.composeunstyled.Text
import cx.m42.unitool.theme.AppTheme

/**
 * Visual weight of a button. [Destructive] is a *tint*, not a filled red button: a row's own title
 * has to stay the loudest thing in it.
 */
enum class ButtonVariant { Default, Outline, Secondary, Ghost, Destructive, Accent }

/** [Lg] is the page-sized button; [Sm] is the one that fits inside a row. */
enum class ButtonSize { Sm, Default, Lg }

/**
 * A button built on the Compose Unstyled [Button] primitive and styled to match the app's
 * monochrome, rounded look — the one place a button's colours are decided, so no screen has to
 * pick them.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Lg,
) {
    val background = when (variant) {
        ButtonVariant.Default -> AppTheme.Primary
        ButtonVariant.Secondary -> AppTheme.Surface
        ButtonVariant.Outline, ButtonVariant.Ghost -> AppTheme.Background
        ButtonVariant.Destructive -> AppTheme.DangerSubtle
        ButtonVariant.Accent -> AppTheme.AccentSubtle
    }
    val foreground = when (variant) {
        ButtonVariant.Default -> AppTheme.PrimaryForeground
        ButtonVariant.Secondary, ButtonVariant.Outline, ButtonVariant.Ghost -> AppTheme.Foreground
        ButtonVariant.Destructive -> AppTheme.Danger
        ButtonVariant.Accent -> AppTheme.Accent
    }
    val border = when (variant) {
        ButtonVariant.Outline, ButtonVariant.Secondary -> AppTheme.Border
        else -> Color.Transparent
    }
    val padding = when (size) {
        ButtonSize.Sm -> PaddingValues(horizontal = 12.dp, vertical = 7.dp)
        ButtonSize.Default -> PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ButtonSize.Lg -> PaddingValues(horizontal = 40.dp, vertical = 18.dp)
    }

    Button(
        // Compose Unstyled's Button has no disabled state of its own, so gate the click here and
        // dim the surface to signal it.
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        shape = RoundedCornerShape(if (size == ButtonSize.Sm) 8.dp else AppTheme.Radius),
        backgroundColor = if (enabled) background else AppTheme.Disabled,
        contentColor = if (enabled) foreground else AppTheme.PrimaryForeground,
        borderColor = if (enabled) border else Color.Transparent,
        borderWidth = if (border == Color.Transparent) 0.dp else 1.dp,
        contentPadding = padding,
    ) {
        Text(
            text = text,
            style = if (size == ButtonSize.Sm) AppTheme.Label else AppTheme.ButtonLabel,
        )
    }
}
