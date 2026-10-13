package com.example.sync.authclient

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceAuthClientTest {
    private val clock = FakeClock()
    private val keys = SoftwareDeviceKeyStore().also { it.generate() }
    private val ids = MemoryDeviceIdStore("dev-1")

    private fun client(transport: FakeTransport) =
        DeviceAuthClient(ApiHttp("https://sync-server.local/", transport, 7), keys, ids, clock)

    private val challengeOk = json(200, """{"challengeId":"c1","nonce":"n1","expiresIn":60}""")
    private val tokenOk = json(200, """{"accessToken":"jwt","tokenType":"Bearer","expiresIn":1800,"serverTime":1760000000}""")

    @Test
    fun `signs the documented message and sends app version header`() {
        val transport = FakeTransport { if (it.url.endsWith("/challenge")) challengeOk else tokenOk }
        val token = client(transport).authenticate()

        assertEquals("jwt", token.value)
        assertEquals(listOf("https://sync-server.local/auth/v1/device/challenge", "https://sync-server.local/auth/v1/token"), transport.requests.map { it.url })
        assertTrue(transport.requests.all { it.headers["X-App-Version"] == "7" })
        assertEquals("device-auth-v1\ndev-1\nc1\nn1", ChallengeMessage.build("dev-1", "c1", "n1"))
    }

    @Test
    fun `retries automatically on challenge_expired`() {
        var tokenCalls = 0
        val transport = FakeTransport {
            when {
                it.url.endsWith("/challenge") -> challengeOk
                ++tokenCalls < 3 -> error(401, ErrorCodes.CHALLENGE_EXPIRED)
                else -> tokenOk
            }
        }
        assertEquals("jwt", client(transport).authenticate().value)
        assertEquals(3, tokenCalls)
    }

    @Test
    fun `gives up after max attempts of challenge_expired`() {
        val transport = FakeTransport { if (it.url.endsWith("/challenge")) challengeOk else error(401, ErrorCodes.CHALLENGE_EXPIRED) }
        val e = assertFailsWith<DeviceAuthException> { client(transport).authenticate() }
        assertEquals(ErrorCodes.CHALLENGE_EXPIRED, e.code)
        assertEquals(3, transport.requests.count { it.url.endsWith("/challenge") })
    }

    @Test
    fun `does not retry terminal errors`() {
        for (code in listOf(ErrorCodes.DEVICE_REVOKED, ErrorCodes.DEVICE_NOT_REGISTERED, ErrorCodes.APP_VERSION_UNSUPPORTED)) {
            val transport = FakeTransport { error(403, code) }
            val e = assertFailsWith<DeviceAuthException> { client(transport).authenticate() }
            assertTrue(e.isTerminal, code)
            assertEquals(1, transport.requests.size, code)
        }
    }

    @Test
    fun `network failures propagate as IOException`() {
        val transport = FakeTransport { throw IOException("offline") }
        assertFailsWith<IOException> { client(transport).authenticate() }
    }

    @Test
    fun `register stores device id and discards key on failure`() {
        val freshKeys = SoftwareDeviceKeyStore()
        val freshIds = MemoryDeviceIdStore()
        val info = DeviceInfo("Pixel", "14", "1.0")

        val ok = DeviceAuthClient(ApiHttp("https://h", FakeTransport { json(200, """{"deviceId":"d-9"}""") }, 1), freshKeys, freshIds, clock)
        assertEquals("d-9", ok.register(" ABCD-EFGH-JKLM ", info))
        assertEquals("d-9", freshIds.get())
        assertTrue(freshKeys.exists())

        val failKeys = SoftwareDeviceKeyStore()
        val failIds = MemoryDeviceIdStore()
        val bad = DeviceAuthClient(ApiHttp("https://h", FakeTransport { error(403, ErrorCodes.INVALID_REGISTRATION_CODE) }, 1), failKeys, failIds, clock)
        assertFailsWith<DeviceAuthException> { bad.register("X", info) }
        assertFalse(failKeys.exists())
        assertEquals(null, failIds.get())
    }

    @Test
    fun `token usability is based on elapsed time not wall clock`() {
        val token = AccessToken("t", 1800, obtainedAtElapsedMs = 1_000, serverTimeEpochSeconds = 0)
        assertTrue(token.isUsable(1_000 + 1_000_000))
        assertFalse(token.isUsable(1_000 + 1_740_000))
        assertFalse(token.isUsable(500)) // 経過時間が負(再起動など)なら使わない
    }
}
