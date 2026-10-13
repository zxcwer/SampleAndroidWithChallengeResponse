package com.example.sync.auth.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "auth")
data class AuthProperties(
    val issuer: String,
    val audience: String,
    val accessTokenTtl: Duration = Duration.ofMinutes(30),
    val challengeTtl: Duration = Duration.ofSeconds(60),
    val registrationCodeTtl: Duration = Duration.ofMinutes(30),
    val minAppVersion: Int = 0,
    val signing: Signing = Signing(),
    val rateLimit: RateLimit = RateLimit(),
) {
    data class Signing(val keyDir: String = "./signing-keys", val activeKid: String = "")

    data class RateLimit(
        val challengePerDevicePerMinute: Int = 10,
        val tokenPerDevicePerMinute: Int = 10,
        val perIpPerMinute: Int = 60,
        val registerPerIpPerMinute: Int = 10,
    )
}

@ConfigurationProperties(prefix = "admin")
data class AdminProperties(val apiToken: String = "")
