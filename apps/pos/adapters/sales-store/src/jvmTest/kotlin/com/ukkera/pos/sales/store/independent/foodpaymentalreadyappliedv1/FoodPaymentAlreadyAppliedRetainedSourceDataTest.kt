package com.ukkera.pos.sales.store.independent.foodpaymentalreadyappliedv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen FAA v1.0.0 provider/literal SUPPORT, not a genuine ordinary AlreadyApplied
 * source. All retained commands and capsules below are explicitly unqualified DATA.
 * The production payment provider and pure verifier are called. Unassigned
 * dependencies throw; no accepting native/source/authority port is supplied.
 *
 * Encoders construct inputs only; no implementation bytes/digest become an oracle
 * (FAA-Q04). Exact native acquisition, positive complete Start/raw/Apply history,
 * Master-current authorization, financial/stock effects, ACK, RPC and restart are
 * unbound. Null requirements and integrity rejection do not prove original absence.
 */
class FoodPaymentAlreadyAppliedRetainedSourceDataTest {
    private val f = AlreadyAppliedSalesData()
    private val recipes = UncalledAlreadyAppliedRecipes()
    private val handler = LocalFoodPaymentHandler(LocalStockRepository(), recipes.value)
    private val profile = LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL

    @Test
    fun faa010MissingNativeBeforeCannotDiscloseOtherwiseMatchingRetainedData() = runTest {
        val original = f.original()
        val view = AlreadyAppliedDataView(f.scope, mapOf(f.operation to original))
        assertNull(handler.rpcRetainedResultRequirements(profile, f.body, view, f.context()))
        assertEquals(listOf(f.operation), view.retainedReads)
        assertEquals(listOf(f.operation), view.beforeReads)
        view.assertNoCurrentFallback()
        view.assertDataUnchanged()
        assertEquals(0, recipes.calls)
    }

    @Test
    fun faa010ReadableCurrentPaidApplicationCannotReplaceMissingNativeBefore() = runTest {
        val current = f.applicationRow()
        val view = AlreadyAppliedDataView(f.scope, mapOf(f.operation to f.original()), rows = mapOf(current.key to current))
        assertNull(handler.rpcRetainedResultRequirements(profile, f.body, view, f.context()))
        assertEquals(listOf(f.operation), view.beforeReads)
        view.assertNoCurrentFallback()
        view.assertDataUnchanged()
        assertEquals(0, recipes.calls)
        // APPLIED JSON is current comparison DATA, not accounting, EGP drawer
        // evidence, genuine original BEFORE or the earlier successful producer.
    }

    @Test
    fun faa010UnavailableOrCorruptNativeReadCannotYieldRequirements() = runTest {
        val original = f.original()
        for (failure in LocalSalesSourceFailure.entries) {
            val view = AlreadyAppliedDataView(f.scope, mapOf(f.operation to original),
                sourceFailure = LocalSalesSourceUnavailable(failure))
            assertNull(handler.rpcRetainedResultRequirements(profile, f.body, view, f.context()))
            assertEquals(listOf(f.operation), view.beforeReads)
            view.assertNoCurrentFallback()
            view.assertDataUnchanged()
        }
        assertEquals(0, recipes.calls)
        // Controlled unavailable/invalid source observations only; no corrupted
        // actual native archive, transport or writer-qualified absence is exercised.
    }

    @Test
    fun faa010UnsupportedRefusalNeverUsesAlreadyAppliedNativeSource() = runTest {
        val result = LocalFoodPaymentCodec.result(LocalFoodPaymentResult.Pending(f.target,
            LocalFoodPaymentPendingReason.SOURCE_UNAVAILABLE))
        val view = AlreadyAppliedDataView(f.scope, mapOf(f.operation to f.original(
            code = "food.payment.pending", result = result)))
        assertNull(handler.rpcRetainedResultRequirements(profile, f.body, view, f.context()))
        assertEquals(listOf(f.operation), view.retainedReads)
        assertTrue(view.beforeReads.isEmpty())
        view.assertNoCurrentFallback()
        view.assertDataUnchanged()
        assertEquals(0, recipes.calls)
    }

    @Test
    fun faa005ForeignRetainedCoordinatesCannotAdoptOriginalData() = runTest {
        val original = f.original()
        val candidates = listOf(
            f.original(actor = f.id("actor_b"), digest = original.semanticDigest),
            f.original(operation = f.wrongOperation),
            f.original(scope = f.scope.copy(branchId = f.id("other_branch")), digest = original.semanticDigest),
            f.original(scope = f.scope.copy(companyId = f.id("other_company")), digest = original.semanticDigest),
            f.original(command = CommandKey(f.command.name, 2), digest = original.semanticDigest),
            f.original(body = LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.ApplyOriginal(f.wrongOperation)),
                digest = original.semanticDigest),
        )
        for (candidate in candidates) {
            val view = AlreadyAppliedDataView(f.scope, mapOf(f.operation to candidate))
            assertNull(handler.rpcRetainedResultRequirements(profile, f.body, view, f.context()))
            assertEquals(listOf(f.operation), view.retainedReads)
            assertTrue(view.beforeReads.isEmpty())
            view.assertNoCurrentFallback()
            view.assertDataUnchanged()
        }
        assertEquals(0, recipes.calls)
        // Scalar coordinates only; native order/collection/session/attempt
        // substitutions and full immutable canonical-byte identity stay unbound.
    }

    @Test
    fun faa005faa010ForeignOrCorruptSourceCapsuleCannotAdoptRetainedData() = runTest {
        val original = f.original()
        val changedResult = StoredBytes("Unqualified changed original result".encodeToByteArray())
        val candidates = listOf(
            f.original(actor = f.id("actor_b"), digest = original.semanticDigest),
            f.original(operation = f.wrongOperation),
            f.original(scope = f.scope.copy(branchId = f.id("other_branch")), digest = original.semanticDigest),
            f.original(scope = f.scope.copy(companyId = f.id("other_company")), digest = original.semanticDigest),
            f.original(digest = f.otherDigest),
            f.original(result = changedResult),
        )
        for (candidate in candidates) {
            val capsule = f.sources(candidate) // Syntactic DATA, never a native read capability.
            assertEquals(RuntimeNativeLiteralBusinessProvenance.UNQUALIFIED, capsule.beforeApplication.businessProvenance)
            val view = AlreadyAppliedDataView(f.scope, mapOf(f.operation to original), source = capsule)
            assertFailsWith<IllegalStateException> {
                handler.rpcRetainedResultRequirements(profile, f.body, view, f.context())
            }
            assertEquals(listOf(f.operation), view.retainedReads)
            assertEquals(listOf(f.operation), view.beforeReads)
            view.assertNoCurrentFallback()
            view.assertDataUnchanged()
        }
        assertEquals(0, recipes.calls)
        // The actual new source/original equality boundary rejects DATA mismatch;
        // exception-to-RPC-unavailable mapping is outside this provider test.
    }

    @Test
    fun faa010MatchingCapsuleStillRequiresActualEarlierAppliedProducer() = runTest {
        val original = f.original()
        val capsule = f.sources(original)
        val view = AlreadyAppliedDataView(f.scope,
            mapOf(f.operation to original, f.precedingApply to null), source = capsule)
        assertFailsWith<IllegalStateException> {
            handler.rpcRetainedResultRequirements(profile, f.body, view, f.context())
        }
        assertEquals(listOf(f.operation, f.precedingApply), view.retainedReads)
        assertEquals(listOf(f.operation), view.beforeReads)
        view.assertNoCurrentFallback()
        view.assertDataUnchanged()
        assertEquals(0, recipes.calls)
        // Matching capsule syntax reaches real predecessor loading, which refuses
        // missing custody. This is not positive AlreadyApplied source disclosure.
    }

    @Test
    fun faa010PureVerifierRejectsMissingNativeOriginalDespiteTypedApplicationData() = runTest {
        val original = f.original()
        val application = f.applicationRow()
        val before = application.payload.copyBytes()
        val unqualifiedApplied = f.original(code = "committed", operation = f.precedingApply, sequence = 1)
        val unqualifiedStart = f.original(code = "committed", operation = f.target.intentOperationId, sequence = 1)
        val unqualifiedRaw = f.original(code = "committed", operation = f.recording, sequence = 1)
        assertNull(unqualifiedRaw.nativeOriginalProvenance)
        assertFailsWith<IllegalStateException> {
            requireFoodPaymentAlreadyApplied(original, application, unqualifiedApplied, unqualifiedStart, unqualifiedRaw)
        }
        assertContentEquals(before, application.payload.copyBytes())
        assertTrue(original.aggregateWrites.isEmpty())
        assertTrue(original.claimChanges.isEmpty())
        assertTrue(original.jobs.isEmpty())
        assertEquals(0, recipes.calls)
        // Pure authentic-source prerequisite only. No fabricated native tag is
        // attached to bypass the missing original or complete earlier producers.
    }

    @Test
    fun faa011RequesterCancellationPreservesSuppliedOriginalData() = runTest {
        val original = f.original()
        val view = AlreadyAppliedDataView(f.scope, mapOf(f.operation to original),
            sourceFailure = CancellationException("Controlled requester cancellation"))
        assertFailsWith<CancellationException> {
            handler.rpcRetainedResultRequirements(profile, f.body, view, f.context())
        }
        assertEquals(listOf(f.operation), view.beforeReads)
        view.assertNoCurrentFallback()
        view.assertDataUnchanged()
        assertEquals(f.operation, original.operationId)
        assertEquals(f.actor, original.originalActorId)
        assertEquals(0, recipes.calls)
        // Cancellation propagation/byte conservation only; genuine retained
        // native lifetime and subsequent authorized A recovery remain unbound.
    }
}

private class AlreadyAppliedSalesData {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/food-payment-already-applied-v1") }
        .first { Files.isRegularFile(it.resolve("fixtures.json")) }
    private val root = Files.readAllBytes(directory.resolve("fixtures.json")).let { bytes ->
        check(sha256(bytes).value == "91ef569172f09e39906996c893bdc19a7e4eb178e07826e4443fbc87b92c9785")
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }.also { check(it.getValue("fixture_version").jsonPrimitive.content == "1.0.0") }
    private val ids = root.getValue("ids").jsonObject
    val scope = BranchScope(id("organization"), id("food_company"), id("branch"))
    val actor = id("actor_a")
    val operation = OperationId(id("operation").value)
    val recording = OperationId(id("original_recording").value)
    val wrongOperation = OperationId(id("wrong_identity").value)
    val command = CommandKey("sales.food.payment", 1)
    val target = LocalFoodPaymentTarget(id("order"), id("collection"), id("cash_session_a"), id("attempt"),
        syntaxOperation("Start"), recording, syntaxOperation("Resolution"))
    val precedingApply = syntaxOperation("Earlier Apply")
    val body = LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.ApplyOriginal(recording))
    val syntaxDigest = sha256("Unqualified FAA source syntax filler".encodeToByteArray())
    val otherDigest = sha256("Different unqualified FAA source syntax filler".encodeToByteArray())
    private val at = UtcInstant.parseCanonical("2026-11-06T00:00:00Z") // Unasserted syntax time.
    fun id(label: String) = DomainId(ids.getValue(label).jsonPrimitive.content)
    private fun syntaxOperation(label: String) = OperationId(UUID.nameUUIDFromBytes(
        "FAA unasserted DATA syntax:$label".encodeToByteArray()).toString())
    fun context() = CommandContext(scope, actor, operation, command, semanticDigest(scope, actor, command, body))

    fun original(scope: BranchScope = this.scope, actor: DomainId = this.actor, operation: OperationId = this.operation,
        command: CommandKey = this.command, body: StoredBytes = this.body,
        digest: PayloadDigest = semanticDigest(scope, actor, command, body), code: String = "food.payment.already_applied",
        result: StoredBytes = LocalFoodPaymentCodec.result(LocalFoodPaymentResult.AlreadyApplied(recording, precedingApply)),
        sequence: Long = 2) = RetainedCommandEvidence(scope, operation, actor, command, id("wrong_identity"), Revision(1),
            JournalSequence(sequence), at, id("auth_session_a"), Revision(7), digest, code, body, result,
            sha256(result.copyBytes()), syntaxDigest, syntaxDigest, emptyList(), emptyList(), emptyList())

    fun applicationRow(): StoredAggregate {
        val value = LocalFoodPaymentApplication(1, scope, target, actor, Revision(1), LocalFoodPaymentApplicationState.APPLIED,
            null, at, precedingApply, LocalFoodPaymentAction.CASH_PAID, emptyList())
        val payload = localSalesEncode(value, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
        return StoredAggregate(foodPaymentApplicationKey(recording), Revision(2),
            LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION), payload, sha256(payload.copyBytes()))
    }

    fun sources(original: RetainedCommandEvidence): RuntimeHistoricalFoodPaymentAlreadyAppliedSources {
        val row = applicationRow()
        val before = RuntimeNativeLiteralAggregateSource(row, RetainedOriginalV2.Legacy(this.original(
            scope = original.scope, code = "committed", operation = precedingApply, sequence = 1)), 1)
        val requirement = AuthorizationRequirement(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), original.scope,
            ActionBinding(id("original_recording"), Revision(1), original.semanticDigest, original.operationId,
                "food.payment.already_applied"), LimitUse.NotApplicable, OwnerConstraint.OwnerPrivate(original.originalActorId))
        val resources = listOf(NativeHistoryOriginalResourceV2(3, NativeHistoryResourceKindV2.CASH_CUSTODY,
            id("cash_session_a"), true, original.semanticDigest))
        return RuntimeHistoricalFoodPaymentAlreadyAppliedSources(original, requirement, resources, before,
            listOf(before), emptyList(), emptySet(), emptySet())
    }
}

/** Denial-only immutable observations. No native/runtime/stock capability is implemented. */
private class AlreadyAppliedDataView(
    override val scope: BranchScope,
    originals: Map<OperationId, RetainedCommandEvidence?>,
    private val source: RuntimeHistoricalFoodPaymentAlreadyAppliedSources? = null,
    private val sourceFailure: Throwable? = null,
    rows: Map<AggregateKey, StoredAggregate> = emptyMap(),
) : CommandReadView {
    private val originals = originals.toMap()
    private val rows = rows.toMap()
    private val originalBytes = originals.mapValues { (_, value) -> value?.let { it.normalized.copyBytes() to it.result.copyBytes() } }
    private val rowBytes = rows.mapValues { it.value.payload.copyBytes() }
    val retainedReads = mutableListOf<OperationId>()
    val beforeReads = mutableListOf<OperationId>()
    private val aggregateReads = mutableListOf<AggregateKey>()
    private val claimReads = mutableListOf<ClaimKey>()
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        aggregateReads += key
        return rows[key] ?: throw AssertionError("Unassigned current aggregate read")
    }
    override suspend fun claim(key: ClaimKey): DomainId? {
        claimReads += key
        throw AssertionError("Unassigned current claim read")
    }
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
        retainedReads += operationId
        if (operationId !in originals) throw AssertionError("Unassigned retained original read")
        return originals[operationId]
    }
    override suspend fun foodPaymentAlreadyAppliedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodPaymentAlreadyAppliedSources? {
        beforeReads += operationId
        if (operationId !in originals) throw AssertionError("Unassigned original-before read")
        sourceFailure?.let { throw it }
        return source
    }
    fun assertNoCurrentFallback() {
        assertTrue(aggregateReads.isEmpty())
        assertTrue(claimReads.isEmpty())
    }
    fun assertDataUnchanged() {
        for ((operation, before) in originalBytes) if (before != null) {
            val after = assertNotNull(originals[operation])
            assertContentEquals(before.first, after.normalized.copyBytes())
            assertContentEquals(before.second, after.result.copyBytes())
        }
        for ((key, before) in rowBytes) assertContentEquals(before, assertNotNull(rows[key]).payload.copyBytes())
    }
}

private class UncalledAlreadyAppliedRecipes {
    var calls = 0
        private set
    val value = Proxy.newProxyInstance(TransactionFoodStockSource::class.java.classLoader,
        arrayOf(TransactionFoodStockSource::class.java)) { _, _, _ ->
        calls++
        throw AssertionError("No genuine FOOD recipe/authority source is supplied")
    } as TransactionFoodStockSource
}
