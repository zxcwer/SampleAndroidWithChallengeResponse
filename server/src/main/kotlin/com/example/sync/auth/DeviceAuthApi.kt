package com.example.sync.auth

import java.time.Instant

/**
 * 認証モジュールが他モジュール(リソース側)へ公開する唯一の窓口。
 * リソース側は認証側のテーブル/内部クラスを直接参照せず、このインターフェース経由で利用する。
 */
interface DeviceAuthApi {
    /** アクセストークンを検証する。不正なら [com.example.sync.common.ApiException] (invalid_token) を投げる。 */
    fun verifyAccessToken(token: String): VerifiedToken

    /** 端末が登録済みかつ「有効」状態なら true。停止/紛失/未登録は false。 */
    fun isDeviceActive(deviceId: String): Boolean
}

data class VerifiedToken(
    val deviceId: String,
    val scopes: Set<String>,
    val jti: String,
    val expiresAt: Instant,
)

object Scopes {
    const val SYNC_READ = "sync:read"
    const val SYNC_WRITE = "sync:write"
}
