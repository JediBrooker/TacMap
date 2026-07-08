package com.tacmap.sync

data class PresencePeer(
    val clientId: String,
    val callsign: String,
    val affiliation: String,
    val echelon: String,
    val function: String,
    val isHQ: Boolean,
    val lat: Double,
    val lon: Double,
    val heading: Double,
    val speed: Double,
    val ts: Long,
    val receivedAt: Long = System.currentTimeMillis()
)
