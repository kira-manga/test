package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Shared publication state machine. Native storage, keys, clocks and head ceremony are ports;
 * no platform substitutes current catalog, completion membership or approval for original history. */
internal class LocalCompanyBusinessPublicationCore(
    private val custody: NativeBusinessPublicationCustody,
    private val originalHistory: () -> OriginalNativeHistorySnapshot,
) {
    private val actionOwner = Any()
    private var trust: NativeCompanyPublicationTrust? = null
    private var issuerHistoryCut: OriginalNativeHistorySnapshot? = null

    /** Only the native private bridge supplies its already authenticated cut, while holding
     * the original owner's exclusion. It reobserves that full cut before effects and after
     * the operation, before private delivery. Reuse here is limited to immutable P2 issuer
     * identity/coordinates; every custody, current head, key and access check still runs.
     * Nothing survives this synchronous invocation or authorizes a later request. */
    fun <T> withVerifiedIssuerHistory(original: OriginalNativeHistorySnapshot, block: () -> T): T {
        check(issuerHistoryCut == null)
        issuerHistoryCut = original
        return try { block() } finally { issuerHistoryCut = null }
    }

    private fun issuerHistory(): OriginalNativeHistorySnapshot = issuerHistoryCut ?: originalHistory()

    fun readTrust(): NativeCompanyPublicationTrust {
        // A failed refresh must never leave an older completeness projection usable.
        trust?.invalidateCompletedSnapshot()
        val original = original()
        val fingerprint = custody.rootFingerprint()
        val result = trust ?: NativeCompanyPublicationTrust(
            CompanyScope(original.original.organizationId, original.original.companyId), fingerprint).also { trust = it }
        if (result.rootFingerprint != fingerprint) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val state = custody.readBusinessPublications()
        requireOriginalSerialSource(state)
        result.retainVerified(state?.records.orEmpty().filter { it.phase == CompanyBusinessPublicationPhase.COMPLETED }.map {
            VerifiedNativeCompanyPublication(it.keyId, it.kind, it.digest, it.publicKey)
        })
        if (state?.records.orEmpty().none { it.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT }) {
            val completed = state?.records.orEmpty().filter { it.phase == CompanyBusinessPublicationPhase.COMPLETED }
                .map { completedOutput(it, fingerprint) }
            result.retainCompletedSnapshot(NativeCompanyBusinessPublicationSnapshot(
                result.company, fingerprint, state?.generation ?: 0L, completed, nativeLedgerPresent = state != null))
        }
        return result
    }

    /** Same native worker/registry/root, before the actual writer attach and BEGIN. */
    fun captureRoomObservation(frame: SelfOwnedNativeRoomFrame): NativeCompanyBusinessPublicationObservation? {
        val snapshot = readTrust().currentCompletedSnapshot() ?: return null
        try { NativeBusinessPublicationObservationWire.requireBeforeRoomSubmission(frame.original.record) }
        catch (_: IllegalArgumentException) { return null } // Optional source capacity, never known absence.
        return NativeCompanyBusinessPublicationObservation(frame, snapshot)
    }

    /** Select the original signed manifests first, then reauthenticate every immutable
     * output they reference. Current completion membership is never an earlier cut. */
    fun selectOriginalHistory(original: OriginalNativeHistorySnapshot): NativeBusinessPublicationHistorySelection {
        val companyOriginal = this.original()
        val fingerprint = custody.rootFingerprint()
        val company = CompanyScope(companyOriginal.original.organizationId, companyOriginal.original.companyId)
        val state = custody.readBusinessPublications()
        requireOriginalSerialSource(state)
        val proofs = original.record.businessPublicationObservations + listOfNotNull(original.record.pending?.businessPublications)
        val completed = state?.records.orEmpty().filter { it.phase == CompanyBusinessPublicationPhase.COMPLETED }
            .associateBy { it.originalActionId }
        val authenticated = mutableMapOf<DomainId, CompanyBusinessPublicationOutput>()
        val snapshots = proofs.map { proof ->
            val manifest = proof.manifest
            if (proof.scope != original.record.allocation.coordinates.scope ||
                proof.originalAllocationDigest != original.record.allocation.digest ||
                manifest.company != company || manifest.rootFingerprint != fingerprint ||
                (manifest.nativeLedgerPresent && (state == null || state.generation < manifest.generation))) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val outputs = manifest.members.map { member ->
                val record = completed[member.originalActionId]
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                if (record.kind != member.kind || record.publicationId != member.publicationId ||
                    record.entityId != member.entityId || record.keyId != member.signingKeyId ||
                    record.digest != member.contentDigest || record.company != manifest.company) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                // Deduplicate native cryptographic work across manifests, never membership.
                authenticated.getOrPut(record.originalActionId) {
                    custody.verifyBusinessPublication(record)
                    completedOutput(record, fingerprint)
                }
            }
            NativeCompanyBusinessPublicationSnapshot(manifest.company, fingerprint, manifest.generation,
                outputs, manifest.nativeLedgerPresent)
        }
        custody.assertContinuity()
        return NativeBusinessPublicationHistorySelection(actionOwner, original, state, proofs, snapshots)
    }

    fun reobserveOriginalHistory(selection: NativeBusinessPublicationHistorySelection, original: OriginalNativeHistorySnapshot) {
        this.original()
        if (selection.nativeOwner !== actionOwner || selection.original !== original ||
            custody.readBusinessPublications() !== selection.ledgerIdentity) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        selection.requireActualTraversal()
        custody.assertContinuity()
    }

    private fun completedOutput(record: NativeCompanyBusinessPublicationRecordV1, fingerprint: String): CompanyBusinessPublicationOutput {
        check(record.phase == CompanyBusinessPublicationPhase.COMPLETED)
        val signature = record.contentSignature.copyBytes()
        return try { CompanyBusinessPublicationOutput(record.originalActionId, record.proposal(), record.keyId,
            selfOwnedHex(signature), fingerprint) }
        finally { signature.fill(0) }
    }

    fun retained(): List<CompanyBusinessPublicationReference> {
        readTrust()
        return custody.readBusinessPublications()?.records.orEmpty().map {
            CompanyBusinessPublicationReference(it.originalActionId, it.publicationId, it.entityId, it.phase, it.kind)
        }
    }

    /** No-effect planning from the actual issuer. Protected origin commits the exact IDs/content. */
    fun planOriginalSerialAuthority(): CompanyBusinessPublicationProposal {
        requireNewIssuance()
        val company = state()
        val origin = readActualSerialIssuerOrigin()
        val ledger = custody.readBusinessPublications()
        if (!company.head.enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        if (company.serialCustody != null || ledger?.records.orEmpty().any { it.kind.isOriginalSerialKindV1() })
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val reserved = serialAuthorityReservedIdentityIds(ledger, null)
        val authority = custody.newId(); val publication = custody.newId()
        if (authority == publication || authority in reserved || publication in reserved) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val definition = LocalPublishedSerialAuthorityV1(authority, publication, origin)
        return makeNativeSerialAuthorityProposal(definition)
    }

    /** BEFORE retaining a requester origin: an old unowned native request is not adoptable. */
    fun requireFreshPrivateSerialOrigin(proposal: CompanyBusinessPublicationProposal) {
        requireNewIssuance()
        val ledger = custody.readBusinessPublications()
        if (!state().head.enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        if (ledger?.records.orEmpty().any { it.publicationId == proposal.publicationId })
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        when (proposal.kind) {
            CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 -> {
                val authority = requireOriginalSerialAuthority(proposal)
                if (state().serialCustody != null || ledger?.records.orEmpty().any { it.kind.isOriginalSerialKindV1() })
                    selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
                requireSerialAuthorityReservedIdentities(authority, ledger, null)
            }
            CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> requireOriginalSerialRegistration(proposal)
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
    }

    /** Only the dedicated requester-custody lane invokes this exact authenticated proposal. */
    fun preparePrivateSerialPublication(proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationAction = when (proposal.kind) {
        CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 -> preparePrivateSerialAuthority(proposal)
        CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> prepareNative(proposal, null)
        else -> selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
    }

    private fun preparePrivateSerialAuthority(proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationAction {
        requireNewIssuance()
        val origin = readActualSerialIssuerOrigin()
        val authority = requireOriginalSerialAuthority(proposal)
        val company = state(); val ledger = custody.readBusinessPublications()
        company.serialCustody?.let { custody ->
            if (custody.authority != authority || custody.authority.origin != origin || custody.intent != null)
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val retained = ledger?.records?.singleOrNull { it.originalActionId == custody.originalAuthorityActionId }
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            requireSameProposal(retained, proposal)
            requireSerialAuthorityReservedIdentities(authority, ledger, retained.originalActionId)
            if (retained.phase != CompanyBusinessPublicationPhase.PREPARED &&
                retained.phase != CompanyBusinessPublicationPhase.COMPLETED)
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            // Exact rejoin must precede prepareNative's fresh-authority custody-null check.
            return retainedReview(retained)
        }
        if (ledger?.records.orEmpty().any { it.kind.isOriginalSerialKindV1() })
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireSerialAuthorityReservedIdentities(authority, ledger, null)
        return prepareNative(proposal, origin)
    }

    fun reviewPrivateSerialPublication(id: DomainId, proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationAction {
        requirePrivateSerialPublication(id, proposal)
        return review(id)
    }

    fun recoverPrivateSerialPublication(id: DomainId, proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationOutput {
        requirePrivateSerialPublication(id, proposal)
        return recover(id)
    }

    /** Lookup only: authenticated complete custody/ledger, never a prepare or signing fallback. */
    fun statusPrivateSerialPublication(proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationReference? {
        original()
        readActualSerialIssuerOrigin()
        val ledger = custody.readBusinessPublications()
        requireOriginalSerialSource(ledger)
        val record = ledger?.records?.singleOrNull { it.publicationId == proposal.publicationId }
        record?.let { requireSameProposal(it, proposal) }
        when (proposal.kind) {
            CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 -> {
                val authority = requireOriginalSerialAuthority(proposal)
                val custody = state().serialCustody
                if (record == null) {
                    if (custody != null || ledger?.records.orEmpty().any { it.kind.isOriginalSerialKindV1() })
                        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                } else if (custody == null || custody.originalAuthorityActionId != record.originalActionId) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                requireSerialAuthorityReservedIdentities(authority, ledger, record?.originalActionId)
            }
            CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> requireOriginalSerialRegistration(proposal)
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
        custody.assertContinuity()
        return record?.let { CompanyBusinessPublicationReference(it.originalActionId, it.publicationId,
            it.entityId, it.phase, it.kind) }
    }

    /** Authenticate the actual immutable member before effects AND again before private delivery. */
    fun requirePrivateSerialPublication(id: DomainId, proposal: CompanyBusinessPublicationProposal) {
        original()
        val ledger = custody.readBusinessPublications()
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireOriginalSerialSource(ledger)
        val record = ledger.records.singleOrNull { it.originalActionId == id }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireSameProposal(record, proposal)
        when (record.kind) {
            CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 -> {
                val authority = requireOriginalSerialAuthority(proposal)
                requireSerialAuthorityReservedIdentities(authority, ledger, record.originalActionId)
            }
            CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> requireOriginalSerialRegistration(proposal)
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
    }

    /** The broad business facade never rejoins a private serial action by a supplied ID. */
    fun requireNonSerialFacadeAction(id: DomainId) {
        original()
        val record = custody.readBusinessPublications()?.records?.singleOrNull { it.originalActionId == id }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.kind.isOriginalSerialKindV1()) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    }

    fun readOriginalSerialAuthority(): NativeOriginalLocalSerialAuthority {
        val ledger = custody.readBusinessPublications() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        requireOriginalSerialSource(ledger)
        val custody = state().serialCustody ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val record = ledger.records.singleOrNull { it.originalActionId == custody.originalAuthorityActionId }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (record.phase != CompanyBusinessPublicationPhase.COMPLETED ||
            ledger.records.any { it.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT })
            selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        return NativeOriginalLocalSerialAuthority(custody.authority, record.originalActionId, record.keyId,
            LocalSerialAuthorityPublicationCodecV1.reference(custody.authority), ledger.generation)
    }

    fun readOriginalSerialCustody(): OriginalLocalSerialCustodyReferenceV1 {
        val custody = state().serialCustody ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        if (custody.authority.origin != readActualSerialIssuerOrigin()) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return this.custody.readOriginalSerialCustody()
    }

    fun recoverOriginalSerialCustody(originalActionId: DomainId): OriginalLocalSerialCustodyReferenceV1 {
        trust?.invalidateCompletedSnapshot()
        readOriginalSerialCustody()
        // Settling native custody is not business-trust qualification. In particular
        // an exact retained SIGNING_INTENT stays unavailable for business use; this
        // lookup path never signs or regenerates its missing output.
        return custody.recoverOriginalSerialCustody(originalActionId)
    }

    fun originalSerialRegistrationAllocation(): NativeLocalSerialRegistrationAllocation {
        val authority = readOriginalSerialAuthority()
        val origin = authority.definition.origin
        return NativeLocalSerialRegistrationAllocation(origin.originalScope, origin.originalAllocationId,
            origin.originalAllocationDigest, authority)
    }

    fun prepare(proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationAction = prepareNative(proposal, null)

    private fun prepareNative(proposal: CompanyBusinessPublicationProposal,
        nativeAuthorityOrigin: LocalCompanySerialIssuerOriginV1?): CompanyBusinessPublicationAction {
        requireNewIssuance()
        val original = original()
        val company = CompanyScope(original.original.organizationId, original.original.companyId)
        if (proposal.company != company) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val head = state().head
        if (!head.enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        val ledger = custody.readBusinessPublications()
        val serial = if (proposal.kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1)
            requireOriginalSerialRegistration(proposal) else null
        val authority = if (proposal.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1) {
            if (nativeAuthorityOrigin == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            val value = requireOriginalSerialAuthority(proposal)
            if (value.origin != nativeAuthorityOrigin || state().serialCustody != null ||
                ledger?.records.orEmpty().any { it.kind.isOriginalSerialKindV1() }) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            value
        } else null
        if (ledger?.records?.any { it.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT } == true) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val previous = ledger?.records?.singleOrNull { it.publicationId == proposal.publicationId }
        val record = if (previous != null) {
            requireSameProposal(previous, proposal)
            previous
        } else {
            if (ledger?.records?.size == CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            }
            // BEGIN pairing-owned identity preflight V1 (legacy references unchanged).
            val pairingReserved = custody.devicePairingReservedIdentityIds()
            val employeeReserved = if (serial == null && authority == null) emptySet() else
                custody.readEmployeeEnrollments()?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
            val serialIds = serial?.let { listOf(it.serialId, it.registrationId, it.publicationId) }.orEmpty() +
                authority?.let { listOf(it.authorityId, it.publicationId) }.orEmpty()
            val allocationIds = if (serial == null && authority == null) emptyList() else originalAllocationIdentityIds()
            if (proposal.publicationId in pairingReserved || proposal.entityId in pairingReserved ||
                serialIds.any { it in pairingReserved || it in employeeReserved || it in original.allIds() || it in allocationIds })
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            // END pairing-owned identity preflight V1.
            val reserved = original.allIds().toSet() + ledger?.records.orEmpty().flatMap {
                it.reservedIdentityIdsV1()
            } + listOf(proposal.publicationId, proposal.entityId) + serialIds + pairingReserved + employeeReserved + allocationIds
            val actionId = custody.newId(); val keyId = custody.newId()
            if (actionId == keyId || actionId in reserved || keyId in reserved) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val content = proposal.copyCanonicalContent()
            val prepared = try { NativeCompanyBusinessPublicationRecordV1(actionId, company, proposal.kind, proposal.publicationId,
                proposal.entityId, keyId, SelfOwnedOctetsV1(content), proposal.contentDigest, CompanyBusinessPublicationPhase.PREPARED) }
            finally { content.fill(0) }
            val next = try { ledger?.next(prepared) ?: NativeCompanyBusinessPublicationStateV1(0, listOf(prepared)) }
            catch (failure: IllegalArgumentException) {
                if (serial == null && authority == null) throw failure
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
            requireNewIssuance()
            custody.retainBusinessPublications(next).records.single { it.originalActionId == actionId }
        }
        val current = state().head
        if (!current.enabled || current.accountRevision != head.accountRevision || current.credentialRevision != head.credentialRevision) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        }
        return retainedReview(record)
    }

    fun review(id: DomainId): CompanyBusinessPublicationAction {
        val ledger = custody.readBusinessPublications()
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val record = ledger.records.singleOrNull { it.originalActionId == id }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.phase != CompanyBusinessPublicationPhase.PREPARED ||
            ledger.records.any { it.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT }) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return retainedReview(record)
    }

    private fun retainedReview(record: NativeCompanyBusinessPublicationRecordV1): CompanyBusinessPublicationAction {
        requireNewIssuance()
        val original = original()
        if (record.kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1)
            requireOriginalSerialRegistration(record.proposal())
        if (record.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1)
            requireOriginalSerialAuthority(record.proposal())
        val current = state().head
        if (!current.enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        val now = custody.clock()
        if (now > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return CompanyBusinessPublicationAction(actionOwner, record.originalActionId, record.proposal(), record.keyId,
            custody.rootFingerprint(), original.original.facts.companyName, current.name, current.login,
            current.accountRevision, current.credentialRevision, custody.boot, now + SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS)
    }

    fun approve(action: CompanyBusinessPublicationAction, login: String, secret: CharArray): CompanyBusinessPublicationOutput {
        var ledger = custody.readBusinessPublications() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        var record = ledger.records.singleOrNull { it.originalActionId == action.originalActionId }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireAction(action, record, current = false)
        val completed = record.phase == CompanyBusinessPublicationPhase.COMPLETED
        if (completed && !record.kind.isOriginalSerialKindV1()) return recover(record.originalActionId)
        if ((!completed && record.phase != CompanyBusinessPublicationPhase.PREPARED) ||
            ledger.records.any { it.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT }) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        requireNewIssuance(); requireAction(action, record, current = true)
        val actionMessage = NativeCompanyBusinessPublicationWireV1.actionMessage(record, original())
        val actionDigest = try { sha256(actionMessage) } finally { actionMessage.fill(0) }
        val approval = publicationPhase("head-authentication") {
            custody.authenticatePublication(record.originalActionId, actionDigest,
                login, secret, action.expiresElapsedMillis)
        }
        custody.requirePublicationStillCurrent(approval, record.originalActionId, actionDigest)
        requireAction(action, record, current = true); requireNewIssuance()
        if (completed) {
            // A cached serial action is not private recovery authority. Authenticate the
            // actual named head even for an unchanged completed result; never sign again.
            val result = recover(record.originalActionId)
            return try {
                custody.requirePublicationStillCurrent(approval, record.originalActionId, actionDigest)
                requireAction(action, record, current = true); requireNewIssuance()
                result
            } catch (failure: Throwable) { result.discardUndeliveredSerialContent(); throw failure }
        }
        // This seed is generated once in this invocation and never persisted/exported. A
        // lost signing intent cannot generate another key or repeat a signature on reopen.
        var point = ByteArray(0)
        var signature = ByteArray(0)
        var endorsement: ByteArray? = null
        var content = ByteArray(0)
        val contentKey = custody.newContentKey()
        try {
            content = record.content.copyBytes()
            point = publicationPhase("key-generation") { contentKey.publicPoint() }
            custody.requirePublicationStillCurrent(approval, record.originalActionId, actionDigest)
            requireNewIssuance()
            record = record.copy(phase = CompanyBusinessPublicationPhase.SIGNING_INTENT,
                head = NativeCompanyBusinessPublicationHeadV1(approval.accountRevision, approval.credentialRevision, approval.boot, approval.expires),
                publicKey = SelfOwnedOctetsV1(point))
            ledger = custody.retainBusinessPublications(ledger.next(record))
            if (ledger.records.single { it.originalActionId == record.originalActionId } != record) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            custody.requirePublicationStillCurrent(approval, record.originalActionId, actionDigest)
            requireNewIssuance()
            signature = publicationPhase("content-signing") { contentKey.sign(content) }
            if (!publicationPhase("content-verification") { nativeBusinessPublicationVerifyEd25519(point, content, signature) })
                selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            endorsement = publicationPhase("root-endorsement") { custody.signBusinessPublicationEndorsement(record, approval) }
            publicationPhase("completion-currentness") {
                custody.requirePublicationStillCurrent(approval, record.originalActionId, actionDigest)
                requireNewIssuance()
            }
            val completed = publicationPhase("completion-record") {
                record.copy(phase = CompanyBusinessPublicationPhase.COMPLETED,
                    contentSignature = SelfOwnedOctetsV1(signature), rootEndorsement = SelfOwnedOctetsV1(requireNotNull(endorsement)))
            }
            publicationPhase("completion-retention") { custody.retainBusinessPublications(ledger.next(completed)) }
            val result = publicationPhase("completion-recovery") { recover(completed.originalActionId) }
            return try {
                publicationPhase("completion-delivery-currentness") {
                    custody.requirePublicationStillCurrent(approval, record.originalActionId, actionDigest)
                    requireNewIssuance()
                }
                result
            } catch (failure: Throwable) {
                if (result.proposal.kind.isOriginalSerialKindV1()) result.discardUndeliveredSerialContent()
                throw failure
            }
        } finally { contentKey.clear(); point.fill(0); signature.fill(0); endorsement?.fill(0); content.fill(0) }
    }

    /** Fixed phase names and elapsed durations only; no keys, credentials, content or IDs.
     * Observation neither changes the owned work deadline nor grants currentness. */
    private inline fun <T> publicationPhase(phase: String, action: () -> T): T {
        val started = custody.elapsedMillis()
        try { custody.info("Company publication phase: $phase-entered") }
        catch (_: Throwable) { /* Diagnostic failure cannot change the native outcome. */ }
        val result = action()
        try { custody.info("Company publication phase: $phase-returned; durationMillis=${custody.elapsedMillis() - started}") }
        catch (_: Throwable) { /* Diagnostic failure cannot change the native outcome. */ }
        return result
    }

    fun recover(id: DomainId): CompanyBusinessPublicationOutput {
        val record = custody.readBusinessPublications()?.records?.singleOrNull { it.originalActionId == id }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.phase != CompanyBusinessPublicationPhase.COMPLETED) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val signature = record.contentSignature.copyBytes()
        return try { CompanyBusinessPublicationOutput(id, record.proposal(), record.keyId, selfOwnedHex(signature), readTrust().rootFingerprint) }
        finally { signature.fill(0) }
    }

    private fun requireAction(action: CompanyBusinessPublicationAction, record: NativeCompanyBusinessPublicationRecordV1, current: Boolean) {
        if (action.owner !== actionOwner || action.originalActionId != record.originalActionId || action.signingKeyId != record.keyId ||
            action.rootFingerprint != custody.rootFingerprint()) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireSameProposal(record, action.proposal)
        if (record.kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1)
            requireOriginalSerialRegistration(record.proposal())
        if (record.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1)
            requireOriginalSerialAuthority(record.proposal())
        if (current) {
            val head = state().head
            if (!head.enabled || action.accountRevision != head.accountRevision || action.credentialRevision != head.credentialRevision ||
                action.boot != custody.boot || custody.clock() >= action.expiresElapsedMillis) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
        }
    }
    private fun requireSameProposal(record: NativeCompanyBusinessPublicationRecordV1, proposal: CompanyBusinessPublicationProposal) {
        val content = proposal.copyCanonicalContent()
        try {
            if (record.company != proposal.company || record.kind != proposal.kind || record.publicationId != proposal.publicationId ||
                record.entityId != proposal.entityId || record.digest != proposal.contentDigest || !record.content.same(content)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        } finally { content.fill(0) }
    }
    private fun requireOriginalSerialRegistration(proposal: CompanyBusinessPublicationProposal): LocalPublishedSerialRegistrationV1 {
        val value = try { proposal.readSerialRegistrationDefinition() }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        val actual = readActualSerialIssuerOrigin()
        val source = custody.readBusinessPublications() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val namespace = source.records.singleOrNull { it.publicationId == value.serialAuthority.publicationId &&
            it.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 && it.phase == CompanyBusinessPublicationPhase.COMPLETED }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val authority = namespace.proposal().readSerialAuthorityDefinition()
        if (authority.origin != actual || value.serialAuthority != LocalSerialAuthorityPublicationCodecV1.reference(authority) ||
            value.scope != actual.originalScope || value.allocationId != actual.originalAllocationId ||
            value.allocationDigest != actual.originalAllocationDigest)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        return value
    }

    private fun requireOriginalSerialAuthority(proposal: CompanyBusinessPublicationProposal): LocalPublishedSerialAuthorityV1 {
        val value = try { proposal.readSerialAuthorityDefinition() }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        if (value.origin != readActualSerialIssuerOrigin()) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        state().serialCustody?.let { if (it.authority != value) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        return value
    }

    private fun requireOriginalSerialSource(source: NativeCompanyBusinessPublicationStateV1?) {
        if (source?.records.orEmpty().none { it.kind.isOriginalSerialKindV1() }) return
        val actual = readActualSerialIssuerOrigin()
        val custody = state().serialCustody ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (custody.authority.origin != actual) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val reference = LocalSerialAuthorityPublicationCodecV1.reference(custody.authority)
        source?.records.orEmpty().filter { it.kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 }.forEach { record ->
            val value = record.proposal().readSerialRegistrationDefinition()
            if (value.serialAuthority != reference || value.scope != actual.originalScope ||
                value.allocationId != actual.originalAllocationId || value.allocationDigest != actual.originalAllocationDigest)
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    /** Actual original root/issuer/grant plus the target owner's complete P2 binding. */
    private fun readActualSerialIssuerOrigin(): LocalCompanySerialIssuerOriginV1 =
        custody.actualSerialIssuerOrigin(issuerHistory())

    private fun serialAuthorityReservedIdentityIds(ledger: NativeCompanyBusinessPublicationStateV1?,
        retainedActionId: DomainId?): Set<DomainId> = original().allIds().toSet() +
        ledger?.records.orEmpty().filterNot { it.originalActionId == retainedActionId }.flatMap { it.reservedIdentityIdsV1() } +
        originalAllocationIdentityIds() + custody.devicePairingReservedIdentityIds() +
        custody.readEmployeeEnrollments()?.records.orEmpty().flatMap { it.nativeIds() }

    private fun requireSerialAuthorityReservedIdentities(authority: LocalPublishedSerialAuthorityV1,
        ledger: NativeCompanyBusinessPublicationStateV1?, retainedActionId: DomainId?) {
        val reserved = serialAuthorityReservedIdentityIds(ledger, retainedActionId)
        if (authority.authorityId in reserved || authority.publicationId in reserved)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    private fun originalAllocationIdentityIds(): List<DomainId> = issuerHistory().record.allocation.coordinates.let {
        listOf(it.allocationId, it.scope.organizationId, it.scope.companyId, it.scope.branchId, it.installationId,
            it.assignmentId, it.deviceId, it.storageInstanceId, it.storageKeyId, it.pepperKeyId,
            it.targetSigningKeyId, it.firstActorId)
    }
    private fun state(): NativeBusinessPublicationCompany = custody.businessState()
    private fun original(): SelfOwnedCompanyBootstrapView {
        custody.requireOriginalObservation()
        return custody.companyOriginal() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
    }
    private fun requireNewIssuance() { original(); custody.requireNewIssuance() }
}
