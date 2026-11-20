package com.schwabquant.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_logs",
    indices = [
        Index(value = ["timestampEpochMs"]),
        Index(value = ["category"])
    ]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampEpochMs: Long,
    val category: String,
    val severity: String,
    val symbol: String? = null,
    val executionMode: String,
    val message: String,
    val metadataJson: String? = null
)
