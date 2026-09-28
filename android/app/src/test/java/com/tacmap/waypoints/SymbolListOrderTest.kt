package com.tacmap.waypoints

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins Symbology list grouping and ordering to `testdata/symbol_list_order.json`,
 * the same fixture the iOS suite reads. */
class SymbolListOrderTest {
    private val fixture: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "testdata/symbol_list_order.json")
            if (file.exists()) return@lazy Json.parseToJsonElement(file.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/symbol_list_order.json")
    }

    private val reference get() = fixture["reference"]!!.jsonObject

    private val waypoints: List<Waypoint> by lazy {
        fixture["waypoints"]!!.jsonArray.map { element ->
            val json = element.jsonObject
            val kind = when (json.string("kind")) {
                "military" -> WaypointKind.Military(
                    MilitarySymbolSpec(affiliation = affiliation(json.string("affiliation")))
                )
                "controlMeasure" -> WaypointKind.ControlMeasure()
                else -> WaypointKind.Generic
            }
            Waypoint(
                id = json.string("id"),
                name = json.string("name"),
                latitude = json["lat"]!!.jsonPrimitive.double,
                longitude = json["lon"]!!.jsonPrimitive.double,
                kind = kind,
                taskColor = json["taskColor"]?.jsonPrimitive?.content?.let(::taskColor) ?: TaskColor.BLACK,
                layerId = json.string("layerID"),
                createdAt = json["createdAtMs"]!!.jsonPrimitive.long,
            )
        }
    }

    @Test
    fun everyOrderMatchesSharedFixture() {
        val layerOrder = fixture["layers"]!!.jsonArray.map { it.jsonObject.string("id") }
        val expected = fixture["expected"]!!.jsonObject
        for (order in SymbolListOrder.entries) {
            val actual = SymbolListSorter.sections(
                waypoints = waypoints,
                order = order,
                layerOrder = layerOrder,
                referenceLat = reference["lat"]!!.jsonPrimitive.double,
                referenceLng = reference["lon"]!!.jsonPrimitive.double,
            )
            val sections = expected[order.persisted]!!.jsonArray.map { it.jsonObject }
            assertEquals(order.persisted, sections.size, actual.size)
            sections.zip(actual).forEach { (want, got) ->
                assertEquals(order.persisted, groupKey(want["group"]?.jsonPrimitive?.takeIf { it.isString }?.content, order), got.group)
                assertEquals(
                    order.persisted,
                    want["ids"]!!.jsonArray.map { it.jsonPrimitive.content },
                    got.waypoints.map { it.id },
                )
            }
        }
    }

    @Test
    fun haversineDistancesMatchSharedFixture() {
        val distances = fixture["distanceMetres"]!!.jsonObject
        for (waypoint in waypoints) {
            val metres = SymbolListSorter.distanceMetres(
                reference["lat"]!!.jsonPrimitive.double,
                reference["lon"]!!.jsonPrimitive.double,
                waypoint.latitude,
                waypoint.longitude,
            )
            assertEquals(waypoint.name, distances[waypoint.id]!!.jsonPrimitive.double, metres, 0.01)
        }
    }

    @Test
    fun naturalNameOrderIsNumericAndCaseAndAccentInsensitive() {
        fixture["naturalNameOrder"]!!.jsonArray.map { it.jsonObject }.forEach { pair ->
            val lesser = pair.string("lesser")
            val greater = pair.string("greater")
            assertTrue("$lesser < $greater", SymbolListSorter.naturalCompare(lesser, greater) < 0)
            assertTrue("$greater > $lesser", SymbolListSorter.naturalCompare(greater, lesser) > 0)
        }
        fixture["naturalNameEqual"]!!.jsonArray.forEach { pair ->
            val (a, b) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$a == $b", 0, SymbolListSorter.naturalCompare(a, b))
        }
    }

    @Test
    fun unknownPersistedOrderFallsBackToNewest() {
        assertEquals(SymbolListOrder.NEWEST, SymbolListOrder.fromPersisted(null))
        assertEquals(SymbolListOrder.NEWEST, SymbolListOrder.fromPersisted("sideways"))
        assertEquals(SymbolListOrder.LAYER, SymbolListOrder.fromPersisted("layer"))
    }

    private fun groupKey(value: String?, order: SymbolListOrder): SymbolListGroup = when {
        value == null -> SymbolListGroup.All
        order == SymbolListOrder.AFFILIATION ->
            SymbolListGroup.Affiliation(SymbolListAffiliation.valueOf(value.uppercase()))
        value == "other" -> SymbolListGroup.OtherLayer
        else -> SymbolListGroup.Layer(value)
    }

    private fun affiliation(value: String): SymbolAffiliation =
        SymbolAffiliation.entries.first { it.name.equals(value, ignoreCase = true) }

    private fun taskColor(value: String): TaskColor =
        TaskColor.entries.first { it.name.equals(value, ignoreCase = true) }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
}
