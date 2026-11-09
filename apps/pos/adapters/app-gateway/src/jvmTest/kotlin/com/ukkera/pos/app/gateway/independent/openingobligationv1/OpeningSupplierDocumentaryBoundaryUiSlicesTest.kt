package com.ukkera.pos.app.gateway.independent.openingobligationv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Frozen OSD-BND v1 exact-retention E slices through actual gateway staging, pending
 * presentation and an independently supplied read DTO. The client/session/lookup replies
 * and memory-only journal are controlled seams, not Master authority or durable capture.
 * No posting, installed/language-switch UI, reopen, saved-stage editing or whole J passes.
 */
class OpeningSupplierDocumentaryBoundaryUiSlicesTest {
    private val f = OpeningSupplierDocumentaryBoundaryUiInputs

    @Test
    fun osdBnd01OneByteStagePendingAndSuppliedReadKeepExactOriginal() = runTest {
        retainedSlice("ascii-1")
    }

    @Test
    fun osdBnd01MaximumAsciiStagePendingAndSuppliedReadKeepExactOriginal() = runTest {
        retainedSlice("ascii-4096")
    }

    @Test
    fun osdBnd02MaximumUnicodeStagePendingAndSuppliedReadKeepEveryScalar() = runTest {
        retainedSlice("unicode-4096")
    }

    @Test
    fun osdBnd03WhitespaceStagePendingAndSuppliedReadKeepEveryOriginalByte() = runTest {
        retainedSlice("whitespace-newlines")
    }

    private suspend fun retainedSlice(fixtureId: String) {
        val original = f.accepted.single { it.fixtureId == fixtureId }
        assertEquals(original.expectedByteSize, original.raw.size, fixtureId)
        assertEquals(original.assetSha256, sha256(original.raw).value, fixtureId)
        assertContentEquals(original.raw, original.text().encodeToByteArray(throwOnInvalidSequence = true), fixtureId)
        val control = OpeningDocumentaryBoundaryUiControl(original)
        try {
            val forms = assertIs<LocalReadResult.Available>(control.gateway.read(query(includeCosts = false))).page.forms
            val form = forms.single { it.action == LocalAction.SUPPLIER_OPENING_RECORD }
            assertTrue(control.endpoint.reads.isEmpty(), "Entry form does not infer or read opening debt")
            val staged = assertIs<LocalStageResult.Staged>(control.gateway.stage(f.intent(original, form.version))).operation
            assertEquals(original.operation, staged.operationId, fixtureId)
            assertEquals(LocalPendingPhase.STAGED, staged.phase, fixtureId)
            assertNull(staged.recordedDecision, fixtureId)
            exactBody(staged.summary, original, "stage")
            exactSourceMetadata(staged.summary, original)

            val stored = control.journal.entries.getValue(original.operation)
            assertEquals(f.scope, stored.owner.scope, fixtureId)
            assertEquals(f.actor, stored.owner.actorId, fixtureId)
            val request = stored.request()
            assertEquals(original.operation, request.operationId, fixtureId)
            assertEquals(LocalOpeningSupplierObligationProfile.COMMAND, request.command, fixtureId)
            val decoded = LocalOpeningSupplierObligationCodec.decodeRequest(request.payload)
            assertEquals(f.request(original), decoded, fixtureId)
            assertContentEquals(original.raw,
                decoded.sourceDocument.text.encodeToByteArray(throwOnInvalidSequence = true), fixtureId)
            assertEquals(1, control.journal.stageWrites, fixtureId)
            assertNull(stored.recorded, fixtureId)
            assertTrue(control.endpoint.executions.isEmpty(), "N slice: staging must never dispatch a mutation")

            val recovered = assertIs<LocalPendingResult.Available>(control.gateway.pending()).operations.single()
            assertEquals(original.operation, recovered.operationId, fixtureId)
            assertEquals(LocalPendingPhase.STAGED, recovered.phase, fixtureId)
            exactBody(recovered.summary, original, "pending")
            exactSourceMetadata(recovered.summary, original)
            assertEquals(request.payload, control.journal.entries.getValue(original.operation).request().payload, fixtureId)
            assertEquals(listOf(original.operation), control.endpoint.lookups.map { it.operationId }, fixtureId)
            assertTrue(control.endpoint.lookups.all { it.originalActorId == f.actor }, fixtureId)

            // This read projection is supplied independently, not an observed result of stage.
            val read = assertIs<LocalReadResult.Available>(control.gateway.read(query(includeCosts = true,
                reference = LocalRecordReference(LocalRecordKind.OPENING_SUPPLIER_OBLIGATION, original.obligation)))).page
            val record = read.records.single()
            assertEquals(LocalRecordReference(LocalRecordKind.OPENING_SUPPLIER_OBLIGATION, original.obligation), record.reference)
            exactBody(record.values, original, "supplied read")
            exactSourceMetadata(record.values, original)
            for (value in listOf(original.operation.value, f.actor.value)) {
                assertTrue(record.values.any { it.value.english == value && it.value.arabic == value }, fixtureId)
            }
            assertTrue(control.endpoint.reads.all { it.query in setOf(LocalConfigurationProfile.QUERY,
                LocalOpeningSupplierObligationProfile.READ) }, fixtureId)
            assertEquals(1, control.endpoint.reads.count { it.query == LocalOpeningSupplierObligationProfile.READ }, fixtureId)
            assertTrue(control.endpoint.executions.isEmpty(), "N slice: pending and read never dispatch opening or other effects")
            assertNull(control.journal.entries.getValue(original.operation).recorded, fixtureId)
        } finally {
            control.journal.close()
        }
        // Only non-dispatch is bound from N. No payable/stock/cash/other ledger delta is observed.
    }

    private fun exactBody(values: List<LocalRecordValue>, original: OpeningDocumentaryBoundaryUiOriginal, stage: String) {
        // Observed label prefix selects body values only; its wording/numbering is a harness
        // binding, not an independent requirement or a fixed part-count/part-length oracle.
        val parts = values.filter { it.label.english.startsWith("Original documentary text") }
        assertTrue(parts.isNotEmpty(), "${original.fixtureId} $stage must expose the complete original")
        for (part in parts) {
            for (text in listOf(part.value.english, part.value.arabic)) {
                text.encodeToByteArray(throwOnInvalidSequence = true) // each readable part must be valid Unicode
                assertFalse(text.contains('\uFFFD'), "${original.fixtureId} $stage must not substitute replacement characters")
            }
        }
        assertContentEquals(original.raw,
            parts.joinToString("") { it.value.english }.encodeToByteArray(throwOnInvalidSequence = true),
            "${original.fixtureId} $stage English E")
        assertContentEquals(original.raw,
            parts.joinToString("") { it.value.arabic }.encodeToByteArray(throwOnInvalidSequence = true),
            "${original.fixtureId} $stage Arabic E")
    }

    private fun exactSourceMetadata(values: List<LocalRecordValue>, original: OpeningDocumentaryBoundaryUiOriginal) {
        for (value in listOf(original.obligation.value, f.supplier.value, f.evidence.value, f.source,
            f.reference, f.date, f.terms)) {
            assertTrue(values.any { it.value.english == value && it.value.arabic == value },
                "${original.fixtureId} must preserve source metadata $value")
        }
        assertTrue(values.any { it.value.english.replace(",", "") == "1250.75 EGP" }, original.fixtureId)
    }

    private fun query(includeCosts: Boolean, reference: LocalRecordReference? = null) = LocalWorkflowQuery(
        LocalWorkflow.PURCHASING, reference = reference,
        purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.OPENING_OBLIGATIONS, includeCosts))
}

/** Reuses only the prior explicit memory journal; this is not durable native staging. */
private class OpeningDocumentaryBoundaryUiControl(original: OpeningDocumentaryBoundaryUiOriginal) {
    private val f = OpeningSupplierDocumentaryBoundaryUiInputs
    val journal = OpeningSupplierControlledJournal(LocalClientIdentity(f.scope, f.actor, f.device))
    val endpoint = OpeningDocumentaryBoundaryControlledEndpoint(original)
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(f.connection, OpaqueCredential(ByteArray(16) { 7 }))) { true }
    }, LocalWorkflowInstallation(setOf(LocalOpeningSupplierObligationProfile.COMMAND),
        setOf(LocalConfigurationProfile.QUERY, LocalOpeningSupplierObligationProfile.READ)))
}

/** Supplied sessions/read/lookup replies only; no authentication, business host or RPC. */
private class OpeningDocumentaryBoundaryControlledEndpoint(private val original: OpeningDocumentaryBoundaryUiOriginal) : LocalMasterClient {
    private val f = OpeningSupplierDocumentaryBoundaryUiInputs
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    val executions = mutableListOf<MutationRequest>()
    private fun unexpected(operation: String): Nothing = throw AssertionError("Unexpected boundary client call: $operation")
    override val health: StateFlow<LocalMasterEndpointHealth> get() = unexpected("health")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        LocalSessionViewResult.Current(f.sessionView())
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        return when (request.query) {
            LocalConfigurationProfile.QUERY -> f.supplierReply()
            LocalOpeningSupplierObligationProfile.READ -> {
                val requested = LocalOpeningSupplierObligationCodec.decodeQuery(request.payload)
                assertEquals(original.obligation, requested.id)
                ReadReply.Data(LocalOpeningSupplierObligationCodec.page(LocalOpeningSupplierObligationPageV1(
                    requested, listOf(f.view(original)), null)), Revision(1), f.recordedAt)
            }
            else -> unexpected("read")
        }
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        return unexpected("execute")
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = unexpected("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = unexpected("refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = unexpected("logout")
}
