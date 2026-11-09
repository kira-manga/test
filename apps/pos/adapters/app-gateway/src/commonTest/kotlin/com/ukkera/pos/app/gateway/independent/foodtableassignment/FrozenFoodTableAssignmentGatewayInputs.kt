@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.foodtableassignment

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** FTA-v1 semantic inputs. Serialization below supplies DATA, not golden wire bytes. */
internal object FtaGatewayInputs {
    private fun id(suffix: String) = DomainId("019ad62c-a100-7000-8000-${suffix.padStart(12, '0')}")
    val scope = BranchScope(id("1"), id("65"), id("c9"))
    val actor = id("192")
    val otherActor = id("193")
    val device = id("1f6")
    val session = id("1ff")
    private val otherSession = id("200")
    val order = id("259")
    val otherOrder = id("25a")
    val floorMain = id("2bd")
    val floorUpper = id("2be")
    val floorRetired = id("2bf")
    val tableA = id("321")
    val tableB = id("322")
    val tableC = id("323")
    val at = UtcInstant.parseCanonical("2026-11-03T00:00:00Z")
    private val json = Json { encodeDefaults = true }

    fun operation(case: Int): OperationId {
        require(case in 1..40)
        return OperationId(id((10_000 + case).toString(16)).value)
    }

    val first = LocalFoodTableAssignmentRequestV1(order, Revision(12), floorMain, Revision(3), tableA, Revision(7), null)
    val move = LocalFoodTableAssignmentRequestV1(order, Revision(12), floorUpper, Revision(5), tableB, Revision(11), Revision(1))
    val sameFloor = LocalFoodTableAssignmentRequestV1(order, Revision(12), floorMain, Revision(3), tableC, Revision(13), Revision(1))
    val sameDestination = first.copy(expectedAssignmentRevision = Revision(1))
    val initialBinding = LocalFoodTableBindingV1(Revision(1), Revision(12), floorMain, Revision(3), tableA, Revision(7))
    val movedBinding = LocalFoodTableBindingV1(Revision(2), Revision(12), floorUpper, Revision(5), tableB, Revision(11))
    val result = LocalFoodTableAssignmentResultV1(LocalFoodTableAssignmentProfileV1.SAVED, order, Revision(12), movedBinding)

    // The distinct predecessor-expectation variant is not expressible in this result DTO.
    // Do not add an invented predecessor field or count result revision 3 twice.
    val conflicts: List<Pair<String, LocalFoodTableAssignmentResultV1>> = listOf(
        "different_order_id" to result.copy(orderId = otherOrder),
        "different_destination_ids" to result.copy(assignment = movedBinding.copy(floorId = floorMain, tableId = tableC)),
        "different_observed_order_revision" to result.copy(orderRevision = Revision(13),
            assignment = movedBinding.copy(observedOrderRevision = Revision(13))),
        "different_observed_floor_revision" to result.copy(assignment = movedBinding.copy(floorRevision = Revision(6))),
        "different_observed_table_revision" to result.copy(assignment = movedBinding.copy(tableRevision = Revision(12))),
        "different_result_assignment_revision" to result.copy(assignment = movedBinding.copy(revision = Revision(3))),
    )

    val mainRow = LocalFoodFloorRowV1(LocalFoodFloorDefinitionV1(floorMain,
        LocalNamesV1("Ground floor", "الطابق الأرضي"), 0, true), Revision(3))
    val upperRow = LocalFoodFloorRowV1(LocalFoodFloorDefinitionV1(floorUpper,
        LocalNamesV1("Upper floor", "الطابق العلوي"), 1, true), Revision(5))
    val retiredRow = LocalFoodFloorRowV1(LocalFoodFloorDefinitionV1(floorRetired,
        LocalNamesV1("Retired floor", "طابق متوقف"), 2, false), Revision(2))
    val tableBRow = LocalFoodTableRowV1(LocalFoodTableDefinitionV1(tableB, floorUpper,
        LocalNamesV1("Table 08", "طاولة ٨"), 8, true), Revision(11))

    fun intent(request: LocalFoodTableAssignmentRequestV1, operation: OperationId,
        version: Int = 1): LocalCommandIntent = LocalCommandIntent(operation, LocalAction.FOOD_ASSIGN_TABLE, version, null,
        listOf(LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(request.orderId)),
            LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(request.expectedOrderRevision.value)),
            LocalScalarInput(LocalField.FLOOR_ID, LocalScalarValue.Identifier(request.floorId)),
            LocalScalarInput(LocalField.EXPECTED_FLOOR_STORAGE_REVISION, LocalScalarValue.Integer(request.expectedFloorRevision.value)),
            LocalScalarInput(LocalField.TABLE_ID, LocalScalarValue.Identifier(request.tableId)),
            LocalScalarInput(LocalField.EXPECTED_TABLE_STORAGE_REVISION, LocalScalarValue.Integer(request.expectedTableRevision.value))) +
            listOfNotNull(request.expectedAssignmentRevision?.let {
                LocalScalarInput(LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION, LocalScalarValue.Integer(it.value))
            }))

    fun observation(binding: LocalFoodTableBindingV1? = initialBinding) = LocalFoodTableAssignmentObservationV1(
        order, Revision(12), actor, LocalSalesActivity.RESTAURANT, LocalSalesChannel.DINE_IN, false, false, binding)

    fun observationBytes(value: LocalFoodTableAssignmentObservationV1) = StoredBytes(
        json.encodeToString(LocalFoodTableAssignmentObservationV1.serializer(), value).encodeToByteArray())
    fun directoryBytes(value: LocalFoodFloorPageV1) = StoredBytes(
        json.encodeToString(LocalFoodFloorPageV1.serializer(), value).encodeToByteArray())

    /** An input envelope only: constructing this does not establish a Master commit. */
    fun recorded(operation: OperationId, value: LocalFoodTableAssignmentResultV1 = result,
        replayed: Boolean = false) = MutationReply.Recorded(operation, actor, true, "committed",
        StoredBytes(json.encodeToString(LocalFoodTableAssignmentResultV1.serializer(), value).encodeToByteArray()),
        Revision(1), JournalSequence(1), at, replayed)

    /** Scripted current-session view; never a native login, admission or authoritative grant. */
    fun view(actorId: DomainId = actor): LocalSessionView {
        val boot = id("1f5")
        return LocalSessionView(actorId, scope, device, if (actorId == otherActor) otherSession else session, SessionGeneration(1),
            LocalLoginIdentifier("synthetic-table-owner"), ActivatedLocalPolicy(scope, actorId, Revision(1),
                listOf(PermissionGrant(Permission(SecurityAction.ORDER_EDIT, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW),
                    PermissionGrant(Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW),
                    PermissionGrant(Permission(SecurityAction.FLOOR_READ, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW)),
                LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(boot, 1_000), MonotonicMoment(boot, 2_000))
    }
}

internal class FtaGatewayFixture {
    val endpoint = FtaControlledEndpoint()
    val journal = FtaControlledJournal(LocalClientIdentity(FtaGatewayInputs.scope, FtaGatewayInputs.actor, FtaGatewayInputs.device))
    var current = true
    private val installed = LocalWorkflowInstallation(setOf(LocalFoodTableAssignmentProfileV1.COMMAND),
        setOf(LocalFoodTableAssignmentReadCodecV1.QUERY, LocalFoodFloorReadCodecV1.QUERY))
    val gateway = newGateway()
    fun newGateway() = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(FtaGatewayInputs.session, OpaqueCredential(ByteArray(16)))) { current }
    }, installed)

    suspend fun assignmentForm(target: LocalFoodUiTableTarget = LocalFoodUiTableTarget(FtaGatewayInputs.order, Revision(12), Revision(1)),
        floor: LocalFoodFloorRowV1 = FtaGatewayInputs.upperRow,
        table: LocalFoodTableRowV1 = FtaGatewayInputs.tableBRow): LocalActionForm {
        endpoint.readPayload = FtaGatewayInputs.directoryBytes(LocalFoodFloorPageV1(
            LocalFoodFloorQueryV1.Table(table.definition.id), true, tables = listOf(table), floor = floor))
        val reply = gateway.read(LocalWorkflowQuery(LocalWorkflow.FOOD,
            food = LocalFoodUiQuery.TableDefinition(table.definition.id, target)))
        return (reply as LocalReadResult.Available).page.forms.single()
    }
}

/** Controlled client-input/result custody. DURABLE is a port answer, not native durability evidence.
 * No assignment-specific result validation is implemented here: the actual gateway must do it. */
internal class FtaControlledJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
        private set
    var stageCalls = 0
        private set
    var dispatchCalls = 0
        private set
    var resultWriteAttempts = 0
        private set
    var acceptedResultCount = 0
        private set
    var acknowledgementAttempts = 0
        private set
    var logicalAcknowledgements = 0
        private set

    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        JournalRead.Value(entry?.takeIf { it.operationId == operationId }?.also { it.validate(owner) })
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> =
        JournalRead.Value(listOfNotNull(entry?.takeUnless { it.acknowledged }?.also { it.validate(owner) }))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        intent.validate(owner)
        check(entry == null)
        entry = intent
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatchCalls++
        val saved = checkNotNull(entry)
        check(saved.operationId == operationId && saved.recorded == null && !saved.acknowledged)
        entry = saved.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
        return JournalWrite.DURABLE
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        resultWriteAttempts++
        val saved = checkNotNull(entry)
        result.validate()
        check(saved.operationId == operationId && result.operationId == operationId && result.originalActorId == owner.actorId)
        if (saved.recorded != null) return if (saved.recorded == result) JournalWrite.DURABLE else JournalWrite.IDENTITY_CONFLICT
        check(!saved.acknowledged)
        acceptedResultCount++
        entry = saved.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
        return JournalWrite.DURABLE
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgementAttempts++
        val saved = checkNotNull(entry)
        if (saved.operationId != operationId || saved.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        if (!saved.acknowledged) {
            logicalAcknowledgements++
            entry = saved.copy(acknowledged = true)
        }
        return JournalWrite.DURABLE
    }
    override suspend fun close() = Unit
}

/** Scripted session/transport DATA. No production Master, P2pKit admission or absence proof. */
internal class FtaControlledEndpoint : LocalMasterClient {
    var view = FtaGatewayInputs.view()
    var readPayload: StoredBytes? = null
    var readFailure: ReadFailure? = null
    var afterRead: (() -> Unit)? = null
    var lookupReply: ((OperationIdentity) -> LookupReply)? = null
    var executeReply: ((MutationRequest) -> MutationReply)? = null
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    val executions = mutableListOf<MutationRequest>()
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("Runtime health is not bound")
    override suspend fun current(credentials: LocalGatewayCredentials) = LocalSessionViewResult.Current(view)
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        afterRead?.invoke()
        return readFailure?.let { ReadReply.Unavailable(it) } ?: ReadReply.Data(checkNotNull(readPayload), Revision(1), FtaGatewayInputs.at)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return lookupReply?.invoke(identity) ?: LookupReply.Deferred(identity.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        return checkNotNull(executeReply) { "No execute DATA was supplied" }(request)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("Native sign-in is not bound")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("Native refresh is not bound")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("Native logout is not bound")
}
