package com.adityaapte.virtualcockpit

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AirportEntity::class,
        RunwayEntity::class,
        AirportFrequencyEntity::class,
        NavaidEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AirportDatabase : RoomDatabase() {
    abstract fun dao(): AirportDao

    companion object {
        @Volatile private var INSTANCE: AirportDatabase? = null

        fun get(context: Context): AirportDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AirportDatabase::class.java,
                    "airports.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
