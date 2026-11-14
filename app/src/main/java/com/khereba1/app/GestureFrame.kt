package com.khereba1.app

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * Container for the fullscreen video. Taps and horizontal drags go to the page as usual.
 * - A clearly vertical drag is taken over: left half = brightness, right half = volume.
 * - A two-finger pinch is taken over: pinch out = fill the screen (zoom), pinch in = back to normal.
 */
class GestureFrame(
    context: Context,
    private val onAdjust: (Boolean, Float) -> Unit,
    private val onZoom: (Boolean) -> Unit,
) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var cumulativeScale = 1f
    private var zoomFired = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cumulativeScale = 1f
                zoomFired = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                cumulativeScale *= detector.scaleFactor
                if (!zoomFired) {
                    if (cumulativeScale > 1.15f) {
                        zoomFired = true
                        onZoom(true)
                    } else if (cumulativeScale < 0.87f) {
                        zoomFired = true
                        onZoom(false)
                    }
                }
                return true
            }
        }
    )

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) {
            dragging = false
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && ev.pointerCount == 1) {
                    val dy = Math.abs(ev.y - downY)
                    val dx = Math.abs(ev.x - downX)
                    if (dy > slop * 2 && dy > dx * 1.5f) {
                        dragging = true
                        lastY = ev.y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragging && height > 0 && ev.pointerCount == 1) {
                    val delta = (lastY - ev.y) / height.toFloat() // finger up = positive
                    lastY = ev.y
                    onAdjust(downX < width / 2f, delta)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging || scaleDetector.isInProgress
    }
}
