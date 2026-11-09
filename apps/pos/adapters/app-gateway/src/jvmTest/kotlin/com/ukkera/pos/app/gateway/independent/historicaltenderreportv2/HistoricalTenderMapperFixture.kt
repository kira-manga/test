package com.ukkera.pos.app.gateway.independent.historicaltenderreportv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

/** Controlled report wire and session-view DATA only. Every nonreport port fails.
 * The actual mapper is invoked; no native/current-authority/admitted RPC or UI rendering is bound. */
internal class HistoricalTenderMapperFixture(private val selected: HistoricalTenderGatewayDocument) : AutoCloseable {
    private val f = HistoricalTenderGatewayInputs
    private val previous = f.previous
    private val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    var payload = encode(f.page(selected))
    var branchRevision = selected.branchRevision
    var observedAt = selected.observedAt
    var denied = false
    var leaseCurrent = true
    var afterRead: () -> Unit = {}
    val requests = mutableListOf<ReadRequest>()
    var otherCalls = 0
        private set
    private val credentials = LocalGatewayCredentials(previous.id(90), OpaqueCredential(ByteArray(32)))
    private val endpoint = object : LocalMasterClient {
        override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
        override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
            explicitDeviceMove: Boolean): LocalAuthenticationResult = unexpected()
        override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = unexpected()
        override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = unexpected()
        override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = unexpected()
        override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply = unexpected()
        override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply = unexpected()
        override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
            requests += request
            val result = if (denied) ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
                else ReadReply.Data(payload, branchRevision, observedAt)
            afterRead()
            return result
        }
    }
    private val scope = BranchScope(previous.organization, previous.company, previous.branch)
    private val policy = ActivatedLocalPolicy(scope, previous.reader, Revision(1), emptyList(),
        LocalSessionPolicy(), ThrottlePolicy())
    private val moment = MonotonicMoment(previous.id(91), 1)
    private val view = LocalSessionView(previous.reader, scope, previous.device, previous.id(92), SessionGeneration(1),
        LocalLoginIdentifier("synthetic-selected-report-reader"), policy, moment, moment)
    private val access = GatewayAccess(endpoint, LocalWorkflowCredentialLease(credentials) { leaseCurrent }, view,
        LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.REPORTS))

    suspend fun read(expectedRevision: Revision? = null): LocalWorkflowPage = branchReportWorkflow(access,
        LocalWorkflowQuery(LocalWorkflow.REPORTS, branchReport = LocalBranchReportQuery(
            LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS, expectedBranchRevision = expectedRevision,
            selectedSaleId = selected.row.id)))

    fun replace(document: HistoricalTenderGatewayDocument,
        row: LocalBranchReportRowV1 = document.row) {
        payload = encode(f.page(document, row = row))
        branchRevision = document.branchRevision
        observedAt = document.observedAt
    }

    fun removeSelectedSource() {
        val page = Json.parseToJsonElement(payload.copyBytes().decodeToString()).jsonObject
        payload = jsonBody(JsonObject(page + ("rows" to JsonArray(emptyList()))))
    }

    fun addPrivatePaymentField(name: String, value: String) {
        val page = Json.parseToJsonElement(payload.copyBytes().decodeToString()).jsonObject
        val row = page.getValue("rows").jsonArray.single().jsonObject
        val detail = row.getValue("retailTenderBreakdown").jsonObject
        val payments = detail.getValue("payments").jsonArray
        val changedPayments = JsonArray(payments.mapIndexed { index, part ->
            if (index == 0) JsonObject(part.jsonObject + (name to JsonPrimitive(value))) else part
        })
        val changedRow = JsonObject(row + ("retailTenderBreakdown" to
            JsonObject(detail + ("payments" to changedPayments))))
        payload = jsonBody(JsonObject(page + ("rows" to JsonArray(listOf(changedRow)))))
    }

    fun assertOnlySelectedReportReads(count: Int = 1) {
        assertEquals(0, otherCalls)
        assertEquals(count, requests.size)
        for (request in requests) {
            assertEquals(LocalBranchReportReadCodec.QUERY, request.query)
            val query = wire.decodeFromString(LocalBranchReportQueryV1.serializer(), request.payload.copyBytes().decodeToString())
            assertEquals(f.query(selected), query)
        }
    }

    private fun encode(page: LocalBranchReportResultV1.Page): StoredBytes =
        StoredBytes(wire.encodeToString(LocalBranchReportResultV1.serializer(), page).encodeToByteArray())
    private fun jsonBody(value: JsonObject): StoredBytes =
        StoredBytes(wire.encodeToString(JsonElement.serializer(), value).encodeToByteArray())
    private fun unexpected(): Nothing {
        otherCalls++
        error("A selected-report SUPPORT mapper must not use authentication, mutations or original-result lookup")
    }
    override fun close() = credentials.bearer.destroy()
}
