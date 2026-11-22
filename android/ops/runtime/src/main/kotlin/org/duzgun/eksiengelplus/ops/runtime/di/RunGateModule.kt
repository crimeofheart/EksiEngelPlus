package org.duzgun.eksiengelplus.ops.runtime.di

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.duzgun.eksiengelplus.ops.runtime.RunGate

/** The app binds [RunGate]; an app that does not (devharness) still compiles and blocks nothing. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RunGateModule {
    @BindsOptionalOf
    abstract fun runGate(): RunGate
}
