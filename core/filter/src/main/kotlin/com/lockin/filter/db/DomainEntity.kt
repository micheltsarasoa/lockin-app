package com.lockin.filter.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "domains",
    foreignKeys = [
        ForeignKey(
            entity = TierEntity::class,
            parentColumns = ["id"],
            childColumns = ["tier_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["domain"], unique = true),
        Index(value = ["tier_id", "domain"])
    ]
)
data class DomainEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "tier_id") val tierId: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis() / 1000,
)
