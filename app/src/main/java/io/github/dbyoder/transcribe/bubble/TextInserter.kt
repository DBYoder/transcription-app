package io.github.dbyoder.transcribe.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import io.github.dbyoder.transcribe.core.TextJoin
import kotlinx.coroutines.delay
import kotlin.math.abs
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
    private const val SEND_CHECK_ATTEMPTS = 12
    private const val MAX_NODES_SEARCHED = 2000

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

    /**
     * The text selected in the focused text box, or null if nothing (or only spaces) is selected,
     * it's a password field, or the app doesn't let us read it.
     */
    fun selectedText(service: AccessibilityService): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val connection = inputConnection(service)
            val around = connection?.let { runCatching { it.getSurroundingText(0, 0, 0) }.getOrNull() }
            if (around != null) return selectionOf(around.text.toString(), around.selectionStart, around.selectionEnd)
        }
        val node = focusedTextBox(service) ?: return null
        if (node.isPassword) return null
        val text = currentText(node) ?: return null
        return selectionOf(text, node.textSelectionStart, node.textSelectionEnd)
    }

    /**
     * Swaps the selection for [new] if [selected] is still what's selected. Returns false if the
     * selection has changed, so the caller can fall back to the clipboard.
     */
    suspend fun replaceSelection(service: AccessibilityService, selected: String, new: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val connection = inputConnection(service)
            val around = connection?.let { runCatching { it.getSurroundingText(0, 0, 0) }.getOrNull() }
            if (connection != null && around != null) {
                if (selectionOf(around.text.toString(), around.selectionStart, around.selectionEnd) != selected) return false
                connection.commitText(new, 1, null) // replaces the selection, like typing over it
                return true
            }
        }
        val node = focusedTextBox(service) ?: return false
        if (node.isPassword) return false
        val existing = currentText(node) ?: return false
        val start = min(node.textSelectionStart, node.textSelectionEnd)
        val end = max(node.textSelectionStart, node.textSelectionEnd)
        if (start < 0 || end > existing.length || existing.substring(start, end) != selected) return false
        val updated = existing.substring(0, start) + new + existing.substring(end)
        return setText(node, updated, cursor = start + new.length) && waitForChange(node, existing)
    }

    /**
     * Sends the message, trying in turn: the app's Send button, the text box's own Send / Go /
     * Search action, and the Enter key (single-line boxes only, so it never just adds a line
     * break). After each, it checks the text box emptied, and moves on to the next if not.
     * Returns false if nothing worked.
     */
    suspend fun pressSend(service: AccessibilityService): Boolean {
        val box = focusedTextBox(service)
        val before = box?.let(::currentText)
        val attempts: List<() -> Boolean> = listOf(
            { clickSendButton(service, box) },
            { editorAction(service) },
            { enterKey(service) },
        )
        for (attempt in attempts) {
            if (!runCatching { attempt() }.getOrDefault(false)) continue
            // Without the box's text we can't tell whether it worked, so trust it.
            if (box == null || before.isNullOrEmpty() || waitForSent(box, before)) return true
        }
        return false
    }

    /** True once the box has emptied (or gone, as when an email's compose screen closes). */
    private suspend fun waitForSent(box: AccessibilityNodeInfo, before: String): Boolean {
        repeat(SEND_CHECK_ATTEMPTS) {
            delay(CHECK_INTERVAL_MS)
            if (!box.refresh()) return true
            val now = currentText(box) ?: return true
            if (now.isBlank() || now.length < before.length / 2) return true
        }
        return false
    }

    /** The text box's own action, if it's one that sends or submits (not just "Done"). */
    private fun editorAction(service: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val info = service.inputMethod?.currentInputEditorInfo ?: return false
        val connection = inputConnection(service) ?: return false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action !in SEND_ACTIONS) return false
        return connection.performEditorAction(action)
    }

    private val SEND_ACTIONS = setOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH)

    /** Enter, but only in a single-line box, where it submits rather than starting a new line. */
    private fun enterKey(service: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val info = service.inputMethod?.currentInputEditorInfo ?: return false
        if ((info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) return false
        val connection = inputConnection(service) ?: return false
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        return true
    }

    /**
     * Taps the app's Send button ("Send", "Send message", "Send SMS"...): in the text box's own
     * window first, then any other app window. The nearest one to the text box wins.
     */
    private fun clickSendButton(service: AccessibilityService, box: AccessibilityNodeInfo?): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching { box?.window?.root }.getOrNull()?.let { roots += it }
        runCatching { service.rootInActiveWindow }.getOrNull()?.let { roots += it }
        runCatching { service.windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .forEach { w -> runCatching { w.root }.getOrNull()?.let { roots += it } }
        val boxBounds = box?.let { Rect().also(it::getBoundsInScreen) }
        val tried = HashSet<Int>()
        for (root in roots) {
            if (!tried.add(root.windowId)) continue
            val buttons = sendButtons(root)
            val ordered = buttons.sortedWith(
                compareBy<AccessibilityNodeInfo>({ if (label(it) == "send") 0 else 1 }, { distance(it, boxBounds) }),
            )
            for (button in ordered) {
                var target: AccessibilityNodeInfo? = button
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    /**
     * Send buttons in a window. Android's text search is quick but many apps (anything built with
     * Jetpack Compose, like Google Messages) don't answer it, so if it finds nothing, walk the
     * window's contents.
     */
    private fun sendButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = runCatching { root.findAccessibilityNodeInfosByText("send") }.getOrNull().orEmpty().filter(::isSendButton)
        if (found.isNotEmpty()) return found
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES_SEARCHED) {
            val node = queue.removeFirst()
            visited++
            if (isSendButton(node)) out += node
            for (i in 0 until node.childCount) runCatching { node.getChild(i) }.getOrNull()?.let(queue::add)
        }
        return out
    }

    private fun label(node: AccessibilityNodeInfo): String =
        (node.contentDescription ?: node.text ?: node.tooltipText)?.toString()?.trim()?.lowercase().orEmpty()

    private fun isSendButton(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled || node.isEditable) return false
        val label = label(node)
        if (label == "send") return true
        if (!label.startsWith("send ") || label.length > 40) return false
        return NOT_SEND_BUTTONS.none { it in label }
    }

    /** "Send ..." buttons that don't send the message you just typed. */
    private val NOT_SEND_BUTTONS = listOf(
        "money", "payment", "pay", "voice", "audio", "photo", "picture", "image", "video", "gif", "sticker",
        "file", "attachment", "location", "feedback", "invite", "contact", "later", "schedule",
    )

    private fun distance(node: AccessibilityNodeInfo, box: Rect?): Int {
        if (box == null) return 0
        val r = Rect().also(node::getBoundsInScreen)
        return abs(r.centerY() - box.centerY()) + abs(r.centerX() - box.right) / 4
    }

    /** text[start until end], if that's a sensible, non-blank range. */
    private fun selectionOf(text: String, start: Int, end: Int): String? {
        val from = min(start, end)
        val to = max(start, end)
        if (from < 0 || to > text.length || from == to) return null
        return text.substring(from, to).takeIf { it.isNotBlank() }
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
