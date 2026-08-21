package com.iptv.tv.core.model

/** Result of building the default shareable Rinat IPTV Favorites backup. */
data class FavoritesPortableExport(
    val content: String,
    val favoriteCount: Int,
    val variantCount: Int,
    val redactedVariantCount: Int
)

enum class FavoritesPortableImportStatus {
    SUCCESS,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION
}

/** Summary returned after validating and merging a portable Favorites backup. */
data class FavoritesPortableImportResult(
    val status: FavoritesPortableImportStatus,
    val importedFavorites: Int = 0,
    val mergedFavorites: Int = 0,
    val importedVariants: Int = 0,
    val redactedVariantsIgnored: Int = 0,
    val skippedUnrestorableFavorites: Int = 0,
    val message: String? = null
)
