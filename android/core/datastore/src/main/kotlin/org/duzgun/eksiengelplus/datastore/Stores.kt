package org.duzgun.eksiengelplus.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val ConfigJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * A typed DataStore serializer over kotlinx-serialization.
 *
 * ignoreUnknownKeys means a downgrade after a new field is added reads the file
 * rather than wiping it. A corrupt file falls back to defaults instead of
 * crashing at startup -- config is not worth taking the app down for.
 */
private class JsonSerializer<T>(
    private val serializer: kotlinx.serialization.KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T = try {
        ConfigJson.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable datastore payload", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(ConfigJson.encodeToString(serializer, t).encodeToByteArray())
    }
}

class ConfigRepository(private val store: DataStore<EksiConfig>) {
    val config: Flow<EksiConfig> get() = store.data
    suspend fun update(transform: (EksiConfig) -> EksiConfig) { store.updateData(transform) }

    /**
     * Corrects a config written before its defaults were checked against the
     * extension.
     *
     * enableMute and enableProtectFollowedUsers shipped false here while
     * config.js has both true, and a stored false beats a corrected default --
     * so every install from before the fix keeps the wrong behaviour forever
     * unless something rewrites it.
     *
     * This does overwrite a deliberate choice, which is why it happens once and
     * is recorded in the version rather than run on every launch.
     */
    suspend fun migrate() {
        store.updateData { current ->
            if (current.configVersion >= EksiConfig.CURRENT_VERSION) {
                current
            } else {
                current.copy(
                    enableMute = true,
                    enableProtectFollowedUsers = true,
                    configVersion = EksiConfig.CURRENT_VERSION,
                )
            }
        }
    }
}

class IdentityRepository(private val store: DataStore<Identity>) {
    val identity: Flow<Identity> get() = store.data

    /** Generated once and never regenerated; callers must be able to rely on it. */
    suspend fun ensureCreated(now: Long, uuid: () -> String): Identity =
        store.updateData { current ->
            if (current.clientUid.isNotBlank()) current
            else current.copy(clientUid = uuid(), firstRunAtMillis = now)
        }

    suspend fun acceptConsent(version: Int) {
        store.updateData { it.copy(consentVersion = version) }
    }
}

object Stores {
    fun config(context: Context): DataStore<EksiConfig> = DataStoreFactory.create(
        serializer = JsonSerializer(EksiConfig.serializer(), EksiConfig()),
        produceFile = { context.dataStoreFile("config.json") },
    )

    /**
     * Lets a caller take the repository without naming DataStore, which would
     * otherwise force the dependency onto every module that only wants config.
     */
    fun configRepository(context: Context): ConfigRepository = ConfigRepository(config(context))

    fun identity(context: Context): DataStore<Identity> = DataStoreFactory.create(
        serializer = JsonSerializer(Identity.serializer(), Identity()),
        produceFile = { context.dataStoreFile("identity.json") },
    )

    private fun Context.dataStoreFile(name: String) = java.io.File(filesDir, "datastore/$name")
}
