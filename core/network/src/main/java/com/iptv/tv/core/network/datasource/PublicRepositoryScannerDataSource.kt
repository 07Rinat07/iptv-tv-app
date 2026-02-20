package com.iptv.tv.core.network.datasource

import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.model.PlaylistCandidate
import com.iptv.tv.core.model.ScannerProviderScope
import com.iptv.tv.core.model.ScannerSearchMode
import com.iptv.tv.core.model.ScannerSearchRequest
import com.iptv.tv.core.network.api.BitbucketApi
import com.iptv.tv.core.network.api.GitHubApi
import com.iptv.tv.core.network.api.GitLabApi
import com.iptv.tv.core.network.dto.BitbucketRepository
import com.iptv.tv.core.network.dto.BitbucketSourceEntry
import com.iptv.tv.core.network.dto.GitHubRepositoryItem
import com.iptv.tv.core.network.dto.GitHubTreeNode
import com.iptv.tv.core.network.dto.GitLabProject
import com.iptv.tv.core.network.dto.GitLabTreeItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import java.io.IOException
import java.net.UnknownHostException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.min
import kotlin.random.Random

class PublicRepositoryScannerDataSource @Inject constructor(
    private val gitHubApi: GitHubApi,
    private val gitLabApi: GitLabApi,
    private val bitbucketApi: BitbucketApi,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val rateLimitTracker = ProviderRateLimitTracker()
    private val providerLastError = ConcurrentHashMap<String, String>()

    suspend fun search(request: ScannerSearchRequest): List<PlaylistCandidate> {
        val normalized = request.normalized()
        val key = normalized.cacheKey()
        cache[key]?.takeIf { it.isFresh() }?.let { return it.items }

        val apiEnabled = normalized.searchMode != ScannerSearchMode.SEARCH_ENGINE
        val webEnabled = normalized.searchMode != ScannerSearchMode.DIRECT_API

        val shouldScanBitbucket =
            normalized.providerScope == ScannerProviderScope.BITBUCKET ||
                (
                    normalized.providerScope == ScannerProviderScope.ALL &&
                        !normalized.repoFilter.isNullOrBlank()
                    )

        val providerResults = if (apiEnabled) {
            coroutineScope {
                buildList {
                    if (normalized.providerScope == ScannerProviderScope.ALL ||
                        normalized.providerScope == ScannerProviderScope.GITHUB
                    ) {
                        add(async {
                            searchProvider(
                                provider = PROVIDER_GITHUB
                            ) { searchGitHub(normalized) }
                        })
                    }
                    if (normalized.providerScope == ScannerProviderScope.ALL ||
                        normalized.providerScope == ScannerProviderScope.GITLAB
                    ) {
                        add(async {
                            searchProvider(
                                provider = PROVIDER_GITLAB
                            ) { searchGitLab(normalized) }
                        })
                    }
                    if (shouldScanBitbucket) {
                        add(async {
                            searchProvider(
                                provider = PROVIDER_BITBUCKET
                            ) { searchBitbucket(normalized) }
                        })
                    }
                }.awaitAll()
            }
        } else {
            emptyList()
        }

        val candidates = providerResults.flatMap { it.items }
        val filtered = candidates
            .distinctBy { it.id }
            .filter { matchesFilters(it, normalized) }
            .sortedWith(
                compareByDescending<PlaylistCandidate> { relevanceScore(it, normalized) }
                    .thenByDescending { parseEpochOrNull(it.updatedAt) ?: 0L }
            )
            .take(normalized.limit)

        val webFallback = if (webEnabled && (normalized.searchMode == ScannerSearchMode.SEARCH_ENGINE || filtered.isEmpty())) {
            searchViaWebEngines(normalized)
        } else {
            WebFallbackResult(emptyList(), null)
        }

        val finalResults = (filtered + webFallback.items)
            .distinctBy { it.id }
            .filter { matchesFilters(it, normalized) }
            .sortedWith(
                compareByDescending<PlaylistCandidate> { relevanceScore(it, normalized) }
                    .thenByDescending { parseEpochOrNull(it.updatedAt) ?: 0L }
            )
            .take(normalized.limit)

        val failures = providerResults.filter { it.error != null }
        val allApiFailed = providerResults.isNotEmpty() && failures.size == providerResults.size
        if (finalResults.isEmpty()) {
            val apiFailureText = if (allApiFailed) {
                failures.joinToString(separator = "; ") { "${it.provider}: ${it.error}" }
            } else {
                ""
            }
            val webFailureText = webFallback.error.orEmpty()

            if (apiFailureText.isNotBlank() || (webEnabled && webFailureText.isNotBlank())) {
                val mergedError = listOfNotNull(
                    apiFailureText.takeIf { it.isNotBlank() },
                    webFailureText.takeIf { it.isNotBlank() }?.let { "web: $it" }
                ).joinToString("; ")
                throw IOException("Provider scan failed: $mergedError")
            }
        }

        cache[key] = CacheEntry(System.currentTimeMillis(), finalResults)
        return finalResults
    }

    private suspend fun searchProvider(
        provider: String,
        block: suspend () -> List<PlaylistCandidate>
    ): ProviderSearchResult {
        clearProviderError(provider)
        val items = runCatching { block() }
            .getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                setProviderError(
                    provider,
                    throwable.toLogSummary(maxDepth = 4)
                )
                emptyList()
            }

        return ProviderSearchResult(
            provider = provider,
            items = items,
            error = consumeProviderError(provider)
        )
    }

    private suspend fun searchGitHub(request: ScannerSearchRequest): List<PlaylistCandidate> {
        val byCodeSearch = runCatching { searchGitHubCode(request) }
            .getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                appendProviderError(
                    PROVIDER_GITHUB,
                    "code search: ${throwable.toLogSummary(maxDepth = 3)}"
                )
                emptyList()
            }
        if (byCodeSearch.size >= request.limit) return byCodeSearch.take(request.limit)

        val byRepositoryTree = runCatching { searchGitHubRepositoryTrees(request) }
            .getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                appendProviderError(
                    PROVIDER_GITHUB,
                    "repo tree: ${throwable.toLogSummary(maxDepth = 3)}"
                )
                emptyList()
            }
        return (byCodeSearch + byRepositoryTree)
            .distinctBy { it.id }
            .take(request.limit)
    }

    private suspend fun searchGitHubCode(request: ScannerSearchRequest): List<PlaylistCandidate> {
        val results = mutableListOf<PlaylistCandidate>()
        val perPage = request.limit.coerceAtMost(30)
        val extensions = listOf("m3u", "m3u8")

        extensions.forEach { extension ->
            val response = executeWithRetry(provider = PROVIDER_GITHUB) {
                gitHubApi.searchCode(
                    query = buildGitHubCodeQuery(request, extension),
                    perPage = perPage
                )
            } ?: return@forEach

            response.items.forEach { item ->
                val repository = item.repository.full_name
                val ref = item.repository.default_branch?.takeIf { it.isNotBlank() } ?: "HEAD"
                results += PlaylistCandidate(
                    id = "gh:$repository/${item.path}",
                    provider = PROVIDER_GITHUB,
                    repository = repository,
                    path = item.path,
                    name = item.name,
                    downloadUrl = "https://raw.githubusercontent.com/$repository/$ref/${encodePathForRaw(item.path)}",
                    updatedAt = item.repository.updated_at,
                    sizeBytes = item.size
                )
            }
        }

        return results.distinctBy { it.id }
    }

    private suspend fun searchGitHubRepositoryTrees(request: ScannerSearchRequest): List<PlaylistCandidate> {
        val response = executeWithRetry(provider = PROVIDER_GITHUB) {
            gitHubApi.searchRepositories(
                query = buildGitHubRepositoryQuery(request),
                perPage = request.limit.coerceAtMost(MAX_GITHUB_REPO_RESULTS)
            )
        } ?: return emptyList()

        val repositories = response.items.take(MAX_GITHUB_REPO_SCAN)
        if (repositories.isEmpty()) return emptyList()

        return coroutineScope {
            repositories
                .map { repo -> async { scanGitHubRepository(repo) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun scanGitHubRepository(repo: GitHubRepositoryItem): List<PlaylistCandidate> {
        val owner = repo.full_name.substringBefore('/', missingDelimiterValue = "")
        val name = repo.full_name.substringAfter('/', missingDelimiterValue = "")
        if (owner.isBlank() || name.isBlank()) return emptyList()

        val ref = repo.default_branch?.takeIf { it.isNotBlank() } ?: "HEAD"
        val treeResponse = executeWithRetry(provider = PROVIDER_GITHUB) {
            gitHubApi.getRepositoryTree(owner = owner, repo = name, treeRef = ref, recursive = 1)
        } ?: return emptyList()

        return treeResponse.tree
            .asSequence()
            .filter { it.type == "blob" && isPlaylistPath(it.path) }
            .take(MAX_FILES_PER_REPOSITORY)
            .map { node ->
                node.toGitHubCandidate(repository = repo.full_name, ref = ref, updatedAt = repo.updated_at)
            }
            .toList()
    }

    private fun GitHubTreeNode.toGitHubCandidate(
        repository: String,
        ref: String,
        updatedAt: String
    ): PlaylistCandidate {
        return PlaylistCandidate(
            id = "gh:$repository/$path",
            provider = PROVIDER_GITHUB,
            repository = repository,
            path = path,
            name = path.substringAfterLast('/'),
            downloadUrl = "https://raw.githubusercontent.com/$repository/$ref/${encodePathForRaw(path)}",
            updatedAt = updatedAt,
            sizeBytes = size
        )
    }

    private suspend fun searchGitLab(request: ScannerSearchRequest): List<PlaylistCandidate> {
        val byBlobs = runCatching { searchGitLabBlobs(request) }
            .getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                appendProviderError(
                    PROVIDER_GITLAB,
                    "blob search: ${throwable.toLogSummary(maxDepth = 3)}"
                )
                emptyList()
            }
        if (byBlobs.size >= request.limit) return byBlobs.take(request.limit)

        val byProjectTrees = runCatching { searchGitLabProjectTrees(request) }
            .getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                appendProviderError(
                    PROVIDER_GITLAB,
                    "project tree: ${throwable.toLogSummary(maxDepth = 3)}"
                )
                emptyList()
            }
        return (byBlobs + byProjectTrees)
            .distinctBy { it.id }
            .take(request.limit)
    }

    private suspend fun searchGitLabBlobs(request: ScannerSearchRequest): List<PlaylistCandidate> {
        val response = executeWithRetry(provider = PROVIDER_GITLAB) {
            gitLabApi.searchBlobs(
                search = "${request.query} m3u",
                perPage = request.limit.coerceAtMost(50)
            )
        } ?: return emptyList()

        return response.map { item ->
            val encodedPath = encodePathForApi(item.path)
            val refQuery = item.ref?.takeIf { it.isNotBlank() }?.let { "?ref=${urlEncode(it)}" }.orEmpty()
            PlaylistCandidate(
                id = "gl:${item.project_id}/${item.path}",
                provider = PROVIDER_GITLAB,
                repository = item.project_id.toString(),
                path = item.path,
                name = item.filename,
                downloadUrl = "https://gitlab.com/api/v4/projects/${item.project_id}/repository/files/$encodedPath/raw$refQuery",
                updatedAt = "",
                sizeBytes = null
            )
        }
    }

    private suspend fun searchGitLabProjectTrees(request: ScannerSearchRequest): List<PlaylistCandidate> {
        val response = executeWithRetry(provider = PROVIDER_GITLAB) {
            gitLabApi.searchProjects(
                search = buildGitLabProjectQuery(request),
                perPage = request.limit.coerceAtMost(MAX_GITLAB_PROJECT_RESULTS)
            )
        } ?: return emptyList()

        val projects = response.take(MAX_GITLAB_PROJECT_SCAN)
        if (projects.isEmpty()) return emptyList()

        return coroutineScope {
            projects
                .map { project -> async { scanGitLabProject(project) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun scanGitLabProject(project: GitLabProject): List<PlaylistCandidate> {
        val branch = project.default_branch?.takeIf { it.isNotBlank() } ?: "main"
        val treeItems = mutableListOf<GitLabTreeItem>()

        var page = 1
        while (page <= MAX_GITLAB_TREE_PAGES && treeItems.size < MAX_FILES_PER_REPOSITORY) {
            val pageResponse = executeWithRetry(provider = PROVIDER_GITLAB) {
                gitLabApi.getRepositoryTree(
                    projectId = project.id,
                    recursive = true,
                    perPage = GITLAB_TREE_PAGE_SIZE,
                    page = page
                )
            } ?: break

            if (pageResponse.isEmpty()) break

            treeItems += pageResponse
            if (pageResponse.size < GITLAB_TREE_PAGE_SIZE) break
            page += 1
        }

        return treeItems
            .asSequence()
            .filter { it.type == "blob" && isPlaylistPath(it.path) }
            .take(MAX_FILES_PER_REPOSITORY)
            .map { item ->
                PlaylistCandidate(
                    id = "gl:${project.id}/${item.path}",
                    provider = PROVIDER_GITLAB,
                    repository = project.path_with_namespace,
                    path = item.path,
                    name = item.name ?: item.path.substringAfterLast('/'),
                    downloadUrl = "https://gitlab.com/${project.path_with_namespace}/-/raw/$branch/${encodePathForRaw(item.path)}",
                    updatedAt = project.last_activity_at,
                    sizeBytes = null
                )
            }
            .toList()
    }

    private suspend fun searchBitbucket(request: ScannerSearchRequest): List<PlaylistCandidate> {
        val repoFilter = request.repoFilter?.trim().orEmpty()
        if (repoFilter.isBlank()) return emptyList()

        val workspace = repoFilter.substringBefore('/').trim()
        if (workspace.isBlank()) return emptyList()
        val repoHint = repoFilter.substringAfter('/', "").trim()

        val response = executeWithRetry(provider = PROVIDER_BITBUCKET) {
            bitbucketApi.listWorkspaceRepositories(
                workspace = workspace,
                pageLen = request.limit.coerceAtMost(MAX_BITBUCKET_REPO_RESULTS)
            )
        } ?: return emptyList()

        val repositories = response.values
            .asSequence()
            .filter { repo ->
                val matchesRepoHint = repoHint.isBlank() ||
                    repo.slug.contains(repoHint, ignoreCase = true) ||
                    repo.full_name.contains(repoFilter, ignoreCase = true)

                val haystack = "${repo.full_name} ${repo.slug}".lowercase()
                val matchesKeywords = request.keywords.isEmpty() || request.keywords.any { keyword ->
                    haystack.contains(keyword.lowercase())
                }
                matchesRepoHint && matchesKeywords
            }
            .take(MAX_BITBUCKET_REPO_SCAN)
            .toList()

        if (repositories.isEmpty()) return emptyList()

        return coroutineScope {
            repositories
                .map { repo -> async { scanBitbucketRepository(workspace = workspace, repository = repo) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun scanBitbucketRepository(
        workspace: String,
        repository: BitbucketRepository
    ): List<PlaylistCandidate> {
        val ref = repository.mainbranch?.name?.takeIf { it.isNotBlank() } ?: "master"
        val query = "path ~ \".m3u\" OR path ~ \".m3u8\""

        val firstPage = executeWithRetry(provider = PROVIDER_BITBUCKET) {
            bitbucketApi.listRepositorySource(
                workspace = workspace,
                repoSlug = repository.slug,
                ref = ref,
                pageLen = BITBUCKET_SOURCE_PAGE_SIZE,
                query = query
            )
        } ?: return emptyList()

        val files = mutableListOf<BitbucketSourceEntry>()
        files += firstPage.values

        var nextPageUrl = firstPage.next
        var page = 1
        while (!nextPageUrl.isNullOrBlank() && page < MAX_BITBUCKET_PAGES && files.size < MAX_FILES_PER_REPOSITORY) {
            val nextResponse = executeWithRetry(provider = PROVIDER_BITBUCKET) {
                bitbucketApi.listRepositorySourcePage(nextPageUrl!!)
            } ?: break

            files += nextResponse.values
            nextPageUrl = nextResponse.next
            page += 1
        }

        return files
            .asSequence()
            .filter { it.type == "commit_file" && isPlaylistPath(it.path) }
            .take(MAX_FILES_PER_REPOSITORY)
            .map { file ->
                PlaylistCandidate(
                    id = "bb:${repository.full_name}/${file.path}",
                    provider = PROVIDER_BITBUCKET,
                    repository = repository.full_name,
                    path = file.path,
                    name = file.path.substringAfterLast('/'),
                    downloadUrl = "https://bitbucket.org/${repository.full_name}/raw/$ref/${encodePathForRaw(file.path)}",
                    updatedAt = file.commit?.date ?: repository.updated_on,
                    sizeBytes = file.size
                )
            }
            .toList()
    }

    private suspend fun searchViaWebEngines(request: ScannerSearchRequest): WebFallbackResult {
        val queries = buildWebSearchQueries(request)
        if (queries.isEmpty()) {
            return WebFallbackResult(
                items = emptyList(),
                error = "web fallback skipped: empty query"
            )
        }

        val collected = linkedMapOf<String, PlaylistCandidate>()
        val errors = mutableListOf<String>()
        var probes = 0
        val engines = listOf(SearchEngine.DUCKDUCKGO, SearchEngine.BING)

        for (query in queries) {
            for (engine in engines) {
                if (collected.size >= request.limit) break

                val links = runCatching { searchLinksByEngine(engine, query) }
                    .getOrElse { throwable ->
                        val reason = throwable.toLogSummary(maxDepth = 3)
                        errors += "${engine.label}: $reason"
                        emptyList()
                    }

                if (links.isEmpty()) continue

                for (link in links.take(MAX_WEB_LINKS_PER_QUERY)) {
                    if (collected.size >= request.limit) break
                    if (probes >= MAX_WEB_PROBES) break

                    val candidate = candidateFromDiscoveredLink(link, request.providerScope) ?: continue
                    if (collected.containsKey(candidate.id)) continue

                    probes += 1
                    if (isLikelyPlaylistUrl(candidate.downloadUrl)) {
                        collected[candidate.id] = candidate
                    }
                }
            }
            if (collected.size >= request.limit || probes >= MAX_WEB_PROBES) {
                break
            }
        }

        return WebFallbackResult(
            items = collected.values.toList(),
            error = if (collected.isEmpty()) errors.joinToString("; ").takeIf { it.isNotBlank() } else null
        )
    }

    private fun buildWebSearchQueries(request: ScannerSearchRequest): List<String> {
        val base = request.query.trim().ifBlank { return emptyList() }
        val keywordTail = request.keywords.take(3).joinToString(" ")
        val searchBase = listOf(base, keywordTail).filter { it.isNotBlank() }.joinToString(" ").trim()

        val hostQueries = when (request.providerScope) {
            ScannerProviderScope.GITHUB -> listOf("site:github.com")
            ScannerProviderScope.GITLAB -> listOf("site:gitlab.com")
            ScannerProviderScope.BITBUCKET -> listOf("site:bitbucket.org")
            ScannerProviderScope.ALL -> listOf(
                "site:github.com",
                "site:gitlab.com",
                "site:bitbucket.org"
            )
        }

        val variations = mutableListOf<String>()
        hostQueries.forEach { site ->
            variations += "$searchBase $site m3u"
            variations += "$searchBase $site m3u8"
            variations += "$searchBase $site iptv playlist"
        }

        if (request.providerScope == ScannerProviderScope.ALL) {
            variations += "$searchBase iptv m3u github"
            variations += "$searchBase iptv m3u gitlab"
        }

        return variations
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 4 }
            .distinct()
            .take(MAX_WEB_SEARCH_QUERIES)
    }

    private suspend fun searchLinksByEngine(
        engine: SearchEngine,
        query: String
    ): List<String> {
        val url = when (engine) {
            SearchEngine.DUCKDUCKGO ->
                "https://duckduckgo.com/html/?q=${urlEncode(query)}&kl=wt-wt"
            SearchEngine.BING ->
                "https://www.bing.com/search?q=${urlEncode(query)}&count=30&setlang=en-US"
        }

        val html = executeRawWithRetry(
            provider = "${PROVIDER_WEB}_${engine.id}",
            url = url
        ) ?: return emptyList()

        return extractLinksFromHtml(engine, html)
    }

    private suspend fun executeRawWithRetry(
        provider: String,
        url: String
    ): String? {
        var attempt = 0
        var lastError: String? = null
        while (attempt < MAX_RETRIES) {
            val attemptNo = attempt + 1
            val waitForRateLimit = rateLimitTracker.waitTime(provider)
            if (waitForRateLimit > 0) {
                delay(min(waitForRateLimit, MAX_RATE_LIMIT_WAIT_MS))
            }

            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.7,ru;q=0.5")
                    .build()

                val response = withTimeout(NETWORK_CALL_TIMEOUT_MS) {
                    okHttpClient.newCall(request).execute()
                }
                response.use { http ->
                    rateLimitTracker.update(provider, http.code, http.headers)
                    if (http.isSuccessful) {
                        val body = http.body?.string().orEmpty()
                        if (body.isBlank()) {
                            lastError = "HTTP ${http.code} empty body, attempt=$attemptNo/$MAX_RETRIES"
                        } else {
                            clearProviderError(provider)
                            return body
                        }
                    } else {
                        if (!isRetriable(http.code)) {
                            lastError = "HTTP ${http.code}, attempt=$attemptNo/$MAX_RETRIES"
                            setProviderError(provider, lastError!!)
                            return null
                        }
                        lastError = "HTTP ${http.code}, attempt=$attemptNo/$MAX_RETRIES, retriable=true"
                    }
                }

                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = computeDelayMs(attempt, headers = null)
                    setProviderError(provider, "$lastError; retryInMs=$delayMs")
                    delay(delayMs)
                }
            } catch (throwable: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Cancelled while executing raw request").also {
                        it.initCause(throwable)
                    }
                }
                lastError = "timeout attempt=$attemptNo/$MAX_RETRIES, provider=$provider, limitMs=$NETWORK_CALL_TIMEOUT_MS"
                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = computeDelayMs(attempt, headers = null)
                    setProviderError(provider, "$lastError; retryInMs=$delayMs")
                    delay(delayMs)
                }
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: IOException) {
                lastError = "network attempt=$attemptNo/$MAX_RETRIES, ${throwable.toLogSummary(maxDepth = 3)}"
                if (isNonRetriableNetworkError(throwable)) {
                    setProviderError(provider, "$lastError; retriable=false")
                    return null
                }
                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = computeDelayMs(attempt, headers = null)
                    setProviderError(provider, "$lastError; retryInMs=$delayMs")
                    delay(delayMs)
                }
            } catch (throwable: Throwable) {
                lastError = "unexpected attempt=$attemptNo/$MAX_RETRIES, ${throwable.toLogSummary(maxDepth = 3)}"
                setProviderError(provider, lastError!!)
                return null
            }
            attempt += 1
        }
        setProviderError(provider, lastError ?: "Unknown network error")
        return null
    }

    private fun extractLinksFromHtml(engine: SearchEngine, html: String): List<String> {
        val links = mutableListOf<String>()
        HTML_LINK_REGEX.findAll(html).forEach { match ->
            val rawLink = decodeHtmlEntities(match.groupValues[1]).trim()
            val normalized = normalizeSearchEngineLink(engine, rawLink) ?: return@forEach
            links += normalized
        }
        return links
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    private fun normalizeSearchEngineLink(engine: SearchEngine, rawLink: String): String? {
        if (rawLink.startsWith("javascript:", ignoreCase = true)) return null
        if (rawLink.startsWith("mailto:", ignoreCase = true)) return null

        val absolute = when {
            rawLink.startsWith("http://", ignoreCase = true) || rawLink.startsWith("https://", ignoreCase = true) -> rawLink
            rawLink.startsWith("//") -> "https:$rawLink"
            rawLink.startsWith("/") -> when (engine) {
                SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com$rawLink"
                SearchEngine.BING -> "https://www.bing.com$rawLink"
            }
            else -> return null
        }

        val parsed = absolute.toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase()

        if (host.contains("duckduckgo.com")) {
            parsed.queryParameter("uddg")?.let { return urlDecode(it) }
            return null
        }

        if (host.contains("bing.com")) {
            parsed.queryParameter("url")?.let { return urlDecode(it) }
            parsed.queryParameter("q")?.let { value ->
                if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
                    return urlDecode(value)
                }
            }
        }

        return absolute
    }

    private fun candidateFromDiscoveredLink(
        link: String,
        scope: ScannerProviderScope
    ): PlaylistCandidate? {
        val url = link.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase()

        return when {
            host == "github.com" || host == "raw.githubusercontent.com" ->
                buildGitHubCandidate(link, scope)
            host == "gitlab.com" ->
                buildGitLabCandidate(link, scope)
            host == "bitbucket.org" ->
                buildBitbucketCandidate(link, scope)
            else -> null
        }
    }

    private fun buildGitHubCandidate(link: String, scope: ScannerProviderScope): PlaylistCandidate? {
        if (scope == ScannerProviderScope.GITLAB || scope == ScannerProviderScope.BITBUCKET) return null
        val parsed = link.toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase()
        val segments = parsed.pathSegments.filter { it.isNotBlank() }

        val repository: String
        val path: String
        val rawUrl: String

        when {
            host == "raw.githubusercontent.com" && segments.size >= 4 -> {
                val owner = segments[0]
                val repo = segments[1]
                val ref = segments[2]
                path = segments.drop(3).joinToString("/")
                if (!isPlaylistPath(path)) return null
                repository = "$owner/$repo"
                rawUrl = "https://raw.githubusercontent.com/$repository/$ref/${encodePathForRaw(path)}"
            }
            host == "github.com" && segments.size >= 5 && (segments[2] == "blob" || segments[2] == "raw") -> {
                val owner = segments[0]
                val repo = segments[1]
                val ref = segments[3]
                path = segments.drop(4).joinToString("/")
                if (!isPlaylistPath(path)) return null
                repository = "$owner/$repo"
                rawUrl = "https://raw.githubusercontent.com/$repository/$ref/${encodePathForRaw(path)}"
            }
            else -> return null
        }

        return PlaylistCandidate(
            id = "web:gh:$repository/$path",
            provider = PROVIDER_GITHUB,
            repository = repository,
            path = path,
            name = path.substringAfterLast('/'),
            downloadUrl = rawUrl,
            updatedAt = "",
            sizeBytes = null
        )
    }

    private fun buildGitLabCandidate(link: String, scope: ScannerProviderScope): PlaylistCandidate? {
        if (scope == ScannerProviderScope.GITHUB || scope == ScannerProviderScope.BITBUCKET) return null
        val parsed = link.toHttpUrlOrNull() ?: return null
        val segments = parsed.pathSegments.filter { it.isNotBlank() }
        val separator = segments.indexOf("-")
        if (separator <= 0 || separator >= segments.lastIndex) return null
        if (separator + 3 >= segments.size) return null

        val namespace = segments.take(separator).joinToString("/")
        val action = segments[separator + 1]
        val ref = segments[separator + 2]
        val path = segments.drop(separator + 3).joinToString("/")
        if (!isPlaylistPath(path)) return null

        val rawUrl = when (action) {
            "raw" -> "https://gitlab.com/$namespace/-/raw/$ref/${encodePathForRaw(path)}"
            "blob" -> "https://gitlab.com/$namespace/-/raw/$ref/${encodePathForRaw(path)}"
            else -> return null
        }

        return PlaylistCandidate(
            id = "web:gl:$namespace/$path",
            provider = PROVIDER_GITLAB,
            repository = namespace,
            path = path,
            name = path.substringAfterLast('/'),
            downloadUrl = rawUrl,
            updatedAt = "",
            sizeBytes = null
        )
    }

    private fun buildBitbucketCandidate(link: String, scope: ScannerProviderScope): PlaylistCandidate? {
        if (scope == ScannerProviderScope.GITHUB || scope == ScannerProviderScope.GITLAB) return null
        val parsed = link.toHttpUrlOrNull() ?: return null
        val segments = parsed.pathSegments.filter { it.isNotBlank() }
        if (segments.size < 5) return null

        val workspace = segments[0]
        val repositoryName = segments[1]
        val action = segments[2]
        val ref = segments[3]
        val path = segments.drop(4).joinToString("/")
        if (!isPlaylistPath(path)) return null
        if (action != "src" && action != "raw") return null

        val repository = "$workspace/$repositoryName"
        val rawUrl = "https://bitbucket.org/$repository/raw/$ref/${encodePathForRaw(path)}"

        return PlaylistCandidate(
            id = "web:bb:$repository/$path",
            provider = PROVIDER_BITBUCKET,
            repository = repository,
            path = path,
            name = path.substringAfterLast('/'),
            downloadUrl = rawUrl,
            updatedAt = "",
            sizeBytes = null
        )
    }

    private suspend fun isLikelyPlaylistUrl(url: String): Boolean {
        val playlistPath = url.toHttpUrlOrNull()?.encodedPath ?: url
        if (!isPlaylistPath(playlistPath)) {
            return false
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-3072")
            .build()

        return runCatching {
            withTimeout(WEB_PROBE_TIMEOUT_MS) {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withTimeout false
                    val payload = response.body?.string().orEmpty()
                    if (payload.isBlank()) return@withTimeout false
                    val normalized = payload.lowercase()
                    if (normalized.contains("<html")) return@withTimeout false
                    normalized.contains("#extm3u") || normalized.contains("#extinf")
                }
            }
        }.getOrDefault(false)
    }

    private fun decodeHtmlEntities(raw: String): String {
        return raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x2F;", "/")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun urlDecode(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
        }.getOrDefault(value)
    }

    private suspend fun <T> executeWithRetry(
        provider: String,
        requestBlock: suspend () -> Response<T>
    ): T? {
        var attempt = 0
        var lastError: String? = null
        while (attempt < MAX_RETRIES) {
            val attemptNo = attempt + 1
            val waitForRateLimit = rateLimitTracker.waitTime(provider)
            if (waitForRateLimit > 0) {
                val waitMs = min(waitForRateLimit, MAX_RATE_LIMIT_WAIT_MS)
                setProviderError(
                    provider,
                    "rate-limit wait ${waitMs}ms before attempt=$attemptNo/$MAX_RETRIES"
                )
                delay(waitMs)
            }

            try {
                val response = withTimeout(NETWORK_CALL_TIMEOUT_MS) {
                    requestBlock()
                }
                rateLimitTracker.update(provider, response.code(), response.headers())

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body == null) {
                        lastError = "HTTP ${response.code()} empty body, attempt=$attemptNo/$MAX_RETRIES"
                        setProviderError(provider, lastError)
                        return null
                    }
                    clearProviderError(provider)
                    return body
                }

                if (!isRetriable(response.code())) {
                    lastError = formatHttpError(response = response, attemptNo = attemptNo, retry = false)
                    setProviderError(provider, lastError)
                    return null
                }

                lastError = formatHttpError(response = response, attemptNo = attemptNo, retry = true)
                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = computeDelayMs(attempt, response.headers())
                    setProviderError(provider, "$lastError; retryInMs=$delayMs")
                    delay(delayMs)
                }
            } catch (throwable: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Cancelled while executing provider request").also {
                        it.initCause(throwable)
                    }
                }
                lastError = "timeout attempt=$attemptNo/$MAX_RETRIES, provider=$provider, limitMs=$NETWORK_CALL_TIMEOUT_MS"
                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = computeDelayMs(attempt, headers = null)
                    setProviderError(provider, "$lastError; retryInMs=$delayMs")
                    delay(delayMs)
                }
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: IOException) {
                lastError = "network attempt=$attemptNo/$MAX_RETRIES, ${throwable.toLogSummary(maxDepth = 3)}"
                if (isNonRetriableNetworkError(throwable)) {
                    setProviderError(provider, "$lastError; retriable=false")
                    return null
                }
                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = computeDelayMs(attempt, headers = null)
                    setProviderError(provider, "$lastError; retryInMs=$delayMs")
                    delay(delayMs)
                }
            } catch (throwable: Throwable) {
                lastError = "unexpected attempt=$attemptNo/$MAX_RETRIES, ${throwable.toLogSummary(maxDepth = 3)}"
                setProviderError(provider, lastError)
                return null
            }
            attempt += 1
        }
        setProviderError(provider, lastError ?: "Unknown network error")
        return null
    }

    private fun <T> formatHttpError(
        response: Response<T>,
        attemptNo: Int,
        retry: Boolean
    ): String {
        val retryAfter = response.headers()["Retry-After"]
        val rateLimitRemaining = response.headers()["X-RateLimit-Remaining"]
        val reason = response.message().takeIf { it.isNotBlank() }?.take(80)
        return buildString {
            append("HTTP ")
            append(response.code())
            append(", attempt=")
            append(attemptNo)
            append("/")
            append(MAX_RETRIES)
            if (retry) {
                append(", retriable=true")
            }
            if (!reason.isNullOrBlank()) {
                append(", reason=")
                append(reason)
            }
            if (!rateLimitRemaining.isNullOrBlank()) {
                append(", remaining=")
                append(rateLimitRemaining)
            }
            if (!retryAfter.isNullOrBlank()) {
                append(", retryAfter=")
                append(retryAfter)
            }
        }
    }

    private fun computeDelayMs(attempt: Int, headers: Headers?): Long {
        val retryAfter = headers
            ?.get("Retry-After")
            ?.toLongOrNull()
            ?.times(1000)
            ?: 0L
        val base = BASE_BACKOFF_MS * (1L shl attempt)
        val jitter = Random.nextLong(0, JITTER_MAX_MS)
        return maxOf(base + jitter, retryAfter).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun isNonRetriableNetworkError(throwable: IOException): Boolean {
        if (throwable is UnknownHostException) return true
        val message = throwable.toLogSummary(maxDepth = 3).lowercase()
        return message.contains("unknownhostexception") ||
            message.contains("unable to resolve host") ||
            message.contains("no address associated with hostname")
    }

    private fun matchesFilters(candidate: PlaylistCandidate, request: ScannerSearchRequest): Boolean {
        val repoFilter = request.repoFilter
        val pathFilter = request.pathFilter
        val minSizeBytes = request.minSizeBytes
        val maxSizeBytes = request.maxSizeBytes
        val updatedAfter = request.updatedAfterEpochMs

        val matchesRepo = repoFilter.isNullOrBlank() || candidate.repository.contains(repoFilter, ignoreCase = true)
        val matchesPath = pathFilter.isNullOrBlank() || candidate.path.contains(pathFilter, ignoreCase = true)
        val size = candidate.sizeBytes
        val matchesMinSize = minSizeBytes == null || (size != null && size >= minSizeBytes)
        val matchesMaxSize = maxSizeBytes == null || (size != null && size <= maxSizeBytes)
        val updated = parseEpochOrNull(candidate.updatedAt)
        val matchesUpdated = updatedAfter == null || (updated != null && updated >= updatedAfter)

        // Keywords are used as relevance boost instead of hard filter:
        // provider APIs already search in file/repository content, while local metadata
        // (name/path/repo) can miss these words and over-filter valid playlists.
        return matchesRepo && matchesPath && matchesMinSize && matchesMaxSize && matchesUpdated
    }

    private fun relevanceScore(candidate: PlaylistCandidate, request: ScannerSearchRequest): Int {
        val haystack = "${candidate.name} ${candidate.path} ${candidate.repository}".lowercase()
        val queryTokens = request.query
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()

        var score = 0
        queryTokens.forEach { token ->
            if (haystack.contains(token)) {
                score += 2
            }
        }
        request.keywords.forEach { keyword ->
            if (haystack.contains(keyword.lowercase())) {
                score += 3
            }
        }
        if (candidate.path.contains("iptv", ignoreCase = true)) {
            score += 1
        }
        return score
    }

    private fun buildGitHubCodeQuery(request: ScannerSearchRequest, extension: String): String {
        val base = buildList {
            add(request.query)
            add("extension:$extension")
            request.repoFilter?.takeIf { it.isNotBlank() }?.let { add("repo:$it") }
            request.pathFilter?.takeIf { it.isNotBlank() }?.let { add("path:$it") }
        }
        return base.joinToString(" ")
    }

    private fun buildGitHubRepositoryQuery(request: ScannerSearchRequest): String {
        val parts = buildList {
            add(request.query)
            request.repoFilter?.takeIf { it.isNotBlank() }?.let { add(it) }
            add("in:name,description,readme")
        }
        return parts.joinToString(" ")
    }

    private fun buildGitLabProjectQuery(request: ScannerSearchRequest): String {
        return buildList {
            add(request.query)
        }.joinToString(" ")
    }

    private fun isPlaylistPath(path: String): Boolean {
        return path.endsWith(".m3u", ignoreCase = true) || path.endsWith(".m3u8", ignoreCase = true)
    }

    private fun encodePathForRaw(path: String): String {
        return path
            .split('/')
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8.toString()).replace("+", "%20")
            }
    }

    private fun encodePathForApi(path: String): String {
        return URLEncoder.encode(path, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    private fun parseEpochOrNull(raw: String): Long? {
        if (raw.isBlank()) return null
        parseIsoEpoch(raw)?.let { return it }
        return raw.toLongOrNull()?.let { epoch ->
            if (epoch < 10_000_000_000L) epoch * 1000 else epoch
        }
    }

    private fun parseIsoEpoch(raw: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val parsed = runCatching { parser.parse(raw)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun ScannerSearchRequest.normalized(): ScannerSearchRequest {
        return copy(
            query = query.trim(),
            keywords = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            repoFilter = repoFilter?.trim()?.ifEmpty { null },
            pathFilter = pathFilter?.trim()?.ifEmpty { null },
            limit = limit.coerceIn(1, 200)
        )
    }

    private fun ScannerSearchRequest.cacheKey(): String {
        return listOf(
            query.lowercase(),
            keywords.joinToString(",") { it.lowercase() },
            providerScope.name,
            searchMode.name,
            repoFilter.orEmpty().lowercase(),
            pathFilter.orEmpty().lowercase(),
            updatedAfterEpochMs?.toString().orEmpty(),
            minSizeBytes?.toString().orEmpty(),
            maxSizeBytes?.toString().orEmpty(),
            limit.toString()
        ).joinToString("|")
    }

    private fun setProviderError(provider: String, message: String) {
        providerLastError[provider] = message
    }

    private fun appendProviderError(provider: String, message: String) {
        val current = providerLastError[provider]
        providerLastError[provider] = if (current.isNullOrBlank()) {
            message
        } else {
            "$current; $message"
        }
    }

    private fun clearProviderError(provider: String) {
        providerLastError.remove(provider)
    }

    private fun consumeProviderError(provider: String): String? {
        return providerLastError.remove(provider)
    }

    private class ProviderRateLimitTracker {
        private val blockedUntil = ConcurrentHashMap<String, Long>()

        fun waitTime(provider: String): Long {
            val until = blockedUntil[provider] ?: return 0L
            return (until - System.currentTimeMillis()).coerceAtLeast(0L)
        }

        fun update(provider: String, code: Int, headers: Headers) {
            val retryAfterMs = headers["Retry-After"]?.toLongOrNull()?.times(1000) ?: 0L
            val resetEpoch = headers["X-RateLimit-Reset"]?.toLongOrNull()
            val remaining = headers["X-RateLimit-Remaining"]?.toLongOrNull()
            val now = System.currentTimeMillis()

            if (retryAfterMs > 0) {
                blockedUntil[provider] = now + retryAfterMs
                return
            }

            if (remaining == 0L && resetEpoch != null) {
                val resetMs = if (resetEpoch < 10_000_000_000L) resetEpoch * 1000 else resetEpoch
                blockedUntil[provider] = resetMs
                return
            }

            if (code == 429 || code == 403) {
                blockedUntil[provider] = now + FALLBACK_RATE_LIMIT_MS
            }
        }
    }

    private data class CacheEntry(
        val savedAtMs: Long,
        val items: List<PlaylistCandidate>
    ) {
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now - savedAtMs <= CACHE_TTL_MS
    }

    private data class ProviderSearchResult(
        val provider: String,
        val items: List<PlaylistCandidate>,
        val error: String?
    )

    private data class WebFallbackResult(
        val items: List<PlaylistCandidate>,
        val error: String?
    )

    private enum class SearchEngine(val id: String, val label: String) {
        DUCKDUCKGO(id = "ddg", label = "DuckDuckGo"),
        BING(id = "bing", label = "Bing")
    }

    private companion object {
        const val PROVIDER_GITHUB = "github"
        const val PROVIDER_GITLAB = "gitlab"
        const val PROVIDER_BITBUCKET = "bitbucket"
        const val PROVIDER_WEB = "web"

        const val CACHE_TTL_MS = 10 * 60 * 1000L
        const val MAX_RETRIES = 2
        const val BASE_BACKOFF_MS = 400L
        const val MAX_BACKOFF_MS = 4_000L
        const val JITTER_MAX_MS = 300L
        const val FALLBACK_RATE_LIMIT_MS = 60_000L
        const val MAX_RATE_LIMIT_WAIT_MS = 10_000L
        const val NETWORK_CALL_TIMEOUT_MS = 8_000L

        const val MAX_FILES_PER_REPOSITORY = 200

        const val MAX_GITHUB_REPO_RESULTS = 20
        const val MAX_GITHUB_REPO_SCAN = 6

        const val MAX_GITLAB_PROJECT_RESULTS = 20
        const val MAX_GITLAB_PROJECT_SCAN = 6
        const val MAX_GITLAB_TREE_PAGES = 3
        const val GITLAB_TREE_PAGE_SIZE = 100

        const val MAX_BITBUCKET_REPO_RESULTS = 20
        const val MAX_BITBUCKET_REPO_SCAN = 6
        const val BITBUCKET_SOURCE_PAGE_SIZE = 100
        const val MAX_BITBUCKET_PAGES = 3

        const val MAX_WEB_SEARCH_QUERIES = 8
        const val MAX_WEB_LINKS_PER_QUERY = 24
        const val MAX_WEB_PROBES = 20
        const val WEB_PROBE_TIMEOUT_MS = 6_000L

        val HTML_LINK_REGEX = Regex("href=[\"']([^\"'#<>]+)[\"']", RegexOption.IGNORE_CASE)
    }

    private fun isRetriable(code: Int): Boolean {
        return code == 429 || code == 403 || code == 408 || code == 500 || code == 502 || code == 503 || code == 504
    }
}
