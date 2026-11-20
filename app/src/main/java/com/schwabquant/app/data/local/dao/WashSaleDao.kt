package com.schwabquant.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WashSaleDao {

    @Query("SELECT * FROM wash_sale_trades ORDER BY tradeTimestampEpochMs ASC, id ASC")
    fun observeAll(): Flow<List<WashSaleTradeEntity>>

    @Query("SELECT * FROM wash_sale_trades ORDER BY tradeTimestampEpochMs ASC, id ASC")
    suspend fun getAll(): List<WashSaleTradeEntity>

    @Query("SELECT COUNT(*) FROM wash_sale_trades WHERE externalOrderId = :externalOrderId")
    suspend fun countByExternalOrderId(externalOrderId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WashSaleTradeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WashSaleTradeEntity>)

    @Query("DELETE FROM wash_sale_trades WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM wash_sale_trades WHERE source = :source")
    suspend fun deleteBySource(source: String)
}
