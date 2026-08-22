package com.iptv.tv.feature.playlists

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.DispatcherProvider
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogNavigationState
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistSourceType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModelTest {
    private val mainDispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun burstPublishesOnlyLatestChannelsWhenCancelledBuildReturnsLate() = runTest(mainDispatcher) {
        val playlist = playlist(id = 1L)
        val playlists = MutableStateFlow(listOf(playlist))
        val channels = MutableStateFlow(listOf(channel(id = 1L, playlistId = playlist.id)))
        val builder = GateCatalogBuilder(ignoredCancellationRequests = 2)
        val viewModel = PlaylistsViewModel(
            playlistRepository = repository(playlists, mapOf(playlist.id to channels)),
            catalogBuilder = builder
        )
        runCurrent()
        val firstRequest = builder.requests.single()

        channels.value = listOf(channel(id = 2L, playlistId = playlist.id))
        runCurrent()
        channels.value = listOf(channel(id = 3L, playlistId = playlist.id))
        runCurrent()

        firstRequest.release.complete(Unit)
        runCurrent()

        assertNull(viewModel.uiState.value.catalog)
        assertEquals(listOf(2L), builder.requests.last().channels.map(Channel::id))

        builder.requests.last().release.complete(Unit)
        runCurrent()

        assertNull(viewModel.uiState.value.catalog)
        assertEquals(listOf(3L), builder.requests.last().channels.map(Channel::id))
        assertEquals(listOf(1L, 2L, 3L), builder.requests.map { it.channels.single().id })
        assertEquals(listOf(1L, 2L), builder.cancelledChannelIds)

        builder.requests.last().release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(3L), viewModel.uiState.value.catalog?.entries?.map { it.channelId })
    }

    @Test
    fun focusChangedDuringBuildIsRestoredIntoPublishedCandidate() = runTest(mainDispatcher) {
        val playlist = playlist(id = 1L)
        val initialChannels = listOf(
            channel(id = 1L, playlistId = playlist.id),
            channel(id = 2L, playlistId = playlist.id)
        )
        val playlists = MutableStateFlow(listOf(playlist))
        val channels = MutableStateFlow(initialChannels)
        val builder = GateCatalogBuilder()
        val viewModel = PlaylistsViewModel(
            playlistRepository = repository(playlists, mapOf(playlist.id to channels)),
            catalogBuilder = builder
        )
        runCurrent()
        builder.requests.single().release.complete(Unit)
        advanceUntilIdle()
        val initialSnapshot = viewModel.uiState.value.catalog!!
        val firstTarget = initialSnapshot.entries.first()
        val latestTarget = initialSnapshot.entries.last()

        channels.value = initialChannels + channel(id = 3L, playlistId = playlist.id)
        runCurrent()
        val rebuild = builder.requests.last()
        builder.blockRestores = true
        viewModel.focusCatalogNode(firstTarget.nodeId)
        rebuild.release.complete(Unit)
        runCurrent()
        val firstRestore = builder.restoreRequests.single()

        viewModel.focusCatalogNode(latestTarget.nodeId)
        firstRestore.release.complete(Unit)
        runCurrent()
        val latestRestore = builder.restoreRequests.last()

        assertEquals(2, builder.restoreRequests.size)
        assertEquals(
            firstTarget.nodeId,
            firstRestore.checkpoint.focusedChildIdByParent[initialSnapshot.currentNodeId]
        )
        assertEquals(
            latestTarget.nodeId,
            latestRestore.checkpoint.focusedChildIdByParent[initialSnapshot.currentNodeId]
        )

        latestRestore.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(latestTarget.nodeId, viewModel.uiState.value.catalog?.restoredFocusId)
        assertEquals(listOf(1L, 2L, 3L), viewModel.uiState.value.catalog?.entries?.map { it.channelId })
    }

    @Test
    fun publicationTokenRejectsEveryStaleOwnershipDimension() {
        val token = CatalogPublicationToken(
            bindingGeneration = 7L,
            channelRevision = 11L,
            playlistId = 13L
        )

        assertTrue(token.isCurrent(7L, 7L, 11L, 13L))
        assertFalse(token.isCurrent(6L, 7L, 11L, 13L))
        assertFalse(token.isCurrent(7L, 8L, 11L, 13L))
        assertFalse(token.isCurrent(7L, 7L, 12L, 13L))
        assertFalse(token.isCurrent(7L, 7L, 11L, 14L))
    }

    @Test
    fun playlistSwitchRejectsLateCandidateFromPreviousBinding() = runTest(mainDispatcher) {
        val firstPlaylist = playlist(id = 1L)
        val secondPlaylist = playlist(id = 2L)
        val playlists = MutableStateFlow(listOf(firstPlaylist, secondPlaylist))
        val firstChannels = MutableStateFlow(listOf(channel(id = 1L, playlistId = firstPlaylist.id)))
        val secondChannels = MutableStateFlow(listOf(channel(id = 2L, playlistId = secondPlaylist.id)))
        val builder = GateCatalogBuilder(ignoredCancellationRequests = 1)
        val viewModel = PlaylistsViewModel(
            playlistRepository = repository(
                playlists = playlists,
                channels = mapOf(
                    firstPlaylist.id to firstChannels,
                    secondPlaylist.id to secondChannels
                )
            ),
            catalogBuilder = builder
        )
        runCurrent()
        val staleRequest = builder.requests.single()

        viewModel.selectPlaylist(secondPlaylist.id)
        runCurrent()
        val currentRequest = builder.requests.last()
        staleRequest.release.complete(Unit)
        runCurrent()

        assertTrue(builder.requests.size >= 2)
        assertEquals(secondPlaylist.id, currentRequest.playlist.id)
        assertNull(viewModel.uiState.value.catalog)

        currentRequest.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(secondPlaylist.id, viewModel.uiState.value.catalog?.playlistId)
        assertEquals(listOf(2L), viewModel.uiState.value.catalog?.entries?.map { it.channelId })
    }

    @Test
    fun defaultBuilderSerializesBuildsAndDispatchesRestoreOffMain() = runTest(mainDispatcher) {
        val buildDispatcher = ManualQueueDispatcher()
        val builder = DefaultPlaylistCatalogBuilder(
            dispatcherProvider = object : DispatcherProvider {
                override val io = mainDispatcher
                override val default = buildDispatcher
                override val main = mainDispatcher
            }
        )

        val first = async {
            builder.build(
                playlist = playlist(id = 1L),
                channels = listOf(channel(id = 1L, playlistId = 1L)),
                checkpoint = null
            )
        }
        val second = async {
            builder.build(
                playlist = playlist(id = 2L),
                channels = listOf(channel(id = 2L, playlistId = 2L)),
                checkpoint = null
            )
        }
        runCurrent()

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(1, buildDispatcher.pendingCount)
        buildDispatcher.runNext()
        runCurrent()

        assertTrue(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(1, buildDispatcher.pendingCount)
        buildDispatcher.runNext()
        runCurrent()

        assertTrue(second.isCompleted)
        val firstCandidate = first.await()
        val restore = async {
            builder.restore(firstCandidate, firstCandidate.navigation.checkpoint())
        }
        runCurrent()

        assertFalse(restore.isCompleted)
        assertEquals(1, buildDispatcher.pendingCount)
        buildDispatcher.runNext()
        runCurrent()

        assertTrue(restore.isCompleted)
        assertEquals(1L, restore.await().snapshot.playlistId)
    }

    private fun repository(
        playlists: Flow<List<Playlist>>,
        channels: Map<Long, Flow<List<Channel>>>
    ): PlaylistRepository = mockk(relaxed = true) {
        every { observePlaylists() } returns playlists
        every { observeChannels(any()) } answers { channels.getValue(firstArg()) }
        coEvery { getPlaylistContentSummary(any()) } returns AppResult.Error("Summary is not used")
    }

    private class GateCatalogBuilder(
        private val ignoredCancellationRequests: Int = 0
    ) : PlaylistCatalogBuilder {
        val requests = mutableListOf<Request>()
        val cancelledChannelIds = mutableListOf<Long>()
        val restoreRequests = mutableListOf<RestoreRequest>()
        var blockRestores: Boolean = false

        override suspend fun build(
            playlist: Playlist,
            channels: List<Channel>,
            checkpoint: CatalogNavigationState?
        ): PlaylistCatalogCandidate {
            val request = Request(
                playlist = playlist,
                channels = channels,
                checkpoint = checkpoint,
                ignoreCancellation = requests.size < ignoredCancellationRequests
            )
            requests += request
            try {
                request.release.await()
            } catch (error: CancellationException) {
                cancelledChannelIds += channels.single().id
                if (!request.ignoreCancellation) throw error
                withContext(NonCancellable) { request.release.await() }
            }
            return PlaylistCatalogNavigationSession.create(
                playlist = playlist,
                channels = channels,
                previousCheckpoint = checkpoint
            ).toCandidate()
        }

        override suspend fun restore(
            candidate: PlaylistCatalogCandidate,
            checkpoint: CatalogNavigationState
        ): PlaylistCatalogCandidate {
            val request = RestoreRequest(checkpoint = checkpoint)
            restoreRequests += request
            if (blockRestores) request.release.await()
            return candidate.navigation.restored(checkpoint).toCandidate()
        }

        private fun PlaylistCatalogNavigationSession.toCandidate() = PlaylistCatalogCandidate(
            navigation = this,
            snapshot = snapshot()
        )

        data class Request(
            val playlist: Playlist,
            val channels: List<Channel>,
            val checkpoint: CatalogNavigationState?,
            val ignoreCancellation: Boolean,
            val release: CompletableDeferred<Unit> = CompletableDeferred()
        )

        data class RestoreRequest(
            val checkpoint: CatalogNavigationState,
            val release: CompletableDeferred<Unit> = CompletableDeferred()
        )
    }

    private class ManualQueueDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pendingCount: Int
            get() = tasks.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    private fun playlist(id: Long) = Playlist(
        id = id,
        name = "Playlist $id",
        sourceType = PlaylistSourceType.URL,
        source = "https://example.test/$id.m3u",
        scheduleHours = 12,
        lastSyncedAt = null,
        channelCount = 0,
        isCustom = false
    )

    private fun channel(id: Long, playlistId: Long) = Channel(
        id = id,
        playlistId = playlistId,
        tvgId = "channel-$id",
        name = "Channel $id",
        group = null,
        logo = null,
        streamUrl = "https://example.test/live/$id",
        health = ChannelHealth.UNKNOWN,
        orderIndex = id.toInt(),
        isHidden = false
    )
}
