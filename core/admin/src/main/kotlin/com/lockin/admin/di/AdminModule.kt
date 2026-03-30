package com.lockin.admin.di

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.lockin.admin.LockInDeviceAdminReceiver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AdminModule {

    @Provides
    @Singleton
    fun provideDevicePolicyManager(@ApplicationContext context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    @Provides
    @Singleton
    fun provideAdminComponentName(@ApplicationContext context: Context): ComponentName =
        ComponentName(context, LockInDeviceAdminReceiver::class.java)
}
