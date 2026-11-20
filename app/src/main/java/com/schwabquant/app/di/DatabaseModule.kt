package com.schwabquant.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.schwabquant.app.data.local.dao.AuditLogDao
import com.schwabquant.app.data.local.dao.SentinelRuleDao
import com.schwabquant.app.data.local.dao.ShadowLedgerDao
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.dao.WatchlistDao
import com.schwabquant.app.data.local.db.SchwabQuantDatabase
import com.schwabquant.app.domain.model.LiquidUniverse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `wash_sale_trades`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `wash_sale_trades` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`underlyingSymbol` TEXT NOT NULL, " +
                    "`symbol` TEXT NOT NULL, " +
                    "`assetType` TEXT NOT NULL, " +
                    "`instruction` TEXT NOT NULL, " +
                    "`quantity` INTEGER NOT NULL, " +
                    "`price` REAL NOT NULL, " +
                    "`realizedPnl` REAL, " +
                    "`tradeTimestampEpochMs` INTEGER NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`externalOrderId` TEXT)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_wash_sale_trades_underlyingSymbol` " +
                    "ON `wash_sale_trades` (`underlyingSymbol`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_wash_sale_trades_symbol` " +
                    "ON `wash_sale_trades` (`symbol`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_wash_sale_trades_tradeTimestampEpochMs` " +
                    "ON `wash_sale_trades` (`tradeTimestampEpochMs`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_wash_sale_trades_externalOrderId` " +
                    "ON `wash_sale_trades` (`externalOrderId`)"
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `watchlists` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`tickersCsv` TEXT NOT NULL, " +
                    "`isDefault` INTEGER NOT NULL, " +
                    "`createdAtEpochMs` INTEGER NOT NULL, " +
                    "`updatedAtEpochMs` INTEGER NOT NULL)"
            )

            val now = System.currentTimeMillis()
            val legacySymbols = mutableListOf<String>()
            db.query("SELECT symbol FROM `watchlist` ORDER BY addedAtEpochMs ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    val sym = cursor.getString(0)?.trim()?.uppercase().orEmpty()
                    if (sym.isNotEmpty()) legacySymbols.add(sym)
                }
            }

            db.execSQL("DROP TABLE IF EXISTS `watchlist`")

            db.execSQL(
                "INSERT INTO `watchlists` (`name`, `tickersCsv`, `isDefault`, `createdAtEpochMs`, `updatedAtEpochMs`) " +
                    "VALUES (?, ?, 1, ?, ?)",
                arrayOf<Any>(
                    LiquidUniverse.CORE_25_NAME,
                    LiquidUniverse.CORE_25_LIQUID_TICKERS.joinToString(","),
                    now,
                    now
                )
            )

            if (legacySymbols.isNotEmpty()) {
                db.execSQL(
                    "INSERT INTO `watchlists` (`name`, `tickersCsv`, `isDefault`, `createdAtEpochMs`, `updatedAtEpochMs`) " +
                        "VALUES (?, ?, 0, ?, ?)",
                    arrayOf<Any>(
                        "Custom Watchlist",
                        legacySymbols.distinct().joinToString(","),
                        now + 1L,
                        now + 1L
                    )
                )
            }
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `sentinel_rules` ADD COLUMN `slippageCollarPct` REAL NOT NULL DEFAULT 0.35")
            db.execSQL("ALTER TABLE `sentinel_rules` ADD COLUMN `minConsecutiveTicks` INTEGER NOT NULL DEFAULT 3")
            db.execSQL("ALTER TABLE `sentinel_rules` ADD COLUMN `maxSpreadPct` REAL NOT NULL DEFAULT 1.5")
            db.execSQL("ALTER TABLE `sentinel_rules` ADD COLUMN `sweepBufferPct` REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE `sentinel_rules` ADD COLUMN `antiKnifeLookbackMinutes` INTEGER NOT NULL DEFAULT 10")
            db.execSQL("ALTER TABLE `sentinel_rules` ADD COLUMN `antiKnifeBouncePct` REAL NOT NULL DEFAULT 0.25")
            db.execSQL("ALTER TABLE `sentinel_rules` ADD COLUMN `isShortPosition` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `sentinel_rules` ADD COLUMN `lastTriggerNote` TEXT")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `sentinel_rules` ADD COLUMN `lastSubmittedOrderId` TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideSchwabQuantDatabase(
        @ApplicationContext context: Context
    ): SchwabQuantDatabase = Room.databaseBuilder(
        context,
        SchwabQuantDatabase::class.java,
        "schwab_quant.db"
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()

    @Provides
    fun provideWatchlistDao(db: SchwabQuantDatabase): WatchlistDao = db.watchlistDao()

    @Provides
    fun provideWashSaleDao(db: SchwabQuantDatabase): WashSaleDao = db.washSaleDao()

    @Provides
    fun provideSentinelRuleDao(db: SchwabQuantDatabase): SentinelRuleDao = db.sentinelRuleDao()

    @Provides
    fun provideShadowLedgerDao(db: SchwabQuantDatabase): ShadowLedgerDao = db.shadowLedgerDao()

    @Provides
    fun provideAuditLogDao(db: SchwabQuantDatabase): AuditLogDao = db.auditLogDao()
}
