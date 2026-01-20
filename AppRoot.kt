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

private enum class Tab { PFD, MAP, DATA }

@Composable
fun AppRoot(
    navFlow: StateFlow<NavData>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCalibrateAttitude: () -> Unit,
    onToggleDemo: (Boolean) -> Unit,
    isDemo: Boolean
) {
    val nav by navFlow.collectAsState()
    var tab by remember { mutableStateOf(Tab.PFD) }

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
                when (tab) {
                    Tab.PFD -> GlassCockpitPfdScreen(
                        nav = nav,
                        modifier = Modifier.fillMaxSize(),
                        onCalibrate = onCalibrateAttitude
                    )
                    Tab.MAP -> MapScreen(nav = nav, modifier = Modifier.fillMaxSize())
                    Tab.DATA -> RawDataScreen(
                        nav = nav,
                        modifier = Modifier.fillMaxSize(),
                        isDemo = isDemo,
                        onToggleDemo = onToggleDemo
                    )
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
        Text("Grant it once, then you can use this offline in flight mode.", color = Color(0xFFB0B0B0), fontSize = 12.sp)
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
