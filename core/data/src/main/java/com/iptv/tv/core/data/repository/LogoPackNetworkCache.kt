package com.iptv.tv.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject

internal data class LogoPackNetworkResult(
    val json: String,
    val fromCache: Boolean,
    val detail: String
)

internal class LogoPackNetworkCache(
    private val cacheRootProvider: () -> File?,
    private val okHttpClient: OkHttpClient
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ) : this(
        cacheRootProvider = { File(context.cacheDir, CACHE_DIR_NAME) },
        okHttpClient = okHttpClient
    )

    fun fetch(url: String): LogoPackNetworkResult {
        val normalizedUrl = url.trim()
        require(normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
            "Logo pack URL должен начинаться с http:// или https://"
        }
        val cacheFile = cacheFileFor(normalizedUrl)
        val networkResult = runCatching {
            val request = Request.Builder()
                .url(normalizedUrl)
                .header("Accept", "application/json,text/json,*/*")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                response.body?.string()?.trim()?.takeIf { it.isNotBlank() }
                    ?: error("Пустой ответ logo pack")
            }
        }
        networkResult.getOrNull()?.let { json ->
            cacheFile?.writeCache(json)
            return LogoPackNetworkResult(
                json = json,
                fromCache = false,
                detail = "network"
            )
        }

        val cached = cacheFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        if (cached != null) {
            return LogoPackNetworkResult(
                json = cached,
                fromCache = true,
                detail = networkResult.exceptionOrNull()?.message ?: "network_error"
            )
        }
        throw IllegalStateException(
            "Не удалось загрузить logo pack: ${networkResult.exceptionOrNull()?.message ?: "network_error"}"
        )
    }

    private fun cacheFileFor(url: String): File? {
        val root = cacheRootProvider.invoke() ?: return null
        return File(root, "${url.sha256()}.json")
    }

    private fun File.writeCache(json: String) {
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(this)) {
            writeText(json)
            tmp.delete()
        }
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private companion object {
        const val CACHE_DIR_NAME = "logo_pack_cache"
    }
}
