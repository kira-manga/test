package com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow

import com.ukkera.pos.app.setup.LocalSetupFreshDeviceTargetInput
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId

/**
 * Selected logical correlations from the unchanged FDPW v1 fixtures. Native point and
 * signature-sized buffers below are synthetic DATA carriers, never key possession, a
 * genuine Kit, native custody, an admitted device, SQLite or protected history evidence.
 * The real typed binary codecs encode/decode the carriers without a new envelope.
 */
internal class FreshDevicePairingWorkflowData {
    val scope = BranchScope(id("organization_A"), id("company_A"), id("branch_A"))
    val masterDeviceId = id("master_device_M")
    val installationId = id("master_installation_M")
    val rootId = id("master_root_M")
    val headAuthorityId = id("master_authority_M")
    val capabilities = setOf("stock.read", "orders.write")
    val originalFixtureAppId = "pos.fixture.local"
    val appId = NewDeviceBranchNamespaceV1Carrier.FDPW_APP_ID
    val privateName = "FDPW private target canary"
    val targetInput get() = LocalSetupFreshDeviceTargetInput(scope, capabilities, appId, privateName)

    private val coordinates = SelfOwnedAllocationCoordinatesV1(
        allocationId = fillerId(0x3001), scope = scope, installationId = installationId,
        assignmentId = fillerId(0x3002), deviceId = masterDeviceId,
        storageInstanceId = fillerId(0x3003), storageKeyId = fillerId(0x3004),
        pepperKeyId = fillerId(0x3005), targetSigningKeyId = id("master_native_key_M"),
        firstActorId = fillerId(0x3007),
    )
    private val rootKeyId = fillerId(0x3008)
    private val foundingActionId = fillerId(0x3009)
    private val headEnrollmentId = fillerId(0x300b)
    private val installationRequestId = fillerId(0x300c)
    private val installationAttemptId = fillerId(0x300d)
    private val issuerContextId = fillerId(0x300e)
    private val bootId = fillerId(0x300f)
    private val rootDocumentDigest = fillerDigest("root-document")
    private val delegationDigest = fillerDigest("delegation-document")
    private val preparationDigest = fillerDigest("preparation-document")
    private val foundingBodyDigest = fillerDigest("founding-body")
    private val rootFingerprint = fillerDigest("root-public-fingerprint").value
    private val hostApplication = SelfOwnedTargetDescriptorV1(
        "synthetic.pos.fresh_workflow_host", 12000,
        fillerDigest("host-apk-signer").value, fillerDigest("host-android-id").value,
    )
    private val binding = createBinding()
    private val bindingDigest = ownedDigest(binding.copyWireBytes())
    private val material = SelfOwnedNativeMaterialView(
        coordinates, hostApplication, "Synthetic fresh workflow branch",
        SelfOwnedKeyProtectionV1.OS_ISOLATED, ownedDigest(publicPoint(0x80)).value,
        bindingDigest.value, SelfOwnedStorageKeySecurity.SOFTWARE, newCeremoniesAllowed = false,
    )
    private val allocation = publicPoint(0x80).let { point ->
        try { OriginalAllocationCommitmentV2(coordinates, bindingDigest, rootDocumentDigest,
            delegationDigest, preparationDigest, point) } finally { point.fill(0) }
    }
    private val receipt = LocalBranchInstallationReceiptV2(LocalBranchInstallationGenesisCoreV2(
        allocation, fillerDigest("installation-definition"), installationRequestId, installationAttemptId,
        1, Revision(0), fillerDigest("installation-human-approval"), fillerDigest("installation-target-proof"),
        issuerContextId, bootId, 0,
        java.time.Instant.parse("2026-11-02T12:00:00Z").toEpochMilli(),
        fillerDigest("installation-custody-data"), headAuthorityId, headEnrollmentId, foundingBodyDigest,
        LocalInstallationSeedDigestsV2(fillerDigest("seed-account"), fillerDigest("seed-policy"),
            fillerDigest("seed-configuration"), fillerDigest("seed-admission"), fillerDigest("seed-account-throttle"),
            fillerDigest("seed-device-throttle"), fillerDigest("seed-recovery")),
    ))
    val originalSetup = LocalOriginalInstallationSetup(
        LocalCompanyAuthorityView(rootId, scope.organizationId, scope.companyId,
            "Synthetic fresh workflow company", rootFingerprint, SelfOwnedKeyProtectionV1.OS_ISOLATED,
            headAuthorityId, headEnrollmentId, "Synthetic named head", "fresh-workflow-head",
            Revision(0), Revision(0), headEnabled = true, policyIssuanceEnabled = false,
            installationIssuanceEnabled = false, foundingBodyDigest = foundingBodyDigest),
        definition = null, receipt = receipt,
    )
    private val observation = LocalAuthenticationObservation(issuerContextId, MonotonicMoment(bootId, 0))
    private val preparedHead = NativeDevicePairingHeadV1(
        "Synthetic fresh workflow company", "Synthetic named head", "fresh-workflow-head",
        accountRevision = 0, credentialRevision = 0, retainedRevision = 0, osBootCount = 0,
        observation = observation, deadlineElapsedMillis = ORIGINAL_DEADLINE_MILLIS,
    )
    private val baseOrigin = NativeDevicePairingOriginV1(
        coordinates, allocation.digest, bindingDigest, installationRequestId, receipt.core.definitionDigest,
        receipt.digest, Revision(0),
        NativeHistoryRecordedHeadV2(1, fillerDigest("history-head"), receipt.genesis, 0, 0,
            fillerDigest("command-head"), fillerDigest("role-commitment"), fillerDigest("recovery-commitment")),
        fillerDigest("preparation-anchor"), rootId, rootKeyId, rootDocumentDigest,
        foundingActionId, headAuthorityId, headEnrollmentId, rootFingerprint,
    )
    // This marker wraps controlled DATA only; it cannot be a native owner capability.
    private val dataOwner = Any()

    val identityT = identity(alternate = false)
    val identityU = identity(alternate = true)

    fun freshContext(): SelfOwnedLocalSecurityContext = SelfOwnedLocalSecurityContext(
        FreshWorkflowBindingDataContext(material, binding),
    )

    fun keyReference(identity: LocalDevicePairingSdkIdentityV2 = identityT): LocalDevicePairingTargetReference =
        LocalDevicePairingTargetReference(identity.originalPreparationId, identity.target.admission.deviceId,
            identity.target.keyId, LocalDevicePairingTargetPhase.KEY_READY)

    fun sdkReference(identity: LocalDevicePairingSdkIdentityV2 = identityT): LocalDevicePairingTargetReference =
        keyReference(identity).copy(phase = LocalDevicePairingTargetPhase.SDK_READY,
            originalSdkAcquisitionId = identity.originalAcquisitionId, sdkIdentityDigest = identity.descriptorDigest)

    fun responseReference(original: FreshDevicePairingWorkflowOriginal): LocalDevicePairingTargetReference =
        sdkReference(original.identity).copy(phase = LocalDevicePairingTargetPhase.RESPONSE_COMPLETED,
            originalResponseId = fillerId(0x3101), originalPairingActionId = original.prepared.originalActionId,
            challengeDigest = original.challenge.challengeDigest)

    /** The controller-assigned request is the runtime carrier for the fixed logical A correlation. */
    fun original(requestId: DomainId = id("master_request_A"), identity: LocalDevicePairingSdkIdentityV2 = identityT,
        actionId: DomainId = id("pairing_action_A"),
        origin: NativeDevicePairingOriginV1 = baseOrigin): FreshDevicePairingWorkflowOriginal {
        val retained = listOf(LocalDeviceAdmission(scope, masterDeviceId, Revision(0), true,
            coordinates.targetSigningKeyId.value, material.targetSigningFingerprint, emptySet()))
        val serviceData = "controlled-complete-requested-admission".encodeToByteArray()
        val requested = try { LocalDevicePairingDefinition.captureSdkBound(requestId,
            identity.target, serviceData, retained, identity) } finally { serviceData.fill(0) }
        val body = NativeDevicePairingBodyV1(actionId, origin,
            ownedOctets(requested.copyWireBytes(), LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES),
            preparedHead, ownedOctets(ByteArray(32) { 0x11 }, 32))
        val challenge = LocalDevicePairingChallenge.fromNative(body)
        val signatureCarrier = ByteArray(64) { 0x11 }
        val possession = try { LocalDevicePairingPossession.capture(challenge, signatureCarrier) }
            finally { signatureCarrier.fill(0) }
        val prepared = NativeDevicePairingRecordV1(body, LocalDevicePairingPhase.PREPARED)
        val proved = NativeDevicePairingRecordV1(body, LocalDevicePairingPhase.TARGET_PROVED,
            ownedOctets(possession.copyWireBytes(), LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES))
        return FreshDevicePairingWorkflowOriginal(identity, LocalDevicePairingAction(dataOwner, prepared),
            LocalDevicePairingAction(dataOwner, proved), possession)
    }

    fun foreignOriginal(): FreshDevicePairingWorkflowOriginal = original(
        id("alternate_master_request"), identityU, id("alternate_action"),
    )

    private fun identity(alternate: Boolean): LocalDevicePairingSdkIdentityV2 {
        val keyId = id(if (alternate) "alternate_native_key_U" else "target_native_key_T")
        val deviceId = id(if (alternate) "alternate_device_U" else "target_device_T")
        val point = publicPoint(if (alternate) 0x40 else 0)
        val target = try { LocalDevicePairingTargetDescriptor.capture(
            LocalDeviceAdmission(scope, deviceId, Revision(0), true, keyId.value, sha256(point).value,
                capabilities), keyId, point) } finally { point.fill(0) }
        val digestText = if (alternate) "c".repeat(51) + "a" else "a".repeat(52)
        val peerText = if (alternate) "b".repeat(51) + "a" else "a".repeat(52)
        val appBinding = if (alternate) "d".repeat(51) + "a" else "a".repeat(52)
        return LocalDevicePairingSdkIdentityV2.capture(
            id(if (alternate) "alternate_acquisition_U" else "target_acquisition_T"),
            id(if (alternate) "alternate_preparation_U" else "target_preparation_T"), target,
            SelfOwnedTargetDescriptorV1(if (alternate) "synthetic.pos.fresh_workflow_u" else "synthetic.pos.fresh_workflow_t",
                if (alternate) 12002 else 12001, fillerDigest(if (alternate) "u-apk" else "t-apk").value,
                fillerDigest(if (alternate) "u-android-id" else "t-android-id").value),
            AppId(appId), PeerId("p2id2-$peerText"), PeerFingerprint("p2f1-$digestText"),
            "p2pkit:v2:p2a1-$appBinding:p2f1-$digestText",
        )
    }

    private fun createBinding(): SelfOwnedNativeBindingV1 {
        val body = SelfOwnedNativeInstallationWireV1.encodeBindingBody(SelfOwnedNativeBindingBodyV1(
            coordinates, hostApplication, rootDocumentDigest.value, delegationDigest.value,
            preparationDigest.value, fillerDigest("completed-native-intent").value,
            ownedOctets(publicPoint(0x80), 65), SelfOwnedKeyProtectionV1.OS_ISOLATED,
            SelfOwnedNativeSecurityV1.SOFTWARE, SelfOwnedNativeSecurityV1.SOFTWARE,
            SelfOwnedNativeSecurityV1.SOFTWARE,
        ))
        val signatureCarrier = ByteArray(64) { 0x33 }
        return try { SelfOwnedNativeBindingV1(body, signatureCarrier) }
            finally { body.fill(0); signatureCarrier.fill(0) }
    }

    companion object {
        const val ORIGINAL_DEADLINE_MILLIS = 300_000L
        private val handles = listOf("organization_A", "company_A", "branch_A", "master_device_M",
            "master_native_key_M", "master_root_M", "master_installation_M", "master_authority_M",
            "target_device_T", "target_native_key_T", "target_preparation_T", "target_acquisition_T",
            "target_installed_application_T", "pairing_action_A", "master_request_A", "target_input_intent_A",
            "possession_intent_A", "alternate_device_U", "alternate_native_key_U", "alternate_preparation_U",
            "alternate_acquisition_U", "alternate_installed_application_U", "organization_B", "company_B", "branch_B",
            "alternate_master_root", "alternate_master_installation", "alternate_master_authority", "alternate_action",
            "alternate_master_request").mapIndexed { index, label ->
            label to DomainId("10000000-0000-4000-8000-" + (index + 1).toString().padStart(12, '0'))
        }.toMap()
        fun id(label: String): DomainId = handles.getValue(label)
    }
}

internal class FreshDevicePairingWorkflowOriginal(val identity: LocalDevicePairingSdkIdentityV2,
    val prepared: LocalDevicePairingAction, val proved: LocalDevicePairingAction,
    val possession: LocalDevicePairingPossession) {
    val challenge get() = prepared.challenge
    val preparedReference get() = prepared.record.reference()
    val provedReference get() = proved.record.reference()
    override fun toString(): String = "FreshDevicePairingWorkflowOriginal([CONTROLLED DATA])"
}

/** Binding read DATA only. Every history-custody or new-authority operation refuses. */
private class FreshWorkflowBindingDataContext(override val materialAtOpen: SelfOwnedNativeMaterialView,
    private val binding: SelfOwnedNativeBindingV1) : SelfOwnedNativeContextController {
    @Volatile private var readable = true
    override suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult = if (readable)
        SelfOwnedNativeReadResult.Retained(materialAtOpen, binding)
        else SelfOwnedNativeReadResult.Unresolved(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    override suspend fun withdrawNewCeremonies(): SelfOwnedNativeWithdrawal =
        SelfOwnedNativeWithdrawal.Unavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    override fun denyContextNewCeremonies() = Unit
    override fun denyContextDataUse() { readable = false }
    override suspend fun closeContext() { readable = false }
    override suspend fun claimOriginalHistoryCustody(): SelfOwnedNativeHistoryClaimResult =
        SelfOwnedNativeHistoryClaimResult.Unresolved(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
}

private fun fillerId(value: Int): DomainId = DomainId(
    "20000000-0000-4000-8000-" + value.toString(16).padStart(12, '0'))
private fun publicPoint(offset: Int): ByteArray = ByteArray(65) { index ->
    if (index == 0) 4.toByte() else (offset + index).toByte() }
private fun fillerDigest(slot: String): PayloadDigest = ownedDigest(("controlled-fresh-workflow-v1:$slot").encodeToByteArray())
private fun ownedDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
private fun ownedOctets(bytes: ByteArray, maximum: Int): SelfOwnedOctetsV1 =
    try { SelfOwnedOctetsV1(bytes, maximum) } finally { bytes.fill(0) }
