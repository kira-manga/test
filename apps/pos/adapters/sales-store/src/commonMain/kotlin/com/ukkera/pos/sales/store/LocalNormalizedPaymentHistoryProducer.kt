package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockPaymentHistorySources
import com.ukkera.pos.inventory.store.LocalStockPaymentObservations
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Genuine normalized retail CASH/external collection Start/raw/financial-Apply originals only. No handler
 * execution, accepting resolver, historical CommandReadView or current-policy backfill is
 * used. All present sources and ancestors are selected by the same complete native scanner
 * and must already have completed their own whole business producer joins. Unsupported
 * pending bookkeeping, refund/supervisor/other-command paths remain held. Original external
 * tender policy is qualified at Start and retained through its ancestry, never read at Apply. */
public class LocalNormalizedPaymentHistoryProducer(
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1,
) : RuntimeHistoryBusinessProducer {
    override public val commands: Set<CommandKey> = setOf(LocalFinancialJournalProfileV3.COLLECTION_COMMAND)

    override public suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (command.command !in commands) return false
        check(LocalCollectionCommandCodecV3.normalize(command.normalized) == command.normalized)
        return when (val request = LocalCollectionCommandCodecV3.decode(command.normalized)) {
            is LocalCollectionRequestV3.Start -> start(command, original, request)
            is LocalCollectionRequestV3.RecordOriginal -> raw(command, original, request.target, LocalJournalFactPhaseV3.ORIGINAL)
            is LocalCollectionRequestV3.RecordResolution -> raw(command, original, request.target, LocalJournalFactPhaseV3.RESOLUTION)
            is LocalCollectionRequestV3.ApplyObservation -> apply(command, original, request)
        }
    }

    private suspend fun start(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalCollectionRequestV3.Start): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            LocalFinancialResultCodecV3.decode(original.result) !is LocalFinancialResultV3.AttemptStarted) return false
        val slot = requireNormalizedPaymentStartOriginal(original, tenderPolicies) // Entire original AFTER envelope/business shape.
        check(slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION)
        val observed = command.requireLegacyReadEvidence() ?: return false
        qualifySources(command, observed, stock = true)
        val collection = collection(command, request.collectionId)
        val order = order(command, collection.orderId)
        val cash = cash(command, collection.cashSessionId)
        val literalStock = LocalStockPaymentObservations.reservation(observed, collection.reservationId)
        requireNormalizedPaymentDemand(collection, literalStock)
        val tenderResources = startTenderResources(command, observed, slot.domain.tender)
        check(collection.stockRequirements.normalized && slot.domain.financialResources.toSet() ==
            financialResources(command, observed, collection, order, cash) + tenderResources)
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.COLLECTION_ATTEST, command.scope, collection.id,
            request.expectedCollectionRevision, command.operationId, command.semanticDigest, "collection.attempt.start",
            collection.collectorId, request.amount))
        // This deadline is the exact retained reservation/position minimum, not recordedAt.
        command.requireFinalDeadlineDecision(literalStock.deadline)
        val resources = slot.domain.financialResources.toSet() + LocalStockPaymentObservations.resources(observed) +
            retailJournalAuthorityResources(slot.requirement()) + recordingAuthority(command.actorId)
        command.requireLegacyEffects(literalEffects(command, original, literalStock.deadline), resources)
        return true
    }

    private class Slot(val value: LocalRetailJournalSlotV3, val start: RetainedCommandEvidence,
        val original: RetainedOriginalV2.Legacy,
        val sources: Pair<LocalPhysicalJournalSourceProjection, LocalPhysicalJournalSourceProjection>)
    private class Fact(val value: LocalRetailJournalFactV3, val raw: RetainedCommandEvidence,
        val original: RetainedOriginalV2.Legacy, val slot: Slot)
    private class Application(val value: LocalRetailJournalApplicationV3, val source: RuntimeHistoricalAggregateSource)

    /** The actual immutable slot, BOTH source rows, both future fences and every fixed
     * lineage/attempt claim. No current/native lease or physical capacity is reconstructed. */
    private suspend fun slot(command: RuntimeHistoricalCommand, target: LocalJournalTargetV3): Slot {
        val paired = required(command, retailJournalSlotKeyV3(target.recordingOperationId))
        val row = paired.row
        check(row.revision == Revision(1) && row.schema == LocalFinancialJournalProfileV3.SLOT_SCHEMA)
        val value = localSalesDecode(row.payload, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES)
        check(value.target == target && target.kind == SalesRecordingKind.PAYMENT_ATTESTATION &&
            value.scope.value() == command.scope && retailSlotWriteV3(value).payload == row.payload)
        val earlier = command.earlierOriginal(target.intentOperationId) as? RetainedOriginalV2.Legacy ?: unavailable()
        check(paired.original === earlier && requireNormalizedPaymentStartOriginal(earlier.evidence, tenderPolicies) == value)
        val requirement = value.requirement()
        val sourceRows = listOf(value.masterAllocationId, value.clientAllocationId).map { allocation ->
            val source = required(command, AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, allocation))
            check(source.original === earlier && source.row.revision == Revision(1) && source.row.schema == LocalPhysicalJournalFormatV1.SOURCE_SCHEMA)
            requireRetailRetainedRowV3(earlier.evidence, source.row)
            source.row
        }
        // Pure parsing of the genuine generation-one BEFORE payloads, not source receipts.
        val sources = requireRetailSourcePairV3(requirement, sourceRows.map { AggregateChange(it.key, null, it.schema, it.payload) })
        check(value.masterSource == retailSourceReferenceV3(sources.first) && value.clientSource == retailSourceReferenceV3(sources.second))
        for (expected in retailFutureFencesV3(requirement)) {
            val fence = required(command, expected.key)
            check(fence.original === earlier && fence.row.revision == Revision(1) && fence.row.schema == expected.schema && fence.row.payload == expected.payload)
            requireRetailRetainedRowV3(earlier.evidence, fence.row)
        }
        for (claim in retailLineageClaimsV3(requirement)) check(command.claim(claim.key) == claim.ownerId)
        check(command.claim(LocalSalesStoreProfile.unique("payment_attempt", target.attemptId)) == target.documentId)
        return Slot(value, earlier.evidence, earlier, sources)
    }

    private suspend fun raw(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        target: LocalJournalTargetV3, phase: LocalJournalFactPhaseV3): Boolean {
        if (target.kind != SalesRecordingKind.PAYMENT_ATTESTATION) return false
        val slot = slot(command, target)
        val fact = factAfter(original, target, phase)
        requireRetailRawEvidenceV3(original, fact, slot.value) // Exact raw + initial-PENDING two inserts/result.
        check(command.aggregate(retailJournalFactKeyV3(phase, command.operationId)) == null &&
            command.aggregate(retailJournalApplicationKeyV3(command.operationId)) == null)
        if (phase == LocalJournalFactPhaseV3.RESOLUTION) {
            if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
            val parent = factBefore(command, target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
            applicationBefore(command, parent) // Exact initial or genuine already joined accounting lineage.
            requireResolution(parent.value, fact, slot.value)
        }
        val initial = initialApplication(fact)
        val changes = listOf(retailFactWriteV3(fact), retailApplicationWriteV3(initial, null)).sortedWith(compareBy({ it.key.kind }, { it.key.id.value }))
        val expected = ProposedOutcome.Apply(original.result, changes)
        val requirement = slot.value.requirement()
        if (command.authority == RuntimeHistoricalAuthority.NATIVE_ORIGINAL) {
            check(phase == LocalJournalFactPhaseV3.ORIGINAL)
            requireRetailNativeOriginalEvidence(original, slot.start, fact, slot.value, tenderPolicies)
            val native = checkNotNull(command.nativeOriginal)
            check(native.provenance == original.nativeOriginalProvenance)
            requireNativeSources(native.provenance, slot.sources)
            command.requireNativeOriginalRequirement(requirement.canonicalBytes)
            // AUTHORIZATION keys here are permanent recovery aliases, not an employee grant.
            command.requireNativeOriginalEffects(expected, slot.value.domain.financialResources.toSet() + retailJournalAuthorityResources(requirement))
        } else {
            command.requireRequirement(salesAuthorizationRequirement(retailFactAction(target.kind, phase), command.scope, slot.value.id,
                Revision(0), command.operationId, command.semanticDigest, "financial.journal.retain", slot.value.actorId, slot.value.domain.amount))
            command.requireLegacyEffects(expected, retailJournalAuthorityResources(requirement) + recordingAuthority(command.actorId))
        }
        return true
    }

    private fun factAfter(original: RetainedCommandEvidence, target: LocalJournalTargetV3,
        phase: LocalJournalFactPhaseV3): LocalRetailJournalFactV3 {
        val write = original.aggregateWrites.singleOrNull { it.key == retailJournalFactKeyV3(phase, target.factOperation(phase)) } ?: unavailable()
        check(write.storageGeneration == Revision(1) && write.schema == (if (phase == LocalJournalFactPhaseV3.ORIGINAL)
            LocalFinancialJournalProfileV3.ORIGINAL_SCHEMA else LocalFinancialJournalProfileV3.RESOLUTION_SCHEMA))
        return localSalesDecode(write.payload, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES).also {
            check(it.target == target && it.phase == phase && it.recordedAt == original.recordedAt && retailFactWriteV3(it).payload == write.payload)
        }
    }

    private suspend fun factBefore(command: RuntimeHistoricalCommand, operation: OperationId, phase: LocalJournalFactPhaseV3): Fact {
        val paired = required(command, retailJournalFactKeyV3(phase, operation))
        val earlier = command.earlierOriginal(operation) as? RetainedOriginalV2.Legacy ?: unavailable()
        check(paired.original === earlier && paired.row.revision == Revision(1))
        val fact = localSalesDecode(paired.row.payload, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES)
        val slot = slot(command, fact.target)
        check(fact.phase == phase && fact.target.factOperation(phase) == operation && factAfter(earlier.evidence, fact.target, phase) == fact)
        requireRetailRawEvidenceV3(earlier.evidence, fact, slot.value)
        if (earlier.evidence.nativeOriginalProvenance != null)
            requireRetailNativeOriginalEvidence(earlier.evidence, slot.start, fact, slot.value, tenderPolicies)
        if (phase == LocalJournalFactPhaseV3.RESOLUTION) {
            val parent = factBefore(command, fact.target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
            requireResolution(parent.value, fact, slot.value)
        }
        return Fact(fact, earlier.evidence, earlier, slot)
    }

    private fun initialApplication(fact: LocalRetailJournalFactV3): LocalRetailJournalApplicationV3 =
        LocalRetailJournalApplicationV3(3, fact.scope, fact.target, fact.phase, fact.recordingActorId, Revision(0),
            LocalJournalApplicationStateV3.PENDING_RECONCILIATION, LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION,
            fact.recordedAt, null, null, emptyList())

    private suspend fun applicationBefore(command: RuntimeHistoricalCommand, fact: Fact): Application {
        val paired = required(command, retailJournalApplicationKeyV3(fact.value.target.factOperation(fact.value.phase)))
        val row = paired.row
        check(row.schema == LocalFinancialJournalProfileV3.APPLICATION_SCHEMA)
        val application = localSalesDecode(row.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
        check(application.schemaVersion == 3 && application.scope == fact.value.scope && application.target == fact.value.target &&
            application.phase == fact.value.phase && application.actorId == fact.value.recordingActorId && application.revision.next() == row.revision)
        if (application.revision == Revision(0)) {
            check(paired.original === fact.original && application == initialApplication(fact.value))
            requireRetailRetainedRowV3(fact.raw, row)
        } else {
            val previous = paired.original as? RetainedOriginalV2.Legacy ?: unavailable()
            check(previous.evidence.nativeOriginalProvenance == null && previous.evidence.command == LocalFinancialJournalProfileV3.COLLECTION_COMMAND &&
                previous.evidence.operationId == application.lastApplicationOperationId && application.updatedAt == previous.evidence.recordedAt &&
                (application.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION || fact.value.phase == LocalJournalFactPhaseV3.ORIGINAL))
            // The core pairing already requires this whole earlier business join. No
            // unsupported PENDING reason is accepted here as a substitute for that join.
            requireRetailApplicationOriginalV3(previous.evidence, fact.value, application, fact.slot.value, null, tenderPolicies)
        }
        return Application(application, paired)
    }

    private fun requireResolution(parent: LocalRetailJournalFactV3, resolution: LocalRetailJournalFactV3,
        slot: LocalRetailJournalSlotV3) {
        val domain = retailJournalDomain(SalesRecordingKind.PAYMENT_ATTESTATION)
        check(parent.target == resolution.target && parent.phase == LocalJournalFactPhaseV3.ORIGINAL && resolution.phase == LocalJournalFactPhaseV3.RESOLUTION &&
            domain.classifyOriginal(slot.requirement(), parent.body()) == LocalPhysicalOriginalKind.UNKNOWN && resolution.originalBodyDigest == parent.bodyDigest)
        // This immutable linkage descriptor is DATA only. Actual current original-recorder
        // authorization was independently captured/replayed by core, never waived by this call.
        domain.validateResolution(slot.requirement(), parent.body(), resolution.recordingActorId,
            retailResolutionDescriptorV3(slot.requirement(), parent.body(), resolution.recordingActorId, resolution.body()), resolution.body())
    }

    private suspend fun apply(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalCollectionRequestV3.ApplyObservation): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            LocalFinancialResultCodecV3.decode(original.result) !is LocalFinancialResultV3.Applied) return false
        val fact = factBefore(command, request.recordingOperationId, request.phase)
        val before = applicationBefore(command, fact)
        if (before.value.state != LocalJournalApplicationStateV3.PENDING_RECONCILIATION) return false
        val write = original.aggregateWrites.singleOrNull { it.key == before.source.row.key } ?: unavailable()
        check(write.schema == LocalFinancialJournalProfileV3.APPLICATION_SCHEMA && write.storageGeneration == before.source.row.revision.next())
        val application = localSalesDecode(write.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
        if (application.state != LocalJournalApplicationStateV3.APPLIED) return false
        val proof = checkNotNull(application.financialProof)
        val parent = if (request.phase == LocalJournalFactPhaseV3.ORIGINAL) { check(proof.parentApplication == null); null } else {
            val parentFact = factBefore(command, fact.value.target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
            val parentApplication = applicationBefore(command, parentFact)
            val previous = parentApplication.source.original as? RetainedOriginalV2.Legacy ?: unavailable()
            val reference = LocalRetailParentApplicationV3(previous.evidence.operationId, parentApplication.source.row.revision,
                parentApplication.source.row.digest.value)
            check(parentApplication.value.state == LocalJournalApplicationStateV3.APPLIED &&
                parentApplication.value.appliedAction == LocalSalesAction.PAYMENT_UNKNOWN &&
                parentApplication.value.lastApplicationOperationId == reference.operationId && proof.parentApplication == reference)
            RetailApplicationParentV3(parentFact.value, parentApplication.value, reference)
        }
        for (source in proof.sources) {
            val row = required(command, AggregateKey(source.sourceKind, source.sourceId)).row
            check(source.scope.value() == command.scope && source.capturingOperationId == command.operationId &&
                source.storageRevision == row.revision && source.sourceSchema == row.schema && source.sourceDigest == row.digest.value && source.body() == row.payload)
        }
        // Entire AFTER financial transition, cash movement, uniqueness, result, link and
        // literal source closure. No stock-at-Apply test or fabricated predecessor is used.
        requireRetailApplicationOriginalV3(original, fact.value, application, fact.slot.value, parent, tenderPolicies)
        check(application == before.value.copy(revision = before.value.revision.next(), state = LocalJournalApplicationStateV3.APPLIED,
            reason = null, updatedAt = original.recordedAt, lastApplicationOperationId = original.operationId,
            appliedAction = retailPaymentAction(retailPaymentObservation(fact.value)), financialRevisions = application.financialRevisions,
            financialProof = proof) && retailApplicationWriteV3(application, before.source.row).payload == write.payload)
        val observed = command.requireLegacyReadEvidence() ?: return false
        qualifySources(command, observed, stock = false)
        val collection = collection(command, fact.value.target.documentId)
        val order = order(command, collection.orderId)
        val cash = cash(command, fact.value.target.sessionId)
        check(collection.stockRequirements.normalized)
        command.requireRequirement(salesAuthorizationRequirement(retailFactAction(SalesRecordingKind.PAYMENT_ATTESTATION, request.phase),
            command.scope, collection.id, collection.revision, command.operationId, command.semanticDigest,
            "financial.journal.payment.apply", fact.slot.value.actorId, fact.slot.value.domain.amount))
        command.requireLegacyEffects(literalEffects(command, original, null), fact.slot.value.domain.financialResources.toSet() +
            financialResources(command, observed, collection, order, cash) + retailJournalAuthorityResources(fact.slot.value.requirement()) + recordingAuthority(command.actorId))
        return true
    }

    private suspend fun qualifySources(command: RuntimeHistoricalCommand, observed: StockReadObservations, stock: Boolean) {
        if (stock) LocalStockPaymentHistorySources.requireOriginals(command, observed)
        else check(observed.sources.none { it is StreamedSourceObservationV2.StockMember || it is StreamedSourceObservationV2.StockRange ||
            it is StreamedSourceObservationV2.Current && it.key.kind in StockRelationalKindsV2.all ||
            it is StreamedSourceObservationV2.Claim && it.key.namespace in com.ukkera.pos.inventory.store.LocalStockStoreProfile.inventoryClaimNamespaces })
        for (source in observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>()) {
            val paired = required(command, source.key)
            check(paired.row.revision == source.generation && paired.row.schema == source.schema && paired.row.payload == source.payload && paired.row.digest == source.payloadHash)
            if (source.key.kind in setOf(LocalSalesStoreProfile.ORDER_KIND, LocalSalesStoreProfile.COLLECTION_KIND)) {
                val linkSource = required(command, commerceLinkKey(source.key))
                val literalLink = observed.current(linkSource.row.key) ?: unavailable()
                check(linkSource.original === paired.original && literalLink.schema == commerceLinkSchema() &&
                    literalLink.generation == linkSource.row.revision && literalLink.payload == linkSource.row.payload && literalLink.payloadHash == linkSource.row.digest)
                val link = localSalesDecode(linkSource.row.payload, CommerceRowLinkV2.serializer())
                val identity = StreamedProducerIdentityV2(link.operationId, CommandKey(link.command, link.version), link.actorId, link.semanticDigest)
                check(link.scope == command.scope && link.kind == source.key.kind && link.id == source.key.id &&
                    link.generation == source.generation && link.digest == source.payloadHash && producer(paired.original) == identity)
                when (val previous = paired.original) {
                    is RetainedOriginalV2.Streamed -> check(previous.evidence.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
                    is RetainedOriginalV2.Legacy -> check(source.key.kind == LocalSalesStoreProfile.COLLECTION_KIND &&
                        previous.evidence.nativeOriginalProvenance == null && previous.evidence.command == LocalFinancialJournalProfileV3.COLLECTION_COMMAND)
                }
            }
            if (source.key.kind == LocalSalesStoreProfile.PRICE_SOURCES_KIND) {
                val price = localSalesDecode(source.payload, LocalSalesPriceSourcesV2.serializer())
                val quote = command.earlierOriginal(price.originalQuoteOperationId) as? RetainedOriginalV2.Streamed ?: unavailable()
                check(paired.original === quote && price.normalizedStock)
                requireNormalizedPaymentQuoteRequest(quote.evidence, price.orderId, price.quoteRevision)
            }
            if (source.key.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND) returnPolicyCapture(command, observed, source)
        }
    }

    /** The actual Start's observed policy bytes match its genuine native BEFORE source.
     * Neither the immutable tender marker nor a current policy lookup can replace this join. */
    private suspend fun startTenderResources(command: RuntimeHistoricalCommand, observed: StockReadObservations,
        tender: LocalTenderViewV1): Set<RecoveryResourceKey> {
        if (tender.kind == "CASH") {
            check(tender == LocalTenderViewV1("CASH"))
            return emptySet()
        }
        val tenderId = checkNotNull(tender.tenderId)
        val literal = observed.current(LocalSalesTenderPolicyProfileV1.key(tenderId)) ?: unavailable()
        val paired = required(command, literal.key)
        check(literal.schema == LocalSalesTenderPolicyProfileV1.SCHEMA && paired.row.revision == literal.generation &&
            paired.row.schema == literal.schema && paired.row.payload == literal.payload && paired.row.digest == literal.payloadHash)
        val decoded = tenderPolicies.decode(command.scope, literal) ?: unavailable()
        val native = tenderPolicies.decode(command.scope, paired.row) ?: unavailable()
        val expectedResources = setOf(recordingAuthority(tenderId))
        check(decoded.scope == command.scope && native.scope == command.scope && tenderView(decoded.tender) == tender &&
            native.tender == decoded.tender && decoded.recoveryResources == expectedResources && native.recoveryResources == expectedResources)
        return expectedResources
    }

    private fun requireNativeSources(p: NativeOriginalJournalProvenanceV1,
        sources: Pair<LocalPhysicalJournalSourceProjection, LocalPhysicalJournalSourceProjection>) {
        for ((actual, retained) in listOf(sources.first, sources.second).zip(listOf(p.nativeOriginal.masterSource, p.nativeOriginal.clientSource))) {
            check(retained.role == (if (actual.owner.role == LocalPhysicalSpoolRole.MASTER) NativeOriginalJournalProvenanceV1.SourceRole.MASTER
                else NativeOriginalJournalProvenanceV1.SourceRole.CLIENT) && retained.installationId == actual.owner.installationId &&
                retained.deviceId == actual.owner.deviceId && retained.catalogueId == actual.owner.catalogueId && retained.keyId == actual.owner.keyId &&
                retained.allocationId == actual.allocationId && retained.nativeSlotIndex == actual.slotIndex &&
                retained.sourcePayloadDigest == retailJournalDigestV3(actual.sourceCommitment) && retained.headerDigest == actual.headerDigest &&
                retained.allocationDigest == actual.allocationDigest && retained.nativeIdentityDigest == sha256(journalNativeBytes(actual.nativeIdentity)))
        }
    }

    private suspend fun required(command: RuntimeHistoricalCommand, key: AggregateKey): RuntimeHistoricalAggregateSource =
        command.aggregateSource(key) ?: unavailable()
    private suspend fun collection(command: RuntimeHistoricalCommand, id: DomainId): CollectionLock =
        required(command, LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, id)).row.let {
            check(it.schema == LocalSalesStoreProfile.schema(it.key.kind)); normalizedCollection(it, command.scope)
        }
    private suspend fun order(command: RuntimeHistoricalCommand, id: DomainId): OwnedOrder =
        required(command, LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, id)).row.let {
            check(it.schema == LocalSalesStoreProfile.schema(it.key.kind))
            SalesSnapshotCodec.decodeOrder(it.text(), SalesSnapshotBinding(SalesSnapshotKind.ORDER, command.scope, id, it.domainRevision()))
        }
    private suspend fun cash(command: RuntimeHistoricalCommand, id: DomainId): CashSession =
        required(command, CashNonSaleStoreProfile.sessionKey(id)).row.let {
            check(it.schema == CashNonSaleStoreProfile.SESSION_SCHEMA)
            SalesSnapshotCodec.decodeCashSession(it.text(), SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, command.scope, id, it.domainRevision()))
        }
    private suspend fun returnPolicyCapture(command: RuntimeHistoricalCommand, observed: StockReadObservations,
        source: StreamedSourceObservationV2.Current): LocalSalesReturnPolicyCaptureV2 {
        val projection = requirePaymentReturnPolicySource(observed, source)
        val paired = required(command, source.key)
        val price = required(command, projection.priceSource.key)
        check(paired.row.revision == source.generation && paired.row.schema == source.schema &&
            paired.row.payload == source.payload && paired.row.digest == source.payloadHash &&
            price.row.revision == projection.priceSource.generation && price.row.schema == projection.priceSource.schema &&
            price.row.payload == projection.priceSource.payload && price.row.digest == projection.priceSource.payloadHash)
        val capture = projection.capture
        // Both native BEFORE companions name the SAME earlier, already fully joined whole
        // quote. The core pair is not opaque authority; every literal write/projection matches.
        val quote = command.earlierOriginal(capture.originalQuoteOperationId) as? RetainedOriginalV2.Streamed ?: unavailable()
        check(paired.original === quote && price.original === quote)
        requireNormalizedPaymentQuoteRequest(quote.evidence, capture.orderId, capture.quoteRevision)
        requireCommerceWrite(quote.evidence, paired.row, price.row)
        return capture
    }

    private suspend fun financialResources(command: RuntimeHistoricalCommand, observed: StockReadObservations,
        collection: CollectionLock, order: OwnedOrder, cash: CashSession): Set<RecoveryResourceKey> {
        val capture = order.returnPolicySourceReference?.let { reference ->
            val source = observed.current(AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, reference.id)) ?: unavailable()
            returnPolicyCapture(command, observed, source).also { check(it.orderId == order.id) }
        }
        return setOf(orderResource(collection.id), orderResource(order.id), orderResource(order.ownerId), cashResource(cash.id),
            cashResource(cash.drawerId), cashResource(cash.custodianId)) + priceSourcesResources(order.priceSourceReference) +
            returnPolicyCaptureResources(capture)
    }
    private fun producer(original: RetainedOriginalV2): StreamedProducerIdentityV2 = when (original) {
        is RetainedOriginalV2.Legacy -> with(original.evidence) { StreamedProducerIdentityV2(operationId, command, originalActorId, semanticDigest) }
        is RetainedOriginalV2.Streamed -> original.evidence.identity.producer
    }

    /** Inert expected effects only AFTER the complete static producer validation. Every
     * expected revision is obtained from its genuine native BEFORE cut, never inferred as a
     * historical view or executed proposal. Core still compares all actual native mutations. */
    private suspend fun literalEffects(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        deadline: UtcInstant?): ProposedOutcome.Apply {
        check(original.jobs.isEmpty())
        val changes = original.aggregateWrites.map { actual ->
            val before = command.aggregate(actual.key)
            check(actual.storageGeneration == (before?.revision?.next() ?: Revision(1)))
            AggregateChange(actual.key, before?.revision, actual.schema, actual.payload)
        }
        val claims = original.claimChanges.map { if (it.action == RetainedClaimAction.ACQUIRE) ClaimChange.Acquire(it.key, it.ownerId)
            else ClaimChange.Release(it.key, it.ownerId) }
        return ProposedOutcome.Apply(original.result, changes, claims, mustCommitBefore = deadline)
    }
    private fun unavailable(): Nothing = error("Original normalized payment source unavailable")
}
