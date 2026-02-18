package com.iptv.tv.core.network.api

import com.iptv.tv.core.network.dto.BitbucketSourceResponse
import com.iptv.tv.core.network.dto.BitbucketWorkspaceReposResponse
import com.iptv.tv.core.network.dto.GitHubRepositorySearchResponse
import com.iptv.tv.core.network.dto.GitHubSearchResponse
import com.iptv.tv.core.network.dto.GitHubTreeResponse
import com.iptv.tv.core.network.dto.GitLabBlob
import com.iptv.tv.core.network.dto.GitLabProject
import com.iptv.tv.core.network.dto.GitLabTreeItem
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface GitHubApi {
    @Headers("Accept: application/vnd.github+json")
    @GET("search/code")
    suspend fun searchCode(
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 50,
        @Query("page") page: Int = 1
    ): Response<GitHubSearchResponse>

    @Headers("Accept: application/vnd.github+json")
    @GET("search/repositories")
    suspend fun searchRepositories(
        @Query("q") query: String,
        @Query("sort") sort: String = "updated",
        @Query("order") order: String = "desc",
        @Query("per_page") perPage: Int = 20,
        @Query("page") page: Int = 1
    ): Response<GitHubRepositorySearchResponse>

    @Headers("Accept: application/vnd.github+json")
    @GET("repos/{owner}/{repo}/git/trees/{treeRef}")
    suspend fun getRepositoryTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("treeRef") treeRef: String,
        @Query("recursive") recursive: Int = 1
    ): Response<GitHubTreeResponse>
}

interface GitLabApi {
    @GET("search")
    suspend fun searchBlobs(
        @Query("scope") scope: String = "blobs",
        @Query("search") search: String,
        @Query("per_page") perPage: Int = 50,
        @Query("page") page: Int = 1
    ): Response<List<GitLabBlob>>

    @GET("projects")
    suspend fun searchProjects(
        @Query("search") search: String,
        @Query("simple") simple: Boolean = true,
        @Query("per_page") perPage: Int = 20,
        @Query("page") page: Int = 1
    ): Response<List<GitLabProject>>

    @GET("projects/{projectId}/repository/tree")
    suspend fun getRepositoryTree(
        @Path("projectId") projectId: Long,
        @Query("recursive") recursive: Boolean = true,
        @Query("per_page") perPage: Int = 100,
        @Query("page") page: Int = 1
    ): Response<List<GitLabTreeItem>>
}

interface BitbucketApi {
    @GET("2.0/repositories/{workspace}")
    suspend fun listWorkspaceRepositories(
        @Path("workspace") workspace: String,
        @Query("pagelen") pageLen: Int = 20
    ): Response<BitbucketWorkspaceReposResponse>

    @GET("2.0/repositories/{workspace}/{repoSlug}/src/{ref}/")
    suspend fun listRepositorySource(
        @Path("workspace") workspace: String,
        @Path("repoSlug") repoSlug: String,
        @Path("ref") ref: String,
        @Query("pagelen") pageLen: Int = 100,
        @Query("q") query: String? = null
    ): Response<BitbucketSourceResponse>

    @GET
    suspend fun listRepositorySourcePage(
        @Url pageUrl: String
    ): Response<BitbucketSourceResponse>
}
