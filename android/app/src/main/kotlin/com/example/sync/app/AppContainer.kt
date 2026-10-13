package com.example.sync.app

import android.content.Context
import com.example.sync.authclient.AccessTokenHolder
import com.example.sync.authclient.ApiHttp
import com.example.sync.authclient.DeviceAuthClient
import com.example.sync.authclient.OfflineGuard
import com.example.sync.authclient.SyncApiClient
import com.example.sync.authclient.UrlConnectionTransport

/** 依存の組み立て(簡易 DI)。アクセストークンを持つ [AccessTokenHolder] はプロセス内で 1 つだけ。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val clock = AndroidDeviceClock(appContext)
    val keyStore = AndroidKeystoreDeviceKeyStore()
    val deviceIds = SharedPrefsDeviceIdStore(appContext)
    val revokedFlag = RevokedFlagStore(appContext)
    val offlineGuard = OfflineGuard(SharedPrefsOfflineStateStore(appContext), clock)

    val dbKeys = LocalDbKeyProvider(appContext)
    val localDatabase = SecureLocalDatabase(appContext, dbKeys)

    private val api = ApiHttp(BuildConfig.SERVER_BASE_URL, UrlConnectionTransport(), appVersionCode())
    val auth = DeviceAuthClient(api, keyStore, deviceIds, clock)
    private val tokenHolder = AccessTokenHolder(clock)

    val syncApi = SyncApiClient(api, auth, tokenHolder) { onDeviceRevoked() }

    /**
     * 失効/紛失/未登録を検知したときの運用ルール。
     *  - 常に: アプリをロックし、以降の自動認証を止める
     *  - WIPE_ON_REVOKED=true の場合: ローカル DB と鍵も消去する
     */
    private fun onDeviceRevoked() {
        revokedFlag.revoked = true
        if (BuildConfig.WIPE_ON_REVOKED) wipeLocalData()
    }

    fun wipeLocalData() {
        localDatabase.wipe()
        offlineGuard.clear()
    }

    private fun appVersionCode(): Int {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return info.longVersionCode.toInt()
    }
}
