package com.ukkera.pos.desktop

import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentExportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentImportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocuments
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentKind
import com.ukkera.pos.app.setup.LocalSetupProblem
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Desktop binary pairing-document carrier: a user-selected file through the OS file dialog. The
 * kind bounds the size only; decoding, review and every ceremony stay shared. No cache or text form.
 */
internal class DesktopSetupPairingDocuments : LocalSetupPairingDocuments {
    private val window = AtomicReference<Frame?>(null)
    private val io = Mutex()

    fun bind(frame: Frame): AutoCloseable {
        window.set(frame)
        return AutoCloseable { window.compareAndSet(frame, null) }
    }

    override suspend fun importDocument(kind: LocalSetupPairingDocumentKind, isCurrent: () -> Boolean): LocalSetupPairingDocumentImportResult {
        if (!io.tryLock()) return LocalSetupPairingDocumentImportResult.Unavailable(LocalSetupProblem.BUSY)
        try {
            val path = choose(FileDialog.LOAD, kind) ?: return LocalSetupPairingDocumentImportResult.Cancelled
            if (!isCurrent()) return LocalSetupPairingDocumentImportResult.Unavailable(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED)
            return withContext(Dispatchers.IO) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@withContext LocalSetupPairingDocumentImportResult.Unavailable()
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    val size = channel.size()
                    if (size < 1) return@withContext LocalSetupPairingDocumentImportResult.Unavailable(LocalSetupProblem.INVALID_INPUT)
                    if (size > kind.maximumBytes) return@withContext LocalSetupPairingDocumentImportResult.Unavailable(LocalSetupProblem.CAPACITY_EXHAUSTED)
                    val bytes = ByteArray(size.toInt())
                    try {
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) check(channel.read(buffer) > 0)
                        check(channel.read(ByteBuffer.allocate(1)) == -1)
                        LocalSetupPairingDocumentImportResult.Completed(kind, bytes)
                    } finally { bytes.fill(0) }
                }
            }
        } catch (_: Exception) {
            return LocalSetupPairingDocumentImportResult.Unavailable()
        } finally { io.unlock() }
    }

    override suspend fun exportDocument(kind: LocalSetupPairingDocumentKind, bytes: ByteArray, isCurrent: () -> Boolean):
        LocalSetupPairingDocumentExportResult {
        if (!io.tryLock()) return LocalSetupPairingDocumentExportResult.Unavailable(LocalSetupProblem.BUSY)
        try {
            if (bytes.isEmpty()) return LocalSetupPairingDocumentExportResult.Unavailable(LocalSetupProblem.INVALID_INPUT)
            if (bytes.size > kind.maximumBytes) return LocalSetupPairingDocumentExportResult.Unavailable(LocalSetupProblem.CAPACITY_EXHAUSTED)
            val path = choose(FileDialog.SAVE, kind) ?: return LocalSetupPairingDocumentExportResult.Cancelled
            if (!isCurrent()) return LocalSetupPairingDocumentExportResult.Unavailable(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED)
            withContext(Dispatchers.IO) {
                // A new file only: an existing document is never overwritten.
                FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
                    channel.force(true)
                }
            }
            return LocalSetupPairingDocumentExportResult.Completed
        } catch (_: FileAlreadyExistsException) {
            return LocalSetupPairingDocumentExportResult.Unavailable(LocalSetupProblem.INVALID_INPUT)
        } catch (_: Exception) {
            return LocalSetupPairingDocumentExportResult.Unavailable()
        } finally { io.unlock() }
    }

    private suspend fun choose(mode: Int, kind: LocalSetupPairingDocumentKind): Path? {
        val frame = window.get() ?: throw IllegalStateException("No desktop window is bound")
        return suspendCancellableCoroutine { continuation ->
            EventQueue.invokeLater {
                val chosen = try {
                    val dialog = FileDialog(frame, title(mode, kind), mode)
                    if (mode == FileDialog.SAVE) dialog.file = "ukkera-${kind.name.lowercase().replace('_', '-')}.bin"
                    dialog.isVisible = true // Modal; returns after the user's choice.
                    val directory = dialog.directory; val file = dialog.file
                    dialog.dispose()
                    if (directory == null || file == null) null else Paths.get(directory, file)
                } catch (_: Exception) { null }
                if (continuation.isActive) continuation.resume(chosen)
            }
        }
    }

    private fun title(mode: Int, kind: LocalSetupPairingDocumentKind): String =
        (if (mode == FileDialog.LOAD) "Open " else "Save ") + kind.name.lowercase().replace('_', ' ') + " document"
}
