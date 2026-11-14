package com.ukkera.pos.app.gateway.setup.ipc

import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentExportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentImportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocuments
import com.ukkera.pos.app.setup.LocalSetupController
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyTarget
import com.ukkera.pos.app.setup.LocalSetupFirstAccountInput
import com.ukkera.pos.app.setup.LocalSetupLanInput
import com.ukkera.pos.app.setup.LocalSetupNewCompanyInput
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentKind
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.rpc.LocalSetupPolicyTransferV1
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalCompositionSdkProfileV1
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import com.ukkera.pos.security.LocalTrainingSdkProfileV1
import com.ukkera.pos.types.DomainId
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * UI-process setup controller for a desktop Master that runs as the OS service. Presentation only:
 * views come from the service coordinator, intents go back to it. Secret buffers are copied into
 * one outgoing frame and the caller's arrays are cleared immediately, as the contract requires.
 * A lost connection leaves a locked view; nothing here grants or caches authority.
 */
public class DesktopRemoteSetupController private constructor(
    private val connection: DesktopSetupIpcConnection,
    override val compositionProfile: LocalCompositionSdkProfileV1?,
    private val documents: LocalSetupPairingDocuments?,
    private val scope: CoroutineScope,
) : LocalSetupController, Closeable {
    private val output = connection.dataOutput()
    private val input = connection.dataInput()
    private val view = MutableStateFlow(LocalSetupView(locked = true))
    private val live = MutableStateFlow(true)
    private val outgoing = Channel<DesktopSetupIpcWire.Frame>(Channel.UNLIMITED)
    override val state: StateFlow<LocalSetupView> = view.asStateFlow()
    /** False once the service connection ended; the screen should offer reconnecting. */
    public val connected: StateFlow<Boolean> = live.asStateFlow()

    private fun start() {
        scope.launch(Dispatchers.IO) {
            for (frame in outgoing) {
                try { if (live.value) DesktopSetupIpcWire.write(output, frame) } catch (_: Throwable) { disconnect() } finally { frame.clear() }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val frame = runInterruptible { DesktopSetupIpcWire.read(input) }
                    frame.clear() // The service never sends secrets.
                    when (val message = frame.message) {
                        is IpcMessage.View -> view.value = message.view.value()
                        is IpcMessage.DocumentImport -> scope.launch { importDocument(message) }
                        is IpcMessage.DocumentExport -> scope.launch { exportDocument(message) }
                        else -> Unit
                    }
                }
            } catch (_: Throwable) { disconnect() }
        }
    }

    private suspend fun importDocument(request: IpcMessage.DocumentImport) {
        val kind = LocalSetupPairingDocumentKind.valueOf(request.kind)
        val reply = when (val result = documents?.importDocument(kind) { live.value }) {
            is LocalSetupPairingDocumentImportResult.Completed -> {
                val bytes = result.copyBytes()
                try { IpcMessage.DocumentImported(request.id, "COMPLETED", DesktopSetupIpcWire.text(bytes)) } finally { bytes.fill(0) }
            }
            LocalSetupPairingDocumentImportResult.Cancelled -> IpcMessage.DocumentImported(request.id, "CANCELLED")
            else -> IpcMessage.DocumentImported(request.id, "UNAVAILABLE")
        }
        outgoing.trySend(DesktopSetupIpcWire.Frame(reply))
    }

    private suspend fun exportDocument(request: IpcMessage.DocumentExport) {
        val kind = LocalSetupPairingDocumentKind.valueOf(request.kind)
        val bytes = DesktopSetupIpcWire.bytes(request.bytes)
        val outcome = try {
            if (bytes == null || bytes.size !in 1..kind.maximumBytes) "UNAVAILABLE"
            else when (documents?.exportDocument(kind, bytes) { live.value }) {
                LocalSetupPairingDocumentExportResult.Completed -> "COMPLETED"
                LocalSetupPairingDocumentExportResult.Cancelled -> "CANCELLED"
                else -> "UNAVAILABLE"
            }
        } finally { bytes?.fill(0) }
        outgoing.trySend(DesktopSetupIpcWire.Frame(IpcMessage.DocumentExported(request.id, outcome)))
    }

    private fun disconnect() {
        if (!live.compareAndSet(true, false)) return
        view.value = LocalSetupView(locked = true)
        runCatching { connection.close() }
        outgoing.close()
    }

    override fun close(): Unit = disconnect()

    private fun send(command: IpcMessage.Command, vararg secrets: CharArray) {
        val copies = secrets.map { it.copyOf() }
        secrets.forEach { it.fill('\u0000') }
        val frame = DesktopSetupIpcWire.Frame(command.copy(secretCount = copies.size), copies)
        if (!live.value || outgoing.trySend(frame).isFailure) frame.clear()
    }
    private fun call(method: String) = send(IpcMessage.Command(method))
    private fun call(method: String, id: DomainId) = send(IpcMessage.Command(method, id = id.value))

    override fun chooseNewLocalCompany(): Unit = call("chooseNewLocalCompany")
    override fun inspectOriginalCompany(): Unit = call("inspectOriginalCompany")
    override fun resumeOriginalCompany(): Unit = call("resumeOriginalCompany")
    override fun inspectOriginalInstallation(): Unit = call("inspectOriginalInstallation")
    override fun prepareNewLocalCompany(input: LocalSetupNewCompanyInput): Unit = send(IpcMessage.Command("prepareNewLocalCompany",
        newCompany = IpcNewCompany(input.companyName, input.installerName, input.installerLogin, input.headName, input.headLogin,
            input.protection.name)))
    override fun completeNewLocalCompany(reviewId: DomainId, installerSecret: CharArray, headSecret: CharArray): Unit =
        send(IpcMessage.Command("completeNewLocalCompany", id = reviewId.value), installerSecret, headSecret)
    override fun prepareOriginalBranch(branchName: String): Unit = send(IpcMessage.Command("prepareOriginalBranch", text = branchName))
    override fun issueOriginalBranch(reviewId: DomainId, installerSecret: CharArray): Unit =
        send(IpcMessage.Command("issueOriginalBranch", id = reviewId.value), installerSecret)
    override fun prepareOriginalNativeTarget(): Unit = call("prepareOriginalNativeTarget")
    override fun openOriginalFirstAccount(): Unit = call("openOriginalFirstAccount")
    override fun prepareFirstAccount(input: LocalSetupFirstAccountInput): Unit = send(IpcMessage.Command("prepareFirstAccount",
        payload = LocalSetupPolicyTransferV1.encodeAccount(input.account), number = input.authorityEpoch.value))
    override fun installFirstAccount(reviewId: DomainId, headSecret: CharArray, employeePassword: CharArray): Unit =
        send(IpcMessage.Command("installFirstAccount", id = reviewId.value), headSecret, employeePassword)
    override fun resumeOriginalInstallationAcknowledgement(): Unit = call("resumeOriginalInstallationAcknowledgement")
    override fun enrollOriginalLocalClient(): Unit = call("enrollOriginalLocalClient")
    override fun continueOriginalLocalClientEnrollment(): Unit = call("continueOriginalLocalClientEnrollment")
    override fun reconnectOriginalLocalClient(): Unit = call("reconnectOriginalLocalClient")
    override fun recoverOriginalLocalClientWrites(): Unit = call("recoverOriginalLocalClientWrites")
    override fun openEmployeeEnrollment(): Unit = call("openEmployeeEnrollment")
    override fun beginEmployeeEnrollment(): Unit = call("beginEmployeeEnrollment")
    override fun inspectEmployeeEnrollment(originalRequestId: DomainId): Unit = call("inspectEmployeeEnrollment", originalRequestId)
    override fun prepareEmployeeEnrollment(account: LocalAccountProvisioning): Unit =
        send(IpcMessage.Command("prepareEmployeeEnrollment", payload = LocalSetupPolicyTransferV1.encodeAccount(account)))
    override fun approveEmployeeEnrollment(reviewId: DomainId, headSecret: CharArray): Unit =
        send(IpcMessage.Command("approveEmployeeEnrollment", id = reviewId.value), headSecret)
    override fun enrollApprovedEmployee(reviewId: DomainId, employeePassword: CharArray): Unit =
        send(IpcMessage.Command("enrollApprovedEmployee", id = reviewId.value), employeePassword)
    override fun enrollEmployeeIntentJournal(reviewId: DomainId): Unit = call("enrollEmployeeIntentJournal", reviewId)
    override fun reopenEmployeeIntentJournal(reviewId: DomainId): Unit = call("reopenEmployeeIntentJournal", reviewId)
    override fun recoverEmployeeIntentJournalWrites(reviewId: DomainId): Unit = call("recoverEmployeeIntentJournalWrites", reviewId)
    override fun finishEmployeeEnrollment(reviewId: DomainId): Unit = call("finishEmployeeEnrollment", reviewId)
    override fun openEmployeePolicies(): Unit = call("openEmployeePolicies")
    override fun openEmployeePolicies(target: LocalSetupEmployeePolicyTarget): Unit =
        send(IpcMessage.Command("openEmployeePoliciesFor", policyTarget = IpcPolicyTarget.of(target)))
    override fun closeEmployeePolicies(): Unit = call("closeEmployeePolicies")
    override fun beginEmployeePolicy(): Unit = call("beginEmployeePolicy")
    override fun inspectEmployeePolicy(originalRequestId: DomainId): Unit = call("inspectEmployeePolicy", originalRequestId)
    override fun prepareEmployeePolicy(reviewId: DomainId, nextPolicy: ActivatedLocalPolicy): Unit = send(IpcMessage.Command(
        "prepareEmployeePolicy", id = reviewId.value, payload = LocalSetupPolicyTransferV1.encodePolicy(nextPolicy)))
    override fun approveEmployeePolicy(reviewId: DomainId, headSecret: CharArray): Unit =
        send(IpcMessage.Command("approveEmployeePolicy", id = reviewId.value), headSecret)
    override fun activateApprovedEmployeePolicy(reviewId: DomainId): Unit = call("activateApprovedEmployeePolicy", reviewId)
    override fun lookupEmployeePolicy(reviewId: DomainId): Unit = call("lookupEmployeePolicy", reviewId)
    override fun finishEmployeePolicy(reviewId: DomainId): Unit = call("finishEmployeePolicy", reviewId)
    override fun openDeviceAdmissions(): Unit = call("openDeviceAdmissions")
    override fun inspectDeviceAdmission(originalActionId: DomainId): Unit = call("inspectDeviceAdmission", originalActionId)
    override fun approveDeviceAdmission(reviewId: DomainId, headSecret: CharArray): Unit =
        send(IpcMessage.Command("approveDeviceAdmission", id = reviewId.value), headSecret)
    override fun admitApprovedDevice(reviewId: DomainId): Unit = call("admitApprovedDevice", reviewId)
    override fun lookupDeviceAdmission(reviewId: DomainId): Unit = call("lookupDeviceAdmission", reviewId)
    override fun closeDeviceAdmissions(): Unit = call("closeDeviceAdmissions")
    override fun openFreshDevicePairing(): Unit = call("openFreshDevicePairing")
    override fun beginFreshDevicePairing(): Unit = call("beginFreshDevicePairing")
    override fun inspectFreshDevicePairing(originalRequestId: DomainId): Unit = call("inspectFreshDevicePairing", originalRequestId)
    override fun importFreshDeviceIdentity(reviewId: DomainId): Unit = call("importFreshDeviceIdentity", reviewId)
    override fun prepareFreshDeviceChallenge(reviewId: DomainId): Unit = call("prepareFreshDeviceChallenge", reviewId)
    override fun importFreshDevicePossession(reviewId: DomainId): Unit = call("importFreshDevicePossession", reviewId)
    override fun proveFreshDeviceTarget(reviewId: DomainId): Unit = call("proveFreshDeviceTarget", reviewId)
    override fun reviewUnsignedDevicePairingRetirement(reviewId: DomainId): Unit = call("reviewUnsignedDevicePairingRetirement", reviewId)
    override fun retireUnsignedDevicePairing(reviewId: DomainId): Unit = call("retireUnsignedDevicePairing", reviewId)
    override fun exportFreshDeviceDocument(reviewId: DomainId, kind: LocalSetupPairingDocumentKind): Unit =
        send(IpcMessage.Command("exportFreshDeviceDocument", id = reviewId.value, kind = kind.name))
    override fun recoverFreshDevicePairing(reviewId: DomainId): Unit = call("recoverFreshDevicePairing", reviewId)
    override fun handoffFreshDeviceAdmission(reviewId: DomainId): Unit = call("handoffFreshDeviceAdmission", reviewId)
    override fun closeFreshDevicePairing(): Unit = call("closeFreshDevicePairing")
    override fun startLanHost(input: LocalSetupLanInput): Unit = send(IpcMessage.Command("startLanHost", lan = IpcLanInput.of(input)))
    override fun refreshLanAttachment(): Unit = call("refreshLanAttachment")
    override fun closeLanAttachment(): Unit = call("closeLanAttachment")
    override fun refreshOriginalStatus(): Unit = call("refreshOriginalStatus")
    override fun authenticateAndStopLocalMaster(login: String, password: CharArray): Unit =
        send(IpcMessage.Command("authenticateAndStopLocalMaster", text = login), password)
    override fun authenticateAndResumeLocalMaster(login: String, password: CharArray): Unit =
        send(IpcMessage.Command("authenticateAndResumeLocalMaster", text = login), password)
    override fun lockPresentation(): Unit = call("lockPresentation")
    override fun resumePresentation(): Unit = call("resumePresentation")

    public companion object {
        /** Reads the service hello before returning; any malformed peer is closed. */
        public fun connect(connection: DesktopSetupIpcConnection, documents: LocalSetupPairingDocuments?,
            scope: CoroutineScope): DesktopRemoteSetupController {
            try {
                val frame = DesktopSetupIpcWire.read(connection.dataInputOnce())
                frame.clear()
                val hello = frame.message as? IpcMessage.Hello ?: error("Unexpected setup service greeting")
                val profile = when {
                    hello.trainingSlot != null -> LocalTrainingSdkProfileV1(LocalTrainingCompositionSlot.valueOf(hello.trainingSlot))
                    hello.master -> LocalMasterSdkProfileV1
                    else -> null
                }
                return DesktopRemoteSetupController(connection, profile, documents, scope).also { it.start() }
            } catch (failure: Throwable) { connection.close(); throw failure }
        }
    }
}

/** Unbuffered single read so no bytes after the greeting are swallowed by a discarded buffer. */
private fun DesktopSetupIpcConnection.dataInputOnce() = java.io.DataInputStream(input)
