package com.iptv.tv

import com.iptv.tv.core.domain.repository.EngineRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Typed access to the application-owned playback engine for platform integrations and diagnostics. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ApplicationEngineEntryPoint {
    fun engineRepository(): EngineRepository
}
