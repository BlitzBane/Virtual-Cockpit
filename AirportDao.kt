package com.adityaapte.virtualcockpit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AirportDao {

    // Airports
    @Query("SELECT COUNT(*) FROM airports")
    suspend fun countAirports(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAirports(items: List<AirportEntity>)

    @Query("""
        SELECT * FROM airports
        WHERE lat BETWEEN :south AND :north
          AND lon BETWEEN :west AND :east
        LIMIT :limit
    """)
    suspend fun inBounds(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        limit: Int
    ): List<AirportEntity>

    @Query("SELECT * FROM airports WHERE icao = :icao LIMIT 1")
    suspend fun airportByIcao(icao: String): AirportEntity?

    // Runways
    @Query("SELECT COUNT(*) FROM runways")
    suspend fun countRunways(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRunways(items: List<RunwayEntity>)

    @Query("""
        SELECT * FROM runways
        WHERE airport_ident = :icao
        ORDER BY length_ft DESC
    """)
    suspend fun runwaysByAirport(icao: String): List<RunwayEntity>

    // Frequencies
    @Query("SELECT COUNT(*) FROM airport_frequencies")
    suspend fun countFrequencies(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFrequencies(items: List<AirportFrequencyEntity>)

    @Query("""
        SELECT * FROM airport_frequencies
        WHERE airport_ident = :icao
        ORDER BY type ASC, frequency_mhz ASC
    """)
    suspend fun freqsByAirport(icao: String): List<AirportFrequencyEntity>

    // Navaids
    @Query("SELECT COUNT(*) FROM navaids")
    suspend fun countNavaids(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNavaids(items: List<NavaidEntity>)

    @Query("""
        SELECT * FROM navaids
        WHERE associated_airport = :icao
        ORDER BY type ASC, ident ASC
        LIMIT 50
    """)
    suspend fun navaidsByAssociatedAirport(icao: String): List<NavaidEntity>
}
