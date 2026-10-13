package com.example.sync.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ローカル DB(SQLCipher) の暗号鍵を管理する。
 * ランダムな 256bit のパスフレーズを作り、Android Keystore の AES 鍵(取り出し不可)でラップして保存する。
 * ストレージを抜き取られても、Keystore の鍵が無ければ DB を復号できない。
 */
class LocalDbKeyProvider(context: Context) {
    private val prefs = context.getSharedPreferences("local_db_key", Context.MODE_PRIVATE)

    /** DB を開くためのパスフレーズ。初回は生成してラップ保存する。呼び出し側で使い終わったらゼロクリアする。 */
    @Synchronized
    fun passphrase(): ByteArray {
        val wrapped = prefs.getString(PREF_WRAPPED, null)
        if (wrapped != null) return unwrap(wrapped)

        val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
        prefs.edit().putString(PREF_WRAPPED, wrap(fresh)).commit()
        return fresh
    }

    /** DB 鍵を破棄する。これ以降、既存の DB ファイルは復号できなくなる(ワイプ用)。 */
    @Synchronized
    fun destroy() {
        prefs.edit().remove(PREF_WRAPPED).commit()
        val ks = keyStore()
        if (ks.containsAlias(WRAP_KEY_ALIAS)) ks.deleteEntry(WRAP_KEY_ALIAS)
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        val encrypted = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val (iv, encrypted) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(encrypted)
    }

    private fun wrapKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(WRAP_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_KEY_ALIAS = "local_db_wrap_key"
        const val PREF_WRAPPED = "wrapped_passphrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
