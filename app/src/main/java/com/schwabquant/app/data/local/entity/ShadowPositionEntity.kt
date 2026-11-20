package com.schwabquant.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shadow_positions")
data class ShadowPositionEntity(
    @PrimaryKey val symbol: String,
    val assetType: String,
    val underlyingSymbol: String,
    val quantity: String,
    val averageCost: String,
    val markPrice: String,
    val openedAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
