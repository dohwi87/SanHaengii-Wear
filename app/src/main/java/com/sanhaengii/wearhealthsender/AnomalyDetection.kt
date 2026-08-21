package com.sanhaengii.wearhealthsender

import org.json.JSONObject

enum class AnomalyType {
    HEART_RATE_HIGH,
    HEART_RATE_LOW,
    SPO2_LOW,
    BODY_TEMPERATURE_HIGH,
    BODY_TEMPERATURE_LOW,
    BACKEND_REPORTED,
    MOBILE_REPORTED,
    MANUAL_SOS,
}

data class DetectedAnomaly(
    val type: AnomalyType,
    val message: String,
)

// HR>160 | HR<40 | SpO2<90% | 체온>39°C | 체온<35°C (모바일 앱 기준과 동일)
// 값이 0이면 "측정 실패"로 보고 이상 판정에서 제외(오탐·오신고 방지).
fun detectAnomaly(payload: HealthServicesPayload): DetectedAnomaly? {
    val hr = payload.heartRate
    if (hr != null) {
        if (hr > 160) {
            return DetectedAnomaly(AnomalyType.HEART_RATE_HIGH, "심박수 과부하 ($hr bpm)")
        }
        if (hr in 1..39) {
            return DetectedAnomaly(AnomalyType.HEART_RATE_LOW, "심박수 이상 저하 ($hr bpm)")
        }
    }
    val spo2 = payload.spo2
    if (spo2 != null && spo2 > 0.0 && spo2 < 90.0) {
        return DetectedAnomaly(AnomalyType.SPO2_LOW, "산소포화도 위험 (${spo2.toInt()}%)")
    }
    val temp = payload.bodyTemp
    if (temp != null) {
        if (temp > 39.0) {
            return DetectedAnomaly(AnomalyType.BODY_TEMPERATURE_HIGH, "고열 감지 (${temp}°C)")
        }
        if (temp > 0.0 && temp < 35.0) {
            return DetectedAnomaly(AnomalyType.BODY_TEMPERATURE_LOW, "저체온 위험 (${temp}°C)")
        }
    }
    return null
}

fun detectAnomalyLocally(payload: HealthServicesPayload): String? {
    return detectAnomaly(payload)?.message
}

// /health/data 백엔드 응답 파싱 → (이상 감지 여부, sos_request_id)
// Railway 백엔드: {is_anomaly, message, anomaly_type}
// sos_alerts.py 로컬: {anomaly, sos_request_id}
// 두 형식 모두 지원한다.
fun parseHealthDataResponse(responseBody: String): Pair<Boolean, Int?> {
    return try {
        val json = JSONObject(responseBody)
        val isAnomaly = json.optBoolean("is_anomaly", false) || json.optBoolean("anomaly", false)
        val sosRequestId = if (json.has("sos_request_id") && !json.isNull("sos_request_id")) {
            json.getInt("sos_request_id")
        } else null
        Pair(isAnomaly, sosRequestId)
    } catch (_: Exception) {
        Pair(false, null)
    }
}
