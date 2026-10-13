package com.example.sync.authclient

object ErrorCodes {
    const val INVALID_REQUEST = "invalid_request"
    const val INVALID_REGISTRATION_CODE = "invalid_registration_code"
    const val CHALLENGE_EXPIRED = "challenge_expired"
    const val INVALID_CHALLENGE = "invalid_challenge"
    const val INVALID_SIGNATURE = "invalid_signature"
    const val INVALID_TOKEN = "invalid_token"
    const val DEVICE_NOT_REGISTERED = "device_not_registered"
    const val DEVICE_REVOKED = "device_revoked"
    const val INSUFFICIENT_SCOPE = "insufficient_scope"
    const val APP_VERSION_UNSUPPORTED = "app_version_unsupported"
    const val RATE_LIMITED = "rate_limited"
    const val UNKNOWN = "unknown_error"
}

/** サーバーが返したエラー。通信自体の失敗は IOException のまま呼び出し元へ伝える。 */
class DeviceAuthException(val code: String, val httpStatus: Int, message: String) :
    RuntimeException("$code ($httpStatus): $message") {

    /** 再試行しても結果が変わらず、画面にメッセージを表示すべきエラー。 */
    val isTerminal: Boolean
        get() = code == ErrorCodes.DEVICE_REVOKED ||
            code == ErrorCodes.DEVICE_NOT_REGISTERED ||
            code == ErrorCodes.APP_VERSION_UNSUPPORTED

    /** 端末が失効/未登録の状態(ローカルデータの扱いは運用ルールで決める)。 */
    val isDeviceRevoked: Boolean
        get() = code == ErrorCodes.DEVICE_REVOKED || code == ErrorCodes.DEVICE_NOT_REGISTERED
}
