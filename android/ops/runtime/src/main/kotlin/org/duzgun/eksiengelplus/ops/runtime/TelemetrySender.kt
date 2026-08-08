package org.duzgun.eksiengelplus.ops.runtime

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.TelemetryOutboxEntity

/**
 * Drains the telemetry outbox.
 *
 * The table has existed since android-foundations with nothing writing to it and
 * nothing reading it, so the app has never reported anything the extension does.
 *
 * An outbox rather than a direct post: a run finishes on the user's own network
 * conditions, and a failed report must never fail the operation that produced
 * it. Rows are attempted, backed off, and eventually abandoned -- telemetry is
 * not worth retrying forever.
 */
class TelemetryReporter(
    private val db: EksiDatabase,
    private val endpoint: String,
    private val apiKey: String,
) {

    /**
     * Records one finished run, if the user allows it.
     *
     * [sendData] is the user's setting; false means nothing is written at all,
     * rather than written and withheld, so opting out leaves no trail on the
     * device either.
     */
    suspend fun record(sendData: Boolean, bodyJson: String, now: Long) {
        if (!sendData) return
        db.telemetryOutbox().add(
            TelemetryOutboxEntity(
                endpoint = endpoint,
                bodyJson = bodyJson,
                attempts = 0,
                nextAttemptAt = now,
            ),
        )
    }
}

/**
 * Posts whatever is due.
 *
 * Silent when no key is configured. The shared key belongs in the build, not in
 * the repository, so a developer build simply never reports -- and an
 * unconfigured release reports nothing rather than posting requests the server
 * will reject.
 */
@HiltWorker
class TelemetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: EksiDatabase,
    private val http: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_API_KEY).orEmpty()
        if (key.isBlank()) return Result.success()

        val now = System.currentTimeMillis()
        val due = db.telemetryOutbox().due(now)
        if (due.isEmpty()) return Result.success()

        for (row in due) {
            val sent = runCatching { post(row, key) }.getOrDefault(false)
            if (sent) {
                db.telemetryOutbox().remove(row.id)
            } else if (row.attempts + 1 >= MAX_ATTEMPTS) {
                // Abandoned rather than retried forever: a report nobody will
                // ever read is not worth carrying on the device.
                db.telemetryOutbox().remove(row.id)
            } else {
                db.telemetryOutbox().add(
                    row.copy(
                        id = 0,
                        attempts = row.attempts + 1,
                        nextAttemptAt = now + BACKOFF_MS * (row.attempts + 1),
                    ),
                )
                db.telemetryOutbox().remove(row.id)
            }
        }
        return Result.success()
    }

    private fun post(row: TelemetryOutboxEntity, key: String): Boolean {
        val request = Request.Builder()
            .url(row.endpoint)
            .addHeader("Authorization", key)
            .post(row.bodyJson.toRequestBody(JSON))
            .build()
        return http.newCall(request).execute().use { it.isSuccessful }
    }

    companion object {
        const val KEY_API_KEY = "apiKey"
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_MS = 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun enqueue(wm: WorkManager, apiKey: String) {
            if (apiKey.isBlank()) return
            wm.enqueueUniqueWork(
                "eksiengel-telemetry",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TelemetryWorker>()
                    .setInputData(
                        androidx.work.Data.Builder().putString(KEY_API_KEY, apiKey).build(),
                    )
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
