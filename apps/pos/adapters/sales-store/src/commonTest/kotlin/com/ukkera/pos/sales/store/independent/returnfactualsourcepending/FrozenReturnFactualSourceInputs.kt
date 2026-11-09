package com.ukkera.pos.sales.store.independent.returnfactualsourcepending

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptAuthorizationV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptEventV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptHeadV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptObservationV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptPortionV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptProfileV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptResultV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptSaleSourceV1
import com.ukkera.pos.sales.store.LocalSalesScope
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Numeric/documentary projection of return-factual-source-pending-v1 only.
 * Manifest SHA-256: d04da1144c647e7674632b490d5487c3284e7567524bf0f2f4afecc7e17ee528.
 *
 * Raw event inputs below are INERT, unsigned format fixtures, not stored rows or
 * genuine originals. The unit ID, reused coordinate IDs, empty-object digest and
 * zero metadata merely fill unasserted transport fields. No native original,
 * principal, grant, retained producer or historical BEFORE is constructed.
 * The supplied fields cannot establish EA catalogue identity or business trust.
 */
internal object FrozenReturnFactualSourceInputs {
    private const val FIXTURE_SLICES_JSON: String = """
    {"identifiers":{"organization":"a5300000-0000-4000-8000-000000000001","company":"a5300000-0000-4000-8000-000000000002","branch":"a5300000-0000-4000-8000-000000000003","otherBranch":"a5300000-0000-4000-8000-000000000004","otherCompany":"a5300000-0000-4000-8000-000000000005","collector":"a5300000-0000-4000-8000-000000000010","sale":"a5300000-0000-4000-8000-000000000101","portionA":"a5300000-0000-4000-8000-000000000201","portionB":"a5300000-0000-4000-8000-000000000202","receiptOperationA":"a5300000-0000-4000-8000-000000000301","receiptOperationB":"a5300000-0000-4000-8000-000000000302"},"F-SALE":{"saleId":"a5300000-0000-4000-8000-000000000101","paidFinalizeAt":"2026-10-20T11:30:00Z","collectorId":"a5300000-0000-4000-8000-000000000010","portions":[{"portionId":"a5300000-0000-4000-8000-000000000201","baseUnit":"EA","precision":0,"originalSoldQuantity":"1"},{"portionId":"a5300000-0000-4000-8000-000000000202","baseUnit":"EA","precision":0,"originalSoldQuantity":"1"}]},"F-RECEIPT":{"observations":[{"operationId":"a5300000-0000-4000-8000-000000000301","request":{"saleId":"a5300000-0000-4000-8000-000000000101","expectedHeadRevision":"0","receivedAt":"2026-10-20T12:00:00Z","receiptTimeZoneId":"Africa/Cairo","observation":"CUSTOMER_RECEIVED_AT_COUNTER","portions":[{"portionId":"a5300000-0000-4000-8000-000000000201","quantity":"1"}]},"masterRecordedAt":"2026-10-20T12:00:10Z"},{"operationId":"a5300000-0000-4000-8000-000000000302","request":{"saleId":"a5300000-0000-4000-8000-000000000101","expectedHeadRevision":"1","receivedAt":"2026-10-20T12:00:00Z","receiptTimeZoneId":"Africa/Cairo","observation":"CUSTOMER_RECEIVED_AT_COUNTER","portions":[{"portionId":"a5300000-0000-4000-8000-000000000202","quantity":"1"}]},"masterRecordedAt":"2026-10-20T12:00:20Z"}],"expectedCompleteHead":{"headRevision":"2","eventCount":2,"cumulativeRecordedQuantity":"2","originalSoldQuantities":["1","1"],"recordedQuantities":["1","1"]},"invalidObservationVectors":[{"id":"future_to_commit","receivedAt":"2026-10-20T12:00:10.001Z","masterRecordedAt":"2026-10-20T12:00:10Z"},{"id":"missing_observed_time","receivedAt":null},{"id":"device_default_zone","receiptTimeZoneId":null}]},"F-SOURCE-DAMAGE":{"receiptVariants":[{"id":"missing_original","state":"No verified original sale evidence","expectedBehavior":"Original unavailable, not no-observation on a verified original"},{"id":"wrong_event_producer","state":"Stored event locator or operation/actor disagrees with genuine retained producer/result","expectedBehavior":"Unavailable, no fabricated historical event"}],"originalVariants":["other selling branch","other company"]}}
    """

    val root: JsonObject = Json.parseToJsonElement(FIXTURE_SLICES_JSON).jsonObject
    private val identifiers: JsonObject = root.getValue("identifiers").jsonObject
    val sale: JsonObject = root.getValue("F-SALE").jsonObject
    val receipt: JsonObject = root.getValue("F-RECEIPT").jsonObject
    val observations: List<JsonObject> = receipt.getValue("observations").jsonArray.map { it.jsonObject }
    val invalidObservations: Map<String, JsonObject> = receipt.getValue("invalidObservationVectors").jsonArray
        .map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
    val completeHead: JsonObject = receipt.getValue("expectedCompleteHead").jsonObject

    fun id(name: String): DomainId = DomainId(identifiers.getValue(name).jsonPrimitive.content)
    val scope: BranchScope = BranchScope(id("organization"), id("company"), id("branch"))
    val otherBranchScope: BranchScope = scope.copy(branchId = id("otherBranch"))
    val otherCompanyScope: BranchScope = scope.copy(companyId = id("otherCompany"))
    val saleId: DomainId = DomainId(sale.getValue("saleId").jsonPrimitive.content)
    val collectorId: DomainId = DomainId(sale.getValue("collectorId").jsonPrimitive.content)
    val paidFinalizeAt: UtcInstant = UtcInstant.parseCanonical(sale.getValue("paidFinalizeAt").jsonPrimitive.content)

    fun operation(index: Int): OperationId = OperationId(observations[index].getValue("operationId").jsonPrimitive.content)
    fun request(index: Int): JsonObject = observations[index].getValue("request").jsonObject
    fun receivedAt(index: Int): UtcInstant = UtcInstant.parseCanonical(request(index).getValue("receivedAt").jsonPrimitive.content)
    fun recordedAt(index: Int): UtcInstant = UtcInstant.parseCanonical(observations[index].getValue("masterRecordedAt").jsonPrimitive.content)
    fun headRevision(index: Int): Revision = Revision((if (index == 0)
        request(1).getValue("expectedHeadRevision") else completeHead.getValue("headRevision")).jsonPrimitive.content.toLong())

    private fun portions(index: Int): List<LocalRetailCustomerReceiptPortionV1> =
        request(index).getValue("portions").jsonArray.map { raw ->
            val p = raw.jsonObject
            val portionId = DomainId(p.getValue("portionId").jsonPrimitive.content)
            val sold = sale.getValue("portions").jsonArray.single {
                it.jsonObject.getValue("portionId").jsonPrimitive.content == portionId.value
            }.jsonObject
            LocalRetailCustomerReceiptPortionV1(portionId, Quantity.parse(id("organization"),
                p.getValue("quantity").jsonPrimitive.content, sold.getValue("precision").jsonPrimitive.int))
        }

    fun eventBytes(index: Int): StoredBytes {
        val opaqueDigest = sha256("{}".encodeToByteArray())
        val source = LocalRetailCustomerReceiptSaleSourceV1(
            LocalRetailCustomerReceiptProfileV1.SOURCE_SCHEMA, LocalSalesScope.from(scope), saleId,
            saleId, collectorId, Revision(1), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.SALE_KIND),
            opaqueDigest, OperationId(saleId.value), collectorId, "sales.collection", 1,
            opaqueDigest, collectorId, Revision(0), JournalSequence(0), paidFinalizeAt,
        )
        val currentOperation = operation(index)
        val head = LocalRetailCustomerReceiptHeadV1(LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA,
            source, headRevision(index), observations.take(index + 1).map {
                OperationId(it.getValue("operationId").jsonPrimitive.content)
            }, (0..index).flatMap(::portions), currentOperation)
        val zone = request(index).getValue("receiptTimeZoneId").jsonPrimitive.content
        val observed = receivedAt(index)
        val committed = recordedAt(index)
        val event = LocalRetailCustomerReceiptEventV1(
            LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA, currentOperation, collectorId, opaqueDigest,
            LocalRetailCustomerReceiptAuthorizationV1(collectorId, collectorId, SessionGeneration(0), Revision(0)),
            LocalRetailCustomerReceiptObservationV1.valueOf(request(index).getValue("observation").jsonPrimitive.content),
            portions(index), observed, zone, committed,
            if (index == 0) null else Revision(request(index).getValue("expectedHeadRevision").jsonPrimitive.content.toLong()),
            if (index == 0) null else opaqueDigest,
            if (index == 0) null else operation(index - 1),
            if (index == 0) null else opaqueDigest,
            emptyList(), head,
            LocalRetailCustomerReceiptResultV1.Applied(saleId, currentOperation, headRevision(index), observed, zone, committed),
        )
        return localSalesEncode(event, LocalRetailCustomerReceiptEventV1.serializer(),
            LocalRetailCustomerReceiptProfileV1.MAX_ROW_BYTES)
    }
}
