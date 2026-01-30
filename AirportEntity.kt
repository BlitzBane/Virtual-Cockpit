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
    @PrimaryKey val id: Long,          // CSV id (NO auto-generate)
    val name: String,
    val iata: String?,
    val icao: String?,                 // from airports.csv "ident"
    val lat: Double,
    val lon: Double,
    val type: String?,
    val country: String?
)
