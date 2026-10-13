package com.example.sync.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.example.sync.authclient.DeviceClock

class AndroidDeviceClock(private val context: Context) : DeviceClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun wallClockMs(): Long = System.currentTimeMillis()

    /** 再起動ごとに増える Settings.Global.BOOT_COUNT。取得できなければ固定値。 */
    override fun bootId(): String =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()
}
