package com.schwabquant.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sentinel_rules",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["status"])
    ]
)
data class SentinelRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symbol: String,
    val assetType: String,
    val ruleType: String,
    val triggerPrice: String,
    val limitOffsetCents: Int = 0,
    val quantity: Int,
    val session: String,
    @ColumnInfo(name = "antiStopHuntSeconds")
    val holdSeconds: Int = 15,
    val status: String,
    val isShadowMode: Boolean = true,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "0.35")
    val slippageCollarPct: Double = 0.35,
    @ColumnInfo(defaultValue = "3")
    val minConsecutiveTicks: Int = 3,
    @ColumnInfo(defaultValue = "1.5")
    val maxSpreadPct: Double = 1.5,
    @ColumnInfo(defaultValue = "0.0")
    val sweepBufferPct: Double = 0.0,
    @ColumnInfo(defaultValue = "10")
    val antiKnifeLookbackMinutes: Int = 10,
    @ColumnInfo(defaultValue = "0.25")
    val antiKnifeBouncePct: Double = 0.25,
    @ColumnInfo(defaultValue = "0")
    val isShortPosition: Boolean = false,
    val lastTriggerNote: String? = null,
    val lastSubmittedOrderId: String? = null
)
