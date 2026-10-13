package com.example.sync.auth.internal

import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object Crypto {
    private val random = SecureRandom()

    private val p256: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))

    /**
     * 端末から届いた公開鍵(X.509 SubjectPublicKeyInfo の Base64)を検証して返す。
     * P-256 の EC 鍵であり、点が曲線上にあることを確認する。
     */
    fun parseDevicePublicKey(base64: String): ECPublicKey {
        val bad = { ApiException(ErrorCode.INVALID_REQUEST, "publicKey must be a Base64 X.509 P-256 EC public key") }
        val der = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw bad()
        }
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        } catch (e: Exception) {
            throw bad()
        } as? ECPublicKey ?: throw bad()

        val params = key.params
        if (params.curve != p256.curve || params.order != p256.order || params.generator != p256.generator) throw bad()
        if (!isOnCurve(key)) throw bad()
        return key
    }

    private fun isOnCurve(key: ECPublicKey): Boolean {
        val curve = key.params.curve
        val p = (curve.field as java.security.spec.ECFieldFp).p
        val x = key.w.affineX
        val y = key.w.affineY
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val lhs = y.modPow(BigInteger.TWO, p)
        val rhs = x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        return lhs == rhs
    }

    fun decodePublicKey(base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    /**
     * 端末の署名(SHA256withECDSA / DER 形式)を検証する。
     * Android の Signature が出力する DER 形式をそのまま Java の Signature が検証できる。
     */
    fun verifyDeviceSignature(publicKey: PublicKey, message: String, signatureBase64: String): Boolean {
        val sig = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(message.toByteArray(Charsets.UTF_8))
                verify(sig)
            }
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
    }
}

/** 端末が署名する文字列。用途・端末ID・チャレンジを含めて別用途/別端末の署名流用を防ぐ。 */
object ChallengeMessage {
    const val PREFIX = "device-auth-v1"
    fun build(deviceId: String, challengeId: String, nonce: String): String =
        listOf(PREFIX, deviceId, challengeId, nonce).joinToString("\n")
}
