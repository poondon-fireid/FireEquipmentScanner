package com.example.myapplication

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "manufacturers")
data class ManufacturerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,         // 업체명
    val representative: String, // 대표자
    val website: String,      // 홈페이지
    val address: String,      // 주소
    val phone: String,        // 전화번호
    val items: String         // 품목
)
