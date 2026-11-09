package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest

/** Installation selection only; current Master authorization remains mandatory. */
internal val invoiceCaptureCommands: Set<CommandKey> = setOf(LocalPurchasingProfile.CAPTURE_INVOICE)
internal val invoiceCaptureReads: Set<ReadKey> = setOf(
    LocalPurchasingProfile.READ_INVOICES, LocalPurchasingProfile.READ, LocalConfigurationProfile.QUERY,
)

/** Both closed invoice workspaces may read invoices and PO context, not administer purchasing. */
internal fun requireInvoiceReadWorkflow(query: LocalWorkflowQuery) {
    val purchasing = query.purchasing
    if (query.workflow != LocalWorkflow.PURCHASING || purchasing == null ||
        purchasing.section !in setOf(LocalPurchasingSection.INVOICES, LocalPurchasingSection.ORDERS) ||
        query.pricePreview != null || query.branchReport != null || query.food != null || query.returns != null ||
        query.cash != null || query.inventory != null || query.openingAllocations != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val kind = if (purchasing.section == LocalPurchasingSection.INVOICES) LocalRecordKind.PURCHASE_INVOICE
        else LocalRecordKind.PURCHASE
    val reference = query.reference
    if (reference != null && (reference.kind != kind || reference.revision != null || reference.parentId != null))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (reference != null && query.cursor != null) invalid()
    parseCursor(query.cursor, if (purchasing.section == LocalPurchasingSection.INVOICES) "purchasing-INVOICE" else "purchasing-ORDER")
}

internal fun requireInvoiceCaptureReferences(query: LocalReferenceQuery) {
    if (query.source.kind !in setOf(LocalRecordKind.SUPPLIER, LocalRecordKind.SKU) || query.source.excludedId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

/** Capture wire version 2 uses the existing capture form version 1. */
internal fun requireInvoiceCaptureIntent(intent: LocalCommandIntent) {
    if (intent.action != LocalAction.SUPPLIER_INVOICE_CAPTURE || intent.formVersion != 1 || intent.arrivalEvidence != null ||
        intent.reference?.let { it.kind != LocalRecordKind.PURCHASE || it.revision != null || it.parentId != null } == true)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

/** Shared keys never admit a payable, export, arbitrary configuration or legacy invoice read. */
internal fun requireInvoiceCaptureRead(request: ReadRequest) {
    try {
        when (request.query) {
            LocalPurchasingProfile.READ, LocalPurchasingProfile.READ_INVOICES -> {
                val selected = LocalPurchasingCodec.decodeQuery(request.payload)
                val section = if (request.query == LocalPurchasingProfile.READ_INVOICES) LocalPurchasingSectionV1.INVOICE
                    else LocalPurchasingSectionV1.ORDER
                if (selected.section != section || selected.export) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalPurchasingCodec.query(selected) != request.payload) invalid()
            }
            LocalConfigurationProfile.QUERY -> {
                val selected = LocalConfigurationCodec.decodeQuery(request.payload)
                if (selected.kind !in setOf(LocalConfigurationProfile.SUPPLIER, LocalConfigurationProfile.SKU) || selected.barcode != null)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalConfigurationCodec.query(selected) != request.payload) invalid()
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { invalid() }
}

/** No matching/approval, supplier write, action approval or physical command can use this profile. */
internal fun invoiceCaptureRequest(action: LocalAction, request: MutationRequest): LocalCaptureSupplierInvoiceV2 {
    if (action != LocalAction.SUPPLIER_INVOICE_CAPTURE || request.command != LocalPurchasingProfile.CAPTURE_INVOICE ||
        request.actionApprovalId != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return try {
        LocalPurchasingCodec.decodeCaptureInvoice(request.payload).also {
            if (LocalPurchasingCodec.normalize(request.command, request.payload) != request.payload)
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}
