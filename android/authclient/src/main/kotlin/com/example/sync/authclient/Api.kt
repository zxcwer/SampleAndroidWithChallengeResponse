package com.example.sync.authclient

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** サーバー(設計書 2 章)との JSON メッセージ。 */
@Serializable
data class DeviceInfo(val model: String, val osVersion: String, val appVersion: String)

@Serializable
data class RegisterRequest(val registrationCode: String, val publicKey: String, val deviceInfo: DeviceInfo)

@Serializable
data class RegisterResponse(val deviceId: String)

@Serializable
data class ChallengeRequest(val deviceId: String)

@Serializable
data class ChallengeResponse(val challengeId: String, val nonce: String, val expiresIn: Long)

@Serializable
data class TokenRequest(val deviceId: String, val challengeId: String, val signature: String)

@Serializable
data class TokenResponse(val accessToken: String, val tokenType: String, val expiresIn: Long, val serverTime: Long)

@Serializable
data class ErrorResponse(val error: String, val message: String = "")

@Serializable
data class SyncPolicy(val maxOfflineDays: Int, val serverTime: Long)

@Serializable
data class PullResponse(val deviceId: String, val serverTime: Long, val records: List<JsonObject> = emptyList())

/** [operatorId] はオフライン中にローカル認証(PIN/生体)した操作者。 */
@Serializable
data class PushRecord(val id: String, val operatorId: String? = null, val payload: JsonObject = JsonObject(emptyMap()))

@Serializable
data class PushRequest(val records: List<PushRecord>)

@Serializable
data class PushResponse(val accepted: Int, val serverTime: Long)
