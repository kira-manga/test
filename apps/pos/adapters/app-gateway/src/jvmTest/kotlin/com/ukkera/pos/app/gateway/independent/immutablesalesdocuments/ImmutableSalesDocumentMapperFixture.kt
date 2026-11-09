package com.ukkera.pos.app.gateway.independent.immutablesalesdocuments

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
import kotlin.time.Instant

/** Real routed mapper and credential lease, controlled endpoint/session DATA only.
 * All authentication, mutation and original-result ports fail. No native authority,
 * persistence, admitted RPC, preparation, printing or ledger integration is supplied.
 */
internal class ImmutableSalesDocumentMapperFixture(
    val source: ImmutableSalesDocumentSupportingSource,
    customerReceiptHint: GrantEffect? = GrantEffect.ALLOW,
    correctionInstalled: Boolean = true,
) : AutoCloseable {
    private val f = ImmutableSalesDocumentSupportingInputs
    private val wire = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; coerceInputValues = false }
    var payload: StoredBytes = encode(LocalCollectionReadResultV1.PaidSale(source.value))
    var denial: ReadFailure? = null
    var leaseCurrent: Boolean = true
    var afterRead: () -> Unit = {}
    val requests = mutableListOf<ReadRequest>()
    var otherCalls: Int = 0
        private set
    val expectedLinkKinds = buildSet {
        if (customerReceiptHint == GrantEffect.ALLOW) add(LocalRecordKind.CUSTOMER_RECEIPT)
        if (correctionInstalled) add(LocalRecordKind.PAYMENT_CORRECTION_SOURCE)
    }
    private val credentials = LocalGatewayCredentials(f.bindingId("connection"), OpaqueCredential(ByteArray(32)))
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
            if (request.query != LocalCollectionReadCodec.QUERY) unexpected()
            val reply = denial?.let { ReadReply.Unavailable(it) }
                ?: ReadReply.Data(payload, Revision(1100), UtcInstant(Instant.parse("2026-11-04T00:00:00Z")))
            afterRead()
            return reply
        }
    }
    private val grants = buildList {
        if (customerReceiptHint != null) {
            add(receiptHint(GrantEffect.ALLOW))
            if (customerReceiptHint == GrantEffect.DENY) add(receiptHint(GrantEffect.DENY))
        }
    }
    private fun receiptHint(effect: GrantEffect) = PermissionGrant(
        Permission(SecurityAction.CUSTOMER_RECEIPT_RECORD, PermissionVerb.PERFORM), f.scope, effect,
        namedSubjectId = f.viewer, targetIds = setOf(source.value.saleId))
    private val policy = ActivatedLocalPolicy(f.scope, f.viewer, f.policyRevision, grants, LocalSessionPolicy(), ThrottlePolicy())
    private val moment = MonotonicMoment(f.bindingId("monotonic-clock"), 1)
    private val view = LocalSessionView(f.viewer, f.scope, f.bindingId("device"), f.bindingId("authentication"), SessionGeneration(1),
        LocalLoginIdentifier("synthetic-document-viewer"), policy, moment, moment)
    private val installation = LocalWorkflowInstallation(
        setOf(LocalSalesStoreProfile.COLLECTION_COMMAND, LocalPaymentCorrectionCodec.COMMAND), buildSet {
            add(LocalCollectionReadCodec.QUERY)
            add(LocalRetailCustomerReceiptReadCodec.QUERY)
            if (correctionInstalled) add(LocalPaymentCorrectionCodec.QUERY)
        })
    private val access = GatewayAccess(endpoint, LocalWorkflowCredentialLease(credentials) { leaseCurrent }, view, installation)

    suspend fun read(cursor: String? = null, workflow: LocalWorkflow = LocalWorkflow.ORDERS): LocalWorkflowPage = readQuery(
        LocalWorkflowQuery(workflow, reference = LocalRecordReference(LocalRecordKind.SALE, source.value.saleId), cursor = cursor))
    suspend fun readQuery(query: LocalWorkflowQuery): LocalWorkflowPage = readLocalWorkflow(access, query)
    fun replace(result: LocalCollectionReadResultV1) { payload = encode(result) }
    fun removeRequiredField(name: String) {
        val root = Json.parseToJsonElement(payload.copyBytes().decodeToString()).jsonObject
        val value = root.getValue("value").jsonObject
        payload = StoredBytes(wire.encodeToString(JsonElement.serializer(), JsonObject(root +
            ("value" to JsonObject(value - name)))).encodeToByteArray())
    }
    fun assertOnlyOriginalSaleReads(count: Int = requests.size) {
        assertEquals(0, otherCalls)
        assertEquals(count, requests.size)
        requests.forEach { request ->
            assertEquals(LocalCollectionReadCodec.QUERY, request.query)
            assertEquals(LocalCollectionQueryV1.PaidSale(source.value.saleId),
                wire.decodeFromString(LocalCollectionQueryV1.serializer(), request.payload.copyBytes().decodeToString()))
        }
    }
    private fun encode(result: LocalCollectionReadResultV1): StoredBytes = StoredBytes(
        wire.encodeToString(LocalCollectionReadResultV1.serializer(), result).encodeToByteArray())
    private fun unexpected(): Nothing {
        otherCalls++
        error("Read-only document SUPPORT must not invoke other endpoint ports")
    }
    override fun close() { credentials.bearer.destroy() }
}
