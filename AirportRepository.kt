package com.adityaapte.virtualcockpit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt

data class AirportDetails(
    val airport: AirportEntity,
    val runways: List<RunwayEntity>,
    val frequencies: List<AirportFrequencyEntity>,
    val navaids: List<NavaidEntity>
)

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

    suspend fun ensureSeededFromAssets() = withContext(Dispatchers.IO) {
        seedAirportsIfNeeded()
        seedRunwaysIfNeeded()
        seedFrequenciesIfNeeded()
        seedNavaidsIfNeeded()
    }

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
            dao.inBounds(south, north, west, east, limit)
        } else {
            val left = dao.inBounds(south, north, west, 180.0, limit)
            val right = dao.inBounds(south, north, -180.0, east, limit)
            (left + right).distinctBy { it.id }.take(limit)
        }
    }

    suspend fun getDetailsByIcao(icao: String): AirportDetails? = withContext(Dispatchers.IO) {
        val airport = dao.airportByIcao(icao) ?: return@withContext null
        val runways = dao.runwaysByAirport(icao)
        val freqs = dao.freqsByAirport(icao)
        val navaids = dao.navaidsByAssociatedAirport(icao)
        AirportDetails(airport, runways, freqs, navaids)
    }

    // ------------------ Seeding ------------------

    private suspend fun seedAirportsIfNeeded() {
        if (dao.countAirports() > 0) return

        context.assets.open("airports.csv").use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                val header = br.readLine() ?: return
                val idx = header.splitCsv().withIndex().associate { it.value.trim() to it.index }
                fun col(name: String) = idx[name] ?: -1

                val iId = col("id")
                val iType = col("type")
                val iName = col("name")
                val iLat = col("latitude_deg")
                val iLon = col("longitude_deg")
                val iIata = col("iata_code")
                val iIdent = col("ident")
                val iCountry = col("iso_country")

                val batch = ArrayList<AirportEntity>(2000)

                while (true) {
                    val line = br.readLine() ?: break
                    val parts = line.splitCsv()
                    if (parts.size < 10) continue

                    val id = parts.getOrNull(iId)?.toLongOrNull() ?: continue
                    val type = parts.getOrNull(iType).orEmpty()
                    if (!type.contains("airport", ignoreCase = true)) continue

                    val lat = parts.getOrNull(iLat)?.toDoubleOrNull() ?: continue
                    val lon = parts.getOrNull(iLon)?.toDoubleOrNull() ?: continue

                    batch.add(
                        AirportEntity(
                            id = id,
                            type = type,
                            name = parts.getOrNull(iName).orEmpty(),
                            lat = lat,
                            lon = lon,
                            iata = parts.getOrNull(iIata)?.trim()?.takeIf { it.isNotEmpty() },
                            icao = parts.getOrNull(iIdent)?.trim()?.takeIf { it.isNotEmpty() },
                            country = parts.getOrNull(iCountry)?.trim()
                        )
                    )

                    if (batch.size >= 2000) {
                        dao.insertAirports(batch)
                        batch.clear()
                    }
                }

                if (batch.isNotEmpty()) dao.insertAirports(batch)
            }
        }
    }

    private suspend fun seedRunwaysIfNeeded() {
        if (dao.countRunways() > 0) return

        context.assets.open("runways.csv").use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                val header = br.readLine() ?: return
                val idx = header.splitCsv().withIndex().associate { it.value.trim() to it.index }
                fun col(name: String) = idx[name] ?: -1

                val iId = col("id")
                val iAirportRef = col("airport_ref")
                val iAirportIdent = col("airport_ident")
                val iLen = col("length_ft")
                val iWid = col("width_ft")
                val iSurface = col("surface")
                val iLighted = col("lighted")
                val iClosed = col("closed")
                val iLeIdent = col("le_ident")
                val iLeHdg = col("le_heading_degT")
                val iHeIdent = col("he_ident")
                val iHeHdg = col("he_heading_degT")

                val batch = ArrayList<RunwayEntity>(3000)

                while (true) {
                    val line = br.readLine() ?: break
                    val p = line.splitCsv()
                    val id = p.getOrNull(iId)?.toLongOrNull() ?: continue

                    batch.add(
                        RunwayEntity(
                            id = id,
                            airport_ref = p.getOrNull(iAirportRef)?.toLongOrNull(),
                            airport_ident = p.getOrNull(iAirportIdent)?.trim()?.takeIf { it.isNotEmpty() },
                            length_ft = p.getOrNull(iLen)?.toDoubleOrNull()?.roundToInt(),
                            width_ft = p.getOrNull(iWid)?.toDoubleOrNull()?.roundToInt(),
                            surface = p.getOrNull(iSurface)?.trim(),
                            lighted = p.getOrNull(iLighted)?.toIntOrNull(),
                            closed = p.getOrNull(iClosed)?.toIntOrNull(),
                            le_ident = p.getOrNull(iLeIdent)?.trim(),
                            le_heading_degT = p.getOrNull(iLeHdg)?.toDoubleOrNull(),
                            he_ident = p.getOrNull(iHeIdent)?.trim(),
                            he_heading_degT = p.getOrNull(iHeHdg)?.toDoubleOrNull()
                        )
                    )

                    if (batch.size >= 3000) {
                        dao.insertRunways(batch)
                        batch.clear()
                    }
                }

                if (batch.isNotEmpty()) dao.insertRunways(batch)
            }
        }
    }

    private suspend fun seedFrequenciesIfNeeded() {
        if (dao.countFrequencies() > 0) return

        context.assets.open("airport-frequencies.csv").use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                val header = br.readLine() ?: return
                val idx = header.splitCsv().withIndex().associate { it.value.trim() to it.index }
                fun col(name: String) = idx[name] ?: -1

                val iId = col("id")
                val iAirportRef = col("airport_ref")
                val iAirportIdent = col("airport_ident")
                val iType = col("type")
                val iDesc = col("description")
                val iFreq = col("frequency_mhz")

                val batch = ArrayList<AirportFrequencyEntity>(3000)

                while (true) {
                    val line = br.readLine() ?: break
                    val p = line.splitCsv()
                    val id = p.getOrNull(iId)?.toLongOrNull() ?: continue

                    batch.add(
                        AirportFrequencyEntity(
                            id = id,
                            airport_ref = p.getOrNull(iAirportRef)?.toLongOrNull(),
                            airport_ident = p.getOrNull(iAirportIdent)?.trim()?.takeIf { it.isNotEmpty() },
                            type = p.getOrNull(iType)?.trim(),
                            description = p.getOrNull(iDesc)?.trim(),
                            frequency_mhz = p.getOrNull(iFreq)?.toDoubleOrNull()
                        )
                    )

                    if (batch.size >= 3000) {
                        dao.insertFrequencies(batch)
                        batch.clear()
                    }
                }

                if (batch.isNotEmpty()) dao.insertFrequencies(batch)
            }
        }
    }

    private suspend fun seedNavaidsIfNeeded() {
        if (dao.countNavaids() > 0) return

        context.assets.open("navaids.csv").use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                val header = br.readLine() ?: return
                val idx = header.splitCsv().withIndex().associate { it.value.trim() to it.index }
                fun col(name: String) = idx[name] ?: -1

                val iId = col("id")
                val iIdent = col("ident")
                val iName = col("name")
                val iType = col("type")
                val iFreq = col("frequency_khz")
                val iLat = col("latitude_deg")
                val iLon = col("longitude_deg")
                val iAssoc = col("associated_airport")

                val batch = ArrayList<NavaidEntity>(3000)

                while (true) {
                    val line = br.readLine() ?: break
                    val p = line.splitCsv()
                    val id = p.getOrNull(iId)?.toLongOrNull() ?: continue

                    batch.add(
                        NavaidEntity(
                            id = id,
                            ident = p.getOrNull(iIdent)?.trim(),
                            name = p.getOrNull(iName)?.trim(),
                            type = p.getOrNull(iType)?.trim(),
                            frequency_khz = p.getOrNull(iFreq)?.toDoubleOrNull(),
                            latitude_deg = p.getOrNull(iLat)?.toDoubleOrNull(),
                            longitude_deg = p.getOrNull(iLon)?.toDoubleOrNull(),
                            associated_airport = p.getOrNull(iAssoc)?.trim()?.takeIf { it.isNotEmpty() }
                        )
                    )

                    if (batch.size >= 3000) {
                        dao.insertNavaids(batch)
                        batch.clear()
                    }
                }

                if (batch.isNotEmpty()) dao.insertNavaids(batch)
            }
        }
    }
}

private fun String.splitCsv(): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0

    while (i < length) {
        val ch = this[i]
        when (ch) {
            '"' -> {
                if (inQuotes && i + 1 < length && this[i + 1] == '"') {
                    sb.append('"'); i++
                } else inQuotes = !inQuotes
            }
            ',' -> if (inQuotes) sb.append(ch) else {
                out.add(sb.toString()); sb.setLength(0)
            }
            else -> sb.append(ch)
        }
        i++
    }
    out.add(sb.toString())
    return out
}
