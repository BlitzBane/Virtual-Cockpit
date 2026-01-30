package com.adityaapte.virtualcockpit

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "runways",
    indices = [
        Index(value = ["airport_ident"]),
        Index(value = ["airport_ref"])
    ]
)
data class RunwayEntity(
    @PrimaryKey val id: Long,
    val airport_ref: Long?,
    val airport_ident: String?,     // ICAO/ident
    val length_ft: Int?,
    val width_ft: Int?,
    val surface: String?,
    val lighted: Int?,
    val closed: Int?,

    val le_ident: String?,
    val le_heading_degT: Double?,
    val he_ident: String?,
    val he_heading_degT: Double?
)
