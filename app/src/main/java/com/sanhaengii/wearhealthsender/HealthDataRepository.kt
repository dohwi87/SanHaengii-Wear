package com.sanhaengii.wearhealthsender

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ApiResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

data class WatchCredentials(val token: String, val userId: Long)

data class HikingRecord(
    val id: Long,
    val status: String,
    val durationMinutes: Double?,
    val distanceKm: Double?,
    val startedAt: String,
)

data class RemoteHealthSample(
    val payload: HealthServicesPayload,
    val identity: RemoteHealthSampleIdentity,
)

class HealthDataRepository(
    private val apiBaseUrl: String,
    private val relayBaseUrl: String,
    private val allowCleartext: Boolean,
) {
    fun postHealthData(payload: HealthServicesPayload, userId: Long, token: String): ApiResponse {
        return request(
            baseUrl = apiBaseUrl,
            path = "/health/data",
            method = "POST",
            token = token,
            body = payload.toRequestBody(userId),
            connectTimeoutMs = 15_000,
            readTimeoutMs = 15_000,
        )
    }

    fun postEmergency(body: String, token: String): ApiResponse {
        return request(
            baseUrl = apiBaseUrl,
            path = "/api/emergency",
            method = "POST",
            token = token,
            body = body,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 15_000,
        )
    }

    fun fetchLatestHealthData(token: String): RemoteHealthSample? {
        val response = request(apiBaseUrl, "/health/data/latest", "GET", token)
        if (!response.isSuccessful) return null
        return parseRemoteHealthSample(response.body)
    }

    fun filterHikingRecords(userId: Long, token: String): List<HikingRecord> {
        val response = request(
            baseUrl = apiBaseUrl,
            path = "/data/hiking_records/filter?select=*&limit=20",
            method = "POST",
            token = token,
            body = JSONObject().put("user_id", userId).toString(),
        )
        if (!response.isSuccessful) return emptyList()
        return parseHikingRecordsResponse(response.body)
    }

    fun updateHikingStatus(recordId: Long, status: String, token: String): Boolean {
        return request(
            baseUrl = apiBaseUrl,
            path = "/data/hiking_records/$recordId",
            method = "PUT",
            token = token,
            body = JSONObject().put("status", status).toString(),
        ).isSuccessful
    }

    fun fetchWatchCredentials(): WatchCredentials? {
        if (relayBaseUrl.isBlank()) return null
        val response = request(relayBaseUrl, "/api/watch-credentials/latest", "GET", "")
        if (!response.isSuccessful) return null
        return parseWatchCredentials(response.body)
    }

    private fun request(
        baseUrl: String,
        path: String,
        method: String,
        token: String,
        body: String? = null,
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 5_000,
    ): ApiResponse {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        require(normalizedBase.isNotBlank()) { "Backend URL is empty" }
        val url = URL("$normalizedBase$path")
        require(url.protocol == "https" || allowCleartext) {
            "Cleartext HTTP is disabled for this build"
        }

        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            ApiResponse(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

fun parseWatchCredentials(text: String): WatchCredentials? {
    return runCatching {
        val json = JSONObject(text)
        val token = json.optString("token", "").trim()
        val userId = json.opt("user_id")?.toString()?.toLongOrNull() ?: return null
        if (token.isBlank() || userId <= 0) null else WatchCredentials(token, userId)
    }.getOrNull()
}

fun parseRemoteHealthSample(text: String): RemoteHealthSample? {
    return runCatching {
        val root = JSONObject(text)
        val data = if (root.has("data") && !root.isNull("data")) root.getJSONObject("data") else root
        val measuredAt = data.firstValue("measured_at", "measuredAt", "created_at", "timestamp")
        val heartRate = data.nullableDouble("heart_rate")?.toInt()
        val spo2 = data.nullableDouble("spo2")
        val bodyTemp = data.nullableDouble("body_temp")
        val userId = data.firstValue("user_id", "userId")?.toLongOrNull()
        val recordId = data.firstValue("id", "health_data_id", "healthDataId")
        val fingerprint = recordId?.let { "id:$it" }
            ?: "at:${measuredAt.orEmpty()}|hr:$heartRate|spo2:$spo2|temp:$bodyTemp"

        RemoteHealthSample(
            payload = HealthServicesPayload(
                measuredAt = measuredAt.orEmpty(),
                heartRate = heartRate,
                steps = null,
                calories = null,
                spo2 = spo2,
                bodyTemp = bodyTemp,
                bloodPressureSystolic = null,
                bloodPressureDiastolic = null,
            ),
            identity = RemoteHealthSampleIdentity(
                fingerprint = fingerprint,
                measuredAtEpochMs = parseHealthSampleTimestamp(measuredAt),
                userId = userId,
            ),
        )
    }.getOrNull()
}

fun parseHikingRecordsResponse(text: String): List<HikingRecord> {
    val array = runCatching { JSONArray(text) }.getOrNull() ?: runCatching {
        val root = JSONObject(text)
        root.optJSONArray("data") ?: root.optJSONArray("rows")
    }.getOrNull() ?: return emptyList()

    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
        HikingRecord(
            id = id,
            status = item.optString("status", ""),
            durationMinutes = item.nullableDouble("duration_minutes"),
            distanceKm = item.nullableDouble("distance_km"),
            startedAt = item.optString("started_at", ""),
        )
    }
}

private fun JSONObject.firstValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    if (has(key) && !isNull(key)) opt(key)?.toString()?.takeIf { it.isNotBlank() } else null
}

private fun JSONObject.nullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
