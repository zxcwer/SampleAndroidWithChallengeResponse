package com.example.sync.authclient

import kotlin.test.Test
import kotlin.test.assertTrue

class LoggingTest {
    private class CapturingLogger : AuthLogger {
        val lines = mutableListOf<String>()
        override fun d(message: String) { lines += message }
        override fun i(message: String) { lines += message }
        override fun w(message: String, throwable: Throwable?) { lines += message }
        override fun e(message: String, throwable: Throwable?) { lines += message }
    }

    @Test
    fun `logs describe the flow but never contain secrets`() {
        val logger = CapturingLogger()
        val keys = SoftwareDeviceKeyStore()
        val ids = MemoryDeviceIdStore()
        val transport = FakeTransport {
            when {
                it.url.endsWith("/devices/register") -> json(200, """{"deviceId":"dev-1"}""")
                it.url.endsWith("/challenge") -> json(200, """{"challengeId":"chal-SECRET-ID","nonce":"NONCE-SECRET","expiresIn":60}""")
                it.url.endsWith("/token") -> json(200, """{"accessToken":"ACCESS-TOKEN-SECRET","tokenType":"Bearer","expiresIn":1800,"serverTime":1}""")
                else -> error(403, ErrorCodes.DEVICE_REVOKED)
            }
        }
        val api = ApiHttp("https://h", transport, 1, logger)
        val auth = DeviceAuthClient(api, keys, ids, FakeClock())
        auth.register("REGI-STRA-TION", DeviceInfo("M", "14", "1.0"))
        val token = auth.authenticate()
        runCatching { SyncApiClient(api, auth, AccessTokenHolder(FakeClock())).pull() }

        val all = logger.lines.joinToString("\n")
        val signature = keys.sign("device-auth-v1\ndev-1\nchal-SECRET-ID\nNONCE-SECRET")
        for (secret in listOf(token.value, "NONCE-SECRET", "REGI-STRA-TION", signature.take(20))) {
            assertTrue(secret !in all, "log leaked: $secret")
        }
        assertTrue("-> POST /auth/v1/token" in all, all)
        assertTrue("<- 200 POST /auth/v1/token" in all, all)
        assertTrue("register: ok deviceId=dev-1" in all, all)
        assertTrue("code=${ErrorCodes.DEVICE_REVOKED}" in all, all)
    }
}
