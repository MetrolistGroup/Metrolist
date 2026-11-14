package com.metrolist.shared.deviceconnect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceConnectIncomingAdmissionTest {
    @Test
    fun limitsEachSourceAndReservesHalfTheSlotsForOutgoingOrAuthenticatedPeers() {
        val admission = DeviceConnectIncomingAdmission()
        assertTrue(admission.tryAcquire("a"))
        assertTrue(admission.tryAcquire("a"))
        assertFalse(admission.tryAcquire("a"))
        assertTrue(admission.tryAcquire("b"))
        assertTrue(admission.tryAcquire("c"))
        assertFalse(admission.tryAcquire("d"))
        admission.release("a")
        assertTrue(admission.tryAcquire("d"))
        admission.release("b")
        admission.release("c")
        admission.release("a")
        admission.release("d")
        assertTrue(admission.tryAcquire("a"))
    }
}
