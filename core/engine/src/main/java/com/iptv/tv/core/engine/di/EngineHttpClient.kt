package com.iptv.tv.core.engine.di

import javax.inject.Qualifier

/** Distinguishes the short-timeout local Ace Stream HTTP client from the app network client. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EngineHttpClient
