package com.example.sync.resource

import com.example.sync.auth.DeviceAuthApi
import com.example.sync.auth.VerifiedToken
import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * リソース側ミドルウェア: 全リクエストでアクセストークンを検証する。
 *  - 署名 / iss / aud / exp(サーバー時刻) は [DeviceAuthApi.verifyAccessToken] が検証
 *  - scope はエンドポイントの [RequiredScope] と照合
 *  - 端末状態は [DeviceAuthApi.isDeviceActive] で毎回確認(同期中に停止された端末も即拒否)
 */
@Component
class AccessTokenInterceptor(private val auth: DeviceAuthApi) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true

        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith("Bearer ")) {
            throw ApiException(ErrorCode.INVALID_TOKEN, "Missing bearer token")
        }
        val token = auth.verifyAccessToken(header.removePrefix("Bearer ").trim())

        val required = handler.getMethodAnnotation(RequiredScope::class.java)?.value
            ?: throw ApiException(ErrorCode.INSUFFICIENT_SCOPE, "Endpoint has no scope policy")
        if (required !in token.scopes) {
            throw ApiException(ErrorCode.INSUFFICIENT_SCOPE, "Scope '$required' is required")
        }

        if (!auth.isDeviceActive(token.deviceId)) {
            throw ApiException(ErrorCode.DEVICE_REVOKED, "Device has been revoked")
        }
        request.setAttribute(TOKEN_ATTRIBUTE, token)
        return true
    }

    companion object {
        const val TOKEN_ATTRIBUTE = "verifiedToken"
    }
}

@Configuration
class ResourceWebConfig(private val interceptor: AccessTokenInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/sync/**")
    }
}

internal fun HttpServletRequest.verifiedToken(): VerifiedToken =
    getAttribute(AccessTokenInterceptor.TOKEN_ATTRIBUTE) as VerifiedToken
