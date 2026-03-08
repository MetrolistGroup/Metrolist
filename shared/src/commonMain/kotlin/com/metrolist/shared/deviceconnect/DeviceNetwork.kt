/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Resolve the device's own LAN IPv4 address (used to advertise the local server).
 */

package com.metrolist.shared.deviceconnect

internal expect fun getLocalIpV4Addresses(): List<String>

fun getLocalIpV4Address(): String? = getLocalIpV4Addresses().firstOrNull()
