package com.schwabquant.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schwabquant.app.data.local.entity.SentinelRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SentinelRuleDao {

    @Query("SELECT * FROM sentinel_rules ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<SentinelRuleEntity>>

    @Query("SELECT * FROM sentinel_rules WHERE status = 'ARMED' ORDER BY updatedAtEpochMs DESC")
    fun observeArmed(): Flow<List<SentinelRuleEntity>>

    @Query("SELECT * FROM sentinel_rules WHERE status IN ('ARMED', 'EXECUTING') ORDER BY updatedAtEpochMs DESC")
    fun observeActive(): Flow<List<SentinelRuleEntity>>

    @Query("SELECT * FROM sentinel_rules WHERE status = 'ARMED' ORDER BY updatedAtEpochMs DESC")
    suspend fun getArmedOnce(): List<SentinelRuleEntity>

    @Query("SELECT * FROM sentinel_rules WHERE status = :status ORDER BY updatedAtEpochMs DESC")
    suspend fun getByStatus(status: String): List<SentinelRuleEntity>

    @Query("SELECT * FROM sentinel_rules WHERE id = :ruleId LIMIT 1")
    suspend fun getById(ruleId: Long): SentinelRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SentinelRuleEntity): Long

    @Query("UPDATE sentinel_rules SET status = :status, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :ruleId")
    suspend fun updateStatus(ruleId: Long, status: String, updatedAtEpochMs: Long)

    @Query(
        "UPDATE sentinel_rules SET status = :status, lastTriggerNote = :note, " +
            "lastSubmittedOrderId = CASE WHEN :status = 'ARMED' THEN NULL ELSE COALESCE(:lastSubmittedOrderId, lastSubmittedOrderId) END, " +
            "updatedAtEpochMs = :updatedAtEpochMs WHERE id = :ruleId"
    )
    suspend fun updateStatusWithNote(
        ruleId: Long,
        status: String,
        note: String?,
        updatedAtEpochMs: Long,
        lastSubmittedOrderId: String? = null
    )

    @Query("UPDATE sentinel_rules SET status = 'PAUSED', updatedAtEpochMs = :updatedAtEpochMs WHERE status = 'ARMED'")
    suspend fun disarmAll(updatedAtEpochMs: Long)

    @Query("DELETE FROM sentinel_rules WHERE id = :ruleId")
    suspend fun deleteById(ruleId: Long)
}
