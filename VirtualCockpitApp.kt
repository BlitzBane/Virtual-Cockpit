package com.adityaapte.virtualcockpit

import android.app.Application
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class VirtualCockpitApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // If you're using the demo style, you still need initialization.
        // Use any non-empty string as apiKey if your tile server doesn't require one.
        MapLibre.getInstance(
            this,
            "DUMMY_KEY",
            WellKnownTileServer.MapLibre
        )
    }
}
