package io.github.iamr0s.carvex.carplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPeerClosedTest {
    @Test fun androidBluetoothReadCloseIsPeerClose() {
        assertTrue(bluetoothPeerClosed(java.io.IOException("bt socket closed, read return: -1")))
        assertTrue(bluetoothPeerClosed(java.io.IOException("read failed, socket might closed or timeout, read ret: -1")))
        assertTrue(bluetoothPeerClosed(java.io.EOFException()))
    }

    @Test fun otherIoFailuresStayFatal() {
        assertFalse(bluetoothPeerClosed(java.io.IOException("Wi-Fi request unavailable")))
        assertFalse(bluetoothPeerClosed(IllegalStateException("Post-bootstrap iAP link changed")))
    }

    @Test fun acceptedCredentialsKeepHandoffAliveAfterBluetoothDrops() {
        assertTrue(handoffContextCurrent(true, false, false, controlSessionLive = false, credentialsReady = true))
        assertFalse(handoffContextCurrent(true, false, false, controlSessionLive = false, credentialsReady = false))
        assertFalse(handoffContextCurrent(true, false, true, controlSessionLive = false, credentialsReady = true))
    }
}
