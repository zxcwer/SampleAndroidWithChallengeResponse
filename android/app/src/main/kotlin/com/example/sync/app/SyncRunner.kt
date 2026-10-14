package com.example.sync.app

import com.example.sync.authclient.DeviceAuthException
import java.io.IOException

sealed interface SyncResult {
    data object Success : SyncResult
    data object NetworkError : SyncResult
    data class AuthError(val error: DeviceAuthException) : SyncResult
}

/**
 * 同期 1 回分。クレードル接続のトリガー(WorkManager 等)からも呼べるよう UI から切り離している。
 * 最後まで成功したときだけ「最終同期」を記録するため、途中失敗でロックが延びることはない。
 */
class SyncRunner(private val container: AppContainer) {
    private val log = container.logger

    fun run(): SyncResult {
        log.i("sync: start")
        // 失効済みの端末は自動で認証し直さない(再登録されるまで止める)
        if (container.revokedFlag.revoked) {
            log.w("sync: skipped - device was revoked earlier; re-registration required")
            return SyncResult.AuthError(DeviceAuthException("device_revoked", 403, "Device was revoked"))
        }
        return try {
            val api = container.syncApi
            // TODO: ここで業務データの pull / push を行う(api.pull(), api.push(records))
            api.pull()
            val policy = api.policy()
            container.offlineGuard.recordSync(policy.serverTime, policy.maxOfflineDays)
            log.i("sync: success (maxOfflineDays=${policy.maxOfflineDays}, serverTime=${policy.serverTime})")
            SyncResult.Success
        } catch (e: IOException) {
            log.w("sync: network error (${e.javaClass.simpleName})")
            SyncResult.NetworkError
        } catch (e: DeviceAuthException) {
            log.w("sync: auth error code=${e.code}")
            SyncResult.AuthError(e)
        }
    }
}
