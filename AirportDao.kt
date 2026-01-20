package com.adityaapte.virtualcockpit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AirportDao {

    @Query("SELECT COUNT(*) FROM airports")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<AirportEntity>)

    @Query("""
        SELECT * FROM airports
        WHERE lat BETWEEN :south AND :north
          AND lon BETWEEN :west AND :east
          AND type IN (:types)
        LIMIT :limit
    """)
    suspend fun inBounds(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        types: List<String>,
        limit: Int
    ): List<AirportEntity>

    // Dateline-safe version
    @Query("""
        SELECT * FROM airports
        WHERE lat BETWEEN :south AND :north
          AND (lon >= :west OR lon <= :east)
          AND type IN (:types)
        LIMIT :limit
    """)
    suspend fun inBoundsDateline(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        types: List<String>,
        limit: Int
    ): List<AirportEntity>
}
