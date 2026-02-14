package com.metrolist.shared.di

import com.metrolist.shared.db.DatabaseDriverFactory
import com.metrolist.shared.db.createDatabase
import com.metrolist.shared.repository.MusicRepository
import org.koin.core.module.Module
import org.koin.dsl.module

fun commonModule() = module {
    single { createDatabase(get()) }
    single { MusicRepository(get()) }
}

expect fun platformModule(): Module
