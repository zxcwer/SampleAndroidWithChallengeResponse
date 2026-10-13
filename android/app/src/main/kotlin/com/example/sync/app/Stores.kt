package com.example.sync.app

import android.content.Context
import android.content.SharedPreferences
import com.example.sync.authclient.DeviceIdStore
import com.example.sync.authclient.OfflineState
import com.example.sync.authclient.OfflineStateStore

private const val PREFS = "device_auth"

private fun Context.prefs(): SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** 端末IDは秘密情報ではないので通常のアプリ内ストレージに保存する。 */
class SharedPrefsDeviceIdStore(context: Context) : DeviceIdStore {
    private val prefs = context.prefs()
    override fun get(): String? = prefs.getString("device_id", null)
    override fun set(deviceId: String) = prefs.edit().putString("device_id", deviceId).apply()
    override fun clear() = prefs.edit().remove("device_id").apply()
}

class SharedPrefsOfflineStateStore(context: Context) : OfflineStateStore {
    private val prefs = context.prefs()

    override fun load(): OfflineState? {
        if (!prefs.contains("off_server_time")) return null
        return OfflineState(
            serverTimeAtSyncSec = prefs.getLong("off_server_time", 0),
            wallAtSyncMs = prefs.getLong("off_wall_at_sync", 0),
            elapsedAtSyncMs = prefs.getLong("off_elapsed_at_sync", 0),
            bootIdAtSync = prefs.getString("off_boot_id", "") ?: "",
            wallHighWaterMs = prefs.getLong("off_wall_high", 0),
            maxOfflineDays = prefs.getInt("off_max_days", 0),
        )
    }

    override fun save(state: OfflineState) {
        prefs.edit()
            .putLong("off_server_time", state.serverTimeAtSyncSec)
            .putLong("off_wall_at_sync", state.wallAtSyncMs)
            .putLong("off_elapsed_at_sync", state.elapsedAtSyncMs)
            .putString("off_boot_id", state.bootIdAtSync)
            .putLong("off_wall_high", state.wallHighWaterMs)
            .putInt("off_max_days", state.maxOfflineDays)
            .commit() // 時計の高水位を確実に残すため同期書き込み
    }

    override fun clear() {
        prefs.edit()
            .remove("off_server_time").remove("off_wall_at_sync").remove("off_elapsed_at_sync")
            .remove("off_boot_id").remove("off_wall_high").remove("off_max_days")
            .commit()
    }
}

/** サーバーから失効(device_revoked 等)を通知されたかどうか。再登録すると解除される。 */
class RevokedFlagStore(context: Context) {
    private val prefs = context.prefs()
    var revoked: Boolean
        get() = prefs.getBoolean("revoked", false)
        set(v) {
            prefs.edit().putBoolean("revoked", v).commit()
        }
}
