package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import org.springframework.stereotype.Service

@Service
class DeviceAdminService(private val devices: DeviceRepository, private val audit: AuditService) {
    fun list(): List<Device> = devices.findAll()

    /** 「停止」「紛失」への変更は次回のトークン発行と、発行済みトークンの利用を即座に拒否する。 */
    fun updateStatus(deviceId: String, status: DeviceStatus, ip: String?) {
        if (!devices.updateStatus(deviceId, status)) {
            throw ApiException(ErrorCode.DEVICE_NOT_REGISTERED, "Device is not registered")
        }
        audit.success(deviceId, "status_${status.value}", ip)
    }
}
