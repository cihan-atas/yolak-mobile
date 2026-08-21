package app.yolaq.mobile.recording

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

private const val TAG = "Haptics"

/**
 * The off-route buzz: two short pulses, distinct from a notification's one.
 *
 * Long enough to register through a sleeve, short enough not to read as an
 * alarm. Two pulses because one is what every other thing on the phone does,
 * and this one means something specific.
 */
private val OFF_ROUTE_PATTERN = longArrayOf(0, 220, 140, 220)

/**
 * Buzzes the phone once to say the athlete has left the route.
 *
 * The on-screen notice is the full message; this is the part that arrives when
 * the screen is dark and the phone is on an arm or in a pocket, which is where
 * it is for most of an outing. Silently does nothing on a device with no
 * vibrator — a missing motor is not a reason to interrupt a recording.
 *
 * @param context Any context.
 */
fun vibrateOffRoute(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    if (vibrator == null || !vibrator.hasVibrator()) {
        return
    }

    try {
        vibrator.vibrate(VibrationEffect.createWaveform(OFF_ROUTE_PATTERN, -1))
    } catch (error: Exception) {
        // Some vendors' vibrators reject patterns while in a power-saving
        // mode. Losing the buzz is not worth losing the recording.
        Log.w(TAG, "Titreşim verilemedi", error)
    }
}
