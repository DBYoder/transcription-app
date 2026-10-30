package io.github.dbyoder.transcribe.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import io.github.dbyoder.transcribe.core.TextJoin
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/** Puts dictated text into the text box that currently has the cursor, in any app. */
object TextInserter {
    /** How the text got in (or didn't). Saved with each dictation to make problems easy to spot. */
    enum class Method(val label: String, val inserted: Boolean) {
        TYPED("typed", true),
        PASTED("pasted", true),
        SET_TEXT("set", true),
        COPIED_NO_FIELD("copied: no text box found", false),
        COPIED_PASSWORD("copied: password field", false),
        COPIED_REJECTED("copied: the app didn't accept the text", false),
    }

    /** @param text exactly what went into the box, including any space added before or after. */
    data class Result(val method: Method, val text: String)

    private const val CHECK_INTERVAL_MS = 100L
    private const val CHECK_ATTEMPTS = 6

    /**
     * Tries, in order, checking each time that the text box really changed:
     * 1. Type it, the way a keyboard does (Android 13+ lets accessibility services do this).
     *    No clipboard involved.
     * 2. Paste at the cursor via the clipboard.
     * 3. Replace the box's text with the dictation spliced in at the cursor.
     * 4. Leave it on the clipboard for the user to paste.
     */
    suspend fun insert(service: AccessibilityService, text: String): Result {
        val node = focusedTextBox(service)
        if (node?.isPassword == true) {
            copy(service, text.trim())
            return Result(Method.COPIED_PASSWORD, text.trim())
        }
        val before = node?.let(::currentText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val typed = typeWithInputMethod(service, text)
            // Without a node to check, trust the keyboard-style input: a fallback could duplicate the text.
            if (typed != null && (node == null || waitForChange(node, before))) return Result(Method.TYPED, typed)
        }

        if (node != null) {
            val existing = before.orEmpty()
            val (selStart, selEnd) = selection(node, existing)
            val insertion = TextJoin.forInsertion(text, existing.substring(0, selStart), existing.substring(selEnd))

            copy(service, insertion)
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE) && waitForChange(node, before)) {
                return Result(Method.PASTED, insertion)
            }
            val updated = existing.substring(0, selStart) + insertion + existing.substring(selEnd)
            if (setText(node, updated, cursor = selStart + insertion.length) && waitForChange(node, before)) {
                return Result(Method.SET_TEXT, insertion)
            }
        }

        copy(service, text.trim())
        return Result(if (node == null) Method.COPIED_NO_FIELD else Method.COPIED_REJECTED, text.trim())
    }

    /**
     * Swaps [old] (text this app just put in, right before the cursor) for [new]. Used by the
     * formatting buttons and their Undo. Returns false if the text box has changed since, so the
     * caller can fall back to the clipboard rather than edit the wrong text.
     */
    suspend fun replace(service: AccessibilityService, old: String, new: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && replaceWithInputMethod(service, old, new)) {
            return true
        }
        val node = focusedTextBox(service) ?: return false
        if (node.isPassword) return false
        val existing = currentText(node) ?: return false
        val index = existing.lastIndexOf(old)
        if (index < 0) return false
        val updated = existing.substring(0, index) + new + existing.substring(index + old.length)
        return setText(node, updated, cursor = index + new.length) && waitForChange(node, existing)
    }

    fun copy(service: AccessibilityService, text: String) {
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }

    private fun focusedTextBox(service: AccessibilityService): AccessibilityNodeInfo? =
        runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }

    /** Commits the text through the accessibility input method. Returns what was typed, or null. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun typeWithInputMethod(service: AccessibilityService, text: String): String? {
        val connection = inputConnection(service) ?: return null
        val around = runCatching { connection.getSurroundingText(200, 200, 0) }.getOrNull()
        val insertion = if (around != null) {
            val surrounding = around.text.toString()
            val selStart = around.selectionStart.coerceIn(0, surrounding.length)
            val selEnd = around.selectionEnd.coerceIn(selStart, surrounding.length)
            TextJoin.forInsertion(text, surrounding.substring(0, selStart), surrounding.substring(selEnd))
        } else {
            text.trim()
        }
        connection.commitText(insertion, 1, null)
        return insertion
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun replaceWithInputMethod(service: AccessibilityService, old: String, new: String): Boolean {
        val connection = inputConnection(service) ?: return false
        val around = runCatching { connection.getSurroundingText(old.length + 8, 0, 0) }.getOrNull() ?: return false
        if (around.selectionStart != around.selectionEnd) return false // something is selected; leave it
        val text = around.text.toString()
        val beforeCursor = text.substring(0, around.selectionStart.coerceIn(0, text.length))
        if (!beforeCursor.endsWith(old)) return false
        connection.deleteSurroundingText(old.length, 0)
        connection.commitText(new, 1, null)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun inputConnection(service: AccessibilityService) = service.inputMethod
        ?.takeIf { it.currentInputStarted }
        ?.takeUnless { im -> im.currentInputEditorInfo?.let { isPasswordInput(it.inputType) } == true }
        ?.currentInputConnection

    private fun selection(node: AccessibilityNodeInfo, existing: String): Pair<Int, Int> {
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start !in 0..existing.length || end !in 0..existing.length) {
            start = existing.length
            end = existing.length
        }
        return min(start, end) to max(start, end)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        return true
    }

    /** The box's real text (not its grey placeholder), or null if the app doesn't expose it. */
    private fun currentText(node: AccessibilityNodeInfo): String? {
        if (node.isShowingHintText) return ""
        val text = node.text?.toString() ?: return null
        val hint = node.hintText?.toString()
        return if (hint != null && text == hint) "" else text
    }

    /** Waits briefly for the app to show the new text. True if the box's text changed. */
    private suspend fun waitForChange(node: AccessibilityNodeInfo, before: String?): Boolean {
        if (before == null) return true // the app doesn't expose its text, so there's nothing to check
        repeat(CHECK_ATTEMPTS) {
            delay(CHECK_INTERVAL_MS)
            if (node.refresh() && currentText(node) != before) return true
        }
        return false
    }

    private fun isPasswordInput(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
