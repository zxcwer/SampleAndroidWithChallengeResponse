package com.example.sync.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.sync.authclient.DeviceKeyStore
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 端末の本人性を証明する ECDSA(P-256 / SHA-256) 鍵ペア。秘密鍵は Android Keystore から取り出せない。
 * StrongBox 搭載端末ではそちらを優先し、非搭載なら通常の TEE にフォールバックする。
 */
class AndroidKeystoreDeviceKeyStore(private val alias: String = KEY_ALIAS) : DeviceKeyStore {

    override fun generate(): String {
        delete()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val publicKey = try {
            kpg.initialize(buildSpec(strongBox = true))
            kpg.generateKeyPair().public
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException は ProviderException のサブクラス。機種により素の ProviderException も来る。
            kpg.initialize(buildSpec(strongBox = false))
            kpg.generateKeyPair().public
        }
        // X.509 SubjectPublicKeyInfo を Base64(改行なし) で送る
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    private fun buildSpec(strongBox: Boolean) =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // 無人でのクレードル同期を行うため、署名ごとのユーザー認証は要求しない
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(strongBox)
            .build()

    override fun exists(): Boolean = keyStore().containsAlias(alias)

    override fun delete() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    /** DER 形式の署名を Base64(改行なし) で返す。 */
    override fun sign(message: String): String {
        val privateKey = keyStore().getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Device key '$alias' does not exist")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message.toByteArray(Charsets.UTF_8))
            sign()
        }
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val KEY_ALIAS = "device_auth_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
