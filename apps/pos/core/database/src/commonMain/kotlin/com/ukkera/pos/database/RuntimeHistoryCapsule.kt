package com.ukkera.pos.database

import com.ukkera.pos.security.NativeHistoryOriginalIntentV2
import com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2
import com.ukkera.pos.security.sha256

/** Reads only bounded intent/dependency/fact sections while all original chunks stream past.
 * Used by both the full event commitment and an authentication row's historical event join.
 * No event image or dataset is accumulated, no caller-selected offset is sent to native IO. */
internal class RuntimeHistoryCapsule(private val header: RuntimeHistoryEventHeaderV2, capsuleOnly: Boolean = false) {
    val intent = ByteArray(header.intentBytes)
    val dependencies = ByteArray(header.dependencyBytes)
    val facts = ByteArray(header.factBytes)
    // Event commitment traversal always starts at zero. Only the subsequent, native-selected
    // lineage relation starts at the chunk containing the already-verified capsule boundary.
    // It must still deliver every capsule byte and the exact final ordinal; no caller offset.
    private var chunks = if (capsuleOnly) (header.imageBytes / RuntimeHistoryProfileV2.MAX_CHUNK_BYTES).toInt() else 0
    private var complete = false

    init { historyShapeV2(chunks in 0 until header.chunkCount) }

    fun append(value: RuntimeHistoryChunkRowV2) {
        historyShapeV2(!complete && value.sequence == header.sequence && value.ordinal == chunks && chunks < header.chunkCount)
        val start = chunks.toLong() * RuntimeHistoryProfileV2.MAX_CHUNK_BYTES
        val expected = minOf(RuntimeHistoryProfileV2.MAX_CHUNK_BYTES.toLong(), header.payloadBytes() - start)
        historyShapeV2(expected > 0 && value.bytes.size.toLong() == expected)
        copyIntersection(value.bytes, start, header.imageBytes, intent)
        copyIntersection(value.bytes, start, header.imageBytes + header.intentBytes, dependencies)
        copyIntersection(value.bytes, start, header.imageBytes + header.intentBytes + header.dependencyBytes, facts)
        chunks++
    }

    fun finish(): NativeHistoryOriginalIntentV2 {
        historyShapeV2(!complete && chunks == header.chunkCount && sha256(intent) == header.intentDigest &&
            sha256(dependencies) == header.dependencyDigest && sha256(facts) == header.factDigest)
        val decoded = SelfOwnedNativeHistoryWireV2.decodeIntent(intent)
        val canonical = SelfOwnedNativeHistoryWireV2.encodeIntent(decoded)
        try { historyShapeV2(canonical.contentEquals(intent)) } finally { canonical.fill(0) }
        historyShapeV2(decoded.purposeTag == header.purpose.tag && decoded.dependenciesDigest == header.dependencyDigest &&
            decoded.originalFactsDigest == header.factDigest)
        if (RuntimeCommandAuthorizationEvidenceCodec.hasProfile(facts)) {
            val command = RuntimeCommandAuthorizationEvidenceCodec.decode(facts)
            historyShapeV2(command.purposeTag == header.purpose.tag && command.capturedChanges == header.changeCount &&
                command.authorization.scope.storageKey() == header.scope &&
                decoded.originalOperationId?.value == command.operationId.value && decoded.originalRequestId.value == command.operationId.value &&
                decoded.originalActorId == command.authorization.actorId && decoded.originalRequestDigest == command.semanticDigest)
            // Canonical framing/header binding only. The historical protected dependencies,
            // requirement/approval, original producer/effects and recovery joins are separate
            // mandatory scanner checks. This discriminator removes NO hold by itself.
            // Legacy owned-decision/authentication readers stay unchanged.
        }
        val selectors = decoded.resources.map { Triple(it.selectorTag, it.kind?.tag, it.resourceId) }
        historyShapeV2(selectors.distinct().size == selectors.size)
        complete = true
        return decoded
    }

    fun clear() { intent.fill(0); dependencies.fill(0); facts.fill(0) }

    private fun copyIntersection(chunk: ByteArray, chunkStart: Long, sectionStart: Long, target: ByteArray) {
        val from = maxOf(chunkStart, sectionStart)
        val to = minOf(chunkStart + chunk.size, sectionStart + target.size)
        if (from < to) chunk.copyInto(target, (from - sectionStart).toInt(), (from - chunkStart).toInt(), (to - chunkStart).toInt())
    }
}
