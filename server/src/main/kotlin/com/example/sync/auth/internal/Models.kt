package com.example.sync.auth.internal

enum class DeviceStatus(val value: String) {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    LOST("lost");

    companion object {
        fun parse(v: String): DeviceStatus? = entries.firstOrNull { it.value == v }
    }
}

data class Device(
    val deviceId: String,
    val publicKey: String,
    val keyFingerprint: String,
    val status: DeviceStatus,
    val model: String?,
    val registeredAt: Long,
    val lastAuthAt: Long?,
)

data class Challenge(
    val challengeId: String,
    val deviceId: String,
    val nonce: String,
    val expiresAt: Long,
    val usedAt: Long?,
)
