package com.ukkera.pos.app.gateway.independent.foodsourcecontinuityv1

import com.ukkera.pos.app.LocalFoodUiBody
import com.ukkera.pos.app.LocalFoodUiPage
import com.ukkera.pos.app.LocalFoodUiQuery
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.LocalClientIntentJournal
import com.ukkera.pos.app.gateway.LocalEndpointWorkflowGateway
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialSource
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalFoodHandoffDirectoryResultV2
import com.ukkera.pos.sales.store.LocalFoodHandoffProfileV2
import com.ukkera.pos.sales.store.LocalFoodHandoffQueryV2
import com.ukkera.pos.sales.store.LocalFoodRecipientV2
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bounded SFC-02 directory mapping, through the public production gateway read route.
 * The endpoint, identity view and rejecting journal below are controlled seams, not a
 * real Master, authentication, admission, durable journal, business cut or RPC proof.
 */
class FoodSourceContinuityGatewayTest {
    @Test
    fun sfc02UnorderedRecipientsAndEmptyContinuationRemainFollowableThroughGateway() = runTest {
        val initial = LocalFoodUiQuery.Recipients(FrozenDirectory.orderId, FrozenDirectory.orderRevision)
        val firstCursor = FrozenDirectory.recipients[19].actorId
        val continuingCursor = FrozenDirectory.filteredEmployeeCoordinate
        val afterFirst = initial.copy(after = firstCursor)
        val afterEmpty = initial.copy(after = continuingCursor)

        // Contract-valid schedule only: the public directory prescribes neither order nor size.
        // Swap pairs so the input is unordered and its continuation is not its last visible ID.
        val unorderedFirst = FrozenDirectory.recipients.take(20).chunked(2).flatMap { it.reversed() }
        val scriptedIds = unorderedFirst.map { it.actorId.value }
        assertFalse(scriptedIds == scriptedIds.sorted(), "The controlled input must really be unordered")
        assertFalse(unorderedFirst.last().actorId == firstCursor)
        val script = listOf(
            ControlledDirectoryPage(null, LocalFoodHandoffDirectoryResultV2(
                recipients = unorderedFirst, nextRecipient = firstCursor)),
            ControlledDirectoryPage(firstCursor, LocalFoodHandoffDirectoryResultV2(
                recipients = emptyList(), nextRecipient = continuingCursor)),
            ControlledDirectoryPage(continuingCursor, LocalFoodHandoffDirectoryResultV2(
                recipients = listOf(FrozenDirectory.recipients[20]))),
        )
        val journal = RejectingReadJournal(LocalClientIdentity(
            FrozenDirectory.scope, FrozenDirectory.ownerId, FrozenDirectory.deviceId))
        val endpoint = ControlledDirectoryEndpoint(FrozenDirectory.constructorOnlyView(), script)
        val credentials = FreshConstructorOnlyCredentials()
        val gateway = LocalEndpointWorkflowGateway(endpoint, journal, credentials,
            LocalWorkflowInstallation(emptySet(), setOf(LocalFoodHandoffProfileV2.QUERY)))

        val first = readRecipients(gateway, initial)
        val firstBody = assertIs<LocalFoodUiBody.Recipients>(first.body)
        assertRecipientSet(FrozenDirectory.recipients.take(20), firstBody)
        val firstNext = assertIs<LocalFoodUiQuery.Recipients>(assertNotNull(first.next))
        assertEquals(afterFirst, firstNext, "Typed continuation retains the original parent/revision")
        assertReadOnly(journal, endpoint)

        // Follow the actual returned selector, not a privately injected continuation.
        val empty = readRecipients(gateway, firstNext)
        val emptyBody = assertIs<LocalFoodUiBody.Recipients>(empty.body)
        assertTrue(emptyBody.recipients.isEmpty(), "An empty response remains an empty displayed page")
        val emptyNext = assertIs<LocalFoodUiQuery.Recipients>(assertNotNull(empty.next,
            "An empty continuing page must not become a terminal page"))
        assertEquals(afterEmpty, emptyNext)
        assertReadOnly(journal, endpoint)

        val terminal = readRecipients(gateway, emptyNext)
        val terminalBody = assertIs<LocalFoodUiBody.Recipients>(terminal.body)
        assertRecipientSet(listOf(FrozenDirectory.recipients[20]), terminalBody)
        assertNull(terminal.next)
        val union = listOf(firstBody, emptyBody, terminalBody).flatMap { it.recipients }
        assertEquals(21, union.size, "All frozen eligible business items must be observed")
        assertEquals(union.size, union.map { it.actorId }.distinct().size, "No cross-page duplicate")
        assertEquals(FrozenDirectory.recipients.map { it.actorId }.toSet(), union.map { it.actorId }.toSet())
        assertEquals(FrozenDirectory.recipients.associate { it.actorId to it.label },
            union.associate { it.actorId to it.label }, "Exact ID/label mapping; no UI ordering oracle")
        assertEquals(listOf(
            LocalFoodHandoffQueryV2.Recipients(FrozenDirectory.orderId, Revision(7)),
            LocalFoodHandoffQueryV2.Recipients(FrozenDirectory.orderId, Revision(7), firstCursor),
            LocalFoodHandoffQueryV2.Recipients(FrozenDirectory.orderId, Revision(7), continuingCursor),
        ), endpoint.queries)
        assertEquals(listOf(FrozenDirectory.readKey, FrozenDirectory.readKey, FrozenDirectory.readKey), endpoint.keys)
        assertEquals(3, endpoint.currentCalls, "The public gateway's normal current-view seam is exercised")
        assertEquals(3, credentials.acquireCalls, "Each read acquires its own disposable constructor-only lease")
        assertReadOnly(journal, endpoint)
    }

    private suspend fun readRecipients(gateway: LocalEndpointWorkflowGateway,
        query: LocalFoodUiQuery.Recipients): LocalFoodUiPage {
        val available = assertIs<LocalReadResult.Available>(gateway.read(
            LocalWorkflowQuery(LocalWorkflow.FOOD, food = query)), "Contract-valid page is not rejected")
        assertEquals(LocalWorkflow.FOOD, available.page.workflow)
        assertNull(available.page.nextCursor, "Continuation belongs to the typed FOOD selector")
        val page = assertNotNull(available.page.food)
        assertEquals(query, page.query)
        assertEquals(FrozenDirectory.scope.companyId, page.companyId)
        assertEquals(FrozenDirectory.scope.branchId, page.branchId)
        assertEquals(FrozenDirectory.ownerId, page.actorId)
        assertEquals(Revision(10), page.branchRevision, "Scripted reply metadata is mapped, not an authority proof")
        assertEquals(FrozenDirectory.observedAt, page.observedAt)
        val body = assertIs<LocalFoodUiBody.Recipients>(page.body)
        assertEquals(FrozenDirectory.orderId, body.orderId)
        assertEquals(Revision(7), body.orderStorageRevision)
        assertTrue(body.recipients.size <= 20)
        assertEquals(body.recipients.size, body.recipients.map { it.actorId }.distinct().size)
        return page
    }

    private fun assertRecipientSet(expected: List<LocalFoodRecipientV2>, actual: LocalFoodUiBody.Recipients) {
        assertEquals(expected.size, actual.recipients.size)
        assertEquals(expected.map { it.actorId }.toSet(), actual.recipients.map { it.actorId }.toSet())
        assertEquals(expected.associate { it.actorId to it.label }, actual.recipients.associate { it.actorId to it.label })
    }

    private fun assertReadOnly(journal: RejectingReadJournal, endpoint: ControlledDirectoryEndpoint) {
        assertTrue(journal.calls.isEmpty(), "No journal find/pending/stage/dispatch/remember/acknowledge/close")
        assertEquals(0, endpoint.executeCalls, "Discovery never dispatches a mutation")
        assertEquals(0, endpoint.lookupCalls, "Discovery never probes a business operation")
        assertTrue(endpoint.rejectedCalls.isEmpty(), "No unrelated endpoint/authentication/native port")
    }
}

/** Exact frozen HANDOFF-A/people values; synthetic seams do not activate these permissions. */
private object FrozenDirectory {
    val readKey = ReadKey("sales.food.handoff-directory", 2)
    val scope = BranchScope(DomainId("a0000000-0000-4000-8000-000000000001"),
        DomainId("a0000000-0000-4000-8000-000000000002"), DomainId("a0000000-0000-4000-8000-000000000004"))
    val ownerId = DomainId("b0000000-0000-4000-8000-000000000001")
    val deviceId = DomainId("d0000000-0000-4000-8000-000000000001")
    val sessionId = DomainId("e0000000-0000-4000-8000-000000000001")
    val orderId = DomainId("f0000000-0000-4000-8000-000000000003")
    val orderRevision = Revision(7)
    val observedAt = UtcInstant.parseCanonical("2026-10-30T09:00:00Z")
    // This disabled account is only a controlled scan coordinate, never an eligible item.
    val filteredEmployeeCoordinate = DomainId("c2000000-0000-4000-8000-000000000001")
    val recipients = (1..21).map { ordinal ->
        val labelOrdinal = ordinal.toString().padStart(2, '0')
        LocalFoodRecipientV2(DomainId("c1000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}"),
            "Recipient $labelOrdinal / مستلم $labelOrdinal", Revision(1), Revision(10))
    }

    // Missing boot/connection coordinates are constructor-only placeholders, not admission IDs.
    fun constructorCoordinate(ordinal: Int): DomainId =
        DomainId("7f020000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")

    fun constructorOnlyView(): LocalSessionView = LocalSessionView(
        ownerId, scope, deviceId, sessionId, SessionGeneration(1), LocalLoginIdentifier("synthetic-food-source"),
        ActivatedLocalPolicy(scope, ownerId, Revision(10), emptyList(), LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(constructorCoordinate(1), 1), MonotonicMoment(constructorCoordinate(1), 2),
    )
}

/** Fresh zero-filled constructor seam on every read; these are not production credentials. */
private class FreshConstructorOnlyCredentials : LocalWorkflowCredentialSource {
    var acquireCalls = 0
        private set
    override suspend fun acquire(): LocalWorkflowCredentialLease {
        acquireCalls++
        return LocalWorkflowCredentialLease(LocalGatewayCredentials(FrozenDirectory.constructorCoordinate(2),
            OpaqueCredential(ByteArray(16)))) { true }
    }
}

/** No successful in-memory journal implementation: every journal operation records and rejects. */
private class RejectingReadJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    val calls = mutableListOf<String>()
    internal override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> = reject("find")
    internal override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = reject("pending")
    internal override suspend fun stage(intent: StoredClientIntentV1): JournalWrite = reject("stage")
    internal override suspend fun markDispatched(operationId: OperationId): JournalWrite = reject("markDispatched")
    internal override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = reject("remember")
    internal override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = reject("acknowledge")
    override suspend fun close(): Unit = reject("close")
    private fun reject(port: String): Nothing {
        calls += port
        throw AssertionError("Unbound controlled journal operation: $port")
    }
}

private data class ControlledDirectoryPage(val expectedAfter: DomainId?, val result: LocalFoodHandoffDirectoryResultV2)
private val directoryWire = Json { encodeDefaults = true }

/** Typed Data construction only; no Master handler, authorization evaluator, storage or RPC. */
private class ControlledDirectoryEndpoint(
    private val view: LocalSessionView,
    private val script: List<ControlledDirectoryPage>,
) : LocalMasterClient {
    val keys = mutableListOf<ReadKey>()
    val queries = mutableListOf<LocalFoodHandoffQueryV2.Recipients>()
    val rejectedCalls = mutableListOf<String>()
    var currentCalls = 0
        private set
    var executeCalls = 0
        private set
    var lookupCalls = 0
        private set

    override val health: StateFlow<LocalMasterEndpointHealth> get() = reject("health")
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = reject("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = reject("refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = reject("logout")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        currentCalls++
        return LocalSessionViewResult.Current(view)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++
        reject("execute")
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookupCalls++
        reject("lookup")
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        val expected = script.getOrNull(queries.size) ?: reject("additional directory read")
        assertEquals(FrozenDirectory.readKey, request.query, "Exact frozen public v2 route")
        val requestBytes = request.payload.copyBytes()
        val decoded = try { directoryWire.decodeFromString(LocalFoodHandoffQueryV2.serializer(), requestBytes.decodeToString()) }
            finally { requestBytes.fill(0) }
        val selected = assertIs<LocalFoodHandoffQueryV2.Recipients>(decoded)
        assertEquals(LocalFoodHandoffQueryV2.Recipients(FrozenDirectory.orderId, Revision(7), expected.expectedAfter), selected)
        keys += request.query
        queries += selected
        val replyBytes = directoryWire.encodeToString(LocalFoodHandoffDirectoryResultV2.serializer(), expected.result).encodeToByteArray()
        val payload = try { StoredBytes(replyBytes) } finally { replyBytes.fill(0) }
        return ReadReply.Data(payload, Revision(10), FrozenDirectory.observedAt)
    }
    private fun reject(port: String): Nothing {
        rejectedCalls += port
        throw AssertionError("Unbound controlled endpoint port: $port")
    }
}
