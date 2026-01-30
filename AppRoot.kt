package com.adityaapte.virtualcockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.ui.platform.LocalContext


// Offline map style constant (matches MapScreen)
private const val STYLE_URL_DARK_ASSET = "asset://styles/offlinedarkstyle.json"

private enum class Tab { PFD, MAP, DATA, OFFLINE, ABOUT }

@Composable
fun AppRoot(
    navFlow: StateFlow<NavData>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCalibrateAttitude: () -> Unit,
    onToggleDemo: (Boolean) -> Unit,
    onSetMountMode: (MountMode) -> Unit,
    isDemo: Boolean
) {
    val nav by navFlow.collectAsState()
    var tab by remember { mutableStateOf(Tab.PFD) }

    var navSource by remember { mutableStateOf(NavSource.HDG) }
    var directTo by remember { mutableStateOf(DirectToPlan()) }

    // Map state for offline settings
    var mapViewRef by remember { mutableStateOf<org.maplibre.android.maps.MapView?>(null) }

    val navUi = remember(nav, navSource, directTo) {
        nav.copy(navSource = navSource, directTo = directTo)
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(Color.Black)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!hasPermission) {
                PermissionGate(onRequestPermission = onRequestPermission)
            } else {
                // Keep all tabs composed so MapView doesn't get destroyed/recreated on tab switch
                // Keep MAP alive (so it doesn't reload), but don't keep PFD alive
                // because MapView uses SurfaceView and can bleed through.
                Box(Modifier.fillMaxSize()) {

                    // MAP is always composed, but we will toggle its underlying View visibility
                    MapScreen(
                        nav = navUi,
                        modifier = Modifier.fillMaxSize(),
                        isActive = (tab == Tab.MAP),
                        directTo = directTo,
                        navSource = navSource,
                        onSetFrom = { wp ->
                            directTo = directTo.copy(from = wp, isActive = false)
                            navSource = NavSource.HDG
                        },
                        onSetTo = { wp ->
                            directTo = directTo.copy(to = wp, isActive = false)
                            navSource = NavSource.HDG
                        },
                        onActivateGps = {
                            val to = directTo.to ?: return@MapScreen
                            val la = nav.lat ?: return@MapScreen
                            val lo = nav.lon ?: return@MapScreen

                            // Snapshot start at activation time
                            val fromNow = Waypoint(
                                ident = "POS",
                                name = "Present Position",
                                lat = la,
                                lon = lo
                            )

                            directTo = directTo.copy(from = fromNow, isActive = true)
                            navSource = NavSource.GPS
                        }
                        ,
                        onUseHdg = {
                            navSource = NavSource.HDG
                            directTo = directTo.copy(isActive = false)
                        },
                        onClearPlan = {
                            navSource = NavSource.HDG
                            directTo = DirectToPlan()
                        },
                        onMapViewReady = { mapViewRef = it }
                    )


                    // PFD only exists when selected (prevents HSI inset MapView bleeding into MAP tab)
                    if (tab == Tab.PFD) {
                        GlassCockpitPfdScreen(
                            nav = navUi,
                            modifier = Modifier.fillMaxSize(),
                            onCalibrate = onCalibrateAttitude
                        )
                    }

                    // DATA only exists when selected
                    if (tab == Tab.DATA) {
                        RawDataScreen(
                            nav = nav,
                            isDemo = isDemo,
                            onToggleDemo = onToggleDemo,
                            onCalibrateAttitude = onCalibrateAttitude,
                            onSetMountMode = onSetMountMode,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // OFFLINE only exists when selected
                    if (tab == Tab.OFFLINE) {
                        OfflineSettingsScreen(
                            modifier = Modifier.fillMaxSize(),
                            mapViewProvider = { mapViewRef },
                            styleUri = STYLE_URL_DARK_ASSET,
                            onClose = { tab = Tab.MAP }
                        )
                    }

                    // ABOUT only exists when selected
                    if (tab == Tab.ABOUT) {
                        AboutScreen(modifier = Modifier.fillMaxSize())
                    }

                }

            }
        }

        BottomRibbon(selected = tab, onSelect = { tab = it }, isDemo = isDemo)

    }
}

@Composable
private fun PermissionGate(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Location permission is required.", color = Color.White)
        Text(
            "Grant it once, then you can use this offline in flight mode.",
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp
        )
        Box(
            modifier = Modifier
                .background(Color(0xFF1E88E5), RoundedCornerShape(10.dp))
                .clickable { onRequestPermission() }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("Grant Location Permission", color = Color.White)
        }
    }
}

@Composable
private fun BottomRibbon(selected: Tab, onSelect: (Tab) -> Unit, isDemo: Boolean) {
    val bg = Color(0xFF0B0B0B)
    val active = Color(0xFF00E676)
    val inactive = Color(0xFFB0B0B0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(bg)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RibbonItem("PFD", selected == Tab.PFD, active, inactive) { onSelect(Tab.PFD) }
        RibbonItem("MAP", selected == Tab.MAP, active, inactive) { onSelect(Tab.MAP) }
        RibbonItem("DATA", selected == Tab.DATA, active, inactive) { onSelect(Tab.DATA) }
        RibbonItem("OFFLINE", selected == Tab.OFFLINE, active, inactive) { onSelect(Tab.OFFLINE) }
        RibbonItem("ABOUT", selected == Tab.ABOUT, active, inactive) { onSelect(Tab.ABOUT) }


        if (isDemo) {
            Text("DEMO", color = Color(0xFFFFD400), fontSize = 12.sp)
        }
    }
}

@Composable
private fun RibbonItem(label: String, isSelected: Boolean, active: Color, inactive: Color, onClick: () -> Unit) {
    val c = if (isSelected) active else inactive
    Box(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = c, fontSize = 13.sp)
    }
}

private fun Modifier.noTouch(): Modifier =
    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent() // consumes touches when hidden
            }
        }
    }
@Composable
private fun AboutScreen(modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val ctx = LocalContext.current

    val versionText = remember {
        try {
            val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "Version ${p.versionName} (${p.longVersionCode})"
        } catch (_: Exception) {
            "Version —"
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black)
            .padding(16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Virtual Cockpit", color = Color.White, fontSize = 20.sp)
        Text(versionText, color = Color(0xFFB0B0B0), fontSize = 12.sp)

        Divider(color = Color(0xFF222222))

        SectionTitle("Safety & Disclaimer")
        Body(
            """
            • This app is for recreational/educational use only.
            • NOT certified for aviation use and NOT a substitute for aircraft instruments.
            • Do NOT use for real-world navigation, flight planning, or safety-of-life decisions.
            • GPS, sensors, and map data can be inaccurate, delayed, or unavailable.
            • Use at your own risk. No warranties are provided.
            """.trimIndent()
        )

        SectionTitle("Accuracy Notes")
        Body(
            """
            • Position, track, and speed depend on device GPS quality and permissions.
            • Attitude/turn-rate are derived from device sensors and mounting orientation.
            • ETA is computed from distance-to-go divided by current ground speed; low speeds may show "—".
            """.trimIndent()
        )

        SectionTitle("Privacy")
        Body(
            """
            • This app can operate fully offline (flight mode) after permissions are granted.
            • Location/sensor data is used only to render instruments and is not intended to be transmitted by the app.
            """.trimIndent()
        )

        SectionTitle("Tech Info")
        Body(
            """
            • Map engine: MapLibre (offline-capable).
            • Sensors: GPS + IMU (accelerometer/gyro/magnetometer) + barometer (if available).
            • Airport data: local CSV overlay (offline).
            """.trimIndent()
        )

        SectionTitle("About the Developer")
        Body(
            """
            Built by Aditya Apte (Chennai, India).
            This is a personal passion project inspired by glass-cockpit avionics and flight simulation.
            """.trimIndent()
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = Color(0xFF00E676), fontSize = 14.sp)
}

@Composable
private fun Body(t: String) {
    Text(t, color = Color(0xFFDDDDDD), fontSize = 12.sp, lineHeight = 16.sp)
}