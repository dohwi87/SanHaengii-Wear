package com.sanhaengii.wearhealthsender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ResponseParsingTest {
    @Test
    fun parsesBothHealthAnomalyResponseShapes() {
        assertEquals(true to null, parseHealthDataResponse("""{"is_anomaly":true}"""))
        assertEquals(true to 31, parseHealthDataResponse("""{"anomaly":true,"sos_request_id":31}"""))
        assertEquals(false to null, parseHealthDataResponse("not-json"))
    }

    @Test
    fun parsesRemoteSampleWrapperTimestampAndFingerprint() {
        val sample = parseRemoteHealthSample(
            """{"data":{"id":44,"user_id":7,"measured_at":"2026-08-21T12:00:00+09:00","heart_rate":161,"spo2":88.5,"body_temp":null}}"""
        )

        requireNotNull(sample)
        assertEquals("id:44", sample.identity.fingerprint)
        assertEquals(7L, sample.identity.userId)
        assertEquals(Instant.parse("2026-08-21T03:00:00Z").toEpochMilli(), sample.identity.measuredAtEpochMs)
        assertEquals(161, sample.payload.heartRate)
        assertEquals(88.5, sample.payload.spo2!!, 0.0)
        assertNull(sample.payload.bodyTemp)
    }

    @Test
    fun derivesStableFingerprintWhenBackendOmitsRecordId() {
        val sample = parseRemoteHealthSample(
            """{"userId":7,"timestamp":"2026-08-21T12:00:00+09:00","heart_rate":80,"spo2":98.0,"body_temp":36.5}"""
        )

        requireNotNull(sample)
        assertEquals("at:2026-08-21T12:00:00+09:00|hr:80|spo2:98.0|temp:36.5", sample.identity.fingerprint)
    }

    @Test
    fun parsesHikingArraysAndWrappedResponsesWhileDroppingInvalidRows() {
        val array = parseHikingRecordsResponse(
            """[{"id":1,"status":"active","duration_minutes":30,"distance_km":2.5,"started_at":"a"},{"status":"active"}]"""
        )
        val wrapped = parseHikingRecordsResponse(
            """{"rows":[{"id":2,"status":"paused","duration_minutes":null,"distance_km":1.2,"started_at":"b"}]}"""
        )

        assertEquals(1, array.size)
        assertEquals(30.0, array.single().durationMinutes!!, 0.0)
        assertEquals("paused", wrapped.single().status)
        assertNull(wrapped.single().durationMinutes)
        assertTrue(parseHikingRecordsResponse("{}" ).isEmpty())
    }

    @Test
    fun rejectsIncompleteCredentials() {
        assertEquals(WatchCredentials("jwt", 7), parseWatchCredentials("""{"token":"jwt","user_id":7}"""))
        assertNull(parseWatchCredentials("""{"token":"","user_id":7}"""))
        assertNull(parseWatchCredentials("""{"token":"jwt","user_id":0}"""))
        assertFalse(parseWatchCredentials("broken") != null)
    }
}
