package cx.m42.unitool.platform

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler as ActivityBackHandler

/**
 * Straight onto the activity's `OnBackPressedDispatcher`, which is what the system back gesture,
 * the back button and the three-button bar all arrive through — and, from Android 13, what drives
 * the predictive-back animation as well.
 */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) =
    ActivityBackHandler(enabled = enabled, onBack = onBack)
