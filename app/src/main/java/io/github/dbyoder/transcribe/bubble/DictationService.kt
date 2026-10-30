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
import io.github.dbyoder.transcribe.core.FormatStyle
import io.github.dbyoder.transcribe.audio.AudioRecorder
import io.github.dbyoder.transcribe.audio.MAX_RECORDING_SECONDS
import io.github.dbyoder.transcribe.audio.MIN_RECORDING_SECONDS
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import io.github.dbyoder.transcribe.history.TranscriptHistory
import io.github.dbyoder.transcribe.model.ModelStore
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
 * focused text box and the keyboard's position, and nothing else.
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

    /** The latest dictation the formatting buttons work on. */
    private class LastDictation(val original: String, var shown: String)

    private var lastDictation: LastDictation? = null

    private val refresh = Runnable { updateBubble() }

    override fun onServiceConnected() {
        bubble = BubbleOverlay(this, ::onBubbleTapped)
        formatBar = FormatBar(this, onFormat = ::onFormatTapped, onUndo = ::onUndoTapped)
        bubble.onDragStart = { formatBar.hide() }
        updateBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Focus and window events arrive in bursts; settle before looking at the screen.
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 120)
    }

    override fun onInterrupt() {
        recorder.cancel()
        setState(BubbleState.IDLE)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(refresh)
        recorder.cancel()
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
            bubble.show(keyboardTop)
            if (formatBar.isShowing) showFormatBar() // follow the bubble
            warmUp()
        } else {
            formatBar.hide()
            bubble.hide()
        }
    }

    private fun showFormatBar() {
        val last = lastDictation ?: return formatBar.hide()
        if (!bubble.isShowing) return formatBar.hide()
        formatBar.show(bubble.bounds, bubble.isOnLeft, bubble.screenWidth, showUndo = last.shown != last.original)
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
            BubbleState.IDLE -> startDictation()
            BubbleState.RECORDING -> scope.launch { finishDictation() }
            BubbleState.TRANSCRIBING -> Unit
        }
    }

    private fun startDictation() {
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
        try {
            recorder.start(
                scope = scope,
                onProgress = { _, level -> handler.post { bubble.view.updateLevel(level) } },
                onLimitReached = { scope.launch { finishDictation() } },
            )
        } catch (e: Exception) {
            reject(e.message ?: "Couldn't start the microphone")
            return
        }
        setState(BubbleState.RECORDING)
        bubble.view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        lastWarmUp = SystemClock.elapsedRealtime()
        scope.launch { runCatching { SpeechEngine.ensureLoaded() } } // load while you talk
    }

    private suspend fun finishDictation() {
        if (state != BubbleState.RECORDING) return
        setState(BubbleState.TRANSCRIBING)
        var offerFormatting = false
        try {
            val samples = recorder.stop()
            val seconds = samples.size / SAMPLE_RATE.toFloat()
            when {
                seconds < MIN_RECORDING_SECONDS -> toast("Too short. Tap, speak, then tap again.")
                // Android feeds silence (exact zeros) to apps it won't let use the mic.
                samples.all { it == 0f } -> reject("Android didn't let Transcribe hear the microphone")
                else -> {
                    var raw = ""
                    val millis = measureTimeMillis { raw = SpeechEngine.transcribe(samples) }
                    val text = TextPipeline.process(this, raw)
                    if (text.isBlank()) {
                        toast("No speech detected")
                    } else {
                        val result = TextInserter.insert(this, text)
                        TranscriptHistory.add(text, seconds, millis, original = raw, insertedVia = result.method.label)
                        if (result.method.inserted && Formatter.isInstalled()) {
                            lastDictation = LastDictation(original = result.text, shown = result.text)
                            offerFormatting = true
                            warmUpFormatter()
                        }
                        when (result.method) {
                            TextInserter.Method.COPIED_NO_FIELD ->
                                toast("Couldn't find the text box, so it's on your clipboard to paste.")
                            TextInserter.Method.COPIED_PASSWORD ->
                                toast("Password fields are skipped. It's on your clipboard instead.")
                            TextInserter.Method.COPIED_REJECTED ->
                                toast("This app didn't take the text, so it's on your clipboard. Long-press the box and tap Paste.")
                            else -> Unit
                        }
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

    // --- AI formatting ------------------------------------------------------------------

    private fun onFormatTapped(style: FormatStyle) {
        runFormatting { last ->
            val source = last.original.trim()
            var formatted = ""
            val millis = measureTimeMillis { formatted = Formatter.format(source, style) }
            AppSettings.markFormatUsed(this)
            TranscriptHistory.add(formatted, 0f, millis, original = source, style = style.label)
            // Keep the spacing the original had around it, so it still fits the sentence.
            val replacement = last.original.takeWhile { it == ' ' } + formatted + last.original.takeLastWhile { it == ' ' }
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
