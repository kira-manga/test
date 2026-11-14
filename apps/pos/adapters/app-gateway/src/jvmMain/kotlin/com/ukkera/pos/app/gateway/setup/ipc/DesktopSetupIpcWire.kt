package com.ukkera.pos.app.gateway.setup.ipc

import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.setup.LocalSetupBranchReview
import com.ukkera.pos.app.setup.LocalSetupCompanyReview
import com.ukkera.pos.app.setup.LocalSetupCompanyView
import com.ukkera.pos.app.setup.LocalSetupDefinitionReview
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionOutcome
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionStage
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionView
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissions
import com.ukkera.pos.app.setup.LocalSetupEmployeeEnrollmentStage
import com.ukkera.pos.app.setup.LocalSetupEmployeeEnrollmentView
import com.ukkera.pos.app.setup.LocalSetupEmployeeEnrollments
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicies
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyOutcome
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyStage
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyTarget
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyView
import com.ukkera.pos.app.setup.LocalSetupFreshDeviceMasterOriginalView
import com.ukkera.pos.app.setup.LocalSetupFreshDevicePairingRole
import com.ukkera.pos.app.setup.LocalSetupFreshDevicePairingStage
import com.ukkera.pos.app.setup.LocalSetupFreshDevicePairingView
import com.ukkera.pos.app.setup.LocalSetupFreshDeviceTargetInput
import com.ukkera.pos.app.setup.LocalSetupLanEndpoint
import com.ukkera.pos.app.setup.LocalSetupLanInput
import com.ukkera.pos.app.setup.LocalSetupLanRole
import com.ukkera.pos.app.setup.LocalSetupLanStatus
import com.ukkera.pos.app.setup.LocalSetupLanView
import com.ukkera.pos.app.setup.LocalSetupLanWorkflow
import com.ukkera.pos.app.setup.LocalSetupNativeView
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentKind
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentOutcome
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentView
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.app.setup.LocalSetupReceiptView
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.app.setup.LocalSetupWork
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalBranchInstallationWireV2
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingDefinition
import com.ukkera.pos.security.LocalDevicePairingMasterTransfer
import com.ukkera.pos.security.LocalDevicePairingPhase
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingTargetPhase
import com.ukkera.pos.security.LocalDevicePairingTargetReference
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalEmployeeEnrollmentDefinition
import com.ukkera.pos.security.LocalEmployeeEnrollmentPhase
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.LocalEmployeePolicyActivationDefinition
import com.ukkera.pos.security.LocalEmployeePolicyActivationPhase
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalMasterSdkOriginal
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.rpc.LocalSetupPolicyTransferV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Desktop setup console wire (D5): the UI process drives the Master service's own setup
 * coordinator. The service re-runs every constructor check on decode and every effect still needs
 * the service-side native owner and named head/installer secrets. Secrets never enter JSON text:
 * they travel as trailing UTF-16 frames that are cleared after use.
 */
internal object DesktopSetupIpcWire {
    const val VERSION: Int = 1
    private const val MAXIMUM_JSON_BYTES = 8 * 1024 * 1024
    private const val MAXIMUM_SECRETS = 4
    private const val MAXIMUM_SECRET_CHARS = 256
    val json: Json = Json { classDiscriminator = "t"; encodeDefaults = false; ignoreUnknownKeys = false }

    /** One frame: JSON message plus zero or more secret buffers owned by the receiver. */
    class Frame(val message: IpcMessage, val secrets: List<CharArray> = emptyList()) {
        fun clear() { secrets.forEach { it.fill('\u0000') } }
        fun secret(index: Int): CharArray = secrets[index]
    }

    fun write(out: DataOutputStream, frame: Frame) {
        val text = json.encodeToString(IpcMessage.serializer(), frame.message).encodeToByteArray()
        require(text.size <= MAXIMUM_JSON_BYTES && frame.secrets.size <= MAXIMUM_SECRETS)
        synchronized(out) {
            out.writeInt(VERSION); out.writeInt(text.size); out.write(text)
            out.writeInt(frame.secrets.size)
            frame.secrets.forEach { secret ->
                require(secret.size <= MAXIMUM_SECRET_CHARS)
                out.writeInt(secret.size); secret.forEach { out.writeChar(it.code) }
            }
            out.flush()
        }
    }

    fun read(input: DataInputStream): Frame {
        require(input.readInt() == VERSION)
        val size = input.readInt(); require(size in 1..MAXIMUM_JSON_BYTES)
        val text = ByteArray(size).also(input::readFully)
        val count = input.readInt(); require(count in 0..MAXIMUM_SECRETS)
        val secrets = ArrayList<CharArray>(count)
        try {
            repeat(count) {
                val length = input.readInt(); require(length in 0..MAXIMUM_SECRET_CHARS)
                secrets += CharArray(length) { input.readChar() }
            }
            return Frame(json.decodeFromString(IpcMessage.serializer(), text.decodeToString()), secrets)
        } catch (failure: Throwable) {
            secrets.forEach { it.fill('\u0000') }; throw failure
        }
    }

    fun bytes(value: String?): ByteArray? = value?.let { Base64.getDecoder().decode(it) }
    fun text(value: ByteArray?): String? = value?.let { Base64.getEncoder().encodeToString(it) }
}

@Serializable
internal sealed class IpcMessage {
    /** Service -> UI: the exact composition profile of the service-owned coordinator. */
    @Serializable @SerialName("hello") data class Hello(val trainingSlot: String? = null, val master: Boolean) : IpcMessage()
    @Serializable @SerialName("view") data class View(val view: IpcView) : IpcMessage()
    /** Service -> UI: the coordinator asks the UI owner to pick/write a pairing document. */
    @Serializable @SerialName("doc-import") data class DocumentImport(val id: Long, val kind: String) : IpcMessage()
    @Serializable @SerialName("doc-export") data class DocumentExport(val id: Long, val kind: String, val bytes: String) : IpcMessage()
    /** UI -> service document replies; bytes only, never authority. */
    @Serializable @SerialName("doc-imported") data class DocumentImported(val id: Long, val outcome: String, val bytes: String? = null) : IpcMessage()
    @Serializable @SerialName("doc-exported") data class DocumentExported(val id: Long, val outcome: String) : IpcMessage()
    /** UI -> service intent. Secrets referenced by [secretCount] travel in the frame's secret section. */
    @Serializable @SerialName("cmd") data class Command(
        val method: String,
        val id: String? = null,
        val text: String? = null,
        val text2: String? = null,
        val number: Long? = null,
        val kind: String? = null,
        val payload: String? = null,
        val newCompany: IpcNewCompany? = null,
        val policyTarget: IpcPolicyTarget? = null,
        val freshTarget: IpcFreshTargetInput? = null,
        val lan: IpcLanInput? = null,
        val endpoint: IpcEndpoint? = null,
        val secretCount: Int = 0,
    ) : IpcMessage()
}

@Serializable internal data class IpcNewCompany(val companyName: String, val installerName: String, val installerLogin: String,
    val headName: String, val headLogin: String, val protection: String)
@Serializable internal data class IpcScope(val organizationId: String, val companyId: String, val branchId: String) {
    fun value() = BranchScope(DomainId(organizationId), DomainId(companyId), DomainId(branchId))
    companion object { fun of(s: BranchScope) = IpcScope(s.organizationId.value, s.companyId.value, s.branchId.value) }
}
@Serializable internal data class IpcPolicyTarget(val scope: IpcScope, val actorId: String, val expectedPolicyRevision: Long) {
    fun value() = LocalSetupEmployeePolicyTarget(scope.value(), DomainId(actorId), Revision(expectedPolicyRevision))
    companion object { fun of(t: LocalSetupEmployeePolicyTarget) = IpcPolicyTarget(IpcScope.of(t.scope), t.actorId.value, t.expectedPolicyRevision.value) }
}
@Serializable internal data class IpcFreshTargetInput(val scope: IpcScope, val capabilities: List<String>, val appId: String, val deviceName: String) {
    fun value() = LocalSetupFreshDeviceTargetInput(scope.value(), capabilities.toSet(), appId, deviceName)
    companion object { fun of(i: LocalSetupFreshDeviceTargetInput) = IpcFreshTargetInput(IpcScope.of(i.scope), i.capabilities.sorted(), i.appId, i.deviceName) }
}
@Serializable internal data class IpcLanInput(val allowedSubnets: List<String>, val interfaceName: String, val localAddress: String,
    val listenPort: Int, val credentialAction: String? = null, val workflow: String, val actorIntentAction: String? = null) {
    fun value() = LocalSetupLanInput(allowedSubnets, interfaceName, localAddress, listenPort,
        credentialAction?.let { LocalPairedDeviceCredentialJournalAction.valueOf(it) }, LocalSetupLanWorkflow.valueOf(workflow),
        actorIntentAction?.let { LocalPairedDeviceCredentialJournalAction.valueOf(it) })
    companion object { fun of(i: LocalSetupLanInput) = IpcLanInput(i.allowedSubnets, i.interfaceName, i.localAddress, i.listenPort,
        i.credentialAction?.name, i.workflow.name, i.actorIntentAction?.name) }
}
@Serializable internal data class IpcEndpoint(val address: String, val port: Int) {
    fun value() = LocalSetupLanEndpoint(address, port)
}

/** Display projection of [LocalSetupView]; security DATA travels in its own canonical codec. */
@Serializable
internal data class IpcView(
    val phase: String, val work: String? = null, val locked: Boolean = false,
    val companyReview: IpcCompanyReview? = null, val company: IpcCompany? = null, val branchReview: IpcBranchReview? = null,
    val native: IpcNative? = null, val receipt: IpcReceipt? = null, val firstAccountHeadLogin: String? = null,
    val definitionReview: IpcDefinitionReview? = null, val problem: String? = null, val lifecycleStopped: Boolean = false,
    val nativeProblem: String? = null, val localClientConnected: Boolean = false, val localClientConnectionAvailable: Boolean = false,
    val firstClientEnrollmentAvailable: Boolean = false, val firstClientEnrollmentContinuationAvailable: Boolean = false,
    val employeeEnrollments: IpcEnrollments? = null, val employeeEnrollment: IpcEnrollment? = null,
    val employeePolicies: IpcPolicies? = null, val employeePolicy: IpcPolicy? = null,
    val deviceAdmissions: IpcAdmissions? = null, val deviceAdmission: IpcAdmission? = null,
    val freshDevicePairing: IpcPairing? = null, val lan: IpcLan = IpcLan(), val lifecycleStopPending: Boolean = false,
) {
    fun value(): LocalSetupView = LocalSetupView(
        LocalSetupPhase.valueOf(phase), work?.let { LocalSetupWork.valueOf(it) }, locked,
        companyReview?.value(), company?.value(), branchReview?.value(), native?.value(), receipt?.value(), firstAccountHeadLogin,
        definitionReview?.value(), problem?.let { LocalSetupProblem.valueOf(it) }, lifecycleStopped,
        nativeProblem?.let { LocalSetupProblem.valueOf(it) }, localClientConnected, localClientConnectionAvailable,
        firstClientEnrollmentAvailable, firstClientEnrollmentContinuationAvailable,
        employeeEnrollments?.value(), employeeEnrollment?.value(), employeePolicies?.value(), employeePolicy?.value(),
        deviceAdmissions?.value(), deviceAdmission?.value(), freshDevicePairing?.value(), lan.value(), lifecycleStopPending)

    companion object {
        fun of(v: LocalSetupView) = IpcView(v.phase.name, v.work?.name, v.locked, v.companyReview?.let(IpcCompanyReview::of),
            v.company?.let(IpcCompany::of), v.branchReview?.let(IpcBranchReview::of), v.native?.let(IpcNative::of),
            v.receipt?.let(IpcReceipt::of), v.firstAccountHeadLogin, v.definitionReview?.let(IpcDefinitionReview::of),
            v.problem?.name, v.lifecycleStopped, v.nativeProblem?.name, v.localClientConnected, v.localClientConnectionAvailable,
            v.firstClientEnrollmentAvailable, v.firstClientEnrollmentContinuationAvailable,
            v.employeeEnrollments?.let(IpcEnrollments::of), v.employeeEnrollment?.let(IpcEnrollment::of),
            v.employeePolicies?.let(IpcPolicies::of), v.employeePolicy?.let(IpcPolicy::of),
            v.deviceAdmissions?.let(IpcAdmissions::of), v.deviceAdmission?.let(IpcAdmission::of),
            v.freshDevicePairing?.let(IpcPairing::of), IpcLan.of(v.lan), v.lifecycleStopPending)
    }
}

private fun id(value: String) = DomainId(value)
private fun ids(values: List<String>) = values.map(::DomainId).toSet()
private fun idList(values: Set<DomainId>) = values.map { it.value }.sorted()

@Serializable internal data class IpcCompanyReview(val reviewId: String, val companyName: String, val installerName: String,
    val installerLogin: String, val headName: String, val headLogin: String, val rootFingerprint: String, val organizationId: String,
    val companyId: String, val headAuthorityId: String, val headEnrollmentId: String, val protection: String, val purposes: List<String>) {
    fun value() = LocalSetupCompanyReview(id(reviewId), companyName, installerName, installerLogin, headName, headLogin,
        rootFingerprint, id(organizationId), id(companyId), id(headAuthorityId), id(headEnrollmentId),
        SelfOwnedKeyProtectionV1.valueOf(protection), purposes.map { LocalCompanyHeadPurposeV1.valueOf(it) }.toSet())
    companion object { fun of(r: LocalSetupCompanyReview) = IpcCompanyReview(r.reviewId.value, r.companyName, r.installerName,
        r.installerLogin, r.headName, r.headLogin, r.rootFingerprint, r.organizationId.value, r.companyId.value,
        r.headAuthorityId.value, r.headEnrollmentId.value, r.protection.name, r.purposes.map { it.name }.sorted()) }
}

@Serializable internal data class IpcCompany(val companyName: String, val installerName: String, val installerLogin: String,
    val headName: String, val headLogin: String, val rootFingerprint: String, val organizationId: String, val companyId: String,
    val protection: String, val headAuthorityId: String, val headEnrollmentId: String, val headEnabled: Boolean,
    val enabledHeadPurposes: List<String>) {
    fun value() = LocalSetupCompanyView(companyName, installerName, installerLogin, headName, headLogin, rootFingerprint,
        id(organizationId), id(companyId), SelfOwnedKeyProtectionV1.valueOf(protection), id(headAuthorityId), id(headEnrollmentId),
        headEnabled, enabledHeadPurposes.map { LocalCompanyHeadPurposeV1.valueOf(it) }.toSet())
    companion object { fun of(c: LocalSetupCompanyView) = IpcCompany(c.companyName, c.installerName, c.installerLogin, c.headName,
        c.headLogin, c.rootFingerprint, c.organizationId.value, c.companyId.value, c.protection.name, c.headAuthorityId.value,
        c.headEnrollmentId.value, c.headEnabled, c.enabledHeadPurposes.map { it.name }.sorted()) }
}

@Serializable internal data class IpcBranchReview(val reviewId: String, val branchName: String, val rootFingerprint: String,
    val organizationId: String, val companyId: String, val branchId: String, val allocationId: String, val installationId: String,
    val deviceId: String, val firstActorId: String, val protection: String) {
    fun value() = LocalSetupBranchReview(id(reviewId), branchName, rootFingerprint, id(organizationId), id(companyId), id(branchId),
        id(allocationId), id(installationId), id(deviceId), id(firstActorId), SelfOwnedKeyProtectionV1.valueOf(protection))
    companion object { fun of(b: LocalSetupBranchReview) = IpcBranchReview(b.reviewId.value, b.branchName, b.rootFingerprint,
        b.organizationId.value, b.companyId.value, b.branchId.value, b.allocationId.value, b.installationId.value, b.deviceId.value,
        b.firstActorId.value, b.protection.name) }
}

@Serializable internal data class IpcNative(val branchName: String, val organizationId: String, val companyId: String,
    val branchId: String, val allocationId: String, val installationId: String, val deviceId: String, val firstActorId: String,
    val targetFingerprint: String, val nativeBindingDigest: String, val protection: String) {
    fun value() = LocalSetupNativeView(branchName, id(organizationId), id(companyId), id(branchId), id(allocationId),
        id(installationId), id(deviceId), id(firstActorId), targetFingerprint, nativeBindingDigest, SelfOwnedKeyProtectionV1.valueOf(protection))
    companion object { fun of(n: LocalSetupNativeView) = IpcNative(n.branchName, n.organizationId.value, n.companyId.value,
        n.branchId.value, n.allocationId.value, n.installationId.value, n.deviceId.value, n.firstActorId.value,
        n.targetFingerprint, n.nativeBindingDigest, n.protection.name) }
}

@Serializable internal data class IpcReceipt(val originalRequestId: String, val issuedAttemptId: String, val installationId: String,
    val branchId: String, val definitionDigest: String, val receiptDigest: String, val genesisDigest: String) {
    fun value() = LocalSetupReceiptView(id(originalRequestId), id(issuedAttemptId), id(installationId), id(branchId),
        definitionDigest, receiptDigest, genesisDigest)
    companion object { fun of(r: LocalSetupReceiptView) = IpcReceipt(r.originalRequestId.value, r.issuedAttemptId.value,
        r.installationId.value, r.branchId.value, r.definitionDigest, r.receiptDigest, r.genesisDigest) }
}

/** The snapshot travels as its canonical definition bytes; the UI only displays the decoded DATA. */
@Serializable internal data class IpcDefinitionReview(val reviewId: String, val snapshot: String, val headLogin: String) {
    fun value() = LocalSetupDefinitionReview(id(reviewId),
        requireNotNull(LocalBranchInstallationWireV2.decodeDefinition(requireNotNull(DesktopSetupIpcWire.bytes(snapshot)))), headLogin)
    companion object { fun of(r: LocalSetupDefinitionReview) = IpcDefinitionReview(r.reviewId.value,
        requireNotNull(DesktopSetupIpcWire.text(r.snapshot.copyWireBytes())), r.headLogin) }
}

@Serializable internal data class IpcEnrollmentReference(val originalActionId: String, val originalRequestId: String,
    val actorId: String, val phase: String) {
    fun value() = LocalEmployeeEnrollmentReference(id(originalActionId), id(originalRequestId), id(actorId), LocalEmployeeEnrollmentPhase.valueOf(phase))
    companion object { fun of(r: LocalEmployeeEnrollmentReference) = IpcEnrollmentReference(r.originalActionId.value,
        r.originalRequestId.value, r.actorId.value, r.phase.name) }
}

@Serializable internal data class IpcEnrollments(val originals: List<IpcEnrollmentReference>, val newDefinitionAvailable: Boolean) {
    fun value() = LocalSetupEmployeeEnrollments(originals.map { it.value() }, newDefinitionAvailable)
    companion object { fun of(e: LocalSetupEmployeeEnrollments) = IpcEnrollments(e.originals.map(IpcEnrollmentReference::of), e.newDefinitionAvailable) }
}

@Serializable internal data class IpcEnrollment(val reviewId: String, val originalRequestId: String, val actorId: String,
    val scope: IpcScope, val admittedDeviceIds: List<String>, val stage: String, val originalActionId: String? = null,
    val nativePhase: String? = null, val requested: String? = null, val companyName: String? = null, val headName: String? = null,
    val headLogin: String? = null, val rootFingerprint: String? = null, val serviceProblem: String? = null,
    val firstJournalEnrollmentAvailable: Boolean = false) {
    fun value() = LocalSetupEmployeeEnrollmentView(id(reviewId), id(originalRequestId), id(actorId), scope.value(), ids(admittedDeviceIds),
        LocalSetupEmployeeEnrollmentStage.valueOf(stage), originalActionId?.let(::id), nativePhase?.let { LocalEmployeeEnrollmentPhase.valueOf(it) },
        requested?.let { LocalEmployeeEnrollmentDefinition.decode(requireNotNull(DesktopSetupIpcWire.bytes(it))) },
        companyName, headName, headLogin, rootFingerprint, serviceProblem?.let { LocalServiceProblem.valueOf(it) }, firstJournalEnrollmentAvailable)
    companion object { fun of(e: LocalSetupEmployeeEnrollmentView) = IpcEnrollment(e.reviewId.value, e.originalRequestId.value,
        e.actorId.value, IpcScope.of(e.scope), idList(e.admittedDeviceIds), e.stage.name, e.originalActionId?.value, e.nativePhase?.name,
        DesktopSetupIpcWire.text(e.requested?.copyWireBytes()), e.companyName, e.headName, e.headLogin, e.rootFingerprint,
        e.serviceProblem?.name, e.firstJournalEnrollmentAvailable) }
}

@Serializable internal data class IpcPolicyReference(val originalActionId: String, val originalRequestId: String,
    val actorId: String, val phase: String) {
    fun value() = LocalEmployeePolicyActivationReference(id(originalActionId), id(originalRequestId), id(actorId),
        LocalEmployeePolicyActivationPhase.valueOf(phase))
    companion object { fun of(r: LocalEmployeePolicyActivationReference) = IpcPolicyReference(r.originalActionId.value,
        r.originalRequestId.value, r.actorId.value, r.phase.name) }
}

@Serializable internal data class IpcPolicies(val originals: List<IpcPolicyReference>, val newDefinitionAvailable: Boolean,
    val suggestedTarget: IpcPolicyTarget? = null) {
    fun value() = LocalSetupEmployeePolicies(originals.map { it.value() }, newDefinitionAvailable, suggestedTarget?.value())
    companion object { fun of(p: LocalSetupEmployeePolicies) = IpcPolicies(p.originals.map(IpcPolicyReference::of),
        p.newDefinitionAvailable, p.suggestedTarget?.let(IpcPolicyTarget::of)) }
}

@Serializable internal data class IpcPolicy(val reviewId: String, val originalRequestId: String, val scope: IpcScope, val stage: String,
    val actorId: String? = null, val suggestedTarget: IpcPolicyTarget? = null, val originalActionId: String? = null,
    val nativePhase: String? = null, val submittedPolicy: String? = null, val requested: String? = null,
    val companyName: String? = null, val headName: String? = null, val headLogin: String? = null, val rootFingerprint: String? = null,
    val headAuthorityId: String? = null, val headEnrollmentId: String? = null, val authorityDigest: String? = null,
    val outcome: String? = null, val eventId: String? = null) {
    fun value() = LocalSetupEmployeePolicyView(id(reviewId), id(originalRequestId), scope.value(), LocalSetupEmployeePolicyStage.valueOf(stage),
        actorId?.let(::id), suggestedTarget?.value(), originalActionId?.let(::id),
        nativePhase?.let { LocalEmployeePolicyActivationPhase.valueOf(it) }, submittedPolicy?.let(LocalSetupPolicyTransferV1::decodePolicy),
        requested?.let { LocalEmployeePolicyActivationDefinition.decode(requireNotNull(DesktopSetupIpcWire.bytes(it))) },
        companyName, headName, headLogin, rootFingerprint, headAuthorityId?.let(::id), headEnrollmentId?.let(::id), authorityDigest,
        outcome?.let { LocalSetupEmployeePolicyOutcome.valueOf(it) }, eventId?.let(::id))
    companion object { fun of(p: LocalSetupEmployeePolicyView) = IpcPolicy(p.reviewId.value, p.originalRequestId.value,
        IpcScope.of(p.scope), p.stage.name, p.actorId?.value, p.suggestedTarget?.let(IpcPolicyTarget::of), p.originalActionId?.value,
        p.nativePhase?.name, p.submittedPolicy?.let(LocalSetupPolicyTransferV1::encodePolicy),
        DesktopSetupIpcWire.text(p.requested?.copyWireBytes()), p.companyName, p.headName, p.headLogin, p.rootFingerprint,
        p.headAuthorityId?.value, p.headEnrollmentId?.value, p.authorityDigest, p.outcome?.name, p.eventId?.value) }
}

@Serializable internal data class IpcPairingReference(val originalActionId: String, val originalRequestId: String,
    val deviceId: String, val keyId: String, val phase: String) {
    fun value() = LocalDevicePairingReference(id(originalActionId), id(originalRequestId), id(deviceId), id(keyId), LocalDevicePairingPhase.valueOf(phase))
    companion object { fun of(r: LocalDevicePairingReference) = IpcPairingReference(r.originalActionId.value,
        r.originalRequestId.value, r.deviceId.value, r.keyId.value, r.phase.name) }
}

@Serializable internal data class IpcAdmissions(val originals: List<IpcPairingReference>, val unresolvedActionId: String? = null) {
    fun value() = LocalSetupDeviceAdmissions(originals.map { it.value() }, unresolvedActionId?.let(::id))
    companion object { fun of(a: LocalSetupDeviceAdmissions) = IpcAdmissions(a.originals.map(IpcPairingReference::of), a.unresolvedActionId?.value) }
}

@Serializable internal data class IpcAdmission(val reviewId: String, val originalActionId: String, val originalRequestId: String,
    val deviceId: String, val keyId: String, val stage: String, val nativePhase: String? = null, val requested: String? = null,
    val companyName: String? = null, val headName: String? = null, val headLogin: String? = null, val rootFingerprint: String? = null,
    val challengeDigest: String? = null, val authorityDigest: String? = null, val headAttempted: Boolean = false,
    val admissionAttempted: Boolean = false, val outcome: String? = null, val eventId: String? = null) {
    fun value() = LocalSetupDeviceAdmissionView(id(reviewId), id(originalActionId), id(originalRequestId), id(deviceId), id(keyId),
        LocalSetupDeviceAdmissionStage.valueOf(stage), nativePhase?.let { LocalDevicePairingPhase.valueOf(it) },
        requested?.let { LocalDevicePairingDefinition.decode(requireNotNull(DesktopSetupIpcWire.bytes(it))) },
        companyName, headName, headLogin, rootFingerprint, challengeDigest, authorityDigest, headAttempted, admissionAttempted,
        outcome?.let { LocalSetupDeviceAdmissionOutcome.valueOf(it) }, eventId?.let(::id))
    companion object { fun of(a: LocalSetupDeviceAdmissionView) = IpcAdmission(a.reviewId.value, a.originalActionId.value,
        a.originalRequestId.value, a.deviceId.value, a.keyId.value, a.stage.name, a.nativePhase?.name,
        DesktopSetupIpcWire.text(a.requested?.copyWireBytes()), a.companyName, a.headName, a.headLogin, a.rootFingerprint,
        a.challengeDigest, a.authorityDigest, a.headAttempted, a.admissionAttempted, a.outcome?.name, a.eventId?.value) }
}

@Serializable internal data class IpcTargetReference(val originalPreparationId: String, val deviceId: String, val keyId: String,
    val phase: String, val originalResponseId: String? = null, val originalPairingActionId: String? = null,
    val challengeDigest: String? = null, val originalSdkAcquisitionId: String? = null, val sdkIdentityDigest: String? = null) {
    fun value() = LocalDevicePairingTargetReference(id(originalPreparationId), id(deviceId), id(keyId),
        LocalDevicePairingTargetPhase.valueOf(phase), originalResponseId?.let(::id), originalPairingActionId?.let(::id),
        challengeDigest?.let(::PayloadDigest), originalSdkAcquisitionId?.let(::id), sdkIdentityDigest?.let(::PayloadDigest))
    companion object { fun of(r: LocalDevicePairingTargetReference) = IpcTargetReference(r.originalPreparationId.value, r.deviceId.value,
        r.keyId.value, r.phase.name, r.originalResponseId?.value, r.originalPairingActionId?.value, r.challengeDigest?.value,
        r.originalSdkAcquisitionId?.value, r.sdkIdentityDigest?.value) }
}

@Serializable internal data class IpcMasterOriginal(val originalRequestId: String, val originalActionId: String? = null,
    val deviceId: String, val keyId: String) {
    fun value() = LocalSetupFreshDeviceMasterOriginalView(id(originalRequestId), originalActionId?.let(::id), id(deviceId), id(keyId))
    companion object { fun of(m: LocalSetupFreshDeviceMasterOriginalView) = IpcMasterOriginal(m.originalRequestId.value,
        m.originalActionId?.value, m.deviceId.value, m.keyId.value) }
}

@Serializable internal data class IpcDocument(val kind: String, val outcome: String, val byteCount: Int? = null) {
    fun value() = LocalSetupPairingDocumentView(LocalSetupPairingDocumentKind.valueOf(kind), LocalSetupPairingDocumentOutcome.valueOf(outcome), byteCount)
    companion object { fun of(d: LocalSetupPairingDocumentView) = IpcDocument(d.kind.name, d.outcome.name, d.byteCount) }
}

@Serializable internal data class IpcPairing(val reviewId: String, val role: String, val stage: String,
    val targetInput: IpcFreshTargetInput? = null, val originalIntentId: String? = null, val originalRequestId: String? = null,
    val targetReference: IpcTargetReference? = null, val identity: String? = null, val challenge: String? = null,
    val possession: String? = null, val nativePhase: String? = null, val preparationAttempted: Boolean = false,
    val sdkAttempted: Boolean = false, val responseAttempted: Boolean = false, val proofAttempted: Boolean = false,
    val masterOriginals: List<IpcMasterOriginal> = emptyList(), val blockedRequestId: String? = null,
    val document: IpcDocument? = null, val retirement: String? = null, val retirementAttempted: Boolean = false,
    val continuationAttempted: Boolean = false, val responseCycle: Int = 0, val masterTransfer: String? = null,
    val verifiedMaster: String? = null) {
    private fun b(value: String?) = value?.let { requireNotNull(DesktopSetupIpcWire.bytes(it)) }
    fun value() = LocalSetupFreshDevicePairingView(id(reviewId), LocalSetupFreshDevicePairingRole.valueOf(role),
        LocalSetupFreshDevicePairingStage.valueOf(stage), targetInput?.value(), originalIntentId?.let(::id),
        originalRequestId?.let(::id), targetReference?.value(), b(identity)?.let(LocalDevicePairingSdkIdentityV2::decode),
        b(challenge)?.let(LocalDevicePairingChallenge::decode), b(possession)?.let(LocalDevicePairingPossession::decode),
        nativePhase?.let { LocalDevicePairingPhase.valueOf(it) }, preparationAttempted, sdkAttempted, responseAttempted,
        proofAttempted, masterOriginals.map { it.value() }, blockedRequestId?.let(::id), document?.value(),
        b(retirement)?.let(LocalDevicePairingUnsignedRetirement::decode), retirementAttempted, continuationAttempted,
        responseCycle, b(masterTransfer)?.let(LocalDevicePairingMasterTransfer::decode), b(verifiedMaster)?.let(LocalMasterSdkOriginal::decode))
    companion object {
        private fun t(value: ByteArray?) = DesktopSetupIpcWire.text(value)
        fun of(p: LocalSetupFreshDevicePairingView) = IpcPairing(p.reviewId.value, p.role.name, p.stage.name,
            p.targetInput?.let(IpcFreshTargetInput::of), p.originalIntentId?.value, p.originalRequestId?.value,
            p.targetReference?.let(IpcTargetReference::of), t(p.identity?.copyWireBytes()), t(p.challenge?.copyWireBytes()),
            t(p.possession?.copyWireBytes()), p.nativePhase?.name, p.preparationAttempted, p.sdkAttempted, p.responseAttempted,
            p.proofAttempted, p.masterOriginals.map(IpcMasterOriginal::of), p.blockedRequestId?.value, p.document?.let(IpcDocument::of),
            t(p.retirement?.copyWireBytes()), p.retirementAttempted, p.continuationAttempted, p.responseCycle,
            t(p.masterTransfer?.copyWireBytes()), t(p.verifiedMaster?.copyWireBytes()))
    }
}

@Serializable internal data class IpcLan(val role: String? = null, val status: String = LocalSetupLanStatus.NOT_ATTACHED.name,
    val endpoint: IpcEndpoint? = null, val hostAvailable: Boolean = false, val clientAvailable: Boolean = false,
    val authenticationAvailable: Boolean = false) {
    fun value() = LocalSetupLanView(role?.let { LocalSetupLanRole.valueOf(it) }, LocalSetupLanStatus.valueOf(status),
        endpoint?.value(), hostAvailable, clientAvailable, authenticationAvailable)
    companion object { fun of(l: LocalSetupLanView) = IpcLan(l.role?.name, l.status.name,
        l.endpoint?.let { IpcEndpoint(it.address, it.port) }, l.hostAvailable, l.clientAvailable, l.authenticationAvailable) }
}
