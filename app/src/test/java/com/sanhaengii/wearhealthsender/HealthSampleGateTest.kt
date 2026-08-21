package com.sanhaengii.wearhealthsender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HealthSampleGateTest {
    private val now = Instant.parse("2026-08-21T12:00:00Z").toEpochMilli()

    @Test
    fun acceptsFreshSampleOnlyOnce() {
        val gate = gate()
        val sample = sample(fingerprint = "id:10", measuredAt = now - 10_000, userId = 7)

        assertEquals(HealthSampleDecision.Accepted, gate.evaluate(sample, 7, now))
        assertRejected(gate.evaluate(sample, 7, now), HealthSampleRejection.DUPLICATE)
    }

    @Test
    fun releasedSampleCanBeRetriedAfterStateMachineBecomesIdle() {
        val gate = gate()
        val sample = sample(fingerprint = "id:retry", measuredAt = now, userId = 7)

        assertEquals(HealthSampleDecision.Accepted, gate.evaluate(sample, 7, now))
        gate.release(sample.fingerprint)
        assertEquals(HealthSampleDecision.Accepted, gate.evaluate(sample, 7, now))
    }

    @Test
    fun rejectsMissingStaleAndFutureTimestamps() {
        val gate = gate()

        assertRejected(
            gate.evaluate(sample("missing", null, 7), 7, now),
            HealthSampleRejection.MISSING_TIMESTAMP,
        )
        assertRejected(
            gate.evaluate(sample("stale", now - 90_001, 7), 7, now),
            HealthSampleRejection.STALE,
        )
        assertRejected(
            gate.evaluate(sample("future", now + 30_001, 7), 7, now),
            HealthSampleRejection.FUTURE_TIMESTAMP,
        )
    }

    @Test
    fun rejectsAnotherUsersSample() {
        val decision = gate().evaluate(sample("id:20", now, 99), expectedUserId = 7, nowEpochMs = now)
        assertRejected(decision, HealthSampleRejection.USER_MISMATCH)
    }

    @Test
    fun parsesOffsetAndKstLocalTimestamps() {
        val expected = Instant.parse("2026-08-21T03:00:00Z").toEpochMilli()

        assertEquals(expected, parseHealthSampleTimestamp("2026-08-21T12:00:00+09:00"))
        assertEquals(expected, parseHealthSampleTimestamp("2026-08-21T12:00:00"))
        assertNull(parseHealthSampleTimestamp("not-a-date"))
    }

    private fun gate() = HealthSampleGate(
        maxAgeMillis = 90_000,
        futureToleranceMillis = 30_000,
    )

    private fun sample(fingerprint: String, measuredAt: Long?, userId: Long?) =
        RemoteHealthSampleIdentity(fingerprint, measuredAt, userId)

    private fun assertRejected(decision: HealthSampleDecision, reason: HealthSampleRejection) {
        assertTrue(decision is HealthSampleDecision.Rejected)
        assertEquals(reason, (decision as HealthSampleDecision.Rejected).reason)
    }
}
