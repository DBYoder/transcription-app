package io.github.dbyoder.transcribe.bubble

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.dbyoder.transcribe.core.FormatStyle
import kotlin.math.roundToInt

/**
 * A row of one-tap AI formatting buttons (Polish, Bullets, Email, Shorter, Prompt, and Undo once
 * something has been reformatted) shown beside the bubble right after a dictation. Like the
 * bubble, it's an accessibility overlay that never takes focus from the text box.
 */
class FormatBar(
    private val context: Context,
    private val onFormat: (FormatStyle) -> Unit,
    private val onUndo: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val gapPx = (6 * density).roundToInt()
    private val minWidthPx = (160 * density).roundToInt()
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }

    private val undoButton = button("Undo") { onUndo() }
    private val styleButtons = FormatStyle.entries.map { style -> button(style.label) { onFormat(style) } }

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val pad = (4 * density).roundToInt()
        setPadding(pad, pad, pad, pad)
        addView(undoButton)
        styleButtons.forEach(::addView)
    }

    // Scrolls sideways when the buttons don't all fit beside the bubble.
    private val bar = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToOutline = true
        background = GradientDrawable().apply {
            cornerRadius = 22 * density
            setColor(context.getColor(android.R.color.system_neutral1_800))
        }
        elevation = 6 * density
        addView(row)
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        fitInsetsTypes = 0
        title = "Transcribe formatting buttons"
    }

    private var attached = false

    val isShowing: Boolean get() = attached

    /** Shows the bar beside [bubble] (screen pixels). */
    fun show(bubble: Rect, bubbleOnLeft: Boolean, screenWidth: Int, showUndo: Boolean) {
        undoButton.visibility = if (showUndo) View.VISIBLE else View.GONE
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val available = (if (bubbleOnLeft) screenWidth - bubble.right else bubble.left) - 2 * gapPx
        val width = row.measuredWidth.coerceAtMost(available.coerceAtLeast(minWidthPx))
        val height = row.measuredHeight
        params.width = width
        params.x = if (bubbleOnLeft) {
            (bubble.right + gapPx).coerceAtMost((screenWidth - width - gapPx).coerceAtLeast(0))
        } else {
            (bubble.left - gapPx - width).coerceAtLeast(gapPx)
        }
        params.y = bubble.centerY() - height / 2
        if (attached) {
            windowManager.updateViewLayout(bar, params)
        } else {
            windowManager.addView(bar, params)
            attached = true
        }
        bar.post { bar.scrollTo(0, 0) }
        keepVisible()
    }

    /** Dims the buttons while the AI is working. */
    fun setBusy(busy: Boolean) {
        bar.alpha = if (busy) 0.5f else 1f
        undoButton.isEnabled = !busy
        styleButtons.forEach { it.isEnabled = !busy }
        if (busy) handler.removeCallbacks(autoHide) else keepVisible()
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        if (!attached) return
        windowManager.removeViewImmediate(bar)
        attached = false
    }

    private fun keepVisible() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val h = (11 * density).roundToInt()
        val v = (9 * density).roundToInt()
        setPadding(h, v, h, v)
        isClickable = true
        isFocusable = false
        background = context.getDrawable(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    companion object {
        private const val AUTO_HIDE_MS = 12_000L
    }
}
