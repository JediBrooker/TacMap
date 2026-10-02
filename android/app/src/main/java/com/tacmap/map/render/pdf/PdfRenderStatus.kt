package com.tacmap.map.render.pdf

import kotlinx.coroutines.flow.collectLatest

/** contract G. preparing until the base raster or the first real tile lands, failed is sticky */
sealed class PdfRenderStatus {
    data object None : PdfRenderStatus()
    data object Preparing : PdfRenderStatus()
    data object Ready : PdfRenderStatus()
    data class Failed(val reason: PdfRenderFailure) : PdfRenderStatus()
}

/** pure bits of the status rules, shared with the import probe */
object PdfRenderRules {
    const val PREPARING_LABEL_DELAY_MS = 300L
    const val RENDER_ERROR_RUN = 3
    const val BLANK_SAMPLE_STRIDE = 4
    const val BLANK_WHITE_MAX = 250

    const val PREPARING_COLOR = 0xFFFFC247
    const val FAILED_COLOR = 0xFFFF5A5A
    const val READY_COLOR = 0xFF74E38A

    /**
     * Every 4th pixel of the full res base raster (unpremultiplied ARGB ints, row major). Blank when no
     * sample has alpha > 0 with min(R,G,B) <= 250, i.e. nothing but transparent or
     * near white. Catches a page that renders to nothing so we don't paint white over the map.
     */
    fun isBlank(pixels: IntArray, width: Int, height: Int, stride: Int = BLANK_SAMPLE_STRIDE): Boolean {
        var y = 0
        while (y < height) {
            var x = 0
            val row = y * width
            while (x < width) {
                val c = pixels[row + x]
                val a = c ushr 24
                if (a > 0) {
                    // Bitmap.getPixels hands back unpremultiplied colours, so these are the real ones
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    if (minOf(r, g, b) <= BLANK_WHITE_MAX) return false
                }
                x += stride
            }
            y += stride
        }
        return true
    }

    /**
     * "Drawing map…" only once preparing has lasted 300 ms (G), so a warm open never
     * flashes it. Runs until the caller's scope goes, [show] gets every change
     */
    suspend fun preparingLabel(status: kotlinx.coroutines.flow.Flow<PdfRenderStatus>, show: (Boolean) -> Unit) {
        status.collectLatest { s ->
            show(false)
            if (s == PdfRenderStatus.Preparing) {
                kotlinx.coroutines.delay(PREPARING_LABEL_DELAY_MS)
                show(true)
            }
        }
    }

    /** which way a live job drew its pixels. doesn't change the accounting, it's for the log + tests */
    enum class JobPath { VECTOR, STAGED, RASTER_SAMPLE, BASE_RASTER }

    /** the reason a throwable carries for G2: OOM either way, our own exception's reason, else null (unclassified) */
    fun reasonOf(t: Throwable): PdfRenderFailure? = when (t) {
        is OutOfMemoryError -> PdfRenderFailure.OUT_OF_MEMORY
        is PdfRenderException -> t.failure
        else -> null
    }

    /**
     * Live source failure accounting (contract G2), pinned by the fixture's
     * failureAccounting cases. Document level trouble (file, password, page, geometry,
     * the blank verdict) is sticky at once. Everything else a live job throws counts,
     * 3 in a row and the source goes sticky with the 3rd one's reason. Bake and estimate
     * jobs never come through here, they report to the bake.
     */
    class FailureTracker {
        var failed: PdfRenderFailure? = null
            @Synchronized get
            private set
        var consecutiveFailures: Int = 0
            @Synchronized get
            private set

        /** a live job delivered (any mix of image + EMPTY) */
        @Synchronized fun jobOk() {
            if (failed == null) consecutiveFailures = 0
        }

        /**
         * A live job threw. [reason] null = something we can't classify, counts as renderError.
         * Returns the reason when this one made the source sticky.
         */
        @Synchronized fun jobFailed(path: JobPath, reason: PdfRenderFailure?): PdfRenderFailure? {
            if (failed != null) return null
            return when (reason) {
                PdfRenderFailure.CANNOT_OPEN, PdfRenderFailure.PASSWORD_PROTECTED,
                PdfRenderFailure.PAGE_MISSING, PdfRenderFailure.PAGE_GEOMETRY -> stick(reason)
                else -> {
                    consecutiveFailures++
                    if (consecutiveFailures >= RENDER_ERROR_RUN) {
                        // blank never comes off a job, only the document check says that
                        stick(if (reason == PdfRenderFailure.OUT_OF_MEMORY) reason else PdfRenderFailure.RENDER_ERROR)
                    } else null
                }
            }
        }

        /** a cancelled live job neither counts nor resets */
        fun jobCancelled() = Unit

        /** bake and bake estimate jobs, ok or not, report to the bake and leave this alone */
        fun bakeJobFinished() = Unit

        /** open / parse failed for the whole document */
        @Synchronized fun documentFailure(reason: PdfRenderFailure): PdfRenderFailure? =
            if (failed != null) null else stick(reason)

        /** the base raster came back with nothing on it */
        @Synchronized fun blankVerdict(): PdfRenderFailure? = documentFailure(PdfRenderFailure.BLANK)

        /** Try Again. the runtime builds a fresh source anyway, this is for the tests */
        @Synchronized fun retry() {
            failed = null
            consecutiveFailures = 0
        }

        private fun stick(reason: PdfRenderFailure): PdfRenderFailure {
            failed = reason
            consecutiveFailures = 0
            return reason
        }
    }

    /**
     * Does a finished job add an EWMA sample (E1 + R2). Only vector work (direct or staged)
     * that started and gave back at least one image. Whether anyone still waits on it
     * doesn't matter, a job cancelled before it started never gets here
     */
    fun feedsEwma(path: JobPath, ok: Boolean, anyImage: Boolean): Boolean =
        ok && anyImage && (path == JobPath.VECTOR || path == JobPath.STAGED)

    /**
     * The base raster's life on one live tile source (contract G r1, R1), pinned by the
     * failureAccounting cases that carry baseMaxZoom. Started once, by the first wanted
     * callback at any z. A failure counts once in [failures] and only a wanted tile at
     * z <= baseMaxZoom with nothing in flight gets another go. Success resets the run and
     * asks for a re-plan, blank is sticky. The plan never changes under it (no halving).
     */
    class BaseRasterLifecycle(
        private val baseMaxZoom: Int,
        private val failures: FailureTracker,
        /** the import probe already handed the raster over */
        alreadyReady: Boolean = false,
    ) {
        enum class Status { NOT_STARTED, IN_FLIGHT, READY, BLANK, FAILED }

        var status: Status = if (alreadyReady) Status.READY else Status.NOT_STARTED
            @Synchronized get
            private set
        var attempts: Int = 0
            @Synchronized get
            private set

        /** a non ignored wanted callback at tile zoom [z]. true = start an attempt right now */
        @Synchronized fun wanted(z: Int): Boolean {
            if (failures.failed != null) return false
            val go = when (status) {
                Status.NOT_STARTED -> true
                Status.FAILED -> z <= baseMaxZoom
                else -> false
            }
            if (go) {
                status = Status.IN_FLIGHT
                attempts++
            }
            return go
        }

        /** what an attempt came back with */
        sealed class Result {
            data object Ok : Result()
            data object Blank : Result()
            /** null reason = something we can't classify, counts as renderError */
            data class Failed(val reason: PdfRenderFailure?) : Result()
        }

        /** the attempt in flight finished. returns whether the view should re-plan */
        @Synchronized fun done(result: Result): Boolean {
            if (status != Status.IN_FLIGHT) return false
            return when (result) {
                Result.Ok -> {
                    status = Status.READY
                    failures.jobOk()
                    failures.failed == null
                }
                Result.Blank -> {
                    status = Status.BLANK
                    failures.blankVerdict()
                    false
                }
                is Result.Failed -> {
                    status = Status.FAILED
                    failures.jobFailed(JobPath.BASE_RASTER, result.reason)
                    false
                }
            }
        }

        /** Try Again on the fixture's single tracker. the app builds a new source instead */
        @Synchronized fun reset() {
            status = Status.NOT_STARTED
            attempts = 0
        }
    }
}
