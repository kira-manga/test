package com.ukkera.pos.security.independent.trainingliveprofilev1

import com.ukkera.pos.security.LocalMasterSdkOriginalV1
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalSetupCompositionMode
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import com.ukkera.pos.security.LocalTrainingSdkProfileV1
import com.ukkera.pos.security.SelfOwnedCompositionNames
import com.ukkera.pos.security.SelfOwnedNativeNamespaceV1
import com.ukkera.pos.security.SelfOwnedRegistryNamespaceV1
import com.ukkera.pos.security.independent.masterboundpairing.AllocationIdentifierAxis
import com.ukkera.pos.security.independent.masterboundpairing.FrozenMasterBoundPairingV1
import com.ukkera.pos.security.independent.masterboundpairing.MasterBoundPairingDataFixture
import dev.p2pkit.core.AppId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Supporting JVM slices for frozen `training-live-composition-profile-v1` TLP-01..TLP-05.
 *
 * The production profile/DATA/name-validation functions run over synthetic descriptive DATA.
 * This is not a native root/key/journal/custody observation, not a durable master result, not a
 * UI/entry observation, and not a whole TLN case. The tests cannot prove that two real
 * compositions coexist or that wrong-root/wrong-key rejection preserves native roots.
 */
class TrainingLiveCompositionProfileSupportingTest {
    private val scope = MasterBoundPairingDataFixture.scope
    private val device = MasterBoundPairingDataFixture.masterDevice
    private val volume = "12345678-1234-4234-8234-123456789abc"

    @Test
    fun tlp01_liveProfileRemainsTheFrozenLegacyNamespace() {
        assertEquals(LocalSetupCompositionMode.LIVE, LocalMasterSdkProfileV1.mode)
        assertEquals(FrozenMasterBoundPairingV1.BRANCH_APP_ID,
            LocalMasterSdkProfileV1.appId(scope).value)
        assertEquals(FrozenMasterBoundPairingV1.INITIAL_MASTER_NAME,
            LocalMasterSdkProfileV1.deviceName(device))
        assertEquals(FrozenMasterBoundPairingV1.BRANCH_APP_ID_UTF8_BYTES,
            LocalMasterSdkProfileV1.appId(scope).value.encodeToByteArray().size)
        assertEquals(FrozenMasterBoundPairingV1.INITIAL_MASTER_NAME_UTF8_BYTES,
            LocalMasterSdkProfileV1.deviceName(device).encodeToByteArray().size)
    }

    @Test
    fun tlp02_trainingSdkProfilesAreDistinctFromLiveAndEachOther() {
        val liveAppId = LocalMasterSdkProfileV1.appId(scope).value
        val liveName = LocalMasterSdkProfileV1.deviceName(device)
        val profiles = LocalTrainingCompositionSlot.entries.map(::LocalTrainingSdkProfileV1)

        assertEquals(LocalTrainingCompositionSlot.entries, profiles.map { it.slot })
        assertEquals(LocalTrainingCompositionSlot.entries.size,
            profiles.map { it.appId(scope).value }.toSet().size)
        assertEquals(LocalTrainingCompositionSlot.entries.size,
            profiles.map { it.deviceName(device) }.toSet().size)

        for (profile in profiles) {
            assertEquals(LocalSetupCompositionMode.TRAINING, profile.mode)
            assertNotEquals(liveAppId, profile.appId(scope).value)
            assertNotEquals(liveName, profile.deviceName(device))
            assertTrue(profile.appId(scope).value.isNotEmpty())
            assertEquals(profile, LocalTrainingSdkProfileV1.forAppId(profile.appId(scope), scope))
            assertNull(LocalTrainingSdkProfileV1.forAppId(LocalMasterSdkProfileV1.appId(scope), scope))
            assertNull(LocalTrainingSdkProfileV1.forAppId(AppId("ukkera-pos.rpc.training.v1:unknown"), scope))
        }
        assertEquals(profiles[0], LocalTrainingSdkProfileV1.forAppId(profiles[0].appId(scope), scope))
        assertNotEquals(profiles[0], profiles[1])
        assertNotEquals(profiles[0].appId(scope), profiles[1].appId(scope))
        assertFalse(profiles[0].deviceName(device) == profiles[1].deviceName(device))
    }

    @Test
    fun tlp03_retainedDeviceLabelIsPreservedAndDoesNotSelectTheProfile() {
        val trainingProfile = LocalTrainingSdkProfileV1(LocalTrainingCompositionSlot.TRAINING_1)
        val trainingOriginal = MasterBoundPairingDataFixture.master(
            appId = trainingProfile.appId(scope),
            name = "Training retained label one",
        )
        assertEquals(LocalSetupCompositionMode.TRAINING, trainingOriginal.compositionProfile.mode)
        assertEquals(trainingProfile, trainingOriginal.compositionProfile)
        assertEquals("Training retained label one", trainingOriginal.exactDeviceName)
        assertNotEquals(trainingProfile.deviceName(device), trainingOriginal.exactDeviceName)

        val trainingBytes = trainingOriginal.copyWireBytes()
        val trainingDecoded = try { LocalMasterSdkOriginalV1.decode(trainingBytes.copyOf()) } finally {
            trainingBytes.fill(0)
        }
        assertEquals(LocalSetupCompositionMode.TRAINING, trainingDecoded.compositionProfile.mode)
        assertEquals(trainingProfile, trainingDecoded.compositionProfile)
        assertEquals(trainingOriginal.exactDeviceName, trainingDecoded.exactDeviceName)
        assertContentEquals(trainingOriginal.copyWireBytes(), trainingDecoded.copyWireBytes())

        val liveOriginal = MasterBoundPairingDataFixture.master(
            appId = LocalMasterSdkProfileV1.appId(scope),
            name = "Retained live label not the generated label",
        )
        assertEquals(LocalSetupCompositionMode.LIVE, liveOriginal.compositionProfile.mode)
        assertEquals(LocalMasterSdkProfileV1, liveOriginal.compositionProfile)
        assertEquals("Retained live label not the generated label", liveOriginal.exactDeviceName)
        assertNotEquals(LocalMasterSdkProfileV1.deviceName(device), liveOriginal.exactDeviceName)

        val liveBytes = liveOriginal.copyWireBytes()
        val liveDecoded = try { LocalMasterSdkOriginalV1.decode(liveBytes.copyOf()) } finally {
            liveBytes.fill(0)
        }
        assertEquals(LocalSetupCompositionMode.LIVE, liveDecoded.compositionProfile.mode)
        assertEquals(liveOriginal.exactDeviceName, liveDecoded.exactDeviceName)
        assertContentEquals(liveOriginal.copyWireBytes(), liveDecoded.copyWireBytes())
    }

    @Test
    fun tlp04_wrongScopeAndUnknownSdkNamespacesAreRejected() {
        val trainingProfile = LocalTrainingSdkProfileV1(LocalTrainingCompositionSlot.TRAINING_1)
        val otherBranchAllocation = MasterBoundPairingDataFixture.allocation(
            MasterBoundPairingDataFixture.coordinates(AllocationIdentifierAxis.BRANCH),
        )
        assertFails {
            MasterBoundPairingDataFixture.master(
                allocation = otherBranchAllocation,
                appId = trainingProfile.appId(scope),
                name = "Training label",
            )
        }
        assertFails {
            MasterBoundPairingDataFixture.master(
                allocation = otherBranchAllocation,
                appId = LocalMasterSdkProfileV1.appId(scope),
                name = "Live label",
            )
        }
        assertFails {
            MasterBoundPairingDataFixture.master(
                appId = AppId("ukkera-pos.rpc.training.v1:training_3:" +
                    "${scope.organizationId.value}:${scope.companyId.value}:${scope.branchId.value}"),
                name = "Unknown training slot",
            )
        }
        assertFails {
            MasterBoundPairingDataFixture.master(
                appId = AppId("ukkera-pos.rpc.other.v1:${scope.organizationId.value}:" +
                    "${scope.companyId.value}:${scope.branchId.value}"),
                name = "Unknown namespace",
            )
        }
    }

    @Test
    fun tlp05_rootNamesAreModeAndSlotSeparatedAndValidationDoesNotCrossAccept() {
        val liveTarget = SelfOwnedCompositionNames.LIVE_TARGET_DIRECTORY
        val liveProvisioner = SelfOwnedCompositionNames.LIVE_PROVISIONER_DIRECTORY
        assertEquals("ukkera-self-owned-target-v1", liveTarget)
        assertEquals("ukkera-self-owned-provisioner-v1", liveProvisioner)

        val targets = LocalTrainingCompositionSlot.entries.map {
            SelfOwnedCompositionNames.trainingTargetDirectory(it)
        }
        val provisioners = LocalTrainingCompositionSlot.entries.map {
            SelfOwnedCompositionNames.trainingProvisionerDirectory(it)
        }
        assertEquals(LocalTrainingCompositionSlot.entries.size, targets.toSet().size)
        assertEquals(LocalTrainingCompositionSlot.entries.size, provisioners.toSet().size)
        for (training in targets) assertNotEquals(liveTarget, training)
        for (training in provisioners) assertNotEquals(liveProvisioner, training)

        val base = "/data/user/0/synthetic.app/no_backup/"
        assertTrue(SelfOwnedCompositionNames.isTargetPath(base + liveTarget))
        assertTrue(SelfOwnedCompositionNames.isProvisionerPath(base + liveProvisioner))
        for (target in targets) {
            assertTrue(SelfOwnedCompositionNames.isTargetPath(base + target))
            assertFalse(SelfOwnedCompositionNames.isProvisionerPath(base + target))
        }
        for (provisioner in provisioners) {
            assertTrue(SelfOwnedCompositionNames.isProvisionerPath(base + provisioner))
            assertFalse(SelfOwnedCompositionNames.isTargetPath(base + provisioner))
        }
        assertFalse(SelfOwnedCompositionNames.isTargetPath(base + "$liveTarget-training-3"))
        assertFalse(SelfOwnedCompositionNames.isProvisionerPath(base + "$liveProvisioner-training-3"))
        assertFalse(SelfOwnedCompositionNames.isTargetPath(base + "$liveTarget/"))
        assertFalse(SelfOwnedCompositionNames.isProvisionerPath(base + "$liveProvisioner/"))

        for (path in listOf(base + liveTarget) + targets.map { base + it })
            assertTrue(SelfOwnedNativeNamespaceV1(path, volume, 1000, 1, 2, 3).canonicalPath == path)
        for (path in listOf(base + liveProvisioner) + provisioners.map { base + it })
            assertTrue(SelfOwnedRegistryNamespaceV1(path, 1000, volume, 1, 2).canonicalPath == path)
        assertFails { SelfOwnedNativeNamespaceV1(base + "$liveTarget-training-3", volume, 1000, 1, 2, 3) }
        assertFails { SelfOwnedRegistryNamespaceV1(base + "$liveProvisioner-training-3", 1000, volume, 1, 2) }
        assertFails { SelfOwnedNativeNamespaceV1(base + liveProvisioner, volume, 1000, 1, 2, 3) }
        assertFails { SelfOwnedRegistryNamespaceV1(base + liveTarget, 1000, volume, 1, 2) }
    }
}
