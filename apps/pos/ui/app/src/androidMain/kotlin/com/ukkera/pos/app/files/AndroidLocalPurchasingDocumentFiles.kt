package com.ukkera.pos.app.files

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** API24+ Storage Access Framework. No persisted URI grant, private file cache or media rewrite. */
class AndroidLocalPurchasingDocumentFiles(context: Context) : LocalPurchasingDocumentFiles {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = AtomicReference<Host?>(null)
    // A cancelled OS request stays outstanding until its actual result; a new request cannot
    // mistake a late result (including an Activity recreation result) for a new user's choice.
    private val pending = AtomicReference<Request?>(null)
    private val unconfirmedCleanup = AtomicBoolean(false)

    private class Host(val activity: Activity, val launch: (Intent) -> Unit, val invalidated: () -> Unit) {
        val alive = AtomicBoolean(true)
        val resumed = AtomicBoolean(false)
        var screenOff: BroadcastReceiver? = null
    }
    private class Request(val host: Host, val output: Boolean, val current: () -> Boolean,
        val continuation: CancellableContinuation<NativeChoice>) {
        val cleanupStarted = AtomicBoolean(false)
        val resultHandled = AtomicBoolean(false)
        var launched = false
        var outstanding = true
        var result: NativeChoice? = null
    }
    private sealed interface NativeChoice {
        class File(val uri: Uri) : NativeChoice
        data object Cancelled : NativeChoice
        class Unavailable(val problem: LocalPurchasingDocumentFileProblem) : NativeChoice
    }

    /** Only the registered native Activity-result owner supplies launch and result callbacks. */
    fun bind(activity: Activity, launch: (Intent) -> Unit, onInvalidated: () -> Unit): Binding {
        check(Looper.myLooper() == Looper.getMainLooper() && activity.applicationContext === application)
        val captured = Host(activity, launch, onInvalidated)
        host.getAndSet(captured)?.let { end(it) }
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
                    pending.get()?.takeIf { it.host === captured }?.let { deliver(it) }
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

    class Binding internal constructor(private val resume: () -> Unit, private val pause: () -> Unit,
        private val pickerStop: () -> Boolean, private val retire: () -> Unit) : AutoCloseable {
        fun onResume() = resume()
        fun onPause() = pause()
        fun stopForPicker(): Boolean = pickerStop()
        fun onUserLeaving() = retire()
        override fun close() = retire()
    }

    /** Called by the same real registered launcher, including rejected late/recreated results. */
    fun onActivityResult(activity: Activity, resultCode: Int, data: Intent?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val request = pending.get() ?: return
        request.outstanding = false
        request.result = when {
            resultCode == Activity.RESULT_CANCELED -> NativeChoice.Cancelled
            resultCode != Activity.RESULT_OK || data?.data == null -> NativeChoice.Unavailable(LocalPurchasingDocumentFileProblem.IO_FAILED)
            else -> NativeChoice.File(checkNotNull(data.data))
        }
        if (request.host.activity !== activity) end(request.host)
        deliver(request)
    }

    override val bound: Boolean get() = Build.VERSION.SDK_INT >= 24 && host.get()?.let(::hostCurrent) == true

    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection {
        val captured = host.get() ?: return LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
        val choice = choose(captured, output = false, isCurrent, Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "application/pdf"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        when (choice) {
            NativeChoice.Cancelled -> return LocalPurchasingDocumentFileSelection.Cancelled
            is NativeChoice.Unavailable -> return LocalPurchasingDocumentFileSelection.Unavailable(choice.problem)
            is NativeChoice.File -> Unit
        }
        var selected: LocalPurchasingDocumentFileSelection.Selected? = null
        return try {
            withContext(Dispatchers.IO) {
                val check = { requireCurrent(captured, isCurrent) }
                check(); requireDocumentUri(choice.uri)
                check()
                val media = mediaForMime(application.contentResolver.getType(choice.uri))
                    ?: documentFileFailure(LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA)
                check()
                var name: String? = null
                var advertised: Long? = null
                application.contentResolver.query(choice.uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null)?.use { cursor ->
                    check()
                    if (!cursor.moveToFirst() || cursor.count != 1) documentFileFailure(LocalPurchasingDocumentFileProblem.IO_FAILED)
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) advertised = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                } ?: documentFileFailure(LocalPurchasingDocumentFileProblem.IO_FAILED)
                val originalName = name?.takeIf(::validLocalPurchasingDocumentFileName)
                    ?: documentFileFailure(LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME)
                if (advertised != null && checkNotNull(advertised) > MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES)
                    documentFileFailure(LocalPurchasingDocumentFileProblem.TOO_LARGE)
                check()
                val descriptor = application.contentResolver.openFileDescriptor(choice.uri, "r")
                    ?: documentFileFailure(LocalPurchasingDocumentFileProblem.IO_FAILED)
                var bytes: ByteArray? = null
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                        bytes = readBoundedDocument(stream, check)
                    }
                    val original = checkNotNull(bytes)
                    check()
                    if (advertised != null && advertised != original.size.toLong()) documentFileFailure(LocalPurchasingDocumentFileProblem.IO_FAILED)
                    if (!localPurchasingDocumentMediaMatches(media, original)) documentFileFailure(LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA)
                    LocalPurchasingDocumentFileSelection.Selected(original, originalName, media).also { selected = it }
                } finally { bytes?.fill(0) }
            }.also {
                if (!hostCurrent(captured) || !safeCurrent(isCurrent)) {
                    selected?.destroy()
                    documentFileFailure(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
                }
            }
        } catch (cancelled: CancellationException) { selected?.destroy(); throw cancelled }
        catch (failure: Exception) { selected?.destroy(); LocalPurchasingDocumentFileSelection.Unavailable(documentProblem(failure)) }
    }

    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection {
        if (!validLocalPurchasingDocumentFileName(originalFileName)) return LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME)
        if (unconfirmedCleanup.get()) return LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)
        val captured = host.get() ?: return LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
        val choice = choose(captured, output = true, isCurrent, Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(mimeForMedia(media)).putExtra(Intent.EXTRA_TITLE, originalFileName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        return when (choice) {
            NativeChoice.Cancelled -> LocalPurchasingDocumentFileDestinationSelection.Cancelled
            is NativeChoice.Unavailable -> LocalPurchasingDocumentFileDestinationSelection.Unavailable(choice.problem)
            is NativeChoice.File -> {
                val destination = AndroidPurchasingDocumentDestination(application, choice.uri, media,
                    onCleanupFailure = { unconfirmedCleanup.set(true) }) {
                    if (unconfirmedCleanup.get()) documentFileFailure(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)
                    requireCurrent(captured, isCurrent)
                }
                try {
                    if (unconfirmedCleanup.get()) documentFileFailure(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)
                    requireCurrent(captured, isCurrent); requireDocumentUri(choice.uri)
                    LocalPurchasingDocumentFileDestinationSelection.Chosen(destination)
                } catch (failure: Exception) {
                    val aborted = destination.abort()
                    LocalPurchasingDocumentFileDestinationSelection.Unavailable(
                        (aborted as? LocalPurchasingDocumentFileIoResult.Unavailable)?.problem ?: documentProblem(failure))
                }
            }
        }
    }

    private suspend fun choose(captured: Host, output: Boolean, current: () -> Boolean, intent: Intent): NativeChoice =
        suspendCancellableCoroutine { continuation ->
            val request = Request(captured, output, current, continuation)
            continuation.invokeOnCancellation { main.post {
                if (!request.outstanding) {
                    pending.compareAndSet(request, null)
                    discardOutput(request, request.result)
                }
            } }
            main.post {
                when {
                    !continuation.isActive -> Unit
                    !hostCurrent(captured) -> continuation.resume(NativeChoice.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND))
                    !safeCurrent(current) -> continuation.resume(NativeChoice.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED))
                    !pending.compareAndSet(null, request) -> continuation.resume(NativeChoice.Unavailable(LocalPurchasingDocumentFileProblem.BUSY))
                    else -> try { captured.launch(intent); request.launched = true }
                    catch (failure: Exception) {
                        pending.compareAndSet(request, null)
                        continuation.resume(NativeChoice.Unavailable(documentProblem(failure)))
                    }
                }
            }
        }

    private fun deliver(request: Request) {
        val result = request.result ?: return
        val live = request.host.alive.get() && host.get() === request.host && safeCurrent(request.current)
        if (live && request.continuation.isActive && !request.host.resumed.get()) return
        if (!request.resultHandled.compareAndSet(false, true)) return
        pending.compareAndSet(request, null)
        if (!live || !request.continuation.isActive || !hostCurrent(request.host)) {
            if (request.output && result is NativeChoice.File && request.continuation.isActive) {
                discardOutput(request, result) { confirmed ->
                    if (request.continuation.isActive) request.continuation.resume(NativeChoice.Unavailable(
                        if (confirmed) LocalPurchasingDocumentFileProblem.ACCESS_CHANGED else LocalPurchasingDocumentFileProblem.CLEANUP_FAILED))
                }
            } else {
                discardOutput(request, result)
                if (request.continuation.isActive) request.continuation.resume(NativeChoice.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED))
            }
            return
        }
        request.continuation.resume(result, onCancellation = { _, abandoned, _ -> discardOutput(request, abandoned) })
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
            else if (request.continuation.isActive) request.continuation.resume(NativeChoice.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED))
        }
        captured.invalidated()
    }

    private fun discardOutput(request: Request, choice: NativeChoice?, onComplete: ((Boolean) -> Unit)? = null) {
        if (!request.output || choice !is NativeChoice.File) { onComplete?.invoke(true); return }
        if (!request.cleanupStarted.compareAndSet(false, true)) { onComplete?.invoke(false); return }
        cleanup.launch {
            val confirmed = deleteDocument(application, choice.uri)
            if (!confirmed) unconfirmedCleanup.set(true)
            if (onComplete != null) main.post { onComplete(confirmed) }
        }
    }

    private fun hostCurrent(captured: Host): Boolean = Build.VERSION.SDK_INT >= 24 && host.get() === captured &&
        captured.alive.get() && captured.resumed.get() && !captured.activity.isFinishing &&
        !captured.activity.isDestroyed && deviceUnlocked()

    private fun requireCurrent(captured: Host, current: () -> Boolean) {
        if (!hostCurrent(captured) || !safeCurrent(current)) documentFileFailure(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
    }

    private fun deviceUnlocked(): Boolean =
        (application.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true &&
            (application.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == false

    private fun requireDocumentUri(uri: Uri) {
        if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(application, uri))
            documentFileFailure(LocalPurchasingDocumentFileProblem.PERMISSION_DENIED)
    }
}

private class AndroidPurchasingDocumentDestination(private val context: Context, private val uri: Uri,
    private val media: PurchasingEvidenceMediaV1, private val onCleanupFailure: () -> Unit,
    private val ownerCurrent: () -> Unit) : LocalPurchasingDocumentFileDestination {
    private val mutex = Mutex()
    private var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var expected: ByteArray? = null
    private var writeCurrent: (() -> Boolean)? = null
    private var finalized = false
    private var used = false
    private var aborted: LocalPurchasingDocumentFileIoResult? = null

    override suspend fun write(bytes: ByteArray, isCurrent: () -> Boolean): LocalPurchasingDocumentFileIoResult {
        val admissionProblem = try {
            ownerCurrent()
            if (safeCurrent(isCurrent)) null else LocalPurchasingDocumentFileProblem.ACCESS_CHANGED
        } catch (failure: Exception) { documentProblem(failure) }
        val problem = when {
            admissionProblem != null -> admissionProblem
            bytes.isEmpty() -> LocalPurchasingDocumentFileProblem.EMPTY_FILE
            bytes.size > MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES -> LocalPurchasingDocumentFileProblem.TOO_LARGE
            else -> null
        }
        var owned: ByteArray? = if (problem == null) bytes.copyOf() else null
        try {
            return withContext(Dispatchers.IO) { mutex.withLock {
                if (used || aborted != null || finalized) return@withLock unavailable(LocalPurchasingDocumentFileProblem.ALREADY_USED)
                used = true; writeCurrent = isCurrent
                try {
                    if (problem != null) documentFileFailure(problem)
                    val original = checkNotNull(owned)
                    requireCurrent()
                    if (!localPurchasingDocumentMediaMatches(media, original)) documentFileFailure(LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA)
                    requireCurrent()
                    val descriptor = context.contentResolver.openFileDescriptor(uri, "rwt")
                        ?: documentFileFailure(LocalPurchasingDocumentFileProblem.IO_FAILED)
                    output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                    var offset = 0
                    while (offset < original.size) {
                        currentCoroutineContext().ensureActive(); requireCurrent()
                        val count = minOf(8192, original.size - offset)
                        checkNotNull(output).write(original, offset, count); offset += count
                    }
                    requireCurrent()
                    expected = original; owned = null
                    LocalPurchasingDocumentFileIoResult.Succeeded
                } catch (failure: Exception) {
                    val cleanup = abortLocked()
                    if (failure is CancellationException) throw failure
                    (cleanup as? LocalPurchasingDocumentFileIoResult.Unavailable) ?: unavailable(documentProblem(failure))
                }
            } }
        } catch (cancelled: CancellationException) { abort(); throw cancelled }
        finally { owned?.fill(0) }
    }

    override suspend fun close(): LocalPurchasingDocumentFileIoResult = try {
        withContext(Dispatchers.IO) { mutex.withLock {
            if (finalized || aborted != null || expected == null) return@withLock unavailable(LocalPurchasingDocumentFileProblem.ALREADY_USED)
            try {
                requireCurrent()
                checkNotNull(output).flush()
                try { checkNotNull(output).close(); output = null }
                catch (_: Exception) { documentFileFailure(LocalPurchasingDocumentFileProblem.CLOSE_FAILED) }
                requireCurrent()
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: documentFileFailure(LocalPurchasingDocumentFileProblem.IO_FAILED)
                var actual: ByteArray? = null
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                        actual = readBoundedDocument(stream, ::requireCurrent)
                    }
                    requireCurrent()
                    if (!checkNotNull(actual).contentEquals(checkNotNull(expected))) documentFileFailure(LocalPurchasingDocumentFileProblem.OUTPUT_MISMATCH)
                } finally { actual?.fill(0) }
                expected?.fill(0); expected = null; writeCurrent = null; finalized = true
                LocalPurchasingDocumentFileIoResult.Succeeded
            } catch (failure: Exception) {
                val cleanup = abortLocked()
                if (failure is CancellationException) throw failure
                (cleanup as? LocalPurchasingDocumentFileIoResult.Unavailable) ?: unavailable(documentProblem(failure))
            }
        } }
    } catch (cancelled: CancellationException) { abort(); throw cancelled }

    override suspend fun abort(): LocalPurchasingDocumentFileIoResult = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (finalized) unavailable(LocalPurchasingDocumentFileProblem.ALREADY_USED)
            else aborted ?: abortLocked()
        }
    }

    private fun requireCurrent() {
        ownerCurrent()
        if (writeCurrent?.let(::safeCurrent) != true) documentFileFailure(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
    }

    /** Cleanup is allowed after session loss; this does not read/release any protected bytes. */
    private fun abortLocked(): LocalPurchasingDocumentFileIoResult {
        var closed = true
        try { output?.close() } catch (_: Exception) { closed = false }
        finally { output = null; expected?.fill(0); expected = null; writeCurrent = null }
        val confirmed = deleteDocument(context, uri) && closed
        if (!confirmed) onCleanupFailure()
        return (if (confirmed) LocalPurchasingDocumentFileIoResult.Succeeded
            else unavailable(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)).also { aborted = it }
    }

    override fun toString(): String = "AndroidPurchasingDocumentDestination([REDACTED])"
}

private suspend fun readBoundedDocument(stream: InputStream, checkCurrent: () -> Unit): ByteArray {
    val buffer = ByteArray(MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES + 1)
    try {
        var length = 0
        while (length < buffer.size) {
            currentCoroutineContext().ensureActive(); checkCurrent()
            val count = stream.read(buffer, length, minOf(8192, buffer.size - length))
            if (count == -1) break
            if (count <= 0) documentFileFailure(LocalPurchasingDocumentFileProblem.IO_FAILED)
            length += count
        }
        checkCurrent()
        if (length == 0) documentFileFailure(LocalPurchasingDocumentFileProblem.EMPTY_FILE)
        if (length > MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES) documentFileFailure(LocalPurchasingDocumentFileProblem.TOO_LARGE)
        return buffer.copyOf(length)
    } finally { buffer.fill(0) }
}

private fun deleteDocument(context: Context, uri: Uri): Boolean = try {
    uri.scheme == "content" && DocumentsContract.isDocumentUri(context, uri) && DocumentsContract.deleteDocument(context.contentResolver, uri)
} catch (_: Exception) { false }

private fun mimeForMedia(media: PurchasingEvidenceMediaV1): String = when (media) {
    PurchasingEvidenceMediaV1.JPEG -> "image/jpeg"
    PurchasingEvidenceMediaV1.PNG -> "image/png"
    PurchasingEvidenceMediaV1.PDF -> "application/pdf"
}
private fun mediaForMime(mime: String?): PurchasingEvidenceMediaV1? = when (mime) {
    "image/jpeg" -> PurchasingEvidenceMediaV1.JPEG
    "image/png" -> PurchasingEvidenceMediaV1.PNG
    "application/pdf" -> PurchasingEvidenceMediaV1.PDF
    else -> null
}
private fun safeCurrent(current: () -> Boolean): Boolean = try { current() } catch (_: Exception) { false }
private class DocumentFileFailure(val problem: LocalPurchasingDocumentFileProblem) : Exception(problem.name)
private fun documentFileFailure(problem: LocalPurchasingDocumentFileProblem): Nothing = throw DocumentFileFailure(problem)
private fun documentProblem(failure: Exception): LocalPurchasingDocumentFileProblem = when (failure) {
    is DocumentFileFailure -> failure.problem
    is SecurityException -> LocalPurchasingDocumentFileProblem.PERMISSION_DENIED
    else -> LocalPurchasingDocumentFileProblem.IO_FAILED
}
private fun unavailable(problem: LocalPurchasingDocumentFileProblem) = LocalPurchasingDocumentFileIoResult.Unavailable(problem)
