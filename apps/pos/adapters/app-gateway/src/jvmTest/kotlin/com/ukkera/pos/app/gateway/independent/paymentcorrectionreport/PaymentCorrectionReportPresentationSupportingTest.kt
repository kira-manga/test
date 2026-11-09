package com.ukkera.pos.app.gateway.independent.paymentcorrectionreport

import com.ukkera.pos.app.LocalBranchReportAmountGroup
import com.ukkera.pos.app.LocalBranchReportQuery
import com.ukkera.pos.app.LocalBranchReportSection
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.gateway.GatewayAccess
import com.ukkera.pos.app.gateway.GatewayFailure
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.branchReportWorkflow
import com.ukkera.pos.app.gateway.currentReportProfile
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportQueryV1
import com.ukkera.pos.sales.store.LocalBranchReportReadCodec
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalBranchReportSectionV1
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
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen report v1 presentation SUPPORT. The real mapper receives controlled
 * ReadReply DATA through existing JVM friendship, not native/current-authority,
 * durable business or admitted RPC replies. No Compose/device execution is implied.
 */
class PaymentCorrectionReportPresentationSupportingTest {
    private val input = PaymentCorrectionReportInputs

    @Test
    fun pcrRep01SupportOriginalCloseAndLinkedAdjustmentHaveDistinctBilingualPresentation() = runTest {
        ReportMapperFixture(listOf(input.originalCashClose), LocalBranchReportSectionV1.CASH_SESSIONS).use { fixture ->
            val page = assertNotNull(fixture.read().branchReport)
            val document = page.documents.single()
            assertEquals(listOf(235000L, 236000L, 1000L, 50000L, 186000L), document.amounts.map { it.amount.minor })
            assertTrue(document.amounts.all { it.group == null })
            assertEquals(input.originalCashClose.businessDate, document.cashSessionBusinessDate)
            assertNull(document.paymentCorrection)
            fixture.assertOnlyReportRead()
        }
        ReportMapperFixture(listOf(input.cashCorrection)).use { fixture ->
            val workflow = fixture.read()
            val page = assertNotNull(workflow.branchReport)
            val document = page.documents.single()
            val original = document.amounts.filter { it.group == LocalBranchReportAmountGroup.ORIGINAL_CLOSE }
            val linked = document.amounts.filter { it.group == LocalBranchReportAmountGroup.LINKED_CORRECTION }
            assertEquals(listOf(235000L, 236000L, 1000L), original.map { it.amount.minor })
            assertEquals(listOf(-1000L, 1000L, 1000L, 0L), linked.map { it.amount.minor })
            assertEquals(0L, linked[0].amount.minor + linked[1].amount.minor)
            // Explanatory residual only; the original submitted variance above stays1000.
            assertEquals(0L, original[2].amount.minor - linked[2].amount.minor)
            (document.amounts.map { it.label } + document.status).forEach(::bilingual)
            assertTrue(original.all { "original" in it.label.english.lowercase() })
            assertTrue(document.status.english.contains("no money movement"))
            val detail = assertNotNull(document.paymentCorrection)
            assertEquals(input.payment, detail.paymentAttemptId)
            assertEquals(input.closeOperation, detail.originalCloseOperationId)
            assertEquals(input.originalCollector, detail.originalCollectorId)
            assertEquals(input.correctionActor, detail.correctionActorId)
            assertNotEquals(detail.originalCollectorId, detail.correctionActorId)
            assertEquals(input.recordedAt, detail.recordedAt)
            assertEquals(input.sale, document.relatedDocumentId)
            assertEquals(input.originalSession, document.cashSessionId)
            assertTrue(detail.previousClassification.label.english.contains("Card"))
            assertTrue(detail.correctedClassification.label.english.contains("Cash"))
            bilingual(detail.previousClassification.label)
            bilingual(detail.correctedClassification.label)
            assertNull(document.cashSessionBusinessDate)
            assertTrue(workflow.records.isEmpty() && workflow.forms.isEmpty())
            fixture.assertOnlyReportRead()
        }
    }

    @Test
    fun pcrRep02SupportFractionalExternalLabelsAndAmountsRemainExactWithoutNewCash() = runTest {
        ReportMapperFixture(listOf(input.externalCorrection)).use { fixture ->
            val document = assertNotNull(fixture.read().branchReport).documents.single()
            assertEquals(listOf(62550L, 62550L, 0L, -2735L, 2735L, 0L, 0L), document.amounts.map { it.amount.minor })
            assertEquals(3, document.amounts.count { it.group == LocalBranchReportAmountGroup.ORIGINAL_CLOSE })
            assertEquals(4, document.amounts.count { it.group == LocalBranchReportAmountGroup.LINKED_CORRECTION })
            val detail = assertNotNull(document.paymentCorrection)
            assertEquals(input.cardTender, detail.previousClassification.tenderId)
            assertEquals(Revision(7), detail.previousClassification.policyRevision)
            assertEquals(input.walletTender, detail.correctedClassification.tenderId)
            assertEquals(Revision(2), detail.correctedClassification.policyRevision)
            assertTrue(detail.previousClassification.label.english.contains("Card"))
            assertTrue(detail.correctedClassification.label.english.contains("wallet"))
            bilingual(detail.previousClassification.label)
            bilingual(detail.correctedClassification.label)
            document.amounts.forEach { bilingual(it.label) }
            fixture.assertOnlyReportRead()
        }
    }

    @Test
    fun pcrRep05SupportSafeSummaryAndPopulatedPrivateFieldProbesExposeNoCanaries() = runTest {
        ReportMapperFixture(listOf(input.cashCorrection)).use { fixture ->
            val safe = fixture.payload.copyBytes().decodeToString() + fixture.read().toString()
            input.privateCanaries.forEach { assertFalse(it in safe) }
            fixture.assertOnlyReportRead()
        }
        val forbiddenFields = listOf("rawPaymentReference", "privatePaymentEvidence", "customerContact", "privateReason")
        forbiddenFields.zip(input.privateCanaries).forEach { (field, canary) ->
            ReportMapperFixture(listOf(input.cashCorrection)).use { fixture ->
                fixture.addPrivateRowField(field, canary)
                assertTrue(canary in fixture.payload.copyBytes().decodeToString())
                val failure = assertFailsWith<IllegalArgumentException> { fixture.read() }
                input.privateCanaries.forEach { assertFalse(it in failure.message.orEmpty()) }
                fixture.assertOnlyReportRead()
            }
        }
        // These nonempty wire probes qualify strict summary decoding, not redaction of
        // genuine original payment/evidence/customer sources or subscriptions/exports.
    }

    @Test
    fun pcrRep03And07SupportMalformedOrFutureTimestampCannotProducePresentation() = runTest {
        listOf("not-a-timestamp", "2026-11-03T99:00:00Z", "2026-11-03T04:00:01+02:00").forEach { invalid ->
            ReportMapperFixture(listOf(input.cashCorrection)).use { fixture ->
                val original = fixture.payload.copyBytes().decodeToString()
                assertTrue(input.recordedAt.canonicalString() in original)
                fixture.payload = StoredBytes(original.replace(input.recordedAt.canonicalString(), invalid).encodeToByteArray())
                assertFailsWith<IllegalArgumentException> { fixture.read() }
                fixture.assertOnlyReportRead()
            }
        }
        val future = input.cashCorrection.copy(paymentCorrection =
            input.cashCorrection.paymentCorrection!!.copy(recordedAt = input.laterRecordedAt))
        ReportMapperFixture(listOf(future)).use { fixture ->
            assertEquals(LocalServiceProblem.UNAVAILABLE, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            fixture.assertOnlyReportRead()
        }
    }

    @Test
    fun pcrRep07SupportEmptyReadKeepsActualCheckpointAndChangedCutRequiresRestart() = runTest {
        ReportMapperFixture(emptyList(), observedAt = input.earlierObservedAt).use { fixture ->
            val page = assertNotNull(fixture.read().branchReport)
            assertTrue(page.documents.isEmpty())
            assertEquals(input.earlierObservedAt, page.observedAt)
            assertEquals(input.branchRevision, page.branchRevision)
            assertEquals(input.reader, page.actorId)
            assertEquals(input.company, page.companyId)
            assertEquals(input.branch, page.branchId)
            assertNull(page.nextAfter)
            fixture.assertOnlyReportRead()
        }
        ReportMapperFixture(listOf(input.cashCorrection)).use { fixture ->
            assertEquals(LocalServiceProblem.REPORT_RESTART_REQUIRED,
                assertFailsWith<GatewayFailure> { fixture.read(Revision(36)) }.problem)
            fixture.assertOnlyReportRead()
        }
    }

    @Test
    fun pcrRep05And06SupportControlledDenialOrEndedLeaseCannotReturnAReport() = runTest {
        ReportMapperFixture(listOf(input.cashCorrection)).use { fixture ->
            fixture.denied = true
            assertEquals(LocalServiceProblem.DENIED, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            fixture.assertOnlyReportRead()
        }
        ReportMapperFixture(listOf(input.cashCorrection)).use { fixture ->
            fixture.leaseCurrent = false
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, fixture.otherCalls)
        }
    }

    private fun bilingual(text: LocalText) {
        assertTrue(text.english.isNotBlank() && text.arabic.isNotBlank())
        assertNotEquals(text.english, text.arabic)
    }
}

/** Controlled wire/view DATA only. Every nonreport port fails on use. */
private class ReportMapperFixture(
    rows: List<LocalBranchReportRowV1>,
    private val section: LocalBranchReportSectionV1 = LocalBranchReportSectionV1.PAYMENT_CORRECTIONS,
    private val observedAt: UtcInstant = PaymentCorrectionReportInputs.observedAt,
) : AutoCloseable {
    private val input = PaymentCorrectionReportInputs
    private val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val profile = currentReportProfile(section)
    var payload = StoredBytes(wire.encodeToString(LocalBranchReportResultV1.serializer(),
        LocalBranchReportResultV1.Page(section, LocalBranchReportDeliveryV1.VIEW, null, rows, null, profile)).encodeToByteArray())
    var denied = false
    var leaseCurrent = true
    val requests = mutableListOf<ReadRequest>()
    var otherCalls = 0
        private set
    private val credentials = LocalGatewayCredentials(input.id(80), OpaqueCredential(ByteArray(32) { 0x33 }))
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
            return if (denied) ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
                else ReadReply.Data(payload, input.branchRevision, observedAt)
        }
    }
    private val scope = BranchScope(input.organization, input.company, input.branch)
    private val policy = ActivatedLocalPolicy(scope, input.reader, Revision(1), emptyList(),
        LocalSessionPolicy(1), ThrottlePolicy(0, 1000, 1000, 1000))
    private val moment = MonotonicMoment(input.id(81), 100000)
    private val view = LocalSessionView(input.reader, scope, input.device, input.id(82), SessionGeneration(1),
        LocalLoginIdentifier("synthetic-report-reader"), policy, moment, moment)
    private val access = GatewayAccess(endpoint, LocalWorkflowCredentialLease(credentials) { leaseCurrent }, view,
        LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.REPORTS))

    suspend fun read(expectedRevision: Revision? = null): LocalWorkflowPage = branchReportWorkflow(access,
        LocalWorkflowQuery(LocalWorkflow.REPORTS, branchReport = LocalBranchReportQuery(
            LocalBranchReportSection.valueOf(section.name), expectedBranchRevision = expectedRevision)))

    fun addPrivateRowField(name: String, value: String) {
        val page = Json.parseToJsonElement(payload.copyBytes().decodeToString()).jsonObject
        val row = page.getValue("rows").jsonArray.single().jsonObject
        val changed = JsonObject(page + ("rows" to JsonArray(listOf(JsonObject(row + (name to JsonPrimitive(value)))))))
        payload = StoredBytes(wire.encodeToString(JsonElement.serializer(), changed).encodeToByteArray())
    }

    fun assertOnlyReportRead() {
        assertEquals(0, otherCalls)
        val request = requests.single()
        assertEquals(LocalBranchReportReadCodec.QUERY, request.query)
        val query = wire.decodeFromString(LocalBranchReportQueryV1.serializer(), request.payload.copyBytes().decodeToString())
        assertEquals(section, query.section)
        assertEquals(profile, query.profile)
        assertEquals(LocalBranchReportDeliveryV1.VIEW, query.delivery)
        assertEquals(5, query.limit)
    }

    private fun unexpected(): Nothing {
        otherCalls++
        error("A report presentation SUPPORT test must not use authentication, mutations or original-result lookup")
    }

    override fun close() = credentials.bearer.destroy()
}
