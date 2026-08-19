package dev.dimension.flare.di

import dev.dimension.flare.logging.BoundedLogBuffer
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.model.PlatformSpec
import org.koin.core.module.Module
import org.koin.dsl.module

/** Koin definitions shared by every platform shell. */
public val sharedModule: Module =
    module {
        single { PlatformRegistry(getAll<PlatformSpec>()) }
        single { BoundedLogBuffer() }
    }
