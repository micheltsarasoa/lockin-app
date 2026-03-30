package com.lockin.filter.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DomainDao {

    // ── Blocklist lookups ────────────────────────────────────────────────────

    /**
     * Returns true if the domain is in the blocklist.
     * Uses the unique index on domains.domain for O(log N) lookup.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM domains WHERE domain = :domain LIMIT 1)")
    suspend fun isBlocked(domain: String): Boolean

    /**
     * Returns true if the domain is in the parent-managed allowlist.
     * Allowlist always wins over blocklist.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM allowlist WHERE domain = :domain LIMIT 1)")
    suspend fun isAllowlisted(domain: String): Boolean

    // ── Bulk insert (used during sync) ───────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDomains(domains: List<DomainEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceTier(tier: TierEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllowlist(entry: AllowlistEntity): Long

    // ── Pruning (used during sync to remove dropped domains) ─────────────────

    @Query("DELETE FROM domains WHERE tier_id = :tierId")
    suspend fun deleteAllDomainsForTier(tierId: Int)

    @Query("SELECT COUNT(*) FROM domains WHERE tier_id = :tierId")
    suspend fun countDomainsForTier(tierId: Int): Int

    @Query("SELECT COUNT(*) FROM domains")
    suspend fun totalDomainCount(): Int

    // ── Tiers ─────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM tiers WHERE name = :name LIMIT 1")
    suspend fun getTierByName(name: String): TierEntity?

    @Query("SELECT * FROM tiers")
    suspend fun getAllTiers(): List<TierEntity>

    @Query("UPDATE tiers SET etag = :etag, last_sync = :lastSync, domain_count = :count WHERE id = :id")
    suspend fun updateTierSyncInfo(id: Int, etag: String?, lastSync: Long, count: Int)

    // ── Allowlist management ──────────────────────────────────────────────────

    @Query("SELECT * FROM allowlist ORDER BY added_at DESC")
    suspend fun getAllAllowlistEntries(): List<AllowlistEntity>

    @Query("DELETE FROM allowlist WHERE domain = :domain")
    suspend fun removeFromAllowlist(domain: String)

    // ── Audit log ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: FilterLogEntity)

    @Query("SELECT COUNT(*) FROM filter_log")
    suspend fun logCount(): Int

    @Query("DELETE FROM filter_log WHERE id IN (SELECT id FROM filter_log ORDER BY ts ASC LIMIT :count)")
    suspend fun pruneOldLogs(count: Int)

    @Query("SELECT * FROM filter_log ORDER BY ts DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 100): List<FilterLogEntity>
}
