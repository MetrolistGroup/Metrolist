package com.metrolist.shared.youtube.playback

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUUID
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
internal class IosPlaybackNetworkMonitor {
    private var monitor: nw_path_monitor_t = null
    private val instanceId = NSUUID().UUIDString
    private var generation = 0L

    fun start() {
        if (monitor != null) return
        val active = checkNotNull(nw_path_monitor_create())
        monitor = active
        nw_path_monitor_set_update_handler(active) { path ->
            if (monitor == active && path != null) {
                onPathChanged(
                    connected = nw_path_get_status(path) == nw_path_status_satisfied,
                    isCellular = nw_path_uses_interface_type(path, nw_interface_type_cellular),
                )
            }
        }
        nw_path_monitor_set_queue(active, dispatch_get_main_queue())
        nw_path_monitor_start(active)
    }

    fun stop() {
        val active = monitor ?: return
        monitor = null
        nw_path_monitor_set_update_handler(active, null)
        nw_path_monitor_cancel(active)
    }

    internal fun onPathChanged(
        connected: Boolean,
        isCellular: Boolean = false,
    ) {
        PlaybackNetworkStateStore.publish(PlaybackNetworkState("ios-$instanceId-${++generation}", connected, isCellular))
    }
}
