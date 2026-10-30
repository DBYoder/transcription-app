package io.github.dbyoder.transcribe.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.dbyoder.transcribe.R

enum class BubbleState { IDLE, RECORDING, TRANSCRIBING }

/** The round mic button. Drawn by hand to keep the overlay light (no Compose in a system window). */
class BubbleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val radius = 22 * density
    private val iconHalf = (12 * density).toInt()

    private val idleColor = context.getColor(android.R.color.system_accent1_600)
    private val busyColor = context.getColor(android.R.color.system_neutral1_800)
    private val recordingColor = Color.rgb(0xD3, 0x2F, 0x2F)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val arcBounds = RectF()

    private val micIcon: Drawable = white(R.drawable.ic_mic)
    private val stopIcon: Drawable = white(R.drawable.ic_stop)

    private var spinnerAngle = 0f
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            spinnerAngle = it.animatedValue as Float
            invalidate()
        }
    }

    var state: BubbleState = BubbleState.IDLE
        set(value) {
            if (field == value) return
            field = value
            shownLevel = 0f
            if (value == BubbleState.TRANSCRIBING) spinner.start() else spinner.cancel()
            contentDescription = when (value) {
                BubbleState.IDLE -> "Start dictating"
                BubbleState.RECORDING -> "Stop dictating"
                BubbleState.TRANSCRIBING -> "Transcribing"
            }
            invalidate()
        }

    private var shownLevel = 0f

    /** Shows microphone loudness (0..1) as a ring that swells while recording. */
    fun updateLevel(level: Float) {
        shownLevel = shownLevel * 0.5f + level.coerceIn(0f, 1f) * 0.5f // smooth out jitter
        if (state == BubbleState.RECORDING) invalidate()
    }

    init {
        contentDescription = "Start dictating"
        isClickable = true
        alpha = 0.92f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        when (state) {
            BubbleState.IDLE -> {
                canvas.drawCircle(cx, cy + 1.5f * density, radius + 1f * density, shadow)
                fill.color = idleColor
                canvas.drawCircle(cx, cy, radius, fill)
                drawIcon(canvas, micIcon, cx, cy)
            }
            BubbleState.RECORDING -> {
                ring.color = recordingColor
                ring.alpha = 80
                canvas.drawCircle(cx, cy, radius * (1f + 0.35f * shownLevel), ring)
                fill.color = recordingColor
                canvas.drawCircle(cx, cy, radius, fill)
                drawIcon(canvas, stopIcon, cx, cy)
            }
            BubbleState.TRANSCRIBING -> {
                canvas.drawCircle(cx, cy + 1.5f * density, radius + 1f * density, shadow)
                fill.color = busyColor
                canvas.drawCircle(cx, cy, radius, fill)
                val inset = radius * 0.55f
                arcBounds.set(cx - inset, cy - inset, cx + inset, cy + inset)
                canvas.drawArc(arcBounds, spinnerAngle, 110f, false, arc)
            }
        }
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, cx: Float, cy: Float) {
        icon.setBounds(cx.toInt() - iconHalf, cy.toInt() - iconHalf, cx.toInt() + iconHalf, cy.toInt() + iconHalf)
        icon.draw(canvas)
    }

    private fun white(resId: Int): Drawable =
        checkNotNull(context.getDrawable(resId)).mutate().apply { setTint(Color.WHITE) }
}
