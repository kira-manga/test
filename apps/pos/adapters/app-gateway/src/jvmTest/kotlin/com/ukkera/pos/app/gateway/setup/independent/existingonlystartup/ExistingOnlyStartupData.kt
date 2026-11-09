package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalBranchInstallationDefinitionV2
import com.ukkera.pos.security.LocalBranchInstallationGenesisCoreV2
import com.ukkera.pos.security.LocalBranchInstallationReceiptV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalBranchInstallationWireV2
import com.ukkera.pos.security.LocalCompanyAuthorityView
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalInstallationHumanReferenceV2
import com.ukkera.pos.security.LocalInstallationSeedDigestsV2
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.OriginalAllocationCommitmentV2
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedNativeBindingBodyV1
import com.ukkera.pos.security.SelfOwnedNativeBindingV1
import com.ukkera.pos.security.SelfOwnedNativeInstallationWireV1
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeSecurityV1
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.SelfOwnedStorageKeySecurity
import com.ukkera.pos.security.SelfOwnedTargetDescriptorV1
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Synthetic DATA construction for the controlled coordinator/record qualification.
 * These values are logical fixture identities and opaque signed-shape carriers only.
 * They are not native keys, native custody, Room state, an installed app or authority.
 */
internal object ExistingOnlyStartupData {
    val canonical: List<String> = ExistingOnlyStartupFixtures.canonical
    val foreign: List<String> = ExistingOnlyStartupFixtures.foreign

    private val handles: Map<String, DomainId> = listOf(
        "company-root", "company-root-key", "head-authority", "head-enrollment", "head-installation-key",
        "founding-action", "installation-request", "installation-attempt", "issuer-context", "monotonic-boot",
    ).mapIndexed { index, label ->
        label to DomainId("30000000-0000-4000-8000-" + (index + 1).toString().padStart(12, '0'))
    }.toMap()

    private fun id(label: String): DomainId = handles.getValue(label)

    val canonicalCoordinates: SelfOwnedAllocationCoordinatesV1 = ExistingOnlyStartupFixtures.coordinates(canonical)

    val targetDescriptor: SelfOwnedTargetDescriptorV1 = SelfOwnedTargetDescriptorV1(
        "synthetic.pos.existing_only", 12000, digest("target-apk-signer"), digest("target-android-id"),
    )

    val company: LocalCompanyAuthorityView = LocalCompanyAuthorityView(
        rootId = id("company-root"),
        organizationId = DomainId(canonical[1]),
        companyId = DomainId(canonical[2]),
        companyName = "Synthetic existing-only company",
        rootFingerprint = digest("company-root-fingerprint"),
        protection = SelfOwnedKeyProtectionV1.OS_ISOLATED,
        headAuthorityId = id("head-authority"),
        headEnrollmentId = id("head-enrollment"),
        headName = "Synthetic named head",
        headLogin = "fixture-head",
        headAccountRevision = Revision(0),
        headCredentialRevision = Revision(0),
        headEnabled = true,
        policyIssuanceEnabled = false,
        installationIssuanceEnabled = false,
        foundingBodyDigest = payloadDigest("founding-body"),
    )

    private val allocation: OriginalAllocationCommitmentV2 = run {
        val point = publicPoint(0x20)
        try {
            OriginalAllocationCommitmentV2(
                canonicalCoordinates,
                bindingDigest(canonicalCoordinates),
                payloadDigest("p1-root"),
                payloadDigest("p1-delegation"),
                payloadDigest("p1-preparation"),
                point,
            )
        } finally { point.fill(0) }
    }

    private val definition: LocalBranchInstallationSnapshotV2 = run {
        val humanPoint = publicPoint(0x60)
        val human = try {
            LocalInstallationHumanReferenceV2(
                id("company-root"), id("company-root-key"), payloadDigest("p1-root"),
                id("founding-action"), payloadDigest("founding-body"),
                id("head-authority"), id("head-enrollment"), id("head-installation-key"), humanPoint,
            )
        } finally { humanPoint.fill(0) }
        val policy = ActivatedLocalPolicy(
            scope = canonicalCoordinates.scope,
            actorId = canonicalCoordinates.firstActorId,
            revision = Revision(0),
            grants = emptyList(),
            sessionPolicy = LocalSessionPolicy(),
            throttlePolicy = ThrottlePolicy(),
        )
        val account = LocalAccountProvisioning(
            actorId = canonicalCoordinates.firstActorId,
            login = LocalLoginIdentifier("fixture-head"),
            policy = policy,
            tokenPolicy = LocalTokenPolicy(1_000, 2_000),
            admittedDeviceIds = setOf(canonicalCoordinates.deviceId),
        )
        val admission = LocalDeviceAdmission(
            scope = canonicalCoordinates.scope,
            deviceId = canonicalCoordinates.deviceId,
            revision = Revision(0),
            admitted = true,
            publicKeyIdentifier = "fixture-target-key",
            publicFingerprint = null,
            capabilities = emptySet(),
        )
        val value = LocalBranchInstallationDefinitionV2(
            allocation = allocation,
            originalRequestId = id("installation-request"),
            authorityEpoch = Revision(0),
            human = human,
            firstAccount = account,
            masterAdmission = admission,
        )
        LocalBranchInstallationWireV2.snapshotDefinition(value)
    }

    val receipt: LocalBranchInstallationReceiptV2 = run {
        val seeds = LocalInstallationSeedDigestsV2(
            payloadDigest("seed-account"), payloadDigest("seed-policy"), payloadDigest("seed-configuration"),
            payloadDigest("seed-admission"), payloadDigest("seed-account-throttle"),
            payloadDigest("seed-device-throttle"), payloadDigest("seed-recovery"),
        )
        val core = LocalBranchInstallationGenesisCoreV2(
            allocation = allocation,
            definitionDigest = definition.definitionDigest,
            originalRequestId = id("installation-request"),
            issuedAttemptId = id("installation-attempt"),
            attemptSequence = 1,
            authorityEpoch = Revision(0),
            humanApprovalDigest = payloadDigest("installation-human-approval"),
            targetProofDigest = payloadDigest("installation-target-proof"),
            issuerContextId = id("issuer-context"),
            monotonicBootId = id("monotonic-boot"),
            installedAtElapsedMillis = 0,
            installedAtOsUtcEpochMillis = 1_760_000_000_000,
            installationCustodyEvidenceDigest = payloadDigest("installation-custody"),
            headAuthorityId = id("head-authority"),
            headEnrollmentId = id("head-enrollment"),
            foundingBodyDigest = payloadDigest("founding-body"),
            seeds = seeds,
        )
        LocalBranchInstallationReceiptV2(core)
    }

    fun setup(definitionPresent: Boolean, receiptPresent: Boolean): LocalOriginalInstallationSetup =
        LocalOriginalInstallationSetup(company, if (definitionPresent) definition else null,
            if (receiptPresent) receipt else null)

    fun material(coordinates: SelfOwnedAllocationCoordinatesV1): SelfOwnedNativeMaterialView =
        SelfOwnedNativeMaterialView(
            coordinates = coordinates,
            target = targetDescriptor,
            branchName = "Synthetic existing-only branch",
            protection = SelfOwnedKeyProtectionV1.OS_ISOLATED,
            targetSigningFingerprint = digest("target-signing-fingerprint"),
            originalNativeBindingDigest = bindingDigest(coordinates).value,
            storageKeySecurity = SelfOwnedStorageKeySecurity.SOFTWARE,
            newCeremoniesAllowed = false,
        )

    fun binding(coordinates: SelfOwnedAllocationCoordinatesV1): SelfOwnedNativeBindingV1 {
        val point = publicPoint(0x20)
        val body = try {
            SelfOwnedNativeInstallationWireV1.encodeBindingBody(SelfOwnedNativeBindingBodyV1(
                coordinates = coordinates,
                target = targetDescriptor,
                rootDocumentDigest = digest("p1-root"),
                delegationDigest = digest("p1-delegation"),
                preparationDigest = digest("p1-preparation"),
                completedIntentDigest = digest("completed-native-intent"),
                targetPublicKey = SelfOwnedOctetsV1(point, 65),
                protection = SelfOwnedKeyProtectionV1.OS_ISOLATED,
                signingSecurity = SelfOwnedNativeSecurityV1.SOFTWARE,
                storageSecurity = SelfOwnedNativeSecurityV1.SOFTWARE,
                pepperSecurity = SelfOwnedNativeSecurityV1.SOFTWARE,
            ))
        } finally { point.fill(0) }
        val signature = ByteArray(64) { 0x33 }
        return try { SelfOwnedNativeBindingV1(body, signature) } finally { body.fill(0); signature.fill(0) }
    }

    fun coordinatesWith(source: String, foreignAt: Int? = null): SelfOwnedAllocationCoordinatesV1 {
        val values = ExistingOnlyStartupFixtures.coordinateValues(source).toMutableList()
        if (foreignAt != null) {
            require(foreignAt in 0..11) { "Foreign coordinate index is out of range" }
            values[foreignAt] = foreign[foreignAt]
        }
        return ExistingOnlyStartupFixtures.coordinates(values)
    }

    private fun bindingDigest(coordinates: SelfOwnedAllocationCoordinatesV1): PayloadDigest {
        val bytes = binding(coordinates).copyWireBytes()
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }

    private fun digest(label: String): String = sha256("existing-only-startup:$label".encodeToByteArray()).value

    private fun payloadDigest(label: String): PayloadDigest = sha256("existing-only-startup:$label".encodeToByteArray())

    private fun publicPoint(offset: Int): ByteArray = ByteArray(65) { index ->
        if (index == 0) 4.toByte() else (offset + index).toByte()
    }
}
