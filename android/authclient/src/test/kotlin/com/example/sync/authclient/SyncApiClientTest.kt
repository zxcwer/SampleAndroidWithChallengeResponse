package com.example.sync.authclient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncApiClientTest {
    private val clock = FakeClock()
    private val keys = SoftwareDeviceKeyStore().also { it.generate() }
    private val revoked = mutableListOf<String>()

    private fun build(handler: (HttpRequest) -> HttpResponse): Pair<SyncApiClient, FakeTransport> {
        val transport = FakeTransport(handler)
        val api = ApiHttp("https://h", transport, 1)
        val auth = DeviceAuthClient(api, keys, MemoryDeviceIdStore("d"), clock)
        return SyncApiClient(api, auth, AccessTokenHolder(clock)) { revoked += it } to transport
    }

    private fun authHandler(tokens: MutableList<String>, onSync: (HttpRequest) -> HttpResponse): (HttpRequest) -> HttpResponse = {
        when {
            it.url.endsWith("/auth/v1/device/challenge") -> json(200, """{"challengeId":"c","nonce":"n","expiresIn":60}""")
            it.url.endsWith("/auth/v1/token") -> {
                val t = "jwt-${tokens.size + 1}"
                tokens += t
                json(200, """{"accessToken":"$t","tokenType":"Bearer","expiresIn":1800,"serverTime":1}""")
            }
            else -> onSync(it)
        }
    }

    @Test
    fun `reuses the in-memory token for consecutive calls`() {
        val tokens = mutableListOf<String>()
        val (client, transport) = build(authHandler(tokens) { json(200, """{"maxOfflineDays":7,"serverTime":5}""") })

        assertEquals(7, client.policy().maxOfflineDays)
        client.policy()

        assertEquals(1, tokens.size)
        assertEquals("Bearer jwt-1", transport.requests.last().headers["Authorization"])
    }

    @Test
    fun `re-authenticates once when the server answers 401`() {
        val tokens = mutableListOf<String>()
        val (client, transport) = build(authHandler(tokens) {
            if (it.headers["Authorization"] == "Bearer jwt-1") error(401, ErrorCodes.INVALID_TOKEN)
            else json(200, """{"deviceId":"d","serverTime":5,"records":[]}""")
        })

        assertEquals("d", client.pull().deviceId)
        assertEquals(2, tokens.size)
        assertEquals("Bearer jwt-2", transport.requests.last().headers["Authorization"])
    }

    @Test
    fun `does not loop forever on persistent 401`() {
        val tokens = mutableListOf<String>()
        val (client, _) = build(authHandler(tokens) { error(401, ErrorCodes.INVALID_TOKEN) })
        assertFailsWith<DeviceAuthException> { client.pull() }
        assertEquals(2, tokens.size)
    }

    @Test
    fun `notifies listener when the device was revoked mid-sync`() {
        val tokens = mutableListOf<String>()
        val (client, _) = build(authHandler(tokens) { error(403, ErrorCodes.DEVICE_REVOKED) })

        val e = assertFailsWith<DeviceAuthException> { client.push(listOf(PushRecord("r1", "op1"))) }
        assertEquals(ErrorCodes.DEVICE_REVOKED, e.code)
        assertEquals(listOf(ErrorCodes.DEVICE_REVOKED), revoked)
    }
}
