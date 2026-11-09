package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

internal data class NativeCompanyBusinessPublicationHeadV1(
    val accountRevision: Long, val credentialRevision: Long, val boot: Int, val expiresElapsedMillis: Long,
) {
    init { require(accountRevision >= 0 && credentialRevision >= 0 && boot >= 0 && expiresElapsedMillis >= 0) }
}

internal data class NativeCompanyBusinessPublicationRecordV1(
    val originalActionId: DomainId,
    val company: CompanyScope,
    val kind: CompanyBusinessPublicationKind,
    val publicationId: DomainId,
    val entityId: DomainId,
    val keyId: DomainId,
    val content: SelfOwnedOctetsV1,
    val digest: PayloadDigest,
    val phase: CompanyBusinessPublicationPhase,
    val head: NativeCompanyBusinessPublicationHeadV1? = null,
    val publicKey: SelfOwnedOctetsV1 = SelfOwnedOctetsV1(ByteArray(0)),
    val contentSignature: SelfOwnedOctetsV1 = SelfOwnedOctetsV1(ByteArray(0)),
    val rootEndorsement: SelfOwnedOctetsV1 = SelfOwnedOctetsV1(ByteArray(0)),
) {
    init {
        require(content.size in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES)
        val bytes = content.copyBytes()
        try {
            val proposal = CompanyBusinessPublicationProposal(company, kind, publicationId, entityId, bytes)
            require(proposal.contentDigest == digest)
        } finally { bytes.fill(0) }
        when (phase) {
            CompanyBusinessPublicationPhase.PREPARED -> require(head == null && publicKey.size == 0 && contentSignature.size == 0 && rootEndorsement.size == 0)
            CompanyBusinessPublicationPhase.SIGNING_INTENT -> require(head != null && publicKey.size == 32 && contentSignature.size == 0 && rootEndorsement.size == 0)
            CompanyBusinessPublicationPhase.COMPLETED -> require(head != null && publicKey.size == 32 && contentSignature.size == 64 && rootEndorsement.size == 64)
        }
    }
    fun proposal(): CompanyBusinessPublicationProposal {
        val bytes = content.copyBytes()
        return try { CompanyBusinessPublicationProposal(company, kind, publicationId, entityId, bytes) }
        finally { bytes.fill(0) }
    }
    override fun toString(): String = "NativeCompanyBusinessPublicationRecordV1([REDACTED])"
}

internal data class NativeCompanyBusinessPublicationStateV1(
    val generation: Long,
    val records: List<NativeCompanyBusinessPublicationRecordV1>,
) {
    init {
        require(generation in 0 until Long.MAX_VALUE && records.size in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
        require(records.map { it.originalActionId }.distinct().size == records.size)
        require(records.map { it.publicationId }.distinct().size == records.size)
        require(records.map { it.keyId }.distinct().size == records.size)
        require(records.count { it.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT } <= 1)
        requireNativeSerialRegistrationReservationsV1(records)
    }
    fun next(record: NativeCompanyBusinessPublicationRecordV1): NativeCompanyBusinessPublicationStateV1 {
        if (generation >= Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val previous = records.singleOrNull { it.originalActionId == record.originalActionId }
        return copy(generation = generation + 1,
            records = if (previous == null) records + record else records.map { if (it === previous) record else it })
    }
    override fun toString(): String = "NativeCompanyBusinessPublicationStateV1([REDACTED])"
}

/** Additive native-local format. It never changes founding, installation, catalog or C1 bytes. */
internal object NativeCompanyBusinessPublicationWireV1 {
    fun aad(original: LocalCompanyAuthorityBootstrapV1): ByteArray = selfOwnedEncode("company-business-publication-registry-aad/v1") {
        string(CompanyBusinessPublicationProfileV1.PURPOSE)
        val founding = LocalCompanyAuthorityWireV1.registryAad(original)
        try { field(founding) } finally { founding.fill(0) }
    }

    fun encode(value: NativeCompanyBusinessPublicationStateV1): ByteArray =
        selfOwnedEncode("native-company-business-publications/v1", CompanyBusinessPublicationProfileV1.MAXIMUM_PLAINTEXT_BYTES) {
            long(value.generation); int(value.records.size)
            for (record in value.records) {
                publicationRecord(record)
            }
        }

    /** Same exact record grammar, scoped to the serial subset; no lost-content replay. */
    fun serialCutDigest(value: NativeCompanyBusinessPublicationStateV1?): PayloadDigest {
        val bytes = selfOwnedEncode("native-company-serial-ledger-cut/v1", CompanyBusinessPublicationProfileV1.MAXIMUM_PLAINTEXT_BYTES) {
            val serials = value?.records.orEmpty().filter { it.kind.isOriginalSerialKindV1() }
            int(serials.size)
            serials.forEach { publicationRecord(it) }
        }
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }

    private fun SelfOwnedWireWriterV1.publicationRecord(record: NativeCompanyBusinessPublicationRecordV1) {
        id(record.originalActionId); id(record.company.organizationId); id(record.company.companyId)
        string(record.kind.name); id(record.publicationId); id(record.entityId); id(record.keyId)
        octets(record.content); string(record.digest.value); string(record.phase.name)
        bool(record.head != null); record.head?.let {
            long(it.accountRevision); long(it.credentialRevision); int(it.boot); long(it.expiresElapsedMillis)
        }
        octets(record.publicKey); octets(record.contentSignature); octets(record.rootEndorsement)
    }

    fun decode(bytes: ByteArray): NativeCompanyBusinessPublicationStateV1 =
        selfOwnedDecode(bytes, "native-company-business-publications/v1") {
            val generation = long(); val count = int()
            require(count in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
            NativeCompanyBusinessPublicationStateV1(generation, List(count) {
                val action = id(); val company = CompanyScope(id(), id())
                val kindName = string(64)
                val kind = CompanyBusinessPublicationKind.entries.single { it.name == kindName }
                val publication = id(); val entity = id(); val key = id()
                val content = octets(CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES); val digest = PayloadDigest(string(64))
                val phaseName = string(32)
                val phase = CompanyBusinessPublicationPhase.entries.single { it.name == phaseName }
                val head = if (bool()) NativeCompanyBusinessPublicationHeadV1(long(), long(), int(), long()) else null
                NativeCompanyBusinessPublicationRecordV1(action, company, kind, publication, entity, key, content, digest, phase,
                    head, octets(32), octets(64), octets(64))
            })
        }

    /** Exact identity/content review to which the independent head authentication is bound. */
    fun actionMessage(value: NativeCompanyBusinessPublicationRecordV1, original: LocalCompanyAuthorityBootstrapV1): ByteArray =
        selfOwnedEncode("company-business-publication-head-action/v1") {
            string(CompanyBusinessPublicationProfileV1.PURPOSE)
            originalAndContent(value, original)
        }

    /** P-256 signs THIS endorsement; Ed25519 separately signs the unchanged canonical catalog bytes. */
    fun endorsementMessage(value: NativeCompanyBusinessPublicationRecordV1, original: LocalCompanyAuthorityBootstrapV1): ByteArray =
        selfOwnedEncode("company-business-publication-root-endorsement/v1") {
            require(value.phase != CompanyBusinessPublicationPhase.PREPARED)
            string(CompanyBusinessPublicationProfileV1.PURPOSE)
            string("NATIVE_NEW_LOCAL_COMPANY_ONLY_NOT_CENTRAL_REGISTRATION_OR_IMPORTED_AUTHORITY")
            string("P256-SHA256-ENDORSEMENT"); string("Ed25519-CANONICAL-CONTENT")
            originalAndContent(value, original)
            val head = requireNotNull(value.head)
            long(head.accountRevision); long(head.credentialRevision); int(head.boot); long(head.expiresElapsedMillis)
            octets(value.publicKey)
        }

    private fun SelfOwnedWireWriterV1.originalAndContent(value: NativeCompanyBusinessPublicationRecordV1,
        original: LocalCompanyAuthorityBootstrapV1) {
        id(original.original.rootId); id(original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId)
        id(original.original.actionId); id(original.headAuthorityId); id(original.headEnrollmentId)
        id(value.company.organizationId); id(value.company.companyId)
        id(value.originalActionId); id(value.publicationId); id(value.entityId); id(value.keyId)
        string(value.kind.name); string(value.kind.domain); string(value.digest.value)
    }

    fun validateTransition(previous: NativeCompanyBusinessPublicationStateV1?, next: NativeCompanyBusinessPublicationStateV1) {
        if (previous == null) {
            require(next.generation == 0L && next.records.size == 1 && next.records.single().phase == CompanyBusinessPublicationPhase.PREPARED)
            return
        }
        require(next.generation == previous.generation + 1 && next.records.size in previous.records.size..previous.records.size + 1)
        val appended = next.records.drop(previous.records.size)
        if (appended.isNotEmpty()) {
            require(next.records.take(previous.records.size) == previous.records && appended.single().phase == CompanyBusinessPublicationPhase.PREPARED)
            require(previous.records.none { it.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT })
            return
        }
        val changed = previous.records.zip(next.records).filter { (before, after) -> before != after }
        require(changed.size == 1)
        val (before, after) = changed.single()
        require(before.originalActionId == after.originalActionId && before.company == after.company && before.kind == after.kind &&
            before.publicationId == after.publicationId && before.entityId == after.entityId && before.keyId == after.keyId &&
            before.content == after.content && before.digest == after.digest)
        when (before.phase) {
            CompanyBusinessPublicationPhase.PREPARED -> require(after.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT)
            CompanyBusinessPublicationPhase.SIGNING_INTENT -> require(after.phase == CompanyBusinessPublicationPhase.COMPLETED &&
                after.head == before.head && after.publicKey == before.publicKey)
            CompanyBusinessPublicationPhase.COMPLETED -> error("Completed native publication is immutable")
        }
    }

    /** Reserve all finite completion bytes before retaining any original action/signing intent. */
    fun encodeWithCompletionCapacity(value: NativeCompanyBusinessPublicationStateV1): ByteArray {
        // Unrelated native writes must not consume the generations already needed
        // to complete permanent serial reservations. Old nonserial-only behavior
        // and wire bytes are unchanged.
        val serialTransitions = value.records.filter { it.kind.isOriginalSerialKindV1() }.sumOf { record ->
            when (record.phase) {
                CompanyBusinessPublicationPhase.PREPARED -> 2L
                CompanyBusinessPublicationPhase.SIGNING_INTENT -> 1L
                CompanyBusinessPublicationPhase.COMPLETED -> 0L
            }
        }
        if (value.generation > Long.MAX_VALUE - 1L - serialTransitions) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        }
        val bytes = encode(value)
        val reserve = value.records.count { it.phase != CompanyBusinessPublicationPhase.COMPLETED }.toLong() * 1024L
        if (bytes.size.toLong() + reserve > CompanyBusinessPublicationProfileV1.MAXIMUM_PLAINTEXT_BYTES) {
            bytes.fill(0); selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        }
        return bytes
    }
}
