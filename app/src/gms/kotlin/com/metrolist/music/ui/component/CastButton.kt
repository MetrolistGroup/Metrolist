/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.EnableGoogleCastKey
import com.metrolist.music.utils.rememberPreference
import timber.log.Timber

/**
 * A Composable Cast button that shows available Cast devices.
 * Uses the app's MenuState to show a styled bottom sheet.
 */
@Composable
fun CastButton(
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    
    var castAvailable by remember { mutableStateOf(false) }
    var mediaRouter by remember { mutableStateOf<MediaRouter?>(null) }
    var routeSelector by remember { mutableStateOf<MediaRouteSelector?>(null) }
    var availableRoutes by remember { mutableStateOf<List<MediaRouter.RouteInfo>>(emptyList()) }
    
    val (enableGoogleCast) = rememberPreference(
        key = EnableGoogleCastKey,
        defaultValue = true
    )
    
    // Get cast state from service
    val castHandler = playerConnection?.service?.castConnectionHandler
    val isCasting by castHandler?.isCasting?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val isConnecting by castHandler?.isConnecting?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val castDeviceName by castHandler?.castDeviceName?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    
    // Get current media metadata
    val currentMetadata by playerConnection?.mediaMetadata?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }

    // Check if Cast is available and disconnect if disabled while casting
    LaunchedEffect(enableGoogleCast) {
        if (!enableGoogleCast) {
            // Disconnect from Cast if currently casting
            if (isCasting) {
                playerConnection?.service?.castConnectionHandler?.disconnect()
            }
            castAvailable = false
            mediaRouter = null
            routeSelector = null
            availableRoutes = emptyList()
            return@LaunchedEffect
        }
        try {
            CastContext.getSharedInstance(context)
            mediaRouter = MediaRouter.getInstance(context)
            routeSelector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            // Reinitialize the Cast handler to ensure it's ready
            playerConnection?.service?.castConnectionHandler?.initialize()
            castAvailable = true
        } catch (e: Exception) {
            Timber.d("Cast not available: ${e.message}")
            castAvailable = false
        }
    }
    
    // Listen for route changes to discover devices
    DisposableEffect(mediaRouter, routeSelector) {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                updateRoutes(router, routeSelector) { availableRoutes = it }
            }
            
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                updateRoutes(router, routeSelector) { availableRoutes = it }
            }
            
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                updateRoutes(router, routeSelector) { availableRoutes = it }
            }
        }
        
        routeSelector?.let { selector ->
            mediaRouter?.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            // Initial update
            updateRoutes(mediaRouter, selector) { availableRoutes = it }
        }
        
        onDispose {
            mediaRouter?.removeCallback(callback)
        }
    }

    // Show the button if Cast is enabled and SDK is available
    if (enableGoogleCast && castAvailable) {
        Box(
            modifier = modifier
        ) {
            // Shadow background for cast button
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.Center)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.4f),
                                Color.Transparent
                            )
                        )
                    )
            )
            
            // Cast button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                    if (currentMetadata == null && !isCasting) {
                        Toast.makeText(context, "Play a song first to cast", Toast.LENGTH_SHORT).show()
                        return@clickable
                    }
                    
                    // Get current connected route if casting
                    val currentRoute = if (isCasting) {
                        mediaRouter?.routes?.find { route ->
                            routeSelector?.let { selector -> 
                                route.matchesSelector(selector) && route.isSelected
                            } == true
                        }
                    } else null
                    
                    // Show bottom sheet with cast picker
                    menuState.show {
                        CastPickerSheet(
                            routes = availableRoutes,
                            isConnecting = isConnecting,
                            currentlyConnectedRoute = currentRoute,
                            onRouteSelected = { route ->
                                castHandler?.connectToRoute(route)
                                menuState.dismiss()
                            },
                            onDisconnect = {
                                castHandler?.disconnect()
                                menuState.dismiss()
                            }
                        )
                    }
                }
            ) {
                Image(
                    painter = painterResource(
                        if (isCasting) R.drawable.cast_connected else R.drawable.cast
                    ),
                    contentDescription = if (isCasting) "Stop casting" else "Cast",
                    colorFilter = ColorFilter.tint(
                        if (isCasting) MaterialTheme.colorScheme.primary else tintColor
                    ),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun updateRoutes(
    router: MediaRouter?,
    selector: MediaRouteSelector?,
    onUpdate: (List<MediaRouter.RouteInfo>) -> Unit
) {
    if (router == null || selector == null) {
        onUpdate(emptyList())
        return
    }
    val routes = router.routes.filter { route ->
        route.matchesSelector(selector) && !route.isDefault
    }
    onUpdate(routes)
}
