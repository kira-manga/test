package com.ukkera.pos.android.independent.nativesupplierworkflowv1

import android.app.Application
import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.LocalSupplierProfileV2
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.android.independent.nativefixture.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Supplier case keeps its original inputs, observation and census over the shared native owner. */
internal class NativeSupplierOwner(
    application: Application,
    protection: SelfOwnedKeyProtectionV1,
    private val evidence: NativeSupplierEvidence,
    originals: NativeSupplierOriginals,
) {
    private val owner = NativeLocalFixtureOwner(application, protection, evidence,
        NativeLocalFixtureSetup(NativeSupplierFixture.companyName, NativeSupplierFixture.branchName,
            NativeSupplierFixture.installerLogin, NativeSupplierFixture.headLogin,
            NativeSupplierFixture.initialAuthorityEpoch, NativeSupplierFixture::founding, NativeSupplierFixture::account)) {
            original, journals, publications -> NativeSupplierClient(original, journals, publications, originals, evidence)
        }

    val coordinates: SelfOwnedAllocationCoordinatesV1 get() = owner.coordinates
    val currentClient: NativeSupplierClient get() = owner.currentClient
    suspend fun setup(installer: CharArray, head: CharArray, employee: CharArray) = owner.setup(installer, head, employee)
    suspend fun reobserveSetup() = owner.reobserveSetup()
    fun requireMasterStillOperational() = owner.requireMasterStillOperational()
    suspend fun openClient(): NativeSupplierClient = owner.openClient()
    suspend fun census(label: String): NativeSupplierCensus = owner.readClosedNativeHistory { custody ->
        NativeSupplierCensus.read(custody, coordinates, label, evidence)
    }
    suspend fun closeOwned() = owner.closeOwned()
}

internal class NativeSupplierClient(
    override val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    publications: LocalCompanyBusinessPublications?,
    originals: NativeSupplierOriginals,
    evidence: NativeSupplierEvidence,
) : NativeLocalFixtureClient {
    val observer = NativeSupplierEndpointObservation(original, originals, evidence)
    override val signIns: Int get() = observer.signIns
    private val connections = MutableStateFlow<LocalMasterClientConnection?>(LocalMasterClientConnection(
        observer, original.peer, original.serviceIncarnationId,
        // NSUP's frozen originals use V1. Select that existing protocol before UI
        // preparation without adding capabilities or changing the native Master.
        LocalWorkflowInstallation(original.commandKeys - LocalSupplierProfileV2.COMMAND,
            original.readKeys - LocalSupplierProfileV2.QUERY),
        LocalBranchDisplayLabels(original.peer.scope, original.companyName, original.material.branchName),
        retailPhysicalBinding = null, administrationClient = null, companyPublications = publications,
        currentLifetime = original::isCurrent))
    val gateway = LocalEndpointBranchGateway(connections, journals.credentials, journals)

    suspend fun intentOwner(): LocalClientIdentity {
        val c = original.material.coordinates
        // This is a selector over the genuinely acquired first-employee owner, not a constructor
        // for a journal or a fallback when its native files are absent.
        return checkNotNull(journals.current(LocalClientIdentity(c.scope, c.firstActorId, c.deviceId))).owner
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        observer.retire()
        connections.value = null
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
            }
        }
        attempt { observer.awaitRetiredNamedReads() }
        attempt { gateway.closeAndAwait() }
        attempt { journals.close() }
        failure?.let { throw it }
    }
}

internal fun requireSupplierPolicy(expected: ActivatedLocalPolicy, actual: ActivatedLocalPolicy) =
    requireNativeFixturePolicy(expected, actual)
