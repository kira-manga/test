package com.ukkera.pos.app.gateway.setup.ipc

import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentExportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentImportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocuments
import com.ukkera.pos.app.setup.LocalSetupController
import com.ukkera.pos.app.setup.LocalSetupFirstAccountInput
import com.ukkera.pos.app.setup.LocalSetupNewCompanyInput
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentKind
import com.ukkera.pos.rpc.LocalSetupPolicyTransferV1
import com.ukkera.pos.security.LocalTrainingSdkProfileV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.io.Closeable
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairing-document bridge for the service-hosted coordinator: file choice/writing happens in the
 * connected UI process; only bounded bytes cross. No connected UI means UNAVAILABLE, never a default.
 */
public class DesktopSetupIpcDocuments : LocalSetupPairingDocuments {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<IpcMessage>>()
    @Volatile private var peer: ((IpcMessage) -> Unit)? = null

    internal fun attach(send: (IpcMessage) -> Unit) { peer = send }
    internal fun detach() {
        peer = null
        pending.values.forEach { it.complete(IpcMessage.DocumentExported(-1, "UNAVAILABLE")) }
        pending.clear()
    }
    internal fun complete(id: Long, reply: IpcMessage) { pending.remove(id)?.complete(reply) }

    private suspend fun request(build: (Long) -> IpcMessage): IpcMessage? {
        val send = peer ?: return null
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<IpcMessage>().also { pending[id] = it }
        try {
            send(build(id))
            return withTimeoutOrNull(DOCUMENT_TIMEOUT_MILLIS) { reply.await() }
        } finally { pending.remove(id) }
    }

    override suspend fun importDocument(kind: LocalSetupPairingDocumentKind, isCurrent: () -> Boolean): LocalSetupPairingDocumentImportResult {
        val reply = request { IpcMessage.DocumentImport(it, kind.name) } as? IpcMessage.DocumentImported
            ?: return LocalSetupPairingDocumentImportResult.Unavailable()
        if (!isCurrent()) return LocalSetupPairingDocumentImportResult.Unavailable()
        return when (reply.outcome) {
            "COMPLETED" -> {
                val bytes = DesktopSetupIpcWire.bytes(reply.bytes) ?: return LocalSetupPairingDocumentImportResult.Unavailable()
                try {
                    if (bytes.size !in 1..kind.maximumBytes) LocalSetupPairingDocumentImportResult.Unavailable()
                    else LocalSetupPairingDocumentImportResult.Completed(kind, bytes)
                } finally { bytes.fill(0) }
            }
            "CANCELLED" -> LocalSetupPairingDocumentImportResult.Cancelled
            else -> LocalSetupPairingDocumentImportResult.Unavailable()
        }
    }

    override suspend fun exportDocument(kind: LocalSetupPairingDocumentKind, bytes: ByteArray,
        isCurrent: () -> Boolean): LocalSetupPairingDocumentExportResult {
        require(bytes.size in 1..kind.maximumBytes)
        val text = requireNotNull(DesktopSetupIpcWire.text(bytes))
        val reply = request { IpcMessage.DocumentExport(it, kind.name, text) } as? IpcMessage.DocumentExported
            ?: return LocalSetupPairingDocumentExportResult.Unavailable()
        return when (reply.outcome) {
            "COMPLETED" -> LocalSetupPairingDocumentExportResult.Completed
            "CANCELLED" -> LocalSetupPairingDocumentExportResult.Cancelled
            else -> LocalSetupPairingDocumentExportResult.Unavailable()
        }
    }

    private companion object { const val DOCUMENT_TIMEOUT_MILLIS = 10L * 60_000L }
}

/**
 * Service side of the setup console. Serves one UI peer at a time over an OS-restricted endpoint and
 * forwards its intents to the service-owned coordinator. The peer is presentation only: all
 * authority stays in the coordinator's native owners and the named head/installer secrets.
 * Fresh-target (Client-role) intents are refused here; the service is never a LAN Client.
 */
public class DesktopSetupIpcServer(
    private val controller: LocalSetupController,
    private val documents: DesktopSetupIpcDocuments,
    private val scope: CoroutineScope,
) : Closeable {
    @Volatile private var endpoint: DesktopSetupIpcEndpoint? = null
    private var job: Job? = null

    @Synchronized public fun serve(listener: DesktopSetupIpcEndpoint) {
        check(endpoint == null)
        endpoint = listener
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val connection = runCatching { runInterruptible { listener.accept() } }.getOrElse { if (endpoint == null) return@launch else continue }
                runCatching { connection.use { session(it) } }
            }
        }
    }

    private suspend fun session(connection: DesktopSetupIpcConnection) {
        val output = connection.dataOutput()
        val input = connection.dataInput()
        val send: (IpcMessage) -> Unit = { message -> DesktopSetupIpcWire.write(output, DesktopSetupIpcWire.Frame(message)) }
        val profile = controller.compositionProfile
        send(IpcMessage.Hello((profile as? LocalTrainingSdkProfileV1)?.slot?.name, master = profile != null))
        documents.attach(send)
        val views = scope.launch(Dispatchers.IO) { controller.state.collect { send(IpcMessage.View(IpcView.of(it))) } }
        try {
            while (true) {
                val frame = runInterruptible { DesktopSetupIpcWire.read(input) }
                try {
                    when (val message = frame.message) {
                        is IpcMessage.Command -> runCatching { dispatch(message, frame) }
                        is IpcMessage.DocumentImported -> documents.complete(message.id, message)
                        is IpcMessage.DocumentExported -> documents.complete(message.id, message)
                        else -> return // A UI peer never sends service messages.
                    }
                } finally { frame.clear() }
            }
        } finally {
            withContext(NonCancellable) {
                documents.detach()
                views.cancelAndJoin()
                // Disconnection locks presentation only; owned native work continues in the service.
                runCatching { controller.lockPresentation() }
            }
        }
    }

    private fun dispatch(c: IpcMessage.Command, frame: DesktopSetupIpcWire.Frame) {
        require(c.secretCount == frame.secrets.size)
        fun id() = DomainId(requireNotNull(c.id))
        fun secret(i: Int) = frame.secret(i).copyOf() // The controller owns and clears its transfer copy.
        val s = controller
        when (c.method) {
            in NO_ARGUMENT -> noArgument(c.method)
            in ID_ARGUMENT -> idArgument(c.method, id())
            "prepareNewLocalCompany" -> requireNotNull(c.newCompany).let {
                s.prepareNewLocalCompany(LocalSetupNewCompanyInput(it.companyName, it.installerName, it.installerLogin,
                    it.headName, it.headLogin, SelfOwnedKeyProtectionV1.valueOf(it.protection)))
            }
            "completeNewLocalCompany" -> s.completeNewLocalCompany(id(), secret(0), secret(1))
            "prepareOriginalBranch" -> s.prepareOriginalBranch(requireNotNull(c.text))
            "issueOriginalBranch" -> s.issueOriginalBranch(id(), secret(0))
            "prepareFirstAccount" -> s.prepareFirstAccount(LocalSetupFirstAccountInput(
                LocalSetupPolicyTransferV1.decodeAccount(requireNotNull(c.payload)), Revision(requireNotNull(c.number))))
            "installFirstAccount" -> s.installFirstAccount(id(), secret(0), secret(1))
            "prepareEmployeeEnrollment" -> s.prepareEmployeeEnrollment(LocalSetupPolicyTransferV1.decodeAccount(requireNotNull(c.payload)))
            "approveEmployeeEnrollment" -> s.approveEmployeeEnrollment(id(), secret(0))
            "enrollApprovedEmployee" -> s.enrollApprovedEmployee(id(), secret(0))
            "openEmployeePoliciesFor" -> s.openEmployeePolicies(requireNotNull(c.policyTarget).value())
            "prepareEmployeePolicy" -> s.prepareEmployeePolicy(id(), LocalSetupPolicyTransferV1.decodePolicy(requireNotNull(c.payload)))
            "approveEmployeePolicy" -> s.approveEmployeePolicy(id(), secret(0))
            "approveDeviceAdmission" -> s.approveDeviceAdmission(id(), secret(0))
            "exportFreshDeviceDocument" -> s.exportFreshDeviceDocument(id(), LocalSetupPairingDocumentKind.valueOf(requireNotNull(c.kind)))
            "startLanHost" -> s.startLanHost(requireNotNull(c.lan).value())
            "authenticateAndStopLocalMaster" -> s.authenticateAndStopLocalMaster(requireNotNull(c.text), secret(0))
            "authenticateAndResumeLocalMaster" -> s.authenticateAndResumeLocalMaster(requireNotNull(c.text), secret(0))
            else -> Unit // Unknown or Client-role intent: refused without effect.
        }
    }

    private fun noArgument(method: String) {
        val s = controller
        when (method) {
            "chooseNewLocalCompany" -> s.chooseNewLocalCompany()
            "inspectOriginalCompany" -> s.inspectOriginalCompany()
            "resumeOriginalCompany" -> s.resumeOriginalCompany()
            "inspectOriginalInstallation" -> s.inspectOriginalInstallation()
            "prepareOriginalNativeTarget" -> s.prepareOriginalNativeTarget()
            "openOriginalFirstAccount" -> s.openOriginalFirstAccount()
            "resumeOriginalInstallationAcknowledgement" -> s.resumeOriginalInstallationAcknowledgement()
            "enrollOriginalLocalClient" -> s.enrollOriginalLocalClient()
            "continueOriginalLocalClientEnrollment" -> s.continueOriginalLocalClientEnrollment()
            "reconnectOriginalLocalClient" -> s.reconnectOriginalLocalClient()
            "recoverOriginalLocalClientWrites" -> s.recoverOriginalLocalClientWrites()
            "openEmployeeEnrollment" -> s.openEmployeeEnrollment()
            "beginEmployeeEnrollment" -> s.beginEmployeeEnrollment()
            "openEmployeePolicies" -> s.openEmployeePolicies()
            "closeEmployeePolicies" -> s.closeEmployeePolicies()
            "beginEmployeePolicy" -> s.beginEmployeePolicy()
            "openDeviceAdmissions" -> s.openDeviceAdmissions()
            "closeDeviceAdmissions" -> s.closeDeviceAdmissions()
            "openFreshDevicePairing" -> s.openFreshDevicePairing()
            "beginFreshDevicePairing" -> s.beginFreshDevicePairing()
            "closeFreshDevicePairing" -> s.closeFreshDevicePairing()
            "refreshLanAttachment" -> s.refreshLanAttachment()
            "closeLanAttachment" -> s.closeLanAttachment()
            "refreshOriginalStatus" -> s.refreshOriginalStatus()
            "lockPresentation" -> s.lockPresentation()
            "resumePresentation" -> s.resumePresentation()
        }
    }

    private fun idArgument(method: String, id: DomainId) {
        val s = controller
        when (method) {
            "inspectEmployeeEnrollment" -> s.inspectEmployeeEnrollment(id)
            "enrollEmployeeIntentJournal" -> s.enrollEmployeeIntentJournal(id)
            "reopenEmployeeIntentJournal" -> s.reopenEmployeeIntentJournal(id)
            "recoverEmployeeIntentJournalWrites" -> s.recoverEmployeeIntentJournalWrites(id)
            "finishEmployeeEnrollment" -> s.finishEmployeeEnrollment(id)
            "inspectEmployeePolicy" -> s.inspectEmployeePolicy(id)
            "activateApprovedEmployeePolicy" -> s.activateApprovedEmployeePolicy(id)
            "lookupEmployeePolicy" -> s.lookupEmployeePolicy(id)
            "finishEmployeePolicy" -> s.finishEmployeePolicy(id)
            "inspectDeviceAdmission" -> s.inspectDeviceAdmission(id)
            "admitApprovedDevice" -> s.admitApprovedDevice(id)
            "lookupDeviceAdmission" -> s.lookupDeviceAdmission(id)
            "inspectFreshDevicePairing" -> s.inspectFreshDevicePairing(id)
            "importFreshDeviceIdentity" -> s.importFreshDeviceIdentity(id)
            "prepareFreshDeviceChallenge" -> s.prepareFreshDeviceChallenge(id)
            "importFreshDevicePossession" -> s.importFreshDevicePossession(id)
            "proveFreshDeviceTarget" -> s.proveFreshDeviceTarget(id)
            "reviewUnsignedDevicePairingRetirement" -> s.reviewUnsignedDevicePairingRetirement(id)
            "retireUnsignedDevicePairing" -> s.retireUnsignedDevicePairing(id)
            "recoverFreshDevicePairing" -> s.recoverFreshDevicePairing(id)
            "handoffFreshDeviceAdmission" -> s.handoffFreshDeviceAdmission(id)
        }
    }

    override fun close() {
        val listener = synchronized(this) { endpoint.also { endpoint = null } }
        runCatching { listener?.close() }
        job?.cancel()
    }

    internal companion object {
        /** Master-role intents only. Fresh-target/LAN-Client intents belong to the UI's own coordinator. */
        val NO_ARGUMENT: Set<String> = setOf("chooseNewLocalCompany", "inspectOriginalCompany", "resumeOriginalCompany",
            "inspectOriginalInstallation", "prepareOriginalNativeTarget", "openOriginalFirstAccount",
            "resumeOriginalInstallationAcknowledgement", "enrollOriginalLocalClient", "continueOriginalLocalClientEnrollment",
            "reconnectOriginalLocalClient", "recoverOriginalLocalClientWrites", "openEmployeeEnrollment", "beginEmployeeEnrollment",
            "openEmployeePolicies", "closeEmployeePolicies", "beginEmployeePolicy", "openDeviceAdmissions", "closeDeviceAdmissions",
            "openFreshDevicePairing", "beginFreshDevicePairing", "closeFreshDevicePairing", "refreshLanAttachment",
            "closeLanAttachment", "refreshOriginalStatus", "lockPresentation", "resumePresentation")
        val ID_ARGUMENT: Set<String> = setOf("inspectEmployeeEnrollment", "enrollEmployeeIntentJournal", "reopenEmployeeIntentJournal",
            "recoverEmployeeIntentJournalWrites", "finishEmployeeEnrollment", "inspectEmployeePolicy", "activateApprovedEmployeePolicy",
            "lookupEmployeePolicy", "finishEmployeePolicy", "inspectDeviceAdmission", "admitApprovedDevice", "lookupDeviceAdmission",
            "inspectFreshDevicePairing", "importFreshDeviceIdentity", "prepareFreshDeviceChallenge", "importFreshDevicePossession",
            "proveFreshDeviceTarget", "reviewUnsignedDevicePairingRetirement", "retireUnsignedDevicePairing",
            "recoverFreshDevicePairing", "handoffFreshDeviceAdmission")
    }
}

internal fun DataOutputStream.send(message: IpcMessage, secrets: List<CharArray> = emptyList()) =
    DesktopSetupIpcWire.write(this, DesktopSetupIpcWire.Frame(message, secrets))
