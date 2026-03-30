package com.lockin.accessibility.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Accessibility module — LockInAccessibilityService uses @AndroidEntryPoint.
 * No additional @Provides needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object AccessibilityModule
