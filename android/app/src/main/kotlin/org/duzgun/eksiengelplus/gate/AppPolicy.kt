package org.duzgun.eksiengelplus.gate

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** What one distribution channel requires (`github`, `play`; the extension reads `chrome`/`firefox`). */
data class ChannelPolicy(
    /** Builds below this stop until updated. A device never lowers it. */
    val minVersionCode: Int,
    /** Individual builds that stop. Follows the newest policy, so it can be lifted. */
    val revokedVersionCodes: Set<Int> = emptySet(),
    /** Newest published build, for a non-blocking "update available". */
    val latestVersionCode: Int? = null,
    /** Where that build is (https only) and its SHA-256; the `github` channel only. */
    val apkUrl: String? = null,
    val sha256: String? = null,
)

/**
 * The update policy both clients fetch from eksiengelplus.duzgun.org (openspec
 * change app-update-gate), ported from OpenOTT's and SportGuide TV's `gate`
 * without their tamper and offline-limit rules. Signed with the maintainer's
 * policy key; an unsigned or altered file is ignored, so only the maintainer can
 * raise a minimum or revoke a build.
 *
 * Mirror: frontend/app/assets/js/updateGate.js (parsePolicy, verifyPolicy).
 * test-update-gate.mjs fails when the two carry different keys.
 */
data class AppPolicy(
    val issuedAt: Long,
    val expiresAt: Long?,
    val app: String,
    val channels: Map<String, ChannelPolicy>,
    val messages: Map<String, String> = emptyMap(),
)

/**
 * `{"alg":"ES256","payload":"<base64 JSON>","sig":"<base64 DER>"}`: ECDSA P-256
 * with SHA-256 over the decoded payload. Two keys are built in, the primary and
 * an offline backup, so losing the primary does not strand every install on its
 * last policy.
 */
object PolicyCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The maintainer's keys (X.509 SubjectPublicKeyInfo, base64), primary first.
     * Same list, same order, as PUBLIC_KEYS in updateGate.js; the private halves
     * are ~/.android/eksiengelplus-policy-key{,-backup}.pem.
     */
    val PUBLIC_KEYS: List<String> = listOf(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmpAcIdckdfHQd2acThkHG5xroivAMU9E3BCg9sFGnlyHyABNb8gk/uF25CsbAca8Ds69cQDWxnvXa4GmCtma0A==",
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcfxP4nYytvzTxBlqNESNX2ZoT+s0NikB49vzr5h0+Jkz1Pn9vVZ25/zr7Q+ozGnPn9yoVCpj6eis3uN2KdpOFw==",
    )

    fun publicKey(base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    fun publicKeys(): List<PublicKey> = PUBLIC_KEYS.map(::publicKey)

    /** The policy inside [envelope] when a key signed it and it names [appId];
     *  null for anything else, never an exception. */
    fun verify(envelope: String, appId: String, keys: List<PublicKey> = publicKeys()): AppPolicy? = runCatching {
        if (envelope.length > MAX_ENVELOPE) return null
        val outer = json.parseToJsonElement(envelope).jsonObject
        if (outer.str("alg") != "ES256") return null
        val payload = Base64.getDecoder().decode(outer.str("payload") ?: return null)
        val sig = Base64.getDecoder().decode(outer.str("sig") ?: return null)
        val signed = keys.any { key ->
            runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(payload)
                    verify(sig)
                }
            }.getOrDefault(false)
        }
        if (!signed) return null
        parse(payload.toString(Charsets.UTF_8))?.takeIf { it.app == appId }
    }.getOrNull()

    internal fun parse(payload: String): AppPolicy? {
        val root = json.parseToJsonElement(payload).jsonObject
        if (root.str("v") != "1") return null
        val channels = (root["channels"] as? JsonObject)?.mapNotNull { (name, value) ->
            val node = value as? JsonObject ?: return@mapNotNull null
            val min = node.str("minVersionCode")?.toIntOrNull() ?: return@mapNotNull null
            name to ChannelPolicy(
                minVersionCode = min,
                revokedVersionCodes = (node["revokedVersionCodes"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet().orEmpty(),
                latestVersionCode = node.str("latestVersionCode")?.toIntOrNull(),
                apkUrl = node.str("apkUrl")?.takeIf { it.startsWith("https://") },
                sha256 = node.str("sha256")?.lowercase()
                    ?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } },
            )
        }?.toMap().orEmpty()
        val messages = (root["message"] as? JsonObject)?.mapNotNull { (lang, text) ->
            (text as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { lang to it.take(500) }
        }?.toMap().orEmpty()
        return AppPolicy(
            issuedAt = (root.str("issuedAt")?.toLongOrNull() ?: return null) * 1000,
            expiresAt = root.str("expiresAt")?.toLongOrNull()?.times(1000),
            app = root.str("app") ?: return null,
            channels = channels,
            messages = messages,
        )
    }

    private const val MAX_ENVELOPE = 64 * 1024

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
