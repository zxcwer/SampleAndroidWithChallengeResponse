package com.example.sync.common

/** HTTP ステータスとエラーコードの対応(設計書 8 章)。 */
enum class ErrorCode(val code: String, val status: Int) {
    INVALID_REQUEST("invalid_request", 400),
    INVALID_REGISTRATION_CODE("invalid_registration_code", 403),
    CHALLENGE_EXPIRED("challenge_expired", 401),
    INVALID_CHALLENGE("invalid_challenge", 401),
    INVALID_SIGNATURE("invalid_signature", 401),
    INVALID_TOKEN("invalid_token", 401),
    UNAUTHORIZED("unauthorized", 401),
    DEVICE_NOT_REGISTERED("device_not_registered", 403),
    DEVICE_REVOKED("device_revoked", 403),
    INSUFFICIENT_SCOPE("insufficient_scope", 403),
    APP_VERSION_UNSUPPORTED("app_version_unsupported", 426),
    RATE_LIMITED("rate_limited", 429),
    ADMIN_DISABLED("admin_disabled", 503),
    INTERNAL_ERROR("internal_error", 500),
}

class ApiException(val error: ErrorCode, message: String) : RuntimeException(message)

data class ApiError(val error: String, val message: String)
