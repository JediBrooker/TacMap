package com.tacmap.waypoints

import com.tacmap.localization.Messages
import java.text.Normalizer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Orders offered by the Symbology list. [persisted] values are stored in
 * preferences, so keep them stable. iOS mirrors these in
 * `SymbolListOrder.swift`; `testdata/symbol_list_order.json` pins both. */
enum class SymbolListOrder(val persisted: String) {
    NEWEST("newest"),
    NAME("name"),
    AFFILIATION("affiliation"),
    LAYER("layer"),
    DISTANCE("distance");

    val displayName: String
        get() = when (this) {
            NEWEST -> Messages.symbolsSortNewest()
            NAME -> Messages.symbolsSortName()
            AFFILIATION -> Messages.symbolsSortAffiliation()
            LAYER -> Messages.symbolsSortLayer()
            DISTANCE -> Messages.symbolsSortDistance()
        }

    companion object {
        const val PREFERENCE_KEY = "symbol_list_order"
        val DEFAULT = NEWEST

        fun fromPersisted(value: String?): SymbolListOrder =
            entries.firstOrNull { it.persisted == value } ?: DEFAULT
    }
}

/** Affiliation bucket for grouping. Units use their frame affiliation and task
 * graphics their affiliation colour; black tasks, markers and generic
 * waypoints carry no affiliation and land in [OTHER]. */
enum class SymbolListAffiliation {
    FRIEND,
    HOSTILE,
    NEUTRAL,
    UNKNOWN,
    OTHER;

    val title: String
        get() = when (this) {
            FRIEND -> SymbolAffiliation.FRIEND.displayName
            HOSTILE -> SymbolAffiliation.HOSTILE.displayName
            NEUTRAL -> SymbolAffiliation.NEUTRAL.displayName
            UNKNOWN -> SymbolAffiliation.UNKNOWN.displayName
            OTHER -> Messages.symbolsGroupOther()
        }

    companion object {
        fun of(waypoint: Waypoint): SymbolListAffiliation = when (val kind = waypoint.kind) {
            is WaypointKind.Military -> when (kind.spec.affiliation) {
                SymbolAffiliation.FRIEND -> FRIEND
                SymbolAffiliation.HOSTILE -> HOSTILE
                SymbolAffiliation.NEUTRAL -> NEUTRAL
                SymbolAffiliation.UNKNOWN -> UNKNOWN
            }
            is WaypointKind.ControlMeasure -> when (waypoint.taskColor) {
                TaskColor.BLUE -> FRIEND
                TaskColor.RED -> HOSTILE
                TaskColor.GREEN -> NEUTRAL
                TaskColor.YELLOW -> UNKNOWN
                TaskColor.BLACK -> OTHER
            }
            else -> OTHER
        }
    }
}

sealed interface SymbolListGroup {
    /** Ungrouped orders produce one untitled section. */
    data object All : SymbolListGroup
    data class Affiliation(val affiliation: SymbolListAffiliation) : SymbolListGroup
    data class Layer(val layerId: String) : SymbolListGroup
    /** Symbols whose layer is not in the current layer list. */
    data object OtherLayer : SymbolListGroup
}

data class SymbolListSection(val group: SymbolListGroup, val waypoints: List<Waypoint>)

/** Pure ordering for the Symbology list so both platforms group and sort the
 * same mission identically. */
object SymbolListSorter {
    /** IUGG mean Earth radius. List distances are for ordering and a compact
     * readout; the sphere keeps them identical on iOS and Android. */
    const val EARTH_RADIUS_METRES = 6_371_008.8

    fun sections(
        waypoints: List<Waypoint>,
        order: SymbolListOrder,
        layerOrder: List<String>,
        referenceLat: Double,
        referenceLng: Double,
    ): List<SymbolListSection> = when (order) {
        SymbolListOrder.NEWEST ->
            listOf(SymbolListSection(SymbolListGroup.All, waypoints.sortedWith(newestFirst)))
        SymbolListOrder.NAME ->
            listOf(SymbolListSection(SymbolListGroup.All, waypoints.sortedWith(byName)))
        SymbolListOrder.DISTANCE -> {
            val distances = waypoints.associate {
                it.id to distanceMetres(referenceLat, referenceLng, it.latitude, it.longitude)
            }
            val byDistance = compareBy<Waypoint> { distances.getValue(it.id) }.then(byName)
            listOf(SymbolListSection(SymbolListGroup.All, waypoints.sortedWith(byDistance)))
        }
        SymbolListOrder.AFFILIATION -> {
            val buckets = waypoints.groupBy(SymbolListAffiliation::of)
            SymbolListAffiliation.entries.mapNotNull { affiliation ->
                buckets[affiliation]?.takeIf { it.isNotEmpty() }?.let {
                    SymbolListSection(SymbolListGroup.Affiliation(affiliation), it.sortedWith(byName))
                }
            }
        }
        SymbolListOrder.LAYER -> {
            val known = layerOrder.toHashSet()
            val buckets = waypoints.groupBy { it.layerId.takeIf(known::contains) }
            val sections = layerOrder.mapNotNull { layerId ->
                buckets[layerId]?.takeIf { it.isNotEmpty() }?.let {
                    SymbolListSection(SymbolListGroup.Layer(layerId), it.sortedWith(byName))
                }
            }
            val orphans = buckets[null].orEmpty()
            if (orphans.isEmpty()) sections
            else sections + SymbolListSection(SymbolListGroup.OtherLayer, orphans.sortedWith(byName))
        }
    }

    /** Great-circle (haversine) distance in metres. */
    fun distanceMetres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(lng2 - lng1)
        val h = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(h)))
    }

    /** Natural name order: ASCII digit runs compare by value, everything else
     * case- and accent-insensitively by code point, so "2 PL" sorts before
     * "10 PL" in every language. Returns a negative, zero or positive value. */
    fun naturalCompare(lhs: String, rhs: String): Int {
        val a = chunks(fold(lhs))
        val b = chunks(fold(rhs))
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]
            val y = b[i]
            val result = if (x.isNumber && y.isNumber) {
                compareDigits(x.codePoints, y.codePoints)
            } else {
                compareCodePoints(x.codePoints, y.codePoints)
            }
            if (result != 0) return result
        }
        return a.size.compareTo(b.size)
    }

    private val byName: Comparator<Waypoint> = Comparator { lhs, rhs ->
        naturalCompare(lhs.name, rhs.name).takeIf { it != 0 }
            ?: rhs.createdAt.compareTo(lhs.createdAt).takeIf { it != 0 }
            ?: lhs.id.lowercase().compareTo(rhs.id.lowercase())
    }

    private val newestFirst: Comparator<Waypoint> = Comparator { lhs, rhs ->
        rhs.createdAt.compareTo(lhs.createdAt).takeIf { it != 0 }
            ?: naturalCompare(lhs.name, rhs.name).takeIf { it != 0 }
            ?: lhs.id.lowercase().compareTo(rhs.id.lowercase())
    }

    private class Chunk(val isNumber: Boolean, val codePoints: IntArray)

    private val combiningMarks = Regex("\\p{Mn}+")

    // Decompose, drop combining marks, then lower-case: the same steps the iOS
    // comparator takes, so accents never change the order.
    private fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

    private fun chunks(value: String): List<Chunk> {
        val result = mutableListOf<Chunk>()
        val current = mutableListOf<Int>()
        var currentIsNumber = false
        value.codePoints().forEach { codePoint ->
            val isDigit = codePoint in 0x30..0x39
            if (current.isNotEmpty() && isDigit != currentIsNumber) {
                result += Chunk(currentIsNumber, current.toIntArray())
                current.clear()
            }
            currentIsNumber = isDigit
            current += codePoint
        }
        if (current.isNotEmpty()) result += Chunk(currentIsNumber, current.toIntArray())
        return result
    }

    private fun compareDigits(lhs: IntArray, rhs: IntArray): Int {
        val a = lhs.dropWhile { it == 0x30 }.toIntArray()
        val b = rhs.dropWhile { it == 0x30 }.toIntArray()
        if (a.size != b.size) return a.size.compareTo(b.size)
        return compareCodePoints(a, b)
    }

    private fun compareCodePoints(lhs: IntArray, rhs: IntArray): Int {
        for (i in 0 until minOf(lhs.size, rhs.size)) {
            if (lhs[i] != rhs[i]) return lhs[i].compareTo(rhs[i])
        }
        return lhs.size.compareTo(rhs.size)
    }
}
