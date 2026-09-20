package cx.m42.unitool.platform

import cx.m42.unitool.data.AppSettings

/**
 * A single short vibration marking the end of an action — the tap you feel when a key is pressed or
 * the drawer comes to rest.
 *
 * Deliberately one-shot and parameterless: the app only ever needs "that happened", and every
 * platform can express that with its own idiomatic primitive instead of a duration this layer would
 * have to invent. Anything richer should become its own named function rather than a parameter on
 * this one.
 *
 * The preference is checked here rather than at the call sites, so turning haptics off in Settings
 * is one decision made in one place. Safe to call from any target: platforms without a motor —
 * desktop, and browsers outside Chrome on Android — do nothing rather than fail.
 */
fun hapticActionComplete() {
    if (!AppSettings.haptics) return
    platformHapticTick()
}

/** The platform's own "that happened" primitive. Never called directly — see above. */
internal expect fun platformHapticTick()
