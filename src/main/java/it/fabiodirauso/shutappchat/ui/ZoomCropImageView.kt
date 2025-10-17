package it.fabiodirauso.shutappchat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ZoomCropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private val rotationDetector: RotationGestureDetector

    private var currentBitmap: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null
    private var currentRotation = 0f
    private var freeRotation = 0f  // Rotazione libera a 2 tocchi
    private var minScale = 1f
    private var maxScale = 6f

    init {
        scaleType = ScaleType.MATRIX
        isSaveEnabled = true

        rotationDetector = RotationGestureDetector(object : RotationGestureDetector.OnRotationGestureListener {
            override fun onRotation(rotationDegrees: Float): Boolean {
                val bitmap = rotatedBitmap ?: currentBitmap ?: return false
                freeRotation += rotationDegrees
                
                // Get the center of the view
                val centerX = width / 2f
                val centerY = height / 2f
                
                drawMatrix.postRotate(rotationDegrees, centerX, centerY)
                constrainMatrix(bitmap)
                imageMatrix = drawMatrix
                invalidate()
                return true
            }
        })

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val bitmap = rotatedBitmap ?: currentBitmap ?: return false
                val currentScale = getCurrentScale()
                var scaleFactor = detector.scaleFactor
                val targetScale = (currentScale * scaleFactor).coerceIn(minScale, maxScale)
                scaleFactor = targetScale / currentScale

                drawMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                constrainMatrix(bitmap)
                imageMatrix = drawMatrix
                invalidate()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val bitmap = rotatedBitmap ?: currentBitmap ?: return false
                drawMatrix.postTranslate(-distanceX, -distanceY)
                constrainMatrix(bitmap)
                imageMatrix = drawMatrix
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                reset()
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = rotatedBitmap ?: currentBitmap ?: return false
        
        // Request parent to not intercept touch events when we're handling them
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        
        var handled = rotationDetector.onTouchEvent(event)
        handled = scaleDetector.onTouchEvent(event) || handled
        handled = gestureDetector.onTouchEvent(event) || handled

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            constrainMatrix(bitmap)
            imageMatrix = drawMatrix
        }
        return handled || super.onTouchEvent(event)
    }

    fun setImageBitmapWithReset(bitmap: Bitmap) {
        currentBitmap?.let { existing ->
            if (existing !== bitmap && !existing.isRecycled) {
                existing.recycle()
            }
        }
        currentBitmap = bitmap
        super.setImageBitmap(bitmap)
        post { configureBaseMatrix(bitmap) }
    }

    fun reset() {
        val bitmap = rotatedBitmap ?: currentBitmap
        bitmap?.let { configureBaseMatrix(it) }
    }

    fun release() {
        currentBitmap?.run {
            if (!isRecycled) recycle()
        }
        currentBitmap = null
        rotatedBitmap?.run {
            if (!isRecycled) recycle()
        }
        rotatedBitmap = null
        setImageDrawable(null)
    }
    
    fun rotate90Degrees() {
        // Get the current bitmap being displayed (rotated or original)
        val currentDisplayedBitmap = rotatedBitmap ?: currentBitmap ?: return
        currentRotation = (currentRotation + 90f) % 360f
        
        // Create rotated bitmap from current displayed bitmap
        val matrix = Matrix()
        matrix.postRotate(90f)
        
        // Free old rotated bitmap if it exists
        val oldRotated = rotatedBitmap
        
        rotatedBitmap = Bitmap.createBitmap(
            currentDisplayedBitmap,
            0, 0,
            currentDisplayedBitmap.width, 
            currentDisplayedBitmap.height,
            matrix,
            true
        )
        
        // Recycle old rotated bitmap after creating new one
        oldRotated?.let {
            if (!it.isRecycled && it !== currentBitmap) {
                it.recycle()
            }
        }
        
        super.setImageBitmap(rotatedBitmap)
        post { rotatedBitmap?.let { configureBaseMatrix(it) } }
    }
    
    fun zoomIn() {
        val bitmap = rotatedBitmap ?: currentBitmap ?: return
        val currentScale = getCurrentScale()
        val newScale = (currentScale * 1.2f).coerceAtMost(maxScale)
        val scaleFactor = newScale / currentScale
        
        val centerX = width / 2f
        val centerY = height / 2f
        
        drawMatrix.postScale(scaleFactor, scaleFactor, centerX, centerY)
        constrainMatrix(bitmap)
        imageMatrix = drawMatrix
        invalidate()
    }
    
    fun zoomOut() {
        val bitmap = rotatedBitmap ?: currentBitmap ?: return
        val currentScale = getCurrentScale()
        val newScale = (currentScale / 1.2f).coerceAtLeast(minScale)
        val scaleFactor = newScale / currentScale
        
        val centerX = width / 2f
        val centerY = height / 2f
        
        drawMatrix.postScale(scaleFactor, scaleFactor, centerX, centerY)
        constrainMatrix(bitmap)
        imageMatrix = drawMatrix
        invalidate()
    }
    
    fun panUp(distance: Float = 50f) {
        val bitmap = rotatedBitmap ?: currentBitmap ?: return
        drawMatrix.postTranslate(0f, distance)
        constrainMatrix(bitmap)
        imageMatrix = drawMatrix
        invalidate()
    }
    
    fun panDown(distance: Float = 50f) {
        val bitmap = rotatedBitmap ?: currentBitmap ?: return
        drawMatrix.postTranslate(0f, -distance)
        constrainMatrix(bitmap)
        imageMatrix = drawMatrix
        invalidate()
    }
    
    fun panLeft(distance: Float = 50f) {
        val bitmap = rotatedBitmap ?: currentBitmap ?: return
        drawMatrix.postTranslate(distance, 0f)
        constrainMatrix(bitmap)
        imageMatrix = drawMatrix
        invalidate()
    }
    
    fun panRight(distance: Float = 50f) {
        val bitmap = rotatedBitmap ?: currentBitmap ?: return
        drawMatrix.postTranslate(-distance, 0f)
        constrainMatrix(bitmap)
        imageMatrix = drawMatrix
        invalidate()
    }
    
    fun getCurrentRotation(): Float = currentRotation

    private fun configureBaseMatrix(bitmap: Bitmap) {
        if (width == 0 || height == 0) return
        drawMatrix.reset()
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val widthScale = viewWidth / bitmap.width.toFloat()
        val heightScale = viewHeight / bitmap.height.toFloat()
        minScale = min(widthScale, heightScale)
        val initialScale = minScale
        drawMatrix.postScale(initialScale, initialScale)

        val translateX = (viewWidth - bitmap.width * initialScale) / 2f
        val translateY = (viewHeight - bitmap.height * initialScale) / 2f
        drawMatrix.postTranslate(translateX, translateY)
        imageMatrix = drawMatrix
    }

    private fun constrainMatrix(bitmap: Bitmap) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        drawMatrix.getValues(matrixValues)
        var scale = matrixValues[Matrix.MSCALE_X]
        scale = scale.coerceIn(minScale, maxScale)

        // Normalize scale around center when clamped
        val currentScale = getCurrentScale()
        if (currentScale != scale) {
            val factor = scale / currentScale
            drawMatrix.postScale(factor, factor, viewWidth / 2f, viewHeight / 2f)
            drawMatrix.getValues(matrixValues)
        }

        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val contentWidth = bitmap.width * scale
        val contentHeight = bitmap.height * scale

        var deltaX = 0f
        var deltaY = 0f

        if (contentWidth <= viewWidth) {
            deltaX = (viewWidth - contentWidth) / 2f - transX
        } else {
            if (transX > 0) {
                deltaX = -transX
            } else if (transX + contentWidth < viewWidth) {
                deltaX = viewWidth - (transX + contentWidth)
            }
        }

        if (contentHeight <= viewHeight) {
            deltaY = (viewHeight - contentHeight) / 2f - transY
        } else {
            if (transY > 0) {
                deltaY = -transY
            } else if (transY + contentHeight < viewHeight) {
                deltaY = viewHeight - (transY + contentHeight)
            }
        }

        drawMatrix.postTranslate(deltaX, deltaY)
    }

    private fun getCurrentScale(): Float {
        drawMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    fun getCroppedBitmap(): Bitmap? {
        val bitmap = rotatedBitmap ?: currentBitmap ?: return null
        if (width == 0 || height == 0) return null

        drawMatrix.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val left = ((0f - transX) / scale).roundToInt().coerceIn(0, bitmap.width)
        val top = ((0f - transY) / scale).roundToInt().coerceIn(0, bitmap.height)
        val right = ((viewWidth - transX) / scale).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((viewHeight - transY) / scale).roundToInt().coerceIn(top + 1, bitmap.height)

        val cropWidth = max(1, right - left)
        val cropHeight = max(1, bottom - top)

        if (left + cropWidth > bitmap.width || top + cropHeight > bitmap.height) {
            return null
        }

        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }
}