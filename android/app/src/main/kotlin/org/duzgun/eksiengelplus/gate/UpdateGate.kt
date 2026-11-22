package org.duzgun.eksiengelplus.gate

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.duzgun.eksiengelplus.BuildConfig
import org.duzgun.eksiengelplus.ops.runtime.RunGate

/**
 * The update gate on the device (openspec change app-update-gate), ported from
 * OpenOTT and SportGuide TV: keeps [GateState], fetches the signed policy, and
 * answers whether the app may run. BrowserActivity asks on every resume and
 * before it queues anything; OperationWorker asks before every run ([RunGate]).
 *
 * Only this build's own version blocks. With no policy published, or the server
 * unreachable, nothing is blocked.
 */
@Singleton
class UpdateGate internal constructor(
    private val context: Context,
    /** The policy file's text, or null; tests hand in their own. */
    private val fetchPolicy: (suspend () -> String?)?,
) : RunGate {
    @Inject constructor(@ApplicationContext context: Context) : this(context, null)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    /**
     * Its own client, not the Ekşi one: that one carries the WebView's cookies
     * and refuses redirects for its own reasons. Not pinned either -- the
     * signature is the trust anchor, and a pin that broke would only stop
     * updates from being enforced.
     */
    private val http by lazy {
        OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(15))
            .followRedirects(false)
            .build()
    }

    /** The policy section that applies to this build. */
    val channel: String get() = if (BuildConfig.SELF_UPDATE) "github" else "play"

    fun state(): GateState = synchronized(lock) {
        val state = Gate.tick(GateState.decode(prefs.getString(KEY_STATE, null)), System.currentTimeMillis())
        prefs.edit().putString(KEY_STATE, state.encode()).apply()
        state
    }

    fun decision(): GateDecision {
        val decision = Gate.evaluate(state(), BuildConfig.VERSION_CODE)
        // Play builds: a minimum raised before Play offers the new version
        // (staged rollouts take days) must not lock people out with nothing to
        // install. Until Play has it, or the grace runs out, the app keeps going.
        if (decision is GateDecision.UpdateRequired && Updater.fromStore(context) &&
            Updater.lastKnownAvailable != true &&
            System.currentTimeMillis() - decision.sinceMs < PLAY_GRACE_MS
        ) {
            return GateDecision.UpdateAvailable(decision.minimum)
        }
        return decision
    }

    override fun blocks(): Boolean = decision().blocks

    fun message(): String? = Gate.message(state())

    fun due(): Boolean =
        System.currentTimeMillis() - prefs.getLong(KEY_ATTEMPT, 0L) !in 0..CHECK_INTERVAL_MS

    /** Fetches and applies the policy. Network errors and bad signatures leave
     *  the state as it was; true when a valid policy was accepted. */
    suspend fun refresh(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && !due()) return@withContext false
        prefs.edit().putLong(KEY_ATTEMPT, System.currentTimeMillis()).apply()
        val body = runCatching {
            fetchPolicy?.invoke() ?: run {
                val request = Request.Builder().url(BuildConfig.POLICY_URL).cacheControl(CacheControl.FORCE_NETWORK).build()
                http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }
        }.getOrNull()
        val policy = body?.let { PolicyCodec.verify(it, APP_ID) } ?: return@withContext false
        // Whether Play can deliver, for the grace rule in decision().
        runCatching { Updater.refreshAvailability(context) }
        synchronized(lock) {
            val current = GateState.decode(prefs.getString(KEY_STATE, null))
            val next = Gate.accept(current, policy, channel, BuildConfig.VERSION_CODE, System.currentTimeMillis())
            prefs.edit().putString(KEY_STATE, next.encode()).apply()
        }
        true
    }

    companion object {
        const val CHECK_INTERVAL_MS = 6L * 3_600_000

        /** Play builds stay usable this long after a new minimum if Play has not offered the update yet. */
        const val PLAY_GRACE_MS = 7L * 24 * 3_600_000

        private const val PREFS = "update_gate"
        private const val KEY_STATE = "state"
        private const val KEY_ATTEMPT = "attempt"

        /** Debug builds follow the release policy. */
        val APP_ID: String get() = BuildConfig.APPLICATION_ID.removeSuffix(".debug")
    }
}

/** Hands the update gate to the operations worker. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GateModule {
    @Binds
    abstract fun runGate(gate: UpdateGate): RunGate
}
