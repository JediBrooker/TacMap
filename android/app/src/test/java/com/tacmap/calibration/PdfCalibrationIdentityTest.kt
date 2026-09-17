package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfCalibrationIdentityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sameFilenameWithDifferentBytesHasDifferentIdentity() {
        val firstDirectory = temporaryFolder.newFolder("first")
        val secondDirectory = temporaryFolder.newFolder("second")
        val first = firstDirectory.resolve("sheet.pdf").apply { writeBytes("revision-one".toByteArray()) }
        val second = secondDirectory.resolve("sheet.pdf").apply { writeBytes("revision-two".toByteArray()) }

        assertNotEquals(
            PdfCalibrationIdentity.contentKey(first),
            PdfCalibrationIdentity.contentKey(second),
        )
    }

    @Test
    fun identicalBytesWithDifferentNamesHaveSameIdentity() {
        val bytes = "%PDF-1.7\nsame map bytes".toByteArray()
        val first = temporaryFolder.newFile("alpha.pdf").apply { writeBytes(bytes) }
        val second = temporaryFolder.newFile("bravo.pdf").apply { writeBytes(bytes) }

        assertEquals(
            PdfCalibrationIdentity.contentKey(first),
            PdfCalibrationIdentity.contentKey(second),
        )
    }

    @Test
    fun coldLookupResolvesOnlyTheExactPdfBytes() {
        val selected = temporaryFolder.newFile("selected.pdf").apply { writeBytes("selected".toByteArray()) }
        val replacement = temporaryFolder.newFile("replacement.pdf").apply { writeBytes("replacement".toByteArray()) }
        val persistedLibrary = mapOf(
            requireNotNull(PdfCalibrationIdentity.contentKey(selected)) to "selected calibration",
        )
        val freshlyLoadedLibrary = persistedLibrary.toMap()

        assertEquals(
            "selected calibration",
            PdfCalibrationIdentity.lookup(selected, freshlyLoadedLibrary),
        )
        assertNull(PdfCalibrationIdentity.lookup(replacement, freshlyLoadedLibrary))
    }

    @Test
    fun filenameOnlyLegacyEntryIsNeverTreatedAsContentIdentity() {
        val file = temporaryFolder.newFile("shared-name.pdf").apply { writeBytes("new revision".toByteArray()) }

        assertNull(PdfCalibrationIdentity.lookup(file, mapOf(file.name to "legacy calibration")))
    }

    @Test
    fun missingOrNonFileHasNoIdentity() {
        assertNull(PdfCalibrationIdentity.contentKey(temporaryFolder.root.resolve("missing.pdf")))
        assertNull(PdfCalibrationIdentity.contentKey(temporaryFolder.newFolder("directory.pdf")))
        assertNull(PdfCalibrationIdentity.contentKey(null))
    }
}
