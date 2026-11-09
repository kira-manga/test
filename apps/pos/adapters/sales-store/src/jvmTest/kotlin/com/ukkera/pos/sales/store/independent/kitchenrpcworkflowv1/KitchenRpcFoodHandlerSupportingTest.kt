@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.kitchenrpcworkflowv1

import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterRpcRetainedResultAccessProvider
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.UnavailableLocalStockAuthority
import com.ukkera.pos.sales.FoodIngredientReturnDisposition
import com.ukkera.pos.sales.store.LocalFoodCodecV1
import com.ukkera.pos.sales.store.LocalFoodDraftCreateV1
import com.ukkera.pos.sales.store.LocalFoodFinancialRequestV1
import com.ukkera.pos.sales.store.LocalFoodHandler
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodProfileV1
import com.ukkera.pos.sales.store.LocalFoodQueryV1
import com.ukkera.pos.sales.store.LocalFoodReadCodecV1
import com.ukkera.pos.sales.store.LocalFoodReadHandler
import com.ukkera.pos.sales.store.LocalFoodRequestV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalFoodStationActionV1
import com.ukkera.pos.sales.store.LocalFoodStockCodecV2
import com.ukkera.pos.sales.store.LocalFoodStockHandler
import com.ukkera.pos.sales.store.LocalFoodStockRequestV2
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.TransactionFoodStockSource
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KRPC-001 typed-selector/body support and KRPC-006 original requirement support.
 * KBA F-SELECTORS/F-BOARD-Q/F-BOARD-R identities and initial revisions are unchanged.
 * Existing production codecs bind the semantic fixtures; no replacement wire format.
 *
 * The real handlers have a fail-on-use recipe port and an unobserved real stock
 * repository. CommandContext is inert DATA for requirement construction only.
 * No prepare/evaluate, current principal/grant, native source, database, retained
 * committed result, progressed task, RPC, effect or whole L3 outcome is supplied.
 */
class KitchenRpcFoodHandlerSupportingTest {
    @Test
    fun krpc001FoodReadClassifierKeepsExactStationAndTaskSelectors() {
        val fixture = KitchenFoodFixture()
        val queries = listOf(LocalFoodQueryV1.Station(KitchenFoodInputs.station),
            LocalFoodQueryV1.Station(KitchenFoodInputs.station, KitchenFoodInputs.cursor),
            LocalFoodQueryV1.Task(KitchenFoodInputs.station, KitchenFoodInputs.task))
        queries.forEach { query ->
            val bytes = LocalFoodReadCodecV1.encode(query)
            assertEquals(query, LocalFoodReadCodecV1.decode(bytes))
            assertEquals(bytes, fixture.read.normalize(bytes))
            assertTrue(fixture.read.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, bytes))
            LocalMasterRpcWorkflowProfile.entries.filter { it != LocalMasterRpcWorkflowProfile.KITCHEN }.forEach { profile ->
                assertFalse(fixture.read.supportsRpcWorkflow(profile, bytes), profile.name)
            }
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun krpc001FoodReadClassifierExcludesOrderOwnAndHandoffOnTheSharedKey() {
        val fixture = KitchenFoodFixture()
        listOf(LocalFoodQueryV1.Order(KitchenFoodInputs.order), LocalFoodQueryV1.Own(),
            LocalFoodQueryV1.Handoff(KitchenFoodInputs.order)).forEach { query ->
            val bytes = LocalFoodReadCodecV1.encode(query)
            assertEquals(bytes, fixture.read.normalize(bytes))
            assertFalse(fixture.read.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, bytes), query.toString())
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun krpc001LegacyFoodClassifierAdmitsOnlyCanonicalStationStartAndReady() {
        val fixture = KitchenFoodFixture()
        listOf(KitchenFoodInputs.start, KitchenFoodInputs.ready).forEach { station ->
            val bytes = LocalFoodCodecV1.encode(station)
            assertEquals(bytes, fixture.legacy.normalize(bytes))
            assertTrue(fixture.legacy.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, bytes))
            LocalMasterRpcWorkflowProfile.entries.filter { it != LocalMasterRpcWorkflowProfile.KITCHEN }.forEach { profile ->
                assertFalse(fixture.legacy.supportsRpcWorkflow(profile, bytes), profile.name)
            }
        }
        KitchenFoodInputs.legacyExclusions().forEach { (name, request) ->
            val bytes = LocalFoodCodecV1.encode(request)
            assertEquals(bytes, fixture.legacy.normalize(bytes))
            assertFalse(fixture.legacy.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, bytes), name)
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun krpc001NormalizedFoodClassifierKeepsThePreparationWrapperAndStationActions() {
        val fixture = KitchenFoodFixture()
        listOf(KitchenFoodInputs.start, KitchenFoodInputs.ready).forEach { station ->
            val request = LocalFoodStockRequestV2.Preparation(station)
            val bytes = LocalFoodStockCodecV2.encode(request)
            assertEquals(request, LocalFoodStockCodecV2.decode(bytes))
            assertEquals(bytes, fixture.normalized.normalize(bytes))
            assertTrue(fixture.normalized.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, bytes))
            LocalMasterRpcWorkflowProfile.entries.filter { it != LocalMasterRpcWorkflowProfile.KITCHEN }.forEach { profile ->
                assertFalse(fixture.normalized.supportsRpcWorkflow(profile, bytes), profile.name)
            }
        }
        KitchenFoodInputs.normalizedExclusions().forEach { (name, request) ->
            val bytes = LocalFoodStockCodecV2.encode(request)
            assertEquals(bytes, fixture.normalized.normalize(bytes))
            assertFalse(fixture.normalized.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, bytes), name)
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun krpc001MalformedTypedInputsCannotBecomeNormalizedStationRequests() {
        val fixture = KitchenFoodFixture()
        val task = LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Task(KitchenFoodInputs.station, KitchenFoodInputs.task))
        val legacy = LocalFoodCodecV1.encode(KitchenFoodInputs.start)
        val normalized = LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.Preparation(KitchenFoodInputs.start))
        assertFailsWith<IllegalArgumentException> { fixture.read.normalize(truncated(task)) }
        assertFailsWith<IllegalArgumentException> { fixture.legacy.normalize(truncated(legacy)) }
        assertFailsWith<IllegalArgumentException> { fixture.normalized.normalize(truncated(normalized)) }
        val invalidStation = KitchenFoodInputs.start.copy(confirmationId = KitchenFoodInputs.confirmation,
            returnDisposition = FoodIngredientReturnDisposition.RELEASED)
        // The existing serializer supplies canonical bytes of an invalid typed body.
        // Normalization must still apply the existing START/READY field restrictions.
        val invalidLegacy = localSalesEncode(invalidStation, LocalFoodRequestV1.serializer())
        val invalidNormalized = localSalesEncode(LocalFoodStockRequestV2.Preparation(invalidStation), LocalFoodStockRequestV2.serializer())
        assertFailsWith<IllegalArgumentException> { fixture.legacy.normalize(invalidLegacy) }
        assertFailsWith<IllegalArgumentException> { fixture.normalized.normalize(invalidNormalized) }
        assertEquals(legacy, fixture.legacy.normalize(legacy))
        assertEquals(normalized, fixture.normalized.normalize(normalized))
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun krpc006RepeatedOriginalRequirementsKeepExactSavedStationBindingAndBytes() {
        val fixture = KitchenFoodFixture()
        fixture.originals().forEach { original ->
            val bytesBefore = original.bytes.copyBytes()
            val context = original.context()
            val expected = AuthorizationRequirement(Permission(SecurityAction.KITCHEN_TASK, PermissionVerb.PERFORM),
                KitchenFoodInputs.scope, ActionBinding(original.station.stationId, original.station.expectedOrderRevision,
                    context.semanticDigest, original.operationId, "food.operation"))
            repeat(2) {
                assertEquals(listOf(expected), original.provider.rpcRetainedResultRequirements(
                    LocalMasterRpcWorkflowProfile.KITCHEN, original.bytes, context), original.name)
            }
            assertTrue(bytesBefore.contentEquals(original.bytes.copyBytes()), original.name)
            assertEquals(original.bytes, original.handler.normalize(original.bytes), original.name)
            assertEquals(KitchenFoodInputs.actor, context.originalActorId)
            assertEquals(original.operationId, context.operationId)
            assertEquals(original.handler.key, context.key)
        }
        assertEquals(0, fixture.source.calls)
        // There is no current task or committed original in this slice. Repetition proves
        // a pure declaration; actual lookup after task progression remains a separate L3 run.
    }

    @Test
    fun krpc006ChangedOriginalBodyOrContextCannotDeclareTheSavedRequirement() {
        val fixture = KitchenFoodFixture()
        fixture.originals().forEach { original ->
            val context = original.context()
            val changed = original.encode(original.station.copy(
                expectedOrderRevision = Revision(original.station.expectedOrderRevision.value + 1)))
            assertFails(original.name) { original.provider.rpcRetainedResultRequirements(
                LocalMasterRpcWorkflowProfile.KITCHEN, changed, context) }
            val wrongActor = CommandContext(context.scope, KitchenFoodInputs.otherActor, context.operationId,
                context.key, context.semanticDigest)
            val wrongScope = CommandContext(context.scope.copy(branchId = KitchenFoodInputs.otherBranch), context.originalActorId,
                context.operationId, context.key, context.semanticDigest)
            val wrongKey = CommandContext(context.scope, context.originalActorId, context.operationId,
                CommandKey(context.key.name, context.key.version + 1), context.semanticDigest)
            listOf(wrongActor, wrongScope, wrongKey).forEach { changedContext ->
                assertFails(original.name) { original.provider.rpcRetainedResultRequirements(
                    LocalMasterRpcWorkflowProfile.KITCHEN, original.bytes, changedContext) }
            }
            assertFails(original.name) { original.provider.rpcRetainedResultRequirements(
                LocalMasterRpcWorkflowProfile.SUPPLIERS, original.bytes, context) }
            assertEquals(original.bytes, original.handler.normalize(original.bytes))
        }
        assertEquals(0, fixture.source.calls)
        // The kernel's authentic retained operation/body association and result codes
        // are not exercised by these inert original-context mismatch checks.
    }

    @Test
    fun krpc006MalformedOrExcludedOriginalBodiesCannotDeclareStationDisclosure() {
        val fixture = KitchenFoodFixture()
        fixture.originals().forEach { original ->
            val malformed = truncated(original.bytes)
            assertFails(original.name) { original.provider.rpcRetainedResultRequirements(
                LocalMasterRpcWorkflowProfile.KITCHEN, malformed, original.context(malformed)) }
        }
        KitchenFoodInputs.legacyExclusions().forEach { (name, request) ->
            val bytes = LocalFoodCodecV1.encode(request)
            assertFails(name) { fixture.legacy.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.KITCHEN,
                bytes, KitchenFoodInputs.context(fixture.legacy.key, bytes, KitchenFoodInputs.startOperation)) }
        }
        KitchenFoodInputs.normalizedExclusions().forEach { (name, request) ->
            val bytes = LocalFoodStockCodecV2.encode(request)
            assertFails(name) { fixture.normalized.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.KITCHEN,
                bytes, KitchenFoodInputs.context(fixture.normalized.key, bytes, KitchenFoodInputs.startOperation)) }
        }
        assertEquals(0, fixture.source.calls)
    }
}

/** Immutable KBA identities/revisions; auxiliary IDs describe codec-only excluded requests. */
private object KitchenFoodInputs {
    val scope = BranchScope(DomainId("10000000-0000-4000-8000-000000000001"),
        DomainId("10000000-0000-4000-8000-000000000002"), DomainId("10000000-0000-4000-8000-000000000003"))
    val otherBranch = DomainId("10000000-0000-4000-8000-000000000004")
    val station = DomainId("20000000-0000-4000-8000-000000000001")
    val order = DomainId("30000000-0000-4000-8000-000000000001")
    val portion = DomainId("40000000-0000-4000-8000-000000000001")
    val cursor = DomainId("50000000-0000-4000-8000-000000000010")
    val task = DomainId("50000000-0000-4000-8000-000000000011")
    val actor = DomainId("60000000-0000-4000-8000-000000000001")
    val otherActor = DomainId("60000000-0000-4000-8000-000000000002")
    val startOperation = OperationId("80000000-0000-4000-8000-000000000001")
    val readyOperation = OperationId("80000000-0000-4000-8000-000000000002")
    val confirmation = auxiliary(6)
    val start = LocalFoodRequestV1.Station(order, Revision(41), portion, Revision(17), station, LocalFoodStationActionV1.START)
    val ready = LocalFoodRequestV1.Station(order, Revision(42), portion, Revision(18), station, LocalFoodStationActionV1.READY)
    private val service = LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "SYNTHETIC KRPC-001")
    private val selection = LocalFoodPortionSelectionV1(portion, auxiliary(1), auxiliary(2), Revision(1), auxiliary(3),
        emptyList(), Quantity(auxiliary(4), 1, 0))
    private val add = LocalFoodRequestV1.Add(order, Revision(41), Revision(1), listOf(selection))

    fun legacyExclusions(): List<Pair<String, LocalFoodRequestV1>> = listOf(
        "CREATE" to LocalFoodRequestV1.Create(order, LocalSalesActivity.RESTAURANT, service, Revision(1), listOf(selection)),
        "ADD" to add,
        "SEND" to LocalFoodRequestV1.Send(order, Revision(41), listOf(portion)),
        "STATION CONFIRM_UNSTARTED" to start.copy(action = LocalFoodStationActionV1.CONFIRM_UNSTARTED,
            confirmationId = confirmation, returnDisposition = FoodIngredientReturnDisposition.RELEASED),
        "SERVE" to LocalFoodRequestV1.Serve(order, Revision(41), portion, Revision(17)),
        "CANCEL" to LocalFoodRequestV1.Cancel(order, Revision(41), portion, Revision(17), auxiliary(5)),
        "DISCARD_STARTED" to LocalFoodRequestV1.DiscardStarted(order, Revision(41), portion, Revision(17), station),
        "OFFER_HANDOFF" to LocalFoodRequestV1.OfferHandoff(order, Revision(41), auxiliary(7), otherActor),
        "ACCEPT_HANDOFF" to LocalFoodRequestV1.AcceptHandoff(order, Revision(41), auxiliary(7)),
        "CANCEL_HANDOFF" to LocalFoodRequestV1.CancelHandoff(order, Revision(41), auxiliary(7)),
        "EDIT_SERVICE_DETAILS" to LocalFoodRequestV1.EditServiceDetails(order, Revision(41), "SYNTHETIC KRPC-001"),
    )

    fun normalizedExclusions(): List<Pair<String, LocalFoodStockRequestV2>> {
        val preparation = legacyExclusions().filter { (_, request) ->
            request !is LocalFoodRequestV1.OfferHandoff && request !is LocalFoodRequestV1.AcceptHandoff &&
                request !is LocalFoodRequestV1.CancelHandoff
        }.map { (name, request) -> "PREPARATION($name)" to LocalFoodStockRequestV2.Preparation(request) }
        val draft = LocalFoodDraftCreateV1(order, LocalSalesActivity.RESTAURANT, service)
        return preparation + listOf(
            "ACTIVITY_PROTECTED" to LocalFoodStockRequestV2.ActivityProtected(draft),
            "ACTIVITY_PROTECTED_ADD" to LocalFoodStockRequestV2.ActivityProtectedAdd(add),
            "ACTIVITY_PROTECTED_QUOTED_ADD" to LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(add),
            "CREATE_DRAFT" to LocalFoodStockRequestV2.CreateDraft(draft),
            "FINANCIAL(BEGIN_COLLECTION)" to LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.BeginCollection(
                order, Revision(41), auxiliary(8), auxiliary(9), Revision(1))),
        )
    }

    fun context(key: CommandKey, bytes: StoredBytes, operation: OperationId): CommandContext =
        CommandContext(scope, actor, operation, key, semanticDigest(scope, actor, key, bytes))

    private fun auxiliary(index: Int): DomainId = DomainId("90000000-0000-4000-8000-" + index.toString().padStart(12, '0'))
}

private class KitchenFoodFixture {
    val source = UncalledKitchenFoodSource()
    private val stock = LocalStockRepository()
    val legacy = LocalFoodHandler(stock, source.value)
    val normalized = LocalFoodStockHandler(stock, UnavailableLocalStockAuthority, source.value)
    val read = LocalFoodReadHandler(source.value, stock)

    fun originals(): List<KitchenOriginalDeclaration> = listOf(
        KitchenOriginalDeclaration("legacy-Start", legacy, KitchenFoodInputs.start, KitchenFoodInputs.startOperation),
        KitchenOriginalDeclaration("legacy-Ready", legacy, KitchenFoodInputs.ready, KitchenFoodInputs.readyOperation),
        KitchenOriginalDeclaration("normalized-Start", normalized, KitchenFoodInputs.start, KitchenFoodInputs.startOperation),
        KitchenOriginalDeclaration("normalized-Ready", normalized, KitchenFoodInputs.ready, KitchenFoodInputs.readyOperation),
    )
}

private class KitchenOriginalDeclaration(val name: String, val handler: MasterCommandHandler,
    val station: LocalFoodRequestV1.Station, val operationId: OperationId) {
    val provider = handler as LocalMasterRpcRetainedResultAccessProvider
    val bytes = encode(station)
    fun encode(request: LocalFoodRequestV1.Station): StoredBytes = if (handler.key == LocalFoodProfileV1.COMMAND)
        LocalFoodCodecV1.encode(request) else LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.Preparation(request))
    fun context(body: StoredBytes = bytes): CommandContext = KitchenFoodInputs.context(handler.key, body, operationId)
}

/** Controlled dependency which fails every call; it supplies no source or authority. */
private class UncalledKitchenFoodSource {
    var calls = 0
        private set
    val value: TransactionFoodStockSource = Proxy.newProxyInstance(TransactionFoodStockSource::class.java.classLoader,
        arrayOf(TransactionFoodStockSource::class.java)) { _, _, _ ->
        calls++
        error("No current recipe, task, profile, execution or retained authority is supplied")
    } as TransactionFoodStockSource
}

private fun truncated(bytes: StoredBytes): StoredBytes = StoredBytes(bytes.copyBytes().copyOf(bytes.size - 1))
