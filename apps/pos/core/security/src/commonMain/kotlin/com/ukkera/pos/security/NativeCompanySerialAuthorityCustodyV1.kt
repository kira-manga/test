package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

internal fun CompanyBusinessPublicationKind.isOriginalSerialKindV1(): Boolean =
    this == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 || this == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1

/** Digest of the actual serial subset, not an encoder from which a lost ledger is replayed. */
internal data class NativeCompanySerialLedgerCutV1(
    val ledgerPresent: Boolean,
    val generation: Long,
    val records: Int,
    val remainingPhaseTransitions: Int,
    val digest: PayloadDigest,
) {
    init {
        require(generation >= 0 && records in 0..CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS &&
            remainingPhaseTransitions in 0..(2 * records))
        require(ledgerPresent || (generation == 0L && records == 0 && remainingPhaseTransitions == 0))
    }

    /** Nonserial native updates may advance generation, never change this serial cut. */
    fun covers(actual: NativeCompanySerialLedgerCutV1): Boolean = ledgerPresent == actual.ledgerPresent &&
        generation <= actual.generation && records == actual.records && remainingPhaseTransitions == actual.remainingPhaseTransitions &&
        digest == actual.digest

    companion object {
        fun observe(state: NativeCompanyBusinessPublicationStateV1?): NativeCompanySerialLedgerCutV1 {
            val records = state?.records.orEmpty().filter { it.kind.isOriginalSerialKindV1() }
            return NativeCompanySerialLedgerCutV1(state != null, state?.generation ?: 0L, records.size,
                records.sumOf { record -> when (record.phase) {
                    CompanyBusinessPublicationPhase.PREPARED -> 2
                    CompanyBusinessPublicationPhase.SIGNING_INTENT -> 1
                    CompanyBusinessPublicationPhase.COMPLETED -> 0
                } }, NativeCompanyBusinessPublicationWireV1.serialCutDigest(state))
        }
    }
}

/** Exact next-cut intent retained BEFORE the companion can change. No previous-cut fallback. */
internal data class NativeCompanySerialLedgerIntentV1(
    val originalActionId: DomainId,
    val publicationId: DomainId,
    val entityId: DomainId,
    val signingKeyId: DomainId,
    val kind: CompanyBusinessPublicationKind,
    val contentDigest: PayloadDigest,
    val phase: CompanyBusinessPublicationPhase,
    val previous: NativeCompanySerialLedgerCutV1,
    val next: NativeCompanySerialLedgerCutV1,
) {
    init {
        require(kind.isOriginalSerialKindV1() && listOf(originalActionId, publicationId, entityId, signingKeyId).distinct().size == 4)
        require(next.ledgerPresent && next.records in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS &&
            next.generation >= previous.generation && next.records in previous.records..previous.records + 1 && next.digest != previous.digest)
        if (next.records > previous.records) require(phase == CompanyBusinessPublicationPhase.PREPARED)
    }

    fun requireActualTarget(state: NativeCompanyBusinessPublicationStateV1) {
        require(NativeCompanySerialLedgerCutV1.observe(state) == next) { "Exact retained serial next cut required" }
        val record = state.records.singleOrNull { it.originalActionId == originalActionId }
        require(record != null && record.publicationId == publicationId && record.entityId == entityId && record.keyId == signingKeyId &&
            record.kind == kind && record.digest == contentDigest && record.phase == phase)
    }

    companion object {
        fun observe(previous: NativeCompanySerialLedgerCutV1, next: NativeCompanyBusinessPublicationStateV1,
            changed: NativeCompanyBusinessPublicationRecordV1): NativeCompanySerialLedgerIntentV1 =
            NativeCompanySerialLedgerIntentV1(changed.originalActionId, changed.publicationId, changed.entityId, changed.keyId,
                changed.kind, changed.digest, changed.phase, previous, NativeCompanySerialLedgerCutV1.observe(next))
    }
}

/**
 * Permanent serial custody inside the MANDATORY authenticated original company
 * registry. An optional companion's absence can never reset this origin or its
 * reservations. This is not a guarantee against coherent rollback of all original
 * native storage when that original custody has no independent rollback fence.
 */
internal data class NativeCompanySerialAuthorityCustodyV1(
    val revision: Long,
    val authority: LocalPublishedSerialAuthorityV1,
    val originalAuthorityActionId: DomainId,
    val authoritySigningKeyId: DomainId,
    val settled: NativeCompanySerialLedgerCutV1?,
    val intent: NativeCompanySerialLedgerIntentV1?,
) {
    init {
        require(revision in 0 until Long.MAX_VALUE && (settled != null || intent != null))
        val reserved = authority.origin.identityIds() + listOf(authority.authorityId, authority.publicationId)
        require(originalAuthorityActionId != authoritySigningKeyId && originalAuthorityActionId !in reserved && authoritySigningKeyId !in reserved)
        settled?.let { require(it.ledgerPresent && it.records >= 1) }
        if (settled == null) {
            val first = requireNotNull(intent)
            require(revision == 0L && first.previous.records == 0 && first.next.records == 1 &&
                first.originalActionId == originalAuthorityActionId && first.signingKeyId == authoritySigningKeyId &&
                first.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 && first.phase == CompanyBusinessPublicationPhase.PREPARED &&
                first.publicationId == authority.publicationId && first.entityId == authority.authorityId &&
                first.contentDigest == LocalSerialAuthorityPublicationCodecV1.reference(authority).contentDigest)
        } else intent?.let { require(it.previous == settled) }
    }

    val reservedCompletionRevisions: Long get() =
        2L * requireNotNull(intent?.next ?: settled).remainingPhaseTransitions + if (intent == null) 0L else 1L

    fun reference(): OriginalLocalSerialCustodyReferenceV1 = OriginalLocalSerialCustodyReferenceV1(
        LocalSerialAuthorityPublicationCodecV1.reference(authority), originalAuthorityActionId,
        if (intent == null) OriginalLocalSerialCustodyPhaseV1.SETTLED else OriginalLocalSerialCustodyPhaseV1.INTENT,
        intent?.originalActionId)

    fun requireSettledSource(state: NativeCompanyBusinessPublicationStateV1?) {
        require(intent == null && requireNotNull(settled).covers(NativeCompanySerialLedgerCutV1.observe(state))) {
            "Original serial custody is unavailable or its companion cut changed"
        }
        requireAuthorityRecord(requireNotNull(state))
    }

    fun requireAuthorityRecord(state: NativeCompanyBusinessPublicationStateV1) {
        val record = state.records.singleOrNull { it.originalActionId == originalAuthorityActionId }
        require(record != null && record.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 &&
            record.publicationId == authority.publicationId && record.entityId == authority.authorityId &&
            record.keyId == authoritySigningKeyId && record.proposal().readSerialAuthorityDefinition() == authority)
    }

    fun beginNext(previous: NativeCompanyBusinessPublicationStateV1?, next: NativeCompanyBusinessPublicationStateV1,
        changed: NativeCompanyBusinessPublicationRecordV1): NativeCompanySerialAuthorityCustodyV1 {
        requireSettledSource(previous)
        requireAuthorityRecord(next)
        require(changed.kind.isOriginalSerialKindV1())
        capacityRevision(revision)
        return copy(revision = revision + 1L,
            intent = NativeCompanySerialLedgerIntentV1.observe(requireNotNull(settled), next, changed))
    }

    /** Only an actually retained exact NEXT cut can settle; no replay or signing occurs. */
    fun settleActualNext(state: NativeCompanyBusinessPublicationStateV1): NativeCompanySerialAuthorityCustodyV1 {
        val pending = requireNotNull(intent)
        pending.requireActualTarget(state)
        requireAuthorityRecord(state)
        capacityRevision(revision)
        return copy(revision = revision + 1L, settled = pending.next, intent = null)
    }

    companion object {
        fun firstIntent(previous: NativeCompanyBusinessPublicationStateV1?, next: NativeCompanyBusinessPublicationStateV1,
            authorityRecord: NativeCompanyBusinessPublicationRecordV1): NativeCompanySerialAuthorityCustodyV1 {
            require(authorityRecord.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 &&
                authorityRecord.phase == CompanyBusinessPublicationPhase.PREPARED)
            val before = NativeCompanySerialLedgerCutV1.observe(previous)
            val after = NativeCompanySerialLedgerCutV1.observe(next)
            require(before.records == 0 && after.records == 1)
            return NativeCompanySerialAuthorityCustodyV1(0L, authorityRecord.proposal().readSerialAuthorityDefinition(),
                authorityRecord.originalActionId, authorityRecord.keyId, null,
                NativeCompanySerialLedgerIntentV1.observe(before, next, authorityRecord))
        }
    }
}

/** Closed monotone marker transitions; unchanged generic company updates preserve it. */
internal fun requireNativeCompanySerialCustodyTransitionV1(previous: NativeCompanySerialAuthorityCustodyV1?,
    next: NativeCompanySerialAuthorityCustodyV1?) {
    if (previous == null) {
        next?.let { require(it.revision == 0L && it.settled == null && it.intent != null) }
        return
    }
    require(next != null) { "Permanent serial custody cannot be removed" }
    if (next == previous) return
    require(next.revision == previous.revision + 1L && next.authority == previous.authority &&
        next.originalAuthorityActionId == previous.originalAuthorityActionId && next.authoritySigningKeyId == previous.authoritySigningKeyId)
    if (previous.intent == null) {
        require(next.settled == previous.settled && next.intent?.previous == previous.settled)
    } else {
        require(next.intent == null && next.settled == previous.intent.next)
    }
}

/** Separate marker codec; unchanged V1 company state stays byte-identical when absent. */
internal object NativeCompanySerialAuthorityCustodyWireV1 {
    const val MAXIMUM_BYTES: Int = 8 * 1024
    const val COMPANY_STATE_DOMAIN: String = "native-company-registry-serial-custody/v1"

    fun encode(value: NativeCompanySerialAuthorityCustodyV1): ByteArray = selfOwnedEncode("native-company-serial-custody/v1", MAXIMUM_BYTES) {
        long(value.revision)
        val authority = LocalSerialAuthorityPublicationCodecV1.canonicalPublication(value.authority)
        try { field(authority) } finally { authority.fill(0) }
        id(value.originalAuthorityActionId); id(value.authoritySigningKeyId)
        bool(value.settled != null); value.settled?.let { cut(it) }
        bool(value.intent != null); value.intent?.let { pending ->
            id(pending.originalActionId); id(pending.publicationId); id(pending.entityId); id(pending.signingKeyId)
            string(pending.kind.name); string(pending.contentDigest.value); string(pending.phase.name)
            cut(pending.previous); cut(pending.next)
        }
    }

    fun decode(bytes: ByteArray): NativeCompanySerialAuthorityCustodyV1 {
        require(bytes.size in 1..MAXIMUM_BYTES)
        return selfOwnedDecode(bytes, "native-company-serial-custody/v1") {
            val revision = long()
            val authorityBytes = field(CompanySerialAuthorityProfileV1.MAXIMUM_PUBLICATION_BYTES)
            val authority = try { LocalSerialAuthorityPublicationCodecV1.decodeCanonicalPublication(authorityBytes) }
                finally { authorityBytes.fill(0) }
            val action = id(); val key = id()
            val settled = if (bool()) cut() else null
            val intent = if (bool()) {
                val pendingAction = id(); val publication = id(); val entity = id(); val signingKey = id()
                val kindName = string(64)
                val kind = requireNotNull(CompanyBusinessPublicationKind.entries.singleOrNull { it.name == kindName })
                val digest = PayloadDigest(string(64)); val phaseName = string(32)
                val phase = requireNotNull(CompanyBusinessPublicationPhase.entries.singleOrNull { it.name == phaseName })
                NativeCompanySerialLedgerIntentV1(pendingAction, publication, entity, signingKey, kind, digest, phase, cut(), cut())
            } else null
            NativeCompanySerialAuthorityCustodyV1(revision, authority, action, key, settled, intent)
        }
    }

    private fun SelfOwnedWireWriterV1.cut(value: NativeCompanySerialLedgerCutV1) {
        bool(value.ledgerPresent); long(value.generation); int(value.records); int(value.remainingPhaseTransitions); string(value.digest.value)
    }

    private fun SelfOwnedWireReaderV1.cut(): NativeCompanySerialLedgerCutV1 =
        NativeCompanySerialLedgerCutV1(bool(), long(), int(), int(), PayloadDigest(string(64)))
}
