@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

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
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
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
import platform.CoreGraphics.CGRectGetMidX
import platform.CoreGraphics.CGRectGetMidY
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForDeleting
import platform.Foundation.NSError
import platform.Foundation.NSFileReadNoPermissionError
import platform.Foundation.NSFileWriteNoPermissionError
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIModalPresentationPopover
import platform.UIKit.UIPresentationController
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UIKit.presentationController
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeJPEG
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.UniformTypeIdentifiers.UTTypePNG
import platform.darwin.NSObject
import platform.posix.EACCES
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.EPERM
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.stat

/**
 * Genuine UIKit document choice: UIDocumentPicker opens/reads a user-selected external
 * document, or selects an external folder for a new no-overwrite output. There is no
 * app-private cache, persisted bookmark, invented provider or bytes-as-file fallback.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocalPurchasingDocumentFiles : LocalPurchasingDocumentFiles, AutoCloseable {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeHost = AtomicReference<Host?>(null)
    // A cancelled or invalidated picker remains outstanding until UIKit actually finishes it.
    private val pending = AtomicReference<Request?>(null)
    private val destinations = AtomicReference<List<Destination>>(emptyList())
    private val closed = AtomicInt(0)
    private val cleanupUncertain = AtomicInt(0)

    private class Host(val controller: UIViewController) {
        val alive = AtomicInt(1)
    }

    private class Request(
        val host: Host,
        val current: () -> Boolean,
        val continuation: CancellableContinuation<NativeChoice>,
    ) {
        val resultHandled = AtomicInt(0)
        var picker: UIDocumentPickerViewController? = null
        var delegate: PickerDelegate? = null
        var result: NativeChoice? = null
    }

    private sealed interface NativeChoice {
        class File(val url: NSURL) : NativeChoice
        data object Cancelled : NativeChoice
        class Unavailable(val problem: LocalPurchasingDocumentFileProblem) : NativeChoice
    }

    /** Only the retained presenting controller supplies this binding. */
    fun bind(controller: UIViewController): Binding {
        check(NSThread.isMainThread)
        val captured = Host(controller)
        activeHost.getAndSet(captured)?.let(::invalidate)
        return Binding { invalidate(captured) }
    }

    class Binding internal constructor(private val retire: () -> Unit) : AutoCloseable {
        override fun close() = retire()
    }

    override val bound: Boolean
        get() {
            if (closed.value != 0 || !NSThread.isMainThread) return false
            val host = activeHost.value ?: return false
            // Availability for the composed form; every actual choice still rechecks current
            // application/presentation/session lifetime and the mounted presenting window.
            return host.alive.value != 0 && host.controller.viewLoaded
        }

    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection {
        val host = activeHost.value ?: return LocalPurchasingDocumentFileSelection.Unavailable(
            LocalPurchasingDocumentFileProblem.NOT_BOUND)
        val choice = choose(host, output = false, isCurrent)
        when (choice) {
            NativeChoice.Cancelled -> return LocalPurchasingDocumentFileSelection.Cancelled
            is NativeChoice.Unavailable -> return LocalPurchasingDocumentFileSelection.Unavailable(choice.problem)
            is NativeChoice.File -> Unit
        }
        var selected: LocalPurchasingDocumentFileSelection.Selected? = null
        var delivered = false
        return try {
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                val check = {
                    context.ensureActive()
                    requireIoCurrent(host, isCurrent)
                }
                val scope = choice.url.startAccessingSecurityScopedResource()
                try {
                    check()
                    selected = readOriginal(choice.url, check)
                    checkNotNull(selected)
                } finally {
                    if (scope) choice.url.stopAccessingSecurityScopedResource()
                }
            }.also { result ->
                selected = result
                requireMainCurrent(host, isCurrent)
                delivered = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LocalPurchasingDocumentFileSelection.Unavailable(documentProblem(failure))
        } finally {
            if (!delivered) selected?.destroy()
        }
    }

    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection {
        if (!validIosDocumentFileName(originalFileName)) {
            return LocalPurchasingDocumentFileDestinationSelection.Unavailable(
                LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME)
        }
        if (cleanupUncertain.value != 0) {
            return LocalPurchasingDocumentFileDestinationSelection.Unavailable(
                LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)
        }
        val host = activeHost.value ?: return LocalPurchasingDocumentFileDestinationSelection.Unavailable(
            LocalPurchasingDocumentFileProblem.NOT_BOUND)
        val choice = choose(host, output = true, isCurrent)
        when (choice) {
            NativeChoice.Cancelled -> return LocalPurchasingDocumentFileDestinationSelection.Cancelled
            is NativeChoice.Unavailable -> return LocalPurchasingDocumentFileDestinationSelection.Unavailable(choice.problem)
            is NativeChoice.File -> Unit
        }
        var destination: Destination? = null
        return try {
            if (!choice.url.isFileURL()) fail(LocalPurchasingDocumentFileProblem.PERMISSION_DENIED)
            requireMainCurrent(host, isCurrent)
            withContext(Dispatchers.Default) { validateDestinationFolder(choice.url, originalFileName) }
            requireMainCurrent(host, isCurrent)
            val created = Destination(host, choice.url, originalFileName, media, isCurrent)
            registerDestination(created)
            destination = created
            LocalPurchasingDocumentFileDestinationSelection.Chosen(created)
        } catch (cancelled: CancellationException) {
            destination?.let { withContext(NonCancellable) { it.abort() } }
            throw cancelled
        } catch (failure: Exception) {
            val cleanup = destination?.let { withContext(NonCancellable) { it.abort() } }
            LocalPurchasingDocumentFileDestinationSelection.Unavailable(
                (cleanup as? LocalPurchasingDocumentFileIoResult.Unavailable)?.problem
                    ?: documentProblem(failure))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(0, 1)) return
        activeHost.getAndSet(null)?.let { it.alive.value = 0 }
        mainScope.launch { failPending(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED) }
        destinations.value.forEach { destination -> cleanupScope.launch { destination.abort() } }
    }

    private suspend fun choose(host: Host, output: Boolean, current: () -> Boolean): NativeChoice =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val request = Request(host, current, continuation)
                continuation.invokeOnCancellation { mainScope.launch { cancelPending(request) } }
                val initial = choiceProblem(host, current)
                if (initial != null) {
                    continuation.resume(NativeChoice.Unavailable(initial))
                    return@suspendCancellableCoroutine
                }
                if (host.controller.viewIfLoaded?.window == null) {
                    continuation.resume(NativeChoice.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED))
                    return@suspendCancellableCoroutine
                }
                if (pending.value != null || host.controller.presentedViewController != null) {
                    continuation.resume(NativeChoice.Unavailable(LocalPurchasingDocumentFileProblem.BUSY))
                    return@suspendCancellableCoroutine
                }
                val delegate = PickerDelegate(request)
                var picker: UIDocumentPickerViewController? = null
                try {
                    val created = if (output) {
                        UIDocumentPickerViewController(
                            forOpeningContentTypes = listOf(UTTypeFolder), asCopy = false)
                    } else {
                        UIDocumentPickerViewController(forOpeningContentTypes = listOf(
                            UTTypeJPEG, UTTypePNG, UTTypePDF), asCopy = false)
                    }
                    picker = created
                    created.delegate = delegate
                    created.allowsMultipleSelection = false
                    configurePresentation(created, host.controller)
                    created.presentationController?.delegate = delegate
                    request.picker = created
                    request.delegate = delegate
                    if (!pending.compareAndSet(null, request)) {
                        continuation.resume(NativeChoice.Unavailable(LocalPurchasingDocumentFileProblem.BUSY))
                        return@suspendCancellableCoroutine
                    }
                    if (!continuation.isActive) {
                        pending.compareAndSet(request, null)
                        return@suspendCancellableCoroutine
                    }
                    host.controller.presentViewController(created, animated = true) {
                        request.picker?.presentationController?.delegate = delegate
                    }
                } catch (failure: Exception) {
                    pending.compareAndSet(request, null)
                    request.picker = null
                    request.delegate = null
                    picker?.delegate = null
                    if (continuation.isActive) {
                        continuation.resume(NativeChoice.Unavailable(documentProblem(failure)))
                    }
                }
            }
        }

    private fun configurePresentation(picker: UIDocumentPickerViewController, controller: UIViewController) {
        if (controller.traitCollection.userInterfaceIdiom != UIUserInterfaceIdiomPad) return
        picker.modalPresentationStyle = UIModalPresentationPopover
        picker.popoverPresentationController?.let { popover ->
            val bounds = controller.view.bounds
            popover.sourceView = controller.view
            popover.sourceRect = CGRectMake(CGRectGetMidX(bounds), CGRectGetMidY(bounds), 1.0, 1.0)
            popover.permittedArrowDirections = 0uL
        }
    }

    private fun cancelPending(request: Request) {
        if (!request.resultHandled.compareAndSet(0, 1)) return
        pending.compareAndSet(request, null)
        dismissPicker(request)
    }

    private fun failPending(problem: LocalPurchasingDocumentFileProblem) {
        val request = pending.getAndSet(null) ?: return
        if (!request.resultHandled.compareAndSet(0, 1)) return
        dismissPicker(request)
        if (request.continuation.isActive) request.continuation.resume(NativeChoice.Unavailable(problem))
    }

    private fun dismissPicker(request: Request) {
        val picker = request.picker
        request.picker = null
        request.delegate = null
        picker?.delegate = null
        picker?.presentingViewController?.dismissViewControllerAnimated(false, completion = null)
    }

    private fun deliver(request: Request) {
        val result = request.result ?: return
        if (!request.resultHandled.compareAndSet(0, 1)) return
        pending.compareAndSet(request, null)
        val picker = request.picker
        request.picker = null
        request.delegate = null
        picker?.delegate = null
        if (choiceProblem(request.host, request.current) != null) {
            if (request.continuation.isActive) {
                request.continuation.resume(NativeChoice.Unavailable(
                    LocalPurchasingDocumentFileProblem.ACCESS_CHANGED))
            }
            return
        }
        if (request.continuation.isActive) request.continuation.resume(result)
    }

    private fun picked(request: Request, controller: UIDocumentPickerViewController, urls: List<*>) {
        if (request.picker !== controller || request.resultHandled.value != 0) return
        val selected = urls.mapNotNull { it as? NSURL }
        request.result = when {
            urls.size != 1 || selected.size != 1 -> NativeChoice.Unavailable(
                LocalPurchasingDocumentFileProblem.IO_FAILED)
            else -> NativeChoice.File(selected.single())
        }
        deliver(request)
    }

    private fun cancelled(request: Request, controller: UIDocumentPickerViewController?) {
        if (request.resultHandled.value != 0) return
        if (controller != null && request.picker !== controller) return
        request.result = NativeChoice.Cancelled
        deliver(request)
    }

    private fun invalidate(host: Host) {
        host.alive.value = 0
        activeHost.compareAndSet(host, null)
        mainScope.launch {
            val request = pending.value
            if (request?.host === host) failPending(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
        }
        destinations.value.filter { it.host === host }.forEach { destination ->
            cleanupScope.launch { destination.abort() }
        }
    }

    private fun choiceProblem(host: Host, current: () -> Boolean):
        LocalPurchasingDocumentFileProblem? {
        if (closed.value != 0 || host.alive.value == 0 || activeHost.value !== host) {
            return LocalPurchasingDocumentFileProblem.NOT_BOUND
        }
        if (!applicationActive()) return LocalPurchasingDocumentFileProblem.ACCESS_CHANGED
        if (!presentationCurrent(host)) return LocalPurchasingDocumentFileProblem.ACCESS_CHANGED
        if (!safeCurrent(current)) return LocalPurchasingDocumentFileProblem.ACCESS_CHANGED
        return null
    }

    private suspend fun requireMainCurrent(host: Host, current: () -> Boolean) {
        val problem = withContext(Dispatchers.Main) { choiceProblem(host, current) }
        if (problem != null) fail(problem)
    }

    private fun requireIoCurrent(host: Host, current: () -> Boolean) {
        if (closed.value != 0 || host.alive.value == 0 || activeHost.value !== host || !safeCurrent(current)) {
            fail(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
        }
    }

    private fun applicationActive(): Boolean =
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

    private fun presentationCurrent(host: Host): Boolean {
        val controller = host.controller
        if (!controller.viewLoaded) return false
        // A full-screen picker may detach the presenter's view from the window while its
        // delegate is still delivering the user's choice. The initial presentation check
        // above requires a mounted window; ongoing work requires the retained controller
        // and current shared session, not a transient UIKit window attachment.
        if (controller.isBeingDismissed() || controller.isMovingFromParentViewController()) return false
        return true
    }

    private fun readOriginal(url: NSURL, check: () -> Unit): LocalPurchasingDocumentFileSelection.Selected {
        if (!url.isFileURL()) fail(LocalPurchasingDocumentFileProblem.PERMISSION_DENIED)
        val name = url.lastPathComponent?.takeIf(::validIosDocumentFileName)
            ?: fail(LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME)
        val media = mediaForFileName(name)
            ?: fail(LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA)
        val read = coordinatedRead(url, MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES, check)
        val actual = read.bytes
        return try {
            check()
            if (!localPurchasingDocumentMediaMatches(media, actual)) {
                fail(LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA)
            }
            LocalPurchasingDocumentFileSelection.Selected(actual, name, media)
        } finally {
            actual.fill(0)
        }
    }

    private fun validateDestinationFolder(folder: NSURL, fileName: String) {
        if (!folder.isFileURL()) fail(LocalPurchasingDocumentFileProblem.PERMISSION_DENIED)
        val scope = folder.startAccessingSecurityScopedResource()
        try {
            val folderPath = folder.path ?: fail(LocalPurchasingDocumentFileProblem.PERMISSION_DENIED)
            val folderStat = statPath(folderPath) ?: fail(LocalPurchasingDocumentFileProblem.PERMISSION_DENIED)
            if ((folderStat.mode and S_IFMT) != S_IFDIR) fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
            val target = folder.URLByAppendingPathComponent(fileName, isDirectory = false)
                ?: fail(LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME)
            val targetPath = target.path ?: fail(LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME)
            if (statPath(targetPath) != null) fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
        } finally {
            if (scope) folder.stopAccessingSecurityScopedResource()
        }
    }

    private fun registerDestination(destination: Destination) {
        while (true) {
            val current = destinations.value
            if (destinations.compareAndSet(current, current + destination)) return
        }
    }

    private fun unregisterDestination(destination: Destination) {
        while (true) {
            val current = destinations.value
            if (destinations.compareAndSet(current, current.filterNot { it === destination })) return
        }
    }

    private inner class Destination(
        val host: Host,
        private val folder: NSURL,
        private val fileName: String,
        private val media: PurchasingEvidenceMediaV1,
        private val ownerCurrent: () -> Boolean,
    ) : LocalPurchasingDocumentFileDestination {
        private val mutex = Mutex()
        private var expected: ByteArray? = null
        private var identity: FileIdentity? = null
        private var actualUrl: NSURL? = null
        private var created = false
        private var attempted = false
        private var finalized = false
        private var abortResult: LocalPurchasingDocumentFileIoResult? = null
        private var writeCurrent: (() -> Boolean)? = null
        private var scopeStarted = false

        private val outputUrl: NSURL by lazy {
            folder.URLByAppendingPathComponent(fileName, isDirectory = false)
                ?: throw DocumentFailure(LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME)
        }

        override suspend fun write(bytes: ByteArray,
            isCurrent: () -> Boolean): LocalPurchasingDocumentFileIoResult {
            val admission = when {
                bytes.isEmpty() -> LocalPurchasingDocumentFileProblem.EMPTY_FILE
                bytes.size > MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES -> LocalPurchasingDocumentFileProblem.TOO_LARGE
                else -> null
            }
            var owned: ByteArray? = if (admission == null) bytes.copyOf() else null
            return try {
                var early: LocalPurchasingDocumentFileProblem? = admission
                if (early == null) {
                    early = try {
                        requireMainCurrent(host, isCurrent)
                        if (!localPurchasingDocumentMediaMatches(media, checkNotNull(owned))) {
                            LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA
                        } else null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        documentProblem(failure)
                    }
                }
                withContext(Dispatchers.Default) {
                    mutex.withLock {
                        if (attempted || finalized || abortResult != null) {
                            return@withLock unavailable(LocalPurchasingDocumentFileProblem.ALREADY_USED)
                        }
                        attempted = true
                        writeCurrent = isCurrent
                        try {
                            if (early != null) fail(early)
                            val original = checkNotNull(owned)
                            requireIoCurrent()
                            startScope()
                            requireIoCurrent()
                            createAndWrite(outputUrl, original, ::requireIoCurrent) { url, fileIdentity ->
                                created = true
                                actualUrl = url
                                identity = fileIdentity
                            }
                            requireIoCurrent()
                            requireMainCurrent(host, isCurrent)
                            expected = original
                            owned = null
                            LocalPurchasingDocumentFileIoResult.Succeeded
                        } catch (failure: Exception) {
                            failureAfterCleanup(failure)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                abort()
                throw cancelled
            } finally {
                owned?.fill(0)
            }
        }

        override suspend fun close(): LocalPurchasingDocumentFileIoResult = try {
            withContext(Dispatchers.Default) {
                mutex.withLock {
                    if (finalized || abortResult != null || expected == null) {
                        return@withLock unavailable(LocalPurchasingDocumentFileProblem.ALREADY_USED)
                    }
                    try {
                        requireIoCurrent()
                        requireMainCurrent(host, ownerCurrent)
                        val original = checkNotNull(expected)
                        val actual = coordinatedRead(actualUrl ?: outputUrl,
                            MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES, ::requireIoCurrent)
                        try {
                            requireIoCurrent()
                            if (actual.identity != identity ||
                                !actual.bytes.contentEquals(original)) {
                                fail(LocalPurchasingDocumentFileProblem.OUTPUT_MISMATCH)
                            }
                        } finally {
                            actual.bytes.fill(0)
                        }
                        requireMainCurrent(host, ownerCurrent)
                        requireIoCurrent()
                        original.fill(0)
                        expected = null
                        writeCurrent = null
                        finalized = true
                        stopScope()
                        unregisterDestination(this@Destination)
                        LocalPurchasingDocumentFileIoResult.Succeeded
                    } catch (failure: Exception) {
                        failureAfterCleanup(failure)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            abort()
            throw cancelled
        }

        override suspend fun abort(): LocalPurchasingDocumentFileIoResult =
            withContext(NonCancellable + Dispatchers.Default) {
                mutex.withLock {
                    if (finalized) unavailable(LocalPurchasingDocumentFileProblem.ALREADY_USED)
                    else abortResult ?: abortLocked()
                }
            }

        override fun toString(): String = "IosPurchasingDocumentDestination([REDACTED])"

        private fun requireIoCurrent() {
            if (closed.value != 0 || host.alive.value == 0 || activeHost.value !== host) {
                fail(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
            }
            if (writeCurrent?.let(::safeCurrent) != true) {
                fail(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
            }
        }

        private fun startScope() {
            if (!scopeStarted && folder.startAccessingSecurityScopedResource()) scopeStarted = true
        }

        private fun stopScope() {
            if (scopeStarted) {
                scopeStarted = false
                folder.stopAccessingSecurityScopedResource()
            }
        }

        private fun failureAfterCleanup(failure: Exception): LocalPurchasingDocumentFileIoResult {
            val cleanup = try { abortLocked() } catch (_: Exception) {
                cleanupUncertain.value = 1
                unavailable(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)
            }
            if (failure is CancellationException) throw failure
            return (cleanup as? LocalPurchasingDocumentFileIoResult.Unavailable) ?: unavailable(documentProblem(failure))
        }

        private fun abortLocked(): LocalPurchasingDocumentFileIoResult {
            var confirmed = true
            if (created) {
                val known = identity
                val url = actualUrl ?: outputUrl
                confirmed = known != null && try { deleteCreated(url, known) } catch (_: Exception) { false }
            }
            stopScope()
            expected?.fill(0)
            expected = null
            writeCurrent = null
            if (!confirmed) cleanupUncertain.value = 1
            unregisterDestination(this)
            return (if (confirmed) LocalPurchasingDocumentFileIoResult.Succeeded
                else unavailable(LocalPurchasingDocumentFileProblem.CLEANUP_FAILED)).also { abortResult = it }
        }
    }

    private inner class PickerDelegate(
        private val request: Request,
    ) : NSObject(), UIDocumentPickerDelegateProtocol,
        UIAdaptivePresentationControllerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController,
            didPickDocumentsAtURLs: List<*>) {
            picked(request, controller, didPickDocumentsAtURLs)
        }

        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentAtURL: NSURL) {
            picked(request, controller, listOf(didPickDocumentAtURL))
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            cancelled(request, controller)
        }

        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
            cancelled(request, presentationController.presentedViewController as? UIDocumentPickerViewController)
        }
    }
}

private data class FileIdentity(val device: Int, val inode: ULong)
private class FileStat(val identity: FileIdentity, val mode: Int)
private class NativeRead(val bytes: ByteArray, val identity: FileIdentity)

private class DocumentFailure(val problem: LocalPurchasingDocumentFileProblem) : Exception(problem.name)

private fun fail(problem: LocalPurchasingDocumentFileProblem): Nothing = throw DocumentFailure(problem)

private fun unavailable(problem: LocalPurchasingDocumentFileProblem) =
    LocalPurchasingDocumentFileIoResult.Unavailable(problem)

private fun safeCurrent(current: () -> Boolean): Boolean =
    try { current() } catch (_: Exception) { false }

private fun documentProblem(failure: Exception): LocalPurchasingDocumentFileProblem = when (failure) {
    is DocumentFailure -> failure.problem
    else -> LocalPurchasingDocumentFileProblem.IO_FAILED
}

private fun nativeProblem(error: NSError?): LocalPurchasingDocumentFileProblem {
    if (error == null) return LocalPurchasingDocumentFileProblem.IO_FAILED
    return if (error.domain == NSCocoaErrorDomain &&
        (error.code == NSFileReadNoPermissionError || error.code == NSFileWriteNoPermissionError)) {
        LocalPurchasingDocumentFileProblem.PERMISSION_DENIED
    } else {
        LocalPurchasingDocumentFileProblem.IO_FAILED
    }
}

private fun errnoProblem(): LocalPurchasingDocumentFileProblem =
    if (errno == EACCES || errno == EPERM) LocalPurchasingDocumentFileProblem.PERMISSION_DENIED
    else LocalPurchasingDocumentFileProblem.IO_FAILED

private fun validIosDocumentFileName(value: String): Boolean =
    validLocalPurchasingDocumentFileName(value) && value != "." && value != ".." &&
        '/' !in value && '\\' !in value

private fun mediaForFileName(name: String): PurchasingEvidenceMediaV1? {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg" -> PurchasingEvidenceMediaV1.JPEG
        "png" -> PurchasingEvidenceMediaV1.PNG
        "pdf" -> PurchasingEvidenceMediaV1.PDF
        else -> null
    }
}

private fun statPath(path: String): FileStat? = memScoped {
    val value = alloc<stat>()
    if (platform.posix.stat(path, value.ptr) != 0) {
        if (errno == ENOENT) return@memScoped null
        fail(errnoProblem())
    }
    FileStat(FileIdentity(value.st_dev, value.st_ino), value.st_mode.toInt())
}

private fun lstatPath(path: String): FileStat? = memScoped {
    val value = alloc<stat>()
    if (platform.posix.lstat(path, value.ptr) != 0) {
        if (errno == ENOENT) return@memScoped null
        fail(errnoProblem())
    }
    FileStat(FileIdentity(value.st_dev, value.st_ino), value.st_mode.toInt())
}

private fun fstatDescriptor(descriptor: Int): FileStat? = memScoped {
    val value = alloc<stat>()
    if (platform.posix.fstat(descriptor, value.ptr) != 0) return@memScoped null
    FileStat(FileIdentity(value.st_dev, value.st_ino), value.st_mode.toInt())
}

private fun coordinatedRead(url: NSURL, maximum: Int, check: () -> Unit): NativeRead {
    var result: NativeRead? = null
    var failure: Throwable? = null
    val coordinator = NSFileCoordinator(null)
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        coordinator.coordinateReadingItemAtURL(url, options = 0uL, error = error.ptr) { coordinated ->
            try {
                val target = coordinated ?: fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
                val path = target.path ?: fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
                result = readBoundedPath(path, maximum, check)
            } catch (thrown: Throwable) {
                failure = thrown
            }
        }
        if (failure == null && error.value != null) failure = DocumentFailure(nativeProblem(error.value))
    }
    failure?.let { throw it }
    return result ?: fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
}

private fun readBoundedPath(path: String, maximum: Int, check: () -> Unit): NativeRead {
    val descriptor = openReadOnly(path)
    try {
        val stat = fstatDescriptor(descriptor) ?: fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
        if ((stat.mode and S_IFMT) != S_IFREG) fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
        val buffer = ByteArray(maximum + 1)
        try {
            var length = 0
            while (length < buffer.size) {
                check()
                val count = buffer.usePinned { pinned ->
                    platform.posix.read(descriptor, pinned.addressOf(length), (buffer.size - length).toULong())
                }
                if (count < 0) {
                    if (errno == EINTR) continue
                    fail(errnoProblem())
                }
                if (count == 0L) break
                length += count.toInt()
            }
            check()
            if (length == 0) fail(LocalPurchasingDocumentFileProblem.EMPTY_FILE)
            if (length > maximum) fail(LocalPurchasingDocumentFileProblem.TOO_LARGE)
            return NativeRead(buffer.copyOf(length), stat.identity)
        } finally {
            buffer.fill(0)
        }
    } finally {
        platform.posix.close(descriptor)
    }
}

private fun openReadOnly(path: String): Int {
    while (true) {
        val descriptor = platform.posix.open(path, O_RDONLY or O_CLOEXEC or O_NOFOLLOW)
        if (descriptor >= 0) return descriptor
        if (errno != EINTR) fail(errnoProblem())
    }
}

private fun createAndWrite(url: NSURL, bytes: ByteArray, check: () -> Unit,
    onCreated: (NSURL, FileIdentity?) -> Unit) {
    var failure: Throwable? = null
    val coordinator = NSFileCoordinator(null)
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        coordinator.coordinateWritingItemAtURL(url, options = 0uL, error = error.ptr) { coordinated ->
            try {
                val target = coordinated ?: fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
                val path = target.path ?: fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
                var descriptor = -1
                try {
                    descriptor = createExclusive(path)
                    val stat = fstatDescriptor(descriptor)
                    onCreated(target, stat?.identity)
                    if (stat == null) fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
                    if ((stat.mode and S_IFMT) != S_IFREG) {
                        fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
                    }
                    var offset = 0
                    while (offset < bytes.size) {
                        check()
                        val count = bytes.usePinned { pinned ->
                            platform.posix.write(descriptor, pinned.addressOf(offset),
                                (bytes.size - offset).toULong())
                        }
                        if (count < 0) {
                            if (errno == EINTR) continue
                            fail(errnoProblem())
                        }
                        if (count == 0L) fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
                        offset += count.toInt()
                    }
                    check()
                    if (platform.posix.fsync(descriptor) != 0) {
                        fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
                    }
                    val closed = platform.posix.close(descriptor)
                    descriptor = -1
                    if (closed != 0) fail(LocalPurchasingDocumentFileProblem.CLOSE_FAILED)
                } finally {
                    if (descriptor >= 0) platform.posix.close(descriptor)
                }
            } catch (thrown: Throwable) {
                failure = thrown
            }
        }
        if (failure == null && error.value != null) failure = DocumentFailure(nativeProblem(error.value))
    }
    failure?.let { throw it }
}

private fun createExclusive(path: String): Int {
    while (true) {
        val descriptor = platform.posix.open(path,
            O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, 0x180)
        if (descriptor >= 0) return descriptor
        if (errno == EEXIST) fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
        if (errno != EINTR) fail(errnoProblem())
    }
}

private fun deleteCreated(url: NSURL, expected: FileIdentity): Boolean {
    var confirmed = false
    var failure: Throwable? = null
    val coordinator = NSFileCoordinator(null)
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        coordinator.coordinateWritingItemAtURL(url, options = NSFileCoordinatorWritingForDeleting,
            error = error.ptr) { coordinated ->
            try {
                val path = coordinated?.path ?: fail(LocalPurchasingDocumentFileProblem.IO_FAILED)
                val current = lstatPath(path)
                confirmed = when {
                    // The created output is already absent without a confirmed unlink. Keep the
                    // cleanup unknown rather than claiming that provider-side deletion occurred.
                    current == null -> false
                    (current.mode and S_IFMT) != S_IFREG -> false
                    current.identity != expected -> false
                    else -> unlinkCreated(path)
                }
            } catch (thrown: Throwable) {
                failure = thrown
            }
        }
        if (error.value != null) confirmed = false
    }
    return confirmed && failure == null
}

private fun unlinkCreated(path: String): Boolean {
    while (true) {
        if (platform.posix.unlink(path) == 0) return true
        if (errno == ENOENT) return false
        if (errno != EINTR) return false
    }
}
