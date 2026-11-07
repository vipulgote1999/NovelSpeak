package app.novelspeak.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.novelspeak.Palette
import kotlin.math.max

/**
 * Renders one manga page and draws the OCR boxes over it, with the box currently being spoken
 * highlighted. Supports pinch zoom, double-tap zoom, panning while zoomed, and tapping a box
 * to jump playback to that line.
 *
 * Tall webtoon strips start at fit-width instead of fit-page, so text stays readable; the view
 * then pans vertically to keep the active box on screen as TTS advances.
 */
class MangaPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val matrix = Matrix()
    private val baseMatrix = Matrix()
    private val inverse = Matrix()
    private val values = FloatArray(9)
    private val mapped = RectF()

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.ACCENT
        alpha = 70
        strokeWidth = dp(1.2f)
    }
    private val activeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.ACCENT
        alpha = 46
    }
    private val activeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.ACCENT
        alpha = 235
        strokeWidth = dp(2.6f)
    }

    private var bitmap: Bitmap? = null
    private var blocks: List<OcrBlock> = emptyList()
    private var activeBlock = -1
    private var showBoxes = true
    private var currentScale = 1f
    private var baseScale = 1f
    private var longStrip = false

    var onBlockTap: ((Int) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = bitmap != null

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (bitmap == null) return false
                val target = (currentScale * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                val applied = target / currentScale
                if (applied == 1f) return true
                matrix.postScale(applied, applied, detector.focusX, detector.focusY)
                currentScale = target
                clampMatrix()
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (bitmap == null) return false
                if (currentScale <= 1.01f && !longStrip) return false
                matrix.postTranslate(-distanceX, -distanceY)
                clampMatrix()
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (bitmap == null) return false
                if (currentScale > 1.05f) {
                    resetZoom()
                } else {
                    val target = 2.5f
                    val applied = target / currentScale
                    matrix.postScale(applied, applied, e.x, e.y)
                    currentScale = target
                    clampMatrix()
                    ensureActiveVisible()
                    invalidate()
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                if (bitmap == null) return true
                matrix.invert(inverse)
                val point = floatArrayOf(e.x, e.y)
                inverse.mapPoints(point)
                var best = -1
                var bestArea = Float.MAX_VALUE
                for (i in blocks.indices) {
                    val box = blocks[i].boundingBox
                    if (box.isEmpty || !box.contains(point[0], point[1])) continue
                    val area = box.width() * box.height()
                    if (area < bestArea) {
                        bestArea = area
                        best = i
                    }
                }
                if (best >= 0) onBlockTap?.invoke(best)
                return true
            }
        }
    )

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
    }

    fun setPage(
        page: Bitmap?,
        ocrBlocks: List<OcrBlock>,
        activeIndex: Int = -1,
        boxesVisible: Boolean = true
    ) {
        bitmap = page
        blocks = ocrBlocks
        activeBlock = activeIndex
        showBoxes = boxesVisible
        if (width > 0 && height > 0) updateBaseMatrix()
        else matrix.reset()
        ensureActiveVisible()
        updateDescription()
        invalidate()
    }

    /** Updates OCR boxes without resetting the user's zoom/pan. */
    fun setBlocks(ocrBlocks: List<OcrBlock>, activeIndex: Int = activeBlock) {
        blocks = ocrBlocks
        activeBlock = activeIndex
        ensureActiveVisible()
        updateDescription()
        invalidate()
    }

    fun setActiveBlock(index: Int, autoPan: Boolean = true) {
        if (activeBlock == index) {
            if (autoPan) ensureActiveVisible()
            return
        }
        activeBlock = index
        if (autoPan) ensureActiveVisible()
        updateDescription()
        invalidate()
    }

    fun setBoxesVisible(visible: Boolean) {
        if (showBoxes == visible) return
        showBoxes = visible
        invalidate()
    }

    fun resetZoom() {
        matrix.set(baseMatrix)
        currentScale = 1f
        ensureActiveVisible()
        invalidate()
    }

    private fun updateBaseMatrix() {
        val b = bitmap ?: return
        val vw = width - paddingLeft - paddingRight
        val vh = height - paddingTop - paddingBottom
        if (vw <= 0 || vh <= 0 || b.width <= 0 || b.height <= 0) return
        val contain = minOf(vw.toFloat() / b.width, vh.toFloat() / b.height)
        longStrip = b.height > b.width * 2.2f
        baseScale = if (longStrip) max(contain, vw.toFloat() / b.width) else contain
        val drawW = b.width * baseScale
        val drawH = b.height * baseScale
        val dx = paddingLeft + (vw - drawW) / 2f
        val dy = if (longStrip) paddingTop.toFloat() else paddingTop + (vh - drawH) / 2f
        baseMatrix.setScale(baseScale, baseScale)
        baseMatrix.postTranslate(dx, dy)
        matrix.set(baseMatrix)
        currentScale = 1f
    }

    private fun clampMatrix() {
        val b = bitmap ?: return
        val vw = width - paddingLeft - paddingRight
        val vh = height - paddingTop - paddingBottom
        if (vw <= 0 || vh <= 0) return
        matrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        val drawW = b.width * scale
        val drawH = b.height * scale
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]
        tx = if (drawW <= vw) {
            paddingLeft + (vw - drawW) / 2f
        } else {
            tx.coerceIn(paddingLeft + vw - drawW, paddingLeft.toFloat())
        }
        ty = if (drawH <= vh) {
            paddingTop + (vh - drawH) / 2f
        } else {
            ty.coerceIn(paddingTop + vh - drawH, paddingTop.toFloat())
        }
        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        matrix.setValues(values)
    }

    private fun ensureActiveVisible() {
        val box = blocks.getOrNull(activeBlock)?.boundingBox ?: return
        if (box.isEmpty || bitmap == null) return
        matrix.mapRect(mapped, box)
        val vw = width - paddingLeft - paddingRight
        val vh = height - paddingTop - paddingBottom
        if (vw <= 0 || vh <= 0) return
        if (currentScale <= 1.01f && !longStrip) return
        val margin = dp(28f)
        var dx = 0f
        var dy = 0f
        if (mapped.width() >= vw - margin * 2) {
            dx = paddingLeft + (vw - mapped.width()) / 2f - mapped.left
        } else if (mapped.left < paddingLeft + margin) {
            dx = paddingLeft + margin - mapped.left
        } else if (mapped.right > paddingLeft + vw - margin) {
            dx = paddingLeft + vw - margin - mapped.right
        }
        if (mapped.height() >= vh - margin * 2) {
            dy = paddingTop + margin - mapped.top
        } else if (mapped.top < paddingTop + margin) {
            dy = paddingTop + margin - mapped.top
        } else if (mapped.bottom > paddingTop + vh - margin) {
            dy = paddingTop + vh - margin - mapped.bottom
        }
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
            clampMatrix()
            invalidate()
        }
    }

    private fun updateDescription() {
        val label = if (activeBlock >= 0) {
            "Page image. Reading: " + (blocks.getOrNull(activeBlock)?.text ?: "")
        } else {
            "Manga page image"
        }
        contentDescription = label
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBaseMatrix()
        ensureActiveVisible()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        canvas.save()
        canvas.concat(matrix)
        canvas.drawBitmap(b, 0f, 0f, imagePaint)
        canvas.restore()

        if (!showBoxes || blocks.isEmpty()) return
        for (i in blocks.indices) {
            val box = blocks[i].boundingBox
            if (box.isEmpty) continue
            matrix.mapRect(mapped, box)
            if (i == activeBlock) {
                mapped.inset(-dp(2f), -dp(2f))
                canvas.drawRect(mapped, activeFill)
                canvas.drawRect(mapped, activeStroke)
            } else {
                canvas.drawRect(mapped, boxStroke)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val MAX_ZOOM = 6f
    }
}
