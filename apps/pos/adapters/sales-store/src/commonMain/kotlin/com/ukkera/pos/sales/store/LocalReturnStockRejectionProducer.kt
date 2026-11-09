package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Only the stale disposition frontier is closed here. A recorded reason is never its
 * own proof; every successful live prefix comes from genuine already-joined BEFORE sources.
 * No current inventory, refund state, policy or reconstructed read view is substituted. */
internal suspend fun joinReturnStockDispositionRejectionHistory(command: RuntimeHistoricalCommand): Boolean {
    if (command.command != LocalReturnStockProfileV1.COMMAND ||
        command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND || command.originalApproval != null) return false
    val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
    if (retained.committed || retained.nativeOriginalProvenance != null) return false
    val request = LocalReturnStockCodecV1.decode(command.normalized)
    check(LocalReturnStockCodecV1.encode(request) == command.normalized &&
        semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest &&
        retained.scope == command.scope && retained.command == command.command && retained.operationId == command.operationId &&
        retained.originalActorId == command.actorId && retained.semanticDigest == command.semanticDigest &&
        retained.authenticationId == command.authenticationId && retained.policyRevision == command.policyRevision &&
        retained.normalized == command.normalized && retained.recordedAt == command.recordedAt &&
        retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty())

    val sources = ReturnStockDispositionRejectionSources(command)
    val selected = sources.required(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, request.caseId))
    val state = decodeLocalSalesSource(selected.row.payload, LocalReturnCaseStateV1.serializer())
    validateReturnCaseState(state)
    check(state.scope.value() == command.scope && state.caseId == request.caseId && state.revision == selected.row.domainRevision())
    if (state.stage != LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING || state.acceptedReturnId != request.returnId ||
        state.revision != request.expectedCaseRevision) return false
    val acceptance = sources.caseOriginal(selected, state.lastOperationId)
    val event = sources.event(state.lastOperationId)
    check(event.after == state && event.action == LocalReturnCaseActionV1.ACCEPTED)
    val first = sources.event(state.originalOperationId)
    check(first.action == LocalReturnCaseActionV1.RECORDED && first.after.caseId == state.caseId &&
        first.after.ownerId == state.ownerId && first.after.facts == state.facts && first.after.recordedAt == state.recordedAt)
    val binding = sources.event(returnCaseRequired(state.bindingOperationId))
    check(binding.after.caseId == state.caseId && binding.after.ownerId == state.ownerId && binding.after.facts == state.facts)
    if (returnCaseRequired(binding.binding).saleId != request.saleId) return false

    val sale = sources.sale(request.saleId)
    val accepted = returnStockAcceptedOriginal(command.scope, sale, request.caseId, request.returnId, acceptance).accepted
    check(accepted.acceptingOperatorId == state.ownerId)
    if (sale.stockIssueId != request.issueId) return false
    sources.claim(LocalReturnCaseProfileV1.source("case", request.caseId), state.ownerId)
    sources.claim(LocalReturnCaseProfileV1.source("claim", request.caseId), request.caseId)
    sources.claim(LocalSalesStoreProfile.unique("return_claim", request.caseId), request.saleId)
    sources.claim(LocalSalesStoreProfile.unique("accepted_return", request.returnId), request.saleId)
    sources.claim(LocalSalesStoreProfile.unique("return_claim_source", request.returnId), request.caseId)
    sources.claim(LocalSalesStoreProfile.unique("claim_acceptance", request.caseId), request.returnId)

    // Live validates current state before comparing the acceptance assertion and then CAS.
    // Later selection/custody/issue-revision/safety checks cannot become refusal evidence.
    val before = sources.disposition(request.returnId)
    if (request.acceptedQuantity != accepted.quantity ||
        (before?.revision ?: Revision(0)) == request.expectedDispositionRevision) return false
    command.requireRequirement(AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
        command.scope, ActionBinding(request.returnId, request.expectedDispositionRevision, command.semanticDigest,
            command.operationId, "return.stock.post"), owner = OwnerConstraint.OwnerPrivate(state.ownerId)))
    command.requireReturnStockDispositionRejection(ProposedOutcome.Reject("sales.return.stock.rejected",
        LocalReturnStockResultCodecV1.encode(LocalReturnStockResultV1.Rejected("DISPOSITION_REVISION_CHANGED"))),
        setOf(entitlementResource(request.returnId), entitlementResource(request.saleId), orderResource(request.caseId),
            RecoveryResourceKey(RecoveryResourceKind.STOCK, request.issueId),
            RecoveryResourceKey(RecoveryResourceKind.STOCK, request.restorationId),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)))
    return true
}

/** Native source capabilities only. This is not a CommandReadView or an authority reader. */
private class ReturnStockDispositionRejectionSources(private val command: RuntimeHistoricalCommand) {
    private val cases = LocalReturnCaseHistorySources(command)
    suspend fun required(key: AggregateKey, schema: String = LocalSalesStoreProfile.schema(key.kind)): RuntimeHistoricalAggregateSource {
        val source = command.aggregateSource(key) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        check(source.row.key == key && source.row.schema == schema && source.row.revision.value >= 1 &&
            sha256(source.row.payload.copyBytes()) == source.row.digest)
        literal(source)
        return source
    }

    suspend fun claim(key: ClaimKey, owner: DomainId?) { check(command.claim(key) == owner) }

    suspend fun caseOriginal(source: RuntimeHistoricalAggregateSource, operationId: OperationId): RetainedCommandEvidence {
        val original = (source.original as? RetainedOriginalV2.Legacy)?.evidence
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        check(original.committed && original.nativeOriginalProvenance == null && original.scope == command.scope &&
            original.command == LocalReturnCaseProfileV1.COMMAND && original.operationId == operationId &&
            LocalReturnCaseCommandCodecV1.normalize(original.normalized) == original.normalized)
        return original
    }

    suspend fun event(operationId: OperationId): LocalReturnCaseEventV1 {
        val source = required(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(operationId.value)))
        val original = caseOriginal(source, operationId)
        // Reuse the native-cut decision, qualification-source and immutable BEFORE-witness
        // joins. A previously joined acceptance alone does not prove these still match here.
        val selected = cases.readEvent(operationId, LocalReturnCaseDependencies())
        check(selected.stored.key == source.row.key && selected.stored.revision == source.row.revision &&
            selected.stored.schema == source.row.schema && selected.stored.payload == source.row.payload &&
            selected.stored.digest == source.row.digest)
        val event = selected.value
        if (event.action == LocalReturnCaseActionV1.ACCEPTED) requireAcceptanceCompanions(original, event)
        return event
    }

    private suspend fun requireAcceptanceCompanions(original: RetainedCommandEvidence, event: LocalReturnCaseEventV1) {
        val request = LocalReturnCaseCommandCodecV1.decode(original.normalized) as? LocalReturnCaseRequestV1.Accept
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        val profile = LocalReturnEntitlementProvenanceProfile
        val key = profile.contribution(original.operationId)
        if (original.aggregateWrites.none { it.key == key }) {
            check(original.aggregateWrites.none { it.key.kind in setOf(profile.LOCATOR, profile.CONTRIBUTION) } &&
                original.claimChanges.none { it.key.namespace == profile.NAMESPACE } && command.aggregateSource(key) == null)
            claim(profile.contributionClaim(original.operationId), null)
        } else {
            val contribution = required(key)
            check(contribution.row.revision == Revision(1) && caseOriginal(contribution, original.operationId) === original)
            val value = decodeLocalSalesSource(contribution.row.payload, LocalReturnEntitlementContribution.serializer(), profile.MAX_BYTES)
            check(value.scope.value() == command.scope && value.producer == returnEntitlementProducer(original, request.caseId))
            claim(profile.contributionClaim(original.operationId), value.saleId)
        }
        if (returnCaseRequired(event.result.originalPayable).minor > 0L) {
            val witness = required(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.WORK_BEFORE, DomainId(original.operationId.value)))
            check(witness.row.revision == Revision(1) && witness.row.digest == event.beforeCashWorkDigest &&
                caseOriginal(witness, original.operationId) === original)
        } else check(event.beforeCashWorkDigest == null)
        // These are immutable acceptance companions. Today's entitlement/cash work and
        // locator are deliberately not read; their mutable frontiers may have advanced.
    }

    suspend fun sale(id: DomainId): PaidSale {
        val source = required(LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, id))
        check(source.row.revision == Revision(1))
        val sale = SalesSnapshotCodec.decodePaidSale(source.row.text(),
            SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, command.scope, id, Revision(0)))
        val paid = linked(source)
        check(paid.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
        val finalization = LocalCollectionStockCodecV2.decode(paid.normalized) as? LocalCollectionStockRequestV2.Finalize
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        check(LocalCollectionStockCodecV2.normalize(paid.normalized) == paid.normalized &&
            finalization.saleId == sale.id && finalization.orderId == sale.orderId && finalization.issueId == sale.stockIssueId)
        val reference = sale.priceSourceReference ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        val price = required(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id),
            LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA)
        check(price.row.revision == Revision(1))
        val prices = requirePriceSources(price.row.payload, command.scope, sale.orderId, sale.quote, reference)
        check(prices.normalizedStock)
        val quoted = quoteOriginal(price, prices.originalQuoteOperationId, sale)
        sale.returnPolicySourceReference?.let { ref ->
            val captured = required(AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, ref.id),
                LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
            check(captured.row.revision == Revision(1))
            val value = requireReturnPolicyCapture(captured.row.payload, command.scope, sale.orderId, sale.quote, ref, prices.sources)
            check(value.originalQuoteOperationId == prices.originalQuoteOperationId && value.id == prices.id &&
                quoteOriginal(captured, value.originalQuoteOperationId, sale).identity == quoted.identity)
        }
        claim(LocalSalesStoreProfile.unique("sale", id), sale.orderId)
        claim(LocalSalesStoreProfile.unique("sale_order", sale.orderId), id)
        claim(LocalSalesStoreProfile.unique("return_entitlement", id), id)
        return sale
    }

    private suspend fun quoteOriginal(source: RuntimeHistoricalAggregateSource, operationId: OperationId, sale: PaidSale): RetainedStreamedCommand {
        val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        check(original.identity.scope == command.scope && original.identity.producer.operationId == operationId &&
            original.identity.producer.command == StockStreamingCommandsV2.COLLECTION &&
            LocalCollectionStockCodecV2.normalize(original.normalized) == original.normalized)
        when (val request = LocalCollectionStockCodecV2.decode(original.normalized)) {
            is LocalCollectionStockRequestV2.CreateOrder -> check(request.orderId == sale.orderId && sale.quote.revision == Revision(0))
            is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> check(request.orderId == sale.orderId && sale.quote.revision.value > 0)
            else -> throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        }
        return original
    }

    suspend fun disposition(returnId: DomainId): LocalReturnStockStateV1? {
        val key = LocalReturnStockProfileV1.stateKey(returnId)
        val source = command.aggregateSource(key)
        claim(LocalReturnStockProfileV1.stateClaim(returnId), source?.let { returnId })
        if (source == null) { check(command.aggregateSource(commerceLinkKey(key)) == null); return null }
        check(source.row.schema == returnStockSchema(key.kind) && sha256(source.row.payload.copyBytes()) == source.row.digest)
        literal(source)
        val value = decodeLocalSalesSource(source.row.payload, LocalReturnStockStateV1.serializer(), 8_192)
        check(value.scope == command.scope && value.returnId == returnId && value.revision == source.row.revision &&
            value.revision.value >= 1 && value.postingCount == value.revision.value && value.postedQuantity.scaled > 0 &&
            value.postedQuantity <= value.acceptedQuantity)
        val original = linked(source)
        check(original.identity.producer.command == LocalReturnStockProfileV1.COMMAND &&
            original.identity.producer.operationId == value.lastOperationId)
        return value
    }

    private suspend fun linked(source: RuntimeHistoricalAggregateSource): RetainedStreamedCommand {
        val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        val linkSource = required(commerceLinkKey(source.row.key), commerceLinkSchema())
        val link = decodeLocalSalesSource(linkSource.row.payload, CommerceRowLinkV2.serializer())
        check(link.scope == command.scope && link.kind == source.row.key.kind && link.id == source.row.key.id &&
            link.generation == source.row.revision && link.digest == source.row.digest &&
            StreamedProducerIdentityV2(link.operationId, CommandKey(link.command, link.version), link.actorId, link.semanticDigest) ==
                original.identity.producer && linkSource.original === source.original)
        return original
    }

    private suspend fun literal(source: RuntimeHistoricalAggregateSource) {
        val row = source.row
        when (val original = source.original) {
            is RetainedOriginalV2.Legacy -> {
                check(original.evidence.committed && original.evidence.scope == command.scope &&
                    original === command.earlierOriginal(original.evidence.operationId))
                val write = original.evidence.aggregateWrites.single { it.key == row.key }
                check(write.storageGeneration == row.revision && write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest)
            }
            is RetainedOriginalV2.Streamed -> {
                check(original.evidence.identity.scope == command.scope &&
                    original === command.earlierOriginal(original.evidence.identity.producer.operationId))
                var found = false
                original.evidence.frames { frame ->
                    val write = (frame as? RetainedCommandFrameV2.Write)?.value
                    if (write?.key == row.key) {
                        check(!found && write.storageGeneration == row.revision && write.schema == row.schema &&
                            write.payload == row.payload && write.payloadDigest == row.digest)
                        found = true
                    }
                }
                check(found)
            }
        }
    }
}
