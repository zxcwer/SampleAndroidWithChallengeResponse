package com.example.sync.authclient

/** 最終同期の記録。永続化は [OfflineStateStore] に任せる。 */
data class OfflineState(
    /** 同期完了時にサーバーが返した時刻(表示用)。 */
    val serverTimeAtSyncSec: Long,
    val wallAtSyncMs: Long,
    val elapsedAtSyncMs: Long,
    val bootIdAtSync: String,
    /** これまでに観測した壁時計の最大値。時計の巻き戻しで経過時間を減らされないようにする。 */
    val wallHighWaterMs: Long,
    val maxOfflineDays: Int,
)

interface OfflineStateStore {
    fun load(): OfflineState?
    fun save(state: OfflineState)
    fun clear()
}

sealed interface LockStatus {
    /** 業務データを閲覧できる。[remainingMs] はロックまでの残り(推定)。 */
    data class Unlocked(val remainingMs: Long) : LockStatus

    enum class Reason { NEVER_SYNCED, OFFLINE_TOO_LONG }
    data class Locked(val reason: Reason) : LockStatus
}

/**
 * 最終同期からの経過日数による制限(設計書 5 章)。
 *
 * 経過時間 = max(壁時計の最大観測値 − 同期時の壁時計, 同一ブート内の elapsedRealtime 差)
 *  - 時計を巻き戻しても、観測済みの最大値と elapsedRealtime が経過時間を保持する
 *  - 時計を進めた場合はロックが早まるだけ(安全側)。次回同期で解消する
 * 許容日数はサーバーが同期時に配信する値(運用中に変更可能)。
 */
class OfflineGuard(private val store: OfflineStateStore, private val clock: DeviceClock) {

    /** 同期が最後まで成功した直後に呼ぶ。 */
    fun recordSync(serverTimeSec: Long, maxOfflineDays: Int) {
        val wall = clock.wallClockMs()
        store.save(
            OfflineState(
                serverTimeAtSyncSec = serverTimeSec,
                wallAtSyncMs = wall,
                elapsedAtSyncMs = clock.elapsedRealtimeMs(),
                bootIdAtSync = clock.bootId(),
                wallHighWaterMs = wall,
                maxOfflineDays = maxOfflineDays,
            ),
        )
    }

    fun status(): LockStatus {
        val state = store.load() ?: return LockStatus.Locked(LockStatus.Reason.NEVER_SYNCED)
        val wall = clock.wallClockMs()
        val highWater = maxOf(state.wallHighWaterMs, wall)
        if (highWater - state.wallHighWaterMs >= HIGH_WATER_SAVE_INTERVAL_MS) {
            store.save(state.copy(wallHighWaterMs = highWater))
        }

        val wallElapsed = highWater - state.wallAtSyncMs
        val monoElapsed =
            if (clock.bootId() == state.bootIdAtSync) clock.elapsedRealtimeMs() - state.elapsedAtSyncMs else 0L
        val elapsed = maxOf(wallElapsed, monoElapsed, 0L)

        val limit = state.maxOfflineDays.toLong() * DAY_MS
        return if (elapsed > limit) LockStatus.Locked(LockStatus.Reason.OFFLINE_TOO_LONG)
        else LockStatus.Unlocked(limit - elapsed)
    }

    fun isLocked(): Boolean = status() is LockStatus.Locked

    fun lastSyncServerTimeSec(): Long? = store.load()?.serverTimeAtSyncSec

    fun clear() = store.clear()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val HIGH_WATER_SAVE_INTERVAL_MS = 60_000L
    }
}
