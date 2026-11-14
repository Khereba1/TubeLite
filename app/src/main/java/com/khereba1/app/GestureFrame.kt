package com.khereba1.app

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * Container for the fullscreen video. Taps and horizontal drags go to the page as usual.
 * - A vertical drag is taken over: left half = brightness, right half = volume.
 * - A two-finger pinch is taken over: pinch out = fill the screen (zoom), pinch in = back to normal.
 *
 * It works in dispatchTouchEvent, not onInterceptTouchEvent: the WebView asks its parents not to intercept
 * once it starts handling a gesture (pinch especially), after which onInterceptTouchEvent is never called.
 * When we take a gesture over, the page gets one ACTION_CANCEL (so it drops any swipe/seek/exit it started)
 * and never sees the rest of that gesture.
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
    private var pending = 0f
    private var lastAdjustTime = 0L
    private var dragging = false
    private var taken = false
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
            lastY = ev.y
            pending = 0f
            lastAdjustTime = 0L
            dragging = false
            taken = false
        }

        if (!taken) {
            if (scaleDetector.isInProgress) {
                takeOver(ev)
            } else if (action == MotionEvent.ACTION_MOVE && ev.pointerCount == 1) {
                val dy = Math.abs(ev.y - downY)
                val dx = Math.abs(ev.x - downX)
                // Start early (before the page decides it is its own swipe), but only for clearly vertical moves.
                if (dy > slop * 0.75f && dy > dx * 1.5f) {
                    dragging = true
                    lastY = downY
                    takeOver(ev)
                }
            }
        }
        if (!taken) return super.dispatchTouchEvent(ev)

        if (dragging && action == MotionEvent.ACTION_MOVE && ev.pointerCount == 1 && height > 0) {
            pending += (lastY - ev.y) / height.toFloat() // finger up = positive
            lastY = ev.y
            if (ev.eventTime - lastAdjustTime >= 16L) { // at most ~60 updates per second
                lastAdjustTime = ev.eventTime
                val d = pending
                pending = 0f
                onAdjust(downX < width / 2f, d)
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            taken = false
            dragging = false
        }
        return true
    }

    private fun takeOver(ev: MotionEvent) {
        taken = true
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }
}
