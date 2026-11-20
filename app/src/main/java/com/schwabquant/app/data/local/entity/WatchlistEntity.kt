package com.schwabquant.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlists")
data class WatchlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val tickersCsv: String,
    val isDefault: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
