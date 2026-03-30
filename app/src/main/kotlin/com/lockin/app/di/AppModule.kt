package com.lockin.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * App-level Hilt module.
 *
 * All core module bindings are handled by their own @Module classes:
 *   - FilterModule    → :core:filter
 *   - SecurityModule  → :core:security
 *   - AdminModule     → :core:admin
 *
 * This module handles any app-level-only bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
