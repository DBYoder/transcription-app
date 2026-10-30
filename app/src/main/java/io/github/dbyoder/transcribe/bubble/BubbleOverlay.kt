package io.github.dbyoder.transcribe.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.dbyoder.transcribe.AppSettings
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Shows [BubbleView] as an accessibility overlay: a small window above every app that never
 * takes keyboard focus, so the text box you're typing in stays active.
 *
 * Position is remembered as a screen side plus a distance above the keyboard, so the bubble
 * follows the keyboard between apps. Drag it to move it.
 */
class BubbleOverlay(private val context: Context, private val onTap: () -> Unit) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val sizePx = (64 * density).roundToInt()
    private val edgeMarginPx = (2 * density).roundToInt()
    private val topLimitPx = (48 * density).roundToInt() // keep clear of the status bar

    val view = BubbleView(context)

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "Transcribe bubble"
    }

    private var attached = false

    /** Top edge of the keyboard on screen; the bubble positions itself relative to it. */
    private var anchorY = 0

    val isShowing: Boolean get() = attached

    /** Where the bubble is on screen, in pixels. */
    val bounds: Rect get() = Rect(params.x, params.y, params.x + sizePx, params.y + sizePx)

    val isOnLeft: Boolean get() = params.x + sizePx / 2 < screenWidth / 2

    val screenWidth: Int get() = screenBounds().width()

    /** Called when the user starts dragging the bubble. */
    var onDragStart: () -> Unit = {}

    init {
        view.setOnTouchListener(DragHandler())
    }

    /** Shows the bubble (or moves it) above a keyboard whose top edge is at [keyboardTop]. */
    fun show(keyboardTop: Int?) {
        val screen = screenBounds()
        anchorY = keyboardTop ?: (screen.bottom - (240 * density).roundToInt())
        placeFromSettings(screen)
        if (attached) {
            windowManager.updateViewLayout(view, params)
        } else {
            windowManager.addView(view, params)
            attached = true
        }
    }

    fun hide() {
        if (!attached) return
        windowManager.removeViewImmediate(view)
        attached = false
    }

    private fun placeFromSettings(screen: Rect) {
        val offsetPx = (AppSettings.bubbleOffsetDp(context) * density).roundToInt()
        params.x = if (AppSettings.bubbleOnLeft(context)) edgeMarginPx else screen.width() - sizePx - edgeMarginPx
        params.y = clampY(anchorY - sizePx - offsetPx)
    }

    private fun clampY(y: Int): Int = y.coerceIn(topLimitPx, (anchorY - sizePx).coerceAtLeast(topLimitPx))

    /** After a drag: snap to the nearer screen edge and remember the spot. */
    private fun settle() {
        val screen = screenBounds()
        val onLeft = params.x + sizePx / 2 < screen.width() / 2
        params.y = clampY(params.y)
        val offsetDp = ((anchorY - sizePx - params.y) / density).coerceAtLeast(0f)
        AppSettings.setBubblePosition(context, onLeft, offsetDp)
        placeFromSettings(screen)
        if (attached) windowManager.updateViewLayout(view, params)
    }

    private fun screenBounds(): Rect =
        runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
            ?: context.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    private inner class DragHandler : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility") // taps go through performClick() below
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        onDragStart()
                    }
                    if (dragging && attached) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        windowManager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        settle()
                    } else {
                        v.performClick()
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> if (dragging) settle()
            }
            return true
        }
    }
}
