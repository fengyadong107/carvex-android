package io.github.iamr0s.carvex.carplay

import org.junit.Assert.*
import org.junit.Test

class ReceiverBonjourPreferenceTest {
    @Test fun acceptsCarPlayName(){
        assertTrue(ReceiverBonjourPreference.shouldAccept("CarPlay"))
        assertTrue(ReceiverBonjourPreference.shouldAccept("carplay"))
        assertTrue(ReceiverBonjourPreference.isPreferredCarPlayName("CarPlay"))
    }

    @Test fun rejectsMacAndConsumerAirPlayHosts(){
        assertFalse(ReceiverBonjourPreference.shouldAccept("fengyadong的MacBook Pro"))
        assertFalse(ReceiverBonjourPreference.shouldAccept("Alice's MacBook Pro"))
        assertFalse(ReceiverBonjourPreference.shouldAccept("Living Room Apple TV"))
        assertFalse(ReceiverBonjourPreference.shouldAccept("Kitchen HomePod"))
        assertTrue(ReceiverBonjourPreference.looksLikeConsumerAirPlayHost("Studio Mac mini"))
    }

    @Test fun keepsUnknownVehicleStyleNames(){
        assertTrue(ReceiverBonjourPreference.shouldAccept("WirelessCarPlay"))
        assertTrue(ReceiverBonjourPreference.shouldAccept("HeadUnit-01"))
    }
}
