package com.lockin.vpn.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * VPN module — LockInVpnService uses @AndroidEntryPoint for injection.
 * No additional @Provides needed at this time.
 */
@Module
@InstallIn(SingletonComponent::class)
object VpnModule
