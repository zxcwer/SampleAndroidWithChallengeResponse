package com.example.sync.auth.internal

import com.example.sync.auth.Scopes
import com.example.sync.auth.VerifiedToken
import com.example.sync.common.ApiException
import com.example.sync.common.ErrorCode
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Date
import java.util.UUID

data class IssuedToken(val accessToken: String, val expiresInSeconds: Long, val issuedAtEpochSeconds: Long)

/** サーバー秘密鍵(ES256)でアクセストークン(JWT)を発行・検証する。 */
@Component
class AccessTokenService(
    private val props: AuthProperties,
    private val keyRing: SigningKeyRing,
    private val clock: Clock,
) {
    fun issue(deviceId: String, scopes: Set<String> = DEFAULT_SCOPES): IssuedToken {
        val now = clock.instant()
        val exp = now.plus(props.accessTokenTtl)
        val claims = JWTClaimsSet.Builder()
            .issuer(props.issuer)
            .audience(props.audience)
            .subject("device:$deviceId")
            .claim("device_id", deviceId)
            .claim("scope", scopes.joinToString(" "))
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .jwtID(UUID.randomUUID().toString())
            .build()
        val key = keyRing.active()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims).apply { sign(ECDSASigner(key)) }
        return IssuedToken(jwt.serialize(), props.accessTokenTtl.seconds, now.epochSecond)
    }

    fun verify(token: String): VerifiedToken {
        val invalid = { reason: String -> ApiException(ErrorCode.INVALID_TOKEN, reason) }
        val jwt = try {
            SignedJWT.parse(token)
        } catch (e: Exception) {
            throw invalid("Malformed token")
        }
        // alg は ES256 のみ許可 (alg=none / HS256 へのすり替え防止)
        if (jwt.header.algorithm != JWSAlgorithm.ES256) throw invalid("Unsupported algorithm")
        val key = keyRing.find(jwt.header.keyID) ?: throw invalid("Unknown key id")
        val signatureOk = try {
            jwt.verify(ECDSAVerifier(key.toECPublicKey()))
        } catch (e: Exception) {
            false
        }
        if (!signatureOk) throw invalid("Invalid signature")

        val claims = try {
            jwt.jwtClaimsSet
        } catch (e: Exception) {
            throw invalid("Malformed claims")
        }
        if (claims.issuer != props.issuer) throw invalid("Invalid issuer")
        if (claims.audience != listOf(props.audience)) throw invalid("Invalid audience")
        val exp = claims.expirationTime ?: throw invalid("Missing exp")
        if (!clock.instant().isBefore(exp.toInstant())) throw invalid("Token expired")
        val deviceId = claims.getStringClaim("device_id")?.takeIf { it.isNotBlank() } ?: throw invalid("Missing device_id")
        val scopes = claims.getStringClaim("scope").orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
        return VerifiedToken(deviceId, scopes, claims.jwtid.orEmpty(), exp.toInstant())
    }

    companion object {
        val DEFAULT_SCOPES = setOf(Scopes.SYNC_READ, Scopes.SYNC_WRITE)
    }
}
