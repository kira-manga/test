package com.ukkera.pos.sales.store.independent.foodcashfinancialrpcv1

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen FFRC v1.0.0 read-declaration and missing/foreign-source SUPPORT only.
 * Header DATA can deny, never establish native originals or current authorization.
 * No accepting grant, successful protected payload or durable business cut is supplied. */
class FoodCashFinancialRpcReadSourceSupportingTest {
    private val f get() = FoodCashFinancialRpcSupportingInputs

    @Test
    fun ffrc014MissingExactOrderAndCollectionDeclareOnlyCurrentOwnerOrderRead() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val body = LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(f.order, limit = 1))
        for (handler in listOf(fixture.foodRead) + fixture.kitchenReads) {
            val key = LocalFoodProfileV1.key(LocalFoodProfileV1.ORDER, f.order)
            val claim = LocalFoodProfileV1.claim(LocalFoodProfileV1.ORDER, f.order)
            val view = FoodCashFinancialRpcDenialDataView(rows = mapOf(key to null), claims = mapOf(claim to null))
            val context = f.readContext(handler.key, body)
            assertDeclaration(handler.prepare(body, view, context), context, SecurityAction.ORDER_READ,
                f.order, Revision(0), OwnerConstraint.OwnerPrivate(f.actor))
            assertEquals(listOf(key), view.aggregateReads)
            assertEquals(listOf(claim), view.claimReads)
            assertTrue(view.retainedReads.isEmpty())
        }
        val key = AggregateKey(LocalFoodFinancialProfileV1.COLLECTION, f.collection)
        val claim = LocalFoodFinancialProfileV1.source(key.kind, key.id)
        val collectionBody = LocalFoodFinancialReadCodecV1.encode(LocalFoodFinancialQueryV1.Collection(f.collection))
        val view = FoodCashFinancialRpcDenialDataView(rows = mapOf(key to null), claims = mapOf(claim to null))
        val context = f.readContext(fixture.financialRead.key, collectionBody)
        assertDeclaration(fixture.financialRead.prepare(collectionBody, view, context), context, SecurityAction.ORDER_READ,
            f.collection, Revision(0), OwnerConstraint.OwnerPrivate(f.actor))
        assertEquals(listOf(key), view.aggregateReads)
        assertEquals(listOf(claim), view.claimReads)
        assertTrue(view.retainedReads.isEmpty())
        assertEquals(0, fixture.source.calls)
        // Declarations and unauthorized-project rejection, not an ORDER_READ grant matrix.
    }

    @Test
    fun ffrc013ffrc014MissingAttemptAndOriginalStillRequireCashReadNotAttest() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val attempt = LocalFoodPaymentQuery.AttemptRecording(f.order, f.collection, f.session,
            f.target.attemptId, f.target.intentOperationId)
        val attemptClaim = foodPaymentAttemptClaim(f.target.attemptId)
        val attemptView = FoodCashFinancialRpcDenialDataView(claims = mapOf(attemptClaim to null))
        val attemptBody = LocalFoodPaymentReadCodec.encode(attempt)
        val attemptContext = f.readContext(fixture.paymentRead.key, attemptBody)
        assertDeclaration(fixture.paymentRead.prepare(attemptBody, attemptView, attemptContext), attemptContext,
            SecurityAction.CASH_READ, f.actor, Revision(0), OwnerConstraint.OwnerPrivate(f.actor))
        assertEquals(listOf(attemptClaim), attemptView.claimReads)
        assertTrue(attemptView.aggregateReads.isEmpty())
        assertTrue(attemptView.retainedReads.isEmpty())

        val raw = foodPaymentRawKey(f.recording)
        val application = foodPaymentApplicationKey(f.recording)
        val originalView = FoodCashFinancialRpcDenialDataView(rows = mapOf(raw to null, application to null))
        val originalBody = LocalFoodPaymentReadCodec.encode(LocalFoodPaymentQuery.Original(f.recording))
        val originalContext = f.readContext(fixture.paymentRead.key, originalBody)
        assertDeclaration(fixture.paymentRead.prepare(originalBody, originalView, originalContext), originalContext,
            SecurityAction.CASH_READ, f.actor, Revision(0), OwnerConstraint.OwnerPrivate(f.actor))
        assertEquals(listOf(raw, application), originalView.aggregateReads)
        assertTrue(originalView.retainedReads.isEmpty())
        assertEquals(0, fixture.source.calls)
        // Neither prepared unavailable value is projected with invented authorization.
    }

    @Test
    fun ffrc014ffrc015ReceiptDeclaresSeparateScopedLookupWithoutAnOwnerGate() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val key = AggregateKey(LocalFoodFinancialProfileV1.ENTITLEMENT, f.sale)
        val claim = LocalFoodFinancialProfileV1.source(key.kind, key.id)
        val body = LocalFoodFinancialReadCodecV1.encode(LocalFoodFinancialQueryV1.Receipt(f.sale))
        val view = FoodCashFinancialRpcDenialDataView(rows = mapOf(key to null), claims = mapOf(claim to null))
        val context = f.readContext(fixture.financialRead.key, body, actor = f.otherActor)
        assertDeclaration(fixture.financialRead.prepare(body, view, context), context,
            SecurityAction.RETURN_RECEIPT_LOOKUP, f.sale, Revision(0), OwnerConstraint.Unrestricted)
        assertEquals(listOf(key), view.aggregateReads)
        assertEquals(listOf(claim), view.claimReads)
        assertTrue(view.retainedReads.isEmpty())
        assertEquals(0, fixture.source.calls)
        // No immutable paid receipt fixture or successful cross-cashier disclosure is qualified.
    }

    @Test
    fun ffrc014ForeignOrderHeaderCanDenyButCannotBorrowOriginalOrRecipeSources() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val row = mixedOrderHeaderData()
        val claim = LocalFoodProfileV1.claim(row.key.kind, row.key.id)
        val body = LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(f.order, limit = 1))
        for (handler in listOf(fixture.foodRead) + fixture.kitchenReads) {
            val view = FoodCashFinancialRpcDenialDataView(rows = mapOf(row.key to row), claims = mapOf(claim to f.order))
            val context = f.readContext(handler.key, body, actor = f.otherActor)
            assertDeclaration(handler.prepare(body, view, context), context, SecurityAction.ORDER_READ,
                f.order, f.orderRevision, OwnerConstraint.OwnerPrivate(f.actor))
            assertEquals(listOf(row.key), view.aggregateReads)
            assertEquals(listOf(claim), view.claimReads)
            assertTrue(view.retainedReads.isEmpty())
            view.assertSuppliedDataUnchanged()
        }
        assertEquals(0, fixture.source.calls)
        // An owner header is a denial hint, not a proof permitting broad grants to disclose it.
    }

    @Test
    fun ffrc003ffrc017MixedPageOneHeaderWithoutItsProducerCannotYieldAnyKitchenTotal() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val row = mixedOrderHeaderData()
        val claim = LocalFoodProfileV1.claim(row.key.kind, row.key.id)
        val producer = f.operation("Order-M")
        val body = LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(f.order, limit = 1))
        for (handler in fixture.kitchenReads) {
            val view = FoodCashFinancialRpcDenialDataView(rows = mapOf(row.key to row), claims = mapOf(claim to f.order),
                originals = mapOf(producer to null))
            assertFailsWith<IllegalStateException> { handler.prepare(body, view, f.readContext(handler.key, body)) }
            assertEquals(listOf(row.key), view.aggregateReads)
            assertEquals(listOf(claim), view.claimReads)
            assertEquals(listOf(producer), view.retainedReads)
            view.assertSuppliedDataUnchanged()
        }
        assertEquals(0, fixture.source.calls)
        // The frozen 57,013 header cannot supply null, zero or a subtotal without its original.
        // Root refusal only: P5/off-page and complete stock/history qualification remain separate.
    }

    @Test
    fun ffrc017EmptyDraftHeaderWithoutItsNativeOriginalCannotInventCurrentZero() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val empty = f.root.getValue("EMPTY").jsonObject
        val id = f.id(empty.getValue("order").jsonPrimitive.content)
        assertTrue(empty.getValue("portions").jsonArray.isEmpty())
        assertEquals(0L, empty.getValue("v3_current_payable_minor").jsonPrimitive.long)
        val envelope = FoodEnvelopeV1(f.scope, LocalFoodProfileV1.ORDER, id, Revision(1), f.operation("Empty-draft"),
            FoodBodyV1.Order(f.actor, f.actor, LocalSalesActivity.RESTAURANT, f.service, emptyList(), emptyList(), f.egp(0)))
        val payload = foodStoredEnvelopeBytes(envelope, LocalFoodDraftProfileV1.ORDER_SCHEMA)
        val row = foodFinancialRpcStoredData(LocalFoodProfileV1.key(envelope.kind, id), envelope.revision,
            LocalFoodDraftProfileV1.ORDER_SCHEMA, payload)
        val claim = LocalFoodProfileV1.claim(row.key.kind, row.key.id)
        val body = LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(id, limit = 1))
        val view = FoodCashFinancialRpcDenialDataView(rows = mapOf(row.key to row), claims = mapOf(claim to id))
        val handler = fixture.kitchenReads.single { it.key.version == 3 }
        assertFailsWith<IllegalStateException> { handler.prepare(body, view, f.readContext(handler.key, body)) }
        assertEquals(listOf(row.key), view.aggregateReads)
        assertTrue(view.retainedReads.isEmpty(), "Scalar retained DATA cannot replace the native draft original")
        view.assertSuppliedDataUnchanged()
        assertEquals(0, fixture.source.calls)
        // No StockRelationalAccess or streamed command is manufactured, even for a zero header.
    }

    @Test
    fun ffrc014ffrc017CorruptOrMismatchedOrderHeaderNeverBecomesAnAcceptingNull() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val row = mixedOrderHeaderData()
        val claim = LocalFoodProfileV1.claim(row.key.kind, row.key.id)
        val wrongDigest = foodFinancialRpcStoredData(row.key, row.revision, row.schema, row.payload,
            sha256("different-header-data".encodeToByteArray()))
        val wrongRevision = foodFinancialRpcStoredData(row.key, row.revision.next(), row.schema, row.payload)
        val wrongSchema = foodFinancialRpcStoredData(row.key, row.revision, "sales.food.order/v99", row.payload)
        val variants = listOf(row to null, row to f.otherActor, wrongDigest to f.order,
            wrongRevision to f.order, wrongSchema to f.order)
        val body = LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(f.order, limit = 1))
        val handler = fixture.kitchenReads.single { it.key.version == 3 }
        for ((data, claimOwner) in variants) {
            val view = FoodCashFinancialRpcDenialDataView(rows = mapOf(row.key to data), claims = mapOf(claim to claimOwner))
            assertFailsWith<IllegalStateException> { handler.prepare(body, view, f.readContext(handler.key, body)) }
            assertTrue(view.retainedReads.isEmpty())
            view.assertSuppliedDataUnchanged()
        }
        assertEquals(0, fixture.source.calls)
        // Header integrity subset only; no complete original or off-page corrupt history exists here.
    }

    @Test
    fun ffrc013ffrc014MissingPendingPaginationIsUnavailableNeverASuccessfulEmptyList() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val query = LocalFoodPaymentQuery.OwnPending(limit = 25)
        val body = LocalFoodPaymentReadCodec.encode(query)
        val page = FoodCashFinancialRpcPageSelection(LocalFoodPaymentProfile.APPLICATION, null, query.limit)
        val view = FoodCashFinancialRpcDenialDataView(unavailablePage = page)
        assertFailsWith<UnsupportedOperationException> {
            fixture.paymentRead.prepare(body, view, f.readContext(fixture.paymentRead.key, body))
        }
        assertEquals(listOf(page), view.pageReads)
        assertTrue(view.aggregateReads.isEmpty())
        assertTrue(view.retainedReads.isEmpty())
        assertEquals(0, fixture.source.calls)
    }

    private fun assertDeclaration(prepared: PreparedRead, context: ReadContext, action: SecurityAction,
        target: DomainId, revision: Revision, owner: OwnerConstraint) {
        val requirement = prepared.requirements.single()
        assertEquals(Permission(action, PermissionVerb.PERFORM), requirement.permission)
        assertEquals(context.scope, requirement.scope)
        assertEquals(owner, requirement.owner)
        assertEquals(LimitUse.NotApplicable, requirement.limitUse)
        val binding = assertNotNull(requirement.binding)
        assertEquals(target, binding.documentId)
        assertEquals(revision, binding.revision)
        assertEquals(context.semanticDigest, binding.payloadDigest)
        assertNull(binding.operationId)
        assertFailsWith<IllegalStateException> { prepared.project(emptyList(), f.at) }
    }

    private fun mixedOrderHeaderData(): StoredAggregate {
        val value = FoodEnvelopeV1(f.scope, LocalFoodProfileV1.ORDER, f.order, f.orderRevision, f.operation("Order-M"),
            FoodBodyV1.Order(f.actor, f.actor, LocalSalesActivity.RESTAURANT, f.service, f.portions, f.batches, f.payable))
        return foodFinancialRpcStoredData(LocalFoodProfileV1.key(value.kind, value.id), value.revision,
            LocalFoodProfileV1.schema(value.kind), foodEnvelopeBytes(value))
    }
}

internal data class FoodCashFinancialRpcPageSelection(val kind: String, val after: DomainId?, val limit: Int)

/** Explicit denial-only DATA. Unassigned reads fail the fixture, rather than returning
 * accepting empty/default observations. This view implements no native stock capability. */
internal class FoodCashFinancialRpcDenialDataView(
    override val scope: BranchScope = FoodCashFinancialRpcSupportingInputs.scope,
    rows: Map<AggregateKey, StoredAggregate?> = emptyMap(),
    claims: Map<ClaimKey, DomainId?> = emptyMap(),
    originals: Map<OperationId, RetainedCommandEvidence?> = emptyMap(),
    private val unavailablePage: FoodCashFinancialRpcPageSelection? = null,
) : CommandReadView {
    private val rows = rows.toMap()
    private val claims = claims.toMap()
    private val originals = originals.toMap()
    private val originalBytes = rows.mapValues { it.value?.payload?.copyBytes() }
    val aggregateReads = mutableListOf<AggregateKey>()
    val claimReads = mutableListOf<ClaimKey>()
    val retainedReads = mutableListOf<OperationId>()
    val pageReads = mutableListOf<FoodCashFinancialRpcPageSelection>()

    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        aggregateReads += key
        if (key !in rows) throw AssertionError("Unassigned supporting aggregate read: $key")
        return rows[key]
    }
    override suspend fun claim(key: ClaimKey): DomainId? {
        claimReads += key
        if (key !in claims) throw AssertionError("Unassigned supporting claim read: $key")
        return claims[key]
    }
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
        retainedReads += operationId
        if (operationId !in originals) throw AssertionError("Unassigned supporting original read: $operationId")
        return originals[operationId]
    }
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        val selection = FoodCashFinancialRpcPageSelection(kind, after, limit)
        pageReads += selection
        if (selection != unavailablePage) throw AssertionError("Unassigned supporting page read: $selection")
        throw UnsupportedOperationException("No qualified pending page is supplied")
    }
    fun assertSuppliedDataUnchanged() {
        for ((key, before) in originalBytes) {
            val after = rows[key]?.payload?.copyBytes()
            if (before == null) assertNull(after) else assertContentEquals(before, after)
        }
        // Supplied DATA byte conservation only, never production persistence/no-effects proof.
    }
}

internal fun foodFinancialRpcStoredData(key: AggregateKey, revision: Revision, schema: String, payload: StoredBytes,
    digest: PayloadDigest = sha256(payload.copyBytes())): StoredAggregate = StoredAggregate(key, revision, schema, payload, digest)
