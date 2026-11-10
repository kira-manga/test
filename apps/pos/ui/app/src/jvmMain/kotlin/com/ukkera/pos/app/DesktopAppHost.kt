package com.ukkera.pos.app

import com.ukkera.pos.app.files.DesktopLocalBranchReportFiles
import com.ukkera.pos.app.files.DesktopLocalBranchReportStorageV1
import com.ukkera.pos.app.files.DesktopLocalPurchasingDocumentFiles
import com.ukkera.pos.security.LocalSetupCompositionMode
import java.awt.EventQueue
import java.awt.Window

/**
 * Owns desktop presentation resources only. Closing this host does not stop a supervised
 * Master or prove native database/key/transport retirement. Trusted dependencies supply those
 * owners separately; the default application remains unconfigured.
 */
class DesktopAppHost private constructor(composition: Composition) : AutoCloseable {
    val runtime: UkkeraRuntime = composition.runtime
    private val reports = composition.reports
    private val documents = composition.documents
    private var reportBinding: AutoCloseable? = null
    private var documentBinding: AutoCloseable? = null
    private var bindingOwner: Any? = null
    private var closed = false
    private var closing = false
    private var reportOwnerRetired = false
    private var documentOwnerRetired = false

    constructor(dependencies: AppDependencies) : this(compose(dependencies))

    /** Only disposal of the exact current window attachment can release its bindings. */
    fun bind(window: Window): AutoCloseable {
        check(EventQueue.isDispatchThread() && !closed)
        val owner = Any()
        var attaching = false
        try {
            endBindings()
            bindingOwner = owner
            attaching = true
            reportBinding = reports?.bind(window)
            documentBinding = documents?.bind(window)
        } catch (failure: Throwable) {
            val cleanup = LocalSessionCleanup()
            cleanup.attempt { throw failure }
            // endBindings already attempted both old peers. Retain a failed old
            // holder without repeating that close; release any newly bound peers.
            if (attaching) cleanup.attempt { endBindings() }
            cleanup.attempt { runtime.lock(runtime.navigator.token()) }
            cleanup.throwIfFailed()
            throw failure
        }
        return AutoCloseable {
            check(EventQueue.isDispatchThread())
            if (bindingOwner === owner) endBindings()
        }
    }

    private fun endBindings() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { reportBinding?.close(); reportBinding = null }
        cleanup.attempt { documentBinding?.close(); documentBinding = null }
        if (reportBinding == null && documentBinding == null) bindingOwner = null
        cleanup.throwIfFailed()
    }

    override fun close() {
        check(EventQueue.isDispatchThread() && !closing)
        closed = true // Fence new presentation before attempting independent negative cleanup.
        closing = true
        try {
            val cleanup = LocalSessionCleanup()
            cleanup.attempt { endBindings() }
            cleanup.attempt { runtime.close() }
            if (!reportOwnerRetired) cleanup.attempt {
                reports?.close()
                reportOwnerRetired = true
            }
            if (!documentOwnerRetired) cleanup.attempt {
                documents?.close()
                documentOwnerRetired = true
            }
            // Failed holders remain for an explicit retry; returned peers are not reopened.
            cleanup.throwIfFailed()
        } finally { closing = false }
    }

    private data class Composition(
        val runtime: UkkeraRuntime,
        val reports: DesktopLocalBranchReportFiles?,
        val documents: DesktopLocalPurchasingDocumentFiles?,
    )

    private companion object {
        fun compose(dependencies: AppDependencies): Composition {
            require(dependencies.platform.family == PlatformFamily.DESKTOP)
            var reports: DesktopLocalBranchReportFiles? = null
            var documents: DesktopLocalPurchasingDocumentFiles? = null
            try {
                if (dependencies.branchReportFiles === UnavailableLocalBranchReportFiles)
                    reports = DesktopLocalBranchReportFiles(if (dependencies.compositionMode == LocalSetupCompositionMode.TRAINING)
                        DesktopLocalBranchReportStorageV1.TRAINING else DesktopLocalBranchReportStorageV1.LIVE)
                if (dependencies.documentFiles === UnavailableLocalPurchasingDocumentFiles)
                    documents = DesktopLocalPurchasingDocumentFiles()
                val runtime = UkkeraRuntime(dependencies.copy(
                    branchReportFiles = reports ?: dependencies.branchReportFiles,
                    documentFiles = documents ?: dependencies.documentFiles,
                ))
                return Composition(runtime, reports, documents)
            } catch (failure: Throwable) {
                val cleanup = LocalSessionCleanup()
                cleanup.attempt { throw failure }
                cleanup.attempt { reports?.close() }
                cleanup.attempt { documents?.close() }
                cleanup.throwIfFailed()
                throw failure
            }
        }
    }
}
