package com.ukkera.pos.app

import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runtime-owned negative custody only. A failed abort retains its exact native destination.
 * Retirement never saves an output, grants access, or redispatches a business original. */
internal class LocalPurchasingDocumentCleanup(
    private val files: LocalPurchasingDocumentFiles,
    private val cleanupPending: () -> Unit,
) : LocalPurchasingDocumentFiles {
    private data class State(val epoch: Any, val closed: Boolean, val held: List<Held>, val failed: Set<Held>,
        val selections: Set<FileSelection> = emptySet(), val opaqueFailure: Boolean = false)
    private val state = MutableStateFlow(State(Any(), false, emptyList(), emptySet()))
    private val nativeCleanup = (files as? LocalNativePurchasingDocumentFiles)?.nativeCleanup
    // Independent of cancelled presentation work and the runtime's other cleanup jobs.
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clear: Boolean get() = state.value.let { it.held.isEmpty() && it.selections.isEmpty() && !it.opaqueFailure && nativeCleanup?.clear != false }
    val needsAttention: Boolean get() = state.value.let { snapshot ->
        nativeCleanup?.clear == false || snapshot.opaqueFailure || snapshot.failed.isNotEmpty() || snapshot.held.any { it.epoch !== snapshot.epoch } ||
            snapshot.selections.any { !it.current(snapshot) } }
    override val bound: Boolean get() = !state.value.closed && !needsAttention && files.bound

    private fun update(change: (State) -> State) {
        while (true) {
            val before = state.value
            if (state.compareAndSet(before, change(before))) return
        }
    }

    /** Admit before native selection can suspend or acquire an OS request. Output
     * selection may return a created file. Native source calls also retain their
     * admission until they return; data-only source replies retain snapshot disposal. */
    private fun beginFileSelection(isCurrent: () -> Boolean): FileSelection? {
        val before = state.value
        if (!bound) return null
        val selection = FileSelection(before.epoch, isCurrent)
        while (true) {
            val snapshot = state.value
            if (snapshot.closed || snapshot.epoch !== before.epoch || snapshot.opaqueFailure ||
                snapshot.failed.isNotEmpty() || snapshot.held.any { it.epoch !== snapshot.epoch } ||
                snapshot.selections.any { !it.current(snapshot) }) return null
            if (state.compareAndSet(snapshot, snapshot.copy(selections = snapshot.selections + selection))) return selection
        }
    }

    private fun finishFileSelection(selection: FileSelection) {
        update { it.copy(selections = it.selections - selection) }
    }

    private inner class FileSelection(val epoch: Any, private val accessCurrent: () -> Boolean) {
        fun current(snapshot: State = state.value): Boolean = !snapshot.closed && snapshot.epoch === epoch &&
            try { accessCurrent() } catch (_: Exception) { false }
    }

    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection {
        val before = state.value
        if (!bound) return LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
        val nativeSelection = if (nativeCleanup != null) beginFileSelection(isCurrent)
            ?: return LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
            else null
        fun current(): Boolean = state.value.let { snapshot -> !snapshot.closed && snapshot.epoch === before.epoch &&
            try { isCurrent() } catch (_: Exception) { false } }
        var selected: LocalPurchasingDocumentFileSelection.Selected? = null
        var delivered = false
        try {
            currentCoroutineContext().ensureActive()
            if (!current())
                return LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
            val result = files.importOriginal(::current)
            selected = result as? LocalPurchasingDocumentFileSelection.Selected
            if (result is LocalPurchasingDocumentFileSelection.Unavailable) retainOpaqueFailure(result.problem)
            currentCoroutineContext().ensureActive()
            if (!current())
                return LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
            delivered = true
            return result
        } finally {
            if (!delivered) selected?.destroy()
            nativeSelection?.let(::finishFileSelection)
        }
    }

    private fun retainOpaqueFailure(problem: LocalPurchasingDocumentFileProblem) {
        if (problem == LocalPurchasingDocumentFileProblem.CLEANUP_FAILED || problem == LocalPurchasingDocumentFileProblem.CLOSE_FAILED) {
            // Genuine native adapters own every failed close in their tracker.
            // Their original call can return an old failure after an independent
            // retry has already proved release. Do not resurrect that settled
            // holder as an opaque failure. Plain data-only ports have no such proof.
            if (nativeCleanup != null) {
                cleanupPending()
                return
            }
            update { it.copy(opaqueFailure = true) }
            cleanupPending()
        }
    }

    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection {
        val selection = beginFileSelection(isCurrent)
            ?: return LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
        var held: Held? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!selection.current())
                return LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
            val selected = files.chooseDestination(originalFileName, media) { selection.current() }
            if (selected !is LocalPurchasingDocumentFileDestinationSelection.Chosen) {
                if (selected is LocalPurchasingDocumentFileDestinationSelection.Unavailable) retainOpaqueFailure(selected.problem)
                currentCoroutineContext().ensureActive()
                return selected
            }
            val destination = Held(selected.destination, selection.epoch, isCurrent)
            held = destination
            // Transfer to exact-output custody before releasing the in-flight obligation.
            // There must be no clear interval for a successor between these two owners.
            update { it.copy(held = it.held + destination) }
            currentCoroutineContext().ensureActive()
            if (!selection.current()) {
                destination.abort()
                return LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
            }
            return LocalPurchasingDocumentFileDestinationSelection.Chosen(destination)
        } catch (cancelled: CancellationException) {
            held?.abort()
            throw cancelled
        } finally {
            finishFileSelection(selection)
        }
    }

    /** Synchronous denial; asynchronous cleanup stays visibly pending until actual results. */
    fun retire() {
        val held: List<Held>
        val selecting: Boolean
        while (true) {
            val before = state.value
            if (state.compareAndSet(before, before.copy(epoch = Any()))) {
                held = before.held
                selecting = before.selections.isNotEmpty()
                break
            }
        }
        if (held.isNotEmpty() || selecting || nativeCleanup?.clear == false) cleanupPending()
        held.forEach { destination -> cleanup.launch { destination.abort() } }
    }

    /** Called after an entry is fenced; a stale entry cannot retire a successor's output. */
    fun retireUnavailable() {
        val snapshot = state.value
        val expired = snapshot.held.filterNot { it.current() }
        if (expired.isNotEmpty() || snapshot.selections.any { !it.current(snapshot) }) {
            update { snapshot ->
                snapshot.copy(failed = snapshot.failed + expired.filter { it in snapshot.held })
            }
            cleanupPending()
            expired.forEach { destination -> cleanup.launch { destination.abort() } }
        }
    }

    suspend fun retryCleanup(): Boolean = withContext(NonCancellable) {
        nativeCleanup?.retry()
        state.value.held.forEach { it.abort() }
        clear
    }

    fun close() {
        update { it.copy(closed = true) }
        retire()
        check(clear) { "Document output cleanup remains pending" }
    }

    private inner class Held(val native: LocalPurchasingDocumentFileDestination, val epoch: Any,
        private val accessCurrent: () -> Boolean) : LocalPurchasingDocumentFileDestination {
        private val mutex = Mutex()
        private var settled = false
        fun current(): Boolean = state.value.let { snapshot ->
            !snapshot.closed && snapshot.epoch === epoch && try { accessCurrent() } catch (_: Exception) { false }
        }
        private fun released() {
            settled = true
            update { it.copy(held = it.held.filterNot { value -> value === this }, failed = it.failed - this) }
        }
        private fun failed() {
            update { it.copy(failed = it.failed + this) }
            cleanupPending()
        }
        override suspend fun write(bytes: ByteArray, isCurrent: () -> Boolean): LocalPurchasingDocumentFileIoResult = mutex.withLock {
            if (settled || !current()) return@withLock LocalPurchasingDocumentFileIoResult.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
            native.write(bytes) { current() && isCurrent() }
        }
        override suspend fun close(): LocalPurchasingDocumentFileIoResult = mutex.withLock {
            if (settled || !current()) return@withLock LocalPurchasingDocumentFileIoResult.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
            val result = native.close()
            if (result == LocalPurchasingDocumentFileIoResult.Succeeded) released()
            result
        }
        override suspend fun abort(): LocalPurchasingDocumentFileIoResult = withContext(NonCancellable) {
            mutex.withLock {
                if (settled) return@withLock LocalPurchasingDocumentFileIoResult.Succeeded
                val result = try { native.abort() }
                catch (_: Exception) {
                    failed()
                    return@withLock LocalPurchasingDocumentFileIoResult.Unavailable(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)
                }
                if (result == LocalPurchasingDocumentFileIoResult.Succeeded) released() else failed()
                result
            }
        }
        override fun toString(): String = "RetainedDocumentCleanup([REDACTED])"
    }
}
