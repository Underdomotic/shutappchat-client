package it.fabiodirauso.shutappchat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withSave

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    private val overlayPath = Path().apply {
        fillType = Path.FillType.EVEN_ODD
    }

    private val cropRect = RectF()

    private val cornerRadius = resources.displayMetrics.density * 12f
    private val edgePadding = 0f

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Calculate center and radius for circular crop
        val centerX = width / 2f
        val centerY = height / 2f
        val minDimension = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
        val radius = (minDimension / 2f) - edgePadding

        if (radius <= 0f) return

        // Draw semi-transparent overlay everywhere except the circle
        overlayPath.reset()
        overlayPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        overlayPath.addCircle(centerX, centerY, radius, Path.Direction.CCW)

        canvas.withSave {
            drawPath(overlayPath, shadePaint)
        }
        
        // Draw white circle border
        canvas.drawCircle(centerX, centerY, radius, borderPaint)
    }
}