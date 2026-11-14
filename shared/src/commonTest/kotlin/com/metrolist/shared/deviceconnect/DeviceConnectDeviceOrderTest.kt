package com.metrolist.shared.deviceconnect

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceConnectDeviceOrderTest {
    @Test
    fun usageOrderPersistsAndIsScopedToAccount() {
        val settings = Settings(MapSettings())
        val store = DevicePairStore(settings)
        val identity = AccountIdentitySnapshot("account", "fingerprint", anonymous = false, resolved = true)
        val other = identity.copy(fingerprint = "other")
        val secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 7 }
        for (account in listOf(identity, other)) {
            for (id in listOf("a", "b", "c")) store.save(id, id, "", account, secret)
        }
        store.recordUsage(setOf("b"), identity)
        store.recordUsage(setOf("c"), identity)
        store.recordUsage(setOf("c"), identity)
        store.save("c", "Renamed", "", identity, secret)
        val restored = DevicePairStore(settings)
        assertEquals(listOf("c", "b", "a"), restored.pairsFor(identity).map { it.peerDeviceId })
        assertEquals(listOf("a", "b", "c"), restored.pairsFor(other).map { it.peerDeviceId })
    }
}
