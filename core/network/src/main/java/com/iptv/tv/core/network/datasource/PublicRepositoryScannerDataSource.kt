package com.iptv.tv.core.network.datasource

import com.iptv.tv.core.model.PlaylistCandidate
import com.iptv.tv.core.model.ScannerProviderScope
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.Headers
import retrofit2.Response
import java.io.IOException
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
    private val bitbucketApi: BitbucketApi
) {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val rateLimitTracker = ProviderRateLimitTracker()
    private val providerLastError = ConcurrentHashMap<String, String>()

    suspend fun search(request: ScannerSearchRequest): List<PlaylistCandidate> {
        val normalized = request.normalized()
        val key = normalized.cacheKey()
        cache[key]?.takeIf { it.isFresh() }?.let { return it.items }

        val shouldScanBitbucket =
            normalized.providerScope == ScannerProviderScope.BITBUCKET ||
                (
                    normalized.providerScope == ScannerProviderScope.ALL &&
                        !normalized.repoFilter.isNullOrBlank()
                    )

        val providerResults = coroutineScope {
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

        val candidates = providerResults.flatMap { it.items }
        val filtered = candidates
            .distinctBy { it.id }
            .filter { matchesFilters(it, normalized) }
            .sortedWith(
                compareByDescending<PlaylistCandidate> { relevanceScore(it, normalized) }
                    .thenByDescending { parseEpochOrNull(it.updatedAt) ?: 0L }
            )
            .take(normalized.limit)

        val failures = providerResults.filter { it.error != null }
        if (filtered.isEmpty() && failures.isNotEmpty() && failures.size == providerResults.size) {
            val failureText = failures.joinToString(separator = "; ") { "${it.provider}: ${it.error}" }
            throw IOException("Provider scan failed: $failureText")
        }

        cache[key] = CacheEntry(System.currentTimeMillis(), filtered)
        return filtered
    }

    private suspend fun searchProvider(
        provider: String,
        block: suspend () -> List<PlaylistCandidate>
    ): ProviderSearchResult {
        clearProviderError(provider)
        val items = runCatching { block() }
            .getOrElse { throwable ->
                setProviderError(
                    provider,
                    throwable.message ?: throwable.javaClass.simpleName
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
                appendProviderError(
                    PROVIDER_GITHUB,
                    "code search: ${throwable.message ?: throwable.javaClass.simpleName}"
                )
                emptyList()
            }
        if (byCodeSearch.size >= request.limit) return byCodeSearch.take(request.limit)

        val byRepositoryTree = runCatching { searchGitHubRepositoryTrees(request) }
            .getOrElse { throwable ->
                appendProviderError(
                    PROVIDER_GITHUB,
                    "repo tree: ${throwable.message ?: throwable.javaClass.simpleName}"
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
                appendProviderError(
                    PROVIDER_GITLAB,
                    "blob search: ${throwable.message ?: throwable.javaClass.simpleName}"
                )
                emptyList()
            }
        if (byBlobs.size >= request.limit) return byBlobs.take(request.limit)

        val byProjectTrees = runCatching { searchGitLabProjectTrees(request) }
            .getOrElse { throwable ->
                appendProviderError(
                    PROVIDER_GITLAB,
                    "project tree: ${throwable.message ?: throwable.javaClass.simpleName}"
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

    private suspend fun <T> executeWithRetry(
        provider: String,
        requestBlock: suspend () -> Response<T>
    ): T? {
        var attempt = 0
        var lastError: String? = null
        while (attempt < MAX_RETRIES) {
            val waitForRateLimit = rateLimitTracker.waitTime(provider)
            if (waitForRateLimit > 0) {
                delay(min(waitForRateLimit, MAX_RATE_LIMIT_WAIT_MS))
            }

            try {
                val response = requestBlock()
                rateLimitTracker.update(provider, response.code(), response.headers())

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body == null) {
                        lastError = "HTTP ${response.code()} (empty body)"
                        setProviderError(provider, lastError)
                        return null
                    }
                    clearProviderError(provider)
                    return body
                }

                if (!isRetriable(response.code())) {
                    lastError = "HTTP ${response.code()}"
                    setProviderError(provider, lastError)
                    return null
                }

                lastError = "HTTP ${response.code()} (retry)"
                val delayMs = computeDelayMs(attempt, response.headers())
                delay(delayMs)
            } catch (_: IOException) {
                lastError = "Network IO error"
                delay(computeDelayMs(attempt, headers = null))
            }
            attempt += 1
        }
        setProviderError(provider, lastError ?: "Unknown network error")
        return null
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

    private fun matchesFilters(candidate: PlaylistCandidate, request: ScannerSearchRequest): Boolean {
        val haystack = "${candidate.name} ${candidate.path} ${candidate.repository}".lowercase()
        val repoFilter = request.repoFilter
        val pathFilter = request.pathFilter
        val minSizeBytes = request.minSizeBytes
        val maxSizeBytes = request.maxSizeBytes
        val updatedAfter = request.updatedAfterEpochMs

        val matchesKeywords = request.keywords.isEmpty() || request.keywords.any { keyword ->
            haystack.contains(keyword.trim().lowercase())
        }
        val matchesRepo = repoFilter.isNullOrBlank() || candidate.repository.contains(repoFilter, ignoreCase = true)
        val matchesPath = pathFilter.isNullOrBlank() || candidate.path.contains(pathFilter, ignoreCase = true)
        val size = candidate.sizeBytes
        val matchesMinSize = minSizeBytes == null || (size != null && size >= minSizeBytes)
        val matchesMaxSize = maxSizeBytes == null || (size != null && size <= maxSizeBytes)
        val updated = parseEpochOrNull(candidate.updatedAt)
        val matchesUpdated = updatedAfter == null || (updated != null && updated >= updatedAfter)
        return matchesKeywords && matchesRepo && matchesPath && matchesMinSize && matchesMaxSize && matchesUpdated
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

    private companion object {
        const val PROVIDER_GITHUB = "github"
        const val PROVIDER_GITLAB = "gitlab"
        const val PROVIDER_BITBUCKET = "bitbucket"

        const val CACHE_TTL_MS = 10 * 60 * 1000L
        const val MAX_RETRIES = 3
        const val BASE_BACKOFF_MS = 800L
        const val MAX_BACKOFF_MS = 8_000L
        const val JITTER_MAX_MS = 300L
        const val FALLBACK_RATE_LIMIT_MS = 60_000L
        const val MAX_RATE_LIMIT_WAIT_MS = 10_000L

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
    }

    private fun isRetriable(code: Int): Boolean {
        return code == 429 || code == 403 || code == 408 || code == 500 || code == 502 || code == 503 || code == 504
    }
}
