package com.schwabquant.app.di

import com.schwabquant.app.data.repository.AuthRepositoryImpl
import com.schwabquant.app.data.repository.MarketDataRepositoryImpl
import com.schwabquant.app.data.repository.OrderRepositoryImpl
import com.schwabquant.app.data.repository.PortfolioRepositoryImpl
import com.schwabquant.app.data.repository.SentinelRepositoryImpl
import com.schwabquant.app.domain.repository.AuthRepository
import com.schwabquant.app.domain.repository.MarketDataRepository
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.repository.SentinelRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindPortfolioRepository(impl: PortfolioRepositoryImpl): PortfolioRepository

    @Binds
    @Singleton
    abstract fun bindMarketDataRepository(impl: MarketDataRepositoryImpl): MarketDataRepository

    @Binds
    @Singleton
    abstract fun bindOrderRepository(impl: OrderRepositoryImpl): OrderRepository

    @Binds
    @Singleton
    abstract fun bindSentinelRepository(impl: SentinelRepositoryImpl): SentinelRepository
}
