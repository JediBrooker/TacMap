package com.tacmap.sync

import com.tacmap.localization.L10n

import org.json.JSONArray
import org.json.JSONObject

internal data class V2SnapshotBatch(
    val sequence: Long,
    val records: List<JSONObject>,
    val members: List<JSONObject>,
)

internal sealed interface V2SnapshotGateEvent {
    data object Ignored : V2SnapshotGateEvent
    data object Began : V2SnapshotGateEvent
    data object PageAccepted : V2SnapshotGateEvent
    data class Completed(val batch: V2SnapshotBatch) : V2SnapshotGateEvent
    data class Rejected(val reason: String) : V2SnapshotGateEvent
}

/**
 * Generation- and socket-bound v2 snapshot fence. The relay sends
 * snapshot-begin, one or more pages, then snapshot-end. No record is exposed to
 * the model and no local diff may be sent until this gate returns [V2SnapshotGateEvent.Completed].
 */
internal class V2SnapshotGate(
    private val maxItems: Int,
    private val maxAggregateBytes: Long,
) {
    private enum class Phase { IDLE, AWAITING_BEGIN, RECEIVING, FINAL_PAGE, COMPLETE, FAILED }

    private var phase = Phase.IDLE
    private var socketIdentity: Any? = null
    private var generation: Long = -1
    private var sequence: Long? = null
    private var aggregateBytes = 0L
    private val records = ArrayList<JSONObject>()
    private val members = ArrayList<JSONObject>()

    fun start(socketIdentity: Any, generation: Long) {
        this.socketIdentity = socketIdentity
        this.generation = generation
        phase = Phase.AWAITING_BEGIN
        sequence = null
        aggregateBytes = 0
        records.clear()
        members.clear()
    }

    fun cancel() {
        phase = Phase.IDLE
        socketIdentity = null
        generation = -1
        sequence = null
        aggregateBytes = 0
        records.clear()
        members.clear()
    }

    fun accept(
        socketIdentity: Any,
        generation: Long,
        message: JSONObject,
        frameBytes: Int,
    ): V2SnapshotGateEvent {
        if (!isCurrent(socketIdentity, generation)) return V2SnapshotGateEvent.Ignored
        if (phase == Phase.COMPLETE || phase == Phase.FAILED || phase == Phase.IDLE) {
            return V2SnapshotGateEvent.Ignored
        }
        if (frameBytes < 0 || aggregateBytes > maxAggregateBytes - frameBytes.toLong()) {
            return reject(L10n.text("The relay snapshot exceeded the safe size limit."))
        }
        aggregateBytes += frameBytes

        return when (message.optString("t")) {
            "snapshot-begin" -> acceptBegin(message)
            "snapshot" -> acceptPage(message)
            "snapshot-end" -> acceptEnd(message)
            else -> reject(L10n.text("The relay sent live data before completing its snapshot fence."))
        }
    }

    fun timeout(socketIdentity: Any, generation: Long): V2SnapshotGateEvent {
        if (!isCurrent(socketIdentity, generation)) return V2SnapshotGateEvent.Ignored
        return when (phase) {
            Phase.AWAITING_BEGIN, Phase.RECEIVING, Phase.FINAL_PAGE ->
                reject(L10n.text("The relay did not complete its initial snapshot in time."))
            else -> V2SnapshotGateEvent.Ignored
        }
    }

    private fun acceptBegin(message: JSONObject): V2SnapshotGateEvent {
        if (phase != Phase.AWAITING_BEGIN) {
            return reject(L10n.text("The relay snapshot began out of order."))
        }
        val seq = strictJsonInteger(message.opt("seq"), 0, Long.MAX_VALUE)
            ?: return reject(L10n.text("The relay snapshot fence was malformed."))
        sequence = seq
        phase = Phase.RECEIVING
        return V2SnapshotGateEvent.Began
    }

    private fun acceptPage(message: JSONObject): V2SnapshotGateEvent {
        if (phase != Phase.RECEIVING) {
            return reject(L10n.text("The relay snapshot page arrived out of order."))
        }
        val items = message.opt("items") as? JSONArray
            ?: return reject(L10n.text("The relay snapshot page was malformed."))
        val more = message.opt("more") as? Boolean
            ?: return reject(L10n.text("The relay snapshot page was missing its final-page marker."))
        if (records.size > maxItems - items.length()) {
            return reject(L10n.text("The relay snapshot contained too many records."))
        }
        val pageRecords = arrayObjects(items)
            ?: return reject(L10n.text("The relay snapshot contained a malformed record."))
        records += pageRecords

        if (message.has("members")) {
            val memberArray = message.opt("members") as? JSONArray
                ?: return reject(L10n.text("The relay snapshot member list was malformed."))
            if (members.size > maxItems - memberArray.length()) {
                return reject(L10n.text("The relay snapshot contained too many members."))
            }
            val pageMembers = arrayObjects(memberArray)
                ?: return reject(L10n.text("The relay snapshot contained a malformed member."))
            members += pageMembers
        }

        if (!more) phase = Phase.FINAL_PAGE
        return V2SnapshotGateEvent.PageAccepted
    }

    private fun acceptEnd(message: JSONObject): V2SnapshotGateEvent {
        if (phase != Phase.FINAL_PAGE) {
            return reject(L10n.text("The relay snapshot ended before its final page."))
        }
        val expected = sequence ?: return reject(L10n.text("The relay snapshot fence was missing."))
        val end = strictJsonInteger(message.opt("seq"), 0, Long.MAX_VALUE)
            ?: return reject(L10n.text("The relay snapshot end fence was malformed."))
        if (end != expected) return reject(L10n.text("The relay snapshot fence changed before completion."))

        phase = Phase.COMPLETE
        return V2SnapshotGateEvent.Completed(
            V2SnapshotBatch(expected, records.toList(), members.toList()),
        )
    }

    private fun reject(reason: String): V2SnapshotGateEvent.Rejected {
        phase = Phase.FAILED
        records.clear()
        members.clear()
        return V2SnapshotGateEvent.Rejected(reason)
    }

    private fun isCurrent(socketIdentity: Any, generation: Long): Boolean =
        this.socketIdentity === socketIdentity && this.generation == generation

    private fun arrayObjects(array: JSONArray): List<JSONObject>? {
        val result = ArrayList<JSONObject>(array.length())
        for (index in 0 until array.length()) {
            result += array.optJSONObject(index) ?: return null
        }
        return result
    }
}

