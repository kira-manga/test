package com.ukkera.pos.security.independent.masteroriginal

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalMasterSdkOriginalV1
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Supporting slices of frozen master-original DATA supplement v1.0.0, MOID-03/MOID-06.
 * Fixed namespace/default-name values and raw malformed inputs need no native original fixture.
 * These profile-helper checks do not compose valid O0/O1 or test retained-name reconstruction.
 * Raw rejection is not independent canonical framing or valid-at-ceiling evidence. Complete
 * DATA/native/whole MOID/MOI cases, acquisition, custody, history and persistence remain separate.
 */
public class LocalMasterOriginalDataSupportingSlicesTest {
    @Test
    public fun moid03_branchNamespaceIsExactWithoutDeviceOrAcquisitionSuffix(): Unit {
        val first = LocalMasterSdkProfileV1.appId(frozenScope())
        val second = LocalMasterSdkProfileV1.appId(frozenScope())

        assertEquals(FrozenMasterOriginalProfileInputs.sameBranchAppId, first.value)
        assertEquals(FrozenMasterOriginalProfileInputs.sameBranchAppId, second.value)
        assertEquals(135, first.value.encodeToByteArray().size)
        for (id in listOf(FrozenMasterOriginalProfileInputs.deviceA, FrozenMasterOriginalProfileInputs.deviceB,
            FrozenMasterOriginalProfileInputs.acquisitionA, FrozenMasterOriginalProfileInputs.acquisitionB)) {
            assertFalse(first.value.contains(id), "The branch namespace has no device/acquisition suffix")
        }
        // Only the branch-wide profile function is observed; two genuine device originals
        // in the same branch still require the frozen A0/A1/S0/S1 composition inputs.
    }

    @Test
    public fun moid03_changedBranchCompanyAndOrganizationHaveExactDistinctNamespaces(): Unit {
        val variants = listOf(
            frozenScope(branch = "9bab4d07-0aa3-4b3d-986c-2fa9c1a9283b") to
                "ukkera-pos.rpc.branch.v1:6135872a-06db-4b42-a397-d9f54c783620:a779e735-0434-4c20-a674-9fa4618de00a:9bab4d07-0aa3-4b3d-986c-2fa9c1a9283b",
            frozenScope(company = "ef088ebd-efc8-4c6c-86d6-6a82af9b086c",
                branch = "c9c7c6b0-d4d7-4023-8e39-bf2b57a3ce3e") to
                "ukkera-pos.rpc.branch.v1:6135872a-06db-4b42-a397-d9f54c783620:ef088ebd-efc8-4c6c-86d6-6a82af9b086c:c9c7c6b0-d4d7-4023-8e39-bf2b57a3ce3e",
            frozenScope(organization = "0c6722c6-c2d6-4bcc-9d65-ef7d31ab904c",
                company = "e129f706-fab7-4a3b-bdd9-8c59c801910c",
                branch = "a92f4374-90b5-46d0-85b2-cf0d0f972bfb") to
                "ukkera-pos.rpc.branch.v1:0c6722c6-c2d6-4bcc-9d65-ef7d31ab904c:e129f706-fab7-4a3b-bdd9-8c59c801910c:a92f4374-90b5-46d0-85b2-cf0d0f972bfb",
        )

        for ((scope, expected) in variants) {
            val actual = LocalMasterSdkProfileV1.appId(scope).value
            assertEquals(expected, actual)
            assertEquals(135, actual.encodeToByteArray().size)
            assertNotEquals(FrozenMasterOriginalProfileInputs.sameBranchAppId, actual)
        }
    }

    @Test
    public fun moid03_initialDefaultNamesPreserveTheirExactDeviceUuids(): Unit {
        val nameA = LocalMasterSdkProfileV1.deviceName(DomainId(FrozenMasterOriginalProfileInputs.deviceA))
        val nameB = LocalMasterSdkProfileV1.deviceName(DomainId(FrozenMasterOriginalProfileInputs.deviceB))

        assertEquals("Master d82e1682-6062-4ca7-9f19-1a1fce9533b7", nameA)
        assertEquals("Master 9e2c4f68-bcf8-472b-857a-9a4a1ef410ae", nameB)
        assertEquals(43, nameA.encodeToByteArray().size)
        assertEquals(43, nameB.encodeToByteArray().size)
        assertNotEquals(nameA, nameB)
        // This is initial default text only, not recovered/renamed native original DATA.
    }

    @Test
    public fun moid03_uppercaseScopeUuidsRejectBeforeNamespaceDataIsProduced(): Unit {
        val original = listOf(FrozenMasterOriginalProfileInputs.organization,
            FrozenMasterOriginalProfileInputs.company, FrozenMasterOriginalProfileInputs.branch)
        for (changedIndex in original.indices) {
            val candidate = original.toMutableList()
            candidate[changedIndex] = original[changedIndex].uppercase()
            assertFails {
                LocalMasterSdkProfileV1.appId(frozenScope(candidate[0], candidate[1], candidate[2]))
            }
        }
        assertEquals(FrozenMasterOriginalProfileInputs.sameBranchAppId,
            LocalMasterSdkProfileV1.appId(frozenScope()).value)
        // This observes the canonical DomainId input boundary, not an Original wire-field mutation.
    }

    @Test
    public fun moid06_emptyWireInputRejectsWithoutAnOriginal(): Unit {
        assertFails { LocalMasterSdkOriginalV1.decode(ByteArray(0)) }
    }

    @Test
    public fun moid06_malformedZeroBytesAtSixteenKiBCeilingReject(): Unit {
        assertEquals(16_384, LocalMasterSdkProfileV1.MAXIMUM_ORIGINAL_BYTES)
        val candidate = ByteArray(16_384)
        assertEquals(16_384, candidate.size)
        assertFails { LocalMasterSdkOriginalV1.decode(candidate) }
        // A size ceiling does not make padding a valid canonical original.
    }

    @Test
    public fun moid06_firstOversizedZeroByteInputRejects(): Unit {
        val candidate = ByteArray(16_385)
        assertEquals(16_385, candidate.size)
        assertFails { LocalMasterSdkOriginalV1.decode(candidate) }
    }
}

/** Exact fixed public inputs from data-fixtures-v1.json; no native allocation/key/SDK seed. */
private object FrozenMasterOriginalProfileInputs {
    const val organization: String = "6135872a-06db-4b42-a397-d9f54c783620"
    const val company: String = "a779e735-0434-4c20-a674-9fa4618de00a"
    const val branch: String = "0483be19-4280-471c-8a39-a8302ad1f204"
    const val deviceA: String = "d82e1682-6062-4ca7-9f19-1a1fce9533b7"
    const val deviceB: String = "9e2c4f68-bcf8-472b-857a-9a4a1ef410ae"
    const val acquisitionA: String = "50727f9d-bc3c-4fb9-a0a1-eb33c81fdce9"
    const val acquisitionB: String = "2a913f48-af14-41f8-a9fd-74daa3d2f5a0"
    const val sameBranchAppId: String =
        "ukkera-pos.rpc.branch.v1:6135872a-06db-4b42-a397-d9f54c783620:a779e735-0434-4c20-a674-9fa4618de00a:0483be19-4280-471c-8a39-a8302ad1f204"
}

private fun frozenScope(
    organization: String = FrozenMasterOriginalProfileInputs.organization,
    company: String = FrozenMasterOriginalProfileInputs.company,
    branch: String = FrozenMasterOriginalProfileInputs.branch,
): BranchScope = BranchScope(DomainId(organization), DomainId(company), DomainId(branch))
