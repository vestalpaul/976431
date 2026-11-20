package com.schwabquant.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.schwabquant.app.data.local.entity.WatchlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {

    @Query("SELECT * FROM watchlists ORDER BY isDefault DESC, createdAtEpochMs ASC")
    fun observeAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlists ORDER BY isDefault DESC, createdAtEpochMs ASC")
    suspend fun getAllOnce(): List<WatchlistEntity>

    @Query("SELECT * FROM watchlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WatchlistEntity?

    @Query("SELECT * FROM watchlists WHERE isDefault = 1 OR name = :defaultName LIMIT 1")
    suspend fun findDefault(defaultName: String = "Core 25 Liquid"): WatchlistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WatchlistEntity): Long

    @Update
    suspend fun update(entity: WatchlistEntity)

    @Query("DELETE FROM watchlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM watchlists")
    suspend fun count(): Int
}
