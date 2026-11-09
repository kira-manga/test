package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalFoodMenuChoiceV1
import com.ukkera.pos.catalog.store.LocalFoodMenuCodecV1
import com.ukkera.pos.catalog.store.LocalFoodMenuPageV1
import com.ukkera.pos.catalog.store.LocalFoodMenuRecipeV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Frozen food-pre-save-preview-v1 common gateway projection/no-journal slices only.
 * The controlled read port supplies public menu/order DTOs and already selected prices;
 * it is not a Master, authenticator, installed source, durable store or admitted transport.
 * Actual renewal/activation, protected disclosure/zero cuts and every whole case remain unbound.
 * LINE-TAX-HALF-UP retains its unbound expected 22805; no alternate profile is substituted.
 */
class FoodPricePreviewFrozenGatewaySlicesTest {
    @Test
    fun fqp01And02OriginalAndCompatibleReadProjectionRetainsExactQuantityTwoInputAndNoJournalUse() = runTest {
        listOf(ORIGINAL_TWO, CURRENT_TWO).forEach { expected ->
            val seam = FoodPricePreviewFrozenGatewaySeam(expected)
            val input = frozenGatewayInput(expected)
            val before = input.snapshot()
            repeat(2) {
                val reply = assertIs<LocalFoodPricePreviewResult.Available>(seam.gateway.previewFood(input))
                assertFrozenGatewayPreview(reply.preview, input, expected)
            }
            assertTrue(input.sameSelection(before))
            assertEquals(2, seam.port.selections.size)
            assertEquals(seam.port.selections[0].payload, seam.port.selections[1].payload)
            assertFrozenGatewayRead(seam, expected)
            assertNoGatewayBusinessEntry(seam)
        }
    }

    @Test
    fun fqp03SuppliedStandardAndTierRepliesProjectQuantityOneAndTwoAsOneCommercialLine() = runTest {
        listOf(TIER_ONE, TIER_TWO).forEach { expected ->
            val seam = FoodPricePreviewFrozenGatewaySeam(expected)
            val input = frozenGatewayInput(expected)
            val reply = assertIs<LocalFoodPricePreviewResult.Available>(seam.gateway.previewFood(input))
            assertFrozenGatewayPreview(reply.preview, input, expected)
            assertFrozenGatewayRead(seam, expected)
            assertNoGatewayBusinessEntry(seam)
        }
    }

    @Test
    fun fqp04SuppliedInclusiveAndExclusiveFourteenPercentRepliesRetainExactWholeLinePostings() = runTest {
        listOf(INCLUSIVE_FOURTEEN, EXCLUSIVE_FOURTEEN).forEach { expected ->
            val seam = FoodPricePreviewFrozenGatewaySeam(expected)
            val input = frozenGatewayInput(expected)
            val reply = assertIs<LocalFoodPricePreviewResult.Available>(seam.gateway.previewFood(input))
            assertFrozenGatewayPreview(reply.preview, input, expected)
            assertFrozenGatewayRead(seam, expected)
            assertNoGatewayBusinessEntry(seam)
        }
    }

    @Test
    fun fqp05DeniedSelectionReplyIsExplicitAndNeverPublishesACompletePreviewOrTouchesJournal() = runTest {
        val seam = FoodPricePreviewFrozenGatewaySeam(ORIGINAL_TWO, FrozenGatewayReply.DENIED)
        val input = frozenGatewayInput(ORIGINAL_TWO)
        val before = input.snapshot()
        val reply = assertIs<LocalFoodPricePreviewResult.Unavailable>(seam.gateway.previewFood(input))
        assertEquals(LocalServiceProblem.DENIED, reply.problem)
        assertTrue(input.sameSelection(before))
        assertFrozenGatewayRead(seam, ORIGINAL_TWO)
        assertNoGatewayBusinessEntry(seam)
    }

    @Test
    fun fqp06LookalikeOrderProductVariantOrOptionProjectionCannotSubstituteOriginalInput() = runTest {
        FrozenGatewayLookalike.entries.forEach { lookalike ->
            val seam = FoodPricePreviewFrozenGatewaySeam(ORIGINAL_TWO, lookalike = lookalike)
            val input = frozenGatewayInput(ORIGINAL_TWO)
            val before = input.snapshot()
            assertIs<LocalFoodPricePreviewResult.Unavailable>(seam.gateway.previewFood(input))
            assertTrue(input.sameSelection(before))
            assertNoGatewayBusinessEntry(seam)
        }
    }

    @Test
    fun fqp07StorageTruncationAndPartitionReplyShapesNeverBecomeZeroOrPartialAvailablePreview() = runTest {
        listOf(FrozenGatewayReply.STORAGE_UNCERTAIN, FrozenGatewayReply.TRUNCATED, FrozenGatewayReply.PARTITION).forEach { failure ->
            val seam = FoodPricePreviewFrozenGatewaySeam(ORIGINAL_TWO, failure)
            val input = frozenGatewayInput(ORIGINAL_TWO)
            val before = input.snapshot()
            assertIs<LocalFoodPricePreviewResult.Unavailable>(seam.gateway.previewFood(input))
            assertTrue(input.sameSelection(before))
            assertFrozenGatewayRead(seam, ORIGINAL_TWO)
            assertNoGatewayBusinessEntry(seam)
        }
    }

    @Test
    fun fqp07CanceledReadPublishesNoCompletePreviewAndNeverAdoptsABusinessOperation() = runTest {
        val seam = FoodPricePreviewFrozenGatewaySeam(ORIGINAL_TWO, FrozenGatewayReply.CANCELED)
        val input = frozenGatewayInput(ORIGINAL_TWO)
        val before = input.snapshot()
        var completePreview = false
        try {
            completePreview = seam.gateway.previewFood(input) is LocalFoodPricePreviewResult.Available
        } catch (_: CancellationException) {
            // Cancellation is not evidence that a real accepted operation did not commit.
        }
        assertFalse(completePreview)
        assertTrue(input.sameSelection(before))
        assertFrozenGatewayRead(seam, ORIGINAL_TWO)
        assertNoGatewayBusinessEntry(seam)
    }

    @Test
    fun fqp07RetiredCapturedResponseContextCannotPublishTheSuppliedPrice() = runTest {
        val seam = FoodPricePreviewFrozenGatewaySeam(ORIGINAL_TWO, FrozenGatewayReply.RETIRED_CONTEXT)
        val input = frozenGatewayInput(ORIGINAL_TWO)
        val before = input.snapshot()
        assertIs<LocalFoodPricePreviewResult.Unavailable>(seam.gateway.previewFood(input))
        assertTrue(input.sameSelection(before))
        assertFrozenGatewayRead(seam, ORIGINAL_TWO)
        assertNoGatewayBusinessEntry(seam)
    }
}

private val FROZEN_SCOPE = BranchScope(
    DomainId("a0000000-0000-4000-8000-000000000001"),
    DomainId("a0000000-0000-4000-8000-000000000002"),
    DomainId("a0000000-0000-4000-8000-000000000004"),
)
private val FROZEN_ACTOR = DomainId("b0000000-0000-4000-8000-000000000001")
private val FROZEN_DEVICE = DomainId("d0000000-0000-4000-8000-000000000001")
private val FROZEN_SESSION = DomainId("e0000000-0000-4000-8000-000000000001")
private val FROZEN_ORDER = DomainId("f0000000-0000-4000-8000-000000000001")
private val FROZEN_LINE = DomainId("f3000000-0000-4000-8000-000000000001")
// Declared synthetic serving/UOM/clock bindings, not preparation-task or storage-row oracles.
private val FROZEN_SECOND_SERVING = DomainId("f3000000-0000-4000-8000-000000000002")
private val FROZEN_UNIT = DomainId("f6000000-0000-4000-8000-000000000001")
private val FROZEN_BOOT = DomainId("f6000000-0000-4000-8000-000000000002")
private val FROZEN_PRODUCT = DomainId("f1000000-0000-4000-8000-000000000001")
private val FROZEN_VARIANT = DomainId("f1000000-0000-4000-8000-000000000002")
private val FROZEN_CHEESE = DomainId("f1000000-0000-4000-8000-000000000003")
private val FROZEN_TAX = DomainId("f7000000-0000-4000-8000-000000000001")
private val FROZEN_TIME = UtcInstant.parseCanonical("2026-10-30T09:00:00Z")
private const val FROZEN_ITEM_NOTE = "no salt / بدون ملح"
private const val FROZEN_SEAT_NOTE = "Seat 02 / المقعد 02"
private const val FROZEN_OPTION_ENGLISH = "Cheese"
private const val FROZEN_OPTION_ARABIC = "جبنة"

private data class FrozenGatewayPrice(val catalog: Long, val recipe: Long, val servings: Long,
    val selectedMajor: String, val unitMinor: Long, val inclusion: TaxInclusion,
    val ratePercent: String, val payableMinor: Long, val netMinor: Long, val taxMinor: Long)

// Literal independent frozen amounts. Selected major-unit price is only a supplied input.
private val ORIGINAL_TWO = FrozenGatewayPrice(5, 1, 2, "110", 11000, TaxInclusion.INCLUSIVE, "0", 22000, 22000, 0)
private val CURRENT_TWO = FrozenGatewayPrice(6, 2, 2, "140", 14000, TaxInclusion.INCLUSIVE, "0", 28000, 28000, 0)
private val TIER_ONE = FrozenGatewayPrice(5, 1, 1, "110", 11000, TaxInclusion.INCLUSIVE, "0", 11000, 11000, 0)
private val TIER_TWO = FrozenGatewayPrice(5, 1, 2, "100", 10000, TaxInclusion.INCLUSIVE, "0", 20000, 20000, 0)
private val INCLUSIVE_FOURTEEN = FrozenGatewayPrice(5, 1, 2, "114", 11400, TaxInclusion.INCLUSIVE, "14", 22800, 20000, 2800)
private val EXCLUSIVE_FOURTEEN = FrozenGatewayPrice(5, 1, 2, "100", 10000, TaxInclusion.EXCLUSIVE, "14", 22800, 20000, 2800)

private enum class FrozenGatewayReply { COMPLETE, DENIED, STORAGE_UNCERTAIN, TRUNCATED, PARTITION, CANCELED, RETIRED_CONTEXT }
private enum class FrozenGatewayLookalike { ORDER, PRODUCT, VARIANT, OPTION }

private fun frozenGatewayInput(price: FrozenGatewayPrice): LocalFoodPreviewInput {
    fun field(key: LocalField, value: LocalScalarValue) = LocalScalarInput(key, value)
    val values = listOf(
        field(LocalField.ORDER_ID, LocalScalarValue.Identifier(FROZEN_ORDER)),
        field(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(7)),
        field(LocalField.EXPECTED_CATALOG_REVISION, LocalScalarValue.Integer(price.catalog)),
    )
    val line = mutableListOf(
        field(LocalField.RECIPE_ID, LocalScalarValue.Identifier(FROZEN_VARIANT)),
        field(LocalField.EXPECTED_FOOD_RECIPE_REVISION, LocalScalarValue.Integer(price.recipe)),
        field(LocalField.SKU_ID, LocalScalarValue.Identifier(FROZEN_PRODUCT)),
        field(LocalField.CHOICE_ID, LocalScalarValue.Identifier(FROZEN_CHEESE)),
        field(LocalField.UNIT, LocalScalarValue.Identifier(FROZEN_UNIT)),
        field(LocalField.QUANTITY, LocalScalarValue.Decimal("1")),
        field(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(0)),
        field(LocalField.MODIFIER_IDS, LocalScalarValue.Text(FROZEN_CHEESE.value)),
        field(LocalField.NAME_ENGLISH, LocalScalarValue.Text(FROZEN_OPTION_ENGLISH)),
        field(LocalField.NAME_ARABIC, LocalScalarValue.Text(FROZEN_OPTION_ARABIC)),
        field(LocalField.ITEM_NOTE, LocalScalarValue.Text(FROZEN_ITEM_NOTE)),
        field(LocalField.LINE_SEAT_NOTE, LocalScalarValue.Text(FROZEN_SEAT_NOTE)),
        field(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(price.servings)),
    )
    if (price.servings == 2L) line += field(LocalField.FOOD_PORTION_IDS,
        LocalScalarValue.Text("${FROZEN_LINE.value},${FROZEN_SECOND_SERVING.value}"))
    return LocalFoodPreviewInput(LocalAction.FOOD_ADD, 1, values, listOf(LocalInputLine(FROZEN_LINE, line)))
}

private fun assertFrozenGatewayPreview(preview: LocalFoodPricePreview, input: LocalFoodPreviewInput, price: FrozenGatewayPrice) {
    assertTrue(preview.input.sameSelection(input))
    assertEquals(listOf(FROZEN_LINE), preview.lines.map { it.lineId })
    val line = preview.lines.single()
    assertEquals(Quantity(FROZEN_UNIT, price.servings, 0), line.quantity)
    assertEquals(Money(Currency.EGP, price.unitMinor), line.unitPrice.toFraction().toMoney(Currency.EGP))
    assertEquals(ExactRational.ratio(price.unitMinor, 100L), line.unitPrice.toFraction())
    assertEquals(price.inclusion == TaxInclusion.INCLUSIVE, line.taxIncluded)
    assertEquals(Money(Currency.EGP, price.payableMinor), line.payable)
    assertEquals(Money(Currency.EGP, price.netMinor), line.net)
    assertEquals(Money(Currency.EGP, price.taxMinor), line.tax)
    assertEquals(Money(Currency.EGP, price.payableMinor), preview.payable)
    assertEquals(Money(Currency.EGP, price.payableMinor), preview.beforeDiscount)
    assertEquals(Money(Currency.EGP, price.netMinor), preview.net)
    assertEquals(Money(Currency.EGP, price.taxMinor), preview.tax)
    assertEquals(Money(Currency.EGP, 0), preview.discount)
    assertEquals(Money(Currency.EGP, 0), preview.charges)
}

private fun assertFrozenGatewayRead(seam: FoodPricePreviewFrozenGatewaySeam, price: FrozenGatewayPrice) {
    assertTrue(seam.port.selections.isNotEmpty())
    seam.port.selections.forEach { read ->
        assertEquals(LocalFoodSelectionPreviewCodecV1.QUERY, read.query)
        val request = assertIs<LocalFoodRequestV1.Add>(LocalFoodCodecV1.decode(read.payload))
        assertEquals(FROZEN_ORDER, request.orderId)
        assertEquals(Revision(7), request.expectedOrderRevision)
        assertEquals(Revision(price.catalog), request.expectedCatalogRevision)
        assertEquals(LocalFoodSelectionPreviewCodecV1.encode(request), read.payload)
        val group = LocalFoodSelectionGroups.group(request.portions).single()
        assertEquals(FROZEN_LINE, group.lineId)
        assertEquals(Quantity(FROZEN_UNIT, price.servings, 0), group.totalQuantity)
        assertTrue(group.portions.all { it.commercialLineId == FROZEN_LINE && it.itemId == FROZEN_PRODUCT &&
            it.recipeId == FROZEN_VARIANT && it.expectedRecipeRevision == Revision(price.recipe) &&
            it.choiceId == FROZEN_CHEESE && it.modifierIds == listOf(FROZEN_CHEESE) &&
            it.quantity == Quantity(FROZEN_UNIT, 1, 0) && it.itemNote == FROZEN_ITEM_NOTE && it.seatNote == FROZEN_SEAT_NOTE })
    }
}

private fun assertNoGatewayBusinessEntry(seam: FoodPricePreviewFrozenGatewaySeam) {
    assertTrue(seam.journal.calls.isEmpty(), "Preview entered a journal method")
    assertTrue(seam.port.forbiddenCalls.isEmpty(), "Preview entered an operation or authentication mutation")
}

private class FoodPricePreviewFrozenGatewaySeam(val price: FrozenGatewayPrice,
    val reply: FrozenGatewayReply = FrozenGatewayReply.COMPLETE, val lookalike: FrozenGatewayLookalike? = null) {
    var currentLifetime: Boolean = true
    val journal = FoodPricePreviewFrozenRefusingJournal()
    val port = FoodPricePreviewFrozenProjectionPort(this)
    val gateway = LocalEndpointWorkflowGateway(port, journal, LocalWorkflowCredentialSource {
        // Inert owned wrapper for this unit seam; no credential is issued or verified.
        LocalWorkflowCredentialLease(LocalGatewayCredentials(FROZEN_DEVICE, OpaqueCredential(ByteArray(16)))) { currentLifetime }
    }, LocalWorkflowInstallation(setOf(LocalFoodProfileV1.COMMAND),
        setOf(LocalFoodProfileV1.QUERY, LocalFoodMenuCodecV1.QUERY, LocalFoodSelectionPreviewCodecV1.QUERY)))
}

/** Throws on every journal entry, including reads; this is not accepting or durable storage. */
private class FoodPricePreviewFrozenRefusingJournal : LocalClientIntentJournal(LocalClientIdentity(FROZEN_SCOPE, FROZEN_ACTOR, FROZEN_DEVICE)) {
    val calls = mutableListOf<String>()
    private fun refused(name: String): Nothing { calls += name; error("Unexpected preview journal entry") }
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> = refused("find")
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = refused("pending")
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite = refused("stage")
    override suspend fun markDispatched(operationId: OperationId): JournalWrite = refused("markDispatched")
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = refused("remember")
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = refused("acknowledge")
    override suspend fun close(): Unit = refused("close")
}

/** Public read-reply shape only. No protected prepare/project, authority or persisted result is executed. */
private class FoodPricePreviewFrozenProjectionPort(private val seam: FoodPricePreviewFrozenGatewaySeam) : LocalMasterClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    val selections = mutableListOf<ReadRequest>()
    val forbiddenCalls = mutableListOf<String>()
    private fun refused(name: String): Nothing { forbiddenCalls += name; error("Unexpected preview operation entry") }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = refused("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = refused("refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = refused("logout")
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply = refused("execute")
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply = refused("lookup")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        val hints = listOf(SecurityAction.ORDER_READ, SecurityAction.ORDER_EDIT, SecurityAction.CATALOG_READ).map {
            PermissionGrant(Permission(it, PermissionVerb.PERFORM), FROZEN_SCOPE, GrantEffect.ALLOW)
        }
        val policy = ActivatedLocalPolicy(FROZEN_SCOPE, FROZEN_ACTOR, Revision(10), hints, LocalSessionPolicy(), ThrottlePolicy())
        return LocalSessionViewResult.Current(LocalSessionView(FROZEN_ACTOR, FROZEN_SCOPE, FROZEN_DEVICE, FROZEN_SESSION,
            SessionGeneration(0), LocalLoginIdentifier("preview-owner"), policy,
            MonotonicMoment(FROZEN_BOOT, 60_000), MonotonicMoment(FROZEN_BOOT, 28_800_000)))
    }

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply = when (request.query) {
        LocalFoodProfileV1.QUERY -> {
            val order = if (seam.lookalike == FrozenGatewayLookalike.ORDER) DomainId("f0000000-0000-4000-8000-000000000002") else FROZEN_ORDER
            val summary = LocalFoodOrderSummaryV1(order, FROZEN_ACTOR, Revision(7), LocalSalesActivity.RESTAURANT,
                LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "T-02"), 0, null, null)
            val detail = LocalFoodOrderDetailV1(summary, emptyList(), Money(Currency.EGP, 0), false)
            data(StoredBytes(frozenGatewayJson.encodeToString(LocalFoodReadResultV1.serializer(),
                LocalFoodReadResultV1(detail = detail)).encodeToByteArray()))
        }
        LocalFoodMenuCodecV1.QUERY -> {
            val product = if (seam.lookalike == FrozenGatewayLookalike.PRODUCT) DomainId("f1000000-0000-4000-8000-000000000004") else FROZEN_PRODUCT
            val variant = if (seam.lookalike == FrozenGatewayLookalike.VARIANT) DomainId("f1000000-0000-4000-8000-000000000005") else FROZEN_VARIANT
            val option = if (seam.lookalike == FrozenGatewayLookalike.OPTION) DomainId("f1000000-0000-4000-8000-000000000006") else FROZEN_CHEESE
            val choice = LocalFoodMenuChoiceV1(option, LocalNamesV1(FROZEN_OPTION_ENGLISH, FROZEN_OPTION_ARABIC), listOf(FROZEN_CHEESE), FROZEN_CHEESE)
            val recipe = LocalFoodMenuRecipeV1(variant, Revision(seam.price.recipe), product, Quantity(FROZEN_UNIT, 1, 0), true, listOf(choice))
            val page = LocalFoodMenuPageV1(Revision(seam.price.catalog), listOf(recipe), null)
            data(StoredBytes(frozenGatewayJson.encodeToString(LocalFoodMenuPageV1.serializer(), page).encodeToByteArray()))
        }
        LocalFoodSelectionPreviewCodecV1.QUERY -> {
            selections += request
            when (seam.reply) {
                FrozenGatewayReply.DENIED -> ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
                FrozenGatewayReply.STORAGE_UNCERTAIN -> ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN)
                FrozenGatewayReply.TRUNCATED -> data(StoredBytes("{".encodeToByteArray()))
                FrozenGatewayReply.PARTITION -> error("Controlled projection unavailable")
                FrozenGatewayReply.CANCELED -> throw CancellationException("Controlled preview cancellation")
                FrozenGatewayReply.COMPLETE, FrozenGatewayReply.RETIRED_CONTEXT -> {
                    val selected = LocalFoodCodecV1.decode(request.payload) as LocalFoodRequestV1.Add
                    val tax = TaxSnapshot(FROZEN_TAX, Revision(1), "ILLUSTRATIVE", PercentageRate.parse(seam.price.ratePercent))
                    val resolved = selected.portions.map { ResolvedPriceLine(it.commercialLineId, it.portionId,
                        Revision(seam.price.catalog), it.quantity, UnitPrice.parse(Currency.EGP, seam.price.selectedMajor), seam.price.inclusion, tax) }
                    val quote = OperationalPricingEngine.calculate(PricingRequest(FROZEN_ORDER, Revision(0), Currency.EGP, resolved))
                    val bytes = StoredBytes(frozenGatewayJson.encodeToString(FoodPricePreviewFrozenGatewayWire.serializer(),
                        FoodPricePreviewFrozenGatewayWire.Available(sha256(request.payload.copyBytes()),
                            StoredPricingSnapshotCodec.encode(quote))).encodeToByteArray())
                    if (seam.reply == FrozenGatewayReply.RETIRED_CONTEXT) seam.currentLifetime = false
                    data(bytes)
                }
            }
        }
        else -> refused("unexpectedRead")
    }

    // Uses only the existing JVM test friendship; this constructor is not a qualified Master cut.
    private fun data(bytes: StoredBytes): ReadReply.Data = ReadReply.Data(bytes, Revision(7), FROZEN_TIME)
}

private val frozenGatewayJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

/** Binding for the AVAILABLE result format, not an authority or replacement private API. */
@Serializable
private sealed interface FoodPricePreviewFrozenGatewayWire {
    @Serializable @SerialName("AVAILABLE")
    data class Available(val requestDigest: PayloadDigest, val pricing: String) : FoodPricePreviewFrozenGatewayWire
}
