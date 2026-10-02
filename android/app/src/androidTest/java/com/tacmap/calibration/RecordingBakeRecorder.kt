package com.tacmap.calibration

/**
 * Stands in for the library side of a bake publish (MapViewModel in the app) in tests that
 * drive PdfBaker directly. Takes every attach unless told not to and remembers the last one,
 * so a test can say "nothing was recorded" the way it used to ask the PDF session
 */
internal class RecordingBakeRecorder(
    private val answer: PdfBakeAttach = PdfBakeAttach.Attached,
) : PdfBakeRecorder {
    @Volatile var attached: PersistedPdfBake? = null
        private set
    @Volatile var attempts = 0
        private set

    override fun attach(entryId: String?, contentKey: String?, renderGuardToken: String, bake: PersistedPdfBake): PdfBakeAttach {
        attempts++
        if (answer == PdfBakeAttach.Attached) attached = bake
        return answer
    }

    override fun detach(entryId: String?, bake: PersistedPdfBake): Boolean {
        if (attached != bake) return false
        attached = null
        return true
    }
}
