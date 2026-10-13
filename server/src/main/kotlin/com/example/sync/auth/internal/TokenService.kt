package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import org.springframework.stereotype.Service
import java.time.Clock

data class TokenResult(val token: IssuedToken, val serverTimeEpochSeconds: Long)

@Service
class TokenService(
    private val props: AuthProperties,
    private val devices: DeviceRepository,
    private val challenges: ChallengeRepository,
    private val accessTokens: AccessTokenService,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val clock: Clock,
) {
    /**
     * チャレンジ・レスポンスを検証してアクセストークンを発行する。
     * 順番どおりに行い、どこかで失敗したら即座に拒否する。
     */
    fun issue(deviceId: String, challengeId: String, signature: String, ip: String?): TokenResult =
        audit.audited(deviceId, "token", ip) {
            rateLimiter.check("token:ip:$ip", props.rateLimit.perIpPerMinute)
            rateLimiter.check("token:device:$deviceId", props.rateLimit.tokenPerDevicePerMinute)
            val now = clock.millis()

            // 1. challengeId が存在し、未使用で、有効期限内であること
            val challenge = challenges.find(challengeId)
                ?: throw ApiException(ErrorCode.INVALID_CHALLENGE, "Unknown challenge")
            if (challenge.usedAt != null) throw ApiException(ErrorCode.INVALID_CHALLENGE, "Challenge already used")
            if (now >= challenge.expiresAt) throw ApiException(ErrorCode.CHALLENGE_EXPIRED, "Challenge expired")

            // 2. 発行先 deviceId とリクエストの deviceId が一致すること
            if (challenge.deviceId != deviceId) throw ApiException(ErrorCode.INVALID_CHALLENGE, "Challenge was issued to another device")

            // 3. 使用済みにする。検証結果にかかわらずここで無効化してリプレイを防ぐ(原子的)
            if (!challenges.consume(challengeId, now)) throw ApiException(ErrorCode.INVALID_CHALLENGE, "Challenge already used")

            // 4. 登録済みの公開鍵で署名を検証する
            val device = devices.find(deviceId) ?: throw ApiException(ErrorCode.DEVICE_NOT_REGISTERED, "Device is not registered")
            val message = ChallengeMessage.build(deviceId, challengeId, challenge.nonce)
            if (!Crypto.verifyDeviceSignature(Crypto.decodePublicKey(device.publicKey), message, signature)) {
                throw ApiException(ErrorCode.INVALID_SIGNATURE, "Signature verification failed")
            }

            // 5. 端末の状態が「有効」であること
            if (device.status != DeviceStatus.ACTIVE) throw ApiException(ErrorCode.DEVICE_REVOKED, "Device has been revoked")

            devices.touchLastAuth(deviceId, now)
            TokenResult(accessTokens.issue(deviceId), clock.instant().epochSecond)
        }
}
