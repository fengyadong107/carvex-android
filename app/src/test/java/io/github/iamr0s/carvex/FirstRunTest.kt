package io.github.iamr0s.carvex

import io.github.iamr0s.carvex.sessioncore.BondedDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstRunTest {
    private fun car(name: String, address: String, transport: BondedDevices.Transport) =
        BondedDevices.Device(name, address, BondedDevices.Kind.CAR, transport)

    @Test fun soleCarPicksTheOnlyClassicAndSkipsLe() {
        val classic = car("车", "AA:AA:AA:AA:AA:01", BondedDevices.Transport.CLASSIC)
        val le = car("CHR", "BB:BB:BB:BB:BB:02", BondedDevices.Transport.LE)
        val unknown = car("?", "CC:CC:CC:CC:CC:03", BondedDevices.Transport.UNKNOWN)
        assertEquals(classic, FirstRun.soleCar(listOf(classic, le, unknown)))
    }

    @Test fun soleCarDoesNotGuessAmongTwoClassicCarsOrLeOnly() {
        val a = car("甲", "AA:AA:AA:AA:AA:01", BondedDevices.Transport.CLASSIC)
        val b = car("乙", "AA:AA:AA:AA:AA:02", BondedDevices.Transport.DUAL)
        assertNull(FirstRun.soleCar(listOf(a, b)))
        assertNull(FirstRun.soleCar(listOf(car("CHR", "BB:BB:BB:BB:BB:02", BondedDevices.Transport.LE))))
        assertNull(FirstRun.soleCar(listOf(car("?", "CC:CC:CC:CC:CC:03", BondedDevices.Transport.UNKNOWN))))
    }
}
