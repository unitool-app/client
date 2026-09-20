package cx.m42.unitool.platform

/**
 * The Vibration API, which in practice means Chrome on Android — desktop browsers and every iOS
 * browser expose no motor. [vibrateMillis] returns false wherever it is unsupported or the page
 * lacks the user activation the spec requires, so this stays a silent no-op there.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal actual fun platformHapticTick() {
    vibrateMillis(20)
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun vibrateMillis(millis: Int): Boolean =
    js("(navigator.vibrate && navigator.vibrate(millis)) || false")
