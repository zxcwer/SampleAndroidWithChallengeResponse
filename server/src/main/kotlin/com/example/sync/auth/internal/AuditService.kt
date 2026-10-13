package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

/** 認証の成功・失敗をすべて auth_audit_logs に残す。 */
@Service
class AuditService(private val repo: AuditLogRepository, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun success(deviceId: String?, event: String, ip: String?) = write(deviceId, event, "success", null, ip)

    fun failure(deviceId: String?, event: String, errorCode: String, ip: String?) =
        write(deviceId, event, "failure", errorCode, ip)

    /** [block] を実行し、結果(成功/ApiException による失敗)を監査ログに記録する。 */
    fun <T> audited(deviceId: String?, event: String, ip: String?, block: () -> T): T {
        try {
            return block().also { success(deviceId, event, ip) }
        } catch (e: ApiException) {
            failure(deviceId, event, e.error.code, ip)
            throw e
        }
    }

    private fun write(deviceId: String?, event: String, result: String, errorCode: String?, ip: String?) {
        try {
            repo.insert(deviceId, event, result, errorCode, ip, clock.millis())
        } catch (e: Exception) {
            // 監査ログの障害で認証自体を止めない。ただし気付けるよう ERROR で残す。
            log.error("Failed to write audit log event={} result={}", event, result, e)
        }
    }
}
