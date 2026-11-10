package com.ukkera.pos.app

import androidx.compose.ui.window.ComposeUIViewController
import com.ukkera.pos.app.files.IosLocalBranchReportFiles
import com.ukkera.pos.app.files.IosLocalBranchReportStorageV1
import com.ukkera.pos.app.files.IosLocalPurchasingDocumentFiles
import com.ukkera.pos.security.LocalSetupCompositionMode
import platform.Foundation.NSThread
import platform.UIKit.UIViewController

/** Swift retains one host per scene. Native presentation lifetimes never grant authority. */
class IosAppHost private constructor(
    private val runtime: UkkeraRuntime,
    private val documents: IosLocalPurchasingDocumentFiles?,
    private val reports: IosLocalBranchReportFiles?,
) {
    private var controller: UIViewController? = null
    private var documentBinding: IosLocalPurchasingDocumentFiles.Binding? = null
    private var reportBinding: AutoCloseable? = null
    private var closed = false
    private var closing = false
    private var documentOwnerRetired = false
    private var reportOwnerRetired = false

    constructor() : this(AppDependencies(platform = PlatformCapabilities(PlatformFamily.IOS)))
    /** Trusted native composition supplies real adapters; unavailable business services stay held. */
    constructor(dependencies: AppDependencies) : this(compose(dependencies))
    private constructor(composition: Composition) : this(composition.runtime, composition.documents, composition.reports)

    fun makeViewController(): UIViewController {
        check(NSThread.isMainThread && !closed)
        if (controller != null) lockForBackground()
        return ComposeUIViewController { UkkeraApp(runtime) }.also {
            controller = it
            resumeForForeground()
        }
    }

    /** Rebind presentation after scene activation; the shared runtime remains locked. */
    fun resumeForForeground() {
        check(NSThread.isMainThread)
        if (closed) return
        val retained = controller ?: return
        try {
            endBindings()
            documentBinding = documents?.bind(retained)
            reportBinding = reports?.bind(retained)
        } catch (failure: Throwable) {
            val cleanup = LocalSessionCleanup()
            cleanup.attempt { throw failure }
            // Preserve any holder whose negative cleanup failed; still attempt its peer.
            cleanup.attempt { endBindings() }
            cleanup.attempt { runtime.lock(runtime.navigator.token()) }
            cleanup.throwIfFailed()
        }
    }

    fun lockForBackground() {
        check(NSThread.isMainThread)
        if (closed) return
        // End native output admission before withdrawing the shared presentation/session.
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { endBindings() }
        // Failed native presentation cleanup cannot preserve private session state.
        cleanup.attempt { runtime.lock(runtime.navigator.token()) }
        cleanup.throwIfFailed()
    }

    /** Only disposal of this exact scene controller can release its bindings. */
    fun releaseViewController(retiring: UIViewController) {
        check(NSThread.isMainThread)
        if (controller !== retiring) return
        try { lockForBackground() }
        finally { controller = null }
    }

    fun close() {
        check(!closing) { "iOS presentation cleanup is already in progress" }
        closed = true // Fence presentation admission before attempting any cleanup.
        closing = true
        try {
            val cleanup = LocalSessionCleanup()
            cleanup.attempt { endBindings() }
            controller = null
            if (!documentOwnerRetired) cleanup.attempt {
                documents?.close()
                documentOwnerRetired = true
            }
            if (!reportOwnerRetired) cleanup.attempt {
                reports?.close()
                reportOwnerRetired = true
            }
            // Runtime.close retains its exact failed negative holders for a later retry.
            // Returning from these presentation adapters is not native-owner close proof.
            cleanup.attempt { runtime.close() }
            cleanup.throwIfFailed()
        } finally { closing = false }
    }

    private fun endBindings() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt {
            reportBinding?.close()
            reportBinding = null
        }
        cleanup.attempt {
            documentBinding?.close()
            documentBinding = null
        }
        cleanup.throwIfFailed()
    }

    private class Composition(
        val runtime: UkkeraRuntime,
        val documents: IosLocalPurchasingDocumentFiles?,
        val reports: IosLocalBranchReportFiles?,
    )

    private companion object {
        /** Preserve injected providers; instantiate only missing platform file adapters. */
        fun compose(dependencies: AppDependencies): Composition {
            require(dependencies.platform.family == PlatformFamily.IOS)
            var documents: IosLocalPurchasingDocumentFiles? = null
            var reports: IosLocalBranchReportFiles? = null
            return try {
                documents = if (dependencies.documentFiles === UnavailableLocalPurchasingDocumentFiles)
                    IosLocalPurchasingDocumentFiles() else null
                reports = if (dependencies.branchReportFiles === UnavailableLocalBranchReportFiles)
                    IosLocalBranchReportFiles(if (dependencies.compositionMode == LocalSetupCompositionMode.TRAINING)
                        IosLocalBranchReportStorageV1.TRAINING else IosLocalBranchReportStorageV1.LIVE) else null
                Composition(UkkeraRuntime(dependencies.copy(
                    documentFiles = documents ?: dependencies.documentFiles,
                    branchReportFiles = reports ?: dependencies.branchReportFiles,
                )), documents, reports)
            } catch (failure: Throwable) {
                val cleanup = LocalSessionCleanup()
                cleanup.attempt { documents?.close() }
                cleanup.attempt { reports?.close() }
                try { cleanup.throwIfFailed() }
                catch (problem: Throwable) { if (problem !== failure) failure.addSuppressed(problem) }
                throw failure
            }
        }
    }
}
