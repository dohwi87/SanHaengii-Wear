package com.sanhaengii.wearhealthsender

enum class EmergencyPhase {
    IDLE,
    COUNTDOWN,
    SENDING,
    SUCCESS,
    FAILED,
}

enum class EmergencySource {
    LOCAL_SENSOR,
    REMOTE_HEALTH_DATA,
    BACKEND_RESPONSE,
    MOBILE_SYNC,
    MANUAL_SOS,
}

enum class EmergencyTrigger(val apiValue: String) {
    USER_CONFIRM("user_confirm"),
    AUTO_TIMEOUT("auto_timeout"),
    MANUAL_LONG_PRESS("manual_watch"),
}

data class EmergencyAlert(
    val anomalyType: AnomalyType,
    val message: String,
    val source: EmergencySource,
    val sosRequestId: Int? = null,
)

data class EmergencyState(
    val phase: EmergencyPhase = EmergencyPhase.IDLE,
    val alert: EmergencyAlert? = null,
    val countdownSeconds: Int? = null,
    val trigger: EmergencyTrigger? = null,
    val failureMessage: String? = null,
)

/**
 * 긴급 신고 UI와 네트워크 요청의 유일한 상태 전이 지점입니다.
 * 네트워크 콜백이 늦게 도착하더라도 현재 단계와 맞지 않으면 무시합니다.
 */
class EmergencyStateMachine(
    private val defaultCountdownSeconds: Int = 30,
) {
    var state: EmergencyState = EmergencyState()
        private set

    fun startAlert(alert: EmergencyAlert, countdownSeconds: Int = defaultCountdownSeconds): Boolean {
        if (state.phase != EmergencyPhase.IDLE) return false
        state = EmergencyState(
            phase = EmergencyPhase.COUNTDOWN,
            alert = alert,
            countdownSeconds = countdownSeconds.coerceAtLeast(0),
        )
        return true
    }

    /** 정확히 1초에서 0초가 되는 한 번만 true를 반환합니다. */
    fun tickCountdown(): Boolean {
        if (state.phase != EmergencyPhase.COUNTDOWN) return false
        val current = state.countdownSeconds ?: return false
        if (current <= 0) return false
        val next = current - 1
        state = state.copy(countdownSeconds = next)
        return next == 0
    }

    fun beginSending(trigger: EmergencyTrigger): EmergencyAlert? {
        if (state.phase != EmergencyPhase.COUNTDOWN && state.phase != EmergencyPhase.FAILED) {
            return null
        }
        val alert = state.alert ?: return null
        state = state.copy(
            phase = EmergencyPhase.SENDING,
            countdownSeconds = null,
            trigger = trigger,
            failureMessage = null,
        )
        return alert
    }

    fun markSucceeded(): Boolean {
        if (state.phase != EmergencyPhase.SENDING) return false
        state = state.copy(phase = EmergencyPhase.SUCCESS, failureMessage = null)
        return true
    }

    fun markFailed(message: String? = null): Boolean {
        if (state.phase != EmergencyPhase.SENDING) return false
        state = state.copy(phase = EmergencyPhase.FAILED, failureMessage = message)
        return true
    }

    fun cancel(): EmergencyAlert? {
        if (state.phase != EmergencyPhase.COUNTDOWN && state.phase != EmergencyPhase.FAILED) {
            return null
        }
        val alert = state.alert
        state = EmergencyState()
        return alert
    }

    fun reset() {
        state = EmergencyState()
    }
}
