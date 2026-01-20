package com.adityaapte.virtualcockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.*
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_URL = "https://demotiles.maplibre.org/style.json"

private const val AIRCRAFT_ICON_ID = "aircraft_icon"
private const val AIRCRAFT_SRC_ID = "aircraft_src"
private const val AIRCRAFT_LAYER_ID = "aircraft_layer"

private const val TRAIL_SRC_ID = "trail_src"
private const val TRAIL_LAYER_ID = "trail_layer"

private const val AIRPORT_ICON_ID = "airport_icon"
private const val AIRPORT_SRC_ID = "airport_src"
private const val AIRPORT_LAYER_ID = "airport_layer"

@Composable
fun MapScreen(
    nav: NavData,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Repo must exist in your project (from the earlier drop-in)
    val repo = remember { AirportRepository.get(ctx) }

    val lat = nav.lat
    val lon = nav.lon
    val track = (nav.trackDeg ?: 0.0).toFloat()

    var follow by remember { mutableStateOf(true) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    // offline progress ui
    var dlPct by remember { mutableStateOf<Int?>(null) }
    var dlActive by remember { mutableStateOf(false) }
    var dlMsg by remember { mutableStateOf<String?>(null) }

    // Airport refresh job (debounce/cancel)
    var airportsJob by remember { mutableStateOf<Job?>(null) }

    val trail = remember { mutableStateListOf<Point>() }

    Box(modifier = modifier) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                MapView(ctx).apply {
                    onCreate(null)
                    onStart()
                    onResume()

                    mapViewRef = this

                    getMapAsync { map ->
                        map.uiSettings.apply {
                            isCompassEnabled = true
                            isRotateGesturesEnabled = true
                            isZoomGesturesEnabled = true
                            isScrollGesturesEnabled = true
                        }

                        map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->

                            // ---- Icons ----
                            style.addImage(AIRCRAFT_ICON_ID, MapIcons.makeAircraftBitmap())
                            style.addImage(AIRPORT_ICON_ID, MapIcons.makeAirportBitmap())

                            // ---- Sources ----
                            style.addSource(
                                GeoJsonSource(
                                    AIRCRAFT_SRC_ID,
                                    Feature.fromGeometry(Point.fromLngLat(0.0, 0.0))
                                )
                            )

                            style.addSource(
                                GeoJsonSource(
                                    TRAIL_SRC_ID,
                                    Feature.fromGeometry(LineString.fromLngLats(emptyList()))
                                )
                            )

                            style.addSource(
                                GeoJsonSource(
                                    AIRPORT_SRC_ID,
                                    FeatureCollection.fromFeatures(emptyArray())
                                )
                            )

                            // ---- Layers ----
                            style.addLayer(
                                LineLayer(TRAIL_LAYER_ID, TRAIL_SRC_ID).withProperties(
                                    lineColor("#00E676"),
                                    lineWidth(3f),
                                    lineOpacity(0.85f)
                                )
                            )

                            style.addLayer(
                                SymbolLayer(AIRCRAFT_LAYER_ID, AIRCRAFT_SRC_ID).withProperties(
                                    iconImage(AIRCRAFT_ICON_ID),
                                    iconAllowOverlap(true),
                                    iconIgnorePlacement(true),
                                    iconSize(1.0f),
                                    iconRotate(get("bearing")),
                                    iconRotationAlignment("map")
                                )
                            )

                            style.addLayer(
                                SymbolLayer(AIRPORT_LAYER_ID, AIRPORT_SRC_ID).withProperties(
                                    iconImage(AIRPORT_ICON_ID),
                                    iconAllowOverlap(true),
                                    iconIgnorePlacement(true),

                                    iconSize(
                                        Expression.interpolate(
                                            Expression.linear(), Expression.zoom(),
                                            Expression.stop(5, 0.6),
                                            Expression.stop(9, 0.9),
                                            Expression.stop(12, 1.1)
                                        )
                                    ),

                                    // label = iata -> icao -> name (properties must exist in the feature)
                                    textField(
                                        Expression.coalesce(
                                            get("iata"),
                                            get("icao"),
                                            get("name")
                                        )
                                    ),
                                    textSize(
                                        Expression.interpolate(
                                            Expression.linear(), Expression.zoom(),
                                            Expression.stop(6, 10),
                                            Expression.stop(10, 12),
                                            Expression.stop(13, 14)
                                        )
                                    ),
                                    textAnchor("top"),
                                    textOffset(arrayOf(0f, 1.0f)),
                                    textAllowOverlap(true),
                                    textIgnorePlacement(true),
                                    textColor("#FFFFFF"),
                                    textHaloColor("#000000"),
                                    textHaloWidth(1.5f)
                                )
                            )

                            // Optional: MapLibre location component (disabled for now)
                            runCatching {
                                val lc = map.locationComponent
                                lc.activateLocationComponent(
                                    LocationComponentActivationOptions.builder(ctx, style).build()
                                )
                                lc.isLocationComponentEnabled = false
                            }

                            styleReady = true

                            // ---- Seed airport DB once (async) ----
                            scope.launch(Dispatchers.IO) {
                                // If your repo method is named differently, change it here:
                                runCatching { repo.ensureSeededFromAssets() }
                            }

                            // ---- Set initial view ----
                            val startLat = nav.lat ?: 20.5937
                            val startLon = nav.lon ?: 78.9629
                            map.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(startLat, startLon), 6.5)
                            )

                            // ---- Airports refresh on camera idle ----
                            fun refreshAirports(m: MapLibreMap) {
                                val s = m.style ?: return
                                val b = m.projection.visibleRegion.latLngBounds
                                val zoom = m.cameraPosition.zoom

                                val sw = b.southWest
                                val ne = b.northEast

                                val south = sw.latitude
                                val west  = sw.longitude
                                val north = ne.latitude
                                val east  = ne.longitude

                                airportsJob?.cancel()
                                airportsJob = scope.launch {
                                    val rows = repo.queryVisible(
                                        south = south,
                                        west = west,
                                        north = north,
                                        east = east,
                                        zoom = zoom
                                    )

                                    val features = rows.map { a ->
                                        Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).also { f ->
                                            f.addStringProperty("name", a.name)
                                            f.addStringProperty("iata", a.iata ?: "")
                                            f.addStringProperty("icao", a.icao ?: "")
                                            f.addStringProperty("type", a.type)
                                        }
                                    }

                                    withContext(Dispatchers.Main) {
                                        s.getSourceAs<GeoJsonSource>(AIRPORT_SRC_ID)
                                            ?.setGeoJson(FeatureCollection.fromFeatures(features))
                                    }
                                }
                            }


                            // initial + on idle
                            refreshAirports(map)
                            map.addOnCameraIdleListener { refreshAirports(map) }
                        }
                    }
                }
            },
            update = { mv ->
                if (!styleReady || lat == null || lon == null) return@AndroidView

                val p = Point.fromLngLat(lon, lat)
                if (trail.isEmpty() || distanceOk(trail.last(), p)) {
                    trail.add(p)
                    while (trail.size > 800) trail.removeAt(0)
                }

                mv.getMapAsync { map ->
                    val style = map.style ?: return@getMapAsync

                    style.getSourceAs<GeoJsonSource>(AIRCRAFT_SRC_ID)?.setGeoJson(
                        Feature.fromGeometry(p).also { f ->
                            f.addNumberProperty("bearing", track.toDouble())
                        }
                    )

                    style.getSourceAs<GeoJsonSource>(TRAIL_SRC_ID)?.setGeoJson(
                        Feature.fromGeometry(LineString.fromLngLats(trail))
                    )

                    if (follow) {
                        map.easeCamera(
                            CameraUpdateFactory.newLatLng(LatLng(lat, lon)),
                            250
                        )
                    }
                }
            }
        )

        // Overlay controls
        Box(Modifier.fillMaxSize()) {

            // Follow toggle
            ControlChip(
                label = if (follow) "FOLLOW: ON" else "FOLLOW: OFF",
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
            ) { follow = !follow }

            // Recenter
            ControlChip(
                label = "RECENTER",
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            ) {
                val mv = mapViewRef ?: return@ControlChip
                if (lat == null || lon == null) return@ControlChip
                follow = true
                mv.getMapAsync { map ->
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 10.5))
                }
            }

            // Zoom controls
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                ControlChip(label = " + ", modifier = Modifier) {
                    mapViewRef?.getMapAsync { map -> map.animateCamera(CameraUpdateFactory.zoomIn()) }
                }
                ControlChip(label = " - ", modifier = Modifier) {
                    mapViewRef?.getMapAsync { map -> map.animateCamera(CameraUpdateFactory.zoomOut()) }
                }
            }

            // Offline Download (safer defaults)
            ControlChip(
                label = if (dlActive) "DOWNLOADING…" else "OFFLINE DL",
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                val mv = mapViewRef ?: return@ControlChip
                if (lat == null || lon == null) return@ControlChip
                if (dlActive) return@ControlChip

                startOfflineDownload(
                    mapView = mv,
                    centerLat = lat,
                    centerLon = lon,
                    onProgress = { pct ->
                        dlActive = true
                        dlPct = pct
                        dlMsg = "Offline: $pct%"
                    },
                    onDone = {
                        dlActive = false
                        dlPct = 100
                        dlMsg = "Offline: done"
                    },
                    onError = {
                        dlActive = false
                        dlMsg = "Offline error: $it"
                    }
                )
            }

            // Download progress chip
            if (dlMsg != null) {
                ControlChip(
                    label = dlMsg!!,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                ) { /* no-op */ }
            }
        }
    }
}

@Composable
private fun ControlChip(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(Color(0xAA000000))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color(0xFF00E676))
    }
}

private fun distanceOk(a: Point, b: Point): Boolean {
    val dx = (a.longitude() - b.longitude())
    val dy = (a.latitude() - b.latitude())
    return (dx * dx + dy * dy) > 1e-10
}

/**
 * OFFLINE DOWNLOAD
 * Safer defaults: smaller area + lower max zoom to avoid crashes.
 */
private fun startOfflineDownload(
    mapView: MapView,
    centerLat: Double,
    centerLon: Double,
    onProgress: (pct: Int) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val radius = 0.25 // degrees (~25-30 km). Much safer than 1.5°
    val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
        .include(LatLng(centerLat + radius, centerLon - radius))
        .include(LatLng(centerLat - radius, centerLon + radius))
        .build()

    val definition: OfflineRegionDefinition = OfflineTilePyramidRegionDefinition(
        STYLE_URL,
        bounds,
        6.0,   // was 4.0
        11.0,  // was 12.0
        mapView.resources.displayMetrics.density
    )

    val metadata = "VC_OFFLINE_${System.currentTimeMillis()}".toByteArray()
    val mgr = OfflineManager.getInstance(mapView.context)

    mgr.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
        override fun onCreate(region: OfflineRegion) {
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    val req = status.requiredResourceCount
                    val done = status.completedResourceCount
                    if (req > 0) {
                        val pct = ((done.toDouble() / req.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(0, 100)
                        onProgress(pct)
                    }
                    if (status.isComplete) onDone()
                }

                override fun onError(error: OfflineRegionError) {
                    onError(error.message ?: "offline error")
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    onError("tile limit exceeded: $limit")
                }
            })

            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }

        override fun onError(error: String) {
            onError(error)
        }
    })
}
