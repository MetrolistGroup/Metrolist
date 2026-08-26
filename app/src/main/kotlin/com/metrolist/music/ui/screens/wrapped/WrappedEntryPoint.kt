/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.logic.wrapped.WrappedManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

internal val LocalWrappedManager = compositionLocalOf<WrappedManager> { error("No WrappedManager found!") }

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WrappedEntryPoint {
    fun databaseDao(): DatabaseDao
}

internal fun provideWrappedManager(context: Context): WrappedManager {
    val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        WrappedEntryPoint::class.java
    )
    return WrappedManager(
        databaseDao = entryPoint.databaseDao(),
        context = context.applicationContext
    )
}
