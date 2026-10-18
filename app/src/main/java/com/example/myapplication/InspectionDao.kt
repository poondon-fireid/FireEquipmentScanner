package com.example.myapplication

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface InspectionDao {
    @Insert
    suspend fun insertRecord(record: InspectionRecord)

    @Query("SELECT * FROM inspection_records ORDER BY timestamp DESC")
    suspend fun getAllRecords(): List<InspectionRecord>
}
