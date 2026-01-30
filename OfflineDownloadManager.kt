package com.adityaapte.virtualcockpit

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.offline.*
import kotlin.math.pow
import android.net.Uri
import java.io.File


/**
 * Manages one-time offline tile download for the entire world.
 * Downloads tiles at a user-selected zoom level and caches them permanently.
 */
class OfflineDownloadManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("offline_tiles", Context.MODE_PRIVATE)
    private val FALLBACK_DOWNLOAD_STYLE =
        "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

    companion object {
        private const val KEY_DOWNLOAD_COMPLETE = "download_complete"
        private const val KEY_DOWNLOAD_ZOOM = "download_zoom"
        private const val REGION_NAME = "VC_WORLD_TILES"

        // Zoom level configurations with estimated tile counts and sizes
        val ZOOM_CONFIGS = listOf(
            ZoomConfig(4, "~170 tiles", "~20 MB"),
            ZoomConfig(5, "~650 tiles", "~80 MB"),
            ZoomConfig(6, "~2,600 tiles", "~300 MB"),
            ZoomConfig(7, "~10,000 tiles", "~1.2 GB"),
            ZoomConfig(8, "~40,000 tiles", "~4.5 GB")
        )
    }

    data class ZoomConfig(
        val zoom: Int,
        val tileCount: String,
        val estimatedSize: String
    )

    data class DownloadProgress(
        val completedResources: Long,
        val requiredResources: Long,
        val percentComplete: Int,
        val isComplete: Boolean
    )

    /**
     * Check if offline tiles have been downloaded for any zoom level
     */
    fun isDownloadComplete(): Boolean {
        return prefs.getBoolean(KEY_DOWNLOAD_COMPLETE, false)
    }

    /**
     * Get the zoom level of the downloaded tiles
     */
    fun getDownloadedZoom(): Int {
        return prefs.getInt(KEY_DOWNLOAD_ZOOM, 0)
    }

    /**
     * Mark download as complete
     */
    private fun markDownloadComplete(zoom: Int) {
        prefs.edit()
            .putBoolean(KEY_DOWNLOAD_COMPLETE, true)
            .putInt(KEY_DOWNLOAD_ZOOM, zoom)
            .apply()
    }

    /**
     * Reset download state (for re-downloading at different zoom)
     */
    fun resetDownload() {
        prefs.edit()
            .putBoolean(KEY_DOWNLOAD_COMPLETE, false)
            .putInt(KEY_DOWNLOAD_ZOOM, 0)
            .apply()
    }

    /**
     * Delete existing offline region if it exists
     */
    fun deleteExistingRegion(onComplete: (Boolean) -> Unit) {
        try {
            val mgr = OfflineManager.getInstance(context)

            mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    if (offlineRegions.isNullOrEmpty()) {
                        onComplete(false)
                        return
                    }

                    val regionsToDelete = offlineRegions.filter {
                        try {
                            String(it.metadata, Charsets.UTF_8).startsWith(REGION_NAME)
                        } catch (e: Exception) {
                            false
                        }
                    }

                    if (regionsToDelete.isEmpty()) {
                        onComplete(false)
                        return
                    }

                    var remainingDeletions = regionsToDelete.size
                    var anyDeleted = false

                    val deleteCallback = object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {
                            anyDeleted = true
                            remainingDeletions--
                            if (remainingDeletions == 0) {
                                onComplete(anyDeleted)
                            }
                        }

                        override fun onError(error: String) {
                            // Log or handle error
                            remainingDeletions--
                            if (remainingDeletions == 0) {
                                onComplete(anyDeleted)
                            }
                        }
                    }

                    regionsToDelete.forEach { region ->
                        region.delete(deleteCallback)
                    }
                }

                override fun onError(error: String) {
                    onComplete(false)
                }
            })
        } catch (e: Exception) {
            onComplete(false)
        }
    }

    /**
     * Start downloading world tiles at the specified zoom level
     */
    fun startWorldDownload(
        mapView: MapView,
        styleUri: String,
        maxZoom: Int,
        onProgress: (DownloadProgress) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        // World bounds
        val bounds = LatLngBounds.Builder()
            .include(LatLng(85.0, -180.0))  // North-West (max latitude for Web Mercator)
            .include(LatLng(-85.0, 180.0))   // South-East
            .build()

        val styleForDownload = styleUriForOffline(styleUri)

        val definition: OfflineRegionDefinition = OfflineTilePyramidRegionDefinition(
            styleForDownload,
            bounds,
            0.0,
            maxZoom.toDouble(),
            mapView.resources.displayMetrics.density
        )


        val metadata = "${REGION_NAME}_zoom${maxZoom}_${System.currentTimeMillis()}"
            .toByteArray(Charsets.UTF_8)

        val mgr = OfflineManager.getInstance(context)

        mgr.createOfflineRegion(definition, metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    var lastProgress = DownloadProgress(0, 0, 0, false)
                    var isFinished = false


                    // Add these:
                    var retryCount = 0
                    val maxRetries = 100
                    val retryDelayMs = 100L
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())

                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val isPrecise = status.isRequiredResourceCountPrecise

                            val req = if (isPrecise) status.requiredResourceCount else 0L
                            val done = status.completedResourceCount

                            val pct = if (isPrecise && req > 0L) {
                                ((done.toDouble() / req.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                            } else 0

                            val progress = DownloadProgress(
                                completedResources = done,
                                requiredResources = req,
                                percentComplete = pct,
                                isComplete = status.isComplete
                            )

                            // Improve reporting: update if counts change (not only percent)
                            if (progress.percentComplete != lastProgress.percentComplete ||
                                progress.isComplete != lastProgress.isComplete ||
                                progress.completedResources != lastProgress.completedResources ||
                                progress.requiredResources != lastProgress.requiredResources
                            ) {
                                lastProgress = progress
                                onProgress(progress)
                            }

                            if (status.isComplete && !isFinished) {
                                isFinished = true
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                markDownloadComplete(maxZoom)
                                onComplete()
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            val msg = (error.message ?: "Unknown offline download error").trim()
                            val looksLikeTimeout = msg.contains("timeout", ignoreCase = true) ||
                                    msg.contains("timed out", ignoreCase = true)

                            // If it's a timeout, DON'T fail the whole job—retry
                            if (!isFinished && looksLikeTimeout && retryCount < maxRetries) {
                                retryCount++

                                // Keep region alive; restart after a short delay
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                handler.postDelayed({
                                    if (!isFinished) region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                                }, retryDelayMs)

                                // Tell UI it's retrying (so you can see it immediately)
                                onError("timeout (retry $retryCount/$maxRetries in ${retryDelayMs}ms)")
                                return
                            }

                            // Non-timeout (or too many retries): stop and fail
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            onError("Offline download error: $msg")
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            onError("Tile count limit exceeded: $limit tiles")
                        }
                    })

                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }


                override fun onError(error: String) {
                    onError("Failed to create offline region: $error")
                }
            })
    }

    /**
     * Calculate approximate tile count for a zoom level (whole world)
     */
    fun estimateTileCount(zoom: Int): Long {
        // For Web Mercator: tiles at zoom level z = 4^z
        // (2^z tiles horizontally × 2^z tiles vertically)
        return 4.0.pow(zoom).toLong()
    }



    private fun styleUriForOffline(styleUri: String): String {
        return if (styleUri.startsWith("asset://") || styleUri.startsWith("asset:///") ||
            styleUri.startsWith("file://")) {
            FALLBACK_DOWNLOAD_STYLE
        } else {
            styleUri
        }
    }


}


