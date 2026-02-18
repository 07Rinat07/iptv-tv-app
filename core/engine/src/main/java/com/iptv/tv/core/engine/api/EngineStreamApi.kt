package com.iptv.tv.core.engine.api

import retrofit2.http.GET
import retrofit2.http.QueryMap
import retrofit2.http.Url

interface EngineStreamApi {
    @GET
    suspend fun status(
        @Url url: String,
        @QueryMap options: Map<String, String> = emptyMap()
    ): Map<String, @JvmSuppressWildcards Any?>

    @GET
    suspend fun resolve(
        @Url url: String,
        @QueryMap options: Map<String, String>
    ): Map<String, @JvmSuppressWildcards Any?>
}
