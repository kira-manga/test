package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Bounded scalar DATA staged on one actual frame; native settlement still owns publication. */
internal class RuntimeRetailCustomerReceiptMembershipDelta(
    val saleId: DomainId,
    val locator: RuntimeRetailCustomerReceiptEventLocator,
) {
    override fun toString(): String = "RuntimeRetailCustomerReceiptMembershipDelta([REDACTED])"
}

/** A normal frame's captured data, not a supplied COMMITTED result or installation proof. */
internal class RuntimeRetailCustomerReceiptMembershipCommit(
    val installation: LocalBranchInstallationReceiptV2?,
    val delta: RuntimeRetailCustomerReceiptMembershipDelta?,
) {
    override fun toString(): String = "RuntimeRetailCustomerReceiptMembershipCommit([REDACTED])"
}

/** The only receipt EVENT writer profile. Rows come from the frame's complete native image
 * crosswalk, never a proposal, supplied journal pair, claim roster or current point query.
 * Retains at most one EVENT and one actual OPERATION/JOURNAL pair, then only scalar DATA. */
internal class RuntimeRetailCustomerReceiptCapture(
    private val original: OriginalNativeHistorySnapshot,
    private val command: RuntimeCommandAuthorizationEvidenceV1,
    private val classifier: RuntimeRetailCustomerReceiptEventClassifier,
) {
    private val coordinates = original.record.allocation.coordinates
    private val parent = checkNotNull(original.record.parent)
    private val scope = coordinates.scope
    private val scopeKey = scope.storageKey()
    private var event: StoredAggregate? = null
    private var operation: OperationRow? = null
    private var journal: JournalRow? = null
    private var completed = false

    init {
        command.validate()
        check(command.purposeTag == RuntimeWritePurposeV2.COMMAND_APPLY.tag &&
            CommandKey(command.commandName, command.commandVersion) == RuntimeRetailCustomerReceiptMembershipProfile.COMMAND &&
            command.nativeRecordDigest == original.recordDigest && command.authorization.scope == scope &&
            command.requirement.action == SecurityAction.CUSTOMER_RECEIPT_RECORD &&
            command.requirement.verb == PermissionVerb.PERFORM &&
            command.requirement.reasonCode == "customer.receipt.record" &&
            original.record.pending == null && original.record.receipt != null &&
            original.record.firstChild != null && original.record.issuerAcknowledgement != null &&
            parent.commandSequence in 0L until Long.MAX_VALUE)
    }

    fun capture(table: RuntimeHistoryTableV2, mutation: Int, before: NativeHistoryPhysicalRowV2?,
        after: NativeHistoryPhysicalRowV2?, hasPrior: Boolean) {
        check(!completed)
        when {
            isEventChange(table, before, after) -> captureEvent(mutation, before, after, hasPrior)
            table == RuntimeHistoryTableV2.OPERATION -> captureOperation(mutation, before, after, hasPrior)
            table == RuntimeHistoryTableV2.JOURNAL -> captureJournal(mutation, before, after, hasPrior)
        }
    }

    private fun freshInsert(mutation: Int, before: NativeHistoryPhysicalRowV2?,
        after: NativeHistoryPhysicalRowV2?, hasPrior: Boolean): RuntimeHistoryRowViewV2 {
        // A tombstoned prior is still prior. Neither resurrection nor UPDATE/DELETE can
        // preserve complete immutable membership, even if the live payload is identical.
        check(mutation == RuntimeHistoryMutationKindV2.INSERT.tag && before == null && after != null && !hasPrior)
        return RuntimeHistoryRowViewV2(checkNotNull(after))
    }

    private fun captureEvent(mutation: Int, before: NativeHistoryPhysicalRowV2?,
        after: NativeHistoryPhysicalRowV2?, hasPrior: Boolean) {
        check(event == null)
        val row = freshInsert(mutation, before, after, hasPrior).aggregate()
        try {
            check(row.scope == scopeKey && row.kind == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND &&
                row.id == command.operationId.value && row.revision == 1L &&
                row.schema == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_SCHEMA &&
                row.payload.size in 1..MAX_STORED_BYTES && sha256(row.payload).value == row.payloadHash)
            event = StoredAggregate(AggregateKey(row.kind, DomainId(row.id)), Revision(row.revision), row.schema,
                StoredBytes(row.payload), PayloadDigest(row.payloadHash))
        } finally { row.payload.fill(0) }
    }

    private fun captureOperation(mutation: Int, before: NativeHistoryPhysicalRowV2?,
        after: NativeHistoryPhysicalRowV2?, hasPrior: Boolean) {
        check(operation == null)
        val row = freshInsert(mutation, before, after, hasPrior).operation()
        var retained = false
        try {
            check(row.scope == scopeKey && row.operationId == command.operationId.value &&
                row.command == command.commandName && row.commandVersion == command.commandVersion &&
                row.actorId == command.authorization.actorId.value && row.digest == command.semanticDigest.value &&
                row.outcome == "committed" && row.authenticationId == command.authorization.sessionId.value &&
                row.policyRevision == command.authorization.policyRevision.value &&
                row.epoch == parent.commandEpoch && row.sequence == parent.commandSequence + 1)
            operation = row; retained = true
        } finally { if (!retained) row.result.fill(0) }
    }

    private fun captureJournal(mutation: Int, before: NativeHistoryPhysicalRowV2?,
        after: NativeHistoryPhysicalRowV2?, hasPrior: Boolean) {
        check(journal == null)
        val row = freshInsert(mutation, before, after, hasPrior).journal()
        var retained = false
        try {
            check(row.scope == scopeKey && row.operationId == command.operationId.value &&
                row.assignmentId == coordinates.assignmentId.value && row.epoch == parent.commandEpoch &&
                row.sequence == parent.commandSequence + 1 && row.previousHash == parent.commandDigest.value)
            journal = row; retained = true
        } finally { if (!retained) row.envelope.fill(0) }
    }

    fun complete(branch: BranchRow): RuntimeRetailCustomerReceiptMembershipDelta {
        check(!completed); completed = true
        val after = checkNotNull(event)
        val retained = checkNotNull(decodeRetainedCommand(scope, command.operationId,
            checkNotNull(operation), checkNotNull(journal)))
        try {
            // decodeRetainedCommand is the closed legacy pair decoder. Streamed originals
            // are unsupported here; native-original provenance is deliberately not employee.
            check(retained.nativeOriginalProvenance == null && retained.committed && retained.scope == scope &&
                retained.operationId == command.operationId &&
                retained.command == RuntimeRetailCustomerReceiptMembershipProfile.COMMAND &&
                retained.originalActorId == command.authorization.actorId &&
                retained.authenticationId == command.authorization.sessionId &&
                retained.policyRevision == command.authorization.policyRevision &&
                retained.semanticDigest == command.semanticDigest && retained.assignmentId == coordinates.assignmentId &&
                retained.authorityEpoch.value == parent.commandEpoch &&
                retained.sequence.value == parent.commandSequence + 1 &&
                retained.previousJournalHash == parent.commandDigest &&
                branch.scope == scopeKey && branch.assignmentId == coordinates.assignmentId.value &&
                branch.deviceId == coordinates.deviceId.value && branch.epoch == retained.authorityEpoch.value &&
                branch.sequence == retained.sequence.value && branch.journalHash == retained.journalHash.value)
            val writes = retained.aggregateWrites.filter { it.key.kind == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND }
            check(writes.size == 1 && writes.single().let {
                it.key == after.key && it.storageGeneration == after.revision && it.schema == after.schema &&
                    it.payloadDigest == after.digest && it.payload == after.payload
            })
            val saleId = classifier.saleId(scope, after, retained)
            check(command.requirement.documentId == saleId)
            return RuntimeRetailCustomerReceiptMembershipDelta(saleId,
                RuntimeRetailCustomerReceiptEventLocator(after.key, after.revision, after.schema, after.digest))
        } finally {
            retained.normalized.bytes().fill(0); retained.result.bytes().fill(0)
            retained.aggregateWrites.forEach { it.payload.bytes().fill(0) }
            retained.jobs.forEach { it.body.bytes().fill(0) }
            clear()
        }
    }

    fun clear() {
        event?.payload?.bytes()?.fill(0); event = null
        operation?.result?.fill(0); operation = null
        journal?.envelope?.fill(0); journal = null
    }

    companion object {
        fun isEventChange(table: RuntimeHistoryTableV2, before: NativeHistoryPhysicalRowV2?,
            after: NativeHistoryPhysicalRowV2?): Boolean = table == RuntimeHistoryTableV2.AGGREGATE &&
            (before?.let { RuntimeHistoryRowViewV2(it).text("kind") == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND } == true ||
                after?.let { RuntimeHistoryRowViewV2(it).text("kind") == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND } == true)
    }
}
