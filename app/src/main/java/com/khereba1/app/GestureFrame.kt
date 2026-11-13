package com.khereba1.app

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * Container for the fullscreen video. Taps and horizontal drags go to the page as usual.
 * A clearly vertical drag is taken over: left half = brightness, right half = volume.
 */
class GestureFrame(
    context: Context,
    private val onAdjust: (Boolean, Float) -> Unit,
) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
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
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragging && height > 0) {
                    val delta = (lastY - ev.y) / height.toFloat() // finger up = positive
                    lastY = ev.y
                    onAdjust(downX < width / 2f, delta)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }
}
