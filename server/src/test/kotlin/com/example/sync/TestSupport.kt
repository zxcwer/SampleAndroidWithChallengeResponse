package com.example.sync

import com.example.sync.authclient.DeviceKeyStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** サーバー時刻を進められるテスト用クロック。 */
class MutableClock(@Volatile var now: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advanceSeconds(s: Long) { now = now.plusSeconds(s) }
}

@TestConfiguration
class TestClockConfig {
    @Bean
    @Primary
    fun mutableClock() = MutableClock()
}

/** Android Keystore の代わりに JVM 上のソフトウェア鍵で署名する。DER 署名を返す点は Android と同じ。 */
class SoftwareDeviceKeyStore : DeviceKeyStore {
    var keyPair: KeyPair? = null

    override fun generate(): String {
        keyPair = newKeyPair()
        return Base64.getEncoder().encodeToString(keyPair!!.public.encoded)
    }

    override fun exists() = keyPair != null
    override fun delete() { keyPair = null }
    override fun sign(message: String): String = signWith(keyPair!!, message)

    companion object {
        fun newKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        fun signWith(kp: KeyPair, message: String): String {
            val sig = Signature.getInstance("SHA256withECDSA").run {
                initSign(kp.private)
                update(message.toByteArray(Charsets.UTF_8))
                sign()
            }
            return Base64.getEncoder().encodeToString(sig)
        }
    }
}

class Resp(val status: Int, val json: JsonNode) {
    val error: String? get() = json.get("error")?.asText()
}

/** JDK の HttpClient で JSON を送る。401 の POST でも HttpURLConnection のようなリトライ問題が起きない。 */
class TestHttp(private val baseUrl: String) {
    private val client = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    fun call(method: String, path: String, body: Any? = null, bearer: String? = null, headers: Map<String, String> = emptyMap()): Resp {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(if (body is String) body else mapper.writeValueAsString(body))
        builder.method(method, publisher)
        if (body != null) builder.header("Content-Type", "application/json")
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Resp(res.statusCode(), if (res.body().isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(res.body()))
    }
}

fun tempKeyDir(): String = Files.createTempDirectory("signing-keys").toString()
