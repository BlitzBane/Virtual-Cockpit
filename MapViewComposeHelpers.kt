@file:Suppress("unused")

package com.adityaapte.virtualcockpit

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember { MapView(context) }

    DisposableEffect(lifecycleOwner, mapView) {
        // Catch-up: if MapView is created after Activity is already RESUMED,
        // DefaultLifecycleObserver would miss ON_CREATE/ON_START/ON_RESUME.
        runCatching { mapView.onCreate(null) }

        val state = lifecycleOwner.lifecycle.currentState
        if (state.isAtLeast(Lifecycle.State.STARTED)) runCatching { mapView.onStart() }
        if (state.isAtLeast(Lifecycle.State.RESUMED)) runCatching { mapView.onResume() }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Do NOT call onDestroy() here (keeps MapView reusable if composable toggles)
            runCatching { mapView.onPause() }
            runCatching { mapView.onStop() }
        }
    }

    return mapView
}
