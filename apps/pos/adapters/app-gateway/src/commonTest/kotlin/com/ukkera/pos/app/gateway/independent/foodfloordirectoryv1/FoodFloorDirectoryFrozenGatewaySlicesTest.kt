@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.foodfloordirectoryv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Frozen food-floor-directory-v1 gateway slices. The actual mapping/staging/journal adapter
 * runs against controlled view, read and client-input seams. DTO replies used by the result
 * mapper are explicitly synthetic: no Master commit, native history, authority or admitted
 * RPC is asserted. DTO serialization supplies input, not a canonical wire-byte oracle.
 */
class FoodFloorDirectoryFrozenGatewaySlicesTest {
    private val f = FfdGatewayInputs

    @Test
    fun ffd003008026StageCapturesTargetsExpectedRevisionsLiteralNamesAndExplicitFalse(): Unit = runTest {
        val requests = listOf(LocalFoodFloorConfigurationRequestV1.Floor(null, f.newFloor),
            LocalFoodFloorConfigurationRequestV1.Table(null, f.newTable),
            LocalFoodFloorConfigurationRequestV1.Table(Revision(1), f.replacement),
            LocalFoodFloorConfigurationRequestV1.Table(Revision(11), f.table010.copy(active = false)))
        for ((index, request) in requests.withIndex()) {
            val fixture = FloorGatewayFixture()
            val intent = f.intent(request, f.operation(if (index == 3) 8 else 3, index + 1))
            val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
            val saved = assertNotNull(fixture.journal.entry)
            assertEquals(intent.operationId, staged.operation.operationId)
            assertEquals(intent.action, staged.operation.action)
            assertEquals(request, LocalFoodFloorCodecV1.decode(saved.request().payload))
            assertEquals(intent.operationId, saved.request().operationId)
            assertEquals(LocalFoodFloorProfileV1.COMMAND, saved.request().command)
            assertNull(saved.request().actionApprovalId)
            assertEquals(fixture.journal.owner, saved.owner)
            if (index >= 2) assertEquals(LocalScalarValue.Flag(false), intent.scalar(LocalField.ACTIVE))
            assertEquals(1, fixture.journal.stageCalls)
            fixture.assertNoMasterMutationOrResultWrite()
        }
    }

    @Test
    fun ffd005026ScalarOnlyFormsRefuseOtherVersionsReferencesAndEmptyOrNonemptyLineGroups(): Unit = runTest {
        for (request in listOf(LocalFoodFloorConfigurationRequestV1.Floor(null, f.newFloor),
            LocalFoodFloorConfigurationRequestV1.Table(null, f.newTable))) {
            val original = f.intent(request, f.operation(26, 1))
            val candidates = listOf(
                original.withShape(version = 2),
                original.withShape(reference = LocalRecordReference(LocalRecordKind.ORDER,
                    DomainId("b5000000-0000-4000-8000-000000000001"))),
                original.withShape(lineField = LocalField.LINES),
                original.withShape(lineField = LocalField.LINES, lines = listOf(LocalInputLine(f.table010.id,
                    listOf(LocalScalarInput(LocalField.NAME_ENGLISH,
                        LocalScalarValue.Text("Any nonempty line in this scalar-only configuration form")))))),
            )
            for (candidate in candidates) {
                val fixture = FloorGatewayFixture()
                assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(candidate))
                assertNull(fixture.journal.entry)
                assertEquals(0, fixture.journal.stageCalls)
                fixture.assertNoMasterMutationOrResultWrite()
            }
        }
        // The frozen literal cannot become a typed scalar field. Actual raw-ingress framing
        // and its exact refusal remain B-01/B-02; this is only the closed field-name seam.
        assertFails { LocalField.valueOf("UNREQUESTED_OVERRIDE") }
    }

    @Test
    fun ffd002004025ReadMappingRetainsExactIdsRevisionsParentsLiteralEditorValuesAndCursor(): Unit = runTest {
        val fixture = FloorGatewayFixture()
        val floorA = LocalFoodFloorRowV1(f.floorA, Revision(7))
        val floorB = LocalFoodFloorRowV1(f.floorB, Revision(3))
        val floorList = LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Floors(), true, floors = listOf(floorA, floorB))
        fixture.endpoint.source = floorList
        val floors = assertIs<LocalReadResult.Available>(fixture.gateway.read(f.uiQuery(LocalFoodUiQuery.FloorDirectory()))).page
        assertEquals(listOf(f.floorA.id, f.floorB.id), assertIs<LocalFoodUiBody.Floors>(floors.food!!.body).floors.map { it.id })
        assertEquals(listOf(Revision(7), Revision(3)), assertIs<LocalFoodUiBody.Floors>(floors.food!!.body).floors.map { it.storageRevision })
        assertNull(floors.food!!.next)
        assertNull(floors.forms.single().field(LocalField.FLOOR_ID).initialValue)
        fixture.assertReadRequest(floorList.query)

        fixture.endpoint.source = LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Tables(f.floorB.id), true,
            tables = listOf(LocalFoodTableRowV1(f.table020, Revision(2))), floor = floorB)
        val disabled = assertIs<LocalReadResult.Available>(fixture.gateway.read(f.uiQuery(LocalFoodUiQuery.FloorTables(f.floorB.id)))).page
        val disabledBody = assertIs<LocalFoodUiBody.Tables>(disabled.food!!.body)
        assertEquals(f.floorB.id, disabledBody.floor.id)
        assertFalse(disabledBody.floor.active)
        assertEquals(f.table020.id, disabledBody.tables.single().id)
        assertFalse(disabledBody.tables.single().active)
        assertEquals(Revision(2), disabledBody.tables.single().storageRevision)
        assertEquals(LocalText("طاولة الشرفة", "طاولة الشرفة"), disabledBody.tables.single().names)

        fixture.endpoint.source = LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Table(f.table020.id), true,
            tables = listOf(LocalFoodTableRowV1(f.table020, Revision(2))), floor = floorB)
        val disabledDetail = assertIs<LocalReadResult.Available>(fixture.gateway.read(f.uiQuery(LocalFoodUiQuery.TableDefinition(f.table020.id)))).page
        val literalForm = disabledDetail.forms.single()
        assertEquals(LocalScalarValue.Text(""), literalForm.field(LocalField.NAME_ENGLISH).initialValue)
        assertEquals(LocalScalarValue.Text("طاولة الشرفة"), literalForm.field(LocalField.NAME_ARABIC).initialValue)
        assertEquals(LocalScalarValue.Identifier(f.floorB.id), literalForm.field(LocalField.FLOOR_ID).initialValue)
        assertTrue(literalForm.field(LocalField.FLOOR_ID).readOnly)

        fixture.endpoint.source = LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Table(f.table010.id), true,
            tables = listOf(LocalFoodTableRowV1(f.table010, Revision(11))), floor = floorA)
        val detail = assertIs<LocalReadResult.Available>(fixture.gateway.read(f.uiQuery(LocalFoodUiQuery.TableDefinition(f.table010.id)))).page
        val body = assertIs<LocalFoodUiBody.Table>(detail.food!!.body)
        assertEquals(f.table010.id, body.table.id)
        assertEquals(Revision(11), body.table.storageRevision)
        assertEquals(f.floorA.id, body.floor.id)
        assertEquals(Revision(7), body.floor.storageRevision)
        assertEquals(LocalScalarValue.Identifier(f.table010.id), detail.forms.single().field(LocalField.TABLE_ID).initialValue)
        assertEquals(LocalScalarValue.Integer(11), detail.forms.single().field(LocalField.EXPECTED_STORAGE_REVISION).initialValue)
        assertNull(detail.food!!.next)

        val capQuery = LocalFoodFloorQueryV1.Tables(f.capacityFloor.id, f.capacityTable(20).id)
        fixture.endpoint.source = LocalFoodFloorPageV1(capQuery, true,
            tables = (21..40).map { LocalFoodTableRowV1(f.capacityTable(it), Revision(1)) },
            floor = LocalFoodFloorRowV1(f.capacityFloor, Revision(1)), nextAfter = f.capacityTable(40).id)
        val paged = assertIs<LocalReadResult.Available>(fixture.gateway.read(f.uiQuery(LocalFoodUiQuery.FloorTables(f.capacityFloor.id,
            f.capacityTable(20).id)))).page
        assertEquals((21..40).map { f.capacityTable(it).id }, assertIs<LocalFoodUiBody.Tables>(paged.food!!.body).tables.map { it.id })
        assertEquals(LocalFoodUiQuery.FloorTables(f.capacityFloor.id, f.capacityTable(40).id), paged.food!!.next)
        fixture.assertReadRequest(capQuery)
        assertTrue(paged.records.isEmpty())
        assertEquals(0, fixture.journal.stageCalls)
        fixture.assertNoMasterMutationOrResultWrite()
    }

    @Test
    fun ffd019FailedOrQueryMismatchedReadsPublishNoCurrentPage(): Unit = runTest {
        val fixture = FloorGatewayFixture()
        fixture.endpoint.source = LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Floors(), true,
            floors = listOf(LocalFoodFloorRowV1(f.floorA, Revision(7)), LocalFoodFloorRowV1(f.floorB, Revision(3))))
        assertIs<LocalReadResult.Available>(fixture.gateway.read(f.uiQuery(LocalFoodUiQuery.FloorDirectory())))
        fixture.endpoint.readFailure = ReadFailure.SERVICE_BUSY
        assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(f.uiQuery(LocalFoodUiQuery.FloorDirectory())))
        fixture.endpoint.readFailure = null
        // The DTO is valid for its own query, but cannot satisfy this different query.
        assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(f.uiQuery(LocalFoodUiQuery.FloorTables(f.floorB.id))))
        assertEquals(0, fixture.journal.stageCalls)
        fixture.assertNoMasterMutationOrResultWrite()
    }

    @Test
    fun ffd026ResultMappingKeepsOriginalTargetsRevisionsAndSixListedRefusals(): Unit = runTest {
        val request = LocalFoodFloorConfigurationRequestV1.Table(Revision(1), f.replacement)
        val fixture = FloorGatewayFixture()
        val intent = f.intent(request, f.operation(26, 2))
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val entry = assertNotNull(fixture.journal.entry)
        val savedDto = LocalConfigurationResultV1("food.floor.saved", f.newTable.id, Revision(2))
        val saved = floorConfigurationRecorded(entry, f.dtoReply(entry.operationId, savedDto, true))
        assertEquals(entry.operationId, saved.operationId)
        assertEquals(LocalRecordedDecision.APPLIED, saved.decision)
        assertTrue(f.newTable.id.value in saved.message.english)
        assertTrue("2" in saved.message.english)
        for (code in f.rejectionCodes) {
            val refused = floorConfigurationRecorded(entry, f.dtoReply(entry.operationId,
                LocalConfigurationResultV1(code, null, null), false))
            assertEquals(entry.operationId, refused.operationId)
            assertEquals(LocalRecordedDecision.REJECTED, refused.decision)
            assertTrue(code in refused.message.english)
        }
        assertEquals(entry, fixture.journal.entry)
        fixture.assertNoMasterMutationOrResultWrite()
    }

    @Test
    fun ffd029SavedLookingResultForBCannotResolveOrRelabelPendingA(): Unit = runTest {
        val fixture = FloorGatewayFixture()
        val intent = f.intent(LocalFoodFloorConfigurationRequestV1.Floor(null, f.newFloor), f.operation(29, 3))
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        val plausibleB = f.dtoReply(f.operation(29, 4), LocalConfigurationResultV1("food.floor.saved", f.newFloor.id, Revision(1)), true)
        assertFails { floorConfigurationRecorded(original, plausibleB) }
        assertEquals(original, fixture.journal.entry)
        val unresolved = assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(original.operationId))
        assertEquals(original.operationId, unresolved.operationId)
        assertEquals(listOf(original.identity()), fixture.endpoint.lookups)
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoMasterMutationOrResultWrite()
    }

    @Test
    fun ffd011012028StagedOriginalSurvivesChangedEditorInputAndAdapterReconstruction(): Unit = runTest {
        val fixture = FloorGatewayFixture()
        val originalInput = LocalFoodFloorConfigurationRequestV1.Floor(Revision(7), f.floorA.copy(
            names = LocalNamesV1("Floor Version A", "صالة النسخة أ"), layoutOrder = 8, active = true))
        val intent = f.intent(originalInput, f.operation(11, 1))
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(intent))
        val changed = f.intent(LocalFoodFloorConfigurationRequestV1.Floor(Revision(7), f.floorA.copy(
            names = LocalNamesV1("Floor Version B", "صالة النسخة ب"), layoutOrder = 9, active = true)), intent.operationId)
        assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(changed))
        assertEquals(original, fixture.journal.entry)
        assertEquals(originalInput, LocalFoodFloorCodecV1.decode(original.request().payload))
        val rebuilt = fixture.newGateway()
        assertEquals(original.operationId, assertIs<LocalOperationResult.Unresolved>(rebuilt.lookup(original.operationId)).operationId)
        assertTrue(fixture.endpoint.lookups.all { it == original.identity() })
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoMasterMutationOrResultWrite()
    }
}

private object FfdGatewayInputs {
    val scope = BranchScope(DomainId("10ad0100-0000-4000-8000-000000000001"),
        DomainId("20ad0200-0000-4000-8000-000000000001"), DomainId("30ad0300-0000-4000-8000-000000000001"))
    val actor = DomainId("40ad0400-0000-4000-8000-000000000001")
    val floorA = LocalFoodFloorDefinitionV1(DomainId("f1000000-0000-4000-8000-000000000010"), LocalNamesV1("Dining 10", "صالة ١٠"), 700, true)
    val floorB = LocalFoodFloorDefinitionV1(DomainId("f1000000-0000-4000-8000-000000000020"), LocalNamesV1("Terrace 02", "شرفة ٠٢"), 100, false)
    val table010 = LocalFoodTableDefinitionV1(DomainId("a2000000-0000-4000-8000-000000000010"), floorA.id, LocalNamesV1("Table 002", "طاولة ٠٠٢"), 9000, true)
    val table020 = LocalFoodTableDefinitionV1(DomainId("a2000000-0000-4000-8000-000000000020"), floorB.id, LocalNamesV1("", "طاولة الشرفة"), 3, false)
    val newFloor = LocalFoodFloorDefinitionV1(DomainId("f1000000-0000-4000-8000-000000000030"), LocalNamesV1("New floor 003", "صالة جديدة ٠٠٣"), 42, true)
    val newTable = LocalFoodTableDefinitionV1(DomainId("a2000000-0000-4000-8000-000000000040"), newFloor.id, LocalNamesV1("004", "طاولة ٠٠٤"), 17, true)
    val replacement = newTable.copy(names = LocalNamesV1(" 004 North ", "طاولة ٠٠٤ شمال"), layoutOrder = 0, active = false)
    val capacityFloor = LocalFoodFloorDefinitionV1(DomainId("f2000000-0000-4000-8000-000000000001"), LocalNamesV1("Floor 01", "صالة"), 9998, false)
    val rejectionCodes = listOf("food.floor.stale", "food.floor.capacity", "food.floor.identity_conflict",
        "food.floor.active_tables", "food.table.floor_required", "food.table.floor_changed")
    val at = UtcInstant.parseCanonical("2026-10-31T02:00:00Z")
    private val json = Json { encodeDefaults = true }
    fun capacityTable(serial: Int) = LocalFoodTableDefinitionV1(DomainId("a3000000-0000-4000-8000-${serial.toString(16).padStart(12, '0')}"),
        capacityFloor.id, LocalNamesV1("Table ${serial.toString().padStart(3, '0')}", "طاولة"), 100 - serial, false)
    fun operation(case: Int, within: Int) = OperationId("d4000000-0000-4000-8000-${(case * 1000 + within).toString(16).padStart(12, '0')}")
    fun uiQuery(query: LocalFoodUiQuery) = LocalWorkflowQuery(LocalWorkflow.FOOD, food = query)
    fun pageBytes(page: LocalFoodFloorPageV1) = StoredBytes(json.encodeToString(LocalFoodFloorPageV1.serializer(), page).encodeToByteArray())
    fun dtoReply(operation: OperationId, result: LocalConfigurationResultV1, committed: Boolean) = MutationReply.Recorded(operation,
        actor, committed, if (committed) "committed" else result.code,
        StoredBytes(json.encodeToString(LocalConfigurationResultV1.serializer(), result).encodeToByteArray()),
        Revision(1), JournalSequence(1), at, false)
    fun intent(request: LocalFoodFloorConfigurationRequestV1, operation: OperationId): LocalCommandIntent {
        val expected: Revision?
        val action: LocalAction
        val names: LocalNamesV1
        val order: Int
        val active: Boolean
        val ids: List<LocalScalarInput>
        when (request) {
            is LocalFoodFloorConfigurationRequestV1.Floor -> {
                expected = request.expectedRevision; action = LocalAction.FLOOR_CONFIGURE; names = request.floor.names
                order = request.floor.layoutOrder; active = request.floor.active
                ids = listOf(LocalScalarInput(LocalField.FLOOR_ID, LocalScalarValue.Identifier(request.floor.id)))
            }
            is LocalFoodFloorConfigurationRequestV1.Table -> {
                expected = request.expectedRevision; action = LocalAction.TABLE_CONFIGURE; names = request.table.names
                order = request.table.layoutOrder; active = request.table.active
                ids = listOf(LocalScalarInput(LocalField.TABLE_ID, LocalScalarValue.Identifier(request.table.id)),
                    LocalScalarInput(LocalField.FLOOR_ID, LocalScalarValue.Identifier(request.table.floorId)))
            }
        }
        return LocalCommandIntent(operation, action, 1, null, ids + listOfNotNull(expected?.let {
            LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(it.value)) }) + listOf(
            LocalScalarInput(LocalField.NAME_ENGLISH, LocalScalarValue.Text(names.english)),
            LocalScalarInput(LocalField.NAME_ARABIC, LocalScalarValue.Text(names.arabic)),
            LocalScalarInput(LocalField.LAYOUT_ORDER, LocalScalarValue.Integer(order.toLong())),
            LocalScalarInput(LocalField.ACTIVE, LocalScalarValue.Flag(active))))
    }
}

private fun LocalCommandIntent.scalar(field: LocalField) = values.single { it.field == field }.value
private fun LocalActionForm.field(field: LocalField) = fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == field }
private fun LocalCommandIntent.withShape(version: Int = formVersion, reference: LocalRecordReference? = this.reference,
    lineField: LocalField? = this.lineField, lines: List<LocalInputLine> = this.lines) =
    LocalCommandIntent(operationId, action, version, reference, values, lineField, lines)

private class FloorGatewayFixture {
    val endpoint = ControlledFloorEndpoint()
    val journal = ControlledFloorJournal(LocalClientIdentity(FfdGatewayInputs.scope, FfdGatewayInputs.actor, endpoint.view.deviceId))
    private val installed = LocalWorkflowInstallation(setOf(LocalFoodFloorProfileV1.COMMAND), setOf(LocalFoodFloorReadCodecV1.QUERY))
    val gateway = newGateway()
    fun newGateway() = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(DomainId.random(), OpaqueCredential(ByteArray(16)))) { true }
    }, installed)
    fun assertReadRequest(query: LocalFoodFloorQueryV1) {
        assertEquals(LocalFoodFloorReadCodecV1.QUERY, endpoint.reads.last().query)
        assertEquals(LocalFoodFloorReadCodecV1.encode(query), endpoint.reads.last().payload)
    }
    fun assertNoMasterMutationOrResultWrite() {
        assertEquals(0, endpoint.executeCalls)
        assertEquals(0, journal.dispatchCalls)
        assertEquals(0, journal.resultWrites)
    }
}

/** Controlled storage of client input only; DURABLE is a seam result, not persistence evidence. */
private class ControlledFloorJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
    var stageCalls = 0
    var dispatchCalls = 0
    var resultWrites = 0
    override suspend fun find(operationId: OperationId) = JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending() = JournalRead.Value(listOfNotNull(entry))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++; check(entry == null); entry = intent; return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite { dispatchCalls++; error("No qualified absence fixture") }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite { resultWrites++; error("No committed result fixture") }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = error("No committed result fixture")
    override suspend fun close() = Unit
}

/** Scripted session/read inputs, never a production principal verifier or Master service. */
private class ControlledFloorEndpoint : LocalMasterClient {
    private val f = FfdGatewayInputs
    private val boot = DomainId.random()
    val view = LocalSessionView(f.actor, f.scope, DomainId.random(), DomainId.random(), SessionGeneration(1),
        LocalLoginIdentifier("synthetic-floor-configurator"), ActivatedLocalPolicy(f.scope, f.actor, Revision(1),
            listOf(PermissionGrant(Permission(SecurityAction.FLOOR_CONFIGURE, PermissionVerb.ADMINISTER), f.scope, GrantEffect.ALLOW),
                PermissionGrant(Permission(SecurityAction.FLOOR_READ, PermissionVerb.PERFORM), f.scope, GrantEffect.ALLOW)),
            LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(boot, 1000), MonotonicMoment(boot, 2000))
    var source: LocalFoodFloorPageV1? = null
    var readFailure: ReadFailure? = null
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    var executeCalls = 0
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No runtime health evidence")
    override suspend fun current(credentials: LocalGatewayCredentials) = LocalSessionViewResult.Current(view)
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        return readFailure?.let { ReadReply.Unavailable(it) } ?: ReadReply.Data(f.pageBytes(checkNotNull(source)), Revision(1), f.at)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity; return LookupReply.Deferred(identity.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply { executeCalls++; error("No qualified writer fixture") }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray, explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No native sign-in fixture")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No native refresh fixture")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No native logout fixture")
}
