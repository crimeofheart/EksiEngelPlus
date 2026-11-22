package org.duzgun.eksiengelplus.gate

import com.google.common.truth.Truth.assertThat
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Test

/**
 * The update gate's rules (openspec change app-update-gate), off device.
 * Policies are signed here with throwaway keys, the way
 * tools/set-min-version.sh signs them: DER ECDSA over the payload bytes.
 * The extension's mirror is tested by frontend/app/scripts/test-update-gate.mjs.
 */
class UpdateGateTest {

    private val primary = keyPair()
    private val backup = keyPair()
    private val keys = listOf(primary.public, backup.public)
    private val app = "org.duzgun.eksiengelplus"

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun envelope(payload: String, key: KeyPair = primary): String {
        val bytes = payload.toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(bytes)
            sign()
        }
        val b64 = Base64.getEncoder()
        return """{"alg":"ES256","payload":"${b64.encodeToString(bytes)}","sig":"${b64.encodeToString(sig)}"}"""
    }

    private fun payload(issuedAt: Long, github: String, extra: String = ""): String =
        """{"v":1,"app":"$app","issuedAt":$issuedAt,"channels":{"github":$github,"play":{"minVersionCode":0}},"message":{"tr":"güncelleyin"}$extra}"""

    private fun policy(issuedAt: Long, github: String, extra: String = ""): AppPolicy =
        checkNotNull(PolicyCodec.verify(envelope(payload(issuedAt, github, extra)), app, keys))

    private fun accept(state: GateState, policy: AppPolicy, version: Int = 604, now: Long = 5_000_000L) =
        Gate.accept(state, policy, "github", version, now)

    @Test
    fun `a signed policy verifies with either built-in key and nothing else`() {
        val body = payload(1, """{"minVersionCode":605}""")
        assertThat(PolicyCodec.verify(envelope(body), app, keys)?.channels?.get("github")?.minVersionCode).isEqualTo(605)
        assertThat(PolicyCodec.verify(envelope(body, backup), app, keys)).isNotNull()
        assertThat(PolicyCodec.verify(envelope(body, keyPair()), app, keys)).isNull()
        assertThat(PolicyCodec.verify(envelope(body), "com.other", keys)).isNull()
        assertThat(PolicyCodec.verify("""{"alg":"none","payload":"","sig":""}""", app, keys)).isNull()
        assertThat(PolicyCodec.verify("not json", app, keys)).isNull()
    }

    @Test
    fun `an altered payload does not verify`() {
        val signed = envelope(payload(1, """{"minVersionCode":605}"""))
        val forged = Base64.getEncoder().encodeToString(payload(1, """{"minVersionCode":9999}""").toByteArray())
        val tampered = signed.replace(Regex(""""payload":"[^"]*""""), """"payload":"$forged"""")
        assertThat(PolicyCodec.verify(tampered, app, keys)).isNull()
    }

    @Test
    fun `the built-in keys are valid P-256 keys`() {
        assertThat(PolicyCodec.publicKeys()).hasSize(2)
        assertThat(PolicyCodec.publicKeys().map { it.algorithm }.toSet()).containsExactly("EC")
    }

    /**
     * The policy tools/set-min-version.sh last wrote is signed by openssl, not by
     * this test: it proves the built-in keys and the real signing path agree.
     * The extension's test checks the same file with WebCrypto.
     */
    @Test
    fun `the last published policy verifies under the built-in keys`() {
        val file = java.io.File(java.io.File("../..").canonicalFile, "backend/policy/app-v1.json")
        if (!file.exists()) return
        val published = PolicyCodec.verify(file.readText(), app)
        assertThat(published).isNotNull()
        assertThat(published!!.channels.keys).containsAtLeast("github", "play", "chrome", "firefox")
    }

    @Test
    fun `a raised minimum blocks older builds only`() {
        val state = accept(GateState(), policy(1, """{"minVersionCode":605}"""))
        assertThat(Gate.evaluate(state, 604)).isInstanceOf(GateDecision.UpdateRequired::class.java)
        assertThat(Gate.evaluate(state, 604).blocks).isTrue()
        assertThat(Gate.evaluate(state, 605)).isEqualTo(GateDecision.Allow)
        assertThat(Gate.message(state)).isEqualTo("güncelleyin")
    }

    @Test
    fun `each flavor reads its own channel`() {
        val state = Gate.accept(GateState(), policy(1, """{"minVersionCode":605}"""), "play", 604, 0)
        assertThat(Gate.evaluate(state, 604).blocks).isFalse()
    }

    @Test
    fun `a revocation stops one build and can be lifted`() {
        val revoked = accept(GateState(), policy(1, """{"minVersionCode":0,"revokedVersionCodes":[605]}"""), version = 605)
        assertThat(Gate.evaluate(revoked, 605)).isInstanceOf(GateDecision.Revoked::class.java)
        assertThat(Gate.evaluate(revoked, 604).blocks).isFalse()
        assertThat(Gate.evaluate(revoked, 606).blocks).isFalse()
        val lifted = accept(revoked, policy(2, """{"minVersionCode":0}"""), version = 605)
        assertThat(Gate.evaluate(lifted, 605).blocks).isFalse()
    }

    @Test
    fun `the minimum never goes down and an older policy changes nothing`() {
        val raised = accept(GateState(), policy(5, """{"minVersionCode":605}"""))
        val lowered = accept(raised, policy(6, """{"minVersionCode":0}"""))
        assertThat(Gate.evaluate(lowered, 604).blocks).isTrue()
        val replayed = accept(raised, policy(4, """{"minVersionCode":0,"revokedVersionCodes":[700]}"""))
        assertThat(replayed.copy(maxWallMs = 0)).isEqualTo(raised.copy(maxWallMs = 0))
    }

    @Test
    fun `an expired policy is ignored, and turning the clock back does not revive it`() {
        val expiring = policy(1, """{"minVersionCode":605}""", extra = ""","expiresAt":2""")
        assertThat(Gate.evaluate(accept(GateState(), expiring, now = 3_000), 604).blocks).isFalse()
        val seenLater = Gate.tick(GateState(), 3_000)
        assertThat(Gate.evaluate(accept(seenLater, expiring, now = 1_000), 604).blocks).isFalse()
        assertThat(Gate.evaluate(accept(GateState(), expiring, now = 1_000), 604).blocks).isTrue()
    }

    @Test
    fun `a newer allowed build is offered without blocking`() {
        val state = accept(GateState(), policy(1, """{"minVersionCode":0,"latestVersionCode":606}"""))
        assertThat(Gate.evaluate(state, 604)).isEqualTo(GateDecision.UpdateAvailable(606))
        assertThat(Gate.evaluate(state, 606)).isEqualTo(GateDecision.Allow)
    }

    @Test
    fun `the APK is kept only with an https URL and a well-formed hash`() {
        val sha = "a".repeat(64)
        val state = accept(GateState(), policy(1, """{"minVersionCode":605,"apkUrl":"https://x/eksiengelplus-0.6.5.apk","sha256":"$sha"}"""))
        assertThat(state.apkUrl).isEqualTo("https://x/eksiengelplus-0.6.5.apk")
        assertThat(state.sha256).isEqualTo(sha)
        val insecure = accept(GateState(), policy(1, """{"minVersionCode":605,"apkUrl":"http://x/a.apk","sha256":"zz"}"""))
        assertThat(insecure.apkUrl).isNull()
        assertThat(insecure.sha256).isNull()
    }

    @Test
    fun `blocked-since survives a re-check and resets once unblocked`() {
        val first = accept(GateState(), policy(1, """{"minVersionCode":605}"""), now = 1_000)
        val second = accept(first, policy(2, """{"minVersionCode":605}"""), now = 9_000)
        assertThat(second.requiredSinceMs).isEqualTo(1_000)
        val updated = accept(second, policy(3, """{"minVersionCode":605}"""), version = 605, now = 10_000)
        assertThat(updated.requiredSinceMs).isEqualTo(0)
    }

    @Test
    fun `state survives a round trip and garbage decodes to the default`() {
        val state = accept(GateState(), policy(1, """{"minVersionCode":605,"revokedVersionCodes":[603]}"""))
        assertThat(GateState.decode(state.encode())).isEqualTo(state)
        assertThat(GateState.decode("{broken")).isEqualTo(GateState())
        assertThat(GateState.decode(null)).isEqualTo(GateState())
    }
}
