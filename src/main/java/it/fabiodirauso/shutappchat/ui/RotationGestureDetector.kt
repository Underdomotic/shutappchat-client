package it.fabiodirauso.shutappchat.ui

import android.view.MotionEvent
import kotlin.math.atan2

class RotationGestureDetector(private val listener: OnRotationGestureListener) {

    interface OnRotationGestureListener {
        fun onRotation(rotationDegrees: Float): Boolean
    }

    private var previousAngle = 0f
    private var isRotating = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    previousAngle = getAngle(event)
                    isRotating = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRotating && event.pointerCount == 2) {
                    val currentAngle = getAngle(event)
                    val deltaAngle = currentAngle - previousAngle
                    
                    // Normalize angle to -180 to 180
                    var normalizedDelta = deltaAngle
                    if (normalizedDelta > 180f) normalizedDelta -= 360f
                    if (normalizedDelta < -180f) normalizedDelta += 360f
                    
                    listener.onRotation(normalizedDelta)
                    previousAngle = currentAngle
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isRotating = false
            }
        }
        return false
    }

    private fun getAngle(event: MotionEvent): Float {
        val deltaX = event.getX(1) - event.getX(0)
        val deltaY = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
    }
}