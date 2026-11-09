package com.ukkera.pos.android.independent.aosfsetupv2

import android.app.Application
import com.ukkera.pos.app.gateway.AndroidLocalBranchRuntime
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.AndroidSelfOwnedInstallationOwner
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Actual existing-only production owner used after the setup service has closed. It calls the
 * same AndroidSelfOwnedInstallationOwner / AndroidLocalBranchRuntime startup as the service;
 * it never opens a second database while a master is live and never creates absent state.
 */
internal class AosfNativeOwner(private val application: Application) {
    var context: SelfOwnedLocalSecurityContext? = null
        private set
    var master: AndroidSelfOwnedMasterService? = null
        private set
    var original: AndroidOriginalLocalClient? = null
        private set
    var setup: LocalOriginalInstallationSetup? = null
        private set
    var materialAtOpen: SelfOwnedNativeMaterialView? = null
        private set
    private var closeAttempted = false
    private var contextCloseUnconfirmed = false

    suspend fun openExistingOnly() {
        check(master == null && context == null && !contextCloseUnconfirmed && !closeAttempted)
        closeAttempted = false
        val opened = AndroidSelfOwnedInstallationOwner.openOriginal(application)
        val actualContext = (opened as? SelfOwnedNativeOpenResult.Opened)?.context
            ?: error("Original existing-only native reopen is unavailable: $opened")
        context = actualContext
        materialAtOpen = actualContext.materialAtOpen
        withContext(NonCancellable) {
            context = null // The factory retains and cleans failed/undelivered claims.
            when (val openedMaster = AndroidLocalBranchRuntime.openOriginalMaster(application, actualContext)) {
                is AndroidSelfOwnedMasterOpenResult.Opened -> master = openedMaster.service
                is AndroidSelfOwnedMasterOpenResult.Unresolved -> {
                    if (openedMaster.reason == SelfOwnedInstallationFailureV1.BUSY)
                        context = actualContext
                    error("Original Master did not pass startup admission: ${openedMaster.reason}")
                }
            }
        }
        val service = checkNotNull(master)
        check(service.mode.value == LocalMasterLifecycleMode.OPERATIONAL) {
            "Original setup-only Master is not operational: ${service.mode.value}"
        }
        val actualOriginal = service.originalLocalClient()
        original = actualOriginal
        setup = service.readOriginalSetup()
        check(actualOriginal.isCurrent() && actualOriginal.peer.isCurrent())
        check(actualOriginal.material.originalNativeBindingDigest ==
            checkNotNull(materialAtOpen).originalNativeBindingDigest)
        check(actualOriginal.material.coordinates.storageInstanceId == materialAtOpen?.coordinates?.storageInstanceId &&
            actualOriginal.material.coordinates.storageKeyId == materialAtOpen?.coordinates?.storageKeyId &&
            actualOriginal.material.coordinates.pepperKeyId == materialAtOpen?.coordinates?.pepperKeyId &&
            actualOriginal.material.coordinates.targetSigningKeyId == materialAtOpen?.coordinates?.targetSigningKeyId)
    }

    suspend fun readOriginalSetup(): LocalOriginalInstallationSetup = checkNotNull(master).readOriginalSetup()

    /** Native process exclusion is a real observation: a second owner must be BUSY, not admitted. */
    suspend fun requireNoSecondWriter() {
        check(master != null && original?.isCurrent() == true)
        when (val second = AndroidSelfOwnedInstallationOwner.openOriginal(application)) {
            is SelfOwnedNativeOpenResult.Opened -> {
                try { second.context.close() } catch (_: Throwable) { /* Retain the actual violation below. */ }
                error("A second native writer was admitted while the original Master was active")
            }
            is SelfOwnedNativeOpenResult.Unresolved ->
                check(second.reason == SelfOwnedInstallationFailureV1.BUSY) {
                    "A second writer was not distinguishably excluded: ${second.reason}"
                }
        }
    }

    suspend fun closeConfirmed() {
        check(!closeAttempted)
        closeAttempted = true
        val service = master ?: error("No actual original Master is open")
        val confirmed = withContext(NonCancellable) { service.quiesceAndClose() }
        check(confirmed) { "Actual original Master close is unconfirmed" }
        check(service.mode.value == LocalMasterLifecycleMode.CLOSED) {
            "Confirmed close published an unexpected lifecycle: ${service.mode.value}"
        }
        check(original?.isCurrent() == false) { "The original client survived confirmed native close" }
        master = null
        closeAttempted = false
        original = null
        setup = null
    }

    /** Abandoned test paths retain an uncertain owner; they never pretend close or retry it. */
    suspend fun closeOnlyWhenConfirmed(): Boolean {
        if (master == null) {
            if (contextCloseUnconfirmed) return false
            context?.let { actual ->
                context = null
                contextCloseUnconfirmed = true
                withContext(NonCancellable) { actual.close() }
                contextCloseUnconfirmed = false
            }
            return true
        }
        if (closeAttempted) return false
        return try { closeConfirmed(); true } catch (_: Throwable) { false }
    }
}
