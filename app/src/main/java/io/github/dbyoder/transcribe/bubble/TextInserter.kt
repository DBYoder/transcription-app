package io.github.dbyoder.transcribe.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import io.github.dbyoder.transcribe.core.TextJoin
import kotlin.math.max
import kotlin.math.min

/** Puts dictated text into the text box that currently has the cursor, in any app. */
object TextInserter {
    enum class Result { INSERTED, COPIED }

    /**
     * Tries, in order:
     * 1. Paste at the cursor (via the clipboard). Works in most apps, including web pages,
     *    and never touches the rest of the text.
     * 2. Replace the box's text with the dictation spliced in at the cursor.
     * 3. Leave it on the clipboard for the user to paste.
     */
    fun insert(service: AccessibilityService, text: String): Result {
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable || node.isPassword) {
            copy(service, text.trim())
            return Result.COPIED
        }

        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start !in 0..existing.length || end !in 0..existing.length) {
            start = existing.length
            end = existing.length
        }
        val selStart = min(start, end)
        val selEnd = max(start, end)
        val insertion = TextJoin.forInsertion(text, existing.substring(0, selStart), existing.substring(selEnd))

        if (node.supports(AccessibilityAction.ACTION_PASTE)) {
            copy(service, insertion)
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return Result.INSERTED
        }

        if (node.supports(AccessibilityAction.ACTION_SET_TEXT)) {
            val updated = existing.substring(0, selStart) + insertion + existing.substring(selEnd)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                val cursor = selStart + insertion.length
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                    },
                )
                return Result.INSERTED
            }
        }

        copy(service, text.trim())
        return Result.COPIED
    }

    private fun AccessibilityNodeInfo.supports(action: AccessibilityAction): Boolean =
        actionList.any { it.id == action.id }

    private fun copy(service: AccessibilityService, text: String) {
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }
}
