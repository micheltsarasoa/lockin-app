package com.lockin.filter.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "allowlist",
    indices = [Index(value = ["domain"], unique = true)]
)
data class AllowlistEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "added_by") val addedBy: String = "parent",
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis() / 1000,
)
