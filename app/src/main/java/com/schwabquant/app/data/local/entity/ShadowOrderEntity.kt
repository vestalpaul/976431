package com.schwabquant.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shadow_orders",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["status"])
    ]
)
data class ShadowOrderEntity(
    @PrimaryKey val orderId: String,
    val symbol: String,
    val orderType: String,
    val session: String,
    val duration: String,
    val strategyType: String,
    val limitPrice: String? = null,
    val stopPrice: String? = null,
    val quantity: Int,
    val status: String,
    val legsJson: String,
    val submittedAtEpochMs: Long,
    val filledAtEpochMs: Long? = null,
    val fillPrice: String? = null
)
