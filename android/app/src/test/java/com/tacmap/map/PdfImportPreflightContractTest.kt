package com.tacmap.map

import org.junit.Assert.assertTrue
import org.junit.Test

class PdfImportPreflightContractTest {
    @Test
    fun everyUnsupportedRightAngleHasActionableNormalizationGuidance() {
        listOf(90, 180, 270).forEach { rotation ->
            val message = pdfRotationRejectionMessage(rotation)
            assertTrue(message.contains("$rotation°"))
            assertTrue(message.contains("Flatten"))
            assertTrue(message.contains("print it to a new PDF"))
        }
    }

    @Test
    fun invalidAndProtectedInputsGetAStableNonTechnicalMessage() {
        val message = pdfImportUserMessage(IllegalStateException("private/path/document.pdf"))
        assertTrue(message.contains("invalid or password-protected"))
        assertTrue(!message.contains("private/path"))
    }

    @Test
    fun hugeInputGetsTheDocumentedLimit() {
        val failure = IllegalArgumentException("Import exceeds the supported size limit")
        assertTrue(pdfImportUserMessage(failure).contains("256 MB"))
    }
}
