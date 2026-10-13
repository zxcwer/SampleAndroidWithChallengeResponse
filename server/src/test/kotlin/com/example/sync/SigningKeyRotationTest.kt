package com.example.sync

import com.example.sync.auth.internal.AccessTokenService
import com.example.sync.auth.internal.AuthProperties
import com.example.sync.auth.internal.SigningKeyRing
import com.example.sync.common.ApiException
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SigningKeyRotationTest {
    private val clock = MutableClock()

    private fun props(dir: String, active: String = "") = AuthProperties(
        issuer = "https://sync-server.local/auth",
        audience = "sync-api",
        signing = AuthProperties.Signing(dir, active),
    )

    @Test
    fun `old tokens stay valid while the old key remains in the key ring, new tokens use the new kid`() {
        val dir = tempKeyDir()
        val ring1 = SigningKeyRing(props(dir))
        val svc1 = AccessTokenService(props(dir), ring1, clock)
        val oldToken = svc1.issue("dev-1").accessToken

        // 新しい鍵を追加して active-kid を切り替える
        val newKey = ECKeyGenerator(Curve.P_256).keyID("zz-new-key").generate()
        Files.writeString(Path.of(dir, "zz-new-key.jwk"), newKey.toJSONString())
        val ring2 = SigningKeyRing(props(dir, "zz-new-key"))
        val svc2 = AccessTokenService(props(dir, "zz-new-key"), ring2, clock)

        assertEquals("zz-new-key", ring2.activeKid)
        assertNotEquals(ring1.activeKid, ring2.activeKid)
        assertEquals("dev-1", svc2.verify(oldToken).deviceId)

        val newToken = svc2.issue("dev-2").accessToken
        assertEquals("zz-new-key", SignedJWT.parse(newToken).header.keyID)
        assertEquals("dev-2", svc2.verify(newToken).deviceId)
    }

    @Test
    fun `after the old key file is removed its tokens are rejected`() {
        val dir = tempKeyDir()
        val oldToken = AccessTokenService(props(dir), SigningKeyRing(props(dir)), clock).issue("dev-1").accessToken

        Files.list(Path.of(dir)).use { it.forEach(Files::delete) }
        val svc = AccessTokenService(props(dir), SigningKeyRing(props(dir)), clock) // 新しい鍵が自動生成される
        assertThrows<ApiException> { svc.verify(oldToken) }
    }

    @Test
    fun `the generated key is persisted so tokens survive a restart`() {
        val dir = tempKeyDir()
        val token = AccessTokenService(props(dir), SigningKeyRing(props(dir)), clock).issue("dev-1").accessToken
        val restarted = AccessTokenService(props(dir), SigningKeyRing(props(dir)), clock)
        assertEquals("dev-1", restarted.verify(token).deviceId)
    }
}
