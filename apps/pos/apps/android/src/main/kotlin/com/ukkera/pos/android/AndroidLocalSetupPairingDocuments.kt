package com.ukkera.pos.android

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentExportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentImportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocuments
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentKind
import com.ukkera.pos.app.setup.LocalSetupProblem
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * API24+ full binary Storage Access Framework I/O. The selected kind supplies only the
 * carrier bound; canonical decoding, review and every native ceremony remain shared.
 * No persisted URI grant, private payload cache, text conversion or proof generation.
 */
internal class AndroidLocalSetupPairingDocuments(private val application: Application) : LocalSetupPairingDocuments {
    private val main = Handler(Looper.getMainLooper())
    private val host = AtomicReference<Host?>(null)
    private val pending = AtomicReference<Request?>(null)
    private val io = Mutex()
    private val closureUncertain = AtomicBoolean(false)
    private var unresolvedStream: Closeable? = null

    private class Host(val activity: Activity, val launch: (Intent) -> Unit, val invalidated: () -> Unit) {
        val alive = AtomicBoolean(true)
        val resumed = AtomicBoolean(false)
        var screenOff: BroadcastReceiver? = null
    }

    private class Request(val host: Host, val current: () -> Boolean,
        val continuation: CancellableContinuation<Choice>) {
        val handled = AtomicBoolean(false)
        var launched = false
        var outstanding = true
        var result: Choice? = null
    }

    private sealed interface Choice {
        class Document(val uri: Uri) : Choice
        data object Cancelled : Choice
        class Unavailable(val problem: LocalSetupProblem) : Choice
    }

    /** Binding/result calls belong to the same real registered Activity-result launcher. */
    fun bind(activity: Activity, launch: (Intent) -> Unit, onInvalidated: () -> Unit): Binding {
        check(Looper.myLooper() == Looper.getMainLooper() && activity.application === application)
        val captured = Host(activity, launch, onInvalidated)
        host.getAndSet(captured)?.let(::end)
        captured.screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) end(captured)
            }
        }.also { receiver ->
            if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            else application.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        return Binding(
            resume = {
                if (captured.alive.get() && deviceUnlocked()) {
                    captured.resumed.set(true)
                    pending.get()?.takeIf { it.host === captured }?.let(::deliver)
                } else end(captured)
            },
            pause = { captured.resumed.set(false) },
            pickerStop = {
                val request = pending.get()
                captured.resumed.set(false)
                captured.alive.get() && host.get() === captured && request != null && request.host === captured &&
                    request.launched && request.outstanding && request.continuation.isActive &&
                    safeCurrent(request.current) && deviceUnlocked() && !activity.isFinishing &&
                    !activity.isDestroyed && !activity.isChangingConfigurations
            },
            retire = { end(captured) },
        )
    }

    internal class Binding internal constructor(private val resume: () -> Unit, private val pause: () -> Unit,
        private val pickerStop: () -> Boolean, private val retire: () -> Unit) : AutoCloseable {
        fun onResume() = resume()
        fun onPause() = pause()
        fun stopForPicker(): Boolean = pickerStop()
        fun onUserLeaving() = retire()
        override fun close() = retire()
    }

    /** A cancelled/abandoned request stays outstanding until its actual native result. */
    fun onActivityResult(activity: Activity, resultCode: Int, data: Intent?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val request = pending.get() ?: return
        if (!request.outstanding) return
        request.outstanding = false
        request.result = when {
            resultCode == Activity.RESULT_CANCELED -> Choice.Cancelled
            resultCode != Activity.RESULT_OK || data?.data == null -> Choice.Unavailable(LocalSetupProblem.NATIVE_UNAVAILABLE)
            else -> Choice.Document(checkNotNull(data.data))
        }
        if (request.host.activity !== activity) end(request.host)
        deliver(request) // DATA selection only; this callback opens no stream or native ceremony.
    }

    override suspend fun importDocument(kind: LocalSetupPairingDocumentKind,
        isCurrent: () -> Boolean): LocalSetupPairingDocumentImportResult {
        if (!io.tryLock()) return LocalSetupPairingDocumentImportResult.Unavailable(LocalSetupProblem.BUSY)
        try {
            admissionProblem()?.let { return LocalSetupPairingDocumentImportResult.Unavailable(it) }
            val captured = host.get() ?: return LocalSetupPairingDocumentImportResult.Unavailable()
            val choice = choose(captured, isCurrent, Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            when (choice) {
                Choice.Cancelled -> return LocalSetupPairingDocumentImportResult.Cancelled
                is Choice.Unavailable -> return LocalSetupPairingDocumentImportResult.Unavailable(choice.problem)
                is Choice.Document -> Unit
            }
            return withContext(Dispatchers.IO) {
                val check = { requireCurrent(captured, isCurrent) }
                check(); requireDocumentUri(choice.uri)
                val advertised = advertisedSize(choice.uri, check)
                if (advertised != null && advertised > kind.maximumBytes) documentFailure(LocalSetupProblem.CAPACITY_EXHAUSTED)
                check()
                val stream = application.contentResolver.openInputStream(choice.uri) ?: documentFailure()
                var bytes: ByteArray? = null
                try {
                    try { bytes = readBounded(stream, kind.maximumBytes, check) }
                    finally { closeConfirmed(stream) }
                    val complete = checkNotNull(bytes)
                    if (advertised != null && advertised != complete.size.toLong()) documentFailure()
                    check()
                    LocalSetupPairingDocumentImportResult.Completed(kind, complete)
                } finally { bytes?.fill(0) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return LocalSetupPairingDocumentImportResult.Unavailable(problem(failure)) }
        finally { io.unlock() }
    }

    override suspend fun exportDocument(kind: LocalSetupPairingDocumentKind, bytes: ByteArray,
        isCurrent: () -> Boolean): LocalSetupPairingDocumentExportResult {
        if (!io.tryLock()) return LocalSetupPairingDocumentExportResult.Unavailable(LocalSetupProblem.BUSY)
        var original: ByteArray? = null
        try {
            admissionProblem()?.let { return LocalSetupPairingDocumentExportResult.Unavailable(it) }
            if (bytes.isEmpty()) return LocalSetupPairingDocumentExportResult.Unavailable(LocalSetupProblem.INVALID_INPUT)
            if (bytes.size > kind.maximumBytes)
                return LocalSetupPairingDocumentExportResult.Unavailable(LocalSetupProblem.CAPACITY_EXHAUSTED)
            original = bytes.copyOf()
            val captured = host.get() ?: return LocalSetupPairingDocumentExportResult.Unavailable()
            val choice = choose(captured, isCurrent, Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, documentName(kind))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            when (choice) {
                Choice.Cancelled -> return LocalSetupPairingDocumentExportResult.Cancelled
                is Choice.Unavailable -> return LocalSetupPairingDocumentExportResult.Unavailable(choice.problem)
                is Choice.Document -> Unit
            }
            return withContext(Dispatchers.IO) {
                val check = { requireCurrent(captured, isCurrent) }
                check(); requireDocumentUri(choice.uri)
                val exact = checkNotNull(original)
                val stream = application.contentResolver.openOutputStream(choice.uri, "wt") ?: documentFailure()
                try {
                    var offset = 0
                    while (offset < exact.size) {
                        currentCoroutineContext().ensureActive(); check()
                        val count = minOf(8192, exact.size - offset)
                        stream.write(exact, offset, count); offset += count
                    }
                    stream.flush(); check()
                } finally { closeConfirmed(stream) }
                check()
                val readback = application.contentResolver.openInputStream(choice.uri) ?: documentFailure()
                var actual: ByteArray? = null
                try {
                    try { actual = readBounded(readback, kind.maximumBytes, check) }
                    finally { closeConfirmed(readback) }
                    check()
                    if (!checkNotNull(actual).contentEquals(exact)) documentFailure()
                    LocalSetupPairingDocumentExportResult.Completed
                } finally { actual?.fill(0) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return LocalSetupPairingDocumentExportResult.Unavailable(problem(failure)) }
        finally { original?.fill(0); io.unlock() }
    }

    private suspend fun choose(captured: Host, current: () -> Boolean, intent: Intent): Choice =
        suspendCancellableCoroutine { continuation ->
            val request = Request(captured, current, continuation)
            continuation.invokeOnCancellation { main.post {
                if (!request.outstanding) pending.compareAndSet(request, null)
                // A still outstanding OS picker is retained as a tombstone, never reassigned.
            } }
            main.post {
                when {
                    !continuation.isActive -> Unit
                    !hostCurrent(captured) -> continuation.resume(Choice.Unavailable(LocalSetupProblem.USER_LOCKED))
                    !safeCurrent(current) -> continuation.resume(Choice.Unavailable(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED))
                    !pending.compareAndSet(null, request) -> continuation.resume(Choice.Unavailable(LocalSetupProblem.BUSY))
                    else -> try {
                        request.launched = true
                        captured.launch(intent)
                    }
                    catch (_: Exception) {
                        // Invocation may already have reached the OS. Preserve its exact
                        // outstanding request; an exception never binds a late result anew.
                        continuation.resume(Choice.Unavailable(LocalSetupProblem.NATIVE_UNAVAILABLE))
                    }
                }
            }
        }

    private fun deliver(request: Request) {
        val choice = request.result ?: return
        val alive = request.host.alive.get() && host.get() === request.host && safeCurrent(request.current)
        if (alive && request.continuation.isActive && !request.host.resumed.get()) return
        if (!request.handled.compareAndSet(false, true)) return
        pending.compareAndSet(request, null)
        if (!request.continuation.isActive) return
        request.continuation.resume(if (alive && hostCurrent(request.host)) choice
            else Choice.Unavailable(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED))
    }

    private fun end(captured: Host) {
        if (!captured.alive.compareAndSet(true, false)) return
        captured.resumed.set(false)
        host.compareAndSet(captured, null)
        captured.screenOff?.let { receiver ->
            captured.screenOff = null
            try { application.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { /* already detached */ }
        }
        pending.get()?.takeIf { it.host === captured }?.let { request ->
            if (!request.outstanding) deliver(request)
            else if (request.continuation.isActive) request.continuation.resume(Choice.Unavailable(LocalSetupProblem.USER_LOCKED))
        }
        captured.invalidated()
    }

    private fun hostCurrent(captured: Host): Boolean = Build.VERSION.SDK_INT >= 24 && host.get() === captured &&
        captured.alive.get() && captured.resumed.get() && !captured.activity.isFinishing &&
        !captured.activity.isDestroyed && deviceUnlocked()

    private fun requireCurrent(captured: Host, current: () -> Boolean) {
        if (closureUncertain.get()) documentFailure(LocalSetupProblem.CLOSE_UNCONFIRMED)
        if (!hostCurrent(captured)) documentFailure(LocalSetupProblem.USER_LOCKED)
        if (!safeCurrent(current)) documentFailure(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED)
    }

    private fun admissionProblem(): LocalSetupProblem? = when {
        Build.VERSION.SDK_INT < 24 -> LocalSetupProblem.UNSUPPORTED_PLATFORM
        closureUncertain.get() -> LocalSetupProblem.CLOSE_UNCONFIRMED
        else -> null
    }

    private fun deviceUnlocked(): Boolean =
        application.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            application.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false

    private fun requireDocumentUri(uri: Uri) {
        if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(application, uri)) documentFailure()
    }

    private fun advertisedSize(uri: Uri, checkCurrent: () -> Unit): Long? {
        checkCurrent()
        return application.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            checkCurrent()
            if (cursor.count != 1 || !cursor.moveToFirst()) documentFailure()
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index).takeIf { it >= 0 }
        }
    }

    private suspend fun readBounded(stream: InputStream, maximum: Int, checkCurrent: () -> Unit): ByteArray {
        val buffer = ByteArray(maximum + 1)
        try {
            var length = 0
            while (length < buffer.size) {
                currentCoroutineContext().ensureActive(); checkCurrent()
                val count = stream.read(buffer, length, minOf(8192, buffer.size - length))
                if (count == -1) break
                if (count <= 0) documentFailure()
                length += count
            }
            checkCurrent()
            if (length == 0) documentFailure(LocalSetupProblem.INVALID_INPUT)
            if (length > maximum) documentFailure(LocalSetupProblem.CAPACITY_EXHAUSTED)
            return buffer.copyOf(length)
        } finally { buffer.fill(0) }
    }

    private fun closeConfirmed(stream: Closeable) {
        try { stream.close() }
        catch (failure: Exception) {
            unresolvedStream = stream // Retain the exact stream before reporting unknown close.
            closureUncertain.set(true)
            throw DocumentFailure(LocalSetupProblem.CLOSE_UNCONFIRMED, failure)
        }
    }

    private fun problem(failure: Exception): LocalSetupProblem =
        if (closureUncertain.get()) LocalSetupProblem.CLOSE_UNCONFIRMED
        else (failure as? DocumentFailure)?.problem ?: LocalSetupProblem.NATIVE_UNAVAILABLE

    private fun documentFailure(problem: LocalSetupProblem = LocalSetupProblem.NATIVE_UNAVAILABLE): Nothing =
        throw DocumentFailure(problem)

    private class DocumentFailure(val problem: LocalSetupProblem, cause: Exception? = null) : Exception(problem.name, cause)

    private fun safeCurrent(current: () -> Boolean): Boolean = try { current() } catch (_: Exception) { false }

    private fun documentName(kind: LocalSetupPairingDocumentKind): String = when (kind) {
        LocalSetupPairingDocumentKind.SDK_IDENTITY -> "ukkera-device-sdk-identity-v2.bin"
        LocalSetupPairingDocumentKind.CHALLENGE -> "ukkera-device-pairing-challenge-v2.bin"
        LocalSetupPairingDocumentKind.POSSESSION -> "ukkera-device-pairing-possession-v2.bin"
        LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT -> "ukkera-device-pairing-unsigned-retirement-v1.bin"
        LocalSetupPairingDocumentKind.APPROVED_MASTER -> "ukkera-device-pairing-approved-master-v1.bin"
    }
}
