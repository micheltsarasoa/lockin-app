package com.lockin.filter.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tiers",
    indices = [Index(value = ["name"], unique = true)]
)
data class TierEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    @ColumnInfo(name = "etag") val etag: String? = null,
    @ColumnInfo(name = "last_sync") val lastSync: Long? = null,
    @ColumnInfo(name = "domain_count") val domainCount: Int = 0,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
)
