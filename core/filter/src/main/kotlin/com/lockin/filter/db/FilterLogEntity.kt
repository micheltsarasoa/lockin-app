package com.lockin.filter.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "filter_log",
    indices = [Index(value = ["ts"])]
)
data class FilterLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "verdict") val verdict: String, // "ALLOW" | "BLOCK" | "FALLBACK"
    @ColumnInfo(name = "source") val source: String? = null,
    @ColumnInfo(name = "ts") val ts: Long = System.currentTimeMillis() / 1000,
)
