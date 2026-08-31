package com.iptv.tv.sync

/**
 * Process-level foreground transition tracker built from Activity lifecycle callbacks.
 *
 * Configuration-driven Activity recreation is deliberately not treated as leaving or re-entering
 * foreground: the UI never became background in the user-visible sense and must not trigger an
 * expensive XMLTV refresh or transiently rewrite process foreground diagnostics.
 */
class EpgForegroundReturnTracker {
    private var startedActivityCount = 0
    private var hasEnteredForeground = false
    private var configurationChangeInProgress = false

    fun onActivityStarted(): EpgForegroundStartTransition {
        val crossingActivityBoundary = startedActivityCount == 0
        val replacingConfiguration = crossingActivityBoundary && configurationChangeInProgress
        val enteredForeground = crossingActivityBoundary && !replacingConfiguration
        val returnedFromBackground = enteredForeground && hasEnteredForeground

        startedActivityCount += 1
        if (crossingActivityBoundary) {
            if (enteredForeground) {
                hasEnteredForeground = true
            }
            configurationChangeInProgress = false
        }

        return EpgForegroundStartTransition(
            enteredForeground = enteredForeground,
            returnedFromBackground = returnedFromBackground
        )
    }

    /** Returns true only when the process really leaves foreground, not during configuration recreation. */
    fun onActivityStopped(isChangingConfigurations: Boolean): Boolean {
        if (startedActivityCount == 0) return false

        startedActivityCount -= 1
        if (startedActivityCount != 0) return false

        configurationChangeInProgress = isChangingConfigurations
        return !isChangingConfigurations
    }
}

data class EpgForegroundStartTransition(
    val enteredForeground: Boolean,
    val returnedFromBackground: Boolean
)
