package com.metrolist.shared.di

import android.content.Context
import com.metrolist.shared.db.DatabaseDriverFactory
import com.metrolist.shared.player.MusicPlayer
import com.metrolist.shared.player.MusicPlayerImpl
import org.koin.dsl.module

actual fun platformModule() = module {
    single { DatabaseDriverFactory(get()) }
    single<MusicPlayer> { MusicPlayerImpl(get()) }
}
