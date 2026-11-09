package com.ukkera.pos.app.gateway.setup.independent.applicationlanattachment

import com.ukkera.pos.app.gateway.setup.LocalSetupLanSettings
import com.ukkera.pos.app.setup.*
import com.ukkera.pos.database.LocalMasterLifecycleMode
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Bounded shared composition slices from application-lan-attachment-v1.1, not native qualification. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationLanAttachmentCoordinatorTest {
    private val frozen = ApplicationLanAttachmentFrozenInputs

    @Test fun v01_rejectedPolicyRequestsHaveNoApplicationEffects() = runTest {
        val rejected = frozen.policyCounts.filter { !it.eligible }.map { it.id to it.cidrs.orEmpty() } +
            frozen.badCidrs.map { it to listOf(it) }
        for ((_, cidrs) in rejected) {
            withMaster { h -> assertRefusedHost(h, frozen.hostZero.copyInput(allowedSubnets = cidrs)) }
            withHarness { h ->
                h.chooseFreshTargetWithoutNativeOwner()
                assertRefusedClient(h, frozen.clientPrimary.copyInput(allowedSubnets = cidrs), frozen.clientEndpoint)
            }
        }
    }

    @Test fun v02_rejectedLocalAddressRequestsHaveNoApplicationEffects() = runTest {
        for (row in frozen.localAddresses.filter { !it.eligible }) {
            withMaster { h -> assertRefusedHost(h, frozen.hostZero.copyInput(localAddress = row.value)) }
            withHarness { h ->
                h.chooseFreshTargetWithoutNativeOwner()
                assertRefusedClient(h, frozen.clientPrimary.copyInput(localAddress = row.value), frozen.clientEndpoint)
            }
        }
    }

    @Test fun v02_rejectedSelectedEndpointAddressesHaveNoApplicationEffects() = runTest {
        for (row in frozen.endpointAddresses.filter { !it.eligible }) {
            withHarness { h ->
                h.chooseFreshTargetWithoutNativeOwner()
                assertRefusedClient(h, frozen.clientPrimary, frozen.clientEndpoint.copy(address = row.value))
            }
        }
    }

    @Test fun v03_missingExplicitInterfaceCannotBeInferredFromTheAddress() = runTest {
        val omitted = frozen.interfaces.single { it.id == null }.id.orEmpty()
        withMaster { h -> assertRefusedHost(h, frozen.hostZero.copyInput(interfaceName = omitted)) }
        withHarness { h ->
            h.chooseFreshTargetWithoutNativeOwner()
            assertRefusedClient(h, frozen.clientPrimary.copyInput(interfaceName = omitted), frozen.clientEndpoint)
        }
    }

    @Test fun v04_rejectedListenerAndEndpointPortsHaveNoApplicationEffects() = runTest {
        for (row in frozen.listenerPorts.filter { !it.eligible }) {
            withMaster { h -> assertRefusedHost(h, frozen.hostZero.copyInput(listenPort = row.value)) }
            withHarness { h ->
                h.chooseFreshTargetWithoutNativeOwner()
                assertRefusedClient(h, frozen.clientPrimary.copyInput(listenPort = row.value), frozen.clientEndpoint)
            }
        }
        for (row in frozen.endpointPorts.filter { !it.eligible }) {
            withHarness { h ->
                h.chooseFreshTargetWithoutNativeOwner()
                assertRefusedClient(h, frozen.clientPrimary, frozen.clientEndpoint.copy(port = row.value))
            }
        }
    }

    @Test fun c01_missingOrNonOperationalRetainedMasterCannotStartOrReplaceAnything() = runTest {
        withHarness { h ->
            val before = h.snapshot()
            repeat(2) { h.startHost(); h.idle() }
            h.assertNoNewEffects(before)
            assertEquals(LocalSetupLanStatus.NOT_ATTACHED, h.view.lan.status)
        }
        // Public operational guard only; these are not initial-Kit/native-original failure evidence.
        for (mode in listOf(LocalMasterLifecycleMode.ALLOCATION_ONLY,
            LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD, LocalMasterLifecycleMode.UNRESOLVED)) {
            withMaster { h ->
                h.master.mode.value = mode
                val before = h.snapshot()
                repeat(2) { h.startHost(); h.idle() }
                h.assertNoNewEffects(before)
                assertEquals(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED, h.view.problem)
                assertEquals(LocalSetupLanStatus.NOT_ATTACHED, h.view.lan.status)
            }
        }
    }

    @Test fun c02_retentionAndControlledReadyCannotPublishBeforeActualObservation() = runTest {
        withMaster { h ->
            h.host.readGate.hold(); h.host.openGate.hold()
            h.startHost()
            val retained = h.hostRetentions.single()
            assertSame(h.master, retained.master)
            assertTrue(retained.settings.sameSelection(LocalSetupLanSettings.from(frozen.hostZero)))
            assertEquals(LocalSetupLanStatus.STARTING, retained.view.status)
            assertNull(retained.view.endpoint)
            assertTrue(h.events.index("Host.retain") < h.events.index("script.HostOriginal.read.enter"))
            h.startHost() // Another button request cannot create a second pending owner.
            assertEquals(1, h.events.count("Host.retain"))
            assertNull(h.view.lan.endpoint)
            val beforeRefresh = h.snapshot()
            h.coordinator.refreshLanAttachment(); h.pump()
            h.assertNoNewEffects(beforeRefresh)

            h.host.readGate.release(); h.pump()
            assertEquals(1, h.events.count("script.HostFactory.ready"))
            assertEquals(0, h.events.count("script.LAN.listening"))
            assertEquals(LocalSetupLanStatus.STARTING, h.view.lan.status)
            assertNull(h.view.lan.endpoint)
            h.host.openGate.release(); h.idle()
            assertEquals(LocalSetupLanStatus.LISTENING, h.view.lan.status)
            assertEquals(frozen.actualHostEndpoint, h.view.lan.endpoint)
            assertTrue(assertNotNull(h.view.lan.endpoint).port != 0)
            h.assertSingleHostScript()
            h.assertMasterPreserved()
        }
    }

    @Test fun c06_eachFrozenHostConflictRefusesWithThePendingOrActiveOwnerUnchanged() = runTest {
        for (row in frozen.hostConflicts) {
            for (pending in listOf(true, false)) {
                withMaster { h ->
                    if (pending) h.host.openGate.hold()
                    h.startHost()
                    val retained = h.hostRetentions.single()
                    val before = h.snapshot()
                    h.coordinator.startLanHost(row.input); h.pump()
                    h.assertNoNewEffects(before)
                    assertSame(retained, h.hostRetentions.single())
                    assertEquals(if (pending) LocalSetupProblem.BUSY else LocalSetupProblem.ORIGINAL_ACTION_REQUIRED,
                        h.view.problem, "ALAN-C06/Host/${row.id}")
                    if (pending) { assertNull(h.view.lan.endpoint); h.host.openGate.release(); h.idle() }
                    assertEquals(frozen.actualHostEndpoint, h.view.lan.endpoint)
                    h.assertSingleHostScript()
                    h.assertMasterPreserved()
                }
            }
        }
    }

    @Test fun c06_identicalActiveHostRequestUsesTheSameControlledPortAndAcquisition() = runTest {
        withMaster { h ->
            h.activeHost()
            val retained = h.hostRetentions.single()
            h.startHost(); h.idle()
            assertSame(retained, h.hostRetentions.single())
            assertEquals(2, h.events.count("Host.start"))
            h.assertSingleHostScript()
            assertEquals(frozen.actualHostEndpoint, h.view.lan.endpoint)
            h.assertMasterPreserved()
        }
    }

    @Test fun c07_cancelledStatusWaiterDoesNotCancelOwnedWorkAtAnyControlledHostStage() = runTest {
        for (stage in frozen.waiterStages) {
            withMaster { h ->
                h.host.hold(stage)
                val waiterA = async {
                    h.coordinator.startLanHost(frozen.hostZero)
                    h.coordinator.state.first { it.lan.status == LocalSetupLanStatus.LISTENING }
                }
                h.pump()
                waiterA.cancelAndJoin(); h.pump()
                assertTrue(waiterA.isCancelled)
                assertTrue(assertNotNull(h.hostRetentions.single().scope.coroutineContext[Job]).isActive)
                assertEquals(0, h.events.count("Host.close.enter"))
                assertNull(h.view.lan.endpoint)
                val waiterB = async {
                    h.coordinator.startLanHost(frozen.hostZero)
                    h.coordinator.state.first { it.lan.status == LocalSetupLanStatus.LISTENING }
                }
                h.pump()
                h.host.release(stage); h.pump()
                assertEquals(frozen.actualHostEndpoint, waiterB.await().lan.endpoint)
                h.assertSingleHostScript()
                h.assertMasterPreserved()
            }
        }
    }

    @Test fun c08_statusSubscriberDisposalAndPresentationLockDoNotCloseTheOwnedHost() = runTest {
        for (pending in listOf(true, false)) {
            for (lock in listOf(false, true)) {
                withMaster { h ->
                    if (pending) h.host.openGate.hold()
                    h.startHost()
                    if (lock) {
                        h.coordinator.lockPresentation(); h.pump()
                        assertTrue(h.view.locked)
                        assertNull(h.view.lan.endpoint)
                    } else {
                        val subscriber = launch { h.coordinator.state.collect { } }
                        h.pump(); subscriber.cancelAndJoin(); h.pump()
                    }
                    assertEquals(0, h.events.count("Host.close.enter"))
                    if (pending) { h.host.openGate.release(); h.idle() }
                    assertEquals(LocalSetupLanStatus.LISTENING, h.view.lan.status)
                    if (lock) {
                        assertNull(h.view.lan.endpoint, "Locked presentation cannot leak the actual endpoint")
                        h.coordinator.resumePresentation(); h.pump()
                    }
                    assertFalse(h.view.locked)
                    assertEquals(frozen.actualHostEndpoint, h.view.lan.endpoint)
                    h.startHost(); h.idle()
                    h.assertSingleHostScript()
                    h.assertMasterPreserved()
                }
            }
        }
        // Subscriber lifetime is the common boundary; Compose disposal and logout stay unrun.
    }

    @Test fun c09_definitiveControlledReadConstructionOrOpenFailureIsUnavailableAndNotRetryable() = runTest {
        for (stage in frozen.waiterStages) {
            withMaster { h ->
                h.host.failedStage = stage
                h.startHost(); h.idle()
                assertEquals(LocalSetupLanStatus.UNAVAILABLE, h.view.lan.status)
                assertEquals(LocalSetupProblem.NATIVE_UNAVAILABLE, h.view.problem)
                assertNull(h.view.lan.endpoint)
                val before = h.snapshot()
                h.startHost(); h.idle()
                h.coordinator.startLanHost(frozen.hostConflicts.first().input); h.idle()
                h.coordinator.refreshLanAttachment(); h.idle()
                h.assertNoNewEffects(before)
                assertEquals(1, h.events.count("Host.retain"))
                assertEquals(if (stage == "original_read_admitted") 0 else 1, h.events.count("script.HostFactory.create.enter"))
                assertEquals(if (stage == "lan_open_admitted") 1 else 0, h.events.count("script.LAN.open.enter"))
                h.assertMasterPreserved()
            }
        }
    }

    @Test fun c09_uncapturedConstructionOutcomeRemainsUnknownWithoutReplacementOrFalseClose() = runTest {
        withMaster(expectUnconfirmed = true) { h ->
            h.retentionFailure = IOException("Controlled unknown construction outcome")
            h.startHost(); h.idle()
            assertEquals(LocalSetupLanStatus.UNKNOWN, h.view.lan.status)
            assertEquals(LocalSetupProblem.CLOSE_UNCONFIRMED, h.view.problem)
            val before = h.snapshot()
            h.startHost(); h.idle()
            h.coordinator.startLanHost(frozen.hostConflicts.first().input); h.idle()
            h.coordinator.refreshLanAttachment(); h.idle()
            h.assertNoNewEffects(before)
            h.coordinator.closeLanAttachment(); h.idle()
            assertEquals(LocalSetupLanStatus.UNKNOWN, h.view.lan.status)
            assertEquals(0, h.events.count("Host.close.enter"))
            assertEquals(1, h.events.count("Host.retain"))
            h.assertMasterPreserved()
        }
    }

    @Test fun c09_failedExactCloseRetainsUnknownAndCannotBeRearmed() = runTest {
        withMaster(expectUnconfirmed = true) { h ->
            h.activeHost()
            h.host.closeFailure = IOException("Controlled close confirmation unknown")
            h.coordinator.closeLanAttachment(); h.idle()
            assertEquals(LocalSetupLanStatus.UNKNOWN, h.view.lan.status)
            assertEquals(LocalSetupProblem.CLOSE_UNCONFIRMED, h.view.problem)
            assertEquals(1, h.events.count("Host.close.enter"))
            assertEquals(0, h.events.count("Host.close.confirmed"))
            val before = h.snapshot()
            h.coordinator.closeLanAttachment(); h.idle()
            h.startHost(); h.idle()
            h.coordinator.startLanHost(frozen.hostConflicts.first().input); h.idle()
            h.coordinator.refreshLanAttachment(); h.idle()
            h.assertNoNewEffects(before)
            h.assertMasterPreserved()
        }
    }

    @Test fun c10_setupRetirementDrainsEachControlledStageAndWaitsForBothExactCloses() = runTest {
        for (stage in frozen.waiterStages) {
            withMaster { h ->
                h.host.hold(stage); h.host.closeGate.hold(); h.master.closeGate.hold()
                h.startHost()
                val result = h.coordinator.requestRetirement()
                assertSame(result, h.coordinator.requestRetirement())
                h.pump()
                assertFalse(result.isCompleted)
                assertNull(h.view.lan.endpoint)
                assertEquals(0, h.events.count("Host.close.enter"), "Admitted startup must drain first")
                val before = h.snapshot()
                h.startHost(); h.coordinator.refreshLanAttachment(); h.pump()
                h.assertNoNewEffects(before)

                val afterRetirement = mutableListOf<LocalSetupView>()
                val subscriber = launch { h.coordinator.state.collect { afterRetirement += it } }
                h.pump()
                h.host.release(stage); h.pump()
                assertFalse(result.isCompleted)
                assertEquals(1, h.events.count("Host.close.enter"))
                assertEquals(0, h.events.count("Host.close.confirmed"))
                assertEquals(0, h.events.count("Master.close.enter"))
                assertEquals(0, h.events.count("setup.retirement.confirmed"))
                h.host.closeGate.release(); h.pump()
                assertFalse(result.isCompleted)
                assertEquals(1, h.events.count("Host.close.confirmed"))
                assertEquals(1, h.events.count("Master.close.enter"))
                h.master.closeGate.release(); h.pump(); result.await()
                assertTrue(afterRetirement.all { it.lan.endpoint == null && it.lan.status != LocalSetupLanStatus.LISTENING })
                subscriber.cancelAndJoin()
                assertTrue(h.events.index("Host.close.confirmed") < h.events.index("Master.close.enter"))
                assertTrue(h.events.index("Master.close.confirmed") < h.events.index("setup.retirement.confirmed"))
                h.assertSingleHostScript()
                h.assertNoAuthorityEffects()
                // Full setup retirement legitimately closes Master. This is not LAN-only
                // retirement/real native custody-release qualification.
            }
        }
    }

    @Test fun c11_lanOnlyCloseIsAwaitedOnceTerminalAndPreservesTheRetainedMaster() = runTest {
        withMaster { h ->
            h.activeHost(); h.host.closeGate.hold()
            h.coordinator.closeLanAttachment(); h.pump()
            assertEquals(LocalSetupLanStatus.CLOSING, h.view.lan.status)
            assertNull(h.view.lan.endpoint)
            assertEquals(1, h.events.count("Host.close.enter"))
            assertEquals(0, h.events.count("Host.close.confirmed"))
            h.coordinator.closeLanAttachment(); h.pump()
            assertEquals(1, h.events.count("Host.close.enter"))
            h.assertMasterPreserved()
            h.host.closeGate.release(); h.idle()
            assertEquals(LocalSetupLanStatus.CLOSED, h.view.lan.status)
            val before = h.snapshot()
            h.coordinator.closeLanAttachment(); h.idle()
            h.startHost(); h.idle()
            h.coordinator.startLanHost(frozen.hostConflicts.first().input); h.idle()
            h.coordinator.refreshLanAttachment(); h.idle()
            h.assertNoNewEffects(before)

            val reads = h.events.count("Master.setup.read")
            h.coordinator.refreshOriginalStatus(); h.idle()
            assertEquals(reads + 1, h.events.count("Master.setup.read"))
            assertEquals(LocalSetupLanStatus.CLOSED, h.view.lan.status)
            val beforeReopen = h.snapshot()
            h.coordinator.openOriginalFirstAccount(); h.idle()
            h.assertNoNewEffects(beforeReopen)
            h.assertMasterPreserved()
            h.assertSingleHostScript()
        }
    }

    @Test fun c12_explicitRefreshReobservesTheSamePortAndLossDoesNotReconnectOrRetarget() = runTest {
        withMaster { h ->
            h.activeHost()
            repeat(2) { h.coordinator.refreshLanAttachment(); h.idle() }
            assertEquals(2, h.events.count("Host.observe.enter"))
            assertEquals(2, h.events.count("Host.observe.confirmed"))
            assertEquals(frozen.actualHostEndpoint, h.view.lan.endpoint)
            h.assertSingleHostScript()
            h.host.observationFailure = IOException("Controlled transport unavailable")
            h.coordinator.refreshLanAttachment(); h.idle()
            assertEquals(LocalSetupLanStatus.UNAVAILABLE, h.view.lan.status)
            assertNull(h.view.lan.endpoint)
            val before = h.snapshot()
            repeat(2) { h.coordinator.refreshLanAttachment(); h.idle() }
            h.coordinator.startLanHost(frozen.hostFixed); h.idle()
            h.pump(); h.pump()
            h.assertNoNewEffects(before)
            assertEquals(3, h.events.count("Host.observe.enter"))
            h.assertMasterPreserved()
        }
    }

    @Test fun c12_changedObservedPortCannotReplaceTheOriginalActualEndpoint() = runTest {
        withMaster { h ->
            h.activeHost()
            h.host.nextObservation = frozen.actualHostEndpoint.copy(
                port = frozen.hostConflicts.single { it.id == "listener" }.input.listenPort)
            h.coordinator.refreshLanAttachment(); h.idle()
            assertEquals(LocalSetupLanStatus.UNAVAILABLE, h.view.lan.status)
            assertNull(h.view.lan.endpoint)
            h.assertSingleHostScript()
            h.assertMasterPreserved()
        }
    }

    @Test fun c13_transportOnlyPublishesNoAuthorityOrLaunchConsentAndFreshScopeIsUnattached() = runTest {
        withMaster { h ->
            h.activeHost()
            assertFalse(h.view.localClientConnected)
            assertNull(h.view.employeeEnrollment)
            assertNull(h.view.employeePolicy)
            assertNull(h.view.deviceAdmission)
            assertNull(h.launchStorage.bytes)
            h.assertNoAuthorityEffects()
            h.coordinator.closeLanAttachment(); h.idle()
            h.assertMasterPreserved()
        }
        withHarness { fresh ->
            // Same unchanged absent launch-record DATA; no durable/process restart is claimed.
            fresh.coordinator.reopenRetainedMaster(); fresh.idle()
            assertEquals(LocalSetupLanStatus.NOT_ATTACHED, fresh.view.lan.status)
            assertNull(fresh.view.lan.endpoint)
            assertEquals(0, fresh.events.count("Host.retain"))
            assertEquals(0, fresh.events.count("setup.master.open-existing"))
            fresh.assertNoAuthorityEffects()
        }
    }

    @Test fun c04_missingTargetOrApprovedMasterDataCannotAuthorizeAClientAttachment() = runTest {
        withHarness { h ->
            h.chooseFreshTargetWithoutNativeOwner()
            val before = h.snapshot()
            repeat(2) { h.coordinator.connectLanClient(frozen.clientPrimary, frozen.clientEndpoint); h.idle() }
            h.assertNoNewEffects(before)
            assertEquals(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED, h.view.problem)
            assertEquals(LocalSetupLanStatus.NOT_ATTACHED, h.view.lan.status)
        }
        for (failedTargetRead in listOf(false, true)) {
            withHarness { h ->
                h.seedResponseData(); h.fresh.originalFailure = failedTargetRead; h.recoverFreshTarget()
                val originalView = assertNotNull(h.view.freshDevicePairing)
                val retainedRecord = assertNotNull(h.pairingIntents.target())
                val before = h.snapshot()
                repeat(2) { h.coordinator.connectLanClient(frozen.clientPrimary, frozen.clientEndpoint); h.idle() }
                h.coordinator.connectLanClient(frozen.hostConflicts.first().input, frozen.clientEndpoint); h.idle()
                h.assertNoNewEffects(before)
                assertEquals(originalView.targetReference, h.view.freshDevicePairing?.targetReference)
                assertEquals(originalView.identity, h.view.freshDevicePairing?.identity)
                assertEquals(retainedRecord, h.pairingIntents.target())
                assertFalse(h.view.lan.clientAvailable)
                assertEquals(LocalSetupLanStatus.NOT_ATTACHED, h.view.lan.status)
                assertEquals(1, h.events.count("setup.fresh-target.open-existing"))
                assertEquals(0, h.events.count("FreshTarget.close.enter"))
                assertEquals(0, h.events.count("FreshTarget.deny"))
                h.assertNoAuthorityEffects()
                // This retained-source refusal is before the native LAN-transfer seam;
                // it does not execute either native original read or genuine approval.
            }
        }
    }

    @Test fun c04_v11_terminalCleanupOfTheExactUnapprovedSourceWaitsForCloseOnce() = runTest {
        withHarness { h ->
            h.seedResponseData(); h.fresh.originalFailure = true; h.recoverFreshTarget()
            h.coordinator.connectLanClient(frozen.clientPrimary, frozen.clientEndpoint); h.idle()
            h.fresh.closeGate.hold()
            val result = h.coordinator.requestRetirement()
            assertSame(result, h.coordinator.requestRetirement())
            h.pump()
            assertFalse(result.isCompleted)
            assertEquals(1, h.events.count("FreshTarget.close.enter"))
            assertEquals(0, h.events.count("FreshTarget.close.confirmed"))
            assertEquals(0, h.events.count("setup.retirement.confirmed"))
            val before = h.snapshot()
            h.coordinator.connectLanClient(frozen.clientPrimary, frozen.clientEndpoint); h.pump()
            h.assertNoNewEffects(before)
            h.fresh.closeGate.release(); h.pump(); result.await()
            assertEquals(1, h.events.count("FreshTarget.close.confirmed"))
            assertEquals(1, h.events.count("setup.fresh-target.open-existing"))
            assertEquals(LocalSetupPhase.CLOSED, h.view.phase)
            assertEquals(LocalSetupLanStatus.NOT_ATTACHED, h.view.lan.status)
            h.assertNoAuthorityEffects()
            // Only common setup completion is observed; real native custody release stays Q02/Q04.
        }
    }

    private fun assertRefusedHost(h: ApplicationLanAttachmentCoordinatorHarness, input: LocalSetupLanInput) {
        val before = h.snapshot()
        h.coordinator.startLanHost(input); h.idle()
        assertEquals(LocalSetupProblem.INVALID_INPUT, h.view.problem)
        assertEquals(LocalSetupLanStatus.NOT_ATTACHED, h.view.lan.status)
        assertNull(h.view.lan.endpoint)
        h.assertNoNewEffects(before)
        h.assertMasterPreserved()
    }
    private fun assertRefusedClient(h: ApplicationLanAttachmentCoordinatorHarness,
        input: LocalSetupLanInput, endpoint: LocalSetupLanEndpoint) {
        val before = h.snapshot()
        h.coordinator.connectLanClient(input, endpoint); h.idle()
        assertEquals(LocalSetupProblem.INVALID_INPUT, h.view.problem)
        assertEquals(LocalSetupLanStatus.NOT_ATTACHED, h.view.lan.status)
        assertNull(h.view.lan.endpoint)
        h.assertNoNewEffects(before)
        h.assertNoAuthorityEffects()
    }
    private suspend fun TestScope.withMaster(expectUnconfirmed: Boolean = false,
        block: suspend (ApplicationLanAttachmentCoordinatorHarness) -> Unit) =
        withHarness(expectUnconfirmed) { h -> h.openExistingMaster(); block(h) }
    private suspend fun TestScope.withHarness(expectUnconfirmed: Boolean = false,
        block: suspend (ApplicationLanAttachmentCoordinatorHarness) -> Unit) {
        val harness = ApplicationLanAttachmentCoordinatorHarness(this)
        try { block(harness) } finally { harness.finish(expectUnconfirmed) }
    }
}
