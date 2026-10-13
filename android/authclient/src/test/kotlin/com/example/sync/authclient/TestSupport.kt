package com.example.sync.authclient

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** JVM 上で Android Keystore の代わりに使うソフトウェア鍵。DER 署名を返す点は Android と同じ。 */
class SoftwareDeviceKeyStore : DeviceKeyStore {
    private var keyPair: KeyPair? = null
    var deleteCount = 0

    override fun generate(): String {
        keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return Base64.getEncoder().encodeToString(keyPair!!.public.encoded)
    }

    override fun exists() = keyPair != null
    override fun delete() { keyPair = null; deleteCount++ }

    override fun sign(message: String): String {
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair!!.private)
            update(message.toByteArray(Charsets.UTF_8))
            sign()
        }
        return Base64.getEncoder().encodeToString(sig)
    }
}

class MemoryDeviceIdStore(private var id: String? = null) : DeviceIdStore {
    override fun get() = id
    override fun set(deviceId: String) { id = deviceId }
    override fun clear() { id = null }
}

class MemoryOfflineStateStore : OfflineStateStore {
    var state: OfflineState? = null
    override fun load() = state
    override fun save(state: OfflineState) { this.state = state }
    override fun clear() { state = null }
}

class FakeClock(
    var elapsed: Long = 0,
    var wall: Long = 1_700_000_000_000,
    var boot: String = "boot-1",
) : DeviceClock {
    override fun elapsedRealtimeMs() = elapsed
    override fun wallClockMs() = wall
    override fun bootId() = boot

    fun advance(ms: Long) { elapsed += ms; wall += ms }
}

/** リクエストを記録し、あらかじめ用意したハンドラで応答する。 */
class FakeTransport(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return handler(request)
    }
}

fun json(status: Int, body: String) = HttpResponse(status, body)
fun error(status: Int, code: String) = HttpResponse(status, """{"error":"$code","message":"m"}""")
