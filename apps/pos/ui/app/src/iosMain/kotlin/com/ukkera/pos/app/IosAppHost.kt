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
        endBindings()
        documentBinding = documents?.bind(retained)
        reportBinding = reports?.bind(retained)
    }

    fun lockForBackground() {
        check(NSThread.isMainThread)
        if (closed) return
        // End native output admission before withdrawing the shared presentation/session.
        endBindings()
        runtime.lock(runtime.navigator.token())
    }

    /** Only disposal of this exact scene controller can release its bindings. */
    fun releaseViewController(retiring: UIViewController) {
        check(NSThread.isMainThread)
        if (controller !== retiring) return
        lockForBackground()
        controller = null
    }

    fun close() {
        if (closed) return
        closed = true
        endBindings()
        controller = null
        documents?.close()
        reports?.close()
        runtime.close()
    }

    private fun endBindings() {
        reportBinding?.close()
        reportBinding = null
        documentBinding?.close()
        documentBinding = null
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
            val documents = if (dependencies.documentFiles === UnavailableLocalPurchasingDocumentFiles)
                IosLocalPurchasingDocumentFiles() else null
            val reports = if (dependencies.branchReportFiles === UnavailableLocalBranchReportFiles)
                IosLocalBranchReportFiles(if (dependencies.compositionMode == LocalSetupCompositionMode.TRAINING)
                    IosLocalBranchReportStorageV1.TRAINING else IosLocalBranchReportStorageV1.LIVE) else null
            return try {
                Composition(UkkeraRuntime(dependencies.copy(
                    documentFiles = documents ?: dependencies.documentFiles,
                    branchReportFiles = reports ?: dependencies.branchReportFiles,
                )), documents, reports)
            } catch (failure: Throwable) {
                documents?.close()
                reports?.close()
                throw failure
            }
        }
    }
}
