package com.tacmap.sync

/**
 * v3 vector clock: 63-bit counter + room-scoped actorId, transmitted as
 * "counterHex16:actorId". Higher counter wins; equal counter -> higher
 * actorId (lexicographic) wins. Never a JSON number.
 */
data class VersionStamp(val counter: Long, val actorId: String) : Comparable<VersionStamp> {

    init {
        require(counter >= 0) { "counter must be non-negative" }
    }

    override fun compareTo(other: VersionStamp): Int {
        // unsigned comparison via Long.compareUnsigned so the full 63-bit range works
        val c = java.lang.Long.compareUnsigned(counter, other.counter)
        if (c != 0) return c
        return actorId.compareTo(other.actorId)
    }

    fun encode(): String = counterHex16(counter) + ":" + actorId

    companion object {
        const val MAX_COUNTER = 0x7FFFFFFFFFFFFFFFL  // 2^63 - 1

        fun parse(vs: String): VersionStamp? {
            val colon = vs.indexOf(':')
            if (colon != 16) return null
            val hexPart = vs.substring(0, 16)
            val actor = vs.substring(17)
            if (actor.isEmpty()) return null
            val counter = try {
                java.lang.Long.parseUnsignedLong(hexPart, 16)
            } catch (_: NumberFormatException) {
                return null
            }
            if (counter < 0) return null  // would mean > 2^63-1
            return VersionStamp(counter, actor)
        }

        fun counterHex16(counter: Long): String =
            counter.toULong().toString(16).padStart(16, '0')

        fun isNewer(incoming: VersionStamp, existing: VersionStamp): Boolean =
            incoming > existing
    }
}
