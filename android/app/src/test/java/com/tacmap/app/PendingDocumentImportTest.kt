package com.tacmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5-18: the picked URI (it carries the file name) lives in memory and
 * savedInstanceState only. Older builds' plaintext prefs are read once and wiped.
 */
class PendingDocumentImportTest {
    private class FakePrefs(vararg pairs: Pair<String, Any>) : LegacyPendingImportPrefs {
        val values = HashMap<String, Any>(pairs.toMap())
        var clears = 0
        override fun getString(key: String): String? = values[key] as? String
        override fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false
        override fun clearAll(): Boolean { clears++; values.clear(); return true }
    }

    private fun legacy() = FakePrefs(
        PendingDocumentImportRestore.KEY_TOKEN to "tok-1",
        PendingDocumentImportRestore.KEY_KIND to "pdf",
        PendingDocumentImportRestore.KEY_URI to "content://com.android.providers.downloads.documents/document/Kestrel%20Ridge.pdf",
        PendingDocumentImportRestore.KEY_GRANT to true,
    )

    @Test
    fun aLegacyPickIsAdoptedOnceAndThePrefsFileIsWiped() {
        val prefs = legacy()
        val restored = PendingDocumentImportRestore.restore(null, prefs)
        assertEquals(PendingDocumentImport("tok-1", DocumentImportKind.PDF, prefs.let { "content://com.android.providers.downloads.documents/document/Kestrel%20Ridge.pdf" }, true), restored)
        assertEquals(1, prefs.clears)
        assertTrue("nothing about the pick left on disk", prefs.values.isEmpty())
        // second launch: nothing to adopt
        assertNull(PendingDocumentImportRestore.restore(null, prefs))
    }

    @Test
    fun theBundleWinsButTheLegacyFileStillGoes() {
        val prefs = legacy()
        val bundle = PendingDocumentImport("tok-2", DocumentImportKind.MBTILES, "content://x/tiles", false)
        assertEquals(bundle, PendingDocumentImportRestore.restore(bundle, prefs))
        assertEquals(1, prefs.clears)
    }

    @Test
    fun junkLegacyValuesAdoptNothingButAreStillWiped() {
        val prefs = FakePrefs(PendingDocumentImportRestore.KEY_URI to "content://x/y", PendingDocumentImportRestore.KEY_KIND to "nope")
        assertNull(PendingDocumentImportRestore.restore(null, prefs))
        assertEquals(1, prefs.clears)
    }

    @Test
    fun aPickNeverWritesAnythingDurable() {
        // the coordinator is memory only; publish/claim/complete touch no storage at all
        val c = PendingDocumentImportCoordinator()
        val p = c.publish(DocumentImportKind.PDF, "content://x/Sheet.pdf", persistableGrantTaken = true)
        assertEquals(p, c.savedSnapshot())
        assertEquals(p, c.claim(p.token))
        assertEquals(p, c.complete(p.token))
        assertNull(c.savedSnapshot())
    }

    @Test
    fun leakedGrantsAreEverythingButThePickInHand() {
        val held = listOf("content://a/1", "content://a/2", "content://a/3")
        assertEquals(held, PendingDocumentImportRestore.orphanedGrants(held, null))
        val pending = PendingDocumentImport("t", DocumentImportKind.PDF, "content://a/2", true)
        assertEquals(listOf("content://a/1", "content://a/3"), PendingDocumentImportRestore.orphanedGrants(held, pending))
    }
}
