package com.metrolist.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey

private const val DETAIL_VIEW_MODEL = "detail_view_model"
internal val detailViewModelMetadata: Map<String, Any> = mapOf(DETAIL_VIEW_MODEL to true)

@Composable
internal fun rememberDetailViewModelNavEntryDecorator(): NavEntryDecorator<NavKey> {
    val stores = rememberViewModelStoreProvider()
    return remember(stores) {
        NavEntryDecorator(onPop = stores::clearKey) { entry ->
            if (entry.metadata[DETAIL_VIEW_MODEL] == true) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides rememberViewModelStoreOwner(entry.contentKey, stores),
                ) {
                    entry.Content()
                }
            } else {
                entry.Content()
            }
        }
    }
}
