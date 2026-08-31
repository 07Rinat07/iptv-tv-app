from pathlib import Path
import re

PATH = Path("core/data/src/main/java/com/iptv/tv/core/data/repository/Repositories.kt")
text = PATH.read_text()


def replace_exact(old: str, new: str, expected: int = 1) -> None:
    global text
    actual = text.count(old)
    if actual != expected:
        raise SystemExit(f"expected {expected} occurrences, found {actual}: {old[:120]!r}")
    text = text.replace(old, new)


replace_exact(
    "import com.iptv.tv.core.database.dao.ChannelDao\n",
    "import com.iptv.tv.core.database.dao.ChannelDao\n"
    "import com.iptv.tv.core.database.dao.EpgSnapshotDao\n",
)
replace_exact(
    "import kotlinx.coroutines.sync.Semaphore\nimport kotlinx.coroutines.sync.withPermit\n",
    "import kotlinx.coroutines.sync.Mutex\n"
    "import kotlinx.coroutines.sync.Semaphore\n"
    "import kotlinx.coroutines.sync.withLock\n"
    "import kotlinx.coroutines.sync.withPermit\n",
)
replace_exact(
    "    private val syncLogDao: SyncLogDao,\n    private val parser: M3uParser,\n",
    "    private val syncLogDao: SyncLogDao,\n"
    "    private val epgSnapshotDao: EpgSnapshotDao,\n"
    "    private val parser: M3uParser,\n",
)
replace_exact(
    "    private val epgLoadLock = Any()\n",
    "    private val epgLoadMutex = Mutex()\n",
)
replace_exact(
    "            epgCache.remove(source)\n            epgFailureBackoff.remove(source)\n",
    "            epgCache.remove(source)\n"
    "            epgFailureBackoff.remove(source)\n"
    "            deleteStoredEpgSnapshotBestEffort(source)\n",
)
replace_exact(
    "loadEpgCandidatesFreshFirst(",
    "loadEpgCandidatesFreshFirstSuspending(",
    expected=3,
)
replace_exact(
    "            loadFresh = ::getOrLoadXmlTv,\n"
    "            captureStaleFallback = ::staleEpgEntryForActiveTransientBackoff,\n",
    "            loadFresh = { url -> getOrLoadXmlTv(url) },\n"
    "            captureStaleFallback = { url -> staleEpgEntryForActiveTransientBackoff(url) },\n",
    expected=3,
)

new_loader = r'''    private suspend fun getOrLoadXmlTv(url: String): EpgCacheEntry {
        val now = System.currentTimeMillis()
        freshEpgEntry(url, now)?.let { return it }
        hydrateStoredEpgEntry(url, now)?.let { restored ->
            val freshness = EpgStaleFallbackPolicy.freshness(
                loadedAtMs = restored.loadedAtMs,
                nowMs = now,
                freshTtlMs = EPG_CACHE_TTL_MS,
                maxStaleAgeMs = EPG_STALE_FALLBACK_MAX_AGE_MS
            )
            if (freshness == EpgCacheFreshness.FRESH) return restored
        }
        epgFailureBackoff.active(url)?.let { failure ->
            throw IOException("EPG temporarily unavailable: ${failure.reason}")
        }

        return epgLoadMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            freshEpgEntry(url, lockedNow)?.let { return@withLock it }
            hydrateStoredEpgEntry(url, lockedNow)?.let { restored ->
                val freshness = EpgStaleFallbackPolicy.freshness(
                    loadedAtMs = restored.loadedAtMs,
                    nowMs = lockedNow,
                    freshTtlMs = EPG_CACHE_TTL_MS,
                    maxStaleAgeMs = EPG_STALE_FALLBACK_MAX_AGE_MS
                )
                if (freshness == EpgCacheFreshness.FRESH) return@withLock restored
            }
            epgFailureBackoff.active(url)?.let { failure ->
                throw IOException("EPG temporarily unavailable: ${failure.reason}")
            }

            purgeExpiredEpgCache(lockedNow)

            try {
                ensureEpgHeapHeadroom(EPG_MIN_START_HEADROOM_BYTES)
                val parsed = epgClient.newCall(
                    Request.Builder()
                        .url(url)
                        .get()
                        .build()
                ).execute().use { response ->
                    if (!response.isSuccessful) throw EpgHttpStatusException(response.code)
                    val body = response.body ?: throw EpgMalformedXmlException("Empty EPG body")
                    val contentLength = body.contentLength()
                    if (contentLength > MAX_EPG_INPUT_BYTES) {
                        throw EpgInputLimitExceededException(
                            maxBytes = MAX_EPG_INPUT_BYTES,
                            observedBytes = contentLength
                        )
                    }
                    try {
                        EpgBoundedInputStream(
                            input = body.byteStream(),
                            maxBytes = MAX_EPG_INPUT_BYTES
                        ).use(::parseXmlTv)
                    } catch (failure: IOException) {
                        throw failure
                    } catch (failure: Exception) {
                        throw EpgMalformedXmlException(
                            message = "Invalid XMLTV: ${failure.message ?: failure.javaClass.simpleName}",
                            cause = failure
                        )
                    }
                }

                epgFailureBackoff.remove(url)
                val entry = EpgCacheEntry(
                    loadedAtMs = System.currentTimeMillis(),
                    data = parsed
                )
                putEpgCache(url = url, entry = entry)
                persistEpgSnapshotBestEffort(url = url, entry = entry)
                entry
            } catch (_: OutOfMemoryError) {
                epgCache.clear()
                epgFailureBackoff.record(
                    url = url,
                    reason = "EPG aborted because heap headroom was exhausted",
                    retryAfterMs = EPG_LOW_MEMORY_BACKOFF_MS,
                    kind = EpgFailureKind.LOW_MEMORY
                )
                throw EpgLowMemoryException("EPG deferred: insufficient heap headroom")
            } catch (failure: CancellationException) {
                throw failure
            } catch (throwable: Exception) {
                val failure = throwable as? IOException
                    ?: EpgMalformedXmlException(
                        message = "Unable to parse EPG: ${throwable.message ?: throwable.javaClass.simpleName}",
                        cause = throwable
                    )
                val failureKind = classifyEpgFailure(failure)
                epgFailureBackoff.record(
                    url = url,
                    reason = failure.message ?: failure.javaClass.simpleName,
                    retryAfterMs = epgFailureBackoffMs(failureKind),
                    kind = failureKind
                )
                if (!EpgStaleFallbackPolicy.allowsStale(failureKind)) {
                    epgCache.remove(url)
                }
                if (
                    failureKind == EpgFailureKind.PERMANENT_HTTP ||
                    failureKind == EpgFailureKind.MALFORMED
                ) {
                    deleteStoredEpgSnapshotBestEffort(url)
                }
                throw failure
            }
        }
    }
'''
text, count = re.subn(
    r"    private fun getOrLoadXmlTv\(url: String\): EpgCacheEntry \{.*?(?=\n    private fun freshEpgEntry)",
    new_loader.rstrip(),
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit(f"getOrLoadXmlTv replacement count={count}")

persistence_helpers = r'''
    private suspend fun hydrateStoredEpgEntry(url: String, now: Long): EpgCacheEntry? {
        epgCache[url]?.let { return it }
        if (heapHeadroomBytes() < EPG_MIN_PARSE_HEADROOM_BYTES) return null

        val stored = try {
            epgSnapshotDao.readSnapshot(url)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: OutOfMemoryError) {
            epgCache.clear()
            return null
        } catch (_: Exception) {
            return null
        } ?: return null

        val freshness = EpgStaleFallbackPolicy.freshness(
            loadedAtMs = stored.source.loadedAtMs,
            nowMs = now,
            freshTtlMs = EPG_CACHE_TTL_MS,
            maxStaleAgeMs = EPG_STALE_FALLBACK_MAX_AGE_MS
        )
        if (freshness == EpgCacheFreshness.EXPIRED) {
            deleteStoredEpgSnapshotBestEffort(url)
            return null
        }

        return try {
            val payload = EpgPersistentSnapshotMapper.restore(stored)
            if (payload.sourceUrl != url) {
                deleteStoredEpgSnapshotBestEffort(url)
                null
            } else {
                EpgCacheEntry(
                    loadedAtMs = payload.loadedAtMs,
                    data = buildXmlTvData(
                        channelDisplayNames = payload.channelDisplayNames,
                        programsByChannel = payload.programsByChannel,
                        declaredChannelIds = payload.channelDisplayNames.keys
                    )
                ).also { entry -> putEpgCache(url = url, entry = entry) }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: OutOfMemoryError) {
            epgCache.clear()
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun persistEpgSnapshotBestEffort(url: String, entry: EpgCacheEntry) {
        try {
            val payload = EpgPersistentSnapshotPayload(
                sourceUrl = url,
                loadedAtMs = entry.loadedAtMs,
                channelDisplayNames = entry.data.channelDisplayNames,
                programsByChannel = entry.data.programsByChannel
            )
            epgSnapshotDao.replaceSnapshot(
                source = EpgPersistentSnapshotMapper.source(payload),
                displayNames = EpgPersistentSnapshotMapper.displayNames(payload),
                programs = EpgPersistentSnapshotMapper.programs(payload)
            )
            pruneStoredEpgSnapshotsBestEffort(keepSourceUrl = url)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: OutOfMemoryError) {
            // Parsed EPG is already valid in RAM; persistence must never turn it into a failure.
        } catch (_: Exception) {
            // Durable cache is an optimization. Keep the successful live result in RAM.
        }
    }

    private suspend fun pruneStoredEpgSnapshotsBestEffort(keepSourceUrl: String) {
        try {
            val sources = epgSnapshotDao.getSourcesOldestFirst()
            val excess = (sources.size - MAX_PERSISTED_EPG_SNAPSHOTS).coerceAtLeast(0)
            if (excess == 0) return
            sources
                .asSequence()
                .filter { it.sourceUrl != keepSourceUrl }
                .take(excess)
                .forEach { source -> epgSnapshotDao.deleteSnapshot(source.sourceUrl) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Best effort only; the next successful write will retry pruning.
        }
    }

    private suspend fun deleteStoredEpgSnapshotBestEffort(url: String) {
        try {
            epgSnapshotDao.deleteSnapshot(url)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Durable invalidation failure must not break the user action or live EPG path.
        }
    }
'''
marker = "\n    private fun epgFailureBackoffMs(failureKind: EpgFailureKind): Long = when (failureKind) {"
if text.count(marker) != 1:
    raise SystemExit("epgFailureBackoffMs marker mismatch")
text = text.replace(marker, "\n" + persistence_helpers.strip("\n") + marker)

parse_tail = r'''        programsByChannel.values.forEach(::sortAndDeduplicateProgramsInPlace)
        return buildXmlTvData(
            channelDisplayNames = channelDisplayNames,
            programsByChannel = programsByChannel,
            declaredChannelIds = declaredChannelIds
        )
    }

    private fun buildXmlTvData(
        channelDisplayNames: Map<String, Set<String>>,
        programsByChannel: Map<String, List<EpgProgram>>,
        declaredChannelIds: Iterable<String>
    ): XmlTvData {
        val knownChannelIds = EpgXmlTvChannelIndexPolicy.knownChannelIds(
            declaredChannelIds = declaredChannelIds,
            programmedChannelIds = programsByChannel.keys
        )
        val channelIdByLowercase = knownChannelIds
            .associateByFirst { it.trim().lowercase(Locale.ROOT) }
        val channelIdByTextKey = knownChannelIds
            .associateByFirst { normalizeTextKey(it) }
        val channelIdsByTextKey = knownChannelIds.mapNotNull { channelId ->
            normalizeTextKey(channelId)
                .takeIf { it.isNotBlank() }
                ?.let { normalizedKey -> normalizedKey to channelId }
        }
        val displayNameAliasIndex = EpgDisplayNameMatchPolicy.buildIndex(
            channelDisplayNames
                .entries
                .asSequence()
                .flatMap { (channelId, names) ->
                    names.asSequence().map { displayName -> normalizeTextKey(displayName) to channelId }
                }
                .filter { (key, _) -> key.isNotBlank() }
                .toList()
        )

        return XmlTvData(
            channelDisplayNames = channelDisplayNames,
            programsByChannel = programsByChannel,
            channelIdByLowercase = channelIdByLowercase,
            channelIdByTextKey = channelIdByTextKey,
            channelIdsByTextKey = channelIdsByTextKey,
            displayNameAliasIndex = displayNameAliasIndex
        )
    }
'''
text, count = re.subn(
    r"        programsByChannel\.values\.forEach\(::sortAndDeduplicateProgramsInPlace\).*?(?=\n    private fun readBoundedXmlText)",
    parse_tail.rstrip(),
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit(f"parse/index replacement count={count}")

replace_exact(
    "        const val MAX_EPG_CACHE_ENTRIES = 1\n",
    "        const val MAX_EPG_CACHE_ENTRIES = 1\n"
    "        const val MAX_PERSISTED_EPG_SNAPSHOTS = 1\n",
)

PATH.write_text(text)
