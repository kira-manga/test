package com.ukkera.pos.security.independent.masterboundpairing

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId

/** Finite supporting DATA identifiers; never an unknown-label/native-identity fallback. */
internal enum class SupportingPairingId(val number: Int) {
    ALLOCATION(0x101), INSTALLATION(0x102), ASSIGNMENT(0x103), STORAGE_INSTANCE(0x104),
    STORAGE_KEY(0x105), PEPPER_KEY(0x106), MASTER_TARGET_KEY(0x107), FIRST_ACTOR(0x108),
    MASTER_ACQUISITION(0x109), TARGET_KEY(0x10a), TARGET_ACQUISITION(0x10b),
    TARGET_PREPARATION(0x10c), PAIRING_REQUEST(0x10d), PAIRING_ACTION(0x10e),
    ROOT(0x10f), ROOT_KEY(0x110), FOUNDING_ACTION(0x111), HEAD_AUTHORITY(0x112),
    HEAD_ENROLLMENT(0x113), INSTALLATION_REQUEST(0x114), OBSERVATION_ISSUER(0x115),
    BOOT(0x116), CHANGED_COORDINATE(0x117),
}

internal enum class AllocationIdentifierAxis {
    ALLOCATION, ORGANIZATION, COMPANY, BRANCH, INSTALLATION, ASSIGNMENT, DEVICE,
    STORAGE_INSTANCE, STORAGE_KEY, PEPPER_KEY, TARGET_SIGNING_KEY, FIRST_ACTOR,
}

/**
 * Descriptive common DATA only, used to wire the frozen exact-preservation and
 * structural-coherence sub-assertions. These are NOT the freeze's genuine originals.
 * Shape-only public points, digest padding, admission bytes and historical clock
 * fields establish no installation, signed admission, native record6, current
 * observation, key possession, Kit, custody, approval or operating authority.
 * No authenticated native record, signature, approved capability or verifier is made.
 */
internal object MasterBoundPairingDataFixture {
    fun id(value: SupportingPairingId): DomainId = DomainId(
        "00000000-0000-4000-8000-" + value.number.toString(16).padStart(12, '0'),
    )

    val scope: BranchScope = BranchScope(DomainId(FrozenMasterBoundPairingV1.ORGANIZATION_UUID),
        DomainId(FrozenMasterBoundPairingV1.COMPANY_UUID), DomainId(FrozenMasterBoundPairingV1.BRANCH_UUID))
    val masterDevice: DomainId = DomainId(FrozenMasterBoundPairingV1.MASTER_DEVICE_UUID)
    val targetDevice: DomainId = DomainId(FrozenMasterBoundPairingV1.TARGET_DEVICE_UUID)
    val application: SelfOwnedTargetDescriptorV1 = SelfOwnedTargetDescriptorV1(
        "synthetic.app", 12345, "a".repeat(64), "b".repeat(64),
    )

    // Already-declared synthetic SDK syntax carriers from native-sdk-association-v1.
    // Their text is input only: it does not bind AppId/PeerId/key or instantiate M1/P1.
    val masterPeer: PeerId = PeerId("p2id2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq")
    val masterFingerprint: PeerFingerprint = PeerFingerprint("p2f1-" + "a".repeat(52))
    val masterQr: String = "p2pkit:v2:p2a1-" + "a".repeat(52) + ":" + masterFingerprint.value
    val targetPeer: PeerId = PeerId("p2id2-" + "a".repeat(52))
    val targetFingerprint: PeerFingerprint = PeerFingerprint(
        "p2f1-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq",
    )
    val targetQr: String = "p2pkit:v2:p2a1-" + "a".repeat(52) + ":" + targetFingerprint.value

    fun masterPoint(): ByteArray = ByteArray(65).also { it[0] = 4 }
    fun targetPoint(): ByteArray = ByteArray(65) { if (it == 0) 4.toByte() else it.toByte() }
    fun paddingDigest(character: Char): PayloadDigest = PayloadDigest(character.toString().repeat(64))

    fun coordinates(changedAxis: AllocationIdentifierAxis? = null): SelfOwnedAllocationCoordinatesV1 {
        fun value(axis: AllocationIdentifierAxis, original: DomainId): DomainId =
            if (axis == changedAxis) id(SupportingPairingId.CHANGED_COORDINATE) else original
        return SelfOwnedAllocationCoordinatesV1(
            value(AllocationIdentifierAxis.ALLOCATION, id(SupportingPairingId.ALLOCATION)),
            BranchScope(value(AllocationIdentifierAxis.ORGANIZATION, scope.organizationId),
                value(AllocationIdentifierAxis.COMPANY, scope.companyId),
                value(AllocationIdentifierAxis.BRANCH, scope.branchId)),
            value(AllocationIdentifierAxis.INSTALLATION, id(SupportingPairingId.INSTALLATION)),
            value(AllocationIdentifierAxis.ASSIGNMENT, id(SupportingPairingId.ASSIGNMENT)),
            value(AllocationIdentifierAxis.DEVICE, masterDevice),
            value(AllocationIdentifierAxis.STORAGE_INSTANCE, id(SupportingPairingId.STORAGE_INSTANCE)),
            value(AllocationIdentifierAxis.STORAGE_KEY, id(SupportingPairingId.STORAGE_KEY)),
            value(AllocationIdentifierAxis.PEPPER_KEY, id(SupportingPairingId.PEPPER_KEY)),
            value(AllocationIdentifierAxis.TARGET_SIGNING_KEY, id(SupportingPairingId.MASTER_TARGET_KEY)),
            value(AllocationIdentifierAxis.FIRST_ACTOR, id(SupportingPairingId.FIRST_ACTOR)),
        )
    }

    fun allocation(coordinates: SelfOwnedAllocationCoordinatesV1 = coordinates(),
        bindingDigest: PayloadDigest = paddingDigest('1'), rootDigest: PayloadDigest = paddingDigest('2'),
        delegationDigest: PayloadDigest = paddingDigest('3'), preparationDigest: PayloadDigest = paddingDigest('4'),
        point: ByteArray = masterPoint()): OriginalAllocationCommitmentV2 = OriginalAllocationCommitmentV2(
        coordinates, bindingDigest, rootDigest, delegationDigest, preparationDigest, point,
    )

    /** Internal capture is exercised solely as the module's descriptive DATA producer. */
    fun master(allocation: OriginalAllocationCommitmentV2 = allocation(),
        acquisitionId: DomainId = id(SupportingPairingId.MASTER_ACQUISITION),
        appId: AppId = AppId(FrozenMasterBoundPairingV1.BRANCH_APP_ID),
        name: String = FrozenMasterBoundPairingV1.INITIAL_MASTER_NAME): LocalMasterSdkOriginalV1 =
        LocalMasterSdkOriginalV1.capture(acquisitionId, allocation, application, appId,
            masterPeer, masterFingerprint, masterQr, name)

    fun target(deviceId: DomainId = targetDevice): LocalDevicePairingTargetDescriptor {
        val key = id(SupportingPairingId.TARGET_KEY)
        val point = targetPoint()
        val admission = LocalDeviceAdmission(scope, deviceId, Revision(0), true, key.value,
            sha256(point).value, setOf("synthetic.data"))
        return try { LocalDevicePairingTargetDescriptor.capture(admission, key, point) }
        finally { point.fill(0) }
    }

    fun sdk(target: LocalDevicePairingTargetDescriptor = target(),
        appId: AppId = AppId(FrozenMasterBoundPairingV1.BRANCH_APP_ID),
        peerId: PeerId = targetPeer, fingerprint: PeerFingerprint = targetFingerprint,
        qr: String = targetQr): LocalDevicePairingSdkIdentityV2 = LocalDevicePairingSdkIdentityV2.capture(
        id(SupportingPairingId.TARGET_ACQUISITION), id(SupportingPairingId.TARGET_PREPARATION),
        target, application, appId, peerId, fingerprint, qr,
    )

    fun retainedAdmission(): LocalDeviceAdmission {
        val point = masterPoint()
        return try { LocalDeviceAdmission(scope, masterDevice, Revision(0), true,
            id(SupportingPairingId.MASTER_TARGET_KEY).value, sha256(point).value, emptySet()) }
        finally { point.fill(0) }
    }

    fun definition(master: LocalMasterSdkOriginalV1 = master(),
        target: LocalDevicePairingTargetDescriptor = target(),
        sdk: LocalDevicePairingSdkIdentityV2 = sdk(target),
        serviceAdmission: ByteArray = byteArrayOf(1, 2, 3)): LocalDevicePairingDefinition =
        LocalDevicePairingDefinition.captureMasterBound(id(SupportingPairingId.PAIRING_REQUEST),
            target, serviceAdmission, listOf(retainedAdmission()), sdk, master)

    fun origin(allocation: OriginalAllocationCommitmentV2 = allocation()): NativeDevicePairingOriginV1 =
        NativeDevicePairingOriginV1(allocation.coordinates, allocation.digest,
            allocation.originalNativeBindingDigest, id(SupportingPairingId.INSTALLATION_REQUEST),
            paddingDigest('5'), paddingDigest('6'), Revision(7),
            NativeHistoryRecordedHeadV2(2, paddingDigest('7'), paddingDigest('8'), 7, 3,
                paddingDigest('9'), paddingDigest('a'), paddingDigest('b')),
            paddingDigest('c'), id(SupportingPairingId.ROOT), id(SupportingPairingId.ROOT_KEY),
            allocation.p1RootDocumentDigest, id(SupportingPairingId.FOUNDING_ACTION),
            id(SupportingPairingId.HEAD_AUTHORITY), id(SupportingPairingId.HEAD_ENROLLMENT),
            paddingDigest('d').value)

    fun body(definition: LocalDevicePairingDefinition = definition(),
        origin: NativeDevicePairingOriginV1 = origin(),
        previousUnsignedRetirementDigest: PayloadDigest? = paddingDigest('e')): NativeDevicePairingBodyV1 {
        val bytes = definition.copyWireBytes()
        return try { NativeDevicePairingBodyV1(id(SupportingPairingId.PAIRING_ACTION), origin,
            SelfOwnedOctetsV1(bytes, LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES),
            NativeDevicePairingHeadV1("Synthetic company", "Synthetic head", "synthetic-head", 3, 4, 5, 6,
                LocalAuthenticationObservation(id(SupportingPairingId.OBSERVATION_ISSUER),
                    MonotonicMoment(id(SupportingPairingId.BOOT), 1000)), 2000),
            SelfOwnedOctetsV1(ByteArray(32) { it.toByte() }, 32), previousUnsignedRetirementDigest) }
        finally { bytes.fill(0) }
    }
}

internal fun allocationIdentifierFacts(value: SelfOwnedAllocationCoordinatesV1): List<DomainId> = listOf(
    value.allocationId, value.scope.organizationId, value.scope.companyId, value.scope.branchId,
    value.installationId, value.assignmentId, value.deviceId, value.storageInstanceId,
    value.storageKeyId, value.pepperKeyId, value.targetSigningKeyId, value.firstActorId,
)
