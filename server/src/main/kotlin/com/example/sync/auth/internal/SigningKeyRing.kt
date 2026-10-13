package com.example.sync.auth.internal

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * JWT 署名鍵(ES256)の保管庫。key-dir 配下の `<kid>.jwk` を読み込む。
 *
 * ローテーション: 新しい `<kid>.jwk` を置いて active-kid を切り替えれば、
 * 旧鍵は検証専用として残り、発行済みトークンはそのまま有効期限まで使える。
 * 旧鍵のファイルはトークン最大有効期間(30 分)経過後に削除する。
 */
@Component
class SigningKeyRing(private val props: AuthProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val keys = ConcurrentHashMap<String, ECKey>()

    final val activeKid: String

    init {
        val dir = Path.of(props.signing.keyDir)
        Files.createDirectories(dir)
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(".jwk") }.forEach { f ->
                val key = ECKey.parse(Files.readString(f))
                require(key.isPrivate) { "Signing key $f has no private part" }
                keys[key.keyID] = key
            }
        }
        if (keys.isEmpty()) {
            val kid = "key-${LocalDate.now()}-${Crypto.randomUrlSafe(4)}"
            val key = ECKeyGenerator(Curve.P_256).keyID(kid).generate()
            val file = dir.resolve("$kid.jwk")
            Files.writeString(file, key.toJSONString())
            runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
            keys[kid] = key
            log.info("Generated new JWT signing key kid={}", kid)
        }
        activeKid = props.signing.activeKid.ifBlank { keys.keys.max() }
        require(keys.containsKey(activeKid)) { "auth.signing.active-kid '$activeKid' not found in ${props.signing.keyDir}" }
    }

    fun active(): ECKey = keys.getValue(activeKid)

    fun find(kid: String?): ECKey? = kid?.let { keys[it] }
}
