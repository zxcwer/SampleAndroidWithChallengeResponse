package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class IssuedRegistrationCode(val code: String, val expiresAt: Instant)

@Service
class RegistrationService(
    private val props: AuthProperties,
    private val devices: DeviceRepository,
    private val codes: RegistrationCodeRepository,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(txManager)
    private val random = SecureRandom()

    /** 管理画面から呼ばれる。ワンタイム・短時間有効のコードを発行し、DB にはハッシュだけ保存する。 */
    fun issueCode(createdBy: String): IssuedRegistrationCode {
        val raw = (1..CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        val expiresAt = clock.instant().plus(props.registrationCodeTtl)
        codes.insert(hash(raw), expiresAt.toEpochMilli(), createdBy)
        audit.success(null, "registration_code_issued", null)
        return IssuedRegistrationCode(raw.chunked(4).joinToString("-"), expiresAt)
    }

    fun register(registrationCode: String, publicKey: String, model: String?, ip: String?): String =
        audit.audited(null, "register", ip) {
            rateLimiter.check("register:ip:$ip", props.rateLimit.registerPerIpPerMinute)

            // コードを消費する前に鍵を検証する(不正な鍵でコードを無駄に使わせない)
            val ecKey = Crypto.parseDevicePublicKey(publicKey)
            val normalized = normalize(registrationCode)
            if (normalized.length != CODE_LENGTH) {
                throw ApiException(ErrorCode.INVALID_REGISTRATION_CODE, "Invalid or expired registration code")
            }

            val now = clock.millis()
            val deviceId = UUID.randomUUID().toString()
            tx.executeWithoutResult {
                if (!codes.consume(hash(normalized), now)) {
                    throw ApiException(ErrorCode.INVALID_REGISTRATION_CODE, "Invalid or expired registration code")
                }
                try {
                    devices.insert(
                        Device(
                            deviceId = deviceId,
                            publicKey = publicKey,
                            keyFingerprint = Crypto.sha256Hex(ecKey.encoded),
                            status = DeviceStatus.ACTIVE,
                            model = model?.take(128),
                            registeredAt = now,
                            lastAuthAt = null,
                        ),
                    )
                } catch (e: DuplicateKeyException) {
                    // ロールバックされるのでコードは消費されない
                    throw ApiException(ErrorCode.INVALID_REQUEST, "This public key is already registered")
                }
            }
            deviceId
        }

    private fun normalize(code: String) = code.uppercase().filter { it in CODE_ALPHABET }
    private fun hash(normalized: String) = Crypto.sha256Hex(normalized)

    companion object {
        // 0/O, 1/I を除いた 32 文字。12 文字 = 60bit。
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val CODE_LENGTH = 12
    }
}
