package com.elect.riderange.scooter.ble

enum class LinkStateKind { DISCONNECTED, CONNECTING, CONNECTED }

data class LinkSnapshot(
    val state: LinkStateKind = LinkStateKind.DISCONNECTED,
    val name: String? = null,
    val address: String? = null,
    val mtu: Int = 23,
    val service: String? = null,
    val rssi: Int? = null,
    /** Set when the phone gave up (connect failed, reconnect window over). */
    val reason: String? = null,
)
