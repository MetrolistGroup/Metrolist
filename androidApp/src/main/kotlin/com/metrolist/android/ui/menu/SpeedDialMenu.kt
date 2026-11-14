package com.metrolist.android.ui.menu

import androidx.compose.runtime.Composable
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.menu.SpeedDialToggle
import org.koin.compose.koinInject
import com.metrolist.shared.ui.menu.rememberSpeedDialToggle as rememberSharedSpeedDialToggle

@Composable
internal fun rememberSpeedDialToggle(item: YTItem): SpeedDialToggle = rememberSharedSpeedDialToggle(item, koinInject<MusicDatabase>())
