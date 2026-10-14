package com.example.sync.authclient

import java.io.IOException

/** 認証まわりのイベント通知。 */
fun interface AuthEventListener {
    /** device_revoked / device_not_registered を受け取った。ローカルデータの扱いは運用ルールで決める。 */
    fun onDeviceRevoked(code: String)
}

/** アクセストークンはメモリ上にだけ保持し、ストレージには保存しない。 */
class AccessTokenHolder(private val clock: DeviceClock) {
    @Volatile
    private var token: AccessToken? = null

    fun current(): AccessToken? = token?.takeIf { it.isUsable(clock.elapsedRealtimeMs()) }
    fun set(t: AccessToken) { token = t }
    fun clear() { token = null }
}

/**
 * 認証付きの同期 API クライアント。
 *  - トークンが無い/期限切れなら自動でチャレンジ・レスポンスをやり直す
 *  - 401 が返ったら 1 回だけ再認証して再送する(端末時計に依存せず、サーバー判定に従う)
 *  - device_revoked / device_not_registered は再試行せずリスナーへ通知する
 */
class SyncApiClient(
    private val api: ApiHttp,
    private val auth: DeviceAuthClient,
    private val holder: AccessTokenHolder,
    private val listener: AuthEventListener = AuthEventListener { },
) {
    @Throws(IOException::class, DeviceAuthException::class)
    fun policy(): SyncPolicy = api.decode(execute("GET", "/sync/v1/policy"))

    @Throws(IOException::class, DeviceAuthException::class)
    fun pull(): PullResponse = api.decode(execute("GET", "/sync/v1/pull"))

    @Throws(IOException::class, DeviceAuthException::class)
    fun push(records: List<PushRecord>): PushResponse =
        api.decode(execute("POST", "/sync/v1/push", ApiJson.encodeToString(PushRequest.serializer(), PushRequest(records))))

    private fun execute(method: String, path: String, body: String? = null): HttpResponse {
        try {
            var response = send(method, path, body)
            if (response.status == 401) {
                api.logger.w("sync: 401 on $method $path - discarding token and re-authenticating once")
                holder.clear()
                response = send(method, path, body)
            }
            api.throwIfError(response)
            return response
        } catch (e: DeviceAuthException) {
            if (e.isDeviceRevoked) {
                api.logger.e("sync: device rejected by server (${e.code}); notifying listener")
                holder.clear()
                listener.onDeviceRevoked(e.code)
            }
            throw e
        }
    }

    private fun send(method: String, path: String, body: String?): HttpResponse {
        val token = holder.current() ?: run {
            api.logger.d("sync: no usable access token in memory - authenticating")
            auth.authenticate().also(holder::set)
        }
        return api.send(method, path, body, token.value)
    }
}
