package com.adityaapte.virtualcockpit

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "airport_frequencies",
    indices = [
        Index(value = ["airport_ident"]),
        Index(value = ["airport_ref"])
    ]
)
data class AirportFrequencyEntity(
    @PrimaryKey val id: Long,
    val airport_ref: Long?,
    val airport_ident: String?,     // ICAO/ident
    val type: String?,
    val description: String?,
    val frequency_mhz: Double?
)
