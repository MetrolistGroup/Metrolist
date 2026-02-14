package com.metrolist.shared

import com.metrolist.shared.di.commonModule
import com.metrolist.shared.di.platformModule
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(commonModule(), platformModule())
    }

// iOS specific initializer
fun initKoin() = initKoin {}
