package com.example.myapplication

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cancellations")
data class CancellationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val no: String,
    val manufacturer: String,
    val itemName: String,
    val approvalNo: String,
    val cancelDate: String,
    val reason: String
)
