package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DeviceInfo(val model: String? = null, val osVersion: String? = null, val appVersion: String? = null)
data class RegisterRequest(val registrationCode: String, val publicKey: String, val deviceInfo: DeviceInfo? = null)
data class RegisterResponse(val deviceId: String)
data class ChallengeRequest(val deviceId: String)
data class ChallengeResponse(val challengeId: String, val nonce: String, val expiresIn: Long)
data class TokenRequest(val deviceId: String, val challengeId: String, val signature: String)
data class TokenResponse(val accessToken: String, val tokenType: String, val expiresIn: Long, val serverTime: Long)

/** 端末向けの認証エンドポイント。 */
@RestController
@RequestMapping("/auth/v1")
class AuthController(
    private val registration: RegistrationService,
    private val challenges: ChallengeService,
    private val tokens: TokenService,
    private val props: AuthProperties,
) {
    @PostMapping("/devices/register")
    fun register(
        @RequestBody req: RegisterRequest,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        http: HttpServletRequest,
    ): RegisterResponse {
        checkAppVersion(appVersion)
        requireText("registrationCode", req.registrationCode, 64)
        requireText("publicKey", req.publicKey, 512)
        val id = registration.register(req.registrationCode, req.publicKey, req.deviceInfo?.model, http.remoteAddr)
        return RegisterResponse(id)
    }

    @PostMapping("/device/challenge")
    fun challenge(
        @RequestBody req: ChallengeRequest,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        http: HttpServletRequest,
    ): ChallengeResponse {
        checkAppVersion(appVersion)
        requireText("deviceId", req.deviceId, 64)
        val c = challenges.issue(req.deviceId, http.remoteAddr)
        return ChallengeResponse(c.challengeId, c.nonce, c.expiresInSeconds)
    }

    @PostMapping("/token")
    fun token(
        @RequestBody req: TokenRequest,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        http: HttpServletRequest,
    ): TokenResponse {
        checkAppVersion(appVersion)
        requireText("deviceId", req.deviceId, 64)
        requireText("challengeId", req.challengeId, 64)
        requireText("signature", req.signature, 256)
        val r = tokens.issue(req.deviceId, req.challengeId, req.signature, http.remoteAddr)
        return TokenResponse(r.token.accessToken, "Bearer", r.token.expiresInSeconds, r.serverTimeEpochSeconds)
    }

    /** X-App-Version(整数の versionCode)が最小要求バージョン未満なら 426。 */
    private fun checkAppVersion(header: String?) {
        if (props.minAppVersion <= 0) return
        val version = header?.trim()?.toIntOrNull()
        if (version == null || version < props.minAppVersion) {
            throw ApiException(ErrorCode.APP_VERSION_UNSUPPORTED, "Please update the app (required version >= ${props.minAppVersion})")
        }
    }

    private fun requireText(name: String, value: String, maxLength: Int) {
        if (value.isBlank() || value.length > maxLength) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "$name is missing or invalid")
        }
    }
}
