package com.example.sync.app

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** SQLCipher で暗号化したローカル DB の入口。パスフレーズは [LocalDbKeyProvider] が Keystore で保護する。 */
class SecureLocalDatabase(private val context: Context, private val keys: LocalDbKeyProvider) {
    private val file get() = context.getDatabasePath(DB_NAME)

    fun open(): SQLiteDatabase {
        System.loadLibrary("sqlcipher")
        file.parentFile?.mkdirs()
        val passphrase = keys.passphrase()
        try {
            return SQLiteDatabase.openOrCreateDatabase(file.absolutePath, passphrase, null, null, null)
        } finally {
            passphrase.fill(0)
        }
    }

    /** DB ファイルと鍵を削除する。 */
    fun wipe() {
        listOf("", "-journal", "-wal", "-shm").forEach { context.getDatabasePath(DB_NAME + it).delete() }
        keys.destroy()
    }

    private companion object {
        const val DB_NAME = "business.db"
    }
}
