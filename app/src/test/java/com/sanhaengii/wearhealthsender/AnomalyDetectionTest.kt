package com.sanhaengii.wearhealthsender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnomalyDetectionTest {
    @Test
    fun appliesBoundaryValuesWithoutTreatingZeroAsAnomaly() {
        assertNull(detectAnomaly(payload(heartRate = 160, spo2 = 90.0, bodyTemp = 39.0)))
        assertNull(detectAnomaly(payload(heartRate = 40, spo2 = 0.0, bodyTemp = 35.0)))

        assertEquals(AnomalyType.HEART_RATE_HIGH, detectAnomaly(payload(heartRate = 161))?.type)
        assertEquals(AnomalyType.HEART_RATE_LOW, detectAnomaly(payload(heartRate = 39))?.type)
        assertEquals(AnomalyType.SPO2_LOW, detectAnomaly(payload(spo2 = 89.0))?.type)
        assertEquals(
            AnomalyType.BODY_TEMPERATURE_HIGH,
            detectAnomaly(payload(bodyTemp = 39.1))?.type,
        )
        assertEquals(
            AnomalyType.BODY_TEMPERATURE_LOW,
            detectAnomaly(payload(bodyTemp = 34.9))?.type,
        )
    }

    private fun payload(
        heartRate: Int? = null,
        spo2: Double? = null,
        bodyTemp: Double? = null,
    ) = HealthServicesPayload(
        measuredAt = "2026-08-21T12:00:00+09:00",
        heartRate = heartRate,
        steps = 0,
        calories = 0.0,
        spo2 = spo2,
        bodyTemp = bodyTemp,
        bloodPressureSystolic = null,
        bloodPressureDiastolic = null,
    )
}
