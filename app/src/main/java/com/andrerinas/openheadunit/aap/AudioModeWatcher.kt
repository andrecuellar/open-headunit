package com.andrerinas.openheadunit.aap

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Watches the audio mode for the call raise, the second way into an episode: a call that only
 * registers after the projection was covered, which the confirm window cannot catch.
 *
 * The listener interface exists from API 31 only, and naming it in a class that the app loads on
 * an older unit fails before any version check can run: the verifier resolves it as soon as the
 * class holding the lambda is verified, which is the NoClassDefFoundError seen on Android 9. The
 * session that owns the listener is a nested class, so it is only loaded on units that have it,
 * and the caller holds an opaque handle instead of the type.
 */
object AudioModeWatcher {

    private const val TAG = "AudioModeWatcher"

    /** @return a handle for [stop], or null on a unit that does not have the listener. */
    fun start(context: Context, onMode: (Int) -> Unit): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Session(context.applicationContext, onMode).also { it.start() }
    }

    fun stop(handle: Any?) {
        (handle as? Session)?.stop()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class Session(
        private val context: Context,
        private val onMode: (Int) -> Unit
    ) {
        private var listener: AudioManager.OnModeChangedListener? = null

        fun start() {
            if (listener != null) return
            try {
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                val registered = AudioManager.OnModeChangedListener { mode -> onMode(mode) }
                manager.addOnModeChangedListener(ContextCompat.getMainExecutor(context), registered)
                listener = registered
            } catch (e: Exception) {
                AppLog.w(TAG, "the audio mode could not be watched: ${e.message}")
            }
        }

        fun stop() {
            val registered = listener ?: return
            listener = null
            try {
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                manager.removeOnModeChangedListener(registered)
            } catch (e: Exception) {
                AppLog.w(TAG, "watching the audio mode could not be stopped: ${e.message}")
            }
        }
    }
}
