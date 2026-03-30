package com.lockin.filter.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database backed by SQLCipher for encrypted storage.
 *
 * The database is provisioned in [com.lockin.filter.di.FilterModule] with
 * a device-bound passphrase derived from an AndroidKeyStore key.
 *
 * Schema summary:
 *   domains     — the blocklist (~1M rows)
 *   tiers       — blocklist source metadata
 *   allowlist   — parent-approved exceptions
 *   filter_log  — rolling audit log (max 10,000 rows)
 */
@Database(
    entities = [
        DomainEntity::class,
        TierEntity::class,
        AllowlistEntity::class,
        FilterLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BlocklistDatabase : RoomDatabase() {
    abstract fun domainDao(): DomainDao
}
