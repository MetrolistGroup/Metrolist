package com.metrolist.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.metrolist.shared.ui.MetrolistApp
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    return ComposeUIViewController { MetrolistApp() }
}
