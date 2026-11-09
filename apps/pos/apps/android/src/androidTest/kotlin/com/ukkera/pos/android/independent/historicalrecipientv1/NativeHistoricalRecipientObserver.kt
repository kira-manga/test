package com.ukkera.pos.android.independent.historicalrecipientv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentState
import com.ukkera.pos.database.RuntimeHistoricalLocalHandoffRecipient
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptEventClassifier
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * Observes only commands supplied by the real native scanner. The host installs the actual
 * registry producer as [delegate]; every command and FOOD closure is forwarded unchanged.
 * A successful observation never replaces the delegate's whole requirement/effects join.
 * No historical command, native origin, employee principal or callback authority is made here.
 *
 * Frozen v1 PARTIAL clauses: HREC-V1-002 known absence/late enrollment; HREC-V1-009 borrowed
 * coroutine/retained command; HREC-V1-010 normal ending/close followed by an independent scan.
 * Supplemental HREC-PREFIX-V1-001/002/003 bind the new initial native lane only. The full
 * OFFER_ENROLLED account-revision oracle remains BLOCKED: existing A has no account-summary
 * ADMINISTER authority. Query output never supplies that missing expected revision. This is
 * NOT F-HREC-BASE or b.original/A7/P4. Full READ_ONLY, precise public refusal error/lifecycle
 * interval and all whole-case/platform outcomes remain unbound or NOT_RUN.
 */
internal class NativeHistoricalRecipientObserver(
    private val delegate: RuntimeHistoryBusinessProducer,
    private val orderId: DomainId,
    private val recipientId: DomainId,
    cuts: List<NativeRecipientRecordedCut>,
    private val newlyEnrolledAt: OperationId? = null,
    private val newlyEnrolledDefinition: LocalAccountProvisioning? = null,
    private val oldCallbacks: List<RuntimeHistoricalCommand> = emptyList(),
) : RuntimeHistoryBusinessProducer {
    private val cutsByOperation = cuts.associateBy { it.request.operationId }
    private val observed = linkedSetOf<OperationId>()
    private val retained = linkedMapOf<OperationId, RuntimeHistoricalCommand>()

    init {
        require(cutsByOperation.size == cuts.size && cuts.isNotEmpty())
        require((newlyEnrolledAt == null) == (newlyEnrolledDefinition == null))
        require(newlyEnrolledAt == null || newlyEnrolledAt in cutsByOperation)
        require(newlyEnrolledDefinition == null || newlyEnrolledDefinition.actorId == recipientId)
    }

    override val commands: Set<CommandKey> get() = delegate.commands
    override val retailCustomerReceiptEventClassifier: RuntimeRetailCustomerReceiptEventClassifier?
        get() = delegate.retailCustomerReceiptEventClassifier

    override suspend fun joinCurrentFood(state: RuntimeHistoricalFoodCurrentState): Boolean =
        delegate.joinCurrentFood(state)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        val joined = delegate.join(command)
        if (!joined) return false
        val cut = cutsByOperation[command.operationId] ?: return joined
        // BIND-ORIGINAL: compare against the request/session and actual durable reply retained
        // before this reopen, not expectations copied from the command being observed.
        cut.requireSameOriginal(command)

        // HREC-V1-009: even another genuine active callback cannot lend its authority to an
        // old command. The host supplies the retired original Offer for a later Offer callback;
        // earlier CREATE objects are also probed inside genuinely later callbacks.
        for (old in oldCallbacks + retained.values.toList()) {
            if (old !== command) requireQueryRefusal(old, orderId, recipientId)
        }

        val first = command.localHandoffRecipientBefore(orderId, recipientId)
        val repeated = command.localHandoffRecipientBefore(orderId, recipientId)
        if (command.operationId != newlyEnrolledAt) {
            // HREC-PREFIX-V1-001 / prior HREC-V1-002: two primary CREATE observations in
            // each pre-enrollment, post-enrollment/pre-Offer and post-Offer complete scan.
            assertNull("Prefix CREATE: no recipient at the original before-cut", first)
            assertNull("Prefix CREATE: repeat at the same cut remains known absence", repeated)
        } else {
            // HREC-PREFIX-V1-002 bound actor/label/P0 fields only. The exact independently
            // bound initial account-revision clause remains BLOCKED, not replaced by this
            // comparative check, a guessed zero/one/A7, or the query's own returned revision.
            val value = checkNotNull(first) { "Genuine newly-enrolled lane did not yield receiving facts" }
            val repeatedValue = checkNotNull(repeated)
            requireBoundEnrolledFields(value)
            requireBoundEnrolledFields(repeatedValue)
            requireSameInertTuple(value, repeatedValue)
        }

        // HREC-V1-009: a child coroutine borrows the exact command while its real owning
        // callback is still active. Its caught refusal cannot cancel the genuine delegate.
        supervisorScope {
            async { requireQueryRefusal(command, orderId, recipientId) }.await()
        }
        // HREC-PREFIX-V1-003: child refusal cannot retire the still-owning callback. These
        // are separate lifetime-recovery observations, not the six primary CREATE queries.
        val afterChild = command.localHandoffRecipientBefore(orderId, recipientId)
        if (command.operationId != newlyEnrolledAt) {
            assertNull("Owning CREATE remains known absent after child refusal", afterChild)
        } else {
            val recovered = checkNotNull(afterChild)
            requireBoundEnrolledFields(recovered)
            requireSameInertTuple(checkNotNull(first), recovered)
        }
        retained[command.operationId] = command
        observed += command.operationId
        return joined
    }

    fun requireObservedOriginals() {
        assertEquals("Every selected original reached its real delegated callback", cutsByOperation.keys, observed)
    }

    fun retainedCommands(): List<RuntimeHistoricalCommand> = retained.values.toList()

    suspend fun requireRetainedQueriesRefused() {
        check(retained.isNotEmpty()) { "No genuine callback was observed" }
        for (command in retained.values) requireQueryRefusal(command, orderId, recipientId)
    }

    private fun requireSameInertTuple(first: RuntimeHistoricalLocalHandoffRecipient,
        repeated: RuntimeHistoricalLocalHandoffRecipient) {
        // HREC-V1-001 repeat-equality clause only, over the explicitly partial native prefix.
        // No expected account revision is inferred from these returned observations.
        assertEquals("Repeated inert actor", first.actorId, repeated.actorId)
        assertEquals("Repeated inert label", first.label, repeated.label)
        assertEquals("Repeated inert account revision", first.accountRevision, repeated.accountRevision)
        assertEquals("Repeated inert policy revision", first.policyRevision, repeated.policyRevision)
    }

    private fun requireBoundEnrolledFields(value: RuntimeHistoricalLocalHandoffRecipient) {
        val definition = checkNotNull(newlyEnrolledDefinition)
        assertEquals("Prefix actor from native enrollment", definition.actorId, value.actorId)
        assertEquals("Prefix label from native enrollment", definition.login.value, value.label)
        assertEquals("Prefix initial policy, not frozen BASE P4", definition.policy.revision, value.policyRevision)
        // NPAPI-01: the unchanged A fixture lacks the actual public account-summary grant.
        // No current census/history shortcut or self-oracle is used for accountRevision.
    }
}

/** Immutable binding DATA from a genuine earlier dispatch; it grants no history authority. */
internal class NativeRecipientRecordedCut(
    val request: MutationRequest,
    val scope: BranchScope,
    val actorId: DomainId,
    val sessionId: DomainId,
    val policyRevision: Revision,
    val assignmentId: DomainId,
    val reply: MutationReply.Recorded,
) {
    fun requireSameOriginal(command: RuntimeHistoricalCommand) {
        assertEquals("Original operation", request.operationId, command.operationId)
        assertEquals("Original command key", request.command, command.command)
        assertEquals("Original scope", scope, command.scope)
        assertEquals("Original actor", actorId, command.actorId)
        assertEquals("Original employee session", sessionId, command.authenticationId)
        assertEquals("Original policy revision", policyRevision, command.policyRevision)
        assertEquals("Whole original normalized request", request.payload, command.normalized)
        assertEquals("Original semantic identity", semanticDigest(scope, actorId, request.command, request.payload),
            command.semanticDigest)
        val original = (command.original as? RetainedOriginalV2.Streamed)?.evidence
            ?: error("Selected normalized command lacks its actual retained streamed original")
        assertEquals("Original assignment", assignmentId, original.identity.assignmentId)
        assertEquals("Original authority epoch", reply.authorityEpoch, original.identity.authorityEpoch)
        assertEquals("Original journal sequence", reply.sequence, original.identity.sequence)
        assertEquals("Whole original durable result", reply.result, original.result)
        assertEquals("Original recorded time", reply.recordedAt, command.recordedAt)
    }
}

/** HREC-V1-009/010 non-result check. The current public base exception is observable, but no
 * stable public unavailable code/schema is assumed. Null is a test failure, not unavailable;
 * Other IllegalArgumentException causes are not distinguished; nonmatching failures and
 * cancellation propagate. Full HAPI-02/06 error binding remains a gap. */
internal suspend fun requireQueryRefusal(command: RuntimeHistoricalCommand,
    orderId: DomainId, recipientId: DomainId) {
    try {
        command.localHandoffRecipientBefore(orderId, recipientId)
    } catch (_: IllegalArgumentException) {
        return
    }
    throw AssertionError("Retained or borrowed callback returned a projection/null instead of explicit refusal")
}
