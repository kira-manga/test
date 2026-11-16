package com.ukkera.pos.database

import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.security.*
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Self-authored, NOT independent. Existing-only diagnostic reopen of a preserved genuine
 * process fixture. It neither installs another target nor changes a history acceptance rule.
 * Preserve the original root before invocation: native recovery and close retain ownership.
 * This proves no network, fresh-identity, financial or platform acceptance by itself. */
internal object LanRecoveryColdReopenSupporting {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 1)
        val original = LanGenuineProcessEndpoint.LanGenuineMaster(Path.of(args.single()).resolve("master"))
        val opened = DesktopSelfOwnedInstallationOwner.open(original.os, SelfOwnedNativeProfileV1.LIVE)
        check(opened is SelfOwnedNativeOpenResult.Opened) { "Existing native Master unavailable" }
        val publications = when (val value = opened.context.readOriginalBusinessPublications()) {
            is LocalCompanyAuthorityResult.Value -> value.value
            is LocalCompanyAuthorityResult.Unavailable -> error("Existing publications unavailable: ${value.reason}")
        }
        val registry = LocalBranchCommerceRegistry(NativeCompanyPublicationSignatureVerifier(publications.trust),
            UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
            nativePublications = publications)
        val delegate = registry.historyProducer
        var reports = 0
        val observed = object : RuntimeHistoryBusinessProducer by delegate {
            override suspend fun join(command: RuntimeHistoricalCommand): Boolean = delegate.join(command).also {
                if (!it && reports++ < 16) {
                    // Installed source names only; no operation, employee, credential or payload.
                    val index = delegate.commands.sortedWith(compareBy({ it.name }, { it.version })).indexOf(command.command)
                    System.err.println("Existing history producer refused: installedCommandIndex=$index")
                }
            }
        }
        val composed = original.factory.fromOriginalContext(opened.context, registry.commands, registry.reads,
            UnavailablePhysicalRecordingExecutionGate, registry.nativeOriginalImports, registry.stockProjection, observed)
        when (composed) {
            is AndroidSelfOwnedMasterOpenResult.Unresolved -> error("Existing service unavailable: ${composed.reason}")
            is AndroidSelfOwnedMasterOpenResult.Opened -> try {
                println("Existing Master mode: ${composed.service.mode.value.name}")
                check(composed.service.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
            } finally {
                withContext(NonCancellable) { check(composed.service.quiesceAndClose()) }
            }
        }
    }
}
