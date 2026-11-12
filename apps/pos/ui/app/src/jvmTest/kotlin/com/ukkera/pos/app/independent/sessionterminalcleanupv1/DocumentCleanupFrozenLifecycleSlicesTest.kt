package com.ukkera.pos.app.independent.sessionterminalcleanupv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.types.DomainId
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * L1 wiring of unchanged STC-S15 / F-STC-LIFECYCLE close outcomes and the frozen
 * cleanup_failure_then_late_success schedule. The common registry and runtime are real;
 * native file outcomes and UI session projections are controlled DATA, not authentication,
 * descriptor/Keychain/DPAPI proof, durable evidence or full STC-S15 qualification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentCleanupFrozenLifecycleSlicesTest {
    @Test
    fun stcS15L1_conclusiveCloseRemovesOnlyItsCleanupObligation() = runTest {
        val native = ControlledDestination()
        val registry = registry(native)
        val held = choose(registry)
        assertFalse(registry.clear)
        assertEquals(LocalPurchasingDocumentFileIoResult.Succeeded, held.close())
        assertTrue(registry.clear)
        registry.close()
        registry.close()
        assertTrue(registry.clear)
    }

    @Test
    fun stcS15L1_failureBeforeReleaseRemainsUnconfirmedUntilSafeRetry() = runTest {
        val native = ControlledDestination(Outcome.FAIL_BEFORE_RELEASE)
        val registry = registry(native)
        choose(registry)
        registry.retire()
        assertFalse(registry.retryCleanup())
        assertFalse(registry.clear)
        assertTrue(registry.needsAttention)
        native.outcome = Outcome.CONCLUSIVE
        assertTrue(registry.retryCleanup())
        assertTrue(registry.clear)
        assertFalse(registry.needsAttention)
        registry.close()
    }

    @Test
    fun stcS15L1_partialReleaseDoesNotEstablishConfirmedCleanup() = runTest {
        val native = ControlledDestination(Outcome.ERROR_AFTER_PARTIAL_RELEASE)
        val registry = registry(native)
        choose(registry)
        registry.retire()
        assertFalse(registry.retryCleanup())
        assertFalse(registry.clear)
        assertTrue(registry.needsAttention)
        native.outcome = Outcome.CONCLUSIVE
        assertTrue(registry.retryCleanup())
        registry.close()
    }

    @Test
    fun stcS15L1_indeterminateOutcomeRetainsTheOriginalHolder() = runTest {
        val native = ControlledDestination(Outcome.INDETERMINATE)
        val registry = registry(native)
        choose(registry)
        registry.retire()
        assertFalse(registry.retryCleanup())
        assertFalse(registry.clear)
        assertTrue(registry.needsAttention)
        native.outcome = Outcome.CONCLUSIVE
        assertTrue(registry.retryCleanup())
        registry.close()
    }

    @Test
    fun stcS15L1_heldCloseCannotReportCompletionAndDuplicateRetirementKeepsItPending() = runTest {
        val native = ControlledDestination(Outcome.HELD)
        val registry = registry(native)
        choose(registry)
        try {
            registry.retire()
            native.entered.await()
            registry.retire()
            val retry = async { registry.retryCleanup() }
            runCurrent()
            assertFalse(retry.isCompleted)
            assertFalse(registry.clear)
            assertTrue(registry.needsAttention)
            native.release.complete(Unit)
            assertTrue(retry.await())
            assertTrue(registry.clear)
        } finally {
            native.release.complete(Unit)
            registry.retryCleanup()
            registry.close()
        }
    }

    @Test
    fun stcS15L1_failedOutputCleanupBlocksSuccessorPresentationUntilConclusiveRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val native = ControlledDestination(Outcome.FAIL_BEFORE_RELEASE)
        val gateway = ControlledSessions()
        val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway,
            platform = PlatformCapabilities(PlatformFamily.DESKTOP), documentFiles = ControlledFiles(native)))
        try {
            runtime.openSignIn(false, runtime.navigator.token())
            runtime.signIn("ACTOR-A", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(ACTOR_A, runtime.presentation.value.display?.actorId)
            choose(runtime.documentFiles)
            val callsBeforeTerminal = gateway.calls
            runtime.signOut()
            runCurrent()
            assertNull(runtime.presentation.value.display)
            assertFalse(runtime.documentFiles.retryCleanup())
            runtime.openSignIn(false, runtime.navigator.token())
            runtime.signIn("ACTOR-B", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(callsBeforeTerminal, gateway.calls)
            assertNull(runtime.presentation.value.display)
            assertTrue(runtime.presentation.value.cleanupNeedsAttention)
            native.outcome = Outcome.CONCLUSIVE
            assertTrue(runtime.documentFiles.retryCleanup())
            runtime.signIn("ACTOR-B", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(ACTOR_B, runtime.presentation.value.display?.actorId)
        } finally {
            native.outcome = Outcome.CONCLUSIVE
            runtime.documentFiles.retryCleanup()
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    /** STC-S03/S08/S12/S15 supporting slices: native picker/import completion is
     * controlled by the frozen late-response and failure-then-success schedules.
     * These do not prove native dialog/descriptor shutdown or real authentication. */
    @Test
    fun stcS15L1_pendingPickerCannotConfirmShutdownBeforeItsExactOutputSettles() = runTest {
        val native = ControlledDestination()
        val files = HeldSelectionFiles(native)
        val registry = LocalPurchasingDocumentCleanup(files) { }
        val choosing = async { registry.chooseDestination("A-PRIVATE-MEDIA-STC.pdf", PurchasingEvidenceMediaV1.PDF) { true } }
        try {
            files.entered.await()
            assertFalse(registry.clear)
            assertFailsWith<IllegalStateException> { registry.close() }
            assertTrue(registry.needsAttention)
            assertFalse(registry.retryCleanup())
            files.release.complete(Unit)
            assertEquals(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED,
                assertIs<LocalPurchasingDocumentFileDestinationSelection.Unavailable>(choosing.await()).problem)
            assertTrue(registry.clear)
        } finally {
            files.release.complete(Unit)
            choosing.join()
            registry.retryCleanup()
            registry.close()
        }
    }

    @Test
    fun stcS08L1_retiredImportDestroysLatePrivateSnapshotBeforeReturning() = runTest {
        val files = HeldSelectionFiles(ControlledDestination())
        val registry = LocalPurchasingDocumentCleanup(files) { }
        val importing = async { registry.importOriginal { true } }
        try {
            files.entered.await()
            registry.retire()
            files.release.complete(Unit)
            assertEquals(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED,
                assertIs<LocalPurchasingDocumentFileSelection.Unavailable>(importing.await()).problem)
            assertFailsWith<IllegalStateException> { checkNotNull(files.selected).copyBytes() }
            assertTrue(registry.clear)
        } finally {
            files.release.complete(Unit)
            importing.join()
            registry.retryCleanup()
            registry.close()
            files.selected?.destroy()
        }
    }

    @Test
    fun stcS08L1_cancelledImportDestroysUndeliveredPrivateSnapshot() = runTest {
        val files = HeldSelectionFiles(ControlledDestination())
        val registry = LocalPurchasingDocumentCleanup(files) { }
        val importing = async { registry.importOriginal { true } }
        try {
            files.entered.await()
            importing.cancel()
            files.release.complete(Unit)
            importing.join()
            assertFailsWith<IllegalStateException> { checkNotNull(files.selected).copyBytes() }
            assertTrue(registry.clear)
        } finally {
            files.release.complete(Unit)
            importing.join()
            registry.retryCleanup()
            registry.close()
            files.selected?.destroy()
        }
    }

    @Test
    fun stcS03L1_pendingPickerBlocksSuccessorUntilLateOutputCleanupIsConclusive() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val native = ControlledDestination(Outcome.FAIL_BEFORE_RELEASE)
        val files = HeldSelectionFiles(native)
        val gateway = ControlledSessions()
        val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway,
            platform = PlatformCapabilities(PlatformFamily.DESKTOP), documentFiles = files))
        val choosing = async(start = CoroutineStart.LAZY) {
            runtime.documentFiles.chooseDestination("A-PRIVATE-MEDIA-STC.pdf", PurchasingEvidenceMediaV1.PDF) {
                runtime.presentation.value.display?.actorId == ACTOR_A
            }
        }
        try {
            runtime.openSignIn(false, runtime.navigator.token())
            runtime.signIn("ACTOR-A", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(ACTOR_A, runtime.presentation.value.display?.actorId)
            choosing.start()
            files.entered.await()
            val callsBeforeTerminal = gateway.calls
            runtime.signOut()
            runCurrent()
            runtime.openSignIn(false, runtime.navigator.token())
            runtime.signIn("ACTOR-B", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(callsBeforeTerminal, gateway.calls)
            assertNull(runtime.presentation.value.display)
            assertTrue(runtime.presentation.value.cleanupNeedsAttention)
            assertFalse(runtime.documentFiles.retryCleanup())
            files.release.complete(Unit)
            assertEquals(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED,
                assertIs<LocalPurchasingDocumentFileDestinationSelection.Unavailable>(choosing.await()).problem)
            assertFalse(runtime.documentFiles.retryCleanup())
            runtime.signIn("ACTOR-B", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(callsBeforeTerminal, gateway.calls)
            native.outcome = Outcome.CONCLUSIVE
            assertTrue(runtime.documentFiles.retryCleanup())
            runtime.signIn("ACTOR-B", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(ACTOR_B, runtime.presentation.value.display?.actorId)
        } finally {
            native.outcome = Outcome.CONCLUSIVE
            files.release.complete(Unit)
            choosing.start()
            choosing.join()
            runtime.documentFiles.retryCleanup()
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    /** STC-S03/S12/S15: only the controlled native completion ends the native
     * obligation. These L1 slices do not execute any OS picker or native handle. */
    @Test
    fun stcS15L1_cancelledNativeWaitCannotConfirmShutdown() = runTest {
        val files = ControlledNativeFiles()
        val registry = LocalPurchasingDocumentCleanup(files) { }
        val importing = async { registry.importOriginal { true } }
        try {
            files.entered.await()
            importing.cancel()
            importing.join()
            assertFalse(registry.clear)
            assertFailsWith<IllegalStateException> { registry.close() }
            assertFalse(registry.retryCleanup())
            files.completeNative()
            assertTrue(registry.retryCleanup())
            registry.close()
            assertTrue(registry.clear)
        } finally {
            importing.cancel()
            importing.join()
            files.completeNative()
            registry.retryCleanup()
            registry.close()
        }
    }

    @Test
    fun stcS03L1_cancelledNativeWaitBlocksSuccessorUntilActualCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = ControlledNativeFiles()
        val gateway = ControlledSessions()
        val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway,
            platform = PlatformCapabilities(PlatformFamily.DESKTOP), documentFiles = files))
        val importing = async(start = CoroutineStart.LAZY) {
            runtime.documentFiles.importOriginal { runtime.presentation.value.display?.actorId == ACTOR_A }
        }
        try {
            runtime.openSignIn(false, runtime.navigator.token())
            runtime.signIn("ACTOR-A", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(ACTOR_A, runtime.presentation.value.display?.actorId)
            importing.start()
            files.entered.await()
            importing.cancel()
            importing.join()
            val callsBeforeTerminal = gateway.calls
            runtime.signOut()
            runCurrent()
            assertNull(runtime.presentation.value.display)
            assertFalse(runtime.documentFiles.retryCleanup())
            runtime.openSignIn(false, runtime.navigator.token())
            runtime.signIn("ACTOR-B", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(callsBeforeTerminal, gateway.calls)
            assertNull(runtime.presentation.value.display)
            files.completeNative()
            assertTrue(runtime.documentFiles.retryCleanup())
            runtime.signIn("ACTOR-B", "synthetic".toCharArray(), runtime.navigator.token())
            assertEquals(ACTOR_B, runtime.presentation.value.display?.actorId)
        } finally {
            importing.cancel()
            importing.join()
            files.completeNative()
            runtime.documentFiles.retryCleanup()
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    /** STC-S13/S15: duplicate/late completion cannot erase another exact obligation. */
    @Test
    fun stcS13L1_unrelatedNativeCompletionCannotClearFailedClose() = runTest {
        val files = ControlledNativeFiles()
        val registry = LocalPurchasingDocumentCleanup(files) { }
        val failed = EqualNativeHolder()
        val other = EqualNativeHolder()
        val lease = files.nativeCleanup.retain(other)
        files.nativeCleanup.unconfirmed(failed)
        registry.retire()
        lease.completed()
        lease.completed()
        files.nativeCleanup.resolved(other)
        assertFalse(registry.retryCleanup())
        assertTrue(registry.needsAttention)
        assertFailsWith<IllegalStateException> { registry.close() }
        files.nativeCleanup.resolved(failed)
        assertTrue(registry.retryCleanup())
        registry.close()
        assertTrue(registry.clear)
    }

    /** STC-S12/S15: a native acquisition call is retained even before its OS
     * request is registered. Ordinary data-only sources keep their separate flow. */
    @Test
    fun stcS15L1_nativeAdmissionCannotDisappearBeforeRequestRegistration() = runTest {
        val files = ControlledNativeAdmissionFiles()
        val registry = LocalPurchasingDocumentCleanup(files) { }
        val importing = async { registry.importOriginal { true } }
        try {
            files.entered.await()
            assertTrue(files.nativeCleanup.clear) // No OS request has started yet.
            registry.retire()
            assertFalse(registry.clear)
            assertFalse(registry.retryCleanup())
            assertFailsWith<IllegalStateException> { registry.close() }
            files.release.complete(Unit)
            assertEquals(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED,
                assertIs<LocalPurchasingDocumentFileSelection.Unavailable>(importing.await()).problem)
            assertTrue(registry.retryCleanup())
            registry.close()
        } finally {
            files.release.complete(Unit)
            importing.join()
            registry.retryCleanup()
            registry.close()
        }
    }

    /** STC-S12/S15: retry reaches a retained native holder even when its original
     * call never returned a destination to the shared registry. Controlled DATA. */
    @Test
    fun stcS15L1_safeNativeRetryRequiresConclusiveExactHolderCleanup() = runTest {
        val files = ControlledNativeFiles()
        val registry = LocalPurchasingDocumentCleanup(files) { }
        val native = ControlledDestination(Outcome.FAIL_BEFORE_RELEASE)
        var attempts = 0
        files.nativeCleanup.unconfirmed(native) {
            attempts++
            if (native.abort() == LocalPurchasingDocumentFileIoResult.Succeeded)
                files.nativeCleanup.resolved(native)
        }
        registry.retire()
        assertFalse(registry.retryCleanup())
        assertEquals(1, attempts)
        assertFalse(registry.clear)
        assertTrue(registry.needsAttention)
        native.outcome = Outcome.CONCLUSIVE
        assertTrue(registry.retryCleanup())
        assertEquals(2, attempts)
        registry.close()
        assertTrue(registry.clear)
    }

    /** STC-S13/S15: a throwing native cleanup cannot suppress another safe attempt
     * or let that unrelated success certify the original unresolved obligation. */
    @Test
    fun stcS13L1_nativeRetryContinuesAfterFailureWithoutCertifyingIt() = runTest {
        val files = ControlledNativeFiles()
        val registry = LocalPurchasingDocumentCleanup(files) { }
        val first = ControlledDestination(Outcome.INDETERMINATE)
        val second = ControlledDestination()
        var secondAttempts = 0
        files.nativeCleanup.unconfirmed(first) {
            if (first.abort() == LocalPurchasingDocumentFileIoResult.Succeeded)
                files.nativeCleanup.resolved(first)
        }
        files.nativeCleanup.unconfirmed(second) {
            secondAttempts++
            if (second.abort() == LocalPurchasingDocumentFileIoResult.Succeeded)
                files.nativeCleanup.resolved(second)
        }
        registry.retire()
        assertFalse(registry.retryCleanup())
        assertEquals(1, secondAttempts)
        assertFalse(registry.clear)
        assertFailsWith<IllegalStateException> { registry.close() }
        first.outcome = Outcome.CONCLUSIVE
        assertTrue(registry.retryCleanup())
        assertEquals(1, secondAttempts)
        registry.close()
        assertTrue(registry.clear)
    }

    /** STC-S12/S15 late-response schedule: actual cleanup can settle before the
     * original native call returns its earlier failure. The call itself stays held. */
    @Test
    fun stcS15L1_lateNativeFailureCannotResurrectConclusivelyReleasedHolder() = runTest {
        val files = ControlledNativeLateFailureFiles()
        val registry = LocalPurchasingDocumentCleanup(files) { }
        val importing = async { registry.importOriginal { true } }
        try {
            files.entered.await()
            registry.retire()
            assertFalse(registry.retryCleanup()) // Native holder settled; original call is pending.
            assertTrue(files.nativeCleanup.clear)
            assertFalse(registry.clear)
            files.release.complete(Unit)
            assertEquals(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED,
                assertIs<LocalPurchasingDocumentFileSelection.Unavailable>(importing.await()).problem)
            assertTrue(registry.retryCleanup())
            assertFalse(registry.needsAttention)
            registry.close()
        } finally {
            files.release.complete(Unit)
            importing.join()
            registry.retryCleanup()
            registry.close()
        }
    }

    private fun registry(native: ControlledDestination) = LocalPurchasingDocumentCleanup(ControlledFiles(native)) { }
    private suspend fun choose(files: LocalPurchasingDocumentFiles): LocalPurchasingDocumentFileDestination {
        val chosen = assertIs<LocalPurchasingDocumentFileDestinationSelection.Chosen>(files.chooseDestination(
            "A-PRIVATE-MEDIA-STC.pdf", PurchasingEvidenceMediaV1.PDF) { true })
        val bytes = "A-PRIVATE-MEDIA-STC".encodeToByteArray()
        try { assertEquals(LocalPurchasingDocumentFileIoResult.Succeeded, chosen.destination.write(bytes) { true }) }
        finally { bytes.fill(0) }
        return chosen.destination
    }
}

private enum class Outcome { CONCLUSIVE, FAIL_BEFORE_RELEASE, ERROR_AFTER_PARTIAL_RELEASE, INDETERMINATE, HELD }
private class ControlledDestination(@Volatile var outcome: Outcome = Outcome.CONCLUSIVE) : LocalPurchasingDocumentFileDestination {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private var owned: ByteArray? = null
    override suspend fun write(bytes: ByteArray, isCurrent: () -> Boolean): LocalPurchasingDocumentFileIoResult {
        check(isCurrent())
        owned = bytes.copyOf()
        return LocalPurchasingDocumentFileIoResult.Succeeded
    }
    override suspend fun close(): LocalPurchasingDocumentFileIoResult {
        owned?.fill(0); owned = null
        return LocalPurchasingDocumentFileIoResult.Succeeded
    }
    override suspend fun abort(): LocalPurchasingDocumentFileIoResult {
        entered.complete(Unit)
        when (outcome) {
            Outcome.FAIL_BEFORE_RELEASE -> return LocalPurchasingDocumentFileIoResult.Unavailable(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)
            Outcome.ERROR_AFTER_PARTIAL_RELEASE -> {
                owned?.fill(0); owned = null
                return LocalPurchasingDocumentFileIoResult.Unavailable(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)
            }
            Outcome.INDETERMINATE -> throw IllegalStateException("controlled indeterminate close")
            Outcome.HELD -> release.await()
            Outcome.CONCLUSIVE -> Unit
        }
        owned?.fill(0); owned = null
        return LocalPurchasingDocumentFileIoResult.Succeeded
    }
}
private class ControlledFiles(private val destination: ControlledDestination) : LocalPurchasingDocumentFiles {
    override val bound = true
    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection =
        LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection =
        LocalPurchasingDocumentFileDestinationSelection.Chosen(destination)
}
private val ACTOR_A = DomainId("74000000-0000-4000-8000-000000000001")
private val ACTOR_B = DomainId("74000000-0000-4000-8000-000000000002")
private class ControlledSessions : BranchUiGateway {
    override val configured = true
    var calls = 0
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
        calls++
        return SignInResult.Authenticated(ControlledSession(if (account == "ACTOR-A") ACTOR_A else ACTOR_B))
    }
}
private class ControlledSession(actor: DomainId) : BranchUiSession {
    private var active = true
    override val display = SessionDisplayContext(actor,
        DomainId("72000000-0000-4000-8000-000000000001"), DomainId("73000000-0000-4000-8000-000000000001"),
        "Controlled actor", "Controlled company", "Controlled branch", setOf(AppArea.CASHIER))
    override fun isCurrent() = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = false
    override fun close() { active = false }
    override suspend fun logout() = LocalSessionEndResult.REVOKED
}

/** A non-cooperative completion barrier represents the frozen late native/media response.
 * It never supplies employee authority or a production native-close result. */
private class HeldSelectionFiles(private val destination: ControlledDestination) : LocalPurchasingDocumentFiles {
    override val bound = true
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var selected: LocalPurchasingDocumentFileSelection.Selected? = null
    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection = withContext(NonCancellable) {
        entered.complete(Unit)
        release.await()
        val bytes = "%PDF-1.4\nA-PRIVATE-MEDIA-STC".encodeToByteArray()
        try {
            LocalPurchasingDocumentFileSelection.Selected(bytes, "A-PRIVATE-MEDIA-STC.pdf", PurchasingEvidenceMediaV1.PDF)
                .also { selected = it }
        } finally { bytes.fill(0) }
    }
    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection = withContext(NonCancellable) {
        entered.complete(Unit)
        release.await()
        LocalPurchasingDocumentFileDestinationSelection.Chosen(destination)
    }
}

/** Separate OS completion is controlled DATA; cancellation only ends the caller. */
private class ControlledNativeFiles : LocalNativePurchasingDocumentFiles {
    override val nativeCleanup = LocalPurchasingDocumentNativeCleanup()
    override val bound = true
    val entered = CompletableDeferred<Unit>()
    private var nativeLease: LocalPurchasingDocumentNativeCleanup.Lease? = null
    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection =
        nativeCleanup.during(this) {
            check(isCurrent())
            nativeLease = nativeCleanup.retain(this)
            entered.complete(Unit)
            awaitCancellation()
        }
    fun completeNative() { nativeLease?.completed() }
    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection =
        LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
}
private class EqualNativeHolder {
    override fun equals(other: Any?) = other is EqualNativeHolder
    override fun hashCode() = 0
}

/** Frozen late-acquisition schedule before an OS request exists, not native proof. */
private class ControlledNativeAdmissionFiles : LocalNativePurchasingDocumentFiles {
    override val nativeCleanup = LocalPurchasingDocumentNativeCleanup()
    override val bound = true
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection =
        withContext(NonCancellable) {
            entered.complete(Unit)
            release.await()
            LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
        }
    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection =
        LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
}

/** A controlled failed native holder and late result; no OS cleanup proof. */
private class ControlledNativeLateFailureFiles : LocalNativePurchasingDocumentFiles {
    override val nativeCleanup = LocalPurchasingDocumentNativeCleanup()
    override val bound = true
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val holder = ControlledDestination()
    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection =
        withContext(NonCancellable) {
            nativeCleanup.unconfirmed(holder) {
                if (holder.abort() == LocalPurchasingDocumentFileIoResult.Succeeded)
                    nativeCleanup.resolved(holder)
            }
            entered.complete(Unit)
            release.await()
            LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)
        }
    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection =
        LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
}
