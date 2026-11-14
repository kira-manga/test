package com.ukkera.pos.security.independent.masterboundpairing

import com.ukkera.pos.security.*
import dev.p2pkit.core.AppId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wiring of bounded L1 sub-assertions from the unchanged Master-bound v1.0.0 freeze.
 * MBP-02/05/09 require genuine native/application layers beyond these DATA checks;
 * their whole cases and unavailable variants remain NOT RUN. Captured common DATA
 * is never authenticated record6, C1/P1, A1/R1/E1, qualified readback or authority.
 */
class MasterBoundDevicePairingFrozenDataSupportTest {
    @Test
    fun mbp02_frozenScopeNamespaceAndInitialNameAreExactData() {
        val scope = MasterBoundPairingDataFixture.scope
        val actual = LocalMasterSdkProfileV1.appId(scope).value
        assertEquals(FrozenMasterBoundPairingV1.BRANCH_APP_ID, actual)
        assertEquals(FrozenMasterBoundPairingV1.BRANCH_APP_ID_UTF8_BYTES, actual.encodeToByteArray().size)
        assertEquals(FrozenMasterBoundPairingV1.INVALID_DEVICE_SUFFIXED_APP_ID_UTF8_BYTES,
            FrozenMasterBoundPairingV1.INVALID_DEVICE_SUFFIXED_APP_ID.encodeToByteArray().size)
        assertEquals(FrozenMasterBoundPairingV1.INITIAL_MASTER_NAME,
            LocalMasterSdkProfileV1.deviceName(MasterBoundPairingDataFixture.masterDevice))
        assertEquals(FrozenMasterBoundPairingV1.INITIAL_MASTER_NAME_UTF8_BYTES,
            LocalMasterSdkProfileV1.deviceName(MasterBoundPairingDataFixture.masterDevice).encodeToByteArray().size)
        val original = MasterBoundPairingDataFixture.master()
        val target = MasterBoundPairingDataFixture.sdk()
        assertEquals(actual, original.appId.value)
        assertEquals(actual, target.appId.value)
        assertFalse(original.peerId == target.peerId)
        assertFalse(original.fingerprint == target.fingerprint)
        assertFalse(original.pairingQr == target.pairingQr)
    }

    @Test
    fun mbp02_completeDescriptiveMasterRoundtripPreservesAllFieldsAndExactBytes() {
        val point = MasterBoundPairingDataFixture.masterPoint()
        val allocation = MasterBoundPairingDataFixture.allocation(point = point)
        val original = MasterBoundPairingDataFixture.master(allocation)
        val captured = original.copyWireBytes()
        val input = captured.copyOf()
        val decoded = LocalMasterSdkOriginalV1.decode(input)
        point.fill(0); input.fill(0)
        assertMaster(original, decoded)
        assertContentEquals(captured, original.copyWireBytes())
        assertContentEquals(captured, decoded.copyWireBytes())
        assertEquals(FrozenMasterBoundPairingV1.MASTER_ORIGINAL_MAX_BYTES, LocalMasterSdkProfileV1.MAXIMUM_ORIGINAL_BYTES)
        assertTrue(captured.size <= FrozenMasterBoundPairingV1.MASTER_ORIGINAL_MAX_BYTES)
        val exposed = decoded.copyWireBytes()
        exposed.fill(0)
        val exposedPoint = decoded.allocation.copyTargetPublicKey()
        exposedPoint.fill(0)
        assertMaster(original, decoded)
        assertContentEquals(captured, decoded.copyWireBytes())
    }

    @Test
    fun mbp02_transportComparisonUsesEveryFieldAndDoesNotCompareName() {
        val original = MasterBoundPairingDataFixture.master()
        assertTrue(original.matchesSdkTransportIdentity(original.appId, original.peerId,
            original.fingerprint, original.pairingQr))
        assertFalse(original.matchesSdkTransportIdentity(AppId(FrozenMasterBoundPairingV1.INVALID_DEVICE_SUFFIXED_APP_ID),
            original.peerId, original.fingerprint, original.pairingQr))
        assertFalse(original.matchesSdkTransportIdentity(original.appId, MasterBoundPairingDataFixture.targetPeer,
            original.fingerprint, original.pairingQr))
        assertFalse(original.matchesSdkTransportIdentity(original.appId, original.peerId,
            MasterBoundPairingDataFixture.targetFingerprint, original.pairingQr))
        assertFalse(original.matchesSdkTransportIdentity(original.appId, original.peerId,
            original.fingerprint, MasterBoundPairingDataFixture.targetQr))
        val changedName = MasterBoundPairingDataFixture.master(name = original.exactDeviceName + " changed")
        assertTrue(changedName.matchesSdkTransportIdentity(original.appId, original.peerId,
            original.fingerprint, original.pairingQr))
        assertFalse(original.copyWireBytes().contentEquals(changedName.copyWireBytes()))
        assertFalse(original.exactDeviceName == changedName.exactDeviceName)
        assertEquals(FrozenMasterBoundPairingV1.INITIAL_MASTER_NAME, original.exactDeviceName)
        // Matching four transport fields is not matching the complete original, custody or trust.
    }

    @Test
    fun mbp02_masterDataRefusesWrongNamespaceAndEveryReservedAcquisitionCollision() {
        val allocation = MasterBoundPairingDataFixture.allocation()
        val original = MasterBoundPairingDataFixture.master(allocation)
        val saved = original.copyWireBytes()
        val reserved = allocationIdentifierFacts(allocation.coordinates)
        assertEquals(FrozenMasterBoundPairingV1.RESERVED_ALLOCATION_IDENTIFIER_COUNT, reserved.size)
        assertEquals(FrozenMasterBoundPairingV1.RESERVED_ALLOCATION_IDENTIFIER_COUNT, reserved.distinct().size)
        for (id in reserved) {
            assertFailsWith<IllegalArgumentException>("Acquisition collides with reserved identifier $id") {
                MasterBoundPairingDataFixture.master(allocation, acquisitionId = id)
            }
        }
        val wrongNamespaces = listOf(
            FrozenMasterBoundPairingV1.INVALID_DEVICE_SUFFIXED_APP_ID,
            FrozenMasterBoundPairingV1.BRANCH_APP_ID.replace(FrozenMasterBoundPairingV1.ORGANIZATION_UUID,
                FrozenMasterBoundPairingV1.TARGET_DEVICE_UUID),
            FrozenMasterBoundPairingV1.BRANCH_APP_ID.replace(FrozenMasterBoundPairingV1.COMPANY_UUID,
                FrozenMasterBoundPairingV1.TARGET_DEVICE_UUID),
            FrozenMasterBoundPairingV1.BRANCH_APP_ID.replace(FrozenMasterBoundPairingV1.BRANCH_UUID,
                FrozenMasterBoundPairingV1.TARGET_DEVICE_UUID),
        )
        for (appId in wrongNamespaces) {
            assertFailsWith<IllegalArgumentException> { MasterBoundPairingDataFixture.master(allocation, appId = AppId(appId)) }
        }
        assertFailsWith<IllegalArgumentException> {
            MasterBoundPairingDataFixture.master(allocation, name = "x".repeat(FrozenMasterBoundPairingV1.APP_ID_AND_NAME_MAX_UTF8_BYTES + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            LocalMasterSdkOriginalV1.decode(ByteArray(FrozenMasterBoundPairingV1.MASTER_ORIGINAL_MAX_BYTES + 1))
        }
        assertContentEquals(saved, original.copyWireBytes())
    }

    @Test
    fun mbp02_mbp05_v3DefinitionPreservesCompleteTargetMasterAndAdmissionData() {
        val serviceAdmission = byteArrayOf(1, 2, 3)
        val original = MasterBoundPairingDataFixture.definition(serviceAdmission = serviceAdmission)
        val saved = original.copyWireBytes()
        val input = saved.copyOf()
        val decoded = LocalDevicePairingDefinition.decode(input)
        serviceAdmission.fill(0); input.fill(0)
        assertDefinition(original, decoded)
        assertContentEquals(byteArrayOf(1, 2, 3), decoded.copyCanonicalServiceAdmission())
        assertContentEquals(saved, original.copyWireBytes())
        assertContentEquals(saved, decoded.copyWireBytes())
        val exposed = decoded.copyCanonicalServiceAdmission()
        exposed.fill(0)
        assertDefinition(original, decoded)
    }

    @Test
    fun mbp05_v3DefinitionRefusesMasterTargetNamespaceAndIdentifierCollisions() {
        val master = MasterBoundPairingDataFixture.master()
        val target = MasterBoundPairingDataFixture.target()
        val sdk = MasterBoundPairingDataFixture.sdk(target)
        val original = MasterBoundPairingDataFixture.definition(master, target, sdk)
        val saved = original.copyWireBytes()
        assertFailsWith<IllegalArgumentException> {
            MasterBoundPairingDataFixture.definition(master,
                MasterBoundPairingDataFixture.target(MasterBoundPairingDataFixture.masterDevice))
        }
        assertFailsWith<IllegalArgumentException> {
            MasterBoundPairingDataFixture.definition(master, target,
                MasterBoundPairingDataFixture.sdk(target, AppId(FrozenMasterBoundPairingV1.INVALID_DEVICE_SUFFIXED_APP_ID)))
        }
        assertFailsWith<IllegalArgumentException> {
            MasterBoundPairingDataFixture.definition(master, target,
                MasterBoundPairingDataFixture.sdk(target, peerId = master.peerId))
        }
        assertFailsWith<IllegalArgumentException> {
            MasterBoundPairingDataFixture.definition(master, target,
                MasterBoundPairingDataFixture.sdk(target, fingerprint = master.fingerprint, qr = master.pairingQr))
        }
        val targetReferences = listOf(original.request.requestId, target.admission.deviceId,
            target.keyId, sdk.originalAcquisitionId, sdk.originalPreparationId)
        for (reference in targetReferences) {
            val changedMaster = MasterBoundPairingDataFixture.master(acquisitionId = reference)
            assertFailsWith<IllegalArgumentException> {
                MasterBoundPairingDataFixture.definition(changedMaster, target, sdk)
            }
        }
        assertContentEquals(saved, original.copyWireBytes())
    }

    @Test
    fun mbp02_mbp05_challengeRoundtripPreservesCompleteDefinitionOriginHeadAndNonceData() {
        val body = MasterBoundPairingDataFixture.body()
        val original = LocalDevicePairingChallenge.fromNative(body)
        val saved = original.copyWireBytes()
        val input = saved.copyOf()
        val decoded = LocalDevicePairingChallenge.decode(input)
        input.fill(0)
        assertChallenge(body, original, decoded)
        assertContentEquals(saved, original.copyWireBytes())
        assertContentEquals(saved, decoded.copyWireBytes())
        val nonce = decoded.reviewContext.copyNonceBytes()
        nonce.fill(0)
        assertChallenge(body, original, decoded)
        val withoutPrevious = MasterBoundPairingDataFixture.body(previousUnsignedRetirementDigest = null)
        val withoutPreviousOriginal = LocalDevicePairingChallenge.fromNative(withoutPrevious)
        assertChallenge(withoutPrevious, withoutPreviousOriginal,
            LocalDevicePairingChallenge.decode(withoutPreviousOriginal.copyWireBytes()))
    }

    @Test
    fun mbp05_challengeDataRefusesEachChangedAllocationIdentifierAndCommitment() {
        val origin = MasterBoundPairingDataFixture.origin()
        val original = LocalDevicePairingChallenge.fromNative(MasterBoundPairingDataFixture.body(origin = origin))
        val saved = original.copyWireBytes()
        val originalSdk = assertNotNull(original.requested.sdkIdentity)
        for (axis in AllocationIdentifierAxis.entries) {
            val changedAllocation = MasterBoundPairingDataFixture.allocation(MasterBoundPairingDataFixture.coordinates(axis))
            val appId = if (axis in listOf(AllocationIdentifierAxis.ORGANIZATION,
                    AllocationIdentifierAxis.COMPANY, AllocationIdentifierAxis.BRANCH)) {
                AppId("ukkera-pos.rpc.branch.v1:${changedAllocation.coordinates.scope.organizationId.value}:" +
                    "${changedAllocation.coordinates.scope.companyId.value}:${changedAllocation.coordinates.scope.branchId.value}")
            } else originalSdk.appId
            val changedMaster = MasterBoundPairingDataFixture.master(changedAllocation, appId = appId)
            assertFailsWith<IllegalArgumentException>("Changed original allocation identifier: $axis") {
                val definition = MasterBoundPairingDataFixture.definition(master = changedMaster)
                MasterBoundPairingDataFixture.body(definition, origin)
            }
        }
        val changed = MasterBoundPairingDataFixture.paddingDigest('f')
        val changedAllocations = listOf(
            MasterBoundPairingDataFixture.allocation(bindingDigest = changed),
            MasterBoundPairingDataFixture.allocation(rootDigest = changed),
            MasterBoundPairingDataFixture.allocation(delegationDigest = changed),
            MasterBoundPairingDataFixture.allocation(preparationDigest = changed),
            MasterBoundPairingDataFixture.allocation(point = MasterBoundPairingDataFixture.targetPoint()),
        )
        for (allocation in changedAllocations) {
            val definition = MasterBoundPairingDataFixture.definition(master = MasterBoundPairingDataFixture.master(allocation))
            assertFailsWith<IllegalArgumentException> { MasterBoundPairingDataFixture.body(definition, origin) }
        }
        assertContentEquals(saved, original.copyWireBytes())
    }

    @Test
    fun mbp09_emptyMalformedAndTotalOverBoundTransferDataRefuse() {
        // Cmax/Rmax are public input caps used to instantiate the frozen equation,
        // not invented fixed component expectations or a manufactured valid E1.
        val bound = LocalDevicePairingMasterTransfer.MAXIMUM_CERTIFICATE_BYTES.toLong() +
            SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES +
            FrozenMasterBoundPairingV1.TRANSFER_FRAMING_ALLOWANCE_BYTES
        assertEquals(bound, LocalDevicePairingMasterTransfer.MAXIMUM_BYTES.toLong())
        assertTrue(bound < Int.MAX_VALUE)
        val malformed = listOf(ByteArray(0), byteArrayOf(0), byteArrayOf(0, 0, 0),
            "not-a-master-bound-pairing-transfer".encodeToByteArray())
        for (input in malformed) {
            val saved = input.copyOf()
            assertFailsWith<IllegalArgumentException> { LocalDevicePairingMasterTransfer.decode(input) }
            assertContentEquals(saved, input)
        }
        val overBound = ByteArray((bound + 1).toInt())
        assertFailsWith<IllegalArgumentException> { LocalDevicePairingMasterTransfer.decode(overBound) }
        // No valid transfer/roundtrip, component-bound, native-signature or no-effect
        // result is inferred from these structural failures. A size at B is not validity.
    }
}

private fun assertMaster(expected: LocalMasterSdkOriginalV1, actual: LocalMasterSdkOriginalV1) {
    assertEquals(expected.originalAcquisitionId, actual.originalAcquisitionId)
    assertEquals(allocationIdentifierFacts(expected.allocation.coordinates), allocationIdentifierFacts(actual.allocation.coordinates))
    assertEquals(expected.allocation.originalNativeBindingDigest, actual.allocation.originalNativeBindingDigest)
    assertEquals(expected.allocation.p1RootDocumentDigest, actual.allocation.p1RootDocumentDigest)
    assertEquals(expected.allocation.p1DelegationDigest, actual.allocation.p1DelegationDigest)
    assertEquals(expected.allocation.p1PreparationDigest, actual.allocation.p1PreparationDigest)
    assertContentEquals(expected.allocation.copyTargetPublicKey(), actual.allocation.copyTargetPublicKey())
    assertEquals(expected.allocation.digest, actual.allocation.digest)
    assertEquals(expected.allocation.originalTargetKeyDigest, actual.allocation.originalTargetKeyDigest)
    assertApplication(expected.nativeApplication, actual.nativeApplication)
    assertEquals(expected.appId, actual.appId); assertEquals(expected.peerId, actual.peerId)
    assertEquals(expected.fingerprint, actual.fingerprint); assertEquals(expected.pairingQr, actual.pairingQr)
    assertEquals(expected.exactDeviceName, actual.exactDeviceName)
    assertEquals(expected.descriptorDigest, actual.descriptorDigest)
    assertContentEquals(expected.copyWireBytes(), actual.copyWireBytes())
}

private fun assertApplication(expected: SelfOwnedTargetDescriptorV1, actual: SelfOwnedTargetDescriptorV1) {
    assertEquals(expected.applicationId, actual.applicationId); assertEquals(expected.applicationUid, actual.applicationUid)
    assertEquals(expected.apkSignerSha256, actual.apkSignerSha256)
    assertEquals(expected.androidIdCommitment, actual.androidIdCommitment)
}

private fun assertAdmission(expected: LocalDeviceAdmission, actual: LocalDeviceAdmission) {
    assertEquals(expected.scope, actual.scope); assertEquals(expected.deviceId, actual.deviceId)
    assertEquals(expected.revision, actual.revision); assertEquals(expected.admitted, actual.admitted)
    assertEquals(expected.publicKeyIdentifier, actual.publicKeyIdentifier)
    assertEquals(expected.publicFingerprint, actual.publicFingerprint)
    assertEquals(expected.capabilities, actual.capabilities)
}

private fun assertDefinition(expected: LocalDevicePairingDefinition, actual: LocalDevicePairingDefinition) {
    assertEquals(expected.request, actual.request)
    assertAdmission(expected.target.admission, actual.target.admission)
    assertEquals(expected.target.keyId, actual.target.keyId)
    assertEquals(expected.target.descriptorDigest, actual.target.descriptorDigest)
    assertContentEquals(expected.target.copyPublicPoint(), actual.target.copyPublicPoint())
    assertContentEquals(expected.target.copyWireBytes(), actual.target.copyWireBytes())
    assertContentEquals(expected.copyCanonicalServiceAdmission(), actual.copyCanonicalServiceAdmission())
    assertEquals(expected.retainedAdmissions.size, actual.retainedAdmissions.size)
    expected.retainedAdmissions.zip(actual.retainedAdmissions).forEach { (left, right) -> assertAdmission(left, right) }
    val leftSdk = assertNotNull(expected.sdkIdentity); val rightSdk = assertNotNull(actual.sdkIdentity)
    assertEquals(leftSdk.originalAcquisitionId, rightSdk.originalAcquisitionId)
    assertEquals(leftSdk.originalPreparationId, rightSdk.originalPreparationId)
    assertContentEquals(leftSdk.target.copyWireBytes(), rightSdk.target.copyWireBytes())
    assertApplication(leftSdk.nativeApplication, rightSdk.nativeApplication)
    assertEquals(leftSdk.appId, rightSdk.appId); assertEquals(leftSdk.peerId, rightSdk.peerId)
    assertEquals(leftSdk.fingerprint, rightSdk.fingerprint); assertEquals(leftSdk.pairingQr, rightSdk.pairingQr)
    assertEquals(leftSdk.descriptorDigest, rightSdk.descriptorDigest)
    assertContentEquals(leftSdk.copyWireBytes(), rightSdk.copyWireBytes())
    // Harness wiring only (13 Nov 2026): the shared accessor is format-neutral; these fixtures are V1.
    assertMaster(assertIs<LocalMasterSdkOriginalV1>(expected.masterSdkOriginal), assertIs<LocalMasterSdkOriginalV1>(actual.masterSdkOriginal))
    assertEquals(expected.purpose, actual.purpose); assertEquals(expected.definitionDigest, actual.definitionDigest)
    assertContentEquals(expected.copyWireBytes(), actual.copyWireBytes())
}

private fun assertChallenge(body: NativeDevicePairingBodyV1, expected: LocalDevicePairingChallenge,
    actual: LocalDevicePairingChallenge) {
    assertEquals(body.originalActionId, actual.originalActionId)
    assertDefinition(body.requested(), actual.requested)
    assertEquals(body.origin.rootFingerprint, actual.rootFingerprint)
    assertEquals(body.origin.coordinates.deviceId, actual.cohostDeviceId)
    assertEquals(body.preparedHead.companyName, actual.companyName); assertEquals(body.preparedHead.headName, actual.headName)
    assertEquals(body.preparedHead.headLogin, actual.headLogin)
    assertEquals(expected.purpose, actual.purpose); assertEquals(expected.challengeDigest, actual.challengeDigest)
    assertContentEquals(expected.copyWireBytes(), actual.copyWireBytes())
    val context = actual.reviewContext; val origin = body.origin; val head = body.preparedHead
    assertEquals(allocationIdentifierFacts(origin.coordinates), allocationIdentifierFacts(context.coordinates))
    assertEquals(origin.allocationDigest, context.allocationDigest); assertEquals(origin.bindingDigest, context.bindingDigest)
    assertEquals(origin.installationRequestId, context.installationRequestId)
    assertEquals(origin.installationDefinitionDigest, context.installationDefinitionDigest)
    assertEquals(origin.installationReceiptDigest, context.installationReceiptDigest)
    assertEquals(origin.authorityEpoch, context.authorityEpoch); assertEquals(origin.preparationParent, context.preparationParent)
    assertEquals(origin.preparationAnchorDigest, context.preparationAnchorDigest)
    assertEquals(origin.rootId, context.rootId); assertEquals(origin.rootKeyId, context.rootKeyId)
    assertEquals(origin.rootDocumentDigest, context.rootDocumentDigest); assertEquals(origin.rootFingerprint, context.rootFingerprint)
    assertEquals(origin.foundingActionId, context.foundingActionId); assertEquals(origin.headAuthorityId, context.headAuthorityId)
    assertEquals(origin.headEnrollmentId, context.headEnrollmentId)
    assertEquals(head.accountRevision, context.accountRevision); assertEquals(head.credentialRevision, context.credentialRevision)
    assertEquals(head.retainedRevision, context.retainedRevision); assertEquals(head.osBootCount, context.osBootCount)
    assertEquals(head.observation.issuerContextId, context.observationIssuerContextId)
    assertEquals(head.observation.moment.bootId, context.observationBootId)
    assertEquals(head.observation.moment.elapsedMillis, context.observationElapsedMillis)
    assertEquals(head.deadlineElapsedMillis, context.deadlineElapsedMillis)
    assertEquals(body.previousUnsignedRetirementDigest, context.previousUnsignedRetirementDigest)
    assertContentEquals(body.nonce.copyBytes(), context.copyNonceBytes())
    assertEquals(expected.reviewContext.nonceDigest, context.nonceDigest)
}
