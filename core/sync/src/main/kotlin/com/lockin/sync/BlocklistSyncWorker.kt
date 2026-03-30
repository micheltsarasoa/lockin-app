package com.lockin.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lockin.filter.db.DomainDao
import com.lockin.filter.db.DomainEntity
import com.lockin.filter.db.TierEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that downloads and processes blocklists.
 *
 * Sync flow:
 *   1. For each tier, download the host file (conditional GET with ETag)
 *   2. Parse into a Set<String> using [HostsFileParser]
 *   3. Delete all existing domains for the tier
 *   4. Insert new domains in batches
 *   5. Update tier metadata (ETag, last_sync, domain_count)
 *   6. After all tiers are done, rebuild the Bloom filter from all domains
 *
 * Run conditions: Network required, battery not critical.
 * Retry policy: Exponential backoff (WorkManager default).
 */
@HiltWorker
class BlocklistSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: BlocklistDownloader,
    private val domainDao: DomainDao,
    private val bloomFilterBuilder: BloomFilterBuilder,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BlocklistSync"
        const val WORK_NAME = "blocklist_sync"
        private const val BATCH_SIZE = 5_000

        /** The blocklist tiers to sync, in priority order. */
        val TIERS = listOf(
            BlocklistTier(
                name = "steven_black",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts",
                description = "Steven Black Unified Hosts (Porn category)",
            ),
            BlocklistTier(
                name = "oisd_adult",
                url = "https://abp.oisd.nl/basic/",
                description = "OISD Basic (Adult filter)",
            ),
            BlocklistTier(
                name = "energized_porn",
                url = "https://block.energized.pro/porn/formats/hosts.txt",
                description = "Energized Porn Blocker",
            ),
        )

        data class BlocklistTier(
            val name: String,
            val url: String,
            val description: String,
        )
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting blocklist sync")

        return try {
            val allDomains = mutableSetOf<String>()

            for (tier in TIERS) {
                val syncedDomains = syncTier(tier)
                allDomains.addAll(syncedDomains)
            }

            Log.i(TAG, "Total domains after sync: ${allDomains.size}")

            // Rebuild bloom filter with all current domains
            bloomFilterBuilder.rebuild(allDomains)
            Log.i(TAG, "Bloom filter rebuilt with ${allDomains.size} domains")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.retry()
        }
    }

    private suspend fun syncTier(tier: BlocklistTier): Set<String> {
        // Ensure tier exists in DB
        val existingTier = domainDao.getTierByName(tier.name)
        val tierId: Int
        if (existingTier == null) {
            tierId = domainDao.insertOrReplaceTier(
                TierEntity(name = tier.name, sourceUrl = tier.url)
            ).toInt()
        } else {
            tierId = existingTier.id
        }

        // Download with conditional GET
        val result = try {
            downloader.download(tier.url, existingTier?.etag)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download ${tier.name}: ${e.message}")
            return emptySet()
        }

        if (result.content == null) {
            // 304 Not Modified — return existing domains (we don't need to re-read them from DB)
            Log.d(TAG, "${tier.name}: not modified, skipping")
            return emptySet() // Bloom filter will be rebuilt from DB content
        }

        // Parse new domains
        val domains = HostsFileParser.parse(result.content)
        Log.i(TAG, "${tier.name}: parsed ${domains.size} domains")

        // Replace all domains for this tier
        domainDao.deleteAllDomainsForTier(tierId)

        // Insert in batches for better performance
        val entities = domains.map { DomainEntity(domain = it, tierId = tierId) }
        entities.chunked(BATCH_SIZE).forEach { batch ->
            domainDao.insertDomains(batch)
        }

        // Update tier metadata
        domainDao.updateTierSyncInfo(
            id = tierId,
            etag = result.newEtag,
            lastSync = System.currentTimeMillis() / 1000,
            count = domains.size,
        )

        Log.i(TAG, "${tier.name}: sync complete — ${domains.size} domains")
        return domains
    }
}
