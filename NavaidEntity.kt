package com.adityaapte.virtualcockpit

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "navaids",
    indices = [
        Index(value = ["ident"]),
        Index(value = ["associated_airport"])
    ]
)
data class NavaidEntity(
    @PrimaryKey val id: Long,
    val ident: String?,
    val name: String?,
    val type: String?,
    val frequency_khz: Double?,
    val latitude_deg: Double?,
    val longitude_deg: Double?,
    val associated_airport: String?     // ICAO/ident if present
)
