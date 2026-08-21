package com.sanhaengii.wearhealthsender

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthServicesPayloadTest {
    @Test
    fun serializesApiFieldNamesAndExplicitNulls() {
        val json = payload(steps = 12, calories = 4.8).toJson(userId = 7)

        assertEquals(7L, json.getLong("user_id"))
        assertEquals("2026-08-21T12:00:00+09:00", json.getString("measured_at"))
        assertEquals(12, json.getInt("steps"))
        assertEquals(4.8, json.getDouble("calories"), 0.0)
        assertTrue(json.isNull("spo2"))
        assertTrue(json.isNull("body_temp"))
        assertTrue(JSONObject(payload(steps = 12, calories = 4.8).toRequestBody(7)).has("blood_pressure_systolic"))
    }

    @Test
    fun mergeKeepsLatestVitalsAndMonotonicTotals() {
        val original = payload(heartRate = 80, steps = 100, calories = 10.0, spo2 = 98.0)
        val update = payload(heartRate = 95, steps = 90, calories = 12.5, spo2 = null)

        val merged = original.mergeWith(update)

        assertEquals(95, merged.heartRate)
        assertEquals(100, merged.steps)
        assertEquals(12.5, merged.calories!!, 0.0)
        assertEquals(98.0, merged.spo2!!, 0.0)
    }

    @Test
    fun requiredFieldsOnlyDependOnStepsAndCalories() {
        val missing = payload(steps = null, calories = null)
        assertFalse(missing.hasCollectedRequiredValues())
        assertEquals("steps, calories", missing.missingRequiredFields())

        assertTrue(payload(steps = 0, calories = 0.0).hasCollectedRequiredValues())
    }

    private fun payload(
        heartRate: Int? = null,
        steps: Int? = null,
        calories: Double? = null,
        spo2: Double? = null,
    ) = HealthServicesPayload(
        measuredAt = "2026-08-21T12:00:00+09:00",
        heartRate = heartRate,
        steps = steps,
        calories = calories,
        spo2 = spo2,
        bodyTemp = null,
        bloodPressureSystolic = null,
        bloodPressureDiastolic = null,
    )
}
