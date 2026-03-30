package com.lockin.security.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Security module — all bindings are @Singleton via @Inject constructors.
 * No explicit @Provides needed; Hilt handles Argon2Hasher, PinManager,
 * BruteForceGuard, EncryptedPrefsStore, and TamperDetector automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule
