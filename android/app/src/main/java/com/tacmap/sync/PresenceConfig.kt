package com.tacmap.sync

import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction

data class PresenceConfig(
    val callsign: String = "",
    val shareLocation: Boolean = false,
    val affiliation: SymbolAffiliation = SymbolAffiliation.FRIEND,
    val echelon: SymbolEchelon = SymbolEchelon.TEAM,
    val function: SymbolFunction = SymbolFunction.INFANTRY,
    val isHQ: Boolean = false
)
