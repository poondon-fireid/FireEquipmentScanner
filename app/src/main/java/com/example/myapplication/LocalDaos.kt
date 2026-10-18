package com.example.myapplication

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ManufacturerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<ManufacturerEntity>)

    @Query("SELECT * FROM manufacturers WHERE name LIKE '%' || :query || '%' LIMIT 1")
    suspend fun getByName(query: String): ManufacturerEntity?

    @Query("SELECT COUNT(*) FROM manufacturers")
    suspend fun getCount(): Int
}

@Dao
interface ApprovalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<ApprovalEntity>)

    @Query("SELECT * FROM approvals WHERE manufacturer LIKE '%' || :query || '%'")
    suspend fun getAllByManufacturer(query: String): List<ApprovalEntity>

    @Query("SELECT * FROM approvals")
    suspend fun getAll(): List<ApprovalEntity>

    @Query("SELECT COUNT(*) FROM approvals")
    suspend fun getCount(): Int
}

@Dao
interface CancellationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<CancellationEntity>)

    @Query("SELECT * FROM cancellations WHERE (manufacturer LIKE '%' || :manufacturer || '%' OR :manufacturer LIKE '%' || manufacturer || '%')")
    suspend fun getCancellationsByManufacturer(manufacturer: String): List<CancellationEntity>

    @Query("SELECT COUNT(*) FROM cancellations")
    suspend fun getCount(): Int
}
