package org.duzgun.eksiengelplus.feature.lists.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.duzgun.eksiengelplus.feature.lists.ListSyncStore
import org.duzgun.eksiengelplus.feature.lists.RelationSource
import org.duzgun.eksiengelplus.feature.lists.RoomListSyncStore
import org.duzgun.eksiengelplus.feature.lists.ScrapeRelationSource

/**
 * The two seams that keep ListSyncer a pure-JVM class: paging and persistence are
 * interfaces here and concrete only at the edge.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ListsModule {

    @Binds @Singleton
    abstract fun relationSource(impl: ScrapeRelationSource): RelationSource

    @Binds @Singleton
    abstract fun listSyncStore(impl: RoomListSyncStore): ListSyncStore
}
