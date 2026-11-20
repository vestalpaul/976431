package com.schwabquant.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.schwabquant.app.data.local.dao.AuditLogDao
import com.schwabquant.app.data.local.dao.SentinelRuleDao
import com.schwabquant.app.data.local.dao.ShadowLedgerDao
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.dao.WatchlistDao
import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.data.local.entity.SentinelRuleEntity
import com.schwabquant.app.data.local.entity.ShadowOrderEntity
import com.schwabquant.app.data.local.entity.ShadowPositionEntity
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import com.schwabquant.app.data.local.entity.WatchlistEntity

@Database(
    entities = [
        WatchlistEntity::class,
        WashSaleTradeEntity::class,
        SentinelRuleEntity::class,
        ShadowPositionEntity::class,
        ShadowOrderEntity::class,
        AuditLogEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class SchwabQuantDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun washSaleDao(): WashSaleDao
    abstract fun sentinelRuleDao(): SentinelRuleDao
    abstract fun shadowLedgerDao(): ShadowLedgerDao
    abstract fun auditLogDao(): AuditLogDao
}
