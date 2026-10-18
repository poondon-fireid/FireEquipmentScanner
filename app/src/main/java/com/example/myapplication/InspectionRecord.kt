package com.example.myapplication

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inspection_records")
data class InspectionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val formatCode: String,
    val type: String,
    val manufacturer: String,
    val formMaterial: String,
    val timestamp: Long
)
