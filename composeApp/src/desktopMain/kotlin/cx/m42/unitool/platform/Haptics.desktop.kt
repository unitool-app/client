package cx.m42.unitool.platform

/** Desktop machines have no vibration motor, so an action is signalled visually only. */
internal actual fun platformHapticTick() {
    // Intentionally empty.
}
