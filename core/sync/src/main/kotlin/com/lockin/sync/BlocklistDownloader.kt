package com.lockin.sync

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads blocklist host files with ETag-based caching.
 *
 * Uses conditional GET (If-None-Match) to avoid re-downloading unchanged files.
 * Returns null if the server responds with 304 Not Modified.
 */
@Singleton
class BlocklistDownloader @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "BlocklistDownloader"
    }

    data class DownloadResult(
        val content: String?,      // null means "not modified" (304)
        val newEtag: String?,
    )

    /**
     * Downloads the content at [url], using [currentEtag] for conditional GET.
     *
     * @param url The URL of the hosts file to download
     * @param currentEtag The ETag from the last successful download, or null
     * @return [DownloadResult] with content=null if 304 Not Modified
     * @throws Exception on network failure
     */
    fun download(url: String, currentEtag: String? = null): DownloadResult {
        val requestBuilder = Request.Builder().url(url)

        if (currentEtag != null) {
            requestBuilder.header("If-None-Match", currentEtag)
        }

        val request = requestBuilder.build()
        val response = httpClient.newCall(request).execute()

        return when (response.code) {
            304 -> {
                Log.d(TAG, "Not modified: $url (ETag: $currentEtag)")
                DownloadResult(content = null, newEtag = currentEtag)
            }
            200 -> {
                val body = response.body?.string()
                    ?: throw Exception("Empty response body from $url")
                val etag = response.header("ETag")
                Log.i(TAG, "Downloaded ${body.length} bytes from $url (ETag: $etag)")
                DownloadResult(content = body, newEtag = etag)
            }
            else -> throw Exception("HTTP ${response.code} from $url")
        }
    }
}
