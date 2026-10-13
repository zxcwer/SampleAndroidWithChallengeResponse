package com.example.sync.authclient

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(val status: Int, val body: String)

interface HttpTransport {
    /** 通信失敗は IOException を投げる。HTTP エラーステータスは例外にせず返す。 */
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * HttpURLConnection 実装。Android では network_security_config.xml (社内 CA の信頼設定・平文禁止) が適用される。
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            request.body?.let {
                conn.doOutput = true
                conn.outputStream.use { os -> os.write(it.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpResponse(status, body)
        } finally {
            conn.disconnect()
        }
    }
}

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** ベース URL・アプリバージョンヘッダ・エラー変換をまとめた薄い HTTP ラッパー。 */
class ApiHttp(
    baseUrl: String,
    val transport: HttpTransport,
    /** サーバーの min-app-version と比較される versionCode。 */
    private val appVersionCode: Int,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    fun send(method: String, path: String, body: String? = null, bearer: String? = null): HttpResponse {
        val headers = buildMap {
            put("Accept", "application/json")
            put("X-App-Version", appVersionCode.toString())
            if (body != null) put("Content-Type", "application/json")
            if (bearer != null) put("Authorization", "Bearer $bearer")
        }
        return transport.execute(HttpRequest(method, baseUrl + path, headers, body))
    }

    inline fun <reified Req, reified Res> postJson(path: String, req: Req): Res =
        decode(send("POST", path, ApiJson.encodeToString(kotlinx.serialization.serializer<Req>(), req)))

    inline fun <reified Res> decode(response: HttpResponse): Res {
        throwIfError(response)
        return ApiJson.decodeFromString(kotlinx.serialization.serializer<Res>(), response.body)
    }

    fun throwIfError(response: HttpResponse) {
        if (response.status in 200..299) return
        val err = runCatching { ApiJson.decodeFromString(ErrorResponse.serializer(), response.body) }.getOrNull()
        throw DeviceAuthException(
            err?.error ?: ErrorCodes.UNKNOWN,
            response.status,
            err?.message ?: "HTTP ${response.status}",
        )
    }
}
