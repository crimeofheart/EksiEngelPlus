package org.duzgun.eksiengelplus.feature.settings.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.RegistrationDateCacheEntity
import org.duzgun.eksiengelplus.feature.settings.Maintenance

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    /**
     * Binds [Maintenance] to the real database.
     *
     * clearAllTables() rather than a list of per-table deletes: it is one
     * transaction, and a table added later is covered without anyone
     * remembering to extend a list here. Configuration survives it -- that lives
     * in DataStore, and a user clearing storage did not ask to lose their
     * settings.
     */
    @Provides @Singleton
    fun maintenance(
        db: EksiDatabase,
        @ApplicationContext context: Context,
    ): Maintenance = Maintenance(
        liveOperations = { db.checkpoints().liveCount() },
        cacheTotal = { db.registrationDates().size() },
        cacheExpired = { cutoff -> db.registrationDates().expiredCount(cutoff) },
        // The journal counts. A database idle at a few hundred KB carries a WAL
        // several times that after a big sync, and a figure that omitted it
        // would disagree with the platform's own storage screen.
        databaseBytes = {
            listOf("", "-wal", "-shm")
                .map { context.getDatabasePath(EksiDatabase.NAME + it) }
                .filter { it.exists() }
                .sumOf { it.length() }
        },
        clearCacheRows = { db.registrationDates().clear() },
        clearAllRows = { db.clearAllTables() },
        ttlMillis = RegistrationDateCacheEntity.TTL_MS,
    )
}
