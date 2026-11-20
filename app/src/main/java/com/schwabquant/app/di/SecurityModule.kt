package com.schwabquant.app.di

import com.schwabquant.app.core.security.AndroidTinkAeadFactory
import com.schwabquant.app.core.security.BiometricGate
import com.schwabquant.app.core.security.BiometricGateImpl
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.TinkAeadFactory
import com.schwabquant.app.core.security.TinkCredentialVault
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindTinkAeadFactory(impl: AndroidTinkAeadFactory): TinkAeadFactory

    @Binds
    @Singleton
    abstract fun bindCredentialVault(impl: TinkCredentialVault): CredentialVault

    @Binds
    @Singleton
    abstract fun bindBiometricGate(impl: BiometricGateImpl): BiometricGate
}
