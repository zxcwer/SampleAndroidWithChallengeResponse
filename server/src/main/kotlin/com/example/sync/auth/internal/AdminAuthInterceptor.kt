package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.MessageDigest

@Component
class AdminAuthInterceptor(private val admin: AdminProperties) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (admin.apiToken.isBlank()) throw ApiException(ErrorCode.ADMIN_DISABLED, "Admin API is not configured")
        val presented = request.getHeader("Authorization")?.removePrefix("Bearer ")?.takeIf {
            request.getHeader("Authorization")!!.startsWith("Bearer ")
        }
        val ok = presented != null && MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            admin.apiToken.toByteArray(Charsets.UTF_8),
        )
        if (!ok) throw ApiException(ErrorCode.UNAUTHORIZED, "Invalid admin credentials")
        return true
    }
}

@Configuration
class AdminWebConfig(private val interceptor: AdminAuthInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/admin/**")
    }
}
