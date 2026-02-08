/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Simple NetworkConnectivityObserver based on OuterTune's implementation
 * Provides network connectivity monitoring for auto-play functionality
 */
class NetworkConnectivityObserver(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkStatus = Channel<Boolean>(Channel.CONFLATED)
    val networkStatus = _networkStatus.receiveAsFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkStatus.trySend(true)
        }

        override fun onLost(network: Network) {
            _networkStatus.trySend(false)
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            // Fallback: assume connected if registration fails
            _networkStatus.trySend(true)
        }
        
        // Send initial state
        val isInitiallyConnected = isCurrentlyConnected()
        _networkStatus.trySend(isInitiallyConnected)
    }

    fun unregister() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
    
    /**
     * Check current connectivity state synchronously
     */
    fun isCurrentlyConnected(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
            // Check if we have internet capability
            val hasInternet = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            
            // For API 23+, also check if connection is validated
            val isValidated =
                networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

            hasInternet && isValidated
        } catch (e: Exception) {
            false
        }
    }
}
