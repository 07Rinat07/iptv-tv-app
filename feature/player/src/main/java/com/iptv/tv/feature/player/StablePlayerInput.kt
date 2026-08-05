package com.iptv.tv.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

private const val HORIZONTAL_SCROLL_COOLDOWN_MS = 350L
private const val MIN_GENERIC_SCROLL = 0.01f

internal data class StablePlayerInputCallbacks(
    val onToggleControls: () -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onPreviousChannel: () -> Unit,
    val onNextChannel: () -> Unit,
    val onVolumeUp: () -> Unit,
    val onVolumeDown: () -> Unit,
    val onToggleMute: () -> Unit,
    val onTogglePlayback: () -> Unit
)

internal fun stableScrollAction(
    horizontal: Float,
    vertical: Float
): StableRemoteAction = when {
    abs(horizontal) > abs(vertical) && horizontal > 0f -> StableRemoteAction.PREVIOUS_CHANNEL
    abs(horizontal) > abs(vertical) && horizontal < 0f -> StableRemoteAction.NEXT_CHANNEL
    vertical > 0f -> StableRemoteAction.VOLUME_UP
    vertical < 0f -> StableRemoteAction.VOLUME_DOWN
    else -> StableRemoteAction.NONE
}

internal class StablePlayerInputHandler(context: Context) :
    View.OnTouchListener,
    View.OnGenericMotionListener,
    View.OnKeyListener {

    private val movementThresholdPx = 48f * context.resources.displayMetrics.density
    private var expanded = false
    private var callbacks = StablePlayerInputCallbacks(
        onToggleControls = {},
        onToggleFullscreen = {},
        onPreviousChannel = {},
        onNextChannel = {},
        onVolumeUp = {},
        onVolumeDown = {},
        onToggleMute = {},
        onTogglePlayback = {}
    )
    private var downX = 0f
    private var downY = 0f
    private var verticalDistance = 0f
    private var lastHorizontalScrollAtMs = 0L

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                callbacks.onToggleControls()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                callbacks.onToggleFullscreen()
                return true
            }

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (abs(distanceY) <= abs(distanceX)) return true
                verticalDistance += distanceY
                while (abs(verticalDistance) >= movementThresholdPx) {
                    if (verticalDistance > 0f) {
                        callbacks.onVolumeUp()
                        verticalDistance -= movementThresholdPx
                    } else {
                        callbacks.onVolumeDown()
                        verticalDistance += movementThresholdPx
                    }
                }
                return true
            }
        }
    )

    fun update(
        expanded: Boolean,
        callbacks: StablePlayerInputCallbacks
    ) {
        this.expanded = expanded
        this.callbacks = callbacks
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachTo(view: View) {
        view.setOnTouchListener(this)
        view.setOnGenericMotionListener(this)
        view.setOnKeyListener(this)
    }

    fun detachFrom(view: View) {
        view.setOnTouchListener(null)
        view.setOnGenericMotionListener(null)
        view.setOnKeyListener(null)
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                verticalDistance = 0f
            }

            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - downX
                val deltaY = event.y - downY
                if (abs(deltaX) >= movementThresholdPx && abs(deltaX) > abs(deltaY)) {
                    if (deltaX > 0f) callbacks.onPreviousChannel() else callbacks.onNextChannel()
                }
                verticalDistance = 0f
            }

            MotionEvent.ACTION_CANCEL -> verticalDistance = 0f
        }
        detector.onTouchEvent(event)
        return true
    }

    override fun onGenericMotion(view: View, event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            return false
        }

        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (abs(horizontal) < MIN_GENERIC_SCROLL && abs(vertical) < MIN_GENERIC_SCROLL) {
            return false
        }

        val action = stableScrollAction(horizontal, vertical)
        if (action == StableRemoteAction.NEXT_CHANNEL ||
            action == StableRemoteAction.PREVIOUS_CHANNEL
        ) {
            val now = SystemClock.uptimeMillis()
            if (now - lastHorizontalScrollAtMs < HORIZONTAL_SCROLL_COOLDOWN_MS) return true
            lastHorizontalScrollAtMs = now
        }
        return dispatch(action)
    }

    override fun onKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return false
        return dispatch(stableRemoteActionForKey(keyCode, expanded))
    }

    private fun dispatch(action: StableRemoteAction): Boolean = when (action) {
        StableRemoteAction.TOGGLE_CONTROLS -> {
            callbacks.onToggleControls()
            true
        }

        StableRemoteAction.TOGGLE_FULLSCREEN -> {
            callbacks.onToggleFullscreen()
            true
        }

        StableRemoteAction.NEXT_CHANNEL -> {
            callbacks.onNextChannel()
            true
        }

        StableRemoteAction.PREVIOUS_CHANNEL -> {
            callbacks.onPreviousChannel()
            true
        }

        StableRemoteAction.VOLUME_UP -> {
            callbacks.onVolumeUp()
            true
        }

        StableRemoteAction.VOLUME_DOWN -> {
            callbacks.onVolumeDown()
            true
        }

        StableRemoteAction.TOGGLE_MUTE -> {
            callbacks.onToggleMute()
            true
        }

        StableRemoteAction.TOGGLE_PLAYBACK -> {
            callbacks.onTogglePlayback()
            true
        }

        StableRemoteAction.NONE -> false
    }
}
