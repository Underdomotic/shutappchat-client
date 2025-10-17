package it.fabiodirauso.shutappchat.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DrawingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ElementType { EMOJI, TEXT }
    enum class ResizeHandle { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    data class DrawingElement(
        val type: ElementType,
        val content: String,
        var x: Float,
        var y: Float,
        var size: Float,
        var rotation: Float = 0f,  // Rotazione in gradi
        val color: Int = Color.WHITE,
        val typeface: Typeface = Typeface.DEFAULT,
        var isBold: Boolean = false,
        var isItalic: Boolean = false,
        var isUnderline: Boolean = false,
        val backgroundColor: Int? = null,
        var width: Float = 0f,  // For text bounding box
        var height: Float = 0f
    )

    private val elements = mutableListOf<DrawingElement>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.CYAN
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.CYAN
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var selectedElement: DrawingElement? = null
    private var selectedIndex: Int = -1
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var activeResizeHandle: ResizeHandle = ResizeHandle.NONE
    private var initialSize = 0f
    private var initialTouchDistance = 0f
    private var initialRotation = 0f
    private var initialAngle = 0f
    private var initialElementX = 0f
    private var initialElementY = 0f
    private var isRotating = false

    var onTextLongPress: ((DrawingElement, Int) -> Unit)? = null
    var onTextTap: ((DrawingElement, Int) -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val tappedElement = findElementAt(e.x, e.y)
            if (tappedElement != null) {
                val index = elements.indexOf(tappedElement)
                if (index != -1) {
                    // Select or deselect
                    if (selectedElement == tappedElement) {
                        // Already selected - do nothing or could deselect
                    } else {
                        selectedElement = tappedElement
                        selectedIndex = index
                        if (tappedElement.type == ElementType.TEXT) {
                            onTextTap?.invoke(tappedElement, index)
                        }
                        invalidate()
                    }
                    return true
                }
            } else {
                // Tap on empty area - deselect
                if (selectedElement != null) {
                    selectedElement = null
                    selectedIndex = -1
                    invalidate()
                }
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            val element = findElementAt(e.x, e.y)
            if (element != null && element.type == ElementType.TEXT) {
                val index = elements.indexOf(element)
                if (index != -1) {
                    selectedElement = element
                    selectedIndex = index
                    onTextLongPress?.invoke(element, index)
                    invalidate()
                }
            }
        }
    })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        elements.forEach { element ->
            canvas.save()
            canvas.rotate(element.rotation, element.x, element.y)
            
            when (element.type) {
                ElementType.EMOJI -> drawEmoji(canvas, element)
                ElementType.TEXT -> drawText(canvas, element)
            }
            
            canvas.restore()
        }

        // Draw selection box and handles if element is selected
        selectedElement?.let { element ->
            canvas.save()
            canvas.rotate(element.rotation, element.x, element.y)
            drawSelectionBox(canvas, element)
            drawResizeHandles(canvas, element)
            canvas.restore()
        }
    }

    private fun drawEmoji(canvas: Canvas, element: DrawingElement) {
        paint.textSize = element.size
        paint.typeface = Typeface.DEFAULT
        paint.color = Color.WHITE
        
        // Calculate emoji bounds for selection
        val bounds = Rect()
        paint.getTextBounds(element.content, 0, element.content.length, bounds)
        val emojiWidth = paint.measureText(element.content)
        val emojiHeight = bounds.height().toFloat()
        
        // Update element dimensions
        element.width = emojiWidth + 32f // padding
        element.height = emojiHeight + 32f
        
        canvas.drawText(element.content, element.x, element.y, paint)
    }

    private fun drawText(canvas: Canvas, element: DrawingElement) {
        // Setup typeface with bold/italic
        var style = Typeface.NORMAL
        if (element.isBold && element.isItalic) style = Typeface.BOLD_ITALIC
        else if (element.isBold) style = Typeface.BOLD
        else if (element.isItalic) style = Typeface.ITALIC
        
        paint.textSize = element.size
        paint.color = element.color
        paint.typeface = Typeface.create(element.typeface, style)
        paint.isUnderlineText = element.isUnderline

        // Calculate text bounds
        val bounds = Rect()
        paint.getTextBounds(element.content, 0, element.content.length, bounds)
        val textWidth = paint.measureText(element.content)
        val textHeight = bounds.height().toFloat()

        // Update element dimensions
        element.width = textWidth + 32f // padding
        element.height = textHeight + 32f

        // Draw background if specified
        element.backgroundColor?.let { bgColor ->
            backgroundPaint.color = bgColor
            val left = element.x - 16f
            val top = element.y - textHeight - 8f
            val right = element.x + textWidth + 16f
            val bottom = element.y + 8f
            canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, backgroundPaint)
        }

        // Draw text with outline for better visibility
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        outlinePaint.textSize = paint.textSize
        outlinePaint.typeface = paint.typeface
        outlinePaint.isUnderlineText = paint.isUnderlineText
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeWidth = 4f
        outlinePaint.color = Color.BLACK
        canvas.drawText(element.content, element.x, element.y, outlinePaint)
        canvas.drawText(element.content, element.x, element.y, paint)
    }

    private fun drawSelectionBox(canvas: Canvas, element: DrawingElement) {
        val bounds = getElementBounds(element)
        canvas.drawRect(bounds, selectionPaint)
    }

    private fun drawResizeHandles(canvas: Canvas, element: DrawingElement) {
        val bounds = getElementBounds(element)
        val handleSize = 32f

        // Draw 4 corner handles
        canvas.drawCircle(bounds.left, bounds.top, handleSize / 2, handlePaint)
        canvas.drawCircle(bounds.right, bounds.top, handleSize / 2, handlePaint)
        canvas.drawCircle(bounds.left, bounds.bottom, handleSize / 2, handlePaint)
        canvas.drawCircle(bounds.right, bounds.bottom, handleSize / 2, handlePaint)
    }

    private fun getElementBounds(element: DrawingElement): RectF {
        paint.textSize = element.size
        
        // Ensure element has width/height calculated
        if (element.width == 0f || element.height == 0f) {
            val bounds = Rect()
            paint.getTextBounds(element.content, 0, element.content.length, bounds)
            val textWidth = paint.measureText(element.content)
            val textHeight = bounds.height().toFloat()
            element.width = textWidth + 32f
            element.height = textHeight + 32f
        }
        
        val padding = 24f
        val bounds = Rect()
        paint.getTextBounds(element.content, 0, element.content.length, bounds)
        val textHeight = bounds.height().toFloat()
        val textWidth = paint.measureText(element.content)
        
        return RectF(
            element.x - padding,
            element.y - textHeight - padding,
            element.x + textWidth + padding,
            element.y + padding
        )
    }

    private fun getHandleAtPoint(x: Float, y: Float, element: DrawingElement): ResizeHandle {
        // Transform touch coordinates to account for element rotation
        val touchPoint = transformTouchPoint(x, y, element)
        
        val bounds = getElementBounds(element)
        val handleRadius = 40f // Touch area

        if (distance(touchPoint.x, touchPoint.y, bounds.left, bounds.top) < handleRadius) return ResizeHandle.TOP_LEFT
        if (distance(touchPoint.x, touchPoint.y, bounds.right, bounds.top) < handleRadius) return ResizeHandle.TOP_RIGHT
        if (distance(touchPoint.x, touchPoint.y, bounds.left, bounds.bottom) < handleRadius) return ResizeHandle.BOTTOM_LEFT
        if (distance(touchPoint.x, touchPoint.y, bounds.right, bounds.bottom) < handleRadius) return ResizeHandle.BOTTOM_RIGHT
        
        return ResizeHandle.NONE
    }

    private fun transformTouchPoint(x: Float, y: Float, element: DrawingElement): PointF {
        // Apply inverse rotation to touch point
        if (element.rotation == 0f) {
            return PointF(x, y)
        }
        
        val radians = Math.toRadians(-element.rotation.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        
        val dx = x - element.x
        val dy = y - element.y
        
        val rotatedX = element.x + (dx * cos - dy * sin)
        val rotatedY = element.y + (dx * sin + dy * cos)
        
        return PointF(rotatedX, rotatedY)
    }

    private fun isTouchInsideElement(x: Float, y: Float, element: DrawingElement): Boolean {
        val touchPoint = transformTouchPoint(x, y, element)
        val bounds = getElementBounds(element)
        return bounds.contains(touchPoint.x, touchPoint.y)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun getAngle(x: Float, y: Float, centerX: Float, centerY: Float): Float {
        val deltaX = x - centerX
        val deltaY = y - centerY
        return Math.toDegrees(kotlin.math.atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isRotating = false
                selectedElement?.let { element ->
                    // Check if touching a resize handle
                    val handle = getHandleAtPoint(event.x, event.y, element)
                    if (handle != ResizeHandle.NONE) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        activeResizeHandle = handle
                        initialSize = element.size
                        initialTouchDistance = distance(event.x, event.y, element.x, element.y)
                        return true
                    }

                    // Check if touching element for drag
                    if (isTouchInsideElement(event.x, event.y, element)) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        val touchPoint = transformTouchPoint(event.x, event.y, element)
                        dragOffsetX = touchPoint.x - element.x
                        dragOffsetY = touchPoint.y - element.y
                        return true
                    }
                }
                
                // Check if touching any element (for selection via tap)
                val elementAtPoint = findElementAt(event.x, event.y)
                if (elementAtPoint != null) {
                    // There's an element here - let gesture detector handle it
                    return true
                }
                
                // No element - pass through to image
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Secondo dito - inizia rotazione attorno al centro dello schermo
                if (event.pointerCount == 2) {
                    selectedElement?.let { element ->
                        isRotating = true
                        initialRotation = element.rotation
                        initialElementX = element.x
                        initialElementY = element.y
                        
                        // Centro dello schermo
                        val centerX = width / 2f
                        val centerY = height / 2f
                        
                        initialAngle = getAngle(event.getX(0), event.getY(0), centerX, centerY)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                selectedElement?.let { element ->
                    if (isRotating && event.pointerCount == 2) {
                        // Rotazione attorno al centro dello schermo
                        val centerX = width / 2f
                        val centerY = height / 2f
                        
                        val currentAngle = getAngle(event.getX(0), event.getY(0), centerX, centerY)
                        val deltaAngle = currentAngle - initialAngle
                        
                        // Aggiorna la rotazione dell'elemento
                        element.rotation = initialRotation + deltaAngle
                        
                        // Calcola la nuova posizione dell'elemento ruotando attorno al centro
                        val radians = Math.toRadians(deltaAngle.toDouble())
                        val cos = kotlin.math.cos(radians).toFloat()
                        val sin = kotlin.math.sin(radians).toFloat()
                        
                        val dx = initialElementX - centerX
                        val dy = initialElementY - centerY
                        
                        element.x = centerX + (dx * cos - dy * sin)
                        element.y = centerY + (dx * sin + dy * cos)
                        
                        invalidate()
                        return true
                    } else if (activeResizeHandle != ResizeHandle.NONE) {
                        // Resize by dragging handle
                        val currentDistance = distance(event.x, event.y, element.x, element.y)
                        val scaleFactor = currentDistance / initialTouchDistance
                        element.size = (initialSize * scaleFactor).coerceIn(20f, 200f)
                        invalidate()
                        return true
                    } else if (dragOffsetX != 0f || dragOffsetY != 0f) {
                        // Drag element
                        val touchPoint = transformTouchPoint(event.x, event.y, element)
                        element.x = touchPoint.x - dragOffsetX
                        element.y = touchPoint.y - dragOffsetY
                        invalidate()
                        return true
                    }
                }
                return false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (isRotating) {
                    isRotating = false
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeResizeHandle != ResizeHandle.NONE || dragOffsetX != 0f || isRotating) {
                    activeResizeHandle = ResizeHandle.NONE
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    isRotating = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }
        }

        return false
    }

    private fun findElementAt(x: Float, y: Float): DrawingElement? {
        Log.d("DrawingCanvas", "findElementAt: x=$x, y=$y, elements.size=${elements.size}")
        for (i in elements.indices.reversed()) {
            val element = elements[i]
            val bounds = getElementBounds(element)
            val inside = isTouchInsideElement(x, y, element)
            Log.d("DrawingCanvas", "Element $i (${element.type}, '${element.content}'): bounds=$bounds, rotation=${element.rotation}, inside=$inside")
            if (inside) {
                Log.d("DrawingCanvas", "Found element at index $i")
                return element
            }
        }
        Log.d("DrawingCanvas", "No element found at touch point")
        return null
    }

    fun addEmoji(emoji: String, size: Float = 80f) {
        val element = DrawingElement(
            type = ElementType.EMOJI,
            content = emoji,
            x = width / 2f,
            y = height / 2f,
            size = size
        )
        elements.add(element)
        invalidate()
    }

    fun addText(
        text: String,
        size: Float = 60f,
        color: Int = Color.WHITE,
        typeface: Typeface = Typeface.DEFAULT,
        backgroundColor: Int? = null
    ) {
        val element = DrawingElement(
            type = ElementType.TEXT,
            content = text,
            x = width / 2f,
            y = height / 2f,
            size = size,
            color = color,
            typeface = typeface,
            backgroundColor = backgroundColor
        )
        elements.add(element)
        selectedElement = element
        selectedIndex = elements.size - 1
        invalidate()
    }

    fun updateTextElement(
        index: Int,
        text: String,
        size: Float,
        color: Int,
        typeface: Typeface,
        isBold: Boolean,
        isItalic: Boolean,
        isUnderline: Boolean,
        backgroundColor: Int?
    ) {
        if (index >= 0 && index < elements.size) {
            val oldElement = elements[index]
            elements[index] = oldElement.copy(
                content = text,
                size = size,
                color = color,
                typeface = typeface,
                isBold = isBold,
                isItalic = isItalic,
                isUnderline = isUnderline,
                backgroundColor = backgroundColor
            )
            selectedElement = elements[index]
            invalidate()
        }
    }

    fun removeLastElement() {
        if (elements.isNotEmpty()) {
            val removed = elements.removeAt(elements.size - 1)
            if (removed == selectedElement) {
                selectedElement = null
                selectedIndex = -1
            }
            invalidate()
        }
    }

    fun getElements(): List<DrawingElement> = elements.toList()
}
