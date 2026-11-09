package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.PayloadDigest

/**
 * Historical v2 dispatch. Fresh Start/Reserve delegate only to explicit journal-first refusal.
 * Exact original v2 RecordOutcome/ApplyObservation remain available for already incurred
 * obligations under their original proven lineage and current authorization. No v3 conversion.
 */
public class LocalHistoricalCollectionHandler(
    stock: LocalStockRepository, tenderSource: TransactionSalesTenderSource,
    private val physicalCapacity: TransactionSalesPhysicalRecordingCapacityV2 = UnavailableSalesPhysicalRecordingCapacityV2,
) : MasterCommandHandler {
    private val intents = LocalCollectionHandler(stock, tenderSource, UnavailableSalesRecordingCapacity)
    override public val key: CommandKey get() = LocalSalesStoreProfile.COLLECTION_COMMAND_V2
    override public val writableKinds: Set<String> get() = intents.writableKinds + physicalWritableKindsV2
    override public val claimNamespaces: Set<String> get() = intents.claimNamespaces + LocalSalesStoreProfile.CLIENT_RECORDING_ALLOCATION_NAMESPACE
    override public fun normalize(input: StoredBytes): StoredBytes = LocalCollectionCommandCodecV2.normalize(input)
    override public fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? =
        (LocalCollectionCommandCodecV2.decode(normalized) as? LocalCollectionRequestV2.RecordOutcome)?.let {
            physicalTargetBindingV2(SalesRecordingKind.PAYMENT_ATTESTATION, it.collectionId, it.sessionId, it.attemptId, it.intentOperationId)
        }

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return when (val request = LocalCollectionCommandCodecV2.decode(normalized)) {
            is LocalCollectionRequestV2.StartAttempt -> intents.startV2(request, view, context, physicalCapacity)
            is LocalCollectionRequestV2.ReserveRecording -> intents.reserveV2(request, view, context, physicalCapacity)
            is LocalCollectionRequestV2.RecordOutcome -> {
                requireHistoricalPhysicalLineageV2(view, context.operationId)
                preparePaymentFactV2(request, view, context)
            }
            is LocalCollectionRequestV2.ApplyObservation -> {
                requireHistoricalPhysicalLineageV2(view, request.recordingOperationId)
                preparePhysicalApplicationV2(SalesRecordingKind.PAYMENT_ATTESTATION, request.recordingOperationId, view, context)
            }
        }
    }
}

public class LocalHistoricalReturnHandler(
    returnPolicy: TransactionSalesReturnPolicySource,
    private val physicalCapacity: TransactionSalesPhysicalRecordingCapacityV2 = UnavailableSalesPhysicalRecordingCapacityV2,
) : MasterCommandHandler {
    private val intents = LocalReturnHandler(returnPolicy, UnavailableSalesRecordingCapacity)
    override public val key: CommandKey get() = LocalSalesStoreProfile.RETURN_COMMAND_V2
    override public val writableKinds: Set<String> get() = intents.writableKinds + physicalWritableKindsV2
    override public val claimNamespaces: Set<String> get() = intents.claimNamespaces + LocalSalesStoreProfile.CLIENT_RECORDING_ALLOCATION_NAMESPACE
    override public fun normalize(input: StoredBytes): StoredBytes = LocalReturnCommandCodecV2.normalize(input)
    override public fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? =
        (LocalReturnCommandCodecV2.decode(normalized) as? LocalReturnRequestV2.RecordRefundOutcome)?.let {
            physicalTargetBindingV2(SalesRecordingKind.REFUND_ATTESTATION, it.returnId, it.sessionId, it.attemptId, it.intentOperationId,
                it.saleId, it.claimId)
        }

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return when (val request = LocalReturnCommandCodecV2.decode(normalized)) {
            is LocalReturnRequestV2.StartRefund -> intents.startV2(request, view, context, physicalCapacity)
            is LocalReturnRequestV2.ReserveRefundRecording -> intents.reserveV2(request, view, context, physicalCapacity)
            is LocalReturnRequestV2.RecordRefundOutcome -> {
                requireHistoricalPhysicalLineageV2(view, context.operationId)
                prepareRefundFactV2(request, view, context)
            }
            is LocalReturnRequestV2.ApplyObservation -> {
                requireHistoricalPhysicalLineageV2(view, request.recordingOperationId)
                preparePhysicalApplicationV2(SalesRecordingKind.REFUND_ATTESTATION, request.recordingOperationId, view, context)
            }
        }
    }
}

private val physicalWritableKindsV2: Set<String> = setOf(LocalSalesStoreProfile.PHYSICAL_SLOT_KIND,
    LocalSalesStoreProfile.PHYSICAL_FACT_KIND, LocalSalesStoreProfile.PHYSICAL_APPLICATION_KIND)
