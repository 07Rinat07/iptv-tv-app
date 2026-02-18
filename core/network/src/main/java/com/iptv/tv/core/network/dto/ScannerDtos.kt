package com.iptv.tv.core.network.dto

data class GitHubSearchResponse(
    val total_count: Int,
    val items: List<GitHubItem> = emptyList()
)

data class GitHubItem(
    val name: String,
    val path: String,
    val size: Long = 0,
    val html_url: String,
    val repository: GitHubRepository
)

data class GitHubRepository(
    val full_name: String,
    val updated_at: String,
    val default_branch: String? = null
)

data class GitHubRepositorySearchResponse(
    val total_count: Int,
    val items: List<GitHubRepositoryItem> = emptyList()
)

data class GitHubRepositoryItem(
    val full_name: String,
    val default_branch: String? = null,
    val updated_at: String,
    val size: Long? = null
)

data class GitHubTreeResponse(
    val sha: String? = null,
    val truncated: Boolean = false,
    val tree: List<GitHubTreeNode> = emptyList()
)

data class GitHubTreeNode(
    val path: String,
    val type: String,
    val size: Long? = null
)

data class GitLabBlob(
    val path: String,
    val filename: String,
    val project_id: Long,
    val data: String? = null,
    val ref: String? = null,
    val startline: Int? = null
)

data class GitLabProject(
    val id: Long,
    val path_with_namespace: String,
    val default_branch: String? = null,
    val last_activity_at: String
)

data class GitLabTreeItem(
    val id: String? = null,
    val type: String,
    val path: String,
    val name: String? = null
)

data class BitbucketWorkspaceReposResponse(
    val values: List<BitbucketRepository> = emptyList()
)

data class BitbucketRepository(
    val full_name: String,
    val slug: String,
    val updated_on: String,
    val mainbranch: BitbucketMainBranch? = null
)

data class BitbucketMainBranch(
    val name: String? = null
)

data class BitbucketSourceResponse(
    val values: List<BitbucketSourceEntry> = emptyList(),
    val next: String? = null
)

data class BitbucketSourceEntry(
    val type: String,
    val path: String,
    val size: Long? = null,
    val commit: BitbucketCommit?
)

data class BitbucketCommit(
    val date: String?
)
