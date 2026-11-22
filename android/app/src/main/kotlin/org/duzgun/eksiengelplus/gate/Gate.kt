package org.duzgun.eksiengelplus.gate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything the update gate remembers between runs. */
@Serializable
data class GateState(
    /** Highest minimum ever accepted; never goes down. */
    val stickyMin: Int = 0,
    /** Builds the newest accepted policy revokes. Not sticky: a revocation can be lifted. */
    val revoked: Set<Int> = emptySet(),
    val latest: Int = 0,
    val policyIssuedAt: Long = 0,
    /** The latest wall-clock time ever seen, so turning the clock back cannot revive an expired policy. */
    val maxWallMs: Long = 0,
    /** When the installed build first became blocked; the Play grace period counts from here. */
    val requiredSinceMs: Long = 0,
    val apkUrl: String? = null,
    val sha256: String? = null,
    val messages: Map<String, String> = emptyMap(),
) {
    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(text: String?): GateState =
            text?.let { runCatching { codec.decodeFromString(serializer(), it) }.getOrNull() } ?: GateState()
    }
}

/** What the gate lets the app do right now. */
sealed interface GateDecision {
    data object Allow : GateDecision

    /** A newer build exists; the app keeps working. */
    data class UpdateAvailable(val latest: Int) : GateDecision

    /** This build is below the minimum: update or close. */
    data class UpdateRequired(val minimum: Int, val sinceMs: Long) : GateDecision

    /** This very build was revoked: update or close. */
    data class Revoked(val versionCode: Int, val sinceMs: Long) : GateDecision

    val blocks: Boolean get() = this is UpdateRequired || this is Revoked
}

/**
 * The gate's rules: pure functions over [GateState], so they are tested off
 * device. Ported from OpenOTT/SportGuide TV without the offline and tamper rules.
 *
 * Mirror: accept() and evaluate() in frontend/app/assets/js/updateGate.js.
 */
object Gate {

    fun tick(state: GateState, nowWallMs: Long): GateState =
        state.copy(maxWallMs = maxOf(state.maxWallMs, nowWallMs))

    /**
     * Folds a verified [policy] into [state]. An older policy than the one
     * already accepted, or one past its own expiry, changes nothing: replaying an
     * old file can neither lower the minimum nor lift a revocation.
     */
    fun accept(state: GateState, policy: AppPolicy, channel: String, versionCode: Int, nowWallMs: Long): GateState {
        val current = tick(state, nowWallMs)
        if (policy.issuedAt < current.policyIssuedAt) return current
        if (policy.expiresAt != null && policy.expiresAt <= current.maxWallMs) return current
        val rules = policy.channels[channel]
        val min = maxOf(current.stickyMin, rules?.minVersionCode ?: 0)
        val revoked = rules?.revokedVersionCodes.orEmpty()
        val blocked = min > versionCode || versionCode in revoked
        val wasBlocked = current.stickyMin > versionCode || versionCode in current.revoked
        val since = when {
            !blocked -> 0L
            wasBlocked && current.requiredSinceMs > 0 -> current.requiredSinceMs
            else -> current.maxWallMs
        }
        return current.copy(
            stickyMin = min,
            revoked = revoked,
            latest = maxOf(rules?.latestVersionCode ?: 0, min),
            policyIssuedAt = policy.issuedAt,
            requiredSinceMs = since,
            apkUrl = rules?.apkUrl,
            sha256 = rules?.sha256,
            messages = policy.messages,
        )
    }

    fun evaluate(state: GateState, versionCode: Int): GateDecision = when {
        versionCode in state.revoked -> GateDecision.Revoked(versionCode, state.requiredSinceMs)
        state.stickyMin > versionCode -> GateDecision.UpdateRequired(state.stickyMin, state.requiredSinceMs)
        state.latest > versionCode -> GateDecision.UpdateAvailable(state.latest)
        else -> GateDecision.Allow
    }

    /** The maintainer's text, Turkish first: the app is Turkish. */
    fun message(state: GateState): String? = state.messages["tr"] ?: state.messages["en"]
}
