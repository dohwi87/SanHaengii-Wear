package com.sanhaengii.wearhealthsender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyStateMachineTest {
    private val alert = EmergencyAlert(
        anomalyType = AnomalyType.HEART_RATE_HIGH,
        message = "심박수 과부하",
        source = EmergencySource.LOCAL_SENSOR,
    )

    @Test
    fun countdownTransitionsToSendingAndSuccess() {
        val machine = EmergencyStateMachine(defaultCountdownSeconds = 30)

        assertTrue(machine.startAlert(alert))
        assertEquals(EmergencyPhase.COUNTDOWN, machine.state.phase)
        repeat(29) { assertFalse(machine.tickCountdown()) }
        assertEquals(1, machine.state.countdownSeconds)
        assertTrue(machine.tickCountdown())
        assertEquals(0, machine.state.countdownSeconds)

        assertNotNull(machine.beginSending(EmergencyTrigger.AUTO_TIMEOUT))
        assertEquals(EmergencyPhase.SENDING, machine.state.phase)
        assertTrue(machine.markSucceeded())
        assertEquals(EmergencyPhase.SUCCESS, machine.state.phase)

        machine.reset()
        assertEquals(EmergencyPhase.IDLE, machine.state.phase)
    }

    @Test
    fun duplicateAlertAndLateCallbacksAreRejected() {
        val machine = EmergencyStateMachine()

        assertTrue(machine.startAlert(alert))
        assertFalse(machine.startAlert(alert.copy(message = "중복")))
        assertFalse(machine.markSucceeded())
        assertFalse(machine.markFailed("late callback"))
        assertEquals(EmergencyPhase.COUNTDOWN, machine.state.phase)
    }

    @Test
    fun failedRequestCanRetryOrBeCancelled() {
        val machine = EmergencyStateMachine()
        machine.startAlert(alert)
        machine.beginSending(EmergencyTrigger.USER_CONFIRM)

        assertTrue(machine.markFailed("network"))
        assertEquals(EmergencyPhase.FAILED, machine.state.phase)
        assertNotNull(machine.beginSending(EmergencyTrigger.USER_CONFIRM))
        assertEquals(EmergencyPhase.SENDING, machine.state.phase)

        machine.markFailed("network again")
        assertEquals(alert, machine.cancel())
        assertEquals(EmergencyPhase.IDLE, machine.state.phase)
        assertNull(machine.cancel())
    }

    @Test
    fun zeroSecondManualAlertSendsImmediatelyWithoutCountdownTick() {
        val machine = EmergencyStateMachine()
        val manual = alert.copy(
            anomalyType = AnomalyType.MANUAL_SOS,
            source = EmergencySource.MANUAL_SOS,
        )

        assertTrue(machine.startAlert(manual, countdownSeconds = 0))
        assertFalse(machine.tickCountdown())
        assertEquals(manual, machine.beginSending(EmergencyTrigger.MANUAL_LONG_PRESS))
        assertEquals(EmergencyPhase.SENDING, machine.state.phase)
        assertNull(machine.beginSending(EmergencyTrigger.MANUAL_LONG_PRESS))
    }
}
