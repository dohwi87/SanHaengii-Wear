package com.sanhaengii.wearhealthsender

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    private val emergencyMachine = EmergencyStateMachine()

    var bpm by mutableIntStateOf(0)
    var eta by mutableStateOf("-")
    var distance by mutableStateOf("-")
    var isPaused by mutableStateOf(false)
    var isHikingActive by mutableStateOf(false)
    var emergencyState by mutableStateOf(emergencyMachine.state)
        private set

    val isEmergencyVisible: Boolean
        get() = emergencyState.phase != EmergencyPhase.IDLE

    val anomalyMessage: String
        get() = emergencyState.alert?.message.orEmpty()

    val anomalyCountdown: Int?
        get() = emergencyState.countdownSeconds

    val isMobileAnomalySource: Boolean
        get() = emergencyState.alert?.source == EmergencySource.MOBILE_SYNC

    val emergencySendState: String
        get() = when (emergencyState.phase) {
            EmergencyPhase.IDLE, EmergencyPhase.COUNTDOWN -> "idle"
            EmergencyPhase.SENDING -> "sending"
            EmergencyPhase.SUCCESS -> "success"
            EmergencyPhase.FAILED -> "failed"
        }

    fun updateHeartRate(newBpm: Int) {
        bpm = newBpm
    }

    fun updateEta(newEta: String) {
        eta = newEta
    }

    fun updateDistance(newDistance: String) {
        distance = newDistance
    }

    fun togglePause() {
        isPaused = !isPaused
    }

    fun updatePaused(paused: Boolean) {
        isPaused = paused
    }

    fun updateHikingActive(active: Boolean) {
        isHikingActive = active
        if (!active) isPaused = false
    }

    fun startEmergencyAlert(alert: EmergencyAlert, countdownSeconds: Int = 30): Boolean {
        val started = emergencyMachine.startAlert(alert, countdownSeconds)
        syncEmergencyState()
        return started
    }

    fun tickCountdown(): Boolean {
        val finished = emergencyMachine.tickCountdown()
        syncEmergencyState()
        return finished
    }

    fun beginEmergencySend(trigger: EmergencyTrigger): EmergencyAlert? {
        val alert = emergencyMachine.beginSending(trigger)
        syncEmergencyState()
        return alert
    }

    fun markEmergencySucceeded(): Boolean {
        val changed = emergencyMachine.markSucceeded()
        syncEmergencyState()
        return changed
    }

    fun markEmergencyFailed(message: String? = null): Boolean {
        val changed = emergencyMachine.markFailed(message)
        syncEmergencyState()
        return changed
    }

    fun cancelEmergency(): EmergencyAlert? {
        val alert = emergencyMachine.cancel()
        syncEmergencyState()
        return alert
    }

    fun resetEmergency() {
        emergencyMachine.reset()
        syncEmergencyState()
    }

    private fun syncEmergencyState() {
        emergencyState = emergencyMachine.state
    }
}
