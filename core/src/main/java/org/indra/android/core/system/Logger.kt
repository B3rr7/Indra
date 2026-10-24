package org.indra.android.core.system

import android.util.Log

object Logger {
    private const val TAG = "INDRA"

    @Volatile
    private var debugEnabled = false // Set to BuildConfig.DEBUG at runtime

    fun d(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(message: String) {
        Log.e(TAG, message)
    }

    fun e(
        message: String,
        throwable: Throwable,
    ) {
        Log.e(TAG, message, throwable)
    }

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }
}
