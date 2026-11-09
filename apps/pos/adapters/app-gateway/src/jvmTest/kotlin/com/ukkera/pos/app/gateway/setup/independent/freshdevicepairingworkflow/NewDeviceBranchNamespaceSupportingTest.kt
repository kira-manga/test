package com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow

import com.ukkera.pos.app.setup.LocalSetupFreshDevicePairingStage
import com.ukkera.pos.app.setup.LocalSetupFreshDeviceTargetInput
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen new-device-branch-namespace v1.0.0 NBN-01/02/03 supporting slices only.
 * The fixed namespace literals are from fixtures-v1.json; other valid/invalid
 * text and capability inputs compose the unchanged independently frozen FDPW inputs.
 * Storage and native ports are controlled. No real editor, native SDK identity,
 * custody, history, Master admission, network or actual restart is qualified.
 * No completed historical SDK/Master/reply fixture is fabricated for NBN-04/05/06.
 */
class NewDeviceBranchNamespaceSupportingTest {
    @Test
    fun fixedCarrierDerivesExactBranchAppIdsWithoutPrivateNameContribution() {
        assertEquals(APP_ID_A, LocalMasterSdkProfileV1.appId(scopeA()).value)
        assertEquals(APP_ID_B, LocalMasterSdkProfileV1.appId(scopeB()).value)
        assertEquals(135, APP_ID_A.encodeToByteArray().size)
        assertEquals(135, APP_ID_B.encodeToByteArray().size)
        assertEquals(172, DEVICE_APPENDED_APP_ID.encodeToByteArray().size)
        assertFalse(APP_ID_A == APP_ID_B)

        for (name in listOf("Tablet Cedar", "Tablet Birch")) {
            val input = input(scopeA(), APP_ID_A, name)
            assertTrue(input.matchesBranchRpcNamespace)
            assertEquals(APP_ID_A, input.appId)
            assertEquals(name, input.deviceName)
            assertEquals(CAPABILITIES, input.capabilities)
        }
        assertTrue(input(scopeB(), APP_ID_B).matchesBranchRpcNamespace)
        assertFalse(input(scopeA(), DEVICE_APPENDED_APP_ID).matchesBranchRpcNamespace)
        // The public derivation accepts only branch scope. Actual editor/device
        // presentation and absence of an editable AppId control need separate UI evidence.
    }

    @Test
    fun nineFrozenInvalidScopesCannotCreateSubmittableInputs() = runBlocking {
        val harness = FreshDevicePairingWorkflowHarness()
        val intentEntry = refuseAndObserveNewIntentEntry(harness)
        try {
            harness.coordinator.chooseFreshDeviceTarget(); harness.idle()
            val variants = listOf(
                Triple("", COMPANY, BRANCH_A),
                Triple("5B89B205-1A52-46B0-9A21-F95D3BA8F401", COMPANY, BRANCH_A),
                Triple("not-a-uuid", COMPANY, BRANCH_A),
                Triple(ORGANIZATION, "", BRANCH_A),
                Triple(ORGANIZATION, "C19682D4-C663-4CD2-8486-CC0C0F7A97A4", BRANCH_A),
                Triple(ORGANIZATION, "not-a-uuid", BRANCH_A),
                Triple(ORGANIZATION, COMPANY, ""),
                Triple(ORGANIZATION, COMPANY, "962F31CB-AA41-4A93-9F1B-458E5BDC7410"),
                Triple(ORGANIZATION, COMPANY, "not-a-uuid"),
            )
            for ((organization, company, branch) in variants) {
                assertFails {
                    input(BranchScope(DomainId(organization), DomainId(company), DomainId(branch)), APP_ID_A)
                }
                assertNoNewTargetCalls(harness, intentEntry)
                assertNull(harness.view().targetInput)
            }
        } finally { harness.cleanup() }
    }

    @Test
    fun earlierFrozenInvalidRequiredInputsCannotSubmitTheNewCarrier() = runBlocking {
        val harness = FreshDevicePairingWorkflowHarness()
        val intentEntry = refuseAndObserveNewIntentEntry(harness)
        try {
            harness.coordinator.chooseFreshDeviceTarget(); harness.idle()
            val names = listOf("", "   ", "x".repeat(256), "é".repeat(128), "x\u0000y", "x\ny", "x\ry")
            for (name in names) {
                assertFails { input(scopeA(), APP_ID_A, name) }
                assertNoNewTargetCalls(harness, intentEntry)
            }
            val capabilities = listOf(
                (0..64).map { "c" + it.toString().padStart(2, '0') }.toSet(),
                setOf(""), setOf("A".repeat(65)), setOf("stock read"),
                setOf("stock/read"), setOf("é"), setOf("\u0000"),
            )
            for (codes in capabilities) {
                assertFails { input(scopeA(), APP_ID_A, capabilities = codes) }
                assertNoNewTargetCalls(harness, intentEntry)
            }
            assertNull(harness.view().targetInput)
        } finally { harness.cleanup() }
    }

    @Test
    fun directMismatchesRefuseReviewPreparationAndAcquisitionBeforeNewCalls() = runBlocking {
        val variants = listOf(APP_ID_B, DEVICE_APPENDED_APP_ID, " $APP_ID_A", "$APP_ID_A ", "", "pos.fixture.local")
        for (appId in variants) {
            val harness = FreshDevicePairingWorkflowHarness()
            val intentEntry = refuseAndObserveNewIntentEntry(harness)
            try {
                harness.coordinator.chooseFreshDeviceTarget(); harness.idle()
                if (appId.isEmpty()) {
                    assertFails { input(scopeA(), appId) }
                } else {
                    val mismatching = input(scopeA(), appId)
                    assertFalse(mismatching.matchesBranchRpcNamespace)
                    harness.coordinator.reviewFreshDeviceTarget(mismatching); harness.idle()
                }
                assertNoNewTargetCalls(harness, intentEntry)
                assertNull(harness.view().targetInput)
                assertNull(harness.view().originalIntentId)

                // These are direct public calls, not editor clicks. An unsuccessful
                // review cannot supply preparation/acquisition authority or a retry.
                harness.coordinator.prepareFreshDeviceTarget(harness.view().reviewId); harness.idle()
                assertNoNewTargetCalls(harness, intentEntry)
                harness.coordinator.acquireFreshDeviceSdkIdentity(harness.view().reviewId); harness.idle()
                assertNoNewTargetCalls(harness, intentEntry)
                assertFalse(harness.view().preparationAttempted)
                assertFalse(harness.view().sdkAttempted)
            } finally { harness.cleanup() }
        }
        // The actual public input requires a nonnullable String. V-APP-06 cannot
        // be encoded here; no invented nullable field, reflection or missing-input API.
    }

    @Test
    fun matchingFixedCarrierReviewIsEligibleWithoutRetainingEffectsOrAuthority() = runBlocking {
        val harness = FreshDevicePairingWorkflowHarness()
        val intentEntry = refuseAndObserveNewIntentEntry(harness)
        try {
            harness.coordinator.chooseFreshDeviceTarget(); harness.idle()
            val matching = input(scopeA(), APP_ID_A)
            assertTrue(matching.matchesBranchRpcNamespace)
            harness.coordinator.reviewFreshDeviceTarget(matching); harness.idle()
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_INPUT_REVIEW, harness.view().stage)
            val retained = assertNotNull(harness.view().targetInput)
            assertTrue(matching.matches(retained))
            assertEquals(APP_ID_A, retained.appId)
            assertNoNewTargetCalls(harness, intentEntry)
            assertFalse(harness.view().preparationAttempted)
            assertFalse(harness.view().sdkAttempted)
            assertNull(harness.view().identity)
            assertNull(harness.view().possession)
        } finally { harness.cleanup() }
    }

    @Test
    fun incompatibleRecoveredPendingIntentStaysExactAcrossTwoDirectAcquisitionAttempts() = runBlocking {
        val world = FreshDevicePairingWorkflowWorld()
        val harness = FreshDevicePairingWorkflowHarness(world)
        try {
            seedRetainedPending(harness, world.data.originalFixtureAppId)
            val before = harness.targetStorage.snapshot()
            val original = assertNotNull(harness.intents.target())
            val intentEntry = refuseAndObserveNewIntentEntry(harness)
            harness.coordinator.inspectOriginalFreshDeviceTarget(); harness.idle()
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_KEY_READY, harness.view().stage)
            assertEquals(world.data.originalFixtureAppId, assertNotNull(harness.view().targetInput).appId)
            assertFalse(assertNotNull(harness.view().targetInput).matchesBranchRpcNamespace)
            assertRetentionUnchanged(before, harness.targetStorage.snapshot())
            repeat(2) {
                harness.coordinator.acquireFreshDeviceSdkIdentity(harness.view().reviewId); harness.idle()
                assertFalse(intentEntry.entered.isCompleted, "no new replacement-intent storage entry")
                assertEquals(0, world.entries(FreshWorkflowEffect.TARGET_SDK_ACQUIRE).size)
                assertEquals(0, world.entries(FreshWorkflowEffect.TARGET_PREPARE).size)
                assertEquals(original, harness.intents.target())
                assertRetentionUnchanged(before, harness.targetStorage.snapshot())
                assertFalse(harness.view().sdkAttempted)
                assertNull(harness.view().identity)
                assertEquals(world.data.originalFixtureAppId, assertNotNull(harness.view().targetInput).appId)
            }
            assertEquals(0, world.entries().size)
            assertTrue(world.forbiddenCalls().isEmpty())
        } finally { harness.cleanup() }
    }

    @Test
    fun matchingRecoveredPendingNamespaceIsEligibleWithoutAutomaticAcquisition() = runBlocking {
        val world = FreshDevicePairingWorkflowWorld()
        val harness = FreshDevicePairingWorkflowHarness(world)
        try {
            seedRetainedPending(harness, NewDeviceBranchNamespaceV1Carrier.FDPW_APP_ID)
            val before = harness.targetStorage.snapshot()
            val original = assertNotNull(harness.intents.target())
            val intentEntry = refuseAndObserveNewIntentEntry(harness)
            harness.coordinator.inspectOriginalFreshDeviceTarget(); harness.idle()
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_KEY_READY, harness.view().stage)
            assertTrue(assertNotNull(harness.view().targetInput).matchesBranchRpcNamespace)
            assertEquals(NewDeviceBranchNamespaceV1Carrier.FDPW_APP_ID, assertNotNull(harness.view().targetInput).appId)
            assertFalse(intentEntry.entered.isCompleted)
            assertEquals(0, world.entries().size)
            assertFalse(harness.view().sdkAttempted)
            assertNull(harness.view().identity)
            assertEquals(original, harness.intents.target())
            assertRetentionUnchanged(before, harness.targetStorage.snapshot())
            assertTrue(world.forbiddenCalls().isEmpty())
            // Explicit positive lifecycle effects use the existing unchanged FDPW
            // assertions with the separately versioned default carrier, not a new oracle.
        } finally { harness.cleanup() }
    }

    private fun seedRetainedPending(harness: FreshDevicePairingWorkflowHarness, appId: String) {
        val world = harness.world
        val intentId = FreshDevicePairingWorkflowData.id("target_input_intent_A")
        // Seed complete controlled pending metadata, never a new incompatible SDK
        // acquisition or an edited completed snapshot. No SDK identity is retained.
        harness.intents.beginTarget(intentId, world.data.scope, world.data.capabilities, appId, world.data.privateName)
        harness.intents.markTargetPreparation(intentId)
        harness.intents.retainTargetReference(intentId, world.data.keyReference())
        world.preparationCompleted = true
        assertFalse(assertNotNull(harness.intents.target()).sdkAttempted)
        assertNull(assertNotNull(harness.intents.target()).acquisitionId)
        assertNull(assertNotNull(harness.intents.target()).sdkDigest)
    }

    private fun refuseAndObserveNewIntentEntry(harness: FreshDevicePairingWorkflowHarness): FreshWorkflowStorageGate =
        FreshWorkflowStorageGate("intent", fail = true).also {
            it.release() // A defect must record its call rather than leave a test waiter blocked.
            harness.targetStorage.gate = it
        }

    private fun assertNoNewTargetCalls(harness: FreshDevicePairingWorkflowHarness, intentEntry: FreshWorkflowStorageGate) {
        assertFalse(intentEntry.entered.isCompleted, "zero calls at the controlled new-intent storage entry")
        assertEquals(0, harness.world.entries(FreshWorkflowEffect.TARGET_PREPARE).size)
        assertEquals(0, harness.world.entries(FreshWorkflowEffect.TARGET_SDK_ACQUIRE).size)
        assertEquals(0, harness.world.entries().size)
        assertTrue(harness.targetStorage.snapshot().isEmpty())
        assertTrue(harness.world.forbiddenCalls().isEmpty())
    }

    private fun assertRetentionUnchanged(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        for ((name, bytes) in expected) assertTrue(bytes.contentEquals(assertNotNull(actual[name])), "exact retained DATA")
    }

    private companion object {
        const val ORGANIZATION = "5b89b205-1a52-46b0-9a21-f95d3ba8f401"
        const val COMPANY = "c19682d4-c663-4cd2-8486-cc0c0f7a97a4"
        const val BRANCH_A = "962f31cb-aa41-4a93-9f1b-458e5bdc7410"
        const val BRANCH_B = "92c84ae1-3c46-4f18-b701-1719da2b2510"
        const val APP_ID_A = "ukkera-pos.rpc.branch.v1:5b89b205-1a52-46b0-9a21-f95d3ba8f401:c19682d4-c663-4cd2-8486-cc0c0f7a97a4:962f31cb-aa41-4a93-9f1b-458e5bdc7410"
        const val APP_ID_B = "ukkera-pos.rpc.branch.v1:5b89b205-1a52-46b0-9a21-f95d3ba8f401:c19682d4-c663-4cd2-8486-cc0c0f7a97a4:92c84ae1-3c46-4f18-b701-1719da2b2510"
        const val DEVICE_APPENDED_APP_ID = "ukkera-pos.rpc.branch.v1:5b89b205-1a52-46b0-9a21-f95d3ba8f401:c19682d4-c663-4cd2-8486-cc0c0f7a97a4:962f31cb-aa41-4a93-9f1b-458e5bdc7410:768de8b6-0ad4-41a5-a235-0279f32ab0d4"
        val CAPABILITIES = setOf("stock.read", "orders.write")
        fun scopeA() = BranchScope(DomainId(ORGANIZATION), DomainId(COMPANY), DomainId(BRANCH_A))
        fun scopeB() = BranchScope(DomainId(ORGANIZATION), DomainId(COMPANY), DomainId(BRANCH_B))
        fun input(scope: BranchScope, appId: String, name: String = "Tablet Cedar", capabilities: Set<String> = CAPABILITIES) =
            LocalSetupFreshDeviceTargetInput(scope, capabilities, appId, name)
    }
}
