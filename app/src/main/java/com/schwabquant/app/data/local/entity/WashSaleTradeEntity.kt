package com.schwabquant.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "wash_sale_trades",
    indices = [
        Index(value = ["underlyingSymbol"]),
        Index(value = ["symbol"]),
        Index(value = ["tradeTimestampEpochMs"]),
        Index(value = ["externalOrderId"])
    ]
)
data class WashSaleTradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val underlyingSymbol: String,
    val symbol: String,
    val assetType: String,
    val instruction: String,
    val quantity: Int,
    val price: Double,
    val realizedPnl: Double? = null,
    val tradeTimestampEpochMs: Long,
    val source: String,
    val externalOrderId: String? = null
)
