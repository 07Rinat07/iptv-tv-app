package com.iptv.tv.core.model

/** Result of building the default shareable Rinat IPTV Favorites backup. */
data class FavoritesPortableExport(
    val content: String,
    val favoriteCount: Int,
    val variantCount: Int,
    val redactedVariantCount: Int
)

/** Standard shareable export formats that must never expose provider credentials by default. */
enum class FavoritesShareableExportFormat {
    TXT,
    M3U8
}

/**
 * Result of building a standard shareable Favorites export.
 *
 * [safeUrlCount] is the number of logical favorites for which a non-credential-bearing source URL
 * could be emitted. TXT keeps metadata for favorites without a safe URL and writes a redaction
 * marker; M3U8 omits those entries because a playlist item without a playable URL is invalid.
 */
data class FavoritesShareableExport(
    val content: String,
    val favoriteCount: Int,
    val safeUrlCount: Int,
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
