package com.example.sync

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig::class)
class RateLimitAndVersionTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { "jdbc:h2:mem:rl-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1" }
            r.add("auth.signing.key-dir") { tempKeyDir() }
            r.add("admin.api-token") { "t" }
            r.add("auth.min-app-version") { "5" }
            r.add("auth.rate-limit.challenge-per-device-per-minute") { "3" }
            r.add("auth.rate-limit.register-per-ip-per-minute") { "4" }
        }
    }

    @LocalServerPort private var port = 0
    private val http by lazy { TestHttp("http://localhost:$port") }
    private val v5 = mapOf("X-App-Version" to "5")

    @Test
    fun `old or missing app version gets 426`() {
        val body = mapOf("deviceId" to UUID.randomUUID().toString())
        for (headers in listOf(mapOf("X-App-Version" to "4"), mapOf("X-App-Version" to "abc"), emptyMap())) {
            val res = http.call("POST", "/auth/v1/device/challenge", body, headers = headers)
            assertEquals(426, res.status, headers.toString())
            assertEquals("app_version_unsupported", res.error)
        }
        // 十分新しければ通常の処理(ここでは未登録端末)に進む
        assertEquals("device_not_registered", http.call("POST", "/auth/v1/device/challenge", body, headers = v5).error)
    }

    @Test
    fun `challenge endpoint is rate limited per device`() {
        val body = mapOf("deviceId" to UUID.randomUUID().toString())
        // 時刻の分境界をまたぐと窓がリセットされるので、またがない範囲で検証する
        repeat(3) { assertEquals(403, http.call("POST", "/auth/v1/device/challenge", body, headers = v5).status) }
        val limited = http.call("POST", "/auth/v1/device/challenge", body, headers = v5)
        assertEquals(429, limited.status)
        assertEquals("rate_limited", limited.error)
        // 別の端末IDは影響を受けない
        assertEquals(403, http.call("POST", "/auth/v1/device/challenge", mapOf("deviceId" to UUID.randomUUID().toString()), headers = v5).status)
    }

    @Test
    fun `registration is rate limited per ip`() {
        val body = mapOf("registrationCode" to "AAAA-BBBB-CCCC", "publicKey" to SoftwareDeviceKeyStore().generate())
        val statuses = (1..6).map { http.call("POST", "/auth/v1/devices/register", body, headers = v5).status }
        assertEquals(listOf(403, 403, 403, 403, 429, 429), statuses)
    }
}
