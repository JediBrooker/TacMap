package com.tacmap.waypoints

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Non-military symbol sets for the app's other audiences: airsoft / milsim /
 * paintball, search & rescue, and a plain civilian POI set. Mirrors the iOS
 * MarkerSymbol. A marker = a set + a symbol id + a colour, rendered as a coloured
 * badge with a short code. Rides the same waypoint pipeline as APP-6 symbols.
 */
@Serializable
data class MarkerSymbol(
    val set: MarkerSet = MarkerSet.AIRSOFT,
    @SerialName("symbol_id") val symbolId: String = "team",
    @SerialName("color") val colorHex: String = "#3B7BE0"
) {
    val entry: MarkerCatalog.Entry get() = MarkerCatalog.entry(set, symbolId)
}

@Serializable
enum class MarkerSet(val displayName: String) {
    @SerialName("airsoft") AIRSOFT("Airsoft / Milsim"),
    @SerialName("sar") SAR("Search & Rescue"),
    @SerialName("poi") POI("Points of Interest")
}

/** The symbol catalog: for each set, an ordered list with a display name, a short
 *  badge code, and a default colour. Mirrors the iOS MarkerCatalog. */
object MarkerCatalog {
    data class Entry(
        val id: String,
        val displayName: String,
        val code: String,
        val defaultColor: String
    )

    /** Airsoft team colours the picker offers (the team IS the colour). */
    val teamColors: List<Pair<String, String>> = listOf(
        "Red" to "#E23B3B", "Blue" to "#3B7BE0", "Green" to "#3BC85A",
        "Yellow" to "#EBC12E", "Orange" to "#F2872E"
    )

    val airsoft = listOf(
        Entry("team", "Team Member", "TM", "#3B7BE0"),
        Entry("capture", "Capture Point", "CP", "#F2872E"),
        Entry("flag", "Flag / CTF", "⚑", "#3B7BE0"),
        Entry("objective", "Objective", "◎", "#F2872E"),
        Entry("spawn", "Spawn", "SP", "#3BC85A"),
        Entry("respawn", "Respawn", "RS", "#3BC85A"),
        Entry("safezone", "Safe Zone", "✓", "#3BC85A"),
        Entry("staging", "Staging", "ST", "#8A93A6"),
        Entry("chrono", "Chrono / Marshalling", "CH", "#8A93A6"),
        Entry("deadzone", "Dead Zone", "✕", "#E23B3B"),
        Entry("oob", "Out of Bounds", "OB", "#E23B3B"),
        Entry("rifleman", "Rifleman", "R", "#3B7BE0"),
        Entry("marksman", "Marksman / Sniper", "MK", "#3B7BE0"),
        Entry("support", "Support / Gunner", "SP", "#3B7BE0"),
        Entry("medic", "Medic", "✚", "#E23B3B"),
        Entry("squadlead", "Squad Lead", "SL", "#EBC12E"),
        Entry("grenadier", "Grenadier", "GR", "#F2872E"),
        Entry("breacher", "Breacher", "BR", "#8A93A6"),
    )

    val sar = listOf(
        Entry("pls", "Point Last Seen (PLS)", "PLS", "#E23B3B"),
        Entry("lkp", "Last Known Position (LKP)", "LKP", "#E23B3B"),
        Entry("ipp", "Initial Planning Point (IPP)", "IPP", "#F2872E"),
        Entry("segment", "Search Segment", "SEG", "#3B7BE0"),
        Entry("assignment", "Assignment", "AS", "#3B7BE0"),
        Entry("clue", "Clue / Find", "?", "#EBC12E"),
        Entry("subject", "Subject Found", "✓", "#3BC85A"),
        Entry("casualty", "Casualty", "✚", "#E23B3B"),
        Entry("evac", "Evac Route", "EV", "#3BC85A"),
        Entry("icp", "Command Post (ICP)", "ICP", "#111417"),
        Entry("base", "Base", "BA", "#3B7BE0"),
        Entry("staging", "Staging", "ST", "#8A93A6"),
        Entry("helispot", "Helispot / LZ", "H", "#3BC85A"),
        Entry("water", "Water", "W", "#3B7BE0"),
        Entry("medical", "Medical", "✚", "#E23B3B"),
        Entry("hazard", "Hazard", "!", "#EBC12E"),
        Entry("containment", "Containment", "CT", "#F2872E"),
        Entry("roadblock", "Road Block", "RB", "#E23B3B"),
    )

    val poi = listOf(
        Entry("medical", "Medical", "✚", "#E23B3B"),
        Entry("water", "Water", "W", "#3B7BE0"),
        Entry("comms", "Comms", "CM", "#3B7BE0"),
        Entry("parking", "Parking", "P", "#3B7BE0"),
        Entry("hazard", "Hazard", "!", "#EBC12E"),
        Entry("checkpoint", "Checkpoint", "CK", "#3BC85A"),
        Entry("pin", "Marker", "•", "#EBC12E"),
    )

    fun entries(set: MarkerSet): List<Entry> = when (set) {
        MarkerSet.AIRSOFT -> airsoft
        MarkerSet.SAR -> sar
        MarkerSet.POI -> poi
    }

    fun entry(set: MarkerSet, id: String): Entry =
        entries(set).firstOrNull { it.id == id } ?: entries(set)[0]
}
