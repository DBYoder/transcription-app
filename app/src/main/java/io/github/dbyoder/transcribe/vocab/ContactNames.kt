package io.github.dbyoder.transcribe.vocab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import io.github.dbyoder.transcribe.AppSettings
import io.github.dbyoder.transcribe.core.ContactNameFilter

/**
 * The names in your contacts, for the speech model to listen out for. Only first, middle and
 * last names are read (no numbers or anything else), they stay in memory on the phone, and the
 * app has no internet access, so they can't go anywhere.
 */
object ContactNames {
    private const val REFRESH_MS = 30 * 60 * 1000L

    @Volatile private var names: List<String> = emptyList()
    @Volatile private var loadedAt = 0L
    @Volatile private var stale = true
    private var observer: ContentObserver? = null

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun inUse(context: Context): Boolean = AppSettings.useContacts(context) && allowed(context)

    /**
     * The names, reading your contacts again if they've changed or it's been a while. Empty when
     * the setting is off or access isn't allowed. Reads a database: call off the main thread.
     */
    fun load(context: Context): List<String> {
        if (!inUse(context)) return emptyList()
        val now = SystemClock.elapsedRealtime()
        if (stale || now - loadedAt > REFRESH_MS) {
            names = runCatching { ContactNameFilter.words(query(context)) }.getOrDefault(names)
            loadedAt = now
            stale = false
            watch(context.applicationContext)
        }
        return names
    }

    /** The names from the last [load], without reading contacts (fine on the main thread). */
    fun cached(context: Context): List<String> = if (inUse(context)) names else emptyList()

    /** Read contacts afresh next time (after the setting or permission changes). */
    fun invalidate() {
        stale = true
    }

    /** Name fields of every contact, starred ones first. */
    private fun query(context: Context): List<String> {
        val out = ArrayList<String>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(StructuredName.GIVEN_NAME, StructuredName.MIDDLE_NAME, StructuredName.FAMILY_NAME),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(StructuredName.CONTENT_ITEM_TYPE),
            "${ContactsContract.Contacts.STARRED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                for (i in 0 until cursor.columnCount) cursor.getString(i)?.let { out += it }
            }
        }
        return out
    }

    /** Notices when contacts change, so new names are picked up. */
    @Synchronized
    private fun watch(context: Context) {
        if (observer != null) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                stale = true
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, o)
            observer = o
        }
    }
}
