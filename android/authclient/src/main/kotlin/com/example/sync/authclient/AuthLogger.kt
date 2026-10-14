package com.example.sync.authclient

/**
 * ログの出力先。authclient は Android に依存しないため、アプリ側が logcat 等の実装を渡す。
 *
 * 守ること: アクセストークン、nonce、署名、登録コード、DB パスフレーズなどの秘密情報は絶対に出力しない。
 * 出力してよいのは HTTP メソッド/パス/ステータス/所要時間、エラーコード、端末ID、状態遷移など。
 */
interface AuthLogger {
    fun d(message: String)
    fun i(message: String)
    fun w(message: String, throwable: Throwable? = null)
    fun e(message: String, throwable: Throwable? = null)

    object None : AuthLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String, throwable: Throwable?) = Unit
        override fun e(message: String, throwable: Throwable?) = Unit
    }
}
