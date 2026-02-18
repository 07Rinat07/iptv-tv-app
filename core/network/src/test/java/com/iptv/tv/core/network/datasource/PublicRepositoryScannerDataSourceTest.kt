package com.iptv.tv.core.network.datasource

import com.iptv.tv.core.model.ScannerSearchRequest
import com.iptv.tv.core.model.ScannerProviderScope
import com.iptv.tv.core.network.api.BitbucketApi
import com.iptv.tv.core.network.api.GitHubApi
import com.iptv.tv.core.network.api.GitLabApi
import com.iptv.tv.core.network.dto.BitbucketCommit
import com.iptv.tv.core.network.dto.BitbucketMainBranch
import com.iptv.tv.core.network.dto.BitbucketRepository
import com.iptv.tv.core.network.dto.BitbucketSourceEntry
import com.iptv.tv.core.network.dto.BitbucketSourceResponse
import com.iptv.tv.core.network.dto.BitbucketWorkspaceReposResponse
import com.iptv.tv.core.network.dto.GitHubRepositoryItem
import com.iptv.tv.core.network.dto.GitHubRepositorySearchResponse
import com.iptv.tv.core.network.dto.GitHubSearchResponse
import com.iptv.tv.core.network.dto.GitHubTreeNode
import com.iptv.tv.core.network.dto.GitHubTreeResponse
import com.iptv.tv.core.network.dto.GitLabBlob
import com.iptv.tv.core.network.dto.GitLabProject
import com.iptv.tv.core.network.dto.GitLabTreeItem
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

class PublicRepositoryScannerDataSourceTest {

    @Test
    fun appliesRepoAndSizeFilters() = runTest {
        val source = PublicRepositoryScannerDataSource(
            gitHubApi = FakeGitHubApi(
                repositorySearch = GitHubRepositorySearchResponse(
                    total_count = 1,
                    items = listOf(
                        GitHubRepositoryItem(
                            full_name = "demo/repo",
                            default_branch = "main",
                            updated_at = "2025-01-01T00:00:00Z"
                        )
                    )
                ),
                treeByRepository = mapOf(
                    "demo/repo@main" to GitHubTreeResponse(
                        tree = listOf(
                            GitHubTreeNode(path = "list/a.m3u", type = "blob", size = 300),
                            GitHubTreeNode(path = "list/b.m3u", type = "blob", size = 50)
                        )
                    )
                )
            ),
            gitLabApi = FakeGitLabApi(),
            bitbucketApi = FakeBitbucketApi()
        )

        val result = source.search(
            ScannerSearchRequest(
                query = "iptv",
                repoFilter = "demo/repo",
                minSizeBytes = 100,
                limit = 20
            )
        )

        assertEquals(1, result.size)
        assertEquals("demo/repo", result.first().repository)
    }

    @Test
    fun returnsCachedResultForSameRequest() = runTest {
        val gitHubApi = FakeGitHubApi(
            repositorySearch = GitHubRepositorySearchResponse(
                total_count = 1,
                items = listOf(
                    GitHubRepositoryItem(
                        full_name = "demo/cache",
                        default_branch = "main",
                        updated_at = "2025-01-01T00:00:00Z"
                    )
                )
            ),
            treeByRepository = mapOf(
                "demo/cache@main" to GitHubTreeResponse(
                    tree = listOf(
                        GitHubTreeNode(path = "live/cache.m3u8", type = "blob", size = 123)
                    )
                )
            )
        )
        val source = PublicRepositoryScannerDataSource(
            gitHubApi = gitHubApi,
            gitLabApi = FakeGitLabApi(),
            bitbucketApi = FakeBitbucketApi()
        )

        val request = ScannerSearchRequest(query = "cache", limit = 20)
        val first = source.search(request)
        val second = source.search(request)

        assertTrue(first.isNotEmpty())
        assertEquals(first, second)
        assertEquals(1, gitHubApi.searchRepositoriesCalls)
    }

    @Test
    fun retriesAfterRateLimitResponse() = runTest {
        val gitHubApi = object : GitHubApi {
            var repositorySearchCalls = 0

            override suspend fun searchCode(query: String, perPage: Int, page: Int): Response<GitHubSearchResponse> {
                return Response.success(GitHubSearchResponse(total_count = 0, items = emptyList()))
            }

            override suspend fun searchRepositories(
                query: String,
                sort: String,
                order: String,
                perPage: Int,
                page: Int
            ): Response<GitHubRepositorySearchResponse> {
                repositorySearchCalls += 1
                return if (repositorySearchCalls == 1) {
                    Response.error(
                        429,
                        "{}".toResponseBody("application/json".toMediaType())
                    )
                } else {
                    Response.success(
                        GitHubRepositorySearchResponse(
                            total_count = 1,
                            items = listOf(
                                GitHubRepositoryItem(
                                    full_name = "demo/retry",
                                    default_branch = "main",
                                    updated_at = "2025-01-01T00:00:00Z"
                                )
                            )
                        )
                    )
                }
            }

            override suspend fun getRepositoryTree(
                owner: String,
                repo: String,
                treeRef: String,
                recursive: Int
            ): Response<GitHubTreeResponse> {
                return Response.success(
                    GitHubTreeResponse(
                        tree = listOf(
                            GitHubTreeNode(path = "tv/retry.m3u", type = "blob", size = 120)
                        )
                    )
                )
            }
        }

        val source = PublicRepositoryScannerDataSource(
            gitHubApi = gitHubApi,
            gitLabApi = FakeGitLabApi(),
            bitbucketApi = FakeBitbucketApi()
        )

        val result = source.search(ScannerSearchRequest(query = "retry", limit = 10))

        assertEquals(1, result.size)
    }

    @Test
    fun bitbucketWorkspaceScanUsesRepoFilterWorkspace() = runTest {
        val source = PublicRepositoryScannerDataSource(
            gitHubApi = FakeGitHubApi(),
            gitLabApi = FakeGitLabApi(),
            bitbucketApi = FakeBitbucketApi(
                workspaceRepos = BitbucketWorkspaceReposResponse(
                    values = listOf(
                        BitbucketRepository(
                            full_name = "demo/iptvrepo",
                            slug = "iptvrepo",
                            updated_on = "2025-01-01T00:00:00Z",
                            mainbranch = BitbucketMainBranch("master")
                        )
                    )
                ),
                sourceResponse = BitbucketSourceResponse(
                    values = listOf(
                        BitbucketSourceEntry(
                            type = "commit_file",
                            path = "live/list.m3u8",
                            size = 123,
                            commit = BitbucketCommit("2025-01-01T00:00:00Z")
                        )
                    )
                )
            )
        )

        val result = source.search(
            ScannerSearchRequest(
                query = "iptv",
                repoFilter = "demo",
                limit = 20
            )
        )

        assertEquals(1, result.size)
        assertEquals("bitbucket", result.first().provider)
        assertEquals("demo/iptvrepo", result.first().repository)
    }

    @Test
    fun appliesProviderScopeFilter() = runTest {
        val source = PublicRepositoryScannerDataSource(
            gitHubApi = FakeGitHubApi(
                repositorySearch = GitHubRepositorySearchResponse(
                    total_count = 1,
                    items = listOf(
                        GitHubRepositoryItem(
                            full_name = "demo/gh-only",
                            default_branch = "main",
                            updated_at = "2025-01-03T00:00:00Z"
                        )
                    )
                ),
                treeByRepository = mapOf(
                    "demo/gh-only@main" to GitHubTreeResponse(
                        tree = listOf(
                            GitHubTreeNode(path = "tv/gh.m3u8", type = "blob", size = 101)
                        )
                    )
                )
            ),
            gitLabApi = FakeGitLabApi(
                projects = listOf(
                    GitLabProject(
                        id = 10,
                        path_with_namespace = "demo/gitlab-only",
                        default_branch = "main",
                        last_activity_at = "2025-01-02T00:00:00Z"
                    )
                ),
                treeItems = listOf(
                    GitLabTreeItem(
                        type = "blob",
                        path = "tv/gl.m3u"
                    )
                )
            ),
            bitbucketApi = FakeBitbucketApi()
        )

        val result = source.search(
            ScannerSearchRequest(
                query = "iptv",
                providerScope = ScannerProviderScope.GITHUB,
                limit = 20
            )
        )

        assertEquals(1, result.size)
        assertEquals("github", result.first().provider)
    }

    @Test
    fun throwsWhenAllEnabledProvidersFail() = runTest {
        val gitHubApi = object : GitHubApi {
            override suspend fun searchCode(query: String, perPage: Int, page: Int): Response<GitHubSearchResponse> =
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))

            override suspend fun searchRepositories(
                query: String,
                sort: String,
                order: String,
                perPage: Int,
                page: Int
            ): Response<GitHubRepositorySearchResponse> =
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))

            override suspend fun getRepositoryTree(
                owner: String,
                repo: String,
                treeRef: String,
                recursive: Int
            ): Response<GitHubTreeResponse> =
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))
        }

        val source = PublicRepositoryScannerDataSource(
            gitHubApi = gitHubApi,
            gitLabApi = FakeGitLabApi(),
            bitbucketApi = FakeBitbucketApi()
        )

        try {
            source.search(
                ScannerSearchRequest(
                    query = "iptv",
                    providerScope = ScannerProviderScope.GITHUB,
                    limit = 10
                )
            )
            fail("Expected IOException")
        } catch (expected: Exception) {
            assertTrue(expected.message?.contains("Provider scan failed") == true)
        }
    }

    @Test
    fun allScopeWithoutBitbucketRepoFilterStillThrowsWhenGitHubAndGitLabFail() = runTest {
        val gitHubApi = object : GitHubApi {
            override suspend fun searchCode(query: String, perPage: Int, page: Int): Response<GitHubSearchResponse> =
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))

            override suspend fun searchRepositories(
                query: String,
                sort: String,
                order: String,
                perPage: Int,
                page: Int
            ): Response<GitHubRepositorySearchResponse> =
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))

            override suspend fun getRepositoryTree(
                owner: String,
                repo: String,
                treeRef: String,
                recursive: Int
            ): Response<GitHubTreeResponse> =
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))
        }

        val gitLabApi = object : GitLabApi {
            override suspend fun searchBlobs(
                scope: String,
                search: String,
                perPage: Int,
                page: Int
            ): Response<List<GitLabBlob>> =
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))

            override suspend fun searchProjects(
                search: String,
                simple: Boolean,
                perPage: Int,
                page: Int
            ): Response<List<GitLabProject>> =
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))

            override suspend fun getRepositoryTree(
                projectId: Long,
                recursive: Boolean,
                perPage: Int,
                page: Int
            ): Response<List<GitLabTreeItem>> =
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))
        }

        val source = PublicRepositoryScannerDataSource(
            gitHubApi = gitHubApi,
            gitLabApi = gitLabApi,
            bitbucketApi = FakeBitbucketApi()
        )

        try {
            source.search(
                ScannerSearchRequest(
                    query = "iptv",
                    providerScope = ScannerProviderScope.ALL,
                    repoFilter = null,
                    limit = 10
                )
            )
            fail("Expected IOException")
        } catch (expected: Exception) {
            assertTrue(expected.message?.contains("Provider scan failed") == true)
        }
    }

    @Test
    fun keywordFilterUsesAnyKeywordInsteadOfAll() = runTest {
        val source = PublicRepositoryScannerDataSource(
            gitHubApi = FakeGitHubApi(
                repositorySearch = GitHubRepositorySearchResponse(
                    total_count = 1,
                    items = listOf(
                        GitHubRepositoryItem(
                            full_name = "demo/usa-channels",
                            default_branch = "main",
                            updated_at = "2025-01-01T00:00:00Z"
                        )
                    )
                ),
                treeByRepository = mapOf(
                    "demo/usa-channels@main" to GitHubTreeResponse(
                        tree = listOf(
                            GitHubTreeNode(path = "iptv/usa-news.m3u8", type = "blob", size = 101)
                        )
                    )
                )
            ),
            gitLabApi = FakeGitLabApi(),
            bitbucketApi = FakeBitbucketApi()
        )

        val result = source.search(
            ScannerSearchRequest(
                query = "iptv",
                keywords = listOf("usa", "premium"),
                providerScope = ScannerProviderScope.GITHUB,
                limit = 20
            )
        )

        assertEquals(1, result.size)
    }

    @Test
    fun githubCodeSearchFailureFallsBackToRepositoryTree() = runTest {
        val gitHubApi = object : GitHubApi {
            override suspend fun searchCode(query: String, perPage: Int, page: Int): Response<GitHubSearchResponse> {
                throw IllegalArgumentException("converter failed")
            }

            override suspend fun searchRepositories(
                query: String,
                sort: String,
                order: String,
                perPage: Int,
                page: Int
            ): Response<GitHubRepositorySearchResponse> {
                return Response.success(
                    GitHubRepositorySearchResponse(
                        total_count = 1,
                        items = listOf(
                            GitHubRepositoryItem(
                                full_name = "demo/fallback",
                                default_branch = "main",
                                updated_at = "2025-01-01T00:00:00Z"
                            )
                        )
                    )
                )
            }

            override suspend fun getRepositoryTree(
                owner: String,
                repo: String,
                treeRef: String,
                recursive: Int
            ): Response<GitHubTreeResponse> {
                return Response.success(
                    GitHubTreeResponse(
                        tree = listOf(
                            GitHubTreeNode(path = "tv/fallback.m3u", type = "blob", size = 120)
                        )
                    )
                )
            }
        }

        val source = PublicRepositoryScannerDataSource(
            gitHubApi = gitHubApi,
            gitLabApi = FakeGitLabApi(),
            bitbucketApi = FakeBitbucketApi()
        )

        val result = source.search(
            ScannerSearchRequest(
                query = "iptv",
                providerScope = ScannerProviderScope.GITHUB,
                limit = 10
            )
        )

        assertEquals(1, result.size)
        assertEquals("demo/fallback", result.first().repository)
    }

    @Test
    fun relevanceScorePrioritizesMatchingQueryTokens() = runTest {
        val source = PublicRepositoryScannerDataSource(
            gitHubApi = FakeGitHubApi(
                repositorySearch = GitHubRepositorySearchResponse(
                    total_count = 2,
                    items = listOf(
                        GitHubRepositoryItem(
                            full_name = "demo/random-tv",
                            default_branch = "main",
                            updated_at = "2025-02-01T00:00:00Z"
                        ),
                        GitHubRepositoryItem(
                            full_name = "demo/world-tv",
                            default_branch = "main",
                            updated_at = "2024-01-01T00:00:00Z"
                        )
                    )
                ),
                treeByRepository = mapOf(
                    "demo/random-tv@main" to GitHubTreeResponse(
                        tree = listOf(
                            GitHubTreeNode(path = "lists/general.m3u", type = "blob", size = 110)
                        )
                    ),
                    "demo/world-tv@main" to GitHubTreeResponse(
                        tree = listOf(
                            GitHubTreeNode(path = "lists/world-channels.m3u8", type = "blob", size = 120)
                        )
                    )
                )
            ),
            gitLabApi = FakeGitLabApi(),
            bitbucketApi = FakeBitbucketApi()
        )

        val result = source.search(
            ScannerSearchRequest(
                query = "world iptv",
                providerScope = ScannerProviderScope.GITHUB,
                limit = 10
            )
        )

        assertEquals(2, result.size)
        assertTrue(result.first().repository.contains("world", ignoreCase = true))
    }

    private class FakeGitHubApi(
        private val codeSearch: GitHubSearchResponse = GitHubSearchResponse(total_count = 0, items = emptyList()),
        private val repositorySearch: GitHubRepositorySearchResponse = GitHubRepositorySearchResponse(
            total_count = 0,
            items = emptyList()
        ),
        private val treeByRepository: Map<String, GitHubTreeResponse> = emptyMap()
    ) : GitHubApi {
        var searchRepositoriesCalls: Int = 0

        override suspend fun searchCode(query: String, perPage: Int, page: Int): Response<GitHubSearchResponse> {
            return Response.success(codeSearch)
        }

        override suspend fun searchRepositories(
            query: String,
            sort: String,
            order: String,
            perPage: Int,
            page: Int
        ): Response<GitHubRepositorySearchResponse> {
            searchRepositoriesCalls += 1
            return Response.success(repositorySearch)
        }

        override suspend fun getRepositoryTree(
            owner: String,
            repo: String,
            treeRef: String,
            recursive: Int
        ): Response<GitHubTreeResponse> {
            val key = "$owner/$repo@$treeRef"
            return Response.success(
                treeByRepository[key] ?: GitHubTreeResponse(tree = emptyList())
            )
        }
    }

    private class FakeGitLabApi(
        private val blobs: List<GitLabBlob> = emptyList(),
        private val projects: List<GitLabProject> = emptyList(),
        private val treeItems: List<GitLabTreeItem> = emptyList()
    ) : GitLabApi {
        override suspend fun searchBlobs(
            scope: String,
            search: String,
            perPage: Int,
            page: Int
        ): Response<List<GitLabBlob>> {
            return Response.success(blobs)
        }

        override suspend fun searchProjects(
            search: String,
            simple: Boolean,
            perPage: Int,
            page: Int
        ): Response<List<GitLabProject>> {
            return Response.success(projects)
        }

        override suspend fun getRepositoryTree(
            projectId: Long,
            recursive: Boolean,
            perPage: Int,
            page: Int
        ): Response<List<GitLabTreeItem>> {
            return Response.success(treeItems)
        }
    }

    private class FakeBitbucketApi(
        private val workspaceRepos: BitbucketWorkspaceReposResponse = BitbucketWorkspaceReposResponse(values = emptyList()),
        private val sourceResponse: BitbucketSourceResponse = BitbucketSourceResponse(values = emptyList())
    ) : BitbucketApi {
        override suspend fun listWorkspaceRepositories(
            workspace: String,
            pageLen: Int
        ): Response<BitbucketWorkspaceReposResponse> {
            return Response.success(workspaceRepos)
        }

        override suspend fun listRepositorySource(
            workspace: String,
            repoSlug: String,
            ref: String,
            pageLen: Int,
            query: String?
        ): Response<BitbucketSourceResponse> {
            return Response.success(sourceResponse)
        }

        override suspend fun listRepositorySourcePage(pageUrl: String): Response<BitbucketSourceResponse> {
            return Response.success(BitbucketSourceResponse(values = emptyList()))
        }
    }
}
