package com.adityaapte.virtualcockpit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun OfflineSettingsScreen(
    modifier: Modifier = Modifier,
    mapViewProvider: () -> org.maplibre.android.maps.MapView?,
    styleUri: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val downloadManager = remember { OfflineDownloadManager(context) }

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<OfflineDownloadManager.DownloadProgress?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var selectedZoom by remember { mutableStateOf(6) }

    // --- Make download status reactive (Compose state) ---
    val offlinePrefs = remember { context.getSharedPreferences("offline_tiles", Context.MODE_PRIVATE) }

    var isComplete by remember { mutableStateOf(downloadManager.isDownloadComplete()) }
    var downloadedZoom by remember { mutableStateOf(downloadManager.getDownloadedZoom()) }

    DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "download_complete" || key == "download_zoom") {
                isComplete = downloadManager.isDownloadComplete()
                downloadedZoom = downloadManager.getDownloadedZoom()
            }
        }
        offlinePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { offlinePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val hasInternet = hasInternetConnection(context)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Offline Maps",
                color = Color(0xFFE8EDF2),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "CLOSE",
                color = Color(0xFF00E676),
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(8.dp)
            )
        }

        // Status section
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A2128))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Status",
                    color = Color(0xFF00E676),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                if (isComplete) {
                    Text(
                        "✓ Offline tiles downloaded",
                        color = Color(0xFF00E676)
                    )
                    Text(
                        "Zoom level: $downloadedZoom",
                        color = Color(0xFFB8C2CC),
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        "No offline tiles downloaded",
                        color = Color(0xFFFFD400)
                    )
                }

                Text(
                    if (hasInternet) "Internet: Connected" else "Internet: Offline",
                    color = if (hasInternet) Color(0xFF00E676) else Color(0xFFFF6B6B),
                    fontSize = 14.sp
                )
            }
        }

        // Download progress (if active)
        if (isDownloading && downloadProgress != null) {
            val progress = downloadProgress!!
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A2128))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Downloading...",
                        color = Color(0xFFE8EDF2),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    LinearProgressIndicator(
                        progress = progress.percentComplete / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Color(0xFF00E676),
                        trackColor = Color(0xFF2A3A45)
                    )

                    Text(
                        "${progress.percentComplete}% (${progress.completedResources} / ${progress.requiredResources} tiles)",
                        color = Color(0xFFB8C2CC),
                        fontSize = 14.sp
                    )

                    Text(
                        "This may take several minutes. Keep the app open.",
                        color = Color(0xFFFFD400),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Error display
        if (downloadError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3D1A1A))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Error",
                        color = Color(0xFFFF6B6B),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        downloadError!!,
                        color = Color(0xFFFFB8B8),
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Zoom selection
        if (!isDownloading) {
            Text(
                "Select Download Quality",
                color = Color(0xFFE8EDF2),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                "Higher zoom levels provide more detail but require more storage and download time.",
                color = Color(0xFFB8C2CC),
                fontSize = 13.sp
            )

            OfflineDownloadManager.ZOOM_CONFIGS.forEach { config ->
                ZoomOptionCard(
                    config = config,
                    isSelected = selectedZoom == config.zoom,
                    onClick = { selectedZoom = config.zoom }
                )
            }

            // Download button
            Spacer(Modifier.height(8.dp))

            val canDownload = hasInternet && !isDownloading
            val buttonText = when {
                !hasInternet -> "Internet Required"
                isComplete -> "Re-download at Zoom $selectedZoom"
                else -> "Download at Zoom $selectedZoom"
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (canDownload) Color(0xFF00E676) else Color(0xFF2A3A45)
                    )
                    .clickable(enabled = canDownload) {
                        val mapView = mapViewProvider() ?: return@clickable

                        isDownloading = true
                        downloadError = null
                        downloadProgress = null

                        scope.launch {
                            // Delete old region if exists
                            downloadManager.deleteExistingRegion { _ ->
                                downloadManager.resetDownload()
                            }

                            // Start new download
                            downloadManager.startWorldDownload(
                                mapView = mapView,
                                styleUri = styleUri,
                                maxZoom = selectedZoom,
                                onProgress = { progress ->
                                    downloadProgress = progress
                                },
                                onComplete = {
                                    isDownloading = false
                                    downloadProgress = null
                                    isComplete = downloadManager.isDownloadComplete()
                                    downloadedZoom = downloadManager.getDownloadedZoom()
                                },
                                onError = { error ->
                                    // Always show the error card
                                    downloadError = error

                                    // BUT only stop downloading UI for a real/fatal error.
                                    // Retry messages come from OfflineDownloadManager as: "timeout (retry x/y ...)"
                                    val isRetry = error.contains("timeout", ignoreCase = true) &&
                                            error.contains("retry", ignoreCase = true)

                                    if (!isRetry) {
                                        isDownloading = false
                                    }
                                    // If it IS a retry: keep isDownloading = true so the Downloading card stays visible
                                }

                            )
                        }
                    }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    buttonText,
                    color = if (canDownload) Color.Black else Color(0xFF6A7A85),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Delete button (if download exists)
            if (isComplete) {
                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3D1A1A))
                        .clickable {
                            val mapView = mapViewProvider() ?: return@clickable
                            downloadManager.deleteExistingRegion { _ ->
                                downloadManager.resetDownload()
                                isComplete = false
                                downloadedZoom = 0
                            }
                        }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Delete Offline Tiles",
                        color = Color(0xFFFF6B6B),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Info section
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A2128))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "How it works",
                    color = Color(0xFF00E676),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "• Downloads map tiles for the entire world at your selected zoom level",
                    color = Color(0xFFB8C2CC),
                    fontSize = 13.sp
                )
                Text(
                    "• Once downloaded, maps work completely offline",
                    color = Color(0xFFB8C2CC),
                    fontSize = 13.sp
                )
                Text(
                    "• When online, higher detail tiles load automatically",
                    color = Color(0xFFB8C2CC),
                    fontSize = 13.sp
                )
                Text(
                    "• You can re-download at a different zoom level anytime",
                    color = Color(0xFFB8C2CC),
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun ZoomOptionCard(
    config: OfflineDownloadManager.ZoomConfig,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) Color(0xFF00E676).copy(alpha = 0.2f)
                else Color(0xFF1A2128)
            )
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Zoom Level ${config.zoom}",
                    color = if (isSelected) Color(0xFF00E676) else Color(0xFFE8EDF2),
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    "${config.tileCount} • ${config.estimatedSize}",
                    color = Color(0xFFB8C2CC),
                    fontSize = 13.sp
                )
            }

            if (isSelected) {
                Text(
                    "✓",
                    color = Color(0xFF00E676),
                    fontSize = 24.sp
                )
            }
        }
    }
}

private fun hasInternetConnection(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}