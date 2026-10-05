package dev.dunno.emula

import android.util.Log

/** Keeps hardware diagnostics available in debug builds and silent in Play releases. */
internal object AppLog {
    private const val TAG = "Emula"

    @JvmStatic
    fun info(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    @JvmStatic
    @JvmOverloads
    fun warning(message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    @JvmStatic
    fun error(message: String, error: Throwable) {
        if (BuildConfig.DEBUG) Log.e(TAG, message, error)
    }
}
