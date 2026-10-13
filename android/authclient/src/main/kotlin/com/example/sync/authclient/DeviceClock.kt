package com.example.sync.authclient

/** 端末の時計。テストで差し替えられるよう Android 依存を切り離している。 */
interface DeviceClock {
    /** 起動からの経過時間(SystemClock.elapsedRealtime)。時計の変更に影響されない。再起動で 0 に戻る。 */
    fun elapsedRealtimeMs(): Long

    /** 壁時計(System.currentTimeMillis)。ユーザーが変更できる。 */
    fun wallClockMs(): Long

    /** 再起動ごとに変わる識別子(Settings.Global.BOOT_COUNT など)。 */
    fun bootId(): String
}
