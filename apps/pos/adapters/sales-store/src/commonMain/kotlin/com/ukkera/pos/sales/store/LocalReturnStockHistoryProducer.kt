package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAuthority
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256

/** Separate nonphysical disposition history, never CASE acceptance or refund authority.
 * The genuine native owner closes original sources, effects and resources through the same
 * complete installed stock/commerce producer used by live reads. Only the separate closed
 * stale-disposition Legacy refusal is supported; it never enters the Apply stock producer. */
public class LocalReturnStockHistoryProducer(private val stock: LocalStockRepository) : RuntimeHistoryBusinessProducer {
    override public val commands: Set<CommandKey> = setOf(LocalReturnStockProfileV1.COMMAND)

    override public suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.original is RetainedOriginalV2.Legacy) return joinReturnStockDispositionRejectionHistory(command)
        if (command.command !in commands || command.original !is RetainedOriginalV2.Streamed ||
            command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND || command.originalApproval != null) return false
        val request = LocalReturnStockCodecV1.decode(command.normalized)
        check(LocalReturnStockCodecV1.encode(request) == command.normalized)
        // The private owner comes from the genuine native CASE before-source and its
        // exact already-joined acceptance original, never from the command actor.
        val caseKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, request.caseId)
        val source = command.aggregateSource(caseKey) ?: error("sales.return.stock_history.case_source_missing")
        val row = source.row
        val state = decodeLocalSalesSource(row.payload, LocalReturnCaseStateV1.serializer())
        validateReturnCaseState(state)
        check(row.key == caseKey && row.schema == LocalSalesStoreProfile.schema(caseKey.kind) &&
            sha256(row.payload.copyBytes()) == row.digest && state.scope.value() == command.scope &&
            state.caseId == request.caseId && state.revision == row.domainRevision() &&
            state.revision == request.expectedCaseRevision &&
            state.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING && state.acceptedReturnId == request.returnId)
        val original = (source.original as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("sales.return.stock_history.case_original_unsupported")
        check(original.committed && original.scope == command.scope && original.command == LocalReturnCaseProfileV1.COMMAND &&
            original.operationId == state.lastOperationId && source.original === command.earlierOriginal(state.lastOperationId))
        val write = original.aggregateWrites.singleOrNull { it.key == caseKey }
            ?: error("sales.return.stock_history.case_write_missing")
        check(write.storageGeneration == row.revision && write.schema == row.schema &&
            write.payload == row.payload && write.payloadDigest == row.digest)
        command.requireRequirement(AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
            command.scope, ActionBinding(request.returnId, request.expectedDispositionRevision, command.semanticDigest,
                command.operationId, "return.stock.post"), owner = OwnerConstraint.OwnerPrivate(state.ownerId)))
        stock.requireHistoricalRelationalProducer(command)
        return true
    }
}
