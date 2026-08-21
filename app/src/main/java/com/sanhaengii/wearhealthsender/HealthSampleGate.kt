package com.sanhaengii.wearhealthsender

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class RemoteHealthSampleIdentity(
    val fingerprint: String,
    val measuredAtEpochMs: Long?,
    val userId: Long?,
)

enum class HealthSampleRejection {
    DUPLICATE,
    MISSING_TIMESTAMP,
    STALE,
    FUTURE_TIMESTAMP,
    USER_MISMATCH,
}

sealed interface HealthSampleDecision {
    data object Accepted : HealthSampleDecision
    data class Rejected(val reason: HealthSampleRejection) : HealthSampleDecision
}

/**
 * /health/data/latest 응답이 현재 사용자의 새 데이터인지 판별합니다.
 * 안전을 위해 측정 시각이 없거나 허용 범위를 벗어난 데이터는 실패 폐쇄 방식으로 거절합니다.
 */
class HealthSampleGate(
    private val maxAgeMillis: Long,
    private val futureToleranceMillis: Long,
    private val maxRememberedFingerprints: Int = 128,
) {
    private val seenFingerprints = LinkedHashSet<String>()

    @Synchronized
    fun evaluate(
        sample: RemoteHealthSampleIdentity,
        expectedUserId: Long?,
        nowEpochMs: Long,
    ): HealthSampleDecision {
        if (expectedUserId != null && sample.userId != null && sample.userId != expectedUserId) {
            return HealthSampleDecision.Rejected(HealthSampleRejection.USER_MISMATCH)
        }

        val measuredAt = sample.measuredAtEpochMs
            ?: return HealthSampleDecision.Rejected(HealthSampleRejection.MISSING_TIMESTAMP)
        if (measuredAt > nowEpochMs + futureToleranceMillis) {
            return HealthSampleDecision.Rejected(HealthSampleRejection.FUTURE_TIMESTAMP)
        }
        if (nowEpochMs - measuredAt > maxAgeMillis) {
            return HealthSampleDecision.Rejected(HealthSampleRejection.STALE)
        }
        if (sample.fingerprint in seenFingerprints) {
            return HealthSampleDecision.Rejected(HealthSampleRejection.DUPLICATE)
        }

        seenFingerprints += sample.fingerprint
        while (seenFingerprints.size > maxRememberedFingerprints) {
            val oldest = seenFingerprints.firstOrNull() ?: break
            seenFingerprints.remove(oldest)
        }
        return HealthSampleDecision.Accepted
    }

    /** 상태 머신이 다른 신고를 처리 중이면 다음 폴링에서 이 샘플을 다시 평가할 수 있게 한다. */
    @Synchronized
    fun release(fingerprint: String) {
        seenFingerprints.remove(fingerprint)
    }
}

fun parseHealthSampleTimestamp(value: String?): Long? {
    val timestamp = value?.trim().orEmpty()
    if (timestamp.isBlank()) return null

    return sequenceOf(
        { Instant.parse(timestamp).toEpochMilli() },
        { OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() },
        { ZonedDateTime.parse(timestamp).toInstant().toEpochMilli() },
        {
            LocalDateTime.parse(timestamp)
                .atZone(ZoneId.of("Asia/Seoul"))
                .toInstant()
                .toEpochMilli()
        },
    ).firstNotNullOfOrNull { parser -> runCatching(parser).getOrNull() }
}
