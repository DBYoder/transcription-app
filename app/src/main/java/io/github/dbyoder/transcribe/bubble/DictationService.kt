package io.github.dbyoder.transcribe.bubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import io.github.dbyoder.transcribe.AppSettings
import io.github.dbyoder.transcribe.MainActivity
import io.github.dbyoder.transcribe.ai.Formatter
import io.github.dbyoder.transcribe.audio.AudioRecorder
import io.github.dbyoder.transcribe.audio.MAX_RECORDING_SECONDS
import io.github.dbyoder.transcribe.audio.MIN_RECORDING_SECONDS
import io.github.dbyoder.transcribe.core.FormatStyle
import io.github.dbyoder.transcribe.core.TextJoin
import io.github.dbyoder.transcribe.history.TranscriptHistory
import io.github.dbyoder.transcribe.model.ModelStore
import io.github.dbyoder.transcribe.speech.LiveTranscription
import io.github.dbyoder.transcribe.speech.SpeechEngine
import io.github.dbyoder.transcribe.speech.TextPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

/**
 * The floating dictation bubble.
 *
 * Android calls this an accessibility service. It's the only way for an app to notice that
 * you're typing in another app and to put text into that app's text box. It looks at the
 * focused text box (its text and selection) and the keyboard's position, and nothing else.
 *
 * Android binds accessibility services with the system's foreground capabilities, which is
 * what lets the bubble use the microphone while another app is on screen.
 */
class DictationService : AccessibilityService() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val recorder = AudioRecorder(MAX_RECORDING_SECONDS)
    private lateinit var bubble: BubbleOverlay
    private lateinit var formatBar: FormatBar
    private var state = BubbleState.IDLE
    private var lastKeyboardTop: Int? = null
    private var lastWarmUp = 0L
    private var destroyed = false

    /** The recording in progress, being transcribed piece by piece as you talk. */
    private var live: LiveTranscription? = null

    /** The recording was started by holding the bubble down, so letting go ends it. */
    private var heldDown = false

    /** In command mode, the selected text the spoken instruction applies to. */
    private var commandTarget: String? = null

    /** Command mode is switched on and the AI model is installed (checked on focus changes). */
    private var commandAvailable = false

    /** The latest dictation (or command result) the formatting buttons work on. */
    private class LastDictation(val original: String, var shown: String)

    private var lastDictation: LastDictation? = null

    private val refresh = Runnable { updateBubble() }
    private val refreshCommandMode = Runnable { updateCommandMode() }

    override fun onServiceConnected() {
        bubble = BubbleOverlay(
            this,
            onTap = ::onBubbleTapped,
            onHoldStart = ::onBubbleHeld,
            onHoldEnd = ::onBubbleReleased,
        )
        formatBar = FormatBar(this, onFormat = ::onFormatTapped, onUndo = ::onUndoTapped, onClose = ::onCloseTapped)
        bubble.onDragStart = { formatBar.hide() }
        updateBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            // These come with every keystroke, so they only update the bubble's command-mode look.
            if (commandAvailable || (::bubble.isInitialized && bubble.view.commandMode)) {
                handler.removeCallbacks(refreshCommandMode)
                handler.postDelayed(refreshCommandMode, 150)
            }
            return
        }
        // Focus and window events arrive in bursts; settle before looking at the screen.
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 120)
    }

    override fun onInterrupt() {
        stopListening()
        setState(BubbleState.IDLE)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(refreshCommandMode)
        stopListening()
        if (::formatBar.isInitialized) formatBar.hide()
        if (::bubble.isInitialized) bubble.hide()
        scope.cancel()
        super.onDestroy()
    }

    // --- Showing and hiding -------------------------------------------------------------

    private fun updateBubble() {
        if (destroyed || !::bubble.isInitialized) return
        val keyboardTop = keyboardTop()
        if (keyboardTop != null) lastKeyboardTop = keyboardTop

        if (state != BubbleState.IDLE) {
            // Mid-dictation: stay put even if the keyboard went away.
            if (bubble.isShowing) bubble.show(lastKeyboardTop)
            return
        }

        val focus = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val typing = keyboardTop != null &&
            focus?.isPassword != true &&
            focus?.packageName?.toString() != packageName
        if (typing) {
            commandAvailable = AppSettings.commandMode(this) && Formatter.isInstalled()
            updateCommandMode(focus)
            bubble.show(keyboardTop)
            if (formatBar.isShowing) showFormatBar() // follow the bubble
            warmUp()
        } else {
            formatBar.hide()
            bubble.hide()
        }
    }

    /** Turns the bubble purple while text is selected (and back when it isn't). */
    private fun updateCommandMode(
        focus: AccessibilityNodeInfo? = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull(),
    ) {
        if (destroyed || !::bubble.isInitialized || state != BubbleState.IDLE) return
        val start = focus?.textSelectionStart ?: -1
        val end = focus?.textSelectionEnd ?: -1
        bubble.view.commandMode = commandAvailable && focus?.isPassword == false && start >= 0 && end >= 0 && start != end
    }

    private fun showFormatBar() {
        val last = lastDictation ?: return formatBar.hide()
        if (!bubble.isShowing) return formatBar.hide()
        val styles = AppSettings.bubbleStyles(this)
        val showUndo = last.shown != last.original
        if (styles.isEmpty() && !showUndo) return formatBar.hide()
        formatBar.show(bubble.bounds, bubble.isOnLeft, bubble.screenWidth, styles, showUndo)
    }

    /** Top edge of the on-screen keyboard, or null if no keyboard is showing. */
    private fun keyboardTop(): Int? {
        val bounds = Rect()
        val keyboard = runCatching { windows }.getOrNull()
            ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return null
        keyboard.getBoundsInScreen(bounds)
        return if (bounds.height() > 0) bounds.top else null
    }

    /** Loads the speech model in the background so the first dictation is quick. */
    private fun warmUp() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWarmUp < 60_000) return
        lastWarmUp = now
        scope.launch { runCatching { SpeechEngine.ensureLoaded() } }
    }

    // --- Dictation ----------------------------------------------------------------------

    private fun onBubbleTapped() {
        when (state) {
            BubbleState.IDLE -> startDictation(held = false)
            BubbleState.RECORDING -> scope.launch { finishDictation() }
            BubbleState.TRANSCRIBING -> Unit
        }
    }

    /** Hold-to-talk: recording starts once the bubble has been held down for a moment... */
    private fun onBubbleHeld() {
        when (state) {
            BubbleState.IDLE -> startDictation(held = true)
            BubbleState.RECORDING -> scope.launch { finishDictation() } // same as a tap
            BubbleState.TRANSCRIBING -> Unit
        }
    }

    /** ...and stops when it's let go. */
    private fun onBubbleReleased() {
        if (heldDown && state == BubbleState.RECORDING) scope.launch { finishDictation() }
    }

    private fun startDictation(held: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reject("Allow microphone access in Transcribe first")
            openApp()
            return
        }
        if (!ModelStore(this).hasModel()) {
            reject("Set up the speech model in Transcribe first")
            openApp()
            return
        }
        formatBar.hide()
        lastDictation = null
        // Text is selected: what you say is an instruction for the AI, not words to type.
        val target = if (AppSettings.commandMode(this) && Formatter.isInstalled()) TextInserter.selectedText(this) else null
        val transcription = LiveTranscription(scope)
        try {
            recorder.start(
                scope = scope,
                onAudio = transcription::accept,
                onProgress = { _, level -> handler.post { bubble.view.updateLevel(level) } },
                onLimitReached = { scope.launch { finishDictation() } },
            )
        } catch (e: Exception) {
            transcription.cancel()
            reject(e.message ?: "Couldn't start the microphone")
            return
        }
        live = transcription
        heldDown = held
        commandTarget = target
        bubble.view.commandMode = target != null
        setState(BubbleState.RECORDING)
        bubble.view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        lastWarmUp = SystemClock.elapsedRealtime()
        scope.launch { runCatching { SpeechEngine.ensureLoaded() } } // load while you talk
        if (target != null) {
            toast("Say what to do with the selected text")
            scope.launch { runCatching { Formatter.ensureLoaded() } }
        }
    }

    /** Stops the microphone and throws away anything not yet transcribed. */
    private fun stopListening() {
        recorder.cancel()
        live?.cancel()
        live = null
        heldDown = false
        commandTarget = null
    }

    private suspend fun finishDictation() {
        if (state != BubbleState.RECORDING) return
        val transcription = live ?: return
        val target = commandTarget
        live = null
        heldDown = false
        commandTarget = null
        setState(BubbleState.TRANSCRIBING)
        var offerFormatting = false
        try {
            val seconds = recorder.stop()
            when {
                seconds < MIN_RECORDING_SECONDS -> {
                    transcription.cancel()
                    toast("Too short. Tap, speak, then tap again (or hold the bubble while you speak).")
                }
                // Android feeds silence (exact zeros) to apps it won't let use the mic.
                transcription.heardNothing -> {
                    transcription.cancel()
                    reject("Android didn't let Transcribe hear the microphone")
                }
                else -> {
                    val result = transcription.finish()
                    val text = TextPipeline.process(this, result.text)
                    offerFormatting = when {
                        text.isBlank() -> {
                            toast(if (target != null) "No instruction heard" else "No speech detected")
                            false
                        }
                        target != null -> runCommand(target, text)
                        else -> insertDictation(text, result)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reject("Transcription failed: ${e.message}")
        } finally {
            setState(BubbleState.IDLE)
            updateBubble()
            if (offerFormatting) showFormatBar()
        }
    }

    /** Types the dictation into the text box. Returns true if the formatting buttons should show. */
    private suspend fun insertDictation(text: String, result: LiveTranscription.Result): Boolean {
        val inserted = TextInserter.insert(this, text)
        TranscriptHistory.add(
            text,
            result.audioSeconds,
            result.computeMillis,
            original = result.text,
            insertedVia = inserted.method.label,
            waitMillis = result.waitMillis,
        )
        when (inserted.method) {
            TextInserter.Method.COPIED_NO_FIELD ->
                toast("Couldn't find the text box, so it's on your clipboard to paste.")
            TextInserter.Method.COPIED_PASSWORD ->
                toast("Password fields are skipped. It's on your clipboard instead.")
            TextInserter.Method.COPIED_REJECTED ->
                toast("This app didn't take the text, so it's on your clipboard. Long-press the box and tap Paste.")
            else -> Unit
        }
        if (inserted.method.inserted && Formatter.isInstalled() && AppSettings.bubbleStyles(this).isNotEmpty()) {
            lastDictation = LastDictation(original = inserted.text, shown = inserted.text)
            warmUpFormatter()
            return true
        }
        return false
    }

    // --- Command mode -------------------------------------------------------------------

    /**
     * Has the AI do what [instruction] says to the [selected] text, and puts the result in place
     * of the selection. Returns true if it did (so Undo and the formatting buttons can show).
     */
    private suspend fun runCommand(selected: String, instruction: String): Boolean {
        val source = selected.trim()
        var edited = ""
        val millis = measureTimeMillis { edited = Formatter.edit(source, instruction) }
        AppSettings.markFormatUsed(this)
        TranscriptHistory.add(edited, 0f, millis, original = source, style = "“$instruction”")
        // Keep any spaces or line breaks the selection started or ended with.
        val replacement = selected.takeWhile { it.isWhitespace() } + edited + selected.takeLastWhile { it.isWhitespace() }
        if (TextInserter.replaceSelection(this, selected, replacement)) {
            lastDictation = LastDictation(original = selected, shown = replacement)
            return true
        }
        TextInserter.copy(this, edited)
        toast("The selection changed, so the result is on your clipboard.")
        return false
    }

    // --- AI formatting ------------------------------------------------------------------

    private fun onFormatTapped(style: FormatStyle) {
        runFormatting { last ->
            val source = last.original.trim()
            var formatted = ""
            val millis = measureTimeMillis { formatted = Formatter.format(source, style) }
            AppSettings.markFormatUsed(this)
            TranscriptHistory.add(formatted, 0f, millis, original = source, style = style.label)
            // A rewritten sentence fragment keeps the original's casing and (missing) full stop,
            // and the spacing around it, so it still fits the surrounding sentence.
            val fitted = when (style) {
                FormatStyle.POLISH, FormatStyle.SHORTER -> TextJoin.matchEdges(formatted, last.original)
                else -> formatted
            }
            val replacement = last.original.takeWhile { it == ' ' } + fitted + last.original.takeLastWhile { it == ' ' }
            if (TextInserter.replace(this, last.shown, replacement)) {
                last.shown = replacement
                true
            } else {
                TextInserter.copy(this, formatted)
                toast("The text box changed, so the ${style.label.lowercase()} version is on your clipboard.")
                false
            }
        }
    }

    private fun onUndoTapped() {
        runFormatting { last ->
            if (last.shown == last.original) return@runFormatting true
            if (TextInserter.replace(this, last.shown, last.original)) {
                last.shown = last.original
                true
            } else {
                TextInserter.copy(this, last.original.trim())
                toast("The text box changed, so your original wording is on your clipboard.")
                false
            }
        }
    }

    /** Puts the buttons away until the next dictation. A rewrite already under way still lands. */
    private fun onCloseTapped() {
        lastDictation = null
        formatBar.hide()
    }

    /** Runs a formatting [action] on the latest dictation; it returns false to retire the buttons. */
    private fun runFormatting(action: suspend (LastDictation) -> Boolean) {
        val last = lastDictation ?: return
        if (state != BubbleState.IDLE) return
        setState(BubbleState.TRANSCRIBING)
        formatBar.setBusy(true)
        scope.launch {
            var keep = true
            try {
                keep = action(last)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reject("Formatting failed: ${e.message}")
            } finally {
                if (!keep) lastDictation = null
                setState(BubbleState.IDLE)
                formatBar.setBusy(false)
                updateBubble()
                if (lastDictation != null) showFormatBar() else formatBar.hide()
            }
        }
    }

    /** Loads the formatting AI ahead of time, but only for people who've used it lately. */
    private fun warmUpFormatter() {
        if (AppSettings.formattedRecently(this)) {
            scope.launch { runCatching { Formatter.ensureLoaded() } }
        }
    }

    private fun setState(newState: BubbleState) {
        state = newState
        if (::bubble.isInitialized) bubble.view.state = newState
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun reject(message: String) {
        if (::bubble.isInitialized) bubble.view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        /** Whether the user has switched the bubble on in Android's accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val ours = ComponentName(context, DictationService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
        }

        /**
         * Whether Android's own floating accessibility button is set up for Transcribe. It shows
         * the app icon on screen all the time and isn't needed: the bubble appears by itself.
         */
        fun isShortcutButtonOn(context: Context): Boolean {
            val ours = ComponentName(context, DictationService::class.java)
            val targets = runCatching {
                Settings.Secure.getString(context.contentResolver, "accessibility_button_targets")
            }.getOrNull() ?: return false
            return targets.split(':').any { ComponentName.unflattenFromString(it) == ours }
        }
    }
}
