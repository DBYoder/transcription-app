package io.github.dbyoder.transcribe

import android.content.Context
import android.content.SharedPreferences

const val DEFAULT_THREADS = 4

/** Small user preferences, shared by the app screen and the bubble. */
object AppSettings {
    private const val KEY_THREADS = "threads"
    private const val KEY_BUBBLE_LEFT = "bubble_left"
    private const val KEY_BUBBLE_OFFSET_DP = "bubble_offset_dp"
    private const val KEY_CLEAN_UP = "clean_up"

    /** How far the bubble's bottom sits above the top of the keyboard, by default. */
    const val DEFAULT_BUBBLE_OFFSET_DP = 96f

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun threads(context: Context): Int = prefs(context).getInt(KEY_THREADS, DEFAULT_THREADS)

    fun setThreads(context: Context, threads: Int) {
        prefs(context).edit().putInt(KEY_THREADS, threads).apply()
    }

    /** Remove filler words and stutters, and turn "new paragraph" into a line break. */
    fun cleanUp(context: Context): Boolean = prefs(context).getBoolean(KEY_CLEAN_UP, true)

    fun setCleanUp(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLEAN_UP, enabled).apply()
    }

    fun bubbleOnLeft(context: Context): Boolean = prefs(context).getBoolean(KEY_BUBBLE_LEFT, false)

    fun bubbleOffsetDp(context: Context): Float =
        prefs(context).getFloat(KEY_BUBBLE_OFFSET_DP, DEFAULT_BUBBLE_OFFSET_DP)

    fun setBubblePosition(context: Context, onLeft: Boolean, offsetDp: Float) {
        prefs(context).edit()
            .putBoolean(KEY_BUBBLE_LEFT, onLeft)
            .putFloat(KEY_BUBBLE_OFFSET_DP, offsetDp)
            .apply()
    }
}
