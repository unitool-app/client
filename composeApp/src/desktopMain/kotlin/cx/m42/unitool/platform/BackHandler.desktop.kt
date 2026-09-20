package cx.m42.unitool.platform

import androidx.compose.runtime.Composable

/**
 * Nothing to intercept: a desktop window has no back gesture and no back button, and the screen's
 * own back arrow in the top bar is the whole of the affordance there.
 */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
