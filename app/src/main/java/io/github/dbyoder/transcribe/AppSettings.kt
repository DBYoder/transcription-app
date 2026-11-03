package io.github.dbyoder.transcribe

import android.content.Context
import android.content.SharedPreferences
import io.github.dbyoder.transcribe.core.FormatStyle

const val DEFAULT_THREADS = 4

/** Small user preferences, shared by the app screen and the bubble. */
object AppSettings {
    private const val KEY_THREADS = "threads"
    private const val KEY_BUBBLE_LEFT = "bubble_left"
    private const val KEY_BUBBLE_OFFSET_DP = "bubble_offset_dp"
    private const val KEY_CLEAN_UP = "clean_up"
    private const val KEY_LAST_FORMAT_USE = "last_format_use"
    private const val KEY_HIDDEN_BUBBLE_STYLES = "hidden_bubble_styles"
    private const val KEY_COMMAND_MODE = "command_mode"
    private const val KEY_AUTO_POLISH = "auto_polish"
    private const val KEY_BOOST_WORDS = "boost_words"
    private const val KEY_USE_CONTACTS = "use_contacts"
    private const val KEY_JOURNAL_FOLDER = "journal_folder"
    private const val KEY_JOURNAL_LABELS = "journal_labels"
    private const val RECENT_FORMAT_MS = 24 * 60 * 60 * 1000L

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

    fun markFormatUsed(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_FORMAT_USE, System.currentTimeMillis()).apply()
    }

    /** True if an AI formatting button was used in the last day (worth loading the model early). */
    fun formattedRecently(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_FORMAT_USE, 0L) < RECENT_FORMAT_MS

    /**
     * The AI formatting buttons that pop up beside the bubble, in their usual order. Stores the
     * ones switched off, so any style added in a later version shows up by default.
     */
    fun bubbleStyles(context: Context): List<FormatStyle> {
        val hidden = hiddenBubbleStyles(context)
        return FormatStyle.entries.filter { it.name !in hidden }
    }

    fun setBubbleStyleShown(context: Context, style: FormatStyle, shown: Boolean) {
        val hidden = hiddenBubbleStyles(context).toMutableSet()
        if (shown) hidden -= style.name else hidden += style.name
        prefs(context).edit().putString(KEY_HIDDEN_BUBBLE_STYLES, hidden.joinToString(",")).apply()
    }

    private fun hiddenBubbleStyles(context: Context): Set<String> =
        prefs(context).getString(KEY_HIDDEN_BUBBLE_STYLES, null).orEmpty()
            .split(',').filter { it.isNotEmpty() }.toSet()

    /** Select text, tap the bubble and say what to do with it (needs the AI model). */
    fun commandMode(context: Context): Boolean = prefs(context).getBoolean(KEY_COMMAND_MODE, true)

    fun setCommandMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMMAND_MODE, enabled).apply()
    }

    /** Run Polish on every bubble dictation before typing it (needs the AI model). */
    fun autoPolish(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_POLISH, false)

    fun setAutoPolish(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_POLISH, enabled).apply()
    }

    /** Have the speech model listen out for the words in your dictionary. */
    fun boostWords(context: Context): Boolean = prefs(context).getBoolean(KEY_BOOST_WORDS, true)

    fun setBoostWords(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOST_WORDS, enabled).apply()
    }

    /** Listen out for the names in your contacts (also needs the contacts permission). */
    fun useContacts(context: Context): Boolean = prefs(context).getBoolean(KEY_USE_CONTACTS, false)

    fun setUseContacts(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_CONTACTS, enabled).apply()
    }

    /** Where the journal lives: a folder's URI, "private" (inside the app), or null if not chosen yet. */
    fun journalFolder(context: Context): String? = prefs(context).getString(KEY_JOURNAL_FOLDER, null)

    fun setJournalFolder(context: Context, value: String) {
        prefs(context).edit().putString(KEY_JOURNAL_FOLDER, value).apply()
    }

    /** Have the AI add a title and tags to each new journal entry. */
    fun journalLabels(context: Context): Boolean = prefs(context).getBoolean(KEY_JOURNAL_LABELS, true)

    fun setJournalLabels(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_JOURNAL_LABELS, enabled).apply()
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
