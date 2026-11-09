package com.ukkera.pos.android.independent.nativesupplierinvoicecapturev1

import com.ukkera.pos.catalog.store.LocalConfigurationPageV1
import com.ukkera.pos.catalog.store.LocalPricePreviewV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentViewV1
import com.ukkera.pos.catalog.store.LocalPurchasingInvoiceDocumentV2
import com.ukkera.pos.catalog.store.LocalSupplierAdvanceViewV1
import com.ukkera.pos.catalog.store.LocalSupplierCashViewV1
import com.ukkera.pos.inventory.store.LocalStockCostPage
import com.ukkera.pos.inventory.store.LocalStockQuantityPage
import com.ukkera.pos.inventory.store.LocalStockReceiptView
import com.ukkera.pos.sales.store.LocalCashReadResultV1
import com.ukkera.pos.sales.store.LocalCashSessionViewV1
import com.ukkera.pos.security.LocalSessionView

/** Fresh protected reads retain the approved PO BASIS. Null PO receipt fields explicitly mean
 * no stock purchase-allowance consumption facts, never absence of approval or invented zero.
 * These projections alone do not prove global receipt/payment/credit/approval document counts;
 * the native census must compare that complete durable baseline independently. */
internal class NativeInvoiceCaptureBaseSnapshot(
    val observedSession: LocalSessionView,
    val supplier: LocalConfigurationPageV1,
    val warehouse: LocalConfigurationPageV1,
    val aSku: NativeInvoiceCaptureBaseSku,
    val bSku: NativeInvoiceCaptureBaseSku,
    val cSku: NativeInvoiceCaptureBaseSku,
    val a: NativeInvoiceCaptureBaseStock,
    val b: NativeInvoiceCaptureBaseStock,
    val c: NativeInvoiceCaptureBaseStock,
    val aPrice: LocalPricePreviewV1,
    val bPrice: LocalPricePreviewV1,
    val cPrice: LocalPricePreviewV1,
    val po: LocalPurchasingDocumentViewV1,
    val poReceivingBasis: LocalConfigurationPageV1,
    val openingReceipt: LocalStockReceiptView,
    val drawer: LocalCashSessionViewV1,
    val ownCash: LocalCashReadResultV1.OwnCash,
    invoiceDocuments: List<LocalPurchasingInvoiceDocumentV2>,
    payables: List<LocalPurchasingDocumentViewV1>,
    supplierAdvances: List<LocalSupplierAdvanceViewV1>,
    ownSupplierCash: List<LocalSupplierCashViewV1>,
) {
    val invoiceDocuments: List<LocalPurchasingInvoiceDocumentV2> = invoiceDocuments.toList()
    val payables: List<LocalPurchasingDocumentViewV1> = payables.toList()
    val supplierAdvances: List<LocalSupplierAdvanceViewV1> = supplierAdvances.toList()
    val ownSupplierCash: List<LocalSupplierCashViewV1> = ownSupplierCash.toList()
    override fun toString(): String = "NativeInvoiceCaptureBaseSnapshot([REDACTED])"
}

/** Actual pages and returned item/position/pool revisions; no DTO stands in for native custody. */
internal class NativeInvoiceCaptureBaseStock(
    quantities: List<LocalStockQuantityPage>,
    costs: List<LocalStockCostPage>,
) {
    val quantities: List<LocalStockQuantityPage> = quantities.toList()
    val costs: List<LocalStockCostPage> = costs.toList()
    override fun toString(): String = "NativeInvoiceCaptureBaseStock([REDACTED])"
}
