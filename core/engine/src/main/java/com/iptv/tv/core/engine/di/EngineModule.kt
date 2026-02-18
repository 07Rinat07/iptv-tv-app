package com.iptv.tv.core.engine.di

import com.iptv.tv.core.engine.api.EngineStreamApi
import com.iptv.tv.core.engine.data.EngineStreamClient
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
    fun provideEngineOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideEngineRetrofit(client: OkHttpClient): Retrofit {
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
    fun provideEngineStreamClient(api: EngineStreamApi): EngineStreamClient = EngineStreamClient(api)
}
