package com.ukkera.pos.app.gateway.setup.independent.retaineddeviceadmissionworkflowv1

import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId

/**
 * Typed controlled application DATA for the unchanged retained-workflow v1 fixtures.
 * Public points and signature-sized arrays below are synthetic carriers, not signatures,
 * native custody, SDK possession, permits, installation/SQL evidence or employee sessions.
 * The only positive context operation returns immutable binding DATA for controlled app
 * bootstrap; its history-custody and unused authority operations always refuse.
 */
internal class RetainedDeviceAdmissionWorkflowV1Data {
    val scope: BranchScope = BranchScope(
        DomainId("00000000-0000-4000-8000-000000000001"),
        DomainId("00000000-0000-4000-8000-000000000002"),
        DomainId("00000000-0000-4000-8000-000000000003"),
    )
    val rootId: DomainId = DomainId("00000000-0000-4000-8000-000000000004")
    val cohostDeviceId: DomainId = DomainId("00000000-0000-4000-8000-000000000005")
    val installationId: DomainId = DomainId("00000000-0000-4000-8000-000000000006")
    val headAccountId: DomainId = DomainId("00000000-0000-4000-8000-00000000000d")
    val headLogin: String = "retained-workflow-head"

    private val coordinates: SelfOwnedAllocationCoordinatesV1 = SelfOwnedAllocationCoordinatesV1(
        allocationId = fillerId(0x3001), scope = scope, installationId = installationId,
        assignmentId = fillerId(0x3002), deviceId = cohostDeviceId,
        storageInstanceId = fillerId(0x3003), storageKeyId = fillerId(0x3004),
        pepperKeyId = fillerId(0x3005), targetSigningKeyId = fillerId(0x3006),
        firstActorId = fillerId(0x3007),
    )
    private val rootKeyId: DomainId = fillerId(0x3008)
    private val foundingActionId: DomainId = fillerId(0x3009)
    private val headAuthorityId: DomainId = fillerId(0x300a)
    private val headEnrollmentId: DomainId = fillerId(0x300b)
    private val installationRequestId: DomainId = fillerId(0x300c)
    private val installationAttemptId: DomainId = fillerId(0x300d)
    private val issuerContextId: DomainId = fillerId(0x300e)
    private val bootId: DomainId = fillerId(0x300f)
    private val rootDocumentDigest: PayloadDigest = fillerDigest("root-document")
    private val delegationDigest: PayloadDigest = fillerDigest("delegation-document")
    private val preparationDigest: PayloadDigest = fillerDigest("preparation-document")
    private val foundingBodyDigest: PayloadDigest = fillerDigest("founding-body")
    private val rootFingerprint: String = fillerDigest("root-public-fingerprint").value
    private val hostApplication: SelfOwnedTargetDescriptorV1 = SelfOwnedTargetDescriptorV1(
        "synthetic.pos.retained_workflow_host", 12000,
        fillerDigest("host-apk-signer").value, fillerDigest("host-android-id").value,
    )
    private val binding: SelfOwnedNativeBindingV1 = createBinding()
    private val bindingDigest: PayloadDigest = ownedDigest(binding.copyWireBytes())
    private val material: SelfOwnedNativeMaterialView = SelfOwnedNativeMaterialView(
        coordinates, hostApplication, "Synthetic retained workflow branch",
        SelfOwnedKeyProtectionV1.OS_ISOLATED, ownedDigest(publicPoint(0x80)).value,
        bindingDigest.value, SelfOwnedStorageKeySecurity.SOFTWARE, newCeremoniesAllowed = false,
    )
    private val allocation: OriginalAllocationCommitmentV2 = publicPoint(0x80).let { point ->
        try {
            OriginalAllocationCommitmentV2(coordinates, bindingDigest, rootDocumentDigest,
                delegationDigest, preparationDigest, point)
        } finally { point.fill(0) }
    }
    private val receipt: LocalBranchInstallationReceiptV2 = createReceipt()

    val context: SelfOwnedLocalSecurityContext = freshContext()
    val originalSetup: LocalOriginalInstallationSetup = LocalOriginalInstallationSetup(
        LocalCompanyAuthorityView(
            rootId, scope.organizationId, scope.companyId, "Synthetic retained workflow company",
            rootFingerprint, SelfOwnedKeyProtectionV1.OS_ISOLATED, headAuthorityId, headEnrollmentId,
            "Synthetic named head", headLogin, Revision(0), Revision(0), headEnabled = true,
            policyIssuanceEnabled = false, installationIssuanceEnabled = false,
            foundingBodyDigest = foundingBodyDigest,
        ),
        definition = null, receipt = receipt,
    )

    private val observation: LocalAuthenticationObservation = LocalAuthenticationObservation(
        issuerContextId, MonotonicMoment(bootId, BEFORE_EXPIRY_ELAPSED_MILLIS),
    )
    private val preparedHead: NativeDevicePairingHeadV1 = NativeDevicePairingHeadV1(
        "Synthetic retained workflow company", "Synthetic named head", headLogin,
        accountRevision = 0, credentialRevision = 0, retainedRevision = 0, osBootCount = 0,
        observation = observation, deadlineElapsedMillis = ORIGINAL_EXPIRY_ELAPSED_MILLIS,
    )
    private val origin: NativeDevicePairingOriginV1 = NativeDevicePairingOriginV1(
        coordinates, allocation.digest, bindingDigest, installationRequestId,
        receipt.core.definitionDigest, receipt.digest, Revision(0),
        NativeHistoryRecordedHeadV2(1, fillerDigest("history-head"), receipt.genesis, 0, 0,
            fillerDigest("command-head"), fillerDigest("role-commitment"), fillerDigest("recovery-commitment")),
        fillerDigest("preparation-anchor"), rootId, rootKeyId, rootDocumentDigest,
        foundingActionId, headAuthorityId, headEnrollmentId, rootFingerprint,
    )
    // This marker can wrap DATA only. It is not a native controller or a usable owner capability.
    private val dataOwner: Any = Any()

    val a: RetainedDeviceAdmissionWorkflowV1Original = original(
        Literals(
            acquisitionId = "00000000-0000-4000-8000-000000000007",
            preparationId = "00000000-0000-4000-8000-000000000008",
            deviceId = "00000000-0000-4000-8000-000000000009",
            keyId = "00000000-0000-4000-8000-00000000000a",
            actionId = "00000000-0000-4000-8000-00000000000b",
            requestId = "00000000-0000-4000-8000-00000000000c",
            sdkPeerId = "p2id2-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            sdkFingerprint = "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            sdkQr = "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            serviceAdmissionToken = "controlled-complete-requested-admission-a",
            installedApplicationToken = "controlled-installed-application-facts-a",
            installedApplicationId = "synthetic.pos.retained_workflow_a", installedApplicationUid = 12001,
            pointOffset = 0, signatureCarrier = 0x11, consentId = fillerId(0x3101),
        ),
    )
    val b: RetainedDeviceAdmissionWorkflowV1Original = original(
        Literals(
            acquisitionId = "00000000-0000-4000-8000-000000000107",
            preparationId = "00000000-0000-4000-8000-000000000108",
            deviceId = "00000000-0000-4000-8000-000000000109",
            keyId = "00000000-0000-4000-8000-00000000010a",
            actionId = "00000000-0000-4000-8000-00000000010b",
            requestId = "00000000-0000-4000-8000-00000000010c",
            sdkPeerId = "p2id2-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbba",
            sdkFingerprint = "p2f1-ccccccccccccccccccccccccccccccccccccccccccccccccccca",
            sdkQr = "p2pkit:v2:p2a1-ddddddddddddddddddddddddddddddddddddddddddddddddddda:p2f1-ccccccccccccccccccccccccccccccccccccccccccccccccccca",
            serviceAdmissionToken = "controlled-complete-requested-admission-b",
            installedApplicationToken = "controlled-installed-application-facts-b",
            installedApplicationId = "synthetic.pos.retained_workflow_b", installedApplicationUid = 12002,
            pointOffset = 0x40, signatureCarrier = 0x22, consentId = fillerId(0x3102),
        ),
    )

    /** Fresh controlled handle for coordinator recreation; no custody can be claimed from it. */
    fun freshContext(): SelfOwnedLocalSecurityContext = SelfOwnedLocalSecurityContext(
        ControlledBindingDataContext(material, binding),
    )

    private fun createBinding(): SelfOwnedNativeBindingV1 {
        val body = SelfOwnedNativeInstallationWireV1.encodeBindingBody(SelfOwnedNativeBindingBodyV1(
            coordinates, hostApplication, rootDocumentDigest.value, delegationDigest.value,
            preparationDigest.value, fillerDigest("completed-native-intent").value,
            ownedOctets(publicPoint(0x80), 65), SelfOwnedKeyProtectionV1.OS_ISOLATED,
            SelfOwnedNativeSecurityV1.SOFTWARE, SelfOwnedNativeSecurityV1.SOFTWARE,
            SelfOwnedNativeSecurityV1.SOFTWARE,
        ))
        val signature = ByteArray(64) { 0x33.toByte() }
        return try { SelfOwnedNativeBindingV1(body, signature) }
        finally { body.fill(0); signature.fill(0) }
    }

    private fun createReceipt(): LocalBranchInstallationReceiptV2 = LocalBranchInstallationReceiptV2(
        LocalBranchInstallationGenesisCoreV2(
            allocation, fillerDigest("installation-definition"), installationRequestId, installationAttemptId,
            1, Revision(0), fillerDigest("installation-human-approval"), fillerDigest("installation-target-proof"),
            issuerContextId, bootId, BEFORE_EXPIRY_ELAPSED_MILLIS,
            java.time.Instant.parse("2026-11-02T12:00:00Z").toEpochMilli(),
            fillerDigest("installation-custody-data"), headAuthorityId, headEnrollmentId, foundingBodyDigest,
            LocalInstallationSeedDigestsV2(fillerDigest("seed-account"), fillerDigest("seed-policy"),
                fillerDigest("seed-configuration"), fillerDigest("seed-admission"), fillerDigest("seed-account-throttle"),
                fillerDigest("seed-device-throttle"), fillerDigest("seed-recovery")),
        ),
    )

    private fun original(value: Literals): RetainedDeviceAdmissionWorkflowV1Original {
        val keyId = DomainId(value.keyId)
        val point = publicPoint(value.pointOffset)
        val target = try {
            LocalDevicePairingTargetDescriptor.capture(
                LocalDeviceAdmission(scope, DomainId(value.deviceId), Revision(0), true,
                    keyId.value, sha256(point).value, emptySet()), keyId, point,
            )
        } finally { point.fill(0) }
        val sdk = LocalDevicePairingSdkIdentityV2.capture(
            DomainId(value.acquisitionId), DomainId(value.preparationId), target,
            SelfOwnedTargetDescriptorV1(value.installedApplicationId, value.installedApplicationUid,
                tokenDigest(value.installedApplicationToken + ":apk-signer").value,
                tokenDigest(value.installedApplicationToken + ":android-id").value),
            AppId("synthetic-pos-retained-workflow-v1"), PeerId(value.sdkPeerId),
            PeerFingerprint(value.sdkFingerprint), value.sdkQr,
        )
        val retained = listOf(LocalDeviceAdmission(scope, cohostDeviceId, Revision(0), true,
            coordinates.targetSigningKeyId.value, material.targetSigningFingerprint, emptySet()))
        val serviceData = value.serviceAdmissionToken.encodeToByteArray()
        val requested: LocalDevicePairingDefinition
        val legacyRequested: LocalDevicePairingDefinition
        try {
            requested = LocalDevicePairingDefinition.captureSdkBound(DomainId(value.requestId),
                target, serviceData, retained, sdk)
            legacyRequested = LocalDevicePairingDefinition.capture(DomainId(value.requestId),
                target, serviceData, retained)
        } finally { serviceData.fill(0) }
        val body = pairingBody(requested, value)
        val prepared = NativeDevicePairingRecordV1(body, LocalDevicePairingPhase.PREPARED)
        val proved = targetProved(body, value.signatureCarrier)
        val consentDigest = ownedDigest(NativeDevicePairingWireV1.actionMessage(proved))
        val approved = NativeDevicePairingRecordV1(
            body, LocalDevicePairingPhase.APPROVED, proved.possession,
            NativeDevicePairingConsentV1(value.consentId, consentDigest, observation,
                0, 0, 0, 0, ORIGINAL_EXPIRY_ELAPSED_MILLIS),
            NativeDevicePairingApprovedHeadV1(0, 0, 0, 0, ORIGINAL_EXPIRY_ELAPSED_MILLIS),
            ownedOctets(ByteArray(64) { (value.signatureCarrier + 1).toByte() }, 64),
        )
        val legacy = targetProved(pairingBody(legacyRequested, value), value.signatureCarrier)
        return RetainedDeviceAdmissionWorkflowV1Original(
            LocalDevicePairingAction(dataOwner, proved), LocalDevicePairingApproval(approved), proved.reference(),
            LocalDevicePairingAction(dataOwner, approved), approved.reference(),
            LocalDevicePairingAction(dataOwner, prepared), prepared.reference(),
            LocalDevicePairingAction(dataOwner, legacy), legacy.reference(),
        )
    }

    private fun pairingBody(requested: LocalDevicePairingDefinition, value: Literals): NativeDevicePairingBodyV1 =
        NativeDevicePairingBodyV1(DomainId(value.actionId), origin,
            ownedOctets(requested.copyWireBytes(), LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES),
            preparedHead, ownedOctets(ByteArray(32) { value.signatureCarrier.toByte() }, 32))

    private fun targetProved(body: NativeDevicePairingBodyV1, signatureCarrier: Int): NativeDevicePairingRecordV1 {
        val requested = body.requested()
        val fields = NativeDevicePairingPossessionV1(body.originalActionId,
            LocalDevicePairingChallenge.fromNative(body).challengeDigest, requested.admission.deviceId,
            requested.target.keyId, requested.target.descriptorDigest,
            ownedOctets(ByteArray(64) { signatureCarrier.toByte() }, 64), requested.sdkIdentity?.descriptorDigest)
        return NativeDevicePairingRecordV1(body, LocalDevicePairingPhase.TARGET_PROVED,
            ownedOctets(NativeDevicePairingWireV1.encodePossession(fields), LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES))
    }

    private data class Literals(
        val acquisitionId: String, val preparationId: String, val deviceId: String, val keyId: String,
        val actionId: String, val requestId: String, val sdkPeerId: String, val sdkFingerprint: String, val sdkQr: String,
        val serviceAdmissionToken: String, val installedApplicationToken: String,
        val installedApplicationId: String, val installedApplicationUid: Int,
        val pointOffset: Int, val signatureCarrier: Int, val consentId: DomainId,
    )

    override fun toString(): String = "RetainedDeviceAdmissionWorkflowV1Data([CONTROLLED DATA])"

    companion object {
        // Fixed synthetic wall mapping: 12:00:00Z -> 0 ms; original 12:10:00Z -> 600,000 ms.
        const val BEFORE_EXPIRY_ELAPSED_MILLIS: Long = 0L
        const val ORIGINAL_EXPIRY_ELAPSED_MILLIS: Long = 600_000L
    }
}

internal class RetainedDeviceAdmissionWorkflowV1Original(
    val action: LocalDevicePairingAction,
    val approval: LocalDevicePairingApproval,
    val reference: LocalDevicePairingReference,
    val approvedAction: LocalDevicePairingAction,
    val approvedReference: LocalDevicePairingReference,
    val preparedAction: LocalDevicePairingAction,
    val preparedReference: LocalDevicePairingReference,
    val legacyAction: LocalDevicePairingAction,
    val legacyReference: LocalDevicePairingReference,
) {
    // Logical fixture metadata only: the native admission and app view have no label slot.
    val deviceDisplayLabel: String = "Synthetic new device"
    override fun toString(): String = "RetainedDeviceAdmissionWorkflowV1Original([CONTROLLED DATA])"
}

/** No writer, native history, ceremony, key, permit or production authority is supplied. */
private class ControlledBindingDataContext(
    override val materialAtOpen: SelfOwnedNativeMaterialView,
    private val binding: SelfOwnedNativeBindingV1,
) : SelfOwnedNativeContextController {
    @Volatile private var readable: Boolean = true

    override suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult = if (readable)
        SelfOwnedNativeReadResult.Retained(materialAtOpen, binding)
    else SelfOwnedNativeReadResult.Unresolved(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)

    override suspend fun withdrawNewCeremonies(): SelfOwnedNativeWithdrawal =
        SelfOwnedNativeWithdrawal.Unavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    override fun denyContextNewCeremonies() { /* Already denied in immutable material DATA. */ }
    override fun denyContextDataUse() { readable = false }
    override suspend fun closeContext() { readable = false }
    override suspend fun claimOriginalHistoryCustody(): SelfOwnedNativeHistoryClaimResult =
        SelfOwnedNativeHistoryClaimResult.Unresolved(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
}

/** Structural filler IDs only; frozen fixture IDs are copied as literals above. */
private fun fillerId(value: Int): DomainId = DomainId(
    "00000000-0000-4000-8000-" + value.toString(16).padStart(12, '0'),
)

private fun publicPoint(offset: Int): ByteArray = ByteArray(65) { index ->
    if (index == 0) 4.toByte() else (offset + index).toByte()
}

private fun fillerDigest(slot: String): PayloadDigest = tokenDigest("controlled-retained-workflow-v1:" + slot)
private fun tokenDigest(value: String): PayloadDigest = ownedDigest(value.encodeToByteArray())
private fun ownedDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
private fun ownedOctets(bytes: ByteArray, maximum: Int): SelfOwnedOctetsV1 =
    try { SelfOwnedOctetsV1(bytes, maximum) } finally { bytes.fill(0) }
