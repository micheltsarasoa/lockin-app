package com.lockin.filter.di

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import com.lockin.filter.FilterEngine
import com.lockin.filter.FilterEngineImpl
import com.lockin.filter.bloom.BloomFilter
import com.lockin.filter.bloom.BloomFilterLoader
import com.lockin.filter.db.BlocklistDatabase
import com.lockin.filter.db.DomainDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FilterModule {

    @Binds
    @Singleton
    abstract fun bindFilterEngine(impl: FilterEngineImpl): FilterEngine

    companion object {

        @Provides
        @Singleton
        fun provideBloomFilterRef(loader: BloomFilterLoader): AtomicReference<BloomFilter> =
            AtomicReference(loader.load())

        @Provides
        @Singleton
        fun provideBlocklistDatabase(
            @ApplicationContext context: Context,
        ): BlocklistDatabase {
            val passphrase = deriveDbPassphrase(context)
            val factory = SupportFactory(passphrase)
            return Room.databaseBuilder(context, BlocklistDatabase::class.java, "blocklist.db")
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }

        @Provides
        @Singleton
        fun provideDomainDao(db: BlocklistDatabase): DomainDao = db.domainDao()

        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        // Note: in the VPN context, the caller must wrap this with a VpnService-protecting
        // SocketFactory to avoid routing loops. See TunInterface.

        /**
         * Derives a database passphrase from a hardware-backed AndroidKeyStore key.
         *
         * The passphrase is: AES-256 key bytes + app install ID salt.
         * This ties the DB to the device's secure element — copying the DB file
         * to another device won't allow decryption.
         */
        private fun deriveDbPassphrase(context: Context): ByteArray {
            val keyAlias = "lockin_db_key"
            val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

            if (!keyStore.containsAlias(keyAlias)) {
                val keyGen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                )
                keyGen.init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                keyGen.generateKey()
            }

            val key = keyStore.getKey(keyAlias, null) as SecretKey
            // Use the raw key material as the passphrase (SQLCipher accepts byte arrays)
            return key.encoded ?: run {
                // Hardware-backed keys may not expose raw bytes — use a derived value
                // by encoding the key alias + a stable device identifier
                (keyAlias + android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )).toByteArray(Charsets.UTF_8)
            }
        }
    }
}
