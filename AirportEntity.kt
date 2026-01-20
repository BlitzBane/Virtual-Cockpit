package com.adityaapte.virtualcockpit

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "airports",
    indices = [
        Index(value = ["iata"]),
        Index(value = ["icao"]),
        Index(value = ["lat"]),
        Index(value = ["lon"])
    ]
)
data class AirportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iata: String?,
    val icao: String?,
    val lat: Double,
    val lon: Double,
    val type: String?,      // e.g. "large_airport"
    val country: String?    // ISO country if present
)
