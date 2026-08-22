package com.iptv.tv.feature.playlists

import com.iptv.tv.core.common.DispatcherProvider
import com.iptv.tv.core.model.CatalogNavigationState
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.Playlist
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PlaylistCatalogCandidate(
    val navigation: PlaylistCatalogNavigationSession,
    val snapshot: PlaylistCatalogSnapshot
)

interface PlaylistCatalogBuilder {
    suspend fun build(
        playlist: Playlist,
        channels: List<Channel>,
        checkpoint: CatalogNavigationState?
    ): PlaylistCatalogCandidate

    suspend fun restore(
        candidate: PlaylistCatalogCandidate,
        checkpoint: CatalogNavigationState
    ): PlaylistCatalogCandidate
}

class DefaultPlaylistCatalogBuilder @Inject constructor(
    private val dispatcherProvider: DispatcherProvider
) : PlaylistCatalogBuilder {
    private val buildMutex = Mutex()

    override suspend fun build(
        playlist: Playlist,
        channels: List<Channel>,
        checkpoint: CatalogNavigationState?
    ): PlaylistCatalogCandidate = serializedOnDefault {
        PlaylistCatalogNavigationSession.create(
            playlist = playlist,
            channels = channels,
            previousCheckpoint = checkpoint
        ).toCandidate()
    }

    override suspend fun restore(
        candidate: PlaylistCatalogCandidate,
        checkpoint: CatalogNavigationState
    ): PlaylistCatalogCandidate = serializedOnDefault {
        candidate.navigation.restored(checkpoint).toCandidate()
    }

    private suspend fun serializedOnDefault(
        block: () -> PlaylistCatalogCandidate
    ): PlaylistCatalogCandidate = buildMutex.withLock {
        withContext(dispatcherProvider.default) { block() }
    }

    private fun PlaylistCatalogNavigationSession.toCandidate() = PlaylistCatalogCandidate(
        navigation = this,
        snapshot = snapshot()
    )
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class PlaylistCatalogBuilderModule {
    @Binds
    @ViewModelScoped
    abstract fun bindPlaylistCatalogBuilder(
        implementation: DefaultPlaylistCatalogBuilder
    ): PlaylistCatalogBuilder
}
