package com.iptv.tv.core.network.di

import com.iptv.tv.core.network.api.BitbucketApi
import com.iptv.tv.core.network.api.GitHubApi
import com.iptv.tv.core.network.api.GitLabApi
import com.iptv.tv.core.network.dns.ResilientDns
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val resilientDns = ResilientDns.create()
        return OkHttpClient.Builder()
            .dns(resilientDns)
            .proxySelector(ProxySelector.getDefault())
            .proxyAuthenticator { _, response ->
                val user = System.getProperty(PROXY_SCANNER_USER).orEmpty().trim()
                if (user.isBlank()) {
                    null
                } else {
                    val pass = System.getProperty(PROXY_SCANNER_PASS).orEmpty()
                    val credential = Credentials.basic(user, pass)
                    if (response.request.header("Proxy-Authorization") == credential) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                }
            }
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "IptvTv/0.1")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logger)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private const val PROXY_SCANNER_USER = "myscaner.proxy.user"
    private const val PROXY_SCANNER_PASS = "myscaner.proxy.pass"

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(client)
            .build()
    }

    @Provides
    @Singleton
    @Named("gitlab")
    fun provideGitLabRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://gitlab.com/api/v4/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(client)
            .build()
    }

    @Provides
    @Singleton
    @Named("bitbucket")
    fun provideBitbucketRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.bitbucket.org/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(client)
            .build()
    }

    @Provides
    @Singleton
    fun provideGitHubApi(@Named("github") retrofit: Retrofit): GitHubApi = retrofit.create(GitHubApi::class.java)

    @Provides
    @Singleton
    fun provideGitLabApi(@Named("gitlab") retrofit: Retrofit): GitLabApi = retrofit.create(GitLabApi::class.java)

    @Provides
    @Singleton
    fun provideBitbucketApi(@Named("bitbucket") retrofit: Retrofit): BitbucketApi = retrofit.create(BitbucketApi::class.java)
}
