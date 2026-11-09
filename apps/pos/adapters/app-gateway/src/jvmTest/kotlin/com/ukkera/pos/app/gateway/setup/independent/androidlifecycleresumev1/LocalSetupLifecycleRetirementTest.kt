package com.ukkera.pos.app.gateway.setup.independent.androidlifecycleresumev1

import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchStorage
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentRead
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStorage
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.app.gateway.setup.LocalSetupClient
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.app.gateway.setup.LocalSetupEmployeeConnection
import com.ukkera.pos.app.gateway.setup.LocalSetupExecution
import com.ukkera.pos.app.gateway.setup.LocalSetupIntentStorage
import com.ukkera.pos.app.gateway.setup.LocalSetupLifecycleMaster
import com.ukkera.pos.app.gateway.setup.LocalSetupLifecycleOpenResult
import com.ukkera.pos.app.gateway.setup.LocalSetupMaster
import com.ukkera.pos.app.gateway.setup.LocalSetupMasterOpenResult
import com.ukkera.pos.app.gateway.setup.LocalSetupNativeServices
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalBranchInstallationDefinitionV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthorityView
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalMasterLifecycleAction
import com.ukkera.pos.security.LocalMasterLifecycleIntent
import com.ukkera.pos.security.LocalMasterLifecycleDenial
import com.ukkera.pos.security.LocalMasterLifecycleReceipt
import com.ukkera.pos.security.LocalMasterLifecycleRequest
import com.ukkera.pos.security.LocalMasterLifecycleResult
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeBindingBodyV1
import com.ukkera.pos.security.SelfOwnedNativeBindingV1
import com.ukkera.pos.security.SelfOwnedNativeContextController
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeInstallationWireV1
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.SelfOwnedNativeReadResult
import com.ukkera.pos.security.SelfOwnedNativeSecurityV1
import com.ukkera.pos.security.SelfOwnedNativeWithdrawal
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.SelfOwnedStorageKeySecurity
import com.ukkera.pos.security.SelfOwnedTargetDescriptorV1
import com.ukkera.pos.security.lifecycleCoordinatesDigest
import com.ukkera.pos.security.lifecycleRequestDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * ALR2-S01..S03/S05 over the real production coordinator, launch record and lifecycle-intent
 * store. Native acquisition/material, lifecycle master and receipt outputs are explicitly
 * controlled ports; no native engine, OS lifecycle, real master or admitted RPC is present.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LocalSetupLifecycleRetirementTest {
    @Test
    fun publicIntentionalRetirementIsRefusedWithoutLaunchWriteOrNativeClose() = runTest {
        val h = Harness(this, InitialLaunch.RUNNING)
        h.reopenRetained()
        val writesBefore = h.launchStorage.writes
        val viewBefore = h.coordinator.state.value

        val refused = h.coordinator.requestRetirement(intentional = true)

        assertFailsWith<IllegalStateException>("ALR2-S01 refusal diagnostic") { refused.getCompleted() }
        assertTrue(refused.isCompleted, "ALR2-S01 refusal did not settle")
        assertEquals(writesBefore, h.launchStorage.writes, "ALR2-S01 public intentional retirement wrote launch bytes")
        assertIs<LocalMasterLaunchRecord.Read.Running>(h.launchRecord.read(),
            "ALR2-S01 public intentional retirement changed the launch projection")
        assertEquals(0, h.beforeRetirementCalls, "ALR2-S01 public intentional retirement entered retirement")
        assertEquals(0, h.master.closeCalls, "ALR2-S01 public intentional retirement closed the master")
        assertEquals(0, h.services.withdrawals, "ALR2-S01 public intentional retirement withdrew the client")
        assertEquals(viewBefore.phase, h.coordinator.state.value.phase,
            "ALR2-S01 public intentional retirement changed the visible phase")
        assertNull(h.intentStorage.bytes, "ALR2-S01 public intentional retirement minted an intent")
        h.dispose()
    }

    @Test
    fun cachedRetirementJoinsOneObserverWithoutSecondWriteOrClose() = runTest {
        val h = Harness(this, InitialLaunch.RUNNING)
        h.reopenRetained()
        val writesBefore = h.launchStorage.writes

        val first = h.coordinator.requestRetirement(intentional = false)
        val intentionalObserver = h.coordinator.requestRetirement(intentional = true)
        val nonIntentionalObserver = h.coordinator.requestRetirement(intentional = false)

        assertSame(first, intentionalObserver, "ALR2-S01 intentional repeat returned another completion")
        assertSame(first, nonIntentionalObserver, "ALR2-S01 repeat returned another completion")
        assertEquals(1, h.beforeRetirementCalls, "ALR2-S01 repeat re-entered retirement")
        h.scheduler.advanceUntilIdle()
        assertTrue(first.isCompleted, "ALR2-S01 cached retirement did not settle")
        assertEquals(writesBefore, h.launchStorage.writes, "ALR2-S01 non-intentional retirement wrote STOP")
        assertEquals(1, h.master.closeCalls, "ALR2-S01 repeat closed the master again")
        assertEquals(1, h.services.withdrawals, "ALR2-S01 repeat withdrew the client again")
        assertEquals(LocalSetupPhase.CLOSED, h.coordinator.state.value.phase,
            "ALR2-S01 retirement did not reach CLOSED")
        val completedObserver = h.coordinator.requestRetirement(intentional = true)
        assertSame(first, completedObserver, "ALR2-S01 settled intentional repeat returned another completion")
        assertEquals(writesBefore, h.launchStorage.writes, "ALR2-S01 settled repeat wrote launch bytes")
    }

    @Test
    fun authenticatedStopRejectsAnyReceiptFieldMismatchWithoutStopOrClose() = runTest {
        for (variant in MISMATCHES) {
            val h = Harness(this, InitialLaunch.RUNNING)
            h.reopenRetained()
            h.master.result = { request -> h.receipt(request, variant) }
            val writesBefore = h.launchStorage.writes
            h.coordinator.authenticateAndStopLocalMaster("synthetic-head", "synthetic-password".toCharArray())
            h.scheduler.advanceUntilIdle()

            assertEquals(LocalSetupProblem.STATE_UNCERTAIN, h.coordinator.state.value.problem,
                "ALR2-S02 $variant receipt was accepted")
            assertEquals(writesBefore, h.launchStorage.writes, "ALR2-S02 $variant wrote launch bytes")
            assertIs<LocalMasterLaunchRecord.Read.Running>(h.launchRecord.read(),
                "ALR2-S02 $variant changed the launch projection")
            assertEquals(0, h.beforeRetirementCalls, "ALR2-S02 $variant entered retirement")
            assertEquals(0, h.master.closeCalls, "ALR2-S02 $variant closed the master")
            assertEquals(0, h.services.withdrawals, "ALR2-S02 $variant withdrew the client")
            assertEquals(LocalMasterLifecycleAction.STOP, h.master.lastRequest?.action,
                "ALR2-S02 $variant dispatched another action")
            val retained = assertIs<LocalMasterLifecycleIntentRead.Retained>(h.intentStore.read(),
                "ALR2-S02 $variant cleared the retained intent").intent
            assertEquals(LocalMasterLifecycleAction.STOP, retained.request.action,
                "ALR2-S02 $variant replaced the retained action")
            assertTrue(retained.matches(h.coordinates, retained.request),
                "ALR2-S02 $variant replaced the retained request identity")
            h.dispose()
        }
    }

    @Test
    fun authenticatedStopWithExactReceiptWritesStopAndClearsRetainedIntent() = runTest {
        val h = Harness(this, InitialLaunch.RUNNING)
        h.reopenRetained()
        h.master.result = { request -> h.receipt(request, "exact") }
        val writesBefore = h.launchStorage.writes
        h.coordinator.authenticateAndStopLocalMaster("synthetic-head", "synthetic-password".toCharArray())
        h.scheduler.advanceUntilIdle()

        assertEquals(1, h.master.applyCalls, "ALR2-S02 STOP was not dispatched")
        assertEquals(LocalMasterLifecycleAction.STOP, h.master.lastRequest?.action,
            "ALR2-S02 wrong action dispatched")
        assertEquals(LocalMasterLaunchRecord.Read.Stopped, h.launchRecord.read(),
            "ALR2-S02 exact STOP receipt did not write STOP")
        assertEquals(writesBefore + 1, h.launchStorage.writes, "ALR2-S02 STOP write count")
        assertEquals(LocalMasterLifecycleIntentRead.Absent, h.intentStore.read(),
            "ALR2-S02 exact STOP did not retire the intent")
        assertEquals(1, h.beforeRetirementCalls, "ALR2-S02 exact STOP did not retire")
        assertEquals(1, h.master.closeCalls, "ALR2-S02 exact STOP did not close the master")
        assertEquals(1, h.services.withdrawals, "ALR2-S02 exact STOP did not withdraw the client")
        assertEquals(LocalSetupPhase.CLOSED, h.coordinator.state.value.phase,
            "ALR2-S02 exact STOP did not reach CLOSED")
        assertTrue(h.retirementDeferred?.isCompleted == true, "ALR2-S02 retirement completion is unresolved")
    }

    @Test
    fun authenticatedResumeRejectsAnyReceiptFieldMismatchAndKeepsStop() = runTest {
        for (variant in MISMATCHES) {
            val h = Harness(this, InitialLaunch.STOPPED)
            h.lifecycle.result = { request -> h.receipt(request, variant) }
            val writesBefore = h.launchStorage.writes
            h.coordinator.authenticateAndResumeLocalMaster("synthetic-head", "synthetic-password".toCharArray())
            h.scheduler.advanceUntilIdle()

            assertEquals(LocalSetupProblem.STATE_UNCERTAIN, h.coordinator.state.value.problem,
                "ALR2-S03 $variant receipt was accepted")
            assertTrue(h.coordinator.state.value.lifecycleStopped,
                "ALR2-S03 $variant cleared the stopped posture")
            assertEquals(LocalMasterLaunchRecord.Read.Stopped, h.launchRecord.read(),
                "ALR2-S03 $variant wrote RUN")
            assertEquals(writesBefore, h.launchStorage.writes, "ALR2-S03 $variant wrote launch bytes")
            assertEquals(1, h.services.openLifecycleCalls, "ALR2-S03 $variant did not open the pre-start owner")
            assertEquals(0, h.services.openMasterCalls, "ALR2-S03 $variant opened an operational master")
            assertEquals(1, h.lifecycle.closeCalls, "ALR2-S03 $variant retained the pre-start owner")
            assertEquals(LocalMasterLifecycleAction.RESUME, h.lifecycle.lastRequest?.action,
                "ALR2-S03 $variant dispatched another action")
            val retained = assertIs<LocalMasterLifecycleIntentRead.Retained>(h.intentStore.read(),
                "ALR2-S03 $variant cleared the retained intent").intent
            assertEquals(LocalMasterLifecycleAction.RESUME, retained.request.action,
                "ALR2-S03 $variant replaced the retained action")
            h.dispose()
        }
    }

    @Test
    fun authenticatedResumeWithExactReceiptWritesRunAndClearsIntent() = runTest {
        val h = Harness(this, InitialLaunch.STOPPED)
        h.lifecycle.result = { request -> h.receipt(request, "exact") }
        val writesBefore = h.launchStorage.writes

        h.coordinator.authenticateAndResumeLocalMaster("synthetic-head", "synthetic-password".toCharArray())
        h.scheduler.advanceUntilIdle()

        assertEquals(1, h.lifecycle.applyCalls, "ALR2-S03 RESUME was not dispatched")
        assertEquals(LocalMasterLifecycleAction.RESUME, h.lifecycle.lastRequest?.action,
            "ALR2-S03 wrong action dispatched")
        assertEquals(1, h.lifecycle.closeCalls, "ALR2-S03 pre-start owner was not closed")
        val running = assertIs<LocalMasterLaunchRecord.Read.Running>(h.launchRecord.read(),
            "ALR2-S03 exact RESUME did not write RUN")
        assertTrue(running.matches(h.coordinates), "ALR2-S03 RUN does not match the original")
        assertEquals(writesBefore + 1, h.launchStorage.writes, "ALR2-S03 RUN write count")
        assertEquals(LocalMasterLifecycleIntentRead.Absent, h.intentStore.read(),
            "ALR2-S03 exact RESUME did not retire the intent")
        assertEquals(2, h.services.openTargetCalls, "ALR2-S03 operational same-original reopen was not attempted")
        assertEquals(1, h.services.openMasterCalls, "ALR2-S03 operational same-original open was not attempted")
        assertEquals(1, h.beforeClientHostingCalls, "ALR2-S03 exact RESUME did not attempt publication")
        assertTrue(!h.coordinator.state.value.lifecycleStopped,
            "ALR2-S03 exact RESUME left the visible stopped posture")
        assertEquals(LocalSetupPhase.INSTALLATION_RETAINED, h.coordinator.state.value.phase,
            "ALR2-S03 exact RESUME did not retain the reopened original")
        h.dispose()
    }

    @Test
    fun deniedNotAuthorizedMapsToOriginalActionRequiredWithoutStopEffects() = runTest {
        val h = Harness(this, InitialLaunch.RUNNING)
        h.reopenRetained()
        h.master.result = { LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.NOT_AUTHORIZED) }
        val writesBefore = h.launchStorage.writes

        h.coordinator.authenticateAndStopLocalMaster("synthetic-head", "synthetic-password".toCharArray())
        h.scheduler.advanceUntilIdle()

        assertEquals(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED, h.coordinator.state.value.problem,
            "ALR2-S05 NOT_AUTHORIZED diagnostic mapping")
        assertEquals(writesBefore, h.launchStorage.writes, "ALR2-S05 denial wrote launch bytes")
        assertIs<LocalMasterLaunchRecord.Read.Running>(h.launchRecord.read(),
            "ALR2-S05 denial changed the launch projection")
        assertIs<LocalMasterLifecycleIntentRead.Retained>(h.intentStore.read(),
            "ALR2-S05 denial cleared the retained intent")
        assertEquals(0, h.beforeRetirementCalls, "ALR2-S05 denial entered retirement")
        assertEquals(0, h.master.closeCalls, "ALR2-S05 denial closed the master")
        h.dispose()
    }

    private enum class InitialLaunch { RUNNING, STOPPED }

    private class Harness(test: TestScope, initial: InitialLaunch) {
        val scheduler = test.testScheduler
        val coordinates = coordinates()
        val launchStorage = MemoryLaunchStorage()
        val launchRecord = LocalMasterLaunchRecord(launchStorage)
        val intentStorage = MemoryIntentStorage()
        val intentStore = LocalMasterLifecycleIntentStore(intentStorage)
        val stopBindingStore = LocalMasterLifecycleIntentStore(MemoryIntentStorage())
        val master = ControlledMaster()
        val lifecycle = ControlledLifecycle()
        val services = ControlledServices()
        var beforeRetirementCalls = 0
        var beforeClientHostingCalls = 0
        var retirementDeferred: Deferred<Unit>? = null

        val coordinator = LocalSetupCoordinator(
            services = services,
            employeeSetupIntents = LocalEmployeeSetupIntents(NoopIntentStorage),
            launchRecord = launchRecord,
            execution = TestExecution(scheduler),
            beforeRetirement = { _, result -> beforeRetirementCalls++; retirementDeferred = result },
            beforeClientHosting = { beforeClientHostingCalls++ },
            lifecycleIntents = intentStore,
            stopBindingIntents = stopBindingStore,
        )

        init {
            when (initial) {
                InitialLaunch.RUNNING -> launchRecord.retainRunning(coordinates)
                InitialLaunch.STOPPED -> {
                    // Controlled retained binding for the same synthetic original. This
                    // fixture does not claim a native durable STOP or lifecycle audit.
                    stopBindingStore.retain(LocalMasterLifecycleIntent.create(coordinates,
                        LocalMasterLifecycleRequest(testId(2_994), LocalMasterLifecycleAction.STOP)))
                    launchRecord.retainStop()
                }
            }
        }

        fun reopenRetained() {
            coordinator.reopenRetainedMaster()
            scheduler.advanceUntilIdle()
            assertEquals(LocalSetupPhase.INSTALLATION_RETAINED, coordinator.state.value.phase,
                "controlled retained original did not open")
            coordinator.resumePresentation()
            assertEquals(false, coordinator.state.value.locked,
                "controlled retained original remained locked")
        }

        fun dispose() {
            if (coordinator.state.value.phase != LocalSetupPhase.CLOSED) {
                coordinator.requestRetirement(intentional = false)
                scheduler.advanceUntilIdle()
            }
        }

        fun receipt(request: LocalMasterLifecycleRequest, variant: String): LocalMasterLifecycleResult {
            val eventId = if (variant == "event") testId(2_998) else request.requestId
            val action = if (variant == "action") {
                if (request.action == LocalMasterLifecycleAction.STOP) LocalMasterLifecycleAction.RESUME
                else LocalMasterLifecycleAction.STOP
            } else request.action
            val installation = if (variant == "installation") testId(2_997) else coordinates.installationId
            val coordinatesDigest = if (variant == "coordinates") sha256("wrong coordinates".encodeToByteArray())
                else lifecycleCoordinatesDigest(coordinates)
            val requestDigest = if (variant == "request") sha256("wrong request".encodeToByteArray())
                else lifecycleRequestDigest(coordinates, request)
            return LocalMasterLifecycleResult.Committed(LocalMasterLifecycleReceipt(eventId, action,
                testId(2_012), testId(2_996), installation, coordinatesDigest, requestDigest,
                Revision(4), Revision(3), MonotonicMoment(testId(2_995), 1_000)))
        }

        inner class ControlledServices : LocalSetupNativeServices {
            var withdrawals = 0
            var openTargetCalls = 0
            var openLifecycleCalls = 0
            var openMasterCalls = 0

            override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult =
                LocalProvisionerOpenResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)

            override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult {
                openTargetCalls++
                check(!preparation)
                return SelfOwnedNativeOpenResult.Opened(SelfOwnedLocalSecurityContext(ControlledContext()))
            }

            override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult {
                openMasterCalls++
                return LocalSetupMasterOpenResult.Opened(master)
            }

            override suspend fun openInstalledLifecycle(
                context: SelfOwnedLocalSecurityContext): LocalSetupLifecycleOpenResult {
                openLifecycleCalls++
                return LocalSetupLifecycleOpenResult.Opened(lifecycle)
            }

            override suspend fun openOriginalReceipt(
                context: SelfOwnedLocalSecurityContext) = LocalMasterInstallationReceiptOpenResult.Unresolved

            override suspend fun connectClient(master: LocalSetupMaster,
                action: LocalOriginalClientJournalAction): LocalSetupClient? = null

            override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean = false

            override fun publishClient(owner: Any, client: LocalSetupClient) = Unit

            override fun withdrawClient(owner: Any) { withdrawals++ }
        }

        private inner class ControlledContext : SelfOwnedNativeContextController {
            override val compositionProfile: com.ukkera.pos.security.LocalCompositionSdkProfileV1
                get() = com.ukkera.pos.security.LocalMasterSdkProfileV1
            private val binding = binding(coordinates)
            override val materialAtOpen: SelfOwnedNativeMaterialView = material(coordinates, binding)

            override suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult =
                SelfOwnedNativeReadResult.Retained(materialAtOpen, binding)

            override suspend fun withdrawNewCeremonies(): SelfOwnedNativeWithdrawal =
                SelfOwnedNativeWithdrawal.Retained

            override fun denyContextNewCeremonies() = Unit
            override fun denyContextDataUse() = Unit
            override suspend fun closeContext() = Unit

            override suspend fun claimOriginalHistoryCustody(): SelfOwnedNativeHistoryClaimResult =
                SelfOwnedNativeHistoryClaimResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        }
    }

    private class ControlledMaster : LocalSetupMaster {
        override val mode: StateFlow<LocalMasterLifecycleMode> =
            MutableStateFlow(LocalMasterLifecycleMode.OPERATIONAL)
        var applyCalls = 0
        var closeCalls = 0
        var lastRequest: LocalMasterLifecycleRequest? = null
        var result: (LocalMasterLifecycleRequest) -> LocalMasterLifecycleResult = { LocalMasterLifecycleResult.Unavailable }

        override suspend fun permitsFirstClientEnrollment(): Boolean = false
        override suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning,
            authorityEpoch: Revision): LocalBranchInstallationSnapshotV2 = unsupported()
        override suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2, headLogin: String,
            headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome = unsupported()
        override suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome = unsupported()
        override suspend fun readOriginalSetup(): LocalOriginalInstallationSetup = setup()

        override suspend fun authenticateAndApplyLifecycle(login: LocalLoginIdentifier, password: CharArray,
            request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult {
            applyCalls++
            lastRequest = request
            return result(request)
        }

        override fun denyNewWork() = Unit
        override suspend fun quiesceAndClose(): Boolean { closeCalls++; return true }
    }

    private class ControlledLifecycle : LocalSetupLifecycleMaster {
        override val mode: StateFlow<LocalMasterLifecycleMode> =
            MutableStateFlow(LocalMasterLifecycleMode.OPERATIONAL)
        var applyCalls = 0
        var closeCalls = 0
        var lastRequest: LocalMasterLifecycleRequest? = null
        var result: (LocalMasterLifecycleRequest) -> LocalMasterLifecycleResult = { LocalMasterLifecycleResult.Unavailable }

        override suspend fun authenticateAndApplyLifecycle(login: LocalLoginIdentifier, password: CharArray,
            request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult {
            applyCalls++
            lastRequest = request
            return result(request)
        }

        override fun denyNewWork() = Unit
        override suspend fun quiesceAndClose(): Boolean { closeCalls++; return true }
    }

    private class TestExecution(private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) :
        LocalSetupExecution {
        override val dispatcher = StandardTestDispatcher(scheduler)
        override fun elapsedMillis(): Long = scheduler.currentTime
        override fun startWatching(tick: () -> Unit) = Unit
        override fun stopWatching() = Unit
    }

    private class MemoryLaunchStorage : LocalMasterLaunchStorage {
        private var bytes: ByteArray? = null
        var writes = 0
            private set

        override fun read(): ByteArray? = bytes?.copyOf()
        override fun replaceAndForce(bytes: ByteArray) {
            writes++
            this.bytes = bytes.copyOf()
        }
    }

    private class MemoryIntentStorage : LocalMasterLifecycleIntentStorage {
        var bytes: ByteArray? = null
            private set
        var writes = 0
            private set
        var clears = 0
            private set

        override fun read(): ByteArray? = bytes?.copyOf()
        override fun readInterruptedWrite(): ByteArray? =
            throw UnsupportedOperationException("ALR2 fixture does not model interrupted writes")
        override fun commitRecovered(bytes: ByteArray): Unit =
            throw UnsupportedOperationException("ALR2 fixture does not model interrupted writes")
        override fun replaceAndForce(bytes: ByteArray) {
            writes++
            this.bytes = bytes.copyOf()
        }
        override fun clearAndForce() {
            clears++
            bytes = null
        }
    }

    private object NoopIntentStorage : LocalSetupIntentStorage {
        override fun names(): List<String> = emptyList()
        override fun read(name: String): ByteArray = error("employee setup intent is not used")
        override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) =
            error("employee setup intent is not used")
        override fun verify() = Unit
    }

    private companion object {
        private val MISMATCHES = listOf("event", "action", "installation", "coordinates", "request")

        private fun testId(value: Int): DomainId =
            DomainId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")

        private fun coordinates() = SelfOwnedAllocationCoordinatesV1(
            testId(2_001), BranchScope(testId(2_002), testId(2_003), testId(2_004)),
            testId(2_005), testId(2_006), testId(2_007), testId(2_008), testId(2_009),
            testId(2_010), testId(2_011), testId(2_012),
        )

        private val target = SelfOwnedTargetDescriptorV1("synthetic.pos.lifecycle", 12_000,
            "a".repeat(64), "b".repeat(64))

        private fun binding(coordinates: SelfOwnedAllocationCoordinatesV1): SelfOwnedNativeBindingV1 {
            val point = ByteArray(65) { index -> if (index == 0) 4 else (0x20 + index).toByte() }
            val body = try {
                SelfOwnedNativeInstallationWireV1.encodeBindingBody(SelfOwnedNativeBindingBodyV1(
                    coordinates, target, "c".repeat(64), "d".repeat(64), "e".repeat(64),
                    "f".repeat(64), SelfOwnedOctetsV1(point, 65), SelfOwnedKeyProtectionV1.OS_ISOLATED,
                    SelfOwnedNativeSecurityV1.SOFTWARE, SelfOwnedNativeSecurityV1.SOFTWARE,
                    SelfOwnedNativeSecurityV1.SOFTWARE))
            } finally { point.fill(0) }
            val signature = ByteArray(64) { 0x33 }
            return try { SelfOwnedNativeBindingV1(body, signature) } finally {
                body.fill(0); signature.fill(0)
            }
        }

        private fun material(coordinates: SelfOwnedAllocationCoordinatesV1,
            binding: SelfOwnedNativeBindingV1): SelfOwnedNativeMaterialView {
            val bytes = binding.copyWireBytes()
            val digest = try { sha256(bytes).value } finally { bytes.fill(0) }
            return SelfOwnedNativeMaterialView(coordinates, target, "Synthetic lifecycle branch",
                SelfOwnedKeyProtectionV1.OS_ISOLATED, "c".repeat(64), digest,
                SelfOwnedStorageKeySecurity.SOFTWARE, false)
        }

        private fun setup(): LocalOriginalInstallationSetup = LocalOriginalInstallationSetup(
            LocalCompanyAuthorityView(testId(2_900), testId(2_002), testId(2_003),
                "Synthetic lifecycle company", "1".repeat(64), SelfOwnedKeyProtectionV1.OS_ISOLATED,
                testId(2_901), testId(2_902), "Synthetic head", "synthetic-head", Revision(4),
                Revision(2), true, false, true, PayloadDigest("2".repeat(64))),
            null, null)

        private fun <T> unsupported(): T = throw UnsupportedOperationException("not used by this test")
    }
}
