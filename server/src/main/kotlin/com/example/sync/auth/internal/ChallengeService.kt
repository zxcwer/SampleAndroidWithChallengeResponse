package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

data class IssuedChallenge(val challengeId: String, val nonce: String, val expiresInSeconds: Long)

@Service
class ChallengeService(
    private val props: AuthProperties,
    private val devices: DeviceRepository,
    private val challenges: ChallengeRepository,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun issue(deviceId: String, ip: String?): IssuedChallenge =
        audit.audited(deviceId, "challenge", ip) {
            rateLimiter.check("challenge:ip:$ip", props.rateLimit.perIpPerMinute)
            rateLimiter.check("challenge:device:$deviceId", props.rateLimit.challengePerDevicePerMinute)

            val device = devices.find(deviceId) ?: throw ApiException(ErrorCode.DEVICE_NOT_REGISTERED, "Device is not registered")
            if (device.status != DeviceStatus.ACTIVE) throw ApiException(ErrorCode.DEVICE_REVOKED, "Device has been revoked")

            val challenge = Challenge(
                challengeId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                nonce = Crypto.randomUrlSafe(32),
                expiresAt = clock.millis() + props.challengeTtl.toMillis(),
                usedAt = null,
            )
            challenges.insert(challenge)
            IssuedChallenge(challenge.challengeId, challenge.nonce, props.challengeTtl.seconds)
        }

    /** 期限切れチャレンジを定期削除する(監査には auth_audit_logs が残る)。 */
    @Scheduled(fixedDelay = 3_600_000)
    fun purgeExpired() {
        challenges.deleteExpiredBefore(clock.millis() - 3_600_000)
    }
}
