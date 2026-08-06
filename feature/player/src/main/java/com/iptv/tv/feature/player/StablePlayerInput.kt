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
private const val CONTROLS_AUTO_HIDE_MS = 4_000L
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

internal fun stableActionRevealsControls(action: StableRemoteAction): Boolean = when (action) {
    StableRemoteAction.NEXT_CHANNEL,
    StableRemoteAction.PREVIOUS_CHANNEL,
    StableRemoteAction.VOLUME_UP,
    StableRemoteAction.VOLUME_DOWN,
    StableRemoteAction.TOGGLE_MUTE,
    StableRemoteAction.TOGGLE_PLAYBACK -> true

    StableRemoteAction.TOGGLE_CONTROLS,
    StableRemoteAction.TOGGLE_FULLSCREEN,
    StableRemoteAction.NONE -> false
}

internal class StablePlayerInputHandler(context: Context) :
    View.OnTouchListener,
    View.OnGenericMotionListener,
    View.OnKeyListener {

    private val movementThresholdPx = 48f * context.resources.displayMetrics.density
    private var expanded = false
    private var controlsVisible = true
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
    private var attachedView: View? = null
    private var downX = 0f
    private var downY = 0f
    private var verticalDistance = 0f
    private var lastHorizontalScrollAtMs = 0L

    private val hideControlsRunnable = Runnable {
        if (expanded && controlsVisible) {
            controlsVisible = false
            callbacks.onToggleControls()
        }
    }

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                cancelAutoHide()
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
                revealControlsForInput()
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
        val enteringFullscreen = expanded && !this.expanded
        this.expanded = expanded
        this.callbacks = callbacks

        if (enteringFullscreen) controlsVisible = true
        if (expanded && controlsVisible) scheduleAutoHide() else cancelAutoHide()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachTo(view: View) {
        if (attachedView !== view) {
            attachedView?.removeCallbacks(hideControlsRunnable)
            attachedView = view
        }
        view.setOnTouchListener(this)
        view.setOnGenericMotionListener(this)
        view.setOnKeyListener(this)
        if (expanded && controlsVisible) scheduleAutoHide()
    }

    fun detachFrom(view: View) {
        view.setOnTouchListener(null)
        view.setOnGenericMotionListener(null)
        view.setOnKeyListener(null)
        view.removeCallbacks(hideControlsRunnable)
        if (attachedView === view) attachedView = null
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
                    revealControlsForInput()
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

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        callbacks.onToggleControls()
        if (controlsVisible) scheduleAutoHide() else cancelAutoHide()
    }

    private fun revealControlsForInput() {
        if (!controlsVisible) {
            controlsVisible = true
            callbacks.onToggleControls()
        }
        if (expanded) scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        val view = attachedView ?: return
        view.removeCallbacks(hideControlsRunnable)
        view.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
    }

    private fun cancelAutoHide() {
        attachedView?.removeCallbacks(hideControlsRunnable)
    }

    private fun dispatch(action: StableRemoteAction): Boolean {
        if (stableActionRevealsControls(action)) revealControlsForInput()
        return when (action) {
            StableRemoteAction.TOGGLE_CONTROLS -> {
                toggleControls()
                true
            }

            StableRemoteAction.TOGGLE_FULLSCREEN -> {
                cancelAutoHide()
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
}
