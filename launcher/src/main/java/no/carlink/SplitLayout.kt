package no.carlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import kotlin.math.abs

/**
 * Viser 1–3 paneler side om side (liggende) eller over hverandre (stående).
 * Skillelinjene mellom panelene kan dras for å justere fordelingen.
 */
class SplitLayout(ctx: Context) : ViewGroup(ctx) {

    var weights: FloatArray = floatArrayOf(1f)
        private set
    var onWeightsChanged: ((FloatArray) -> Unit)? = null

    private val gap = 14.dp
    private val touchZone = 36.dp
    private val minFrac = 0.15f
    private var horizontal = true
    private var dragIndex = -1

    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F6673.toInt() }
    private val gripActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT }
    private val rect = RectF()

    init { setWillNotDraw(false) }

    fun setWeights(w: FloatArray) {
        val sum = w.sum().takeIf { it > 0f } ?: 1f
        weights = FloatArray(w.size) { w[it] / sum }
        requestLayout(); invalidate()
    }

    private fun eff(n: Int): FloatArray = if (weights.size == n) weights else FloatArray(n) { 1f / n }

    private fun sizes(total: Int): IntArray {
        val n = childCount
        val avail = (total - gap * (n - 1)).coerceAtLeast(0)
        val w = eff(n)
        val out = IntArray(n)
        var used = 0
        for (i in 0 until n) {
            out[i] = if (i == n - 1) avail - used else (avail * w[i]).toInt()
            used += out[i]
        }
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        horizontal = w >= h
        if (childCount == 0) return
        val s = sizes(if (horizontal) w else h)
        for (i in 0 until childCount) {
            val main = MeasureSpec.makeMeasureSpec(s[i], MeasureSpec.EXACTLY)
            val cross = MeasureSpec.makeMeasureSpec(if (horizontal) h else w, MeasureSpec.EXACTLY)
            if (horizontal) getChildAt(i).measure(main, cross) else getChildAt(i).measure(cross, main)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var pos = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (horizontal) {
                c.layout(pos, 0, pos + c.measuredWidth, c.measuredHeight); pos += c.measuredWidth + gap
            } else {
                c.layout(0, pos, c.measuredWidth, pos + c.measuredHeight); pos += c.measuredHeight + gap
            }
        }
    }

    private fun dividerCenter(i: Int): Float {
        val c = getChildAt(i)
        return if (horizontal) c.right + gap / 2f else c.bottom + gap / 2f
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val len = 56.dp.toFloat(); val th = 5.dp.toFloat()
        for (i in 0 until childCount - 1) {
            val c = dividerCenter(i)
            if (horizontal) rect.set(c - th / 2, height / 2f - len / 2, c + th / 2, height / 2f + len / 2)
            else rect.set(width / 2f - len / 2, c - th / 2, width / 2f + len / 2, c + th / 2)
            canvas.drawRoundRect(rect, th, th, if (i == dragIndex) gripActive else gripPaint)
        }
    }

    private fun hit(x: Float, y: Float): Int {
        val p = if (horizontal) x else y
        for (i in 0 until childCount - 1) if (abs(p - dividerCenter(i)) <= touchZone / 2f) return i
        return -1
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            dragIndex = hit(ev.x, ev.y)
            if (dragIndex >= 0) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); invalidate() }
        }
        return dragIndex >= 0
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dragIndex < 0) dragIndex = hit(ev.x, ev.y)
                if (dragIndex < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragIndex >= 0) drag(if (horizontal) ev.x else ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragIndex >= 0) {
                dragIndex = -1; invalidate(); onWeightsChanged?.invoke(weights.copyOf())
            }
        }
        return true
    }

    private fun drag(p: Float) {
        val n = childCount
        val total = if (horizontal) width else height
        val avail = (total - gap * (n - 1)).toFloat()
        if (avail <= 0f || dragIndex >= n - 1) return
        val w = eff(n).copyOf()
        val i = dragIndex
        var start = 0f
        for (k in 0 until i) start += w[k] * avail + gap
        val pair = w[i] + w[i + 1]
        if (pair < 2 * minFrac) return
        val ni = ((p - gap / 2f - start) / avail).coerceIn(minFrac, pair - minFrac)
        w[i] = ni; w[i + 1] = pair - ni
        weights = w
        requestLayout(); invalidate()
    }
}
