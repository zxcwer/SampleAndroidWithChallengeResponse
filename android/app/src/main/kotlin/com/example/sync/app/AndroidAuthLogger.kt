package com.example.sync.app

import android.util.Log
import com.example.sync.authclient.AuthLogger

/**
 * logcat 出力。確認方法:  adb logcat -s SyncAuth
 * 詳細(d/i)はデバッグビルドのみ。警告/エラーはリリースでも出す。秘密情報は出力しない(AuthLogger の注意を参照)。
 */
object AndroidAuthLogger : AuthLogger {
    const val TAG = "SyncAuth"

    override fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    override fun i(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    override fun w(message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }

    override fun e(message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
