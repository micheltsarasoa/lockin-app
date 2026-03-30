package com.lockin.sync.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Sync module — BlocklistSyncWorker uses @HiltWorker for injection.
 * SyncScheduler, BloomFilterBuilder, BlocklistDownloader, HostsFileParser
 * are all @Singleton via @Inject constructors.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule
