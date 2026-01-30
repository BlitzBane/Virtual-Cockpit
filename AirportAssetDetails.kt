package com.adityaapte.virtualcockpit

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

data class AirportExtras(
    val runways: List<RunwayRow>,
    val freqs: List<FreqRow>,
    val navaids: List<NavaidRow>
)

data class RunwayRow(
    val leIdent: String?,
    val heIdent: String?,
    val lengthFt: String?,
    val surface: String?,
    val lighted: String?,
    val closed: String?
) {
    fun summary(): String {
        val le = leIdent?.takeIf { it.isNotBlank() } ?: "?"
        val he = heIdent?.takeIf { it.isNotBlank() } ?: "?"
        val len = lengthFt?.takeIf { it.isNotBlank() } ?: "?"
        val surf = surface?.takeIf { it.isNotBlank() } ?: "?"
        val flags = buildList {
            if (lighted == "1") add("Lighted")
            if (closed == "1") add("Closed")
        }.joinToString(", ")
        return "$le/$he • $len ft • $surf" + if (flags.isNotBlank()) " • $flags" else ""
    }
}

data class FreqRow(
    val type: String?,
    val mhz: String?,
    val desc: String?
) {
    fun summary(): String {
        val t = type?.takeIf { it.isNotBlank() } ?: "UNK"
        val f = mhz?.takeIf { it.isNotBlank() } ?: "?"
        val d = desc?.takeIf { it.isNotBlank() }
        return if (d != null) "$t • $f MHz — $d" else "$t • $f MHz"
    }
}

data class NavaidRow(
    val type: String?,
    val ident: String?,
    val name: String?,
    val freqKHz: String?
) {
    fun summary(): String {
        val t = type?.takeIf { it.isNotBlank() } ?: "NAV"
        val i = ident?.takeIf { it.isNotBlank() } ?: "?"
        val f = freqKHz?.takeIf { it.isNotBlank() }?.let { "$it kHz" }
        val n = name?.takeIf { it.isNotBlank() }
        return listOfNotNull("$t $i", f, n).joinToString(" • ")
    }
}

object AirportAssetsDetails {

    // Simple in-memory caches so we don’t re-scan whole CSV every tap
    private var runwaysByIcao: Map<String, List<RunwayRow>>? = null
    private var freqsByIcao: Map<String, List<FreqRow>>? = null
    private var navaidsByIcao: Map<String, List<NavaidRow>>? = null

    suspend fun load(context: Context, icao: String): AirportExtras {
        val key = icao.trim()
        if (key.isEmpty()) return AirportExtras(emptyList(), emptyList(), emptyList())

        if (runwaysByIcao == null) runwaysByIcao = buildRunways(context)
        if (freqsByIcao == null) freqsByIcao = buildFreqs(context)
        if (navaidsByIcao == null) navaidsByIcao = buildNavaids(context)

        return AirportExtras(
            runways = runwaysByIcao!![key].orEmpty(),
            freqs = freqsByIcao!![key].orEmpty(),
            navaids = navaidsByIcao!![key].orEmpty()
        )
    }

    private fun buildRunways(context: Context): Map<String, List<RunwayRow>> {
        val map = HashMap<String, MutableList<RunwayRow>>(5000)

        context.assets.open("runways.csv").use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                val header = br.readLine() ?: return emptyMap()
                val idx = header.splitCsv().withIndex().associate { it.value.trim() to it.index }
                fun col(name: String) = idx[name] ?: -1

                val iIcao = col("airport_ident")
                val iLen = col("length_ft")
                val iSurf = col("surface")
                val iLight = col("lighted")
                val iClosed = col("closed")
                val iLe = col("le_ident")
                val iHe = col("he_ident")

                while (true) {
                    val line = br.readLine() ?: break
                    val p = line.splitCsv()
                    val icao = p.getOrNull(iIcao)?.trim().orEmpty()
                    if (icao.isEmpty()) continue

                    val row = RunwayRow(
                        leIdent = p.getOrNull(iLe),
                        heIdent = p.getOrNull(iHe),
                        lengthFt = p.getOrNull(iLen),
                        surface = p.getOrNull(iSurf),
                        lighted = p.getOrNull(iLight),
                        closed = p.getOrNull(iClosed)
                    )
                    map.getOrPut(icao) { mutableListOf() }.add(row)
                }
            }
        }
        return map
    }

    private fun buildFreqs(context: Context): Map<String, List<FreqRow>> {
        val map = HashMap<String, MutableList<FreqRow>>(5000)

        context.assets.open("airport-frequencies.csv").use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                val header = br.readLine() ?: return emptyMap()
                val idx = header.splitCsv().withIndex().associate { it.value.trim() to it.index }
                fun col(name: String) = idx[name] ?: -1

                val iIcao = col("airport_ident")
                val iType = col("type")
                val iDesc = col("description")
                val iMhz = col("frequency_mhz")

                while (true) {
                    val line = br.readLine() ?: break
                    val p = line.splitCsv()
                    val icao = p.getOrNull(iIcao)?.trim().orEmpty()
                    if (icao.isEmpty()) continue

                    val row = FreqRow(
                        type = p.getOrNull(iType),
                        mhz = p.getOrNull(iMhz),
                        desc = p.getOrNull(iDesc)
                    )
                    map.getOrPut(icao) { mutableListOf() }.add(row)
                }
            }
        }
        return map
    }

    private fun buildNavaids(context: Context): Map<String, List<NavaidRow>> {
        val map = HashMap<String, MutableList<NavaidRow>>(5000)


        context.assets.open("navaids.csv").use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                val header = br.readLine() ?: return emptyMap()
                val idx = header.splitCsv().withIndex().associate { it.value.trim() to it.index }
                fun col(name: String) = idx[name] ?: -1

                val iAssoc = col("associated_airport")
                val iType = col("type")
                val iIdent = col("ident")
                val iName = col("name")
                val iFreq = col("frequency_khz")

                while (true) {
                    val line = br.readLine() ?: break
                    val p = line.splitCsv()
                    val icao = p.getOrNull(iAssoc)?.trim().orEmpty()
                    if (icao.isEmpty()) continue

                    val row = NavaidRow(
                        type = p.getOrNull(iType),
                        ident = p.getOrNull(iIdent),
                        name = p.getOrNull(iName),
                        freqKHz = p.getOrNull(iFreq)
                    )
                    map.getOrPut(icao) { mutableListOf() }.add(row)
                }
            }
        }
        return map
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
}
