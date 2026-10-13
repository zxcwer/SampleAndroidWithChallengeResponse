package com.example.sync.auth.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class DeviceRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { rs, _ ->
        Device(
            deviceId = rs.getString("device_id"),
            publicKey = rs.getString("public_key"),
            keyFingerprint = rs.getString("key_fingerprint"),
            // 不明な値は最も安全側(停止扱い)に倒す
            status = DeviceStatus.parse(rs.getString("status")) ?: DeviceStatus.SUSPENDED,
            model = rs.getString("model"),
            registeredAt = rs.getLong("registered_at"),
            lastAuthAt = rs.getLong("last_auth_at").takeUnless { rs.wasNull() },
        )
    }

    fun insert(d: Device) {
        jdbc.update(
            "INSERT INTO devices (device_id, public_key, key_fingerprint, status, model, registered_at) VALUES (?,?,?,?,?,?)",
            d.deviceId, d.publicKey, d.keyFingerprint, d.status.value, d.model, d.registeredAt,
        )
    }

    fun find(deviceId: String): Device? =
        jdbc.query("SELECT * FROM devices WHERE device_id = ?", mapper, deviceId).firstOrNull()

    fun findAll(): List<Device> = jdbc.query("SELECT * FROM devices ORDER BY registered_at", mapper)

    fun updateStatus(deviceId: String, status: DeviceStatus): Boolean =
        jdbc.update("UPDATE devices SET status = ? WHERE device_id = ?", status.value, deviceId) > 0

    fun touchLastAuth(deviceId: String, at: Long) {
        jdbc.update("UPDATE devices SET last_auth_at = ? WHERE device_id = ?", at, deviceId)
    }
}

@Repository
class RegistrationCodeRepository(private val jdbc: JdbcTemplate) {
    fun insert(codeHash: String, expiresAt: Long, createdBy: String) {
        jdbc.update(
            "INSERT INTO registration_codes (code_hash, expires_at, created_by) VALUES (?,?,?)",
            codeHash, expiresAt, createdBy,
        )
    }

    /** 未使用かつ期限内のコードだけを原子的に使用済みにする。成功したら true。 */
    fun consume(codeHash: String, now: Long): Boolean =
        jdbc.update(
            "UPDATE registration_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
            now, codeHash, now,
        ) > 0
}

@Repository
class ChallengeRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { rs, _ ->
        Challenge(
            challengeId = rs.getString("challenge_id"),
            deviceId = rs.getString("device_id"),
            nonce = rs.getString("nonce"),
            expiresAt = rs.getLong("expires_at"),
            usedAt = rs.getLong("used_at").takeUnless { rs.wasNull() },
        )
    }

    fun insert(c: Challenge) {
        jdbc.update(
            "INSERT INTO device_challenges (challenge_id, device_id, nonce, expires_at) VALUES (?,?,?,?)",
            c.challengeId, c.deviceId, c.nonce, c.expiresAt,
        )
    }

    fun find(challengeId: String): Challenge? =
        jdbc.query("SELECT * FROM device_challenges WHERE challenge_id = ?", mapper, challengeId).firstOrNull()

    /** 未使用の場合だけ使用済みにする。並行リクエストでも成功するのは 1 つだけ。 */
    fun consume(challengeId: String, now: Long): Boolean =
        jdbc.update(
            "UPDATE device_challenges SET used_at = ? WHERE challenge_id = ? AND used_at IS NULL",
            now, challengeId,
        ) > 0

    fun deleteExpiredBefore(threshold: Long): Int =
        jdbc.update("DELETE FROM device_challenges WHERE expires_at < ?", threshold)
}

@Repository
class AuditLogRepository(private val jdbc: JdbcTemplate) {
    fun insert(deviceId: String?, event: String, result: String, errorCode: String?, ip: String?, at: Long) {
        jdbc.update(
            "INSERT INTO auth_audit_logs (device_id, event, result, error_code, ip, created_at) VALUES (?,?,?,?,?,?)",
            deviceId, event, result, errorCode, ip, at,
        )
    }
}
