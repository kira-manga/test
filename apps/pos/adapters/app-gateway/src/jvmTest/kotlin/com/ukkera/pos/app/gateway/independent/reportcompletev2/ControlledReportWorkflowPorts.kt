package com.ukkera.pos.app.gateway.independent.reportcompletev2

import com.ukkera.pos.app.LocalBranchReportAuditCommand
import com.ukkera.pos.app.LocalBranchReportAuditSlot
import com.ukkera.pos.app.LocalBranchReportExportLifetime
import com.ukkera.pos.app.LocalBranchReportExportProgress
import com.ukkera.pos.app.LocalBranchReportExportResult
import com.ukkera.pos.app.LocalBranchReportExportSelection
import com.ukkera.pos.app.LocalBranchReportFileDelivery
import com.ukkera.pos.app.LocalBranchReportFileDestinationKind
import com.ukkera.pos.app.LocalBranchReportFileReceipt
import com.ukkera.pos.app.LocalReportCommitGate
import com.ukkera.pos.app.LocalReportCommitStart
import com.ukkera.pos.app.LocalReportLinkOutcome
import com.ukkera.pos.app.LocalReportNativeChoice
import com.ukkera.pos.app.LocalReportReceiptDisk
import com.ukkera.pos.app.LocalReportRecoveredPublication
import com.ukkera.pos.app.auditCommand
import com.ukkera.pos.app.decodeLocalReportReceipt
import com.ukkera.pos.app.encodeLocalReportReceipt
import com.ukkera.pos.app.gateway.GatewayAccess
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.exportLocalBranchReport
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalBranchReportExportCodecV1
import com.ukkera.pos.sales.store.LocalBranchReportExportRequestV1
import com.ukkera.pos.sales.store.LocalBranchReportExportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportExportStageV1
import com.ukkera.pos.sales.store.LocalBranchReportExportViewV1
import com.ukkera.pos.sales.store.LocalBranchReportQueryV1
import com.ukkera.pos.sales.store.LocalBranchReportReadCodec
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
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
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Wire construction mechanics only. This Json does not generate any expected CSV or oracle. */
private val controlledWire = Json { encodeDefaults = true }

private fun <T> wire(value: T, serializer: KSerializer<T>): StoredBytes {
    val bytes = controlledWire.encodeToString(serializer, value).encodeToByteArray()
    return try { StoredBytes(bytes) } finally { bytes.fill(0) }
}

private fun <T> unwire(body: StoredBytes, serializer: KSerializer<T>): T {
    val bytes = body.copyBytes()
    return try { controlledWire.decodeFromString(serializer, bytes.decodeToString()) }
    finally { bytes.fill(0) }
}

internal fun StoredBytes.observedHex(): String {
    val bytes = copyBytes()
    return try { bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') } }
    finally { bytes.fill(0) }
}

internal fun receiptWireText(receipt: LocalBranchReportFileReceipt): String {
    val bytes = encodeLocalReportReceipt(receipt)
    return try { bytes.decodeToString() } finally { bytes.fill(0) }
}

/**
 * Executes the real common workflow and concrete common receipt/delivery state machine.
 * The master and native primitives below are controlled ports. Their volatile map, synchronous
 * gate and scripted acknowledgements prove no real grant, RPC, filesystem force or restart.
 * JVM test compilation is a friend of the genuine UI main artifact for internal native seams.
 * No visibility or override diagnostic suppression is used; this grants no production authority.
 */
internal class ControlledReportWorkflowHarness(reads: List<FrozenWorkflowRead>) {
    val events = mutableListOf<String>()
    val files = ControlledWorkflowFiles(events)
    val master = ControlledWorkflowMaster(files, reads, events)
    val interactive = files.newLifetime { true }
    var leaseCurrent = true
    val progress = mutableListOf<LocalBranchReportExportProgress>()

    // Missing exact organization/device/session/boot/connection coordinates are constructor-only
    // synthetic placeholders, not I32 export identities or an authenticated production session.
    private fun coordinate(ordinal: Int): DomainId =
        DomainId("7f320000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    private val scope = BranchScope(coordinate(1), FrozenReportWorkflowInputs.companyId,
        FrozenReportWorkflowInputs.branchId)
    private val view = LocalSessionView(
        FrozenReportWorkflowInputs.actorId, scope, coordinate(2), coordinate(3), SessionGeneration(1),
        LocalLoginIdentifier("synthetic-report-workflow"),
        ActivatedLocalPolicy(scope, FrozenReportWorkflowInputs.actorId, Revision(1), emptyList(),
            LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(coordinate(4), 1), MonotonicMoment(coordinate(4), 2),
    )
    // Constructor-only owned zero bytes, never real credentials; no code reads or logs them.
    private val lease = LocalWorkflowCredentialLease(LocalGatewayCredentials(coordinate(5),
        OpaqueCredential(ByteArray(16)))) { leaseCurrent }
    private val access = GatewayAccess(master, lease, view, LocalWorkflowInstallation(
        setOf(LocalBranchReportExportCodecV1.COMMAND), setOf(LocalBranchReportReadCodec.QUERY)))

    suspend fun run(selection: LocalBranchReportExportSelection = FrozenReportWorkflowInputs.completeSelection):
        LocalBranchReportExportResult = exportLocalBranchReport(access, selection, files, interactive) { progress += it }

    fun destroy() { interactive.end(); lease.destroy() }
}

internal data class ObservedWorkflowCommand(
    val request: MutationRequest,
    val body: LocalBranchReportExportRequestV1,
    val stagedOriginal: LocalBranchReportAuditCommand,
)

/** Typed script boundary only, not the production Master endpoint or a permission evaluator. */
internal class ControlledWorkflowMaster(
    private val files: ControlledWorkflowFiles,
    val reads: List<FrozenWorkflowRead>,
    private val events: MutableList<String>,
) : LocalMasterClient {
    val readQueries = mutableListOf<LocalBranchReportQueryV1>()
    val readStages = mutableListOf<String>()
    val protectedReplies = mutableListOf<String>()
    val commands = mutableListOf<ObservedWorkflowCommand>()
    val deniedReadStages = mutableSetOf<String>()
    var denyBegin = false
    var denyFinish = false
    var beforeRead: (FrozenWorkflowRead) -> Unit = {}
    var afterRead: (FrozenWorkflowRead) -> Unit = {}
    private var beginView: LocalBranchReportExportViewV1? = null

    override val health: StateFlow<LocalMasterEndpointHealth> get() = forbidden("health")
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = forbidden("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = forbidden("refresh")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = forbidden("current")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = forbidden("logout")
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply = forbidden("lookup")

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        assertEquals(LocalBranchReportReadCodec.QUERY, request.query, "No VIEW reload or unrelated read is permitted")
        val expected = reads.getOrNull(readQueries.size) ?: forbidden("unexpected additional EXPORT read")
        val query = unwire(request.payload, LocalBranchReportQueryV1.serializer())
        readQueries += query
        readStages += expected.stage
        assertEquals(expected.query, query, "Exact frozen section/cursor/limit/EXPORT query at ${expected.stage}")
        val acknowledgement = assertNotNull(assertNotNull(files.latest).begin?.acknowledgement,
            "Data cannot precede the recorded Begin acknowledgement")
        assertEquals("REQUESTED", acknowledgement.stage.name)
        events += "master:read:${expected.stage}"
        beforeRead(expected)
        if (expected.stage in deniedReadStages) return ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        val reply = ReadReply.Data(wire(expected.page, LocalBranchReportResultV1.serializer()),
            expected.revision, expected.observedAt)
        protectedReplies += expected.stage
        afterRead(expected)
        return reply
    }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        assertEquals(LocalBranchReportExportCodecV1.COMMAND, request.command, "No business command is allowed")
        assertNull(request.actionApprovalId)
        val body = LocalBranchReportExportCodecV1.decodeRequest(request.payload)
        val initial = assertNotNull(files.initial)
        val slot = if (body is LocalBranchReportExportRequestV1.Begin) LocalBranchReportAuditSlot.BEGIN
            else LocalBranchReportAuditSlot.FINISH
        val staged = assertNotNull(assertNotNull(files.latest).auditCommand(slot), "Original must be staged before send")
        assertNull(staged.acknowledgement, "A prepared command is not a master acknowledgement")
        assertEquals(staged.operationId, request.operationId)
        assertEquals(staged.canonicalBodyHex, request.payload.observedHex(), "Send the original staged bytes")
        assertEquals(initial.exportId, body.exportId)
        commands += ObservedWorkflowCommand(request, body, staged)
        events += "master:command:${slot.name}"
        if (slot == LocalBranchReportAuditSlot.BEGIN && denyBegin || slot == LocalBranchReportAuditSlot.FINISH && denyFinish)
            return MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)

        val resultView = when (body) {
            is LocalBranchReportExportRequestV1.Begin -> {
                assertEquals(initial.beginOperationId, request.operationId)
                assertEquals(initial.receiptId, body.nativeReceiptId)
                LocalBranchReportExportViewV1(body.exportId, FrozenReportWorkflowInputs.actorId, body.query,
                    body.nativeReceiptId, body.destination, Revision(0), LocalBranchReportExportStageV1.REQUESTED,
                    request.operationId, request.operationId, FrozenReportWorkflowInputs.baseObservation,
                    null, null, body.coverage).also { beginView = it }
            }
            is LocalBranchReportExportRequestV1.Finish -> {
                assertEquals(initial.finishOperationId, request.operationId)
                assertEquals(initial.receiptId, body.nativeReceiptId)
                assertEquals(Revision(0), body.expectedRevision)
                assertNotNull(beginView).copy(revision = Revision(1),
                    stage = LocalBranchReportExportStageV1.valueOf(body.outcome.name),
                    lastOperationId = request.operationId, lastReportedAt = FrozenReportWorkflowInputs.c2Time,
                    file = body.file)
            }
        }
        return MutationReply.Recorded(request.operationId, FrozenReportWorkflowInputs.actorId, true,
            "controlled.report-export", wire(LocalBranchReportExportResultV1.Recorded(resultView),
                LocalBranchReportExportResultV1.serializer()), FrozenReportWorkflowInputs.authorityEpoch,
            JournalSequence(commands.size.toLong()),
            if (slot == LocalBranchReportAuditSlot.BEGIN) FrozenReportWorkflowInputs.baseObservation
                else FrozenReportWorkflowInputs.c2Time, false)
    }

    private fun forbidden(port: String): Nothing = throw AssertionError("Unbound controlled workflow port: $port")
}

/** Real common choose/spool/publish, but strictly test-only, volatile native primitives. */
internal class ControlledWorkflowFiles(private val events: MutableList<String>) : LocalBranchReportFileDelivery() {
    override val bound: Boolean = true
    internal override val commitGate: LocalReportCommitGate = object : LocalReportCommitGate {
        override fun <T> locked(block: () -> T): T = block()
    }
    private val host = Any()
    var hostAlive = true
    var chooserCancelled = false
    var onNativeEvent: (String) -> Unit = {}
    var beforeCommitStart: () -> Unit = {}
    private val snapshots = mutableListOf<LocalBranchReportFileReceipt>()
    val initial: LocalBranchReportFileReceipt? get() = snapshots.firstOrNull()
    val latest: LocalBranchReportFileReceipt? get() = snapshots.lastOrNull()
    var spoolWrites = 0
        private set
    var linkAttempts = 0
        private set
    var admittedLinks = 0
        private set
    private val store = ControlledReceiptDisk()

    val publishedBytes: List<ByteArray> get() = store.fileBytes.filterKeys { it.startsWith("published/") }.values.map { it.copyOf() }
    val spooledBytes: ByteArray? get() = initial?.let { store.fileBytes[store.spool(it.exportId)]?.copyOf() }

    internal override suspend fun nativeChoice(suggestedName: String, lifetime: LocalBranchReportExportLifetime): LocalReportNativeChoice? {
        emit("native:chooser")
        return if (chooserCancelled) null else LocalReportNativeChoice(host, "report-fixture.csv",
            "Controlled local destination", LocalBranchReportFileDestinationKind.DESKTOP_LOCAL_FILE)
    }
    internal override fun hostCurrent(host: Any): Boolean = this.host === host && hostAlive
    internal override suspend fun <T> disk(block: (LocalReportReceiptDisk) -> T): T = block(store)

    private fun emit(event: String) { events += event; onNativeEvent(event) }

    private inner class ControlledReceiptDisk : LocalReportReceiptDisk() {
        val fileBytes = linkedMapOf<String, ByteArray>()
        private val directories = linkedSetOf("receipts", "published")

        override fun entries(directory: String, maximum: Int): List<String> {
            check(directory in directories)
            val prefix = "$directory/"
            return (directories + fileBytes.keys).filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }.filter { '/' !in it }.distinct().sorted().take(maximum)
        }
        override fun makeDirectory(path: String) { check(!exists(path)); directories += path }
        override fun exists(path: String): Boolean = path in fileBytes || path in directories
        override fun readBounded(path: String, maximum: Int): ByteArray =
            checkNotNull(fileBytes[path]).also { check(it.size <= maximum) }.copyOf()
        override fun writeExclusive(path: String, bytes: ByteArray) {
            check(!exists(path) && path.substringBeforeLast('/') in directories)
            fileBytes[path] = bytes.copyOf()
            if (path.endsWith("/content.csv")) {
                spoolWrites += 1
                emit("native:spool-bytes")
            } else if (path.endsWith(".json")) {
                val next = decodeLocalReportReceipt(bytes)
                val previous = snapshots.lastOrNull()
                snapshots += next
                if (previous == null) emit("native:selected")
                else {
                    if (previous.phase != next.phase) emit("native:phase:${next.phase.name}")
                    for (slot in LocalBranchReportAuditSlot.entries) {
                        val old = previous.auditCommand(slot)
                        val current = next.auditCommand(slot)
                        if (old == null && current != null) emit("native:staged:${slot.name}")
                        val acknowledgement = current?.acknowledgement
                        if (old?.acknowledgement == null && acknowledgement != null)
                            emit("native:acknowledged:${slot.name}:${acknowledgement.stage.name}")
                    }
                }
            }
        }
        override fun writeExclusiveAtCommitStart(path: String, bytes: ByteArray, commitStart: LocalReportCommitStart): Boolean {
            if (!commitStart.admit()) return false
            writeExclusive(path, bytes)
            return true
        }
        override fun identity(path: String): String { check(path in fileBytes); return "controlled:$path" }
        override fun forceDirectory(path: String) { check(path in directories) }
        override fun linkComplete(receipt: LocalBranchReportFileReceipt, commitStart: LocalReportCommitStart): LocalReportLinkOutcome {
            linkAttempts += 1
            emit("native:link-attempt")
            beforeCommitStart()
            if (!commitStart.admit()) return LocalReportLinkOutcome.NOT_ATTEMPTED
            val destination = published(receipt.fileName)
            check(!exists(destination))
            fileBytes[destination] = checkNotNull(fileBytes[spool(receipt.exportId)]).copyOf()
            admittedLinks += 1
            return LocalReportLinkOutcome.DURABLE // Scripted primitive result, not real native durability.
        }
        override fun verifyPublished(receipt: LocalBranchReportFileReceipt): Boolean {
            val original = fileBytes[spool(receipt.exportId)] ?: return false
            return fileBytes[published(receipt.fileName)]?.contentEquals(original) == true
        }
        override fun recoverPublication(receipt: LocalBranchReportFileReceipt): LocalReportRecoveredPublication =
            throw AssertionError("Restart/native recovery is not bound by this controlled workflow slice")
    }
}
