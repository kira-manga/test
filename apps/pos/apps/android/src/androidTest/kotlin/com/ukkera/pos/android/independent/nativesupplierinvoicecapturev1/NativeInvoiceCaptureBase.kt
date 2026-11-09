package com.ukkera.pos.android.independent.nativesupplierinvoicecapturev1

import com.ukkera.pos.catalog.store.LocalConfigurationDetailV1
import com.ukkera.pos.catalog.store.LocalConfigurationListItemV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentViewV1
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.inventory.PurchaseOrderV1
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Observed bindings for frozen SIDC BASE, never a fixture-created authority or receipt.
 * The separate fixture employee authored setup; the first employee's capture policy is untouched. */
internal class NativeInvoiceCaptureBase(
    val coordinates: SelfOwnedAllocationCoordinatesV1,
    val fixtureActorId: DomainId,
    val fixtureLogin: LocalLoginIdentifier,
    val fixtureAccount: LocalAccountProvisioning,
    val supplierId: DomainId,
    val supplierStorageRevision: Revision,
    val warehouseId: DomainId,
    val eaUnitId: DomainId,
    val cartonUnitId: DomainId,
    val trayUnitId: DomainId,
    val gramUnitId: DomainId,
    val kilogramUnitId: DomainId,
    val a: NativeInvoiceCaptureBaseSku,
    val b: NativeInvoiceCaptureBaseSku,
    val c: NativeInvoiceCaptureBaseSku,
    val originalOrder: PurchaseOrderV1,
    val po: LocalPurchasingDocumentViewV1,
    val openingReceiptId: DomainId,
    val drawerId: DomainId,
    val cashSessionId: DomainId,
    val enrollment: NativeInvoiceCaptureBaseEnrollment,
    publications: List<NativeInvoiceCaptureBasePublication>,
    operations: List<NativeInvoiceCaptureBaseOperation>,
    val snapshot: NativeInvoiceCaptureBaseSnapshot,
) {
    val publications: List<NativeInvoiceCaptureBasePublication> = publications.toList()
    val operations: List<NativeInvoiceCaptureBaseOperation> = operations.toList()
    override fun toString(): String = "NativeInvoiceCaptureBase([REDACTED])"
}

/** Exact native-signed requested content plus the independently authorized current projection.
 * Unit/product/conversion revisions used by the case come from detail, not guessed counters. */
internal class NativeInvoiceCaptureBaseSku(
    val definition: LocalPublishedSkuV1,
    val item: LocalConfigurationListItemV1,
    val detail: LocalConfigurationDetailV1.Sku,
) {
    override fun toString(): String = "NativeInvoiceCaptureBaseSku([REDACTED])"
}

/** Genuine setup command's saved original and actual committed response; no reconstructed result. */
internal class NativeInvoiceCaptureBaseOperation(
    val label: String,
    val request: MutationRequest,
    val identity: OperationIdentity,
    val session: LocalSessionView,
    val result: MutationReply.Recorded,
) {
    override fun toString(): String = "NativeInvoiceCaptureBaseOperation([REDACTED])"
}

internal class NativeInvoiceCaptureBaseEnrollment(
    val requestId: DomainId,
    val originalActionId: DomainId,
    val definitionDigest: PayloadDigest,
    val appliedEventId: DomainId,
) {
    override fun toString(): String = "NativeInvoiceCaptureBaseEnrollment([REDACTED])"
}

internal class NativeInvoiceCaptureBasePublication(
    val originalActionId: DomainId,
    val publicationId: DomainId,
    val signingKeyId: DomainId,
    val contentDigest: PayloadDigest,
) {
    override fun toString(): String = "NativeInvoiceCaptureBasePublication([REDACTED])"
}
