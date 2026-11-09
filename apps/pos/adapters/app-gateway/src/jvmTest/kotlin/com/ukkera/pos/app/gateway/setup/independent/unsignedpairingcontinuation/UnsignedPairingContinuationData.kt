package com.ukkera.pos.app.gateway.setup.independent.unsignedpairingcontinuation

import com.ukkera.pos.app.setup.LocalSetupFreshDeviceTargetInput
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId

/**
 * UPRC-v1/v2 B0 correlations with the independently frozen UPRC-v3 canonical
 * original-setup AppId, encoded by the production DATA codecs. Points,
 * signature-sized buffers and the root document are synthetic carriers. They are
 * not native keys, signatures, root verification, Kit correspondence or custody.
 */
internal class UnsignedPairingContinuationData {
    val scope = BranchScope(
        DomainId("11111111-1111-4111-8111-111111111111"),
        DomainId("22222222-2222-4222-8222-222222222222"),
        DomainId("33333333-3333-4333-8333-333333333333"),
    )
    val masterDeviceId = DomainId("44444444-4444-4444-8444-444444444444")
    val installationId = DomainId("55555555-5555-4555-8555-555555555555")
    val rootId = DomainId("66666666-6666-4666-8666-666666666666")
    val targetDeviceId = DomainId("77777777-7777-4777-8777-777777777777")
    val targetKeyId = DomainId("88888888-8888-4888-8888-888888888888")
    val targetPreparationId = DomainId("99999999-9999-4999-8999-999999999999")
    val targetAcquisitionId = DomainId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    val capabilities = setOf("pos.client", "inventory.read")
    // Frozen B0-CANONICAL-NEW input; do not derive an oracle from the implementation.
    val appId = "ukkera-pos.rpc.branch.v1:11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222222:33333333-3333-4333-8333-333333333333"
    val privateName = "Synthetic target counter A"
    val targetInput get() = LocalSetupFreshDeviceTargetInput(scope, capabilities, appId, privateName)
    val headAuthorityId = carrierId(0x300a)

    private val coordinates = SelfOwnedAllocationCoordinatesV1(
        carrierId(0x3001), scope, installationId, carrierId(0x3002), masterDeviceId,
        carrierId(0x3003), carrierId(0x3004), carrierId(0x3005), carrierId(0x3006), carrierId(0x3007),
    )
    private val rootKeyId = carrierId(0x3008)
    private val foundingActionId = carrierId(0x3009)
    private val headEnrollmentId = carrierId(0x300b)
    private val installationRequestId = carrierId(0x300c)
    private val installationAttemptId = carrierId(0x300d)
    private val issuerContextId = carrierId(0x300e)
    private val bootId = carrierId(0x300f)
    private val rootDocumentDigest = carrierDigest("root-document")
    private val delegationDigest = carrierDigest("delegation-document")
    private val preparationDigest = carrierDigest("preparation-document")
    private val foundingBodyDigest = carrierDigest("founding-body")
    private val rootFingerprint = carrierDigest("root-public-fingerprint").value
    private val hostApplication = SelfOwnedTargetDescriptorV1(
        "synthetic.pos.continuation_host", 12000,
        carrierDigest("host-apk-signer").value, carrierDigest("host-android-id").value,
    )
    private val binding = createBinding()
    private val bindingDigest = dataDigest(binding.copyWireBytes())
    private val material = SelfOwnedNativeMaterialView(
        coordinates, hostApplication, "Synthetic branch A", SelfOwnedKeyProtectionV1.OS_ISOLATED,
        dataDigest(point(0x80)).value, bindingDigest.value, SelfOwnedStorageKeySecurity.SOFTWARE,
        newCeremoniesAllowed = false,
    )
    private val allocation = point(0x80).let { bytes ->
        try { OriginalAllocationCommitmentV2(coordinates, bindingDigest, rootDocumentDigest,
            delegationDigest, preparationDigest, bytes) } finally { bytes.fill(0) }
    }
    private val receipt = LocalBranchInstallationReceiptV2(LocalBranchInstallationGenesisCoreV2(
        allocation, carrierDigest("installation-definition"), installationRequestId, installationAttemptId,
        1, Revision(0), carrierDigest("installation-human-approval"), carrierDigest("installation-target-proof"),
        issuerContextId, bootId, 0, 1, carrierDigest("installation-custody"),
        headAuthorityId, headEnrollmentId, foundingBodyDigest,
        LocalInstallationSeedDigestsV2(carrierDigest("seed-account"), carrierDigest("seed-policy"),
            carrierDigest("seed-configuration"), carrierDigest("seed-admission"), carrierDigest("seed-account-throttle"),
            carrierDigest("seed-device-throttle"), carrierDigest("seed-recovery")),
    ))
    val originalSetup = LocalOriginalInstallationSetup(
        LocalCompanyAuthorityView(rootId, scope.organizationId, scope.companyId, "Synthetic company A",
            rootFingerprint, SelfOwnedKeyProtectionV1.OS_ISOLATED, headAuthorityId, headEnrollmentId,
            "Synthetic named head", "continuation-head", Revision(0), Revision(0),
            headEnabled = true, policyIssuanceEnabled = false, installationIssuanceEnabled = false,
            foundingBodyDigest = foundingBodyDigest),
        definition = null, receipt = receipt,
    )
    private val baseOrigin = NativeDevicePairingOriginV1(
        coordinates, allocation.digest, bindingDigest, installationRequestId, receipt.core.definitionDigest,
        receipt.digest, Revision(0),
        NativeHistoryRecordedHeadV2(1, carrierDigest("history-head"), receipt.genesis, 0, 0,
            carrierDigest("command-head"), carrierDigest("role-commitment"), carrierDigest("recovery-commitment")),
        carrierDigest("preparation-anchor"), rootId, rootKeyId, rootDocumentDigest,
        foundingActionId, headAuthorityId, headEnrollmentId, rootFingerprint,
    )
    private val carrierOwner = Any()
    val identity = identity(alternate = false)
    val otherIdentity = identity(alternate = true)

    fun freshContext() = SelfOwnedLocalSecurityContext(ContinuationBindingDataContext(material, binding))
    fun actionId(index: Int) = carrierId(0x6000 + index * 8)
    fun requestId(index: Int) = carrierId(0x6001 + index * 8)
    fun responseId(index: Int) = carrierId(0x6002 + index * 8)
    fun keyReference() = LocalDevicePairingTargetReference(targetPreparationId, targetDeviceId, targetKeyId,
        LocalDevicePairingTargetPhase.KEY_READY)
    fun sdkReference(index: Int = 0) = keyReference().copy(
        phase = if (index == 0) LocalDevicePairingTargetPhase.SDK_READY else LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY,
        originalResponseId = responseId(index), originalSdkAcquisitionId = targetAcquisitionId,
        sdkIdentityDigest = identity.descriptorDigest,
    )
    fun responseReference(original: ContinuationOriginal, index: Int) = sdkReference(index).copy(
        phase = LocalDevicePairingTargetPhase.RESPONSE_COMPLETED,
        originalPairingActionId = original.prepared.originalActionId, challengeDigest = original.challenge.challengeDigest,
    )

    /** Runtime requests carry the fixture's logical MREQ correlations; no extra persisted UUID is added. */
    fun original(requestId: DomainId = requestId(0), identity: LocalDevicePairingSdkIdentityV2 = this.identity,
        index: Int = 0, previous: LocalDevicePairingUnsignedRetirement? = null,
        predecessorDigest: PayloadDigest? = previous?.dispositionDigest,
        differentRoot: Boolean = false): ContinuationOriginal {
        val retained = listOf(LocalDeviceAdmission(scope, masterDeviceId, Revision(0), true,
            coordinates.targetSigningKeyId.value, material.targetSigningFingerprint, emptySet()))
        val serviceData = "controlled-complete-requested-admission".encodeToByteArray()
        val requested = try { LocalDevicePairingDefinition.captureSdkBound(requestId,
            identity.target, serviceData, retained, identity) } finally { serviceData.fill(0) }
        val preparedAt = if (index == 0) OLD_PREPARED_AT else NEW_PREPARED_AT
        val head = NativeDevicePairingHeadV1("Synthetic company A", "Synthetic named head", "continuation-head",
            0, 0, 0, 0, LocalAuthenticationObservation(issuerContextId, MonotonicMoment(bootId, preparedAt)),
            preparedAt + PREPARATION_DEADLINE)
        val origin = if (differentRoot) baseOrigin.copy(rootId = carrierId(0x7000),
            rootKeyId = carrierId(0x7001), rootDocumentDigest = carrierDigest("other-root-document")) else baseOrigin
        val body = NativeDevicePairingBodyV1(actionId(index), origin,
            dataOctets(requested.copyWireBytes(), LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES),
            head, dataOctets(ByteArray(32) { 0x11 }, 32), predecessorDigest)
        val challenge = LocalDevicePairingChallenge.fromNative(body)
        val signature = ByteArray(64) { 0x11 }
        val possession = try { LocalDevicePairingPossession.capture(challenge, signature) } finally { signature.fill(0) }
        val prepared = NativeDevicePairingRecordV1(body, LocalDevicePairingPhase.PREPARED)
        val proved = prepared.copy(phase = LocalDevicePairingPhase.TARGET_PROVED,
            possession = dataOctets(possession.copyWireBytes(), LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES))
        return ContinuationOriginal(identity, LocalDevicePairingAction(carrierOwner, prepared),
            LocalDevicePairingAction(carrierOwner, proved), possession, index)
    }

    fun retirement(original: ContinuationOriginal, withPossession: Boolean = true): Pair<LocalDevicePairingAction, LocalDevicePairingUnsignedRetirement> {
        val unsigned = if (withPossession) original.proved.record else original.prepared.record
        val head = unsigned.body.preparedHead
        val disposition = NativeDevicePairingUnsignedDispositionV1(carrierId(0x6003 + original.index * 8),
            unsigned.phase, NativeDevicePairingWireV1.unsignedRecordDigest(unsigned.body, unsigned.phase,
                unsigned.possession, unsigned.consent), 1, 0, 0, 0,
            LocalAuthenticationObservation(head.observation.issuerContextId,
                MonotonicMoment(head.observation.moment.bootId, RETIRED_AT)))
        val terminal = unsigned.copy(phase = LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED,
            unsignedDisposition = disposition, retirementEndorsement = dataOctets(ByteArray(64) { 0x33 }, 64))
        val rootBody = "synthetic-root-document-carrier".encodeToByteArray()
        val rootSignature = ByteArray(64) { 0x55 }
        val root = try { SelfOwnedSignedDocumentV1(SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT, rootBody, rootSignature) }
            finally { rootBody.fill(0); rootSignature.fill(0) }
        return LocalDevicePairingAction(carrierOwner, terminal) to LocalDevicePairingUnsignedRetirement.fromNative(terminal, root)
    }

    fun latePhase(original: ContinuationOriginal, phase: LocalDevicePairingPhase): LocalDevicePairingAction {
        val proved = original.proved.record
        val head = proved.body.preparedHead
        val actionDigest = dataDigest(NativeDevicePairingWireV1.actionMessage(proved))
        val consent = NativeDevicePairingConsentV1(carrierId(0x6004 + original.index * 8), actionDigest,
            LocalAuthenticationObservation(head.observation.issuerContextId,
                MonotonicMoment(head.observation.moment.bootId, head.observation.moment.elapsedMillis + 1)),
            0, 0, 0, 0, head.deadlineElapsedMillis)
        val consentRecord = proved.copy(phase = LocalDevicePairingPhase.HEAD_CONSENT_INTENT, consent = consent)
        val record = when (phase) {
            LocalDevicePairingPhase.HEAD_CONSENT_INTENT -> consentRecord
            LocalDevicePairingPhase.SIGNING_INTENT, LocalDevicePairingPhase.APPROVED -> consentRecord.copy(
                phase = phase, approvedHead = NativeDevicePairingApprovedHeadV1(0, 0, 0, 0, head.deadlineElapsedMillis),
                rootEndorsement = dataOctets(if (phase == LocalDevicePairingPhase.APPROVED) ByteArray(64) { 0x66 } else ByteArray(0)))
            LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT -> retirement(original).first.record.copy(
                phase = phase, retirementEndorsement = dataOctets(ByteArray(0)))
            else -> error("Unsupported controlled late phase")
        }
        return LocalDevicePairingAction(carrierOwner, record)
    }

    private fun identity(alternate: Boolean): LocalDevicePairingSdkIdentityV2 {
        val keyId = if (alternate) carrierId(0x7101) else targetKeyId
        val deviceId = if (alternate) carrierId(0x7102) else targetDeviceId
        val nativePoint = point(if (alternate) 0x40 else 0)
        val target = try { LocalDevicePairingTargetDescriptor.capture(
            LocalDeviceAdmission(scope, deviceId, Revision(0), true, keyId.value, sha256(nativePoint).value,
                capabilities), keyId, nativePoint) } finally { nativePoint.fill(0) }
        val sdkText = if (alternate) "b".repeat(51) + "a" else "a".repeat(52)
        return LocalDevicePairingSdkIdentityV2.capture(
            if (alternate) carrierId(0x7103) else targetAcquisitionId,
            if (alternate) carrierId(0x7104) else targetPreparationId, target,
            SelfOwnedTargetDescriptorV1(if (alternate) "synthetic.pos.continuation_other" else "synthetic.pos.continuation_target",
                if (alternate) 12002 else 12001, carrierDigest(if (alternate) "other-apk" else "target-apk").value,
                carrierDigest(if (alternate) "other-android-id" else "target-android-id").value),
            AppId(appId), PeerId("p2id2-$sdkText"), PeerFingerprint("p2f1-$sdkText"),
            "p2pkit:v2:p2a1-$sdkText:p2f1-$sdkText",
        )
    }

    private fun createBinding(): SelfOwnedNativeBindingV1 {
        val body = SelfOwnedNativeInstallationWireV1.encodeBindingBody(SelfOwnedNativeBindingBodyV1(
            coordinates, hostApplication, rootDocumentDigest.value, delegationDigest.value, preparationDigest.value,
            carrierDigest("completed-native-intent").value, dataOctets(point(0x80), 65),
            SelfOwnedKeyProtectionV1.OS_ISOLATED, SelfOwnedNativeSecurityV1.SOFTWARE,
            SelfOwnedNativeSecurityV1.SOFTWARE, SelfOwnedNativeSecurityV1.SOFTWARE))
        val signature = ByteArray(64) { 0x33 }
        return try { SelfOwnedNativeBindingV1(body, signature) } finally { body.fill(0); signature.fill(0) }
    }

    companion object {
        const val OLD_PREPARED_AT = 120_000L
        const val OLD_DEADLINE = 420_000L
        const val NEW_PREPARED_AT = 510_000L
        const val NEW_DEADLINE = 810_000L
        const val RETIRED_AT = 450_000L
        const val PREPARATION_DEADLINE = 300_000L
    }
}

internal class ContinuationOriginal(val identity: LocalDevicePairingSdkIdentityV2,
    val prepared: LocalDevicePairingAction, val proved: LocalDevicePairingAction,
    val possession: LocalDevicePairingPossession, val index: Int) {
    var current: LocalDevicePairingAction = prepared
    var retirement: LocalDevicePairingUnsignedRetirement? = null
    val challenge get() = prepared.challenge
    val requestId get() = prepared.requested.request.requestId
    override fun toString() = "ContinuationOriginal([CONTROLLED DATA])"
}

/** Original binding reads only; every native-history/new-authority operation refuses. */
private class ContinuationBindingDataContext(override val materialAtOpen: SelfOwnedNativeMaterialView,
    private val binding: SelfOwnedNativeBindingV1) : SelfOwnedNativeContextController {
        override val compositionProfile: com.ukkera.pos.security.LocalCompositionSdkProfileV1
            get() = com.ukkera.pos.security.LocalMasterSdkProfileV1
    private var readable = true
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

internal fun carrierId(value: Int) = DomainId("f1000000-0000-4000-8000-" + value.toString(16).padStart(12, '0'))
internal fun carrierDigest(label: String): PayloadDigest = dataDigest(("controlled-unsigned-continuation:$label").encodeToByteArray())
private fun point(offset: Int) = ByteArray(65) { if (it == 0) 4.toByte() else (offset + it).toByte() }
private fun dataDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
private fun dataOctets(bytes: ByteArray, maximum: Int = SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES): SelfOwnedOctetsV1 =
    try { SelfOwnedOctetsV1(bytes, maximum) } finally { bytes.fill(0) }
