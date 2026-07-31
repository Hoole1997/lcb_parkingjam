package com.example.lcb.parking.data.state

import com.example.lcb.parking.domain.ports.PendingFactBatch
import com.example.lcb.parking.domain.ports.PendingFactRecord
import com.example.lcb.parking.domain.rules.DomainFact
import java.util.ArrayDeque
import java.util.PriorityQueue

/**
 * Pure bounded-retention policy for the disposable telemetry outbox.
 *
 * Snapshot and PlayerProgress remain the authoritative business state. Invalid, oversized or old
 * telemetry is dropped with an accumulated counter instead of blocking a gameplay transaction.
 */
internal object TelemetryOutboxPolicy {

    const val MAX_RECORDS: Int = 1_024

    data class EncodedPayload(
        val payload: GameStatePayloadDto,
        val raw: String,
    )

    fun append(
        source: TelemetryOutboxDto?,
        facts: List<DomainFact>,
    ): TelemetryOutboxDto {
        val clean = sanitize(source)
        if (facts.isEmpty()) return clean

        // The staging deque is capped as records arrive, so a large fact batch cannot create a
        // second unbounded in-memory list before old telemetry is trimmed.
        val retained = ArrayDeque<PendingFactRecordDto>(MAX_RECORDS)
        clean.records.forEach { retained.addLast(checkNotNull(it)) }
        var nextSequence = clean.nextSequence
        var dropped = clean.droppedTelemetryCount
        facts.forEach { fact ->
            if (nextSequence >= Long.MAX_VALUE) {
                dropped = saturatedAdd(dropped, 1L)
            } else {
                if (retained.size == MAX_RECORDS) {
                    retained.removeFirst()
                    dropped = saturatedAdd(dropped, 1L)
                }
                retained.addLast(
                    PendingFactRecordDto(
                        sequence = nextSequence,
                        eventId = eventId(nextSequence),
                        fact = GameStateMapper.toDto(fact),
                    ),
                )
                nextSequence += 1L
            }
        }

        return TelemetryOutboxDto(
            records = retained.toList(),
            nextSequence = nextSequence,
            droppedTelemetryCount = dropped,
        )
    }

    /**
     * Invalid or over-capacity telemetry is disposable; retained sequence/event identities are
     * never rewritten. A bounded min-heap keeps only the newest sequences even for malformed input.
     */
    fun sanitize(source: TelemetryOutboxDto?): TelemetryOutboxDto {
        if (source == null) return TelemetryOutboxDto()
        val sourceRecords = source.records.orEmpty()
        val retained = PriorityQueue<PendingFactRecordDto>(
            MAX_RECORDS,
            compareBy(PendingFactRecordDto::sequence),
        )
        val retainedSequences = HashSet<Long>(MAX_RECORDS)
        val retainedEventIds = HashSet<String>(MAX_RECORDS)
        var maxObservedSequence = 0L
        var dropped = source.droppedTelemetryCount.coerceAtLeast(0L)
        sourceRecords.forEach { record ->
            val eventId = record?.eventId
            val fact = record?.fact
            if (record != null && record.sequence > maxObservedSequence) {
                maxObservedSequence = record.sequence
            }
            val validRecord = record != null &&
                record.sequence > 0L &&
                !eventId.isNullOrBlank() &&
                fact != null &&
                record.sequence !in retainedSequences &&
                eventId !in retainedEventIds &&
                isValidFact(fact)
            if (!validRecord) {
                dropped = saturatedAdd(dropped, 1L)
                return@forEach
            }

            if (retained.size == MAX_RECORDS) {
                val oldest = checkNotNull(retained.peek())
                if (oldest.sequence >= record.sequence) {
                    dropped = saturatedAdd(dropped, 1L)
                    return@forEach
                }
                val removed = retained.remove()
                retainedSequences.remove(removed.sequence)
                retainedEventIds.remove(checkNotNull(removed.eventId))
                dropped = saturatedAdd(dropped, 1L)
            }
            retained.add(record)
            retainedSequences.add(record.sequence)
            retainedEventIds.add(checkNotNull(eventId))
        }
        val valid = retained.toList().sortedBy(PendingFactRecordDto::sequence)
        val nextSequence = maxOf(
            1L,
            source.nextSequence,
            maxObservedSequence.saturatedIncrement(),
        )
        return TelemetryOutboxDto(
            records = valid,
            nextSequence = nextSequence,
            droppedTelemetryCount = dropped,
        )
    }

    fun acknowledge(source: TelemetryOutboxDto?, throughSequence: Long): Pair<TelemetryOutboxDto, Int> {
        require(throughSequence >= 0L) { "throughSequence must be non-negative" }
        val clean = sanitize(source)
        val retained = clean.records.filter { record ->
            checkNotNull(record).sequence > throughSequence
        }
        return clean.copy(records = retained) to (clean.records.size - retained.size)
    }

    fun toBatch(source: TelemetryOutboxDto?, limit: Int): PendingFactBatch {
        require(limit > 0) { "limit must be positive" }
        val clean = sanitize(source)
        val records = clean.records.asSequence().take(limit).map { record ->
            val value = checkNotNull(record)
            PendingFactRecord(
                sequence = value.sequence,
                eventId = checkNotNull(value.eventId),
                fact = GameStateMapper.toDomain(checkNotNull(value.fact)),
            )
        }.toList()
        return PendingFactBatch(records, clean.droppedTelemetryCount)
    }

    /**
     * Applies the byte target by dropping oldest telemetry in bounded logarithmic passes. If the
     * authoritative payload alone is larger, it is still returned and must still be committed.
     */
    fun encodeWithinTarget(
        source: GameStatePayloadDto,
        maxBytes: Int,
        encode: (GameStatePayloadDto) -> String,
    ): EncodedPayload {
        require(maxBytes > 0) { "maxBytes must be positive" }
        var outbox = sanitize(source.telemetryOutbox)
        var payload = source.copy(telemetryOutbox = outbox)
        var raw = encode(payload)
        while (raw.utf8Size() > maxBytes && outbox.records.isNotEmpty()) {
            val dropCount = maxOf(1, outbox.records.size / 2)
            outbox = outbox.copy(
                records = outbox.records.drop(dropCount),
                droppedTelemetryCount = saturatedAdd(
                    outbox.droppedTelemetryCount,
                    dropCount.toLong(),
                ),
            )
            payload = payload.copy(telemetryOutbox = outbox)
            raw = encode(payload)
        }
        return EncodedPayload(payload, raw)
    }

    private fun eventId(sequence: Long): String = "telemetry-$sequence"

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    /** Telemetry corruption must not hide VM-fatal errors such as OutOfMemoryError. */
    private fun isValidFact(fact: DomainFactDto): Boolean = try {
        GameStateMapper.toDomain(fact)
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    } catch (_: NullPointerException) {
        false
    }

    private fun Long.saturatedIncrement(): Long =
        if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second
}
