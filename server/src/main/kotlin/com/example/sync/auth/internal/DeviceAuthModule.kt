package com.example.sync.auth.internal

import com.example.sync.auth.DeviceAuthApi
import com.example.sync.auth.VerifiedToken
import org.springframework.stereotype.Service

/** [DeviceAuthApi] の実装。認証モジュール内部の実装を隠蔽する。 */
@Service
class DeviceAuthModule(
    private val accessTokens: AccessTokenService,
    private val devices: DeviceRepository,
) : DeviceAuthApi {
    override fun verifyAccessToken(token: String): VerifiedToken = accessTokens.verify(token)

    override fun isDeviceActive(deviceId: String): Boolean =
        devices.find(deviceId)?.status == DeviceStatus.ACTIVE
}
