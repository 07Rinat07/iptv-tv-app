package com.iptv.tv.sync

/**
 * Process-level foreground transition tracker built from Activity lifecycle callbacks.
 *
 * Configuration-driven Activity recreation is deliberately not treated as a real foreground
 * return: the UI never became background in the user-visible sense and must not trigger an
 * expensive XMLTV refresh merely because an Activity instance was replaced.
 */
class EpgForegroundReturnTracker {
    private var startedActivityCount = 0
    private var hasEnteredForeground = false
    private var configurationChangeInProgress = false

    fun onActivityStarted(): EpgForegroundStartTransition {
        val enteringForeground = startedActivityCount == 0
        val returnedFromBackground =
            enteringForeground && hasEnteredForeground && !configurationChangeInProgress

        startedActivityCount += 1
        if (enteringForeground) {
            hasEnteredForeground = true
            configurationChangeInProgress = false
        }

        return EpgForegroundStartTransition(
            enteredForeground = enteringForeground,
            returnedFromBackground = returnedFromBackground
        )
    }

    /** Returns true only when the last started Activity actually stops. */
    fun onActivityStopped(isChangingConfigurations: Boolean): Boolean {
        if (startedActivityCount == 0) return false

        startedActivityCount -= 1
        val leftForeground = startedActivityCount == 0
        if (leftForeground) {
            configurationChangeInProgress = isChangingConfigurations
        }
        return leftForeground
    }
}

data class EpgForegroundStartTransition(
    val enteredForeground: Boolean,
    val returnedFromBackground: Boolean
)
