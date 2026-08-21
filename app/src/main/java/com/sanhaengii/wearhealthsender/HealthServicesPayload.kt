package com.sanhaengii.wearhealthsender

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class HealthServicesPayload(
    val measuredAt: String,
    val heartRate: Int?,
    val steps: Int?,
    val calories: Double?,
    val spo2: Double?,
    val bodyTemp: Double?,
    val bloodPressureSystolic: Int?,
    val bloodPressureDiastolic: Int?,
) {
    fun toJson(userId: Long): JSONObject = JSONObject()
        .put("user_id", userId)
        .put("measured_at", measuredAt)
        .putNullable("heart_rate", heartRate)
        .putNullable("steps", steps)
        .putNullable("calories", calories)
        .putNullable("spo2", spo2)
        .putNullable("body_temp", bodyTemp)
        .putNullable("blood_pressure_systolic", bloodPressureSystolic)
        .putNullable("blood_pressure_diastolic", bloodPressureDiastolic)

    fun toRequestBody(userId: Long): String = toJson(userId).toString()

    fun toDisplayText(): String = """
        HR: ${heartRate.display("bpm")}
        BP: ${displayBloodPressure()}
        SpO2: ${spo2.display("%")}
        Steps: ${steps.display()}
        Calories: ${calories.display("kcal")}
        Temp: ${bodyTemp.display("C")}
        At: $measuredAt
    """.trimIndent()

    fun mergeWith(update: HealthServicesPayload): HealthServicesPayload = copy(
        measuredAt = update.measuredAt,
        heartRate = update.heartRate ?: heartRate,
        steps = maxNullable(steps, update.steps),
        calories = maxNullable(calories, update.calories),
        spo2 = update.spo2 ?: spo2,
        bodyTemp = update.bodyTemp ?: bodyTemp,
        bloodPressureSystolic = update.bloodPressureSystolic ?: bloodPressureSystolic,
        bloodPressureDiastolic = update.bloodPressureDiastolic ?: bloodPressureDiastolic,
    )

    fun hasCollectedRequiredValues(): Boolean = steps != null && calories != null

    fun missingRequiredFields(): String = listOfNotNull(
        "steps".takeIf { steps == null },
        "calories".takeIf { calories == null },
    ).joinToString()

    private fun displayBloodPressure(): String =
        if (bloodPressureSystolic == null || bloodPressureDiastolic == null) "-"
        else "$bloodPressureSystolic/$bloodPressureDiastolic mmHg"

    companion object {
        fun empty() = HealthServicesPayload(
            measuredAt = nowKstIsoString(),
            heartRate = null,
            steps = null,
            calories = null,
            spo2 = null,
            bodyTemp = null,
            bloodPressureSystolic = null,
            bloodPressureDiastolic = null,
        )
    }
}

fun nowKstIsoString(): String = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
    .truncatedTo(ChronoUnit.SECONDS)
    .toString()

private fun maxNullable(first: Int?, second: Int?): Int? = listOfNotNull(first, second).maxOrNull()
private fun maxNullable(first: Double?, second: Double?): Double? = listOfNotNull(first, second).maxOrNull()
private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun Int?.display(suffix: String = ""): String = this?.let { if (suffix.isBlank()) "$it" else "$it $suffix" } ?: "-"
private fun Double?.display(suffix: String = ""): String = this?.let { if (suffix.isBlank()) "$it" else "$it $suffix" } ?: "-"
fun Double.roundToOneDecimal(): Double = (this * 10.0).roundToInt() / 10.0
