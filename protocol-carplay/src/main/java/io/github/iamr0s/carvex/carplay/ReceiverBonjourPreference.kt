package io.github.iamr0s.carvex.carplay

/**
 * Prefer the wireless CarPlay receiver over consumer AirPlay hosts that share
 * `_airplay._tcp` on the same hotspot (Mac / Apple TV / HomePod).
 * First-resolved Bonjour used to win; a Mac on the LAN then caused PAIR 403.
 */
internal object ReceiverBonjourPreference {
    fun shouldAccept(serviceName: String): Boolean {
        if(isPreferredCarPlayName(serviceName))return true
        return !looksLikeConsumerAirPlayHost(serviceName)
    }

    fun isPreferredCarPlayName(serviceName: String): Boolean =
        serviceName.equals("CarPlay", ignoreCase = true)

    fun looksLikeConsumerAirPlayHost(serviceName: String): Boolean {
        val n=serviceName.lowercase()
        if(n=="carplay")return false
        val markers=listOf(
            "macbook","imac","mac mini","mac studio","mac pro","macbookair","macbook pro",
            "apple tv","homepod","airport express","airfoil")
        if(markers.any{n.contains(it)})return true
        // e.g. "xxx的MacBook Pro" / "xxx's Mac"
        if(n.contains("的mac")||n.contains("'s mac")||n.contains("’s mac"))return true
        return false
    }
}
