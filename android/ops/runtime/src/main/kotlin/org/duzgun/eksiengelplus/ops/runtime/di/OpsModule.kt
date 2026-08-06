package org.duzgun.eksiengelplus.ops.runtime.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.eksi.client.RelationClient
import org.duzgun.eksiengelplus.eksi.client.ScrapeClient
import org.duzgun.eksiengelplus.ops.engine.FavActionTask
import org.duzgun.eksiengelplus.ops.engine.FollowActionTask
import org.duzgun.eksiengelplus.ops.engine.ListActionTask
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.OperationTask
import org.duzgun.eksiengelplus.ops.engine.PacerSnapshot
import org.duzgun.eksiengelplus.ops.engine.SingleActionTask
import org.duzgun.eksiengelplus.ops.engine.TargetRunner
import org.duzgun.eksiengelplus.ops.engine.TitleActionTask
import org.duzgun.eksiengelplus.ops.engine.UndoBanAllTask
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.ops.runtime.InMemoryCommandBus
import org.duzgun.eksiengelplus.ops.runtime.OperationCommandBus
import org.duzgun.eksiengelplus.ops.runtime.OperationTaskFactory
import org.duzgun.eksiengelplus.ops.runtime.OpsNotifier
import org.duzgun.eksiengelplus.ops.runtime.PacerStateStore

@Module
@InstallIn(SingletonComponent::class)
object OpsModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): EksiDatabase =
        Room.databaseBuilder(context, EksiDatabase::class.java, EksiDatabase.NAME).build()

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides @Singleton
    fun commandBus(): OperationCommandBus = InMemoryCommandBus()

    @Provides @Singleton
    fun notifier(@ApplicationContext context: Context): OpsNotifier = OpsNotifier(context)

    @Provides @Singleton
    fun runner(relations: RelationClient, scrape: ScrapeClient) = TargetRunner(relations, scrape)

    /**
     * Persisted so a killed process does not resume with a full token bucket and
     * fire a burst at a server that already counted those requests.
     *
     * Kept in SharedPreferences rather than Room: it is a single small record
     * written on every action, and routing that through a database transaction
     * would add contention on the hot path for no benefit.
     */
    @Provides @Singleton
    fun pacerState(@ApplicationContext context: Context): PacerStateStore =
        object : PacerStateStore {
            private val prefs = context.getSharedPreferences("pacer", Context.MODE_PRIVATE)

            override fun load(): PacerSnapshot? {
                if (!prefs.contains("tokens")) return null
                return PacerSnapshot(
                    tokens = prefs.getFloat("tokens", 0f).toDouble(),
                    lastRefillAt = prefs.getLong("lastRefillAt", 0),
                    intervalMs = prefs.getLong("intervalMs", 5_000),
                    blockedUntil = prefs.getLong("blockedUntil", 0),
                )
            }

            override fun save(snapshot: PacerSnapshot) {
                prefs.edit()
                    .putFloat("tokens", snapshot.tokens.toFloat())
                    .putLong("lastRefillAt", snapshot.lastRefillAt)
                    .putLong("intervalMs", snapshot.intervalMs)
                    .putLong("blockedUntil", snapshot.blockedUntil)
                    .apply()
            }
        }

    @Provides @Singleton
    fun taskFactory(runner: TargetRunner, scrape: ScrapeClient): OperationTaskFactory =
        object : OperationTaskFactory {
            override fun create(request: OperationRequest): OperationTask? = when (request.source) {
                BanSource.SINGLE -> SingleActionTask(runner)
                BanSource.LIST -> ListActionTask(runner)
                // Novice favourites are gated on enableNoobBan
                // (scrapingHandler.js:186); wired to config with the settings screen.
                BanSource.FAV -> FavActionTask(runner, scrape, includeNovices = { true })
                BanSource.FOLLOW -> FollowActionTask(runner, scrape)
                BanSource.TITLE -> TitleActionTask(runner, scrape)
                BanSource.UNDOBANALL -> UndoBanAllTask(runner, scrape)
                else -> null   // the remaining sources arrive with later changes
            }
        }
}
