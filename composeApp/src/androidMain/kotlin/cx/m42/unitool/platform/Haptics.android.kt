package cx.m42.unitool.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Holds the system vibrator. [initHaptics] must run before the first vibration; `MainActivity`
 * does it, mirroring how [cx.m42.unitool.data.PrefsStorage] is wired up.
 */
private object Haptics {
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        // The application context, not the activity: this object outlives any one activity, and
        // holding an activity here would leak it across a rotation.
        val app = context.applicationContext
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // getSystemService(VIBRATOR_SERVICE) still works on API 31+ but is deprecated, and on a
            // multi-motor device it picks an arbitrary one; the manager names the default.
            val manager = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun tick() {
        val vibrator = vibrator ?: return
        // hasVibrator() is false on tablets and emulators without a motor; vibrate() would be a
        // silent no-op there anyway, but checking keeps the intent clear.
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A predefined effect is tuned per device, so it feels like the rest of the system
            // rather than a raw buzz of a duration we picked.
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20L)
        }
    }
}

/** Wires haptics to a context. Call once, before [cx.m42.unitool.App] first composes. */
fun initHaptics(context: Context) = Haptics.init(context)

internal actual fun platformHapticTick() = Haptics.tick()
