package com.iptv.tv.core.engine.di

import com.iptv.tv.core.engine.api.EngineStreamApi
import com.iptv.tv.core.engine.data.AceContentMetadataProvider
import com.iptv.tv.core.engine.data.AceContentTransportResolver
import com.iptv.tv.core.engine.data.AceStreamServiceConnector
import com.iptv.tv.core.engine.data.ChainedAceContentMetadataProvider
import com.iptv.tv.core.engine.data.EngineStreamClient
import com.iptv.tv.core.engine.data.ExternalAceContentTransportResolver
import com.iptv.tv.core.engine.data.ExternalEngineAceContentMetadataProvider
import com.iptv.tv.core.engine.data.LoopbackFirstAceContentTransportResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    @EngineHttpClient
    fun provideEngineOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            // The endpoint is loopback, so connection establishment should still be immediate.
            .connectTimeout(2, TimeUnit.SECONDS)
            // Ace live startup may legitimately spend up to ~10 s waiting for P2P manifest data.
            // Keep the client deadline above that engine-side window instead of failing at 3-5 s.
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideEngineRetrofit(
        @EngineHttpClient client: OkHttpClient
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://127.0.0.1/")
            .addConverterFactory(MoshiConverterFactory.create())
            .client(client)
            .build()
    }

    @Provides
    @Singleton
    fun provideEngineApi(retrofit: Retrofit): EngineStreamApi {
        return retrofit.create(EngineStreamApi::class.java)
    }

    @Provides
    @Singleton
    fun provideEngineStreamClient(
        api: EngineStreamApi,
        serviceConnector: AceStreamServiceConnector
    ): EngineStreamClient = EngineStreamClient(api, serviceConnector)

    @Provides
    @Singleton
    fun provideAceContentMetadataProvider(
        client: EngineStreamClient
    ): AceContentMetadataProvider {
        val externalCompatibility = ExternalEngineAceContentMetadataProvider(client)
        return ChainedAceContentMetadataProvider(
            providers = listOf(externalCompatibility)
        )
    }

    @Provides
    @Singleton
    fun provideAceContentTransportResolver(
        client: EngineStreamClient,
        metadataProvider: AceContentMetadataProvider
    ): AceContentTransportResolver {
        val classifier = ExternalAceContentTransportResolver(metadataProvider)
        return LoopbackFirstAceContentTransportResolver(
            client = client,
            delegate = classifier
        )
    }
}
