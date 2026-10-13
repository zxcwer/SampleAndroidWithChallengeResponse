package com.example.sync

import com.example.sync.auth.internal.AccessTokenService
import com.example.sync.auth.internal.AuthProperties
import com.example.sync.auth.internal.SigningKeyRing
import com.example.sync.authclient.AccessTokenHolder
import com.example.sync.authclient.ApiHttp
import com.example.sync.authclient.AuthEventListener
import com.example.sync.authclient.DeviceAuthClient
import com.example.sync.authclient.DeviceAuthException
import com.example.sync.authclient.DeviceClock
import com.example.sync.authclient.DeviceIdStore
import com.example.sync.authclient.DeviceInfo
import com.example.sync.authclient.ErrorCodes
import com.example.sync.authclient.PushRecord
import com.example.sync.authclient.SyncApiClient
import com.example.sync.authclient.UrlConnectionTransport
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.Date
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ADMIN_TOKEN = "test-admin-token"

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig::class)
class DeviceAuthIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { "jdbc:h2:mem:auth-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1" }
            r.add("auth.signing.key-dir") { tempKeyDir() }
            r.add("admin.api-token") { ADMIN_TOKEN }
            // 全テストが同一 IP / 短時間に走るので、ここではレート制限を実質無効にする(制限自体は RateLimitAndVersionTest で検証)
            r.add("auth.rate-limit.register-per-ip-per-minute") { "100000" }
            r.add("auth.rate-limit.per-ip-per-minute") { "100000" }
            r.add("auth.rate-limit.challenge-per-device-per-minute") { "100000" }
            r.add("auth.rate-limit.token-per-device-per-minute") { "100000" }
        }
    }

    @LocalServerPort private var port = 0
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var keyRing: SigningKeyRing
    @Autowired private lateinit var tokens: AccessTokenService
    @Autowired private lateinit var props: AuthProperties

    private val http by lazy { TestHttp("http://localhost:$port") }

    // ---- helpers --------------------------------------------------------------------------

    @BeforeEach
    fun resetClock() { clock.now = java.time.Instant.now() }

    private val deviceClock = object : DeviceClock {
        override fun elapsedRealtimeMs() = System.nanoTime() / 1_000_000
        override fun wallClockMs() = System.currentTimeMillis()
        override fun bootId() = "test"
    }

    private fun admin(method: String, path: String, body: Any? = null, token: String? = ADMIN_TOKEN) =
        http.call(method, path, body, bearer = token)

    private fun newCode(): String = admin("POST", "/admin/v1/registration-codes").let {
        assertEquals(201, it.status)
        it.json["code"].asText()
    }

    private class Device(val id: String, val keys: SoftwareDeviceKeyStore)

    private fun api(appVersion: Int = 1) = ApiHttp("http://localhost:$port", UrlConnectionTransport(), appVersion)

    private fun authClient(keys: SoftwareDeviceKeyStore, id: String?, appVersion: Int = 1) =
        DeviceAuthClient(api(appVersion), keys, object : DeviceIdStore {
            var v = id
            override fun get() = v
            override fun set(deviceId: String) { v = deviceId }
            override fun clear() { v = null }
        }, deviceClock)

    /** 実際の authclient で登録する。 */
    private fun registerDevice(): Device {
        val keys = SoftwareDeviceKeyStore()
        val client = authClient(keys, null)
        val id = client.register(newCode(), DeviceInfo("TestModel", "14", "1.0"))
        return Device(id, keys)
    }

    private fun rawRegister(code: String, publicKey: String) = http.call(
        "POST", "/auth/v1/devices/register",
        mapOf("registrationCode" to code, "publicKey" to publicKey, "deviceInfo" to mapOf("model" to "M")),
    )

    private fun rawChallenge(deviceId: String) = http.call("POST", "/auth/v1/device/challenge", mapOf("deviceId" to deviceId))

    private fun rawToken(deviceId: String, challengeId: String, signature: String) =
        http.call("POST", "/auth/v1/token", mapOf("deviceId" to deviceId, "challengeId" to challengeId, "signature" to signature))

    private fun message(deviceId: String, challengeId: String, nonce: String) =
        "device-auth-v1\n$deviceId\n$challengeId\n$nonce"

    private fun accessToken(d: Device): String = authClient(d.keys, d.id).authenticate().value

    private fun setStatus(deviceId: String, status: String) =
        admin("PUT", "/admin/v1/devices/$deviceId/status", mapOf("status" to status))

    // ---- 登録 ------------------------------------------------------------------------------

    @Test
    fun `full flow - register, authenticate, call sync APIs with the real client`() {
        val d = registerDevice()
        val auth = authClient(d.keys, d.id)
        val sync = SyncApiClient(api(), auth, AccessTokenHolder(deviceClock))

        assertEquals(7, sync.policy().maxOfflineDays)
        assertEquals(d.id, sync.pull().deviceId)
        assertEquals(2, sync.push(listOf(PushRecord("r1", "op1"), PushRecord("r2"))).accepted)
    }

    @Test
    fun `registration code is single use`() {
        val code = newCode()
        val first = SoftwareDeviceKeyStore().generate()
        assertEquals(200, rawRegister(code, first).status)

        val second = rawRegister(code, SoftwareDeviceKeyStore().generate())
        assertEquals(403, second.status)
        assertEquals("invalid_registration_code", second.error)
    }

    @Test
    fun `registration code expires after 30 minutes`() {
        val code = newCode()
        clock.advanceSeconds(30 * 60 + 1)
        val res = rawRegister(code, SoftwareDeviceKeyStore().generate())
        assertEquals("invalid_registration_code", res.error)
    }

    @Test
    fun `registration code is accepted regardless of case and dashes but unknown codes are rejected`() {
        val code = newCode()
        assertEquals(200, rawRegister(code.lowercase().replace("-", " "), SoftwareDeviceKeyStore().generate()).status)
        assertEquals("invalid_registration_code", rawRegister("AAAA-BBBB-CCCC", SoftwareDeviceKeyStore().generate()).error)
    }

    @Test
    fun `registration codes are stored hashed`() {
        val code = newCode()
        val hashes = jdbc.queryForList("SELECT code_hash FROM registration_codes", String::class.java)
        assertTrue(hashes.none { it.contains(code.replace("-", "")) })
        assertTrue(hashes.all { it.length == 64 })
    }

    @Test
    fun `invalid public keys are rejected without burning the registration code`() {
        val code = newCode()
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val p384 = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        for (bad in listOf("not-base64!!", "AAAA", java.util.Base64.getEncoder().encodeToString(rsa.public.encoded), java.util.Base64.getEncoder().encodeToString(p384.public.encoded))) {
            val res = rawRegister(code, bad)
            assertEquals(400, res.status, bad)
            assertEquals("invalid_request", res.error)
        }
        assertEquals(200, rawRegister(code, SoftwareDeviceKeyStore().generate()).status)
    }

    @Test
    fun `the same public key cannot be registered twice and the code is kept`() {
        val key = SoftwareDeviceKeyStore().generate()
        assertEquals(200, rawRegister(newCode(), key).status)

        val code2 = newCode()
        assertEquals(400, rawRegister(code2, key).status)
        assertEquals(200, rawRegister(code2, SoftwareDeviceKeyStore().generate()).status)
    }

    @Test
    fun `malformed requests return invalid_request`() {
        assertEquals("invalid_request", http.call("POST", "/auth/v1/device/challenge", "{}").error)
        assertEquals("invalid_request", http.call("POST", "/auth/v1/token", mapOf("deviceId" to "x")).error)
        assertEquals("invalid_request", http.call("POST", "/auth/v1/devices/register", "not json").error)
        assertEquals("invalid_request", rawChallenge(" ").error)
    }

    // ---- チャレンジ・レスポンス --------------------------------------------------------------

    @Test
    fun `challenge for unknown device is device_not_registered`() {
        val res = rawChallenge(UUID.randomUUID().toString())
        assertEquals(403, res.status)
        assertEquals("device_not_registered", res.error)
    }

    @Test
    fun `challenge replay is rejected`() {
        val d = registerDevice()
        val c = rawChallenge(d.id).json
        val sig = d.keys.sign(message(d.id, c["challengeId"].asText(), c["nonce"].asText()))

        assertEquals(60, c["expiresIn"].asInt())
        assertEquals(200, rawToken(d.id, c["challengeId"].asText(), sig).status)
        val replay = rawToken(d.id, c["challengeId"].asText(), sig)
        assertEquals(401, replay.status)
        assertEquals("invalid_challenge", replay.error)
    }

    @Test
    fun `challenge expires after 60 seconds`() {
        val d = registerDevice()
        val c = rawChallenge(d.id).json
        val sig = d.keys.sign(message(d.id, c["challengeId"].asText(), c["nonce"].asText()))
        clock.advanceSeconds(61)
        val res = rawToken(d.id, c["challengeId"].asText(), sig)
        assertEquals(401, res.status)
        assertEquals("challenge_expired", res.error)
    }

    @Test
    fun `unknown challenge id is invalid_challenge`() {
        val d = registerDevice()
        assertEquals("invalid_challenge", rawToken(d.id, UUID.randomUUID().toString(), "AAAA").error)
    }

    @Test
    fun `a wrong signature is rejected and still burns the challenge`() {
        val d = registerDevice()
        val c = rawChallenge(d.id).json
        val id = c["challengeId"].asText()
        val msg = message(d.id, id, c["nonce"].asText())

        val attacker = SoftwareDeviceKeyStore.newKeyPair()
        val bad = rawToken(d.id, id, SoftwareDeviceKeyStore.signWith(attacker, msg))
        assertEquals(401, bad.status)
        assertEquals("invalid_signature", bad.error)

        // 正しい署名でも、同じチャレンジはもう使えない
        assertEquals("invalid_challenge", rawToken(d.id, id, d.keys.sign(msg)).error)
    }

    @Test
    fun `garbage signature is invalid_signature`() {
        val d = registerDevice()
        val c = rawChallenge(d.id).json
        assertEquals("invalid_signature", rawToken(d.id, c["challengeId"].asText(), "%%%not-base64").error)
        val c2 = rawChallenge(d.id).json
        assertEquals("invalid_signature", rawToken(d.id, c2["challengeId"].asText(), "AAAA").error)
    }

    @Test
    fun `signature must cover purpose, device id, challenge id and nonce`() {
        val d = registerDevice()
        val c = rawChallenge(d.id).json
        val id = c["challengeId"].asText()
        val nonce = c["nonce"].asText()
        // nonce だけに署名(用途・端末ID なし)したものは受け付けない
        assertEquals("invalid_signature", rawToken(d.id, id, d.keys.sign(nonce)).error)

        val c2 = rawChallenge(d.id).json
        val id2 = c2["challengeId"].asText()
        assertEquals("invalid_signature", rawToken(d.id, id2, d.keys.sign("device-auth-v2\n${d.id}\n$id2\n${c2["nonce"].asText()}")).error)
    }

    @Test
    fun `a challenge issued to another device cannot be used and is not consumed`() {
        val a = registerDevice()
        val b = registerDevice()
        val c = rawChallenge(a.id).json
        val id = c["challengeId"].asText()
        val nonce = c["nonce"].asText()

        val stolen = rawToken(b.id, id, b.keys.sign(message(b.id, id, nonce)))
        assertEquals("invalid_challenge", stolen.error)

        // 本来の端末は使える
        assertEquals(200, rawToken(a.id, id, a.keys.sign(message(a.id, id, nonce))).status)
    }

    @Test
    fun `concurrent attempts with the same challenge succeed only once`() {
        val d = registerDevice()
        val c = rawChallenge(d.id).json
        val id = c["challengeId"].asText()
        val sig = d.keys.sign(message(d.id, id, c["nonce"].asText()))

        val pool = Executors.newFixedThreadPool(6)
        val results = pool.invokeAll(List(6) { Callable { rawToken(d.id, id, sig).status } }).map { it.get() }
        pool.shutdown()
        assertEquals(1, results.count { it == 200 }, results.toString())
    }

    // ---- トークン ---------------------------------------------------------------------------

    @Test
    fun `access token carries the documented claims and is signed with ES256 and a kid`() {
        val d = registerDevice()
        val jwt = SignedJWT.parse(accessToken(d))

        assertEquals(JWSAlgorithm.ES256, jwt.header.algorithm)
        assertEquals(keyRing.activeKid, jwt.header.keyID)
        val c = jwt.jwtClaimsSet
        assertEquals(props.issuer, c.issuer)
        assertEquals(listOf("sync-api"), c.audience)
        assertEquals("device:${d.id}", c.subject)
        assertEquals(d.id, c.getStringClaim("device_id"))
        assertEquals("sync:read sync:write", c.getStringClaim("scope"))
        assertEquals(1800, (c.expirationTime.time - c.issueTime.time) / 1000)
        assertNotNull(c.jwtid)
    }

    @Test
    fun `token response has bearer type, expiry and server time`() {
        val d = registerDevice()
        val c = rawChallenge(d.id).json
        val id = c["challengeId"].asText()
        val res = rawToken(d.id, id, d.keys.sign(message(d.id, id, c["nonce"].asText())))
        assertEquals("Bearer", res.json["tokenType"].asText())
        assertEquals(1800, res.json["expiresIn"].asInt())
        assertEquals(clock.instant().epochSecond, res.json["serverTime"].asLong())
    }

    // ---- リソース側のトークン検証 ----------------------------------------------------------

    @Test
    fun `sync APIs reject missing, malformed and unsigned tokens`() {
        assertEquals("invalid_token", http.call("GET", "/sync/v1/pull").error)
        assertEquals(401, http.call("GET", "/sync/v1/pull", bearer = "garbage").status)

        val claims = JWTClaimsSet.Builder().issuer(props.issuer).audience(props.audience)
            .claim("device_id", "x").claim("scope", "sync:read")
            .expirationTime(Date.from(clock.instant().plusSeconds(600))).build()
        // alg=none
        assertEquals(401, http.call("GET", "/sync/v1/pull", bearer = PlainJWT(claims).serialize()).status)
        // HS256 へのすり替え
        val hs = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims).apply { sign(MACSigner(ByteArray(32) { 1 })) }
        assertEquals(401, http.call("GET", "/sync/v1/pull", bearer = hs.serialize()).status)
        // 別の鍵で署名(kid は本物)
        val foreign = ECKeyGenerator(Curve.P_256).generate()
        val forged = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyRing.activeKid).build(), claims).apply { sign(ECDSASigner(foreign)) }
        assertEquals(401, http.call("GET", "/sync/v1/pull", bearer = forged.serialize()).status)
    }

    private fun forgeToken(
        deviceId: String,
        issuer: String = props.issuer,
        audience: String = props.audience,
        scope: String = "sync:read sync:write",
        ttlSeconds: Long = 600,
    ): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("device:$deviceId")
            .claim("device_id", deviceId).claim("scope", scope)
            .issueTime(Date.from(clock.instant()))
            .expirationTime(Date.from(clock.instant().plusSeconds(ttlSeconds))).jwtID(UUID.randomUUID().toString()).build()
        val key = keyRing.active()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build(), claims)
            .apply { sign(ECDSASigner(key)) }.serialize()
    }

    @Test
    fun `sync APIs check issuer, audience and expiry`() {
        val d = registerDevice()
        assertEquals(200, http.call("GET", "/sync/v1/pull", bearer = forgeToken(d.id)).status)
        assertEquals(401, http.call("GET", "/sync/v1/pull", bearer = forgeToken(d.id, issuer = "https://evil")).status)
        assertEquals(401, http.call("GET", "/sync/v1/pull", bearer = forgeToken(d.id, audience = "other-api")).status)
        assertEquals(401, http.call("GET", "/sync/v1/pull", bearer = forgeToken(d.id, ttlSeconds = -1)).status)
    }

    @Test
    fun `an access token expires by server time`() {
        val d = registerDevice()
        val token = accessToken(d)
        assertEquals(200, http.call("GET", "/sync/v1/pull", bearer = token).status)
        clock.advanceSeconds(30 * 60 + 1)
        val res = http.call("GET", "/sync/v1/pull", bearer = token)
        assertEquals(401, res.status)
        assertEquals("invalid_token", res.error)
    }

    @Test
    fun `scopes are enforced per endpoint`() {
        val d = registerDevice()
        val readOnly = forgeToken(d.id, scope = "sync:read")
        val writeOnly = forgeToken(d.id, scope = "sync:write")
        val body = mapOf("records" to emptyList<Any>())

        assertEquals(200, http.call("GET", "/sync/v1/pull", bearer = readOnly).status)
        assertEquals(403, http.call("POST", "/sync/v1/push", body, bearer = readOnly).status)
        assertEquals("insufficient_scope", http.call("POST", "/sync/v1/push", body, bearer = readOnly).error)
        assertEquals(200, http.call("POST", "/sync/v1/push", body, bearer = writeOnly).status)
        assertEquals("insufficient_scope", http.call("GET", "/sync/v1/policy", bearer = writeOnly).error)
    }

    @Test
    fun `a token for an unregistered device is refused`() {
        val res = http.call("GET", "/sync/v1/pull", bearer = forgeToken(UUID.randomUUID().toString()))
        assertEquals(403, res.status)
        assertEquals("device_revoked", res.error)
    }

    // ---- 失効・紛失 -------------------------------------------------------------------------

    @Test
    fun `suspending or losing a device blocks new tokens and already issued tokens immediately`() {
        for (status in listOf("suspended", "lost")) {
            val d = registerDevice()
            val auth = authClient(d.keys, d.id)
            val token = auth.authenticate().value
            assertEquals(200, http.call("GET", "/sync/v1/pull", bearer = token).status)

            assertEquals(200, setStatus(d.id, status).status)

            val used = http.call("GET", "/sync/v1/pull", bearer = token)
            assertEquals(403, used.status, status)
            assertEquals("device_revoked", used.error)
            assertEquals("device_revoked", rawChallenge(d.id).error)

            val e = assertThrows<DeviceAuthException> { auth.authenticate() }
            assertEquals(ErrorCodes.DEVICE_REVOKED, e.code)
            assertTrue(e.isTerminal)

            // 復帰させれば再び使える
            assertEquals(200, setStatus(d.id, "active").status)
            assertEquals(200, http.call("GET", "/sync/v1/pull", bearer = token).status)
        }
    }

    @Test
    fun `a device suspended after the challenge is refused at the token step`() {
        val d = registerDevice()
        val c = rawChallenge(d.id).json
        val id = c["challengeId"].asText()
        setStatus(d.id, "suspended")
        val res = rawToken(d.id, id, d.keys.sign(message(d.id, id, c["nonce"].asText())))
        assertEquals(403, res.status)
        assertEquals("device_revoked", res.error)
    }

    @Test
    fun `the sync client reports revocation that happens mid-sync`() {
        val d = registerDevice()
        val revoked = mutableListOf<String>()
        val sync = SyncApiClient(api(), authClient(d.keys, d.id), AccessTokenHolder(deviceClock), AuthEventListener { revoked += it })
        sync.pull()

        setStatus(d.id, "lost")
        val e = assertThrows<DeviceAuthException> { sync.pull() }
        assertEquals(ErrorCodes.DEVICE_REVOKED, e.code)
        assertEquals(listOf(ErrorCodes.DEVICE_REVOKED), revoked)
    }

    @Test
    fun `the sync client recovers from a 401 by authenticating again`() {
        val d = registerDevice()
        val holder = AccessTokenHolder(deviceClock)
        val sync = SyncApiClient(api(), authClient(d.keys, d.id), holder)
        sync.pull()
        // サーバー側の時刻だけ進み、端末側の経過時間ではまだ有効 → 401 → 自動で再認証
        clock.advanceSeconds(30 * 60 + 1)
        assertEquals(d.id, sync.pull().deviceId)
    }

    // ---- 管理 API・監査ログ -----------------------------------------------------------------

    @Test
    fun `admin API requires the admin token`() {
        assertEquals(401, admin("POST", "/admin/v1/registration-codes", token = null).status)
        assertEquals(401, admin("GET", "/admin/v1/devices", token = "wrong").status)
        assertEquals(401, http.call("GET", "/admin/v1/devices", bearer = accessToken(registerDevice())).status)
    }

    @Test
    fun `admin can list devices and validates status changes`() {
        val d = registerDevice()
        val list = admin("GET", "/admin/v1/devices").json
        val mine = list.first { it["deviceId"].asText() == d.id }
        assertEquals("active", mine["status"].asText())
        assertEquals("TestModel", mine["model"].asText())
        assertEquals(64, mine["keyFingerprint"].asText().length)
        assertFalse(mine.has("publicKey"))

        assertEquals("invalid_request", setStatus(d.id, "banana").error)
        assertEquals("device_not_registered", setStatus(UUID.randomUUID().toString(), "lost").error)
    }

    @Test
    fun `last_auth_at is updated and every outcome lands in the audit log`() {
        val d = registerDevice()
        assertNull(jdbc.queryForObject("SELECT last_auth_at FROM devices WHERE device_id = ?", Long::class.javaObjectType, d.id))
        accessToken(d)
        assertNotNull(jdbc.queryForObject("SELECT last_auth_at FROM devices WHERE device_id = ?", Long::class.javaObjectType, d.id))

        val c = rawChallenge(d.id).json
        rawToken(d.id, c["challengeId"].asText(), "AAAA")

        val rows = jdbc.queryForList("SELECT event, result, error_code, ip FROM auth_audit_logs WHERE device_id = ?", d.id)
        fun has(event: String, result: String, error: String?) =
            rows.any { it["EVENT"] == event && it["RESULT"] == result && it["ERROR_CODE"] == error && it["IP"] != null }
        assertTrue(has("challenge", "success", null), rows.toString())
        assertTrue(has("token", "success", null), rows.toString())
        assertTrue(has("token", "failure", "invalid_signature"), rows.toString())
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM auth_audit_logs WHERE event = 'register' AND result = 'success'", Int::class.java)!! > 0)
    }
}
