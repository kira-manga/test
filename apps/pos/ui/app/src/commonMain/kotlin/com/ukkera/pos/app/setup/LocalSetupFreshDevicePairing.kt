package com.ukkera.pos.app.setup

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPhase
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingProfileV1
import com.ukkera.pos.security.LocalDevicePairingProfileV2
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalDevicePairingMasterTransfer
import com.ukkera.pos.security.LocalMasterSdkOriginal
import com.ukkera.pos.security.LocalDevicePairingTargetReference
import com.ukkera.pos.types.DomainId

/** The full canonical binary payload travels through a bounded document, without text conversion. */
public enum class LocalSetupPairingDocumentKind(public val maximumBytes: Int) {
    SDK_IDENTITY(LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES),
    CHALLENGE(LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES),
    POSSESSION(LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES),
    UNSIGNED_RETIREMENT(LocalDevicePairingProfileV1.MAXIMUM_RETIREMENT_BYTES),
    APPROVED_MASTER(LocalDevicePairingMasterTransfer.MAXIMUM_BYTES),
}

public enum class LocalSetupPairingDocumentOutcome { IMPORTED, EXPORTED, CANCELLED, UNAVAILABLE }

public data class LocalSetupPairingDocumentView(public val kind: LocalSetupPairingDocumentKind,
    public val outcome: LocalSetupPairingDocumentOutcome, public val byteCount: Int? = null) {
    init { byteCount?.let { require(it in 1..kind.maximumBytes) } }
}

public enum class LocalSetupFreshDevicePairingRole { FRESH_TARGET, ORIGINAL_MASTER }

/** Durable application request identity only; every selected original still needs native review. */
public data class LocalSetupFreshDeviceMasterOriginalView(public val originalRequestId: DomainId,
    public val originalActionId: DomainId?, public val deviceId: DomainId, public val keyId: DomainId)

/** Presentation only. A saved label or an imported proof never confirms a native phase. */
public enum class LocalSetupFreshDevicePairingStage {
    TARGET_INPUT, TARGET_INPUT_REVIEW, TARGET_PREPARATION_UNRESOLVED, TARGET_KEY_READY,
    SDK_ACQUISITION_UNRESOLVED, SDK_IDENTITY_READY, TARGET_CHALLENGE_REVIEW,
    TARGET_RESPONSE_UNRESOLVED, TARGET_POSSESSION_READY,
    MASTER_IDENTITY_IMPORT, MASTER_IDENTITY_REVIEW, MASTER_PREPARATION_UNRESOLVED,
    MASTER_CHALLENGE_READY, MASTER_POSSESSION_REVIEW, MASTER_PROOF_UNRESOLVED,
    TARGET_PROVED, RETAINED_ADMISSION_HANDOFF, ORIGINAL_UNAVAILABLE,
    MASTER_UNSIGNED_RETIREMENT_REVIEW, MASTER_UNSIGNED_RETIREMENT_UNRESOLVED, MASTER_UNSIGNED_RETIRED,
    TARGET_RETIREMENT_REVIEW, TARGET_CONTINUATION_UNRESOLVED, TARGET_CONTINUATION_READY,
    TARGET_MASTER_REVIEW, TARGET_MASTER_UNRESOLVED, TARGET_MASTER_VERIFIED,
}

/**
 * Exact descriptive inputs for the new physical target. Name is private local acquisition
 * metadata: the existing I/C/P wire formats do not carry it or protect it as a Master name.
 * The capability codes describe a request; they never create permissions or an admission.
 */
public class LocalSetupFreshDeviceTargetInput(
    public val scope: BranchScope,
    capabilities: Set<String>,
    public val appId: String,
    public val deviceName: String,
) {
    private val describedCapabilities = capabilities.toList().sorted()
    public val capabilities: Set<String> get() = describedCapabilities.toSet()
    init {
        require(listOf(scope.organizationId, scope.companyId, scope.branchId).distinct().size == 3)
        require(describedCapabilities.size <= 64 && describedCapabilities.distinct().size == describedCapabilities.size)
        require(describedCapabilities.all { code -> code.length in 1..64 &&
            code.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" } })
        LocalDevicePairingProfileV2.validateAcquisitionText(appId)
        LocalDevicePairingProfileV2.validateAcquisitionText(deviceName)
    }
    public fun matches(other: LocalSetupFreshDeviceTargetInput): Boolean = scope == other.scope &&
        describedCapabilities == other.describedCapabilities && appId == other.appId && deviceName == other.deviceName
    /** New-effect eligibility only; historical inputs remain readable without being relabeled. */
    public val matchesBranchRpcNamespace: Boolean get() = matchesRpcNamespace(LocalMasterSdkProfileV1)
    public fun matchesRpcNamespace(profile: com.ukkera.pos.security.LocalCompositionSdkProfileV1): Boolean =
        appId == profile.appId(scope).value
    override fun toString(): String = "LocalSetupFreshDeviceTargetInput([REDACTED])"
}

/** Complete immutable public review DATA. No native owner, Kit, signer or approval is published. */
public data class LocalSetupFreshDevicePairingView(
    public val reviewId: DomainId,
    public val role: LocalSetupFreshDevicePairingRole,
    public val stage: LocalSetupFreshDevicePairingStage,
    public val targetInput: LocalSetupFreshDeviceTargetInput? = null,
    public val originalIntentId: DomainId? = null,
    public val originalRequestId: DomainId? = null,
    public val targetReference: LocalDevicePairingTargetReference? = null,
    public val identity: LocalDevicePairingSdkIdentityV2? = null,
    public val challenge: LocalDevicePairingChallenge? = null,
    public val possession: LocalDevicePairingPossession? = null,
    public val nativePhase: LocalDevicePairingPhase? = null,
    public val preparationAttempted: Boolean = false,
    public val sdkAttempted: Boolean = false,
    public val responseAttempted: Boolean = false,
    public val proofAttempted: Boolean = false,
    public val masterOriginals: List<LocalSetupFreshDeviceMasterOriginalView> = emptyList(),
    public val blockedRequestId: DomainId? = null,
    public val document: LocalSetupPairingDocumentView? = null,
    public val retirement: LocalDevicePairingUnsignedRetirement? = null,
    public val retirementAttempted: Boolean = false,
    public val continuationAttempted: Boolean = false,
    public val responseCycle: Int = 0,
    public val masterTransfer: LocalDevicePairingMasterTransfer? = null,
    /** Native verified-readback identity only, not a session or business grant. */
    public val verifiedMaster: LocalMasterSdkOriginal? = null,
) {
    override fun toString(): String = "LocalSetupFreshDevicePairingView([REDACTED])"
}
