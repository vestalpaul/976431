package com.schwabquant.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schwabquant.app.data.local.entity.ShadowOrderEntity
import com.schwabquant.app.data.local.entity.ShadowPositionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShadowLedgerDao {

    @Query("SELECT * FROM shadow_positions ORDER BY symbol ASC")
    fun observePositions(): Flow<List<ShadowPositionEntity>>

    @Query("SELECT * FROM shadow_positions ORDER BY symbol ASC")
    suspend fun getPositionsSnapshot(): List<ShadowPositionEntity>

    @Query("SELECT * FROM shadow_positions WHERE symbol = :symbol LIMIT 1")
    suspend fun getPositionBySymbol(symbol: String): ShadowPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(position: ShadowPositionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPositions(positions: List<ShadowPositionEntity>)

    @Query("DELETE FROM shadow_positions WHERE symbol = :symbol")
    suspend fun deletePosition(symbol: String)

    @Query("DELETE FROM shadow_positions")
    suspend fun clearPositions()

    @Query("SELECT * FROM shadow_orders ORDER BY submittedAtEpochMs DESC")
    fun observeOrders(): Flow<List<ShadowOrderEntity>>

    @Query("SELECT * FROM shadow_orders ORDER BY submittedAtEpochMs DESC")
    suspend fun getOrdersSnapshot(): List<ShadowOrderEntity>

    @Query("SELECT * FROM shadow_orders WHERE orderId = :orderId LIMIT 1")
    suspend fun getOrderById(orderId: String): ShadowOrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: ShadowOrderEntity)

    @Query("UPDATE shadow_orders SET status = 'CANCELED' WHERE orderId = :orderId")
    suspend fun cancelOrder(orderId: String)
}
