package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Exact employee-to-native private serial bridge. Qualified endpoint work authenticates the
 * real bearer/peer BEFORE entry. One original owner's exclusion spans settled protected
 * request capture and subsequent no-Room native work; no caller loan or cached grant enters.
 */
internal class RoomSelfOwnedSerialPublications(
    private val owner: RoomRuntimeHistoryOwner,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val custody: SelfOwnedNativeHistoryCustody,
) {
    init {
        check(store.database === owner.database && source.belongsTo(store) && custody === owner.originalCustody)
    }

    /** Existing authentic authority origin is reread before any no-effect native planning. */
    suspend fun prepareAuthority(principal: LocalEmployeePrincipal, accessRequestId: DomainId):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> = result {
        requirePrincipal(principal)
        owner.serialPublicationLoan { loan ->
            val original = loan.original
            val originId = NativeSerialPrivatePublicationRequestCodecV1.originRequestId(principal.scope,
                original.record.allocation.digest, CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1, null)
            val retained = writer(loan, principal) { frame -> frame.readOrigin(originId) }
            val proposal = if (retained != null) retained.requestValue().originalProposal()
            else when (val planned = custody.planOriginalSerialAuthority()) {
                is LocalCompanyAuthorityResult.Unavailable -> return@serialPublicationLoan planned
                is LocalCompanyAuthorityResult.Value -> planned.value
            }
            val prepared = prepareRequest(loan, principal, accessRequestId, proposal)
            val value = custody.prepareOriginalPrivateSerialPublication(loan.original,
                prepared.access.capsule(), prepared.origin.capsule())
            requireAction(value, prepared.origin.requestValue(), null)
            value
        }
    }

    suspend fun prepare(principal: LocalEmployeePrincipal, accessRequestId: DomainId,
        proposal: CompanyBusinessPublicationProposal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> = result {
        requirePrincipal(principal)
        owner.serialPublicationLoan { loan ->
            val prepared = prepareRequest(loan, principal, accessRequestId, proposal)
            val value = custody.prepareOriginalPrivateSerialPublication(loan.original,
                prepared.access.capsule(), prepared.origin.capsule())
            requireAction(value, prepared.origin.requestValue(), null)
            value
        }
    }

    /** Fresh original-account recovery is separate from permission to prepare or activate. */
    suspend fun review(principal: LocalEmployeePrincipal, accessRequestId: DomainId,
        originRequestId: DomainId, originalActionId: DomainId):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> = result {
        requirePrincipal(principal)
        owner.serialPublicationLoan { loan ->
            val prepared = accessRequest(loan, principal, NativeSerialPrivatePublicationOperationV1.REVIEW,
                accessRequestId, originRequestId, originalActionId)
            val value = custody.reviewOriginalPrivateSerialPublication(loan.original,
                prepared.access.capsule(), prepared.origin.capsule())
            requireAction(value, prepared.origin.requestValue(), originalActionId)
            value
        }
    }

    suspend fun recover(principal: LocalEmployeePrincipal, accessRequestId: DomainId,
        originRequestId: DomainId, originalActionId: DomainId):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> = result {
        requirePrincipal(principal)
        owner.serialPublicationLoan { loan ->
            val prepared = accessRequest(loan, principal, NativeSerialPrivatePublicationOperationV1.RECOVER,
                accessRequestId, originRequestId, originalActionId)
            val value = custody.recoverOriginalPrivateSerialPublication(loan.original,
                prepared.access.capsule(), prepared.origin.capsule())
            if (value is LocalCompanyAuthorityResult.Value) {
                try {
                    check(value.value.originalActionId == originalActionId)
                    requireProposal(value.value.proposal, prepared.origin.requestValue())
                } catch (failure: Throwable) {
                    value.value.discardUndeliveredSerialContent()
                    throw failure
                }
            }
            value
        }
    }

    /** Discovery is exact private-origin status, never the generic native retained census. */
    suspend fun retained(principal: LocalEmployeePrincipal):
        LocalCompanyAuthorityResult<List<LocalSerialPrivatePublicationReference>> = result {
        requirePrincipal(principal)
        owner.serialPublicationLoan { loan ->
            val origins = writer(loan, principal) { frame -> frame.readOrigins() }
            val references = mutableListOf<LocalSerialPrivatePublicationReference>()
            for (origin in origins) {
                val prepared = writer(loan, principal) { frame ->
                    val original = frame.readOrigin(origin.requestValue().requestId) ?: frame.refuse()
                    val request = NativeSerialPrivatePublicationRequestCodecV1.access(
                        NativeSerialPrivatePublicationOperationV1.STATUS, frame.accessRequestId,
                        original.requestValue(), null)
                    CapturedRequest(frame.record(request, original), original)
                }
                when (val status = custody.statusOriginalPrivateSerialPublication(loan.original,
                    prepared.access.capsule(), prepared.origin.capsule())) {
                    is LocalCompanyAuthorityResult.Unavailable -> return@serialPublicationLoan status
                    is LocalCompanyAuthorityResult.Value -> status.value?.let { reference ->
                        val original = prepared.origin.requestValue()
                        check(reference.kind == original.kind && reference.publicationId == original.publicationId &&
                            reference.entityId == original.entityId)
                        references += LocalSerialPrivatePublicationReference(original.requestId, reference)
                    }
                }
            }
            check(references.size <= CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS &&
                references.map { it.originRequestId }.distinct().size == references.size &&
                references.map { it.publication.originalActionId }.distinct().size == references.size &&
                references.map { it.publication.publicationId }.distinct().size == references.size)
            // Includes an empty census. Earlier access must not outlive the whole lookup.
            writer(loan, principal) { Unit }
            LocalCompanyAuthorityResult.Value(references.toList())
        }
    }

    private class CapturedRequest(val access: RuntimeSerialPublicationRequestEvidenceV1,
        val origin: RuntimeSerialPublicationRequestEvidenceV1)

    private suspend fun prepareRequest(loan: RoomRuntimeHistoryOwner.SerialPublicationLoan,
        principal: LocalEmployeePrincipal, accessRequestId: DomainId,
        proposal: CompanyBusinessPublicationProposal): CapturedRequest {
        val requested = NativeSerialPrivatePublicationRequestCodecV1.origin(principal.scope,
            loan.original.record.allocation.digest, proposal)
        return writer(loan, principal) { frame ->
            val retained = frame.readOrigin(requested.requestId)
            if (retained == null) {
                val origin = frame.record(requested, null)
                CapturedRequest(origin, origin)
            } else {
                val original = retained.requestValue()
                val expectedBytes = NativeSerialPrivatePublicationRequestCodecV1.encode(requested)
                try {
                    val retainedBytes = NativeSerialPrivatePublicationRequestCodecV1.encode(original)
                    try { if (!retainedBytes.contentEquals(expectedBytes)) frame.refuse() }
                    finally { retainedBytes.fill(0) }
                } finally { expectedBytes.fill(0) }
                val request = NativeSerialPrivatePublicationRequestCodecV1.access(
                    NativeSerialPrivatePublicationOperationV1.PREPARE, accessRequestId, original, null)
                CapturedRequest(frame.record(request, retained), retained)
            }
        }
    }

    private suspend fun accessRequest(loan: RoomRuntimeHistoryOwner.SerialPublicationLoan,
        principal: LocalEmployeePrincipal, operation: NativeSerialPrivatePublicationOperationV1,
        accessRequestId: DomainId, originRequestId: DomainId, originalActionId: DomainId): CapturedRequest =
        writer(loan, principal) { frame ->
            val retained = frame.readOrigin(originRequestId) ?: frame.refuse()
            val request = NativeSerialPrivatePublicationRequestCodecV1.access(operation, accessRequestId,
                retained.requestValue(), originalActionId)
            CapturedRequest(frame.record(request, retained), retained)
        }

    /** Non-values are unwrapped only AFTER actual rollback/outer cleanup/native settlement. */
    private suspend fun <T> writer(loan: RoomRuntimeHistoryOwner.SerialPublicationLoan,
        principal: LocalEmployeePrincipal, block: suspend (RoomSerialPublicationRequestFrame) -> T): T {
        val settled = loan.writer { connection ->
            store.transactionAtWriter(connection) { transaction ->
                block(transaction.openSerialPublicationFrame(principal, source))
            }
        }
        if (settled is RoomLocalSecurityOutcome.Unavailable) throw RoomSerialPublicationUnavailable()
        return settled.valueOrThrow()
    }

    private fun requirePrincipal(principal: LocalEmployeePrincipal) {
        if (principal.scope != owner.originalScope || principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE)
            currentLocalAuthorizationDenied()
    }

    private fun requireAction(value: LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>,
        origin: NativeSerialPrivatePublicationRequestV1, originalActionId: DomainId?) {
        if (value is LocalCompanyAuthorityResult.Value) {
            try {
                check(originalActionId == null || value.value.originalActionId == originalActionId)
                requireProposal(value.value.proposal, origin)
            } catch (failure: Throwable) {
                value.value.discardUndeliveredSerialContent()
                throw failure
            }
        }
    }

    private fun requireProposal(proposal: CompanyBusinessPublicationProposal,
        origin: NativeSerialPrivatePublicationRequestV1) {
        check(proposal.company == origin.scope.company() && proposal.kind == origin.kind &&
            proposal.publicationId == origin.publicationId && proposal.entityId == origin.entityId &&
            proposal.contentDigest == origin.contentDigest)
    }

    private suspend inline fun <T> result(block: () -> LocalCompanyAuthorityResult<T>): LocalCompanyAuthorityResult<T> = try {
        currentCoroutineContext().ensureActive()
        val value = block()
        // Private output is withheld on cancellation; completed effects are never replayed.
        try { currentCoroutineContext().ensureActive() }
        catch (failure: Throwable) {
            if (value is LocalCompanyAuthorityResult.Value) when (val content = value.value) {
                is CompanyBusinessPublicationAction -> content.discardUndeliveredSerialContent()
                is CompanyBusinessPublicationOutput -> content.discardUndeliveredSerialContent()
            }
            throw failure
        }
        value
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: RoomCurrentLocalAuthorizationDenied) { unavailable(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED) }
    catch (_: RoomSerialPublicationUnavailable) { unavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE) }
    catch (_: RoomLocalStorageUncertain) { unavailable(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
    catch (_: IllegalArgumentException) { unavailable(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
    catch (_: Exception) { unavailable(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }

    private fun unavailable(reason: SelfOwnedInstallationFailureV1): LocalCompanyAuthorityResult.Unavailable =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE, reason)

    override fun toString(): String = "RoomSelfOwnedSerialPublications([REDACTED])"
}

private class RoomSerialPublicationUnavailable : IllegalStateException("Private serial request unavailable")

/**
 * Fixed security-transaction finalizer, never a caller-supplied authorization callback.
 * The actual source binding owns its protected rereads and final synchronous observation.
 */
internal class RoomSerialPublicationRequestFrame(
    private val view: TransactionView,
    private val history: RoomRuntimeHistoryFrame,
    private val principal: LocalEmployeePrincipal,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val context: CoroutineContext,
) {
    private var initialized = false
    private var prepared = false
    private var finished = false
    private var closed = false
    private var failed = false
    private var cancellation: CancellationException? = null
    private var authorization: AuthorizationSnapshot? = null
    private var capture: RuntimeSerialPublicationRequestCapture? = null
    private var recorded: RuntimeSerialPublicationRequestEvidenceV1? = null
    private val origins: MutableMap<DomainId, RuntimeSerialPublicationRequestEvidenceV1> = mutableMapOf()
    private lateinit var transaction: RoomLocalSecurityTransaction

    /** Actual native frame identity, not caller entropy or a reusable previous access ID. */
    val accessRequestId: DomainId get() {
        requireBody()
        return history.native.frameId
    }

    suspend fun initialize(transaction: RoomLocalSecurityTransaction) = operation {
        context.ensureActive()
        check(!initialized && !closed && principal.authenticationPurpose == AuthenticationPurpose.INTERACTIVE)
        transaction.requireSerialPublicationWriteCount(0)
        authorization = source.current(principal, view) ?: currentLocalAuthorizationDenied()
        view.captureRuntimeCommandAuthorization(history, principal)
        this.transaction = transaction
        initialized = true
    }

    suspend fun readOrigin(requestId: DomainId): RuntimeSerialPublicationRequestEvidenceV1? = operation {
        requireBody()
        val retained = transaction.readHistorySerialPublicationRequest(requestId) ?: return@operation null
        retainOrigin(requestId, retained)
    }

    suspend fun readOrigins(): List<RuntimeSerialPublicationRequestEvidenceV1> = operation {
        requireBody()
        transaction.requireSerialPublicationWriteCount(0)
        val retained = transaction.readHistorySerialPublicationOrigins(principal.actorId)
        historyCapacityV2(retained.size <= SelfOwnedNativeHistoryWireV2.MAX_FINAL_DEADLINE_DECISIONS)
        val ids = retained.map { it.requestValue().requestId }
        if (ids.distinct().size != retained.size) refuse()
        retained.mapIndexed { index, evidence -> retainOrigin(ids[index], evidence) }
    }

    private suspend fun retainOrigin(requestId: DomainId,
        retained: RuntimeSerialPublicationRequestEvidenceV1): RuntimeSerialPublicationRequestEvidenceV1 {
        retained.validate()
        val request = retained.requestValue()
        if (request.operation != NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE ||
            request.requestId != requestId || request.scope != principal.scope) refuse()
        if (retained.authorization.actorId != principal.actorId) currentLocalAuthorizationDenied()
        val event = transaction.readAuthEvent(principal.scope, requestId) ?: refuse()
        if (!retained.matchesEvent(event)) refuse()
        val previous = origins[requestId]
        if (previous != null && !sameEvidence(previous, retained)) refuse()
        origins[requestId] = retained
        return retained
    }

    suspend fun record(request: NativeSerialPrivatePublicationRequestV1,
        origin: RuntimeSerialPublicationRequestEvidenceV1?): RuntimeSerialPublicationRequestEvidenceV1 = operation {
        requireBody()
        if (capture != null || recorded != null || request.scope != principal.scope) refuse()
        transaction.requireSerialPublicationWriteCount(0)
        if (transaction.readAuthEvent(principal.scope, request.requestId) != null) refuse()
        requireRuntimeSerialPublicationOrigin(request, origin, principal.actorId)
        if (origin != null) {
            val retained = origins[origin.requestValue().requestId] ?: refuse()
            if (!sameEvidence(retained, origin)) refuse()
        }
        val requirement = runtimeSerialPublicationRequirement(request, principal.actorId)
        var permitted: AuthorizedAction? = null
        var recovered: ResultRecoveryDecision.Allowed? = null
        when (request.operation) {
            NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE,
            NativeSerialPrivatePublicationOperationV1.PREPARE -> {
                permitted = (AuthorizationEvaluator.evaluate(principal, authorization ?: refuse(), requirement)
                    as? AuthorizationDecision.Allowed)?.authorization ?: currentLocalAuthorizationDenied()
            }
            NativeSerialPrivatePublicationOperationV1.REVIEW,
            NativeSerialPrivatePublicationOperationV1.RECOVER,
            NativeSerialPrivatePublicationOperationV1.STATUS -> {
                // Full private bytes never enter the supervisor projection route.
                val retained = requireNotNull(origin)
                if (retained.authorization.actorId != principal.actorId) currentLocalAuthorizationDenied()
                val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { selected ->
                    if (selected !== principal) currentLocalAuthorizationDenied()
                    source.current(selected, view)
                })
                recovered = policy.authorize(principal, principal.scope, retained.authorization.actorId,
                    OperationId(requireNotNull(request.originRequestId).value)) as? ResultRecoveryDecision.Allowed
                    ?: currentLocalAuthorizationDenied()
                if (recovered.access != ResultRecoveryAccess.ORIGINAL_ACTOR) currentLocalAuthorizationDenied()
            }
        }
        val snapshot = view.captureRuntimeCommandAuthorization(history, principal)
        val event = runtimeSerialPublicationEvent(request, snapshot, snapshot.access.observedAt)
        val owned = RuntimeSerialPublicationRequestCapture.capture(principal, request, snapshot, event,
            origin, permitted, recovered)
        capture = owned
        transaction.beginHistorySerialPublication(owned)
        transaction.insertAuthEvent(event)
        transaction.completeHistorySerialPublication(owned)
        val evidence = owned.evidence()
        recorded = evidence
        evidence
    }

    suspend fun prepareNormalReturn(transaction: RoomLocalSecurityTransaction) = operation {
        requireBody()
        check(this.transaction === transaction)
        origins.forEach { (id, original) ->
            val current = transaction.readHistorySerialPublicationRequest(id) ?: refuse()
            val event = transaction.readAuthEvent(principal.scope, id) ?: refuse()
            if (!sameEvidence(original, current) || !current.matchesEvent(event)) refuse()
        }
        recorded?.let { expected ->
            val event = transaction.readAuthEvent(principal.scope, expected.requestValue().requestId) ?: refuse()
            if (!expected.matchesEvent(event) || !sameEvidence(expected, checkNotNull(capture).evidence())) refuse()
        }
        transaction.requireSerialPublicationWriteCount(if (recorded == null) 0 else 1)
        transaction.ensureAuthorizationHealthy()
        view.prepareNormalReturn()
        prepared = true
    }

    /** No Room/provider IO or suspension follows the source's final synchronous sample. */
    fun finishNow() {
        try {
            context.ensureActive()
            cancellation?.let { throw it }
            check(initialized && prepared && !finished && !closed && !failed)
            view.finishNow()
            finished = true
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
            failed = true
            throw cancelled
        } catch (failure: Exception) { failed = true; throw failure }
    }

    suspend fun close() {
        closed = true
        origins.clear()
        capture = null
        recorded = null
        authorization = null
        view.close()
    }

    fun refuse(): Nothing {
        failed = true
        transaction.refuse()
    }

    private fun requireBody() {
        context.ensureActive()
        cancellation?.let { throw it }
        check(initialized && !prepared && !finished && !closed)
        if (failed) refuse()
    }

    private fun sameEvidence(left: RuntimeSerialPublicationRequestEvidenceV1,
        right: RuntimeSerialPublicationRequestEvidenceV1): Boolean {
        val first = RuntimeSerialPublicationRequestEvidenceCodec.encode(left)
        return try {
            val second = RuntimeSerialPublicationRequestEvidenceCodec.encode(right)
            try { first.contentEquals(second) } finally { second.fill(0) }
        } finally { first.fill(0) }
    }

    private suspend inline fun <T> operation(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) {
        if (cancellation == null) cancellation = cancelled
        failed = true
        throw cancelled
    } catch (failure: Exception) { failed = true; throw failure }

    override fun toString(): String = "RoomSerialPublicationRequestFrame([REDACTED])"
}
