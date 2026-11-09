package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest

/** Fixed financial workflow selection, never an authorization grant or a capture capability. */
internal val invoiceMatchingCommands: Set<CommandKey> = setOf(LocalPurchasingProfile.MATCH_CAPTURED_INVOICE)
internal val invoiceMatchingReads: Set<ReadKey> = setOf(
    LocalPurchasingProfile.READ_INVOICES, LocalPurchasingProfile.READ,
    LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE, LocalPurchasingDocumentProfileV1.READ,
)

internal fun requireInvoiceMatchingIntent(intent: LocalCommandIntent) {
    if (intent.action != LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE || intent.formVersion != 1 ||
        intent.arrivalEvidence != null || intent.lineField != null || intent.lines.isNotEmpty() ||
        intent.reference?.let { it.kind != LocalRecordKind.PURCHASE_INVOICE || it.revision != null || it.parentId != null } == true)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

/** No configuration, document scan/bytes/export, payable or legacy matching reads. */
internal fun requireInvoiceMatchingRead(request: ReadRequest) {
    try {
        when (request.query) {
            LocalPurchasingProfile.READ, LocalPurchasingProfile.READ_INVOICES -> {
                val selected = LocalPurchasingCodec.decodeQuery(request.payload)
                val section = if (request.query == LocalPurchasingProfile.READ_INVOICES) LocalPurchasingSectionV1.INVOICE
                    else LocalPurchasingSectionV1.ORDER
                if (selected.section != section || selected.export) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalPurchasingCodec.query(selected) != request.payload) invalid()
            }
            LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE -> {
                val selected = LocalPurchasingCodec.decodeCapturedMatchSourceQuery(request.payload)
                if (LocalPurchasingCodec.capturedMatchSource(selected) != request.payload) invalid()
            }
            LocalPurchasingDocumentProfileV1.READ -> {
                val selected = LocalPurchasingDocumentCodecV1.decodeQuery(request.payload)
                if (selected.documentId == null || selected.after != null || selected.pendingDocumentId != null)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalPurchasingDocumentCodecV1.query(selected).payload != request.payload) invalid()
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { invalid() }
}

internal fun invoiceMatchingRequest(action: LocalAction, request: MutationRequest): LocalMatchCapturedSupplierInvoiceV2 {
    if (action != LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE ||
        request.command != LocalPurchasingProfile.MATCH_CAPTURED_INVOICE || request.actionApprovalId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return try {
        LocalPurchasingCodec.decodeCapturedMatch(request.payload).also {
            if (LocalPurchasingCodec.normalize(request.command, request.payload) != request.payload)
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}
