package com.iptv.tv.core.data.di

import com.iptv.tv.core.common.DefaultDispatcherProvider
import com.iptv.tv.core.common.DispatcherProvider
import com.iptv.tv.core.data.repository.ChannelMetadataRepositoryImpl
import com.iptv.tv.core.data.repository.CoalescingEngineRepository
import com.iptv.tv.core.data.repository.DiagnosticsRepositoryImpl
import com.iptv.tv.core.data.repository.DownloadRepositoryImpl
import com.iptv.tv.core.data.repository.EpgSettingsRepositoryImpl
import com.iptv.tv.core.data.repository.FavoritesRepositoryFacade
import com.iptv.tv.core.data.repository.HistoryRepositoryImpl
import com.iptv.tv.core.data.repository.PlaylistEditorRepositoryImpl
import com.iptv.tv.core.data.repository.ProviderAccountRepositoryImpl
import com.iptv.tv.core.data.repository.RecordingRepositoryImpl
import com.iptv.tv.core.data.repository.ScannerRepositoryImpl
import com.iptv.tv.core.data.repository.SettingsRepositoryImpl
import com.iptv.tv.core.data.repository.StreamingUrlPlaylistRepository
import com.iptv.tv.core.data.repository.TvHomeIntegrationRepositoryImpl
import com.iptv.tv.core.domain.repository.ChannelMetadataRepository
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.DownloadRepository
import com.iptv.tv.core.domain.repository.EngineRepository
import com.iptv.tv.core.domain.repository.EpgSettingsRepository
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.PlaylistEditorRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.ProviderAccountRepository
import com.iptv.tv.core.domain.repository.RecordingRepository
import com.iptv.tv.core.domain.repository.ScannerRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.domain.repository.TvHomeIntegrationRepository
import com.iptv.tv.core.parser.M3uParser
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {
    @Binds
    @Singleton
    abstract fun bindScannerRepository(impl: ScannerRepositoryImpl): ScannerRepository

    @Binds
    @Singleton
    abstract fun bindPlaylistRepository(impl: StreamingUrlPlaylistRepository): PlaylistRepository

    @Binds
    @Singleton
    abstract fun bindPlaylistEditorRepository(impl: PlaylistEditorRepositoryImpl): PlaylistEditorRepository

    @Binds
    @Singleton
    abstract fun bindProviderAccountRepository(impl: ProviderAccountRepositoryImpl): ProviderAccountRepository

    @Binds
    @Singleton
    abstract fun bindFavoritesRepository(impl: FavoritesRepositoryFacade): FavoritesRepository

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds
    @Singleton
    abstract fun bindDiagnosticsRepository(impl: DiagnosticsRepositoryImpl): DiagnosticsRepository

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    @Binds
    @Singleton
    abstract fun bindRecordingRepository(impl: RecordingRepositoryImpl): RecordingRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindEpgSettingsRepository(impl: EpgSettingsRepositoryImpl): EpgSettingsRepository

    @Binds
    @Singleton
    abstract fun bindEngineRepository(impl: CoalescingEngineRepository): EngineRepository

    @Binds
    @Singleton
    abstract fun bindTvHomeIntegrationRepository(impl: TvHomeIntegrationRepositoryImpl): TvHomeIntegrationRepository

    @Binds
    @Singleton
    abstract fun bindChannelMetadataRepository(impl: ChannelMetadataRepositoryImpl): ChannelMetadataRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider

    @Provides
    @Singleton
    fun provideM3uParser(): M3uParser = M3uParser()
}
