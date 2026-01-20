package com.adityaapte.virtualcockpit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class AirportRepository private constructor(
    private val context: Context,
    private val dao: AirportDao
) {
    companion object {
        @Volatile private var INSTANCE: AirportRepository? = null

        fun get(context: Context): AirportRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AirportRepository(
                    context.applicationContext,
                    AirportDatabase.get(context).dao()
                ).also { INSTANCE = it }
            }
    }

    /**
     * One-time import from assets/airports.csv
     */
    suspend fun ensureSeededFromAssets() = withContext(Dispatchers.IO) {
        if (dao.count() > 0) return@withContext

        context.assets.open("airports.csv").use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                val header = br.readLine() ?: return@withContext
                val idx = header.splitCsv().withIndex().associate { it.value.trim() to it.index }

                fun col(name: String) = idx[name] ?: -1

                val iId = col("id")
                val iType = col("type")
                val iName = col("name")
                val iLat = col("latitude_deg")
                val iLon = col("longitude_deg")
                val iIata = col("iata_code")
                val iIcao = col("ident") // OurAirports "ident" (often ICAO or local ident)

                val batch = ArrayList<AirportEntity>(2000)

                while (true) {
                    val line = br.readLine() ?: break
                    val parts = line.splitCsv()
                    if (parts.size < 6) continue

                    val id = parts.getOrNull(iId)?.toIntOrNull() ?: continue
                    val type = parts.getOrNull(iType).orEmpty()

                    // Keep only airport-ish rows
                    if (!type.contains("airport", ignoreCase = true)) continue

                    val lat = parts.getOrNull(iLat)?.toDoubleOrNull() ?: continue
                    val lon = parts.getOrNull(iLon)?.toDoubleOrNull() ?: continue

                    val name = parts.getOrNull(iName).orEmpty()
                    val iata = parts.getOrNull(iIata)?.trim()?.takeIf { it.isNotEmpty() }
                    val icao = parts.getOrNull(iIcao)?.trim()?.takeIf { it.isNotEmpty() }

                    batch.add(
                        AirportEntity(
                            id = id,
                            type = type,
                            name = name,
                            lat = lat,
                            lon = lon,
                            iata = iata,
                            icao = icao
                        )
                    )

                    if (batch.size >= 2000) {
                        dao.insertAll(batch)
                        batch.clear()
                    }
                }

                if (batch.isNotEmpty()) dao.insertAll(batch)
            }
        }
    }

    /**
     * Query airports that fall inside the current visible map bounds.
     * Handles the antimeridian (dateline) by splitting into two queries if west > east.
     */
    suspend fun queryVisible(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        zoom: Double
    ): List<AirportEntity> = withContext(Dispatchers.IO) {

        val limit = when {
            zoom < 6.0 -> 150
            zoom < 8.0 -> 300
            zoom < 10.0 -> 700
            else -> 1500
        }

        if (west <= east) {
            dao.inBounds(
                south = south,
                north = north,
                west = west,
                east = east,
                limit = limit
            )
        } else {
            // Dateline-safe split:
            // (west..180) U (-180..east)
            val left = dao.inBounds(
                south = south,
                north = north,
                west = west,
                east = 180.0,
                limit = limit
            )
            val right = dao.inBounds(
                south = south,
                north = north,
                west = -180.0,
                east = east,
                limit = limit
            )
            (left + right).distinctBy { it.id }.take(limit)
        }
    }
}

/**
 * Minimal CSV splitter that supports quoted fields with commas.
 * Works well for OurAirports CSV.
 */
private fun String.splitCsv(): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0

    while (i < length) {
        val ch = this[i]
        when (ch) {
            '"' -> {
                // Escaped quotes ""
                if (inQuotes && i + 1 < length && this[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            }

            ',' -> {
                if (inQuotes) {
                    sb.append(ch)
                } else {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
            }

            else -> sb.append(ch)
        }
        i++
    }

    out.add(sb.toString())
    return out
}
