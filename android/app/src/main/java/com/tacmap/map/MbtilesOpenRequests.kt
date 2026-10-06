package com.tacmap.map

/**
 * s14.2: the one MBTiles open that can be running off main, and whether what it brings back
 * still gets to go up. A newer request or anything else going up supersedes it, and a library
 * that moved on since it started makes it stale. Pure, main thread only
 */
internal class MbtilesOpenRequests {
    data class Ticket(val seq: Long, val entryId: String, val generation: Long)

    enum class Verdict {
        /** still the newest request on the same library: put it up (an activation writes first) */
        CURRENT,
        /** nothing newer asked for, but the library was written meanwhile */
        MOVED,
        /** something newer came along, or nothing can take it now (locked, cleared): close it */
        SUPERSEDED,
    }

    private var seq = 0L

    var pending: Ticket? = null
        private set

    /** a new open for [entryId] on library [generation], whatever was running is superseded */
    fun begin(entryId: String, generation: Long): Ticket = Ticket(++seq, entryId, generation).also { pending = it }

    /** something else went up, or the screen's going. returns what was running, if anything */
    fun supersede(): Ticket? = pending.also { pending = null }

    /** the open came back. [generation] = the library now, null when it can't take a result */
    fun finish(ticket: Ticket, generation: Long?): Verdict {
        if (pending?.seq != ticket.seq) return Verdict.SUPERSEDED
        pending = null
        return when (generation) {
            null -> Verdict.SUPERSEDED
            ticket.generation -> Verdict.CURRENT
            else -> Verdict.MOVED
        }
    }
}
