package com.tacmap.waypoints

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import com.tacmap.util.DataKey
import com.tacmap.export.GeoJsonExporter
import com.tacmap.export.GeoJsonImporter
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CustomSymbolPackTest {
    private val fixture = """{"format":1,"name":"Test Pack","attribution":"Test fixture from CC0 Taktische-Zeichen v2.0.0","symbols":[{"id":"6e5cdd96a6e02a921b45134ccc23b3bc644c3f6c9c05dd365a2b1eb20f3200b9","name":"Bundeswehr Einheiten / Einheit der Bundeswehr","png":"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"}]}"""
    @Test fun encryptedPersistenceAndLockedOrCorruptStoreCannotBeOverwritten() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "symbol-test-${System.nanoTime()}").apply { mkdirs() }
        val old = SafeStore.keyProvider
        try {
            CustomSymbolStore.initialize(object : ContextWrapper(context) { override fun getFilesDir(): File = root })
            val pack = CustomSymbolStore.importPack(fixture.byteInputStream())
            val file = File(root, "custom-symbol-packs.sealed")
            val original = file.readBytes()
            assertTrue(SealedEnvelope.isSealedFile(original))
            assertFalse(original.decodeToString().contains("Test Pack"))
            CustomSymbolStore.clear(); CustomSymbolStore.reload()
            assertEquals(pack, CustomSymbolStore.packs.first())
            val prepared = CustomSymbolStore.preparePack(fixture.byteInputStream())
            SafeStore.keyProvider = SafeStore.KeyProvider { throw DataKey.LockedException() }
            assertThrows(Exception::class.java) { CustomSymbolStore.installPack(prepared) }
            assertArrayEquals(original, file.readBytes())
            assertThrows(Exception::class.java) { CustomSymbolStore.importPack(fixture.byteInputStream()) }
            assertArrayEquals(original, file.readBytes())
            SafeStore.keyProvider = old
            val corrupt = original.copyOf(); corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte(); file.writeBytes(corrupt)
            assertThrows(Exception::class.java) { CustomSymbolStore.importPack(fixture.byteInputStream()) }
            assertArrayEquals(corrupt, file.readBytes())
            file.writeText(fixture)
            assertThrows(Exception::class.java) { CustomSymbolStore.reload() }
            assertEquals(fixture, file.readText())
        } finally { SafeStore.keyProvider = old; CustomSymbolStore.clear(); CustomSymbolStore.initialize(context); root.deleteRecursively() }
    }
    @Test fun artworkRoundTripsThroughGeoJSONAndRejectsTampering() {
        val pack = Json.decodeFromString<CustomSymbolPack>(fixture)
        CustomSymbolStore.validate(pack)
        val symbol = pack.symbols.first()
        assertNull(symbol.copy(id = "wrong").image())
        assertNull(symbol.copy(png = "https://example.invalid/image.png").image())
        assertThrows(Exception::class.java) { CustomSymbolStore.validate(pack.copy(symbols = listOf(symbol, symbol))) }
        assertThrows(Exception::class.java) { CustomSymbolStore.preflight("[".repeat(100) + "]".repeat(100)) }
        CustomSymbolStore.preflight(fixture)
        val wp = Waypoint(name = "Crew", latitude = 51.0, longitude = 9.0, kind = WaypointKind.Marker(MarkerSymbol(MarkerSet.CUSTOM, symbol.id, "#3B7BE0", symbol)))
        val exported = GeoJsonExporter.export(listOf(wp))
        val result = GeoJsonImporter.parse(exported, emptyList(), wp.layerId)
        val restored = (result.waypoints.first().kind as WaypointKind.Marker).marker.custom
        assertEquals(symbol, restored)
        assertNotNull(restored?.image())
    }
}
