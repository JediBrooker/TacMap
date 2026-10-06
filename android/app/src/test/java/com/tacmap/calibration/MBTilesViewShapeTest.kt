package com.tacmap.calibration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** import_limits.json mbtilesMetadataAdmission viewShape + connection (s14.1), the parts that need no SQLite */
class MBTilesViewShapeTest {
    private val admission = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText())
        .jsonObject["mbtilesMetadataAdmission"]!!.jsonObject
    private val shape = admission["viewShape"]!!.jsonObject
    private val connection = admission["connection"]!!.jsonObject

    @Test
    fun everyViewShapeCaseGetsTheSharedVerdictReasonAndTables() {
        val cases = shape["cases"]!!.jsonArray.map { it.jsonObject }
        // a generator change that drops rows shouldn't pass by testing less
        assertTrue("only ${cases.size} cases", cases.size >= 63)
        val reasons = HashSet<String>()
        for (case in cases) {
            val id = case["id"]!!.jsonPrimitive.content
            val expect = case["expect"]!!.jsonObject
            val got = MBTilesViewShape.check(case["sql"]!!.jsonPrimitive.content, case["relation"]!!.jsonPrimitive.content)
            if (expect["accepted"]!!.jsonPrimitive.boolean) {
                val tables = expect["tables"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(id, MBTilesViewShape.Result.Accepted(tables), got)
            } else {
                val reason = expect["reason"]!!.jsonPrimitive.content
                reasons += reason
                assertEquals(id, MBTilesViewShape.Result.Refused(reason), got)
            }
        }
        assertEquals(setOf("tooLong", "token", "shape"), reasons)
        assertEquals(MBTilesViewShape.Result.Refused("shape"), MBTilesViewShape.check(null, "tiles"))
    }

    @Test
    fun theTokenizerAndKeywordsAreTheSharedOnes() {
        assertEquals(shape["maxSqlBytes"]!!.jsonPrimitive.int, MBTilesViewShape.MAX_SQL_BYTES)
        val reserved = shape["reservedWords"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(149, reserved.size)
        assertEquals(reserved.toSet(), MBTilesViewShape.RESERVED_WORDS)
        assertEquals(listOf(" ", "\t", "\r", "\n"), shape["whitespace"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("(", ")", ",", ".", "*", "="), shape["punctuation"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun connectionCapsAndGatesAreTheSharedOnes() {
        assertEquals(connection.l("maxTileBytes"), MBTilesStore.MAX_TILE_BYTES.toLong())
        assertEquals(connection.l("maxValueBytes"), MBTilesStore.MAX_VALUE_BYTES.toLong())
        assertEquals(connection.l("maxSchemaSqlBytes"), MBTilesStore.MAX_SCHEMA_SQL_BYTES.toLong())
        val android = connection["android"]!!.jsonObject
        assertEquals(android.l("hardHeapLimitBytes"), MBTilesStore.HARD_HEAP_LIMIT_BYTES)
        assertEquals(SqliteVersion.parse(android["hardHeapLimitMinSqlite"]!!.jsonPrimitive.content), MBTilesStore.HARD_HEAP_LIMIT_MIN_SQLITE)
        assertEquals(SqliteVersion.parse(connection["generatedColumnsMinSqlite"]!!.jsonPrimitive.content), MBTilesStore.GENERATED_COLUMNS_MIN_SQLITE)
        // 3.0.2: the admission budget is for views only
        assertEquals("views", admission["admissionBudgetAppliesTo"]!!.jsonPrimitive.content)
    }

    @Test
    fun everyBaseTableShapeCaseGetsTheSharedVerdict() {
        // SEC-M1-SHADOW: a base table row has to declare its own name, the generator's table_declares()
        val cases = admission["baseTableShape"]!!.jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue("only ${cases.size} cases", cases.size >= 35)
        val verdicts = HashMap<Boolean, Int>()
        for (case in cases) {
            val id = case["id"]!!.jsonPrimitive.content
            val sql = case["sql"]!!.jsonPrimitive.contentOrNull
            val want = case["expect"]!!.jsonObject["accepted"]!!.jsonPrimitive.boolean
            assertEquals(id, want, MBTilesViewShape.tableDeclares(sql, case["name"]!!.jsonPrimitive.content))
            verdicts[want] = (verdicts[want] ?: 0) + 1
        }
        // both verdicts really show up, IF NOT EXISTS and the name lie among the refusals
        assertTrue(verdicts.keys == setOf(true, false))
        assertFalse(MBTilesViewShape.tableDeclares("CREATE TABLE IF NOT EXISTS tiles (a)", "tiles"))
        assertFalse(MBTilesViewShape.tableDeclares("CREATE TABLE decoy (a)", "t"))
        assertFalse(MBTilesViewShape.tableDeclares(null, "tiles"))
    }

    @Test
    fun sqliteVersionsCompareByNumberNotText() {
        assertEquals(SqliteVersion(3, 44, 3), SqliteVersion.parse("3.44.3"))
        assertEquals(SqliteVersion(3, 9, 0), SqliteVersion.parse("3.9"))
        assertTrue(SqliteVersion.parse("3.9.2")!! < SqliteVersion(3, 31, 0))
        assertTrue(SqliteVersion.parse("3.31.0")!! >= SqliteVersion(3, 31, 0))
        assertTrue(SqliteVersion.parse("3.100.0")!! > SqliteVersion(3, 31, 0))
        assertNull(SqliteVersion.parse("three"))
        assertNull(SqliteVersion.parse(null))
    }

    private fun JsonObject.l(k: String): Long = this[k]!!.jsonPrimitive.long
}
