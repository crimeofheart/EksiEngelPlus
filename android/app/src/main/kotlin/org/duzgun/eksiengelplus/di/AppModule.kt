package org.duzgun.eksiengelplus.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.duzgun.eksiengelplus.datastore.ConfigRepository
import org.duzgun.eksiengelplus.datastore.Stores

/**
 * Config lives here rather than in :core:datastore because that module carries no
 * Hilt plugin -- it is a plain library, usable from a test or a harness without a
 * graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun configRepository(@ApplicationContext context: Context): ConfigRepository =
        Stores.configRepository(context)
}
