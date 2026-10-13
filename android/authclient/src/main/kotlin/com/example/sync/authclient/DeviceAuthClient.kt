package com.example.sync.authclient

import java.io.IOException

/** 端末の鍵(Android では Android Keystore)。秘密鍵は端末の外へ出せない。 */
interface DeviceKeyStore {
    /** 鍵ペアを生成し、公開鍵を X.509 SubjectPublicKeyInfo の Base64(NO_WRAP) で返す。既存の鍵は置き換える。 */
    fun generate(): String

    fun exists(): Boolean

    fun delete()

    /** SHA256withECDSA で署名し、DER 形式の署名を Base64(NO_WRAP) で返す。 */
    fun sign(message: String): String
}

/** 端末ID(秘密情報ではないので通常のアプリ内ストレージでよい)。 */
interface DeviceIdStore {
    fun get(): String?
    fun set(deviceId: String)
    fun clear()
}

/** 署名対象文字列。サーバーの ChallengeMessage と一致させること。 */
object ChallengeMessage {
    private const val PREFIX = "device-auth-v1"
    fun build(deviceId: String, challengeId: String, nonce: String): String =
        listOf(PREFIX, deviceId, challengeId, nonce).joinToString("\n")
}

class AccessToken(
    val value: String,
    val expiresInSeconds: Long,
    /** 取得時の SystemClock.elapsedRealtime()。端末の時計ずれの影響を受けないよう経過時間で期限を管理する。 */
    private val obtainedAtElapsedMs: Long,
    val serverTimeEpochSeconds: Long,
) {
    /** 期限の少し前(skew)で使えなくする。 */
    fun isUsable(nowElapsedMs: Long, skewSeconds: Long = 60): Boolean {
        val ageMs = nowElapsedMs - obtainedAtElapsedMs
        return ageMs in 0 until (expiresInSeconds - skewSeconds) * 1000
    }
}

class DeviceAuthClient(
    private val api: ApiHttp,
    private val keyStore: DeviceKeyStore,
    private val deviceIds: DeviceIdStore,
    private val clock: DeviceClock,
    private val maxChallengeAttempts: Int = 3,
) {
    /**
     * 端末登録(キッティング時に 1 回だけ)。鍵ペアを生成して公開鍵を送り、サーバー採番の deviceId を保存する。
     * 失敗した場合は生成した鍵を破棄する。
     */
    @Throws(IOException::class, DeviceAuthException::class)
    fun register(registrationCode: String, deviceInfo: DeviceInfo): String {
        val publicKey = keyStore.generate()
        try {
            val res: RegisterResponse = api.postJson("/auth/v1/devices/register", RegisterRequest(registrationCode.trim(), publicKey, deviceInfo))
            deviceIds.set(res.deviceId)
            return res.deviceId
        } catch (e: Exception) {
            keyStore.delete()
            throw e
        }
    }

    fun isRegistered(): Boolean = deviceIds.get() != null && keyStore.exists()

    /** チャレンジ・レスポンスでアクセストークンを取得する。challenge_expired は自動で再試行する。 */
    @Throws(IOException::class, DeviceAuthException::class)
    fun authenticate(): AccessToken {
        val deviceId = deviceIds.get() ?: throw DeviceAuthException(ErrorCodes.DEVICE_NOT_REGISTERED, 0, "Device is not registered")
        var attempt = 0
        while (true) {
            attempt++
            try {
                return authenticateOnce(deviceId)
            } catch (e: DeviceAuthException) {
                if (e.code != ErrorCodes.CHALLENGE_EXPIRED || attempt >= maxChallengeAttempts) throw e
            }
        }
    }

    private fun authenticateOnce(deviceId: String): AccessToken {
        val challenge: ChallengeResponse = api.postJson("/auth/v1/device/challenge", ChallengeRequest(deviceId))
        val signature = keyStore.sign(ChallengeMessage.build(deviceId, challenge.challengeId, challenge.nonce))
        val res: TokenResponse = api.postJson("/auth/v1/token", TokenRequest(deviceId, challenge.challengeId, signature))
        return AccessToken(res.accessToken, res.expiresIn, clock.elapsedRealtimeMs(), res.serverTime)
    }
}
