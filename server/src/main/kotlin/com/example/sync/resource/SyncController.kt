package com.example.sync.resource

import com.example.sync.auth.Scopes
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

data class SyncPolicy(val maxOfflineDays: Int, val serverTime: Long)
data class PullResponse(val deviceId: String, val serverTime: Long, val records: List<Map<String, Any?>>)

/** operatorId: オフライン中にローカル認証(PIN/生体)した操作者。サーバー側で許可判定を行う拡張ポイント。 */
data class PushRecord(val id: String, val operatorId: String? = null, val payload: Map<String, Any?> = emptyMap())
data class PushRequest(val records: List<PushRecord> = emptyList())
data class PushResponse(val accepted: Int, val serverTime: Long)

/**
 * 同期 API。業務データの保存/配信は本サンプルの対象外(プレースホルダ)で、
 * 認証・認可(トークン検証、scope、端末状態)の動作確認に使う。
 */
@RestController
@RequestMapping("/sync/v1")
class SyncController(
    private val clock: Clock,
    @Value("\${sync.max-offline-days}") private val maxOfflineDays: Int,
) {
    @GetMapping("/policy")
    @RequiredScope(Scopes.SYNC_READ)
    fun policy() = SyncPolicy(maxOfflineDays, clock.instant().epochSecond)

    @GetMapping("/pull")
    @RequiredScope(Scopes.SYNC_READ)
    fun pull(http: HttpServletRequest) =
        PullResponse(http.verifiedToken().deviceId, clock.instant().epochSecond, emptyList())

    @PostMapping("/push")
    @RequiredScope(Scopes.SYNC_WRITE)
    fun push(@RequestBody req: PushRequest) = PushResponse(req.records.size, clock.instant().epochSecond)
}
