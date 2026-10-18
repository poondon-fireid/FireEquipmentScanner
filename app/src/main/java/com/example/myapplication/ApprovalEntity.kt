package com.example.myapplication

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "approvals")
data class ApprovalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val certType: String,     // "형식승인", "성능인증", "KFI인증"
    val manufacturer: String,
    val itemName: String,
    val approvalNo: String,
    val approvalDate: String,
    val spec: String
)
