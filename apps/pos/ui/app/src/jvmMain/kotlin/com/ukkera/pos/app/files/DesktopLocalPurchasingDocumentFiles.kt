package com.ukkera.pos.app.files

import com.ukkera.pos.app.LocalPurchasingDocumentFileDestination
import com.ukkera.pos.app.LocalPurchasingDocumentFileDestinationSelection
import com.ukkera.pos.app.LocalPurchasingDocumentFileIoResult
import com.ukkera.pos.app.LocalPurchasingDocumentFileProblem
import com.ukkera.pos.app.LocalPurchasingDocumentFileSelection
import com.ukkera.pos.app.LocalPurchasingDocumentFiles
import com.ukkera.pos.app.MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES
import com.ukkera.pos.app.localPurchasingDocumentMediaMatches
import com.ukkera.pos.app.validLocalPurchasingDocumentFileName
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native desktop document selection. No cached source, persisted permission or media rewrite.
 * Output is created exclusively: selecting an existing file never truncates it. */
class DesktopLocalPurchasingDocumentFiles : LocalPurchasingDocumentFiles, AutoCloseable {
    private class Host(val window: Window) {
        val alive = AtomicBoolean(true)
        val visible = AtomicBoolean(window.isShowing)
        val generation = AtomicReference(Any())
    }
    private class Choice(val path: Path, val host: Host, val generation: Any)
    private class Picker(val host: Host, val dialog: FileDialog)
    private val host = AtomicReference<Host?>(null)
    private val dialog = AtomicReference<Picker?>(null)
    private val closed = AtomicBoolean(false)
    private val cleanupUncertain = AtomicBoolean(false)
    private val destinations = ConcurrentHashMap.newKeySet<Destination>()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val bound: Boolean get() = !closed.get() && !cleanupUncertain.get() &&
        host.get()?.let { it.alive.get() && it.visible.get() } == true

    fun bind(window: Window): AutoCloseable {
        check(EventQueue.isDispatchThread())
        val captured = Host(window)
        check(!closed.get() && host.compareAndSet(null, captured))
        val windows = object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) { invalidate(captured, end = true) }
            override fun windowIconified(event: WindowEvent) { invalidate(captured) }
            override fun windowDeiconified(event: WindowEvent) { captured.visible.set(true) }
        }
        val components = object : ComponentAdapter() {
            override fun componentHidden(event: ComponentEvent) { invalidate(captured) }
            override fun componentShown(event: ComponentEvent) { captured.visible.set(true) }
        }
        window.addWindowListener(windows)
        window.addComponentListener(components)
        return AutoCloseable {
            invalidate(captured, end = true)
            EventQueue.invokeLater {
                window.removeWindowListener(windows)
                window.removeComponentListener(components)
            }
        }
    }

    private fun invalidate(value: Host, end: Boolean = false) {
        value.visible.set(false)
        value.generation.set(Any())
        if (end) { value.alive.set(false); host.compareAndSet(value, null) }
        EventQueue.invokeLater {
            dialog.get()?.takeIf { it.host === value }?.let { if (dialog.compareAndSet(it, null)) it.dialog.dispose() }
        }
        destinations.filter { it.choice.host === value }.forEach { cleanup.launch { it.abort() } }
    }

    private fun current(choice: Choice, access: () -> Boolean): Boolean = bound &&
        host.get() === choice.host && choice.host.alive.get() && choice.host.visible.get() &&
        choice.host.generation.get() === choice.generation && safeCurrent(access)

    private suspend fun choose(save: Boolean, name: String?, access: () -> Boolean): Choice? =
        suspendCancellableCoroutine { continuation ->
            val captured = host.get()
            val generation = captured?.generation?.get()
            val owned = AtomicReference<Picker?>(null)
            continuation.invokeOnCancellation { EventQueue.invokeLater {
                owned.getAndSet(null)?.let { dialog.compareAndSet(it, null); it.dialog.dispose() }
            } }
            EventQueue.invokeLater {
                if (!continuation.isActive) return@invokeLater
                if (captured == null || generation == null || !bound || !safeCurrent(access)) {
                    continuation.resumeWith(Result.failure(DocumentFailure(LocalPurchasingDocumentFileProblem.NOT_BOUND)))
                    return@invokeLater
                }
                var chooser: FileDialog? = null
                var picker: Picker? = null
                try {
                    val title = if (save) "Save original document / حفظ المستند الأصلي" else "Open original document / فتح المستند الأصلي"
                    val mode = if (save) FileDialog.SAVE else FileDialog.LOAD
                    chooser = when (val window = captured.window) {
                        is Frame -> FileDialog(window, title, mode)
                        is Dialog -> FileDialog(window, title, mode)
                        else -> throw DocumentFailure(LocalPurchasingDocumentFileProblem.NOT_BOUND)
                    }
                    picker = Picker(captured, chooser)
                    if (!dialog.compareAndSet(null, picker)) throw DocumentFailure(LocalPurchasingDocumentFileProblem.BUSY)
                    owned.set(picker)
                    chooser.isMultipleMode = false
                    if (name != null) chooser.file = name
                    chooser.isVisible = true
                    val file = chooser.file
                    val directory = chooser.directory
                    val choice = if (file != null && directory != null)
                        Choice(Path.of(directory).resolve(file).toAbsolutePath().normalize(), captured, generation) else null
                    if (continuation.isActive) {
                        if (choice != null && !current(choice, access))
                            continuation.resumeWith(Result.failure(DocumentFailure(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)))
                        else continuation.resume(choice)
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                } finally {
                    picker?.let { dialog.compareAndSet(it, null); owned.compareAndSet(it, null) }
                    chooser?.dispose()
                }
            }
        }

    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection {
        var selected: LocalPurchasingDocumentFileSelection.Selected? = null
        var delivered = false
        try {
            val choice = choose(false, null, isCurrent) ?: return LocalPurchasingDocumentFileSelection.Cancelled
            val result = withContext(Dispatchers.IO) {
                val bytes = readBounded(choice.path) { checkCurrent(choice, isCurrent) }
                try {
                    val media = PurchasingEvidenceMediaV1.entries.firstOrNull { localPurchasingDocumentMediaMatches(it, bytes) }
                        ?: throw DocumentFailure(LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA)
                    checkCurrent(choice, isCurrent)
                    LocalPurchasingDocumentFileSelection.Selected(bytes, choice.path.fileName.toString(), media)
                        .also { selected = it }
                } finally { bytes.fill(0) }
            }
            delivered = true
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return LocalPurchasingDocumentFileSelection.Unavailable(problem(failure)) }
        finally { if (!delivered) selected?.destroy() }
    }

    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection {
        return try {
            if (!validLocalPurchasingDocumentFileName(originalFileName))
                throw DocumentFailure(LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME)
            val choice = choose(true, originalFileName, isCurrent)
                ?: return LocalPurchasingDocumentFileDestinationSelection.Cancelled
            if (!validLocalPurchasingDocumentFileName(choice.path.fileName.toString()))
                throw DocumentFailure(LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME)
            val destination = Destination(choice, media, isCurrent)
            destinations.add(destination)
            if (!current(choice, isCurrent)) {
                destination.abort()
                throw DocumentFailure(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
            }
            LocalPurchasingDocumentFileDestinationSelection.Chosen(destination)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { LocalPurchasingDocumentFileDestinationSelection.Unavailable(problem(failure)) }
    }

    private fun checkCurrent(choice: Choice, access: () -> Boolean) {
        if (!current(choice, access)) throw DocumentFailure(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
    }

    private inner class Destination(val choice: Choice, val media: PurchasingEvidenceMediaV1,
        val ownerCurrent: () -> Boolean) : LocalPurchasingDocumentFileDestination {
        val mutex = Mutex()
        var expected: ByteArray? = null
        var output: FileChannel? = null
        var fileKey: Any? = null
        var created = false
        var attempted = false
        var completed = false
        var abortResult: LocalPurchasingDocumentFileIoResult? = null
        var writeCurrent: (() -> Boolean)? = null

        private fun requireCurrent() {
            checkCurrent(choice, ownerCurrent)
            if (writeCurrent?.let(::safeCurrent) != true)
                throw DocumentFailure(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
        }

        override suspend fun write(bytes: ByteArray, isCurrent: () -> Boolean): LocalPurchasingDocumentFileIoResult {
            var owned: ByteArray? = null
            try {
                if (bytes.size !in 1..MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES)
                    throw DocumentFailure(if (bytes.isEmpty()) LocalPurchasingDocumentFileProblem.EMPTY_FILE else LocalPurchasingDocumentFileProblem.TOO_LARGE)
                owned = bytes.copyOf()
                return withContext(Dispatchers.IO) { mutex.withLock {
                    if (attempted || completed || abortResult != null) return@withLock unavailable(LocalPurchasingDocumentFileProblem.ALREADY_USED)
                    attempted = true
                    writeCurrent = isCurrent
                    try {
                        requireCurrent()
                        val original = checkNotNull(owned)
                        if (!localPurchasingDocumentMediaMatches(media, original))
                            throw DocumentFailure(LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA)
                        // No replacement/truncation, even after a native chooser overwrite prompt.
                        output = FileChannel.open(choice.path, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, NOFOLLOW_LINKS)
                        created = true
                        fileKey = attributes(choice.path).fileKey() ?: throw DocumentFailure(LocalPurchasingDocumentFileProblem.IO_FAILED)
                        val buffer = ByteBuffer.wrap(original)
                        while (buffer.hasRemaining()) {
                            currentCoroutineContext().ensureActive(); requireCurrent()
                            check(checkNotNull(output).write(buffer) > 0)
                        }
                        requireCurrent()
                        expected = original; owned = null
                        LocalPurchasingDocumentFileIoResult.Succeeded
                    } catch (failure: Exception) { failureAfterCleanup(failure) }
                } }
            } catch (cancelled: CancellationException) { abort(); throw cancelled }
            catch (failure: Exception) { return unavailable(problem(failure)) }
            finally { owned?.fill(0) }
        }

        override suspend fun close(): LocalPurchasingDocumentFileIoResult = try {
            withContext(Dispatchers.IO) { mutex.withLock {
                if (completed || abortResult != null || expected == null) return@withLock unavailable(LocalPurchasingDocumentFileProblem.ALREADY_USED)
                try {
                    requireCurrent()
                    checkNotNull(output).force(true)
                    try { checkNotNull(output).close(); output = null }
                    catch (_: Exception) { throw DocumentFailure(LocalPurchasingDocumentFileProblem.CLOSE_FAILED) }
                    requireCurrent()
                    check(attributes(choice.path).fileKey() == fileKey)
                    val actual = readBounded(choice.path, ::requireCurrent)
                    try {
                        if (!actual.contentEquals(checkNotNull(expected)))
                            throw DocumentFailure(LocalPurchasingDocumentFileProblem.OUTPUT_MISMATCH)
                    } finally { actual.fill(0) }
                    requireCurrent()
                    expected?.fill(0); expected = null; writeCurrent = null; completed = true
                    destinations.remove(this@Destination)
                    LocalPurchasingDocumentFileIoResult.Succeeded
                } catch (failure: Exception) { failureAfterCleanup(failure) }
            } }
        } catch (cancelled: CancellationException) { abort(); throw cancelled }

        private fun failureAfterCleanup(failure: Exception): LocalPurchasingDocumentFileIoResult {
            val clean = abortLocked()
            if (failure is CancellationException) throw failure
            return (clean as? LocalPurchasingDocumentFileIoResult.Unavailable) ?: unavailable(problem(failure))
        }

        override suspend fun abort(): LocalPurchasingDocumentFileIoResult = withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock { if (completed) unavailable(LocalPurchasingDocumentFileProblem.ALREADY_USED) else abortResult ?: abortLocked() }
        }

        private fun abortLocked(): LocalPurchasingDocumentFileIoResult {
            var confirmed = true
            try { output?.close() } catch (_: Exception) { confirmed = false }
            finally { output = null; expected?.fill(0); expected = null; writeCurrent = null }
            try {
                if (created) {
                    check(fileKey != null && attributes(choice.path).fileKey() == fileKey)
                    Files.delete(choice.path)
                }
            } catch (_: Exception) { confirmed = false }
            if (!confirmed) cleanupUncertain.set(true)
            destinations.remove(this@Destination)
            return (if (confirmed) LocalPurchasingDocumentFileIoResult.Succeeded
                else unavailable(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)).also { abortResult = it }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) host.get()?.let { invalidate(it, end = true) }
        destinations.forEach { cleanup.launch { it.abort() } }
    }
}

private class DocumentFailure(val problem: LocalPurchasingDocumentFileProblem) : Exception()
private fun problem(failure: Exception): LocalPurchasingDocumentFileProblem = when (failure) {
    is DocumentFailure -> failure.problem
    is java.nio.file.AccessDeniedException, is SecurityException -> LocalPurchasingDocumentFileProblem.PERMISSION_DENIED
    else -> LocalPurchasingDocumentFileProblem.IO_FAILED
}
private fun unavailable(problem: LocalPurchasingDocumentFileProblem) = LocalPurchasingDocumentFileIoResult.Unavailable(problem)
private fun safeCurrent(access: () -> Boolean): Boolean = try { access() } catch (_: Exception) { false }
private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).also {
    if (!it.isRegularFile || it.isSymbolicLink) throw DocumentFailure(LocalPurchasingDocumentFileProblem.IO_FAILED)
}
private suspend fun readBounded(path: Path, current: () -> Unit): ByteArray {
    current()
    val before = attributes(path)
    if (before.size() !in 1..MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES.toLong())
        throw DocumentFailure(if (before.size()==0L) LocalPurchasingDocumentFileProblem.EMPTY_FILE else LocalPurchasingDocumentFileProblem.TOO_LARGE)
    val bytes = ByteArray(before.size().toInt())
    try {
        FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                currentCoroutineContext().ensureActive(); current()
                check(input.read(buffer)>0)
            }
            check(input.read(ByteBuffer.allocate(1)) == -1)
        }
        val after = attributes(path)
        check(before.fileKey()!=null && before.fileKey()==after.fileKey() && before.size()==after.size() &&
            before.lastModifiedTime()==after.lastModifiedTime())
        current()
        return bytes
    } catch (failure: Throwable) { bytes.fill(0); throw failure }
}
