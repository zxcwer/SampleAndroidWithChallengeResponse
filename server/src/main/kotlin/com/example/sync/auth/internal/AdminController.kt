package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class RegistrationCodeResponse(val code: String, val expiresAt: Instant)
data class DeviceSummary(
    val deviceId: String,
    val status: String,
    val model: String?,
    val keyFingerprint: String,
    val registeredAt: Instant,
    val lastAuthAt: Instant?,
)
data class StatusUpdateRequest(val status: String)

/** Web 管理画面(バックエンド)向け。AdminAuthInterceptor で管理者トークンを検証する。 */
@RestController
@RequestMapping("/admin/v1")
class AdminController(private val registration: RegistrationService, private val admin: DeviceAdminService) {

    @PostMapping("/registration-codes")
    @ResponseStatus(HttpStatus.CREATED)
    fun issueRegistrationCode(
        @RequestHeader("X-Admin-User", required = false) adminUser: String?,
    ): RegistrationCodeResponse {
        val issued = registration.issueCode((adminUser ?: "admin").take(128))
        return RegistrationCodeResponse(issued.code, issued.expiresAt)
    }

    @GetMapping("/devices")
    fun listDevices(): List<DeviceSummary> = admin.list().map {
        DeviceSummary(
            it.deviceId, it.status.value, it.model, it.keyFingerprint,
            Instant.ofEpochMilli(it.registeredAt), it.lastAuthAt?.let(Instant::ofEpochMilli),
        )
    }

    @PutMapping("/devices/{deviceId}/status")
    fun updateStatus(@PathVariable deviceId: String, @RequestBody req: StatusUpdateRequest, http: HttpServletRequest) {
        val status = DeviceStatus.parse(req.status)
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "status must be one of: active, suspended, lost")
        admin.updateStatus(deviceId, status, http.remoteAddr)
    }
}
