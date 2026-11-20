package com.schwabquant.app.domain.repository

import com.schwabquant.app.domain.model.AccountSummary
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.Position
import kotlinx.coroutines.flow.Flow

interface PortfolioRepository {
    fun observePortfolioSnapshot(source: PortfolioDataSource): Flow<PortfolioSnapshot?>
    fun observeAccountSummary(mode: ExecutionMode): Flow<AccountSummary?>
    fun observePositions(mode: ExecutionMode): Flow<List<Position>>
    suspend fun fetchAccountHashes(): Result<List<String>>
    suspend fun refreshLivePortfolio(accountHash: String? = null): Result<PortfolioSnapshot>
    suspend fun refreshLivePositionsOnly(accountHash: String? = null): Result<PortfolioSnapshot> =
        refreshLivePortfolio(accountHash)
    suspend fun refreshShadowPortfolio(): Result<PortfolioSnapshot>
    suspend fun seedDemoPortfolio(): Result<Unit>
}
