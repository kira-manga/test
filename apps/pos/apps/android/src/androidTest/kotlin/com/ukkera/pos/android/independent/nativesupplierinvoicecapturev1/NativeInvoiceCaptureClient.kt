package com.ukkera.pos.android.independent.nativesupplierinvoicecapturev1

import android.util.Base64
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureClient
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One observed genuine application original. This object owns no authority or result producer. */
internal class NativeInvoiceCaptureOriginal(
    val symbol: String,
    val operationId: OperationId,
    val expected: LocalCaptureSupplierInvoiceV2,
    val owner: LocalClientIdentity,
) {
    var identity: OperationIdentity? = null
        private set
    var request: MutationRequest? = null
        private set
    var session: LocalSessionView? = null
        private set
    var result: MutationReply.Recorded? = null
        private set

    fun observeLookup(value: OperationIdentity) {
        check(value.operationId == operationId && value.branchId == owner.scope.branchId &&
            value.originalActorId == owner.actorId && value.semanticDigestVersion == 1)
        identity?.let { check(it == value) { "The original capture identity changed" } } ?: run { identity = value }
    }

    fun beforeDispatch(value: MutationRequest, view: LocalSessionView, evidence: NativeLocalFixtureEvidence) {
        check(request == null) { "This capture already reached its first dispatch" }
        check(value.operationId == operationId && value.command == LocalPurchasingProfile.CAPTURE_INVOICE &&
            value.actionApprovalId == null && view.scope == owner.scope && view.actorId == owner.actorId &&
            view.deviceId == owner.deviceId)
        val saved = checkNotNull(identity) { "No actual saved-original lookup preceded capture dispatch" }
        check(saved.semanticSha256 == semanticDigest(owner.scope, owner.actorId, value.command, value.payload).value)
        val decoded = LocalPurchasingCodec.decodeCaptureInvoice(value.payload)
        check(decoded == expected && LocalPurchasingCodec.captureInvoice(decoded) == value.payload)
        check(decoded.invoice.internalOrderLink == null && decoded.invoice.supplierOrderReference == null &&
            decoded.invoice.lines.all { it.internalAllocationId == null && it.supplierAllocationReference == null })
        request = value
        session = view
        evidence.bind("${symbol}_PRE_DISPATCH", JSONObject()
            .put("operationId", operationId.value).put("originalActorId", owner.actorId.value)
            .put("branchId", owner.scope.branchId.value).put("deviceId", owner.deviceId.value)
            .put("sessionId", view.sessionId.value).put("policyRevision", view.policy.revision.value)
            .put("semanticDigestVersion", saved.semanticDigestVersion).put("semanticDigest", saved.semanticSha256)
            .put("command", value.command.name).put("commandVersion", value.command.version)
            .put("expectedSupplierStorageRevision", decoded.expectedSupplierRevision.value)
            .put("canonicalCaptureInput", captureBase64(value.payload))
            .put("inputOrigin", "PUBLIC_SHARED_APPLICATION_GATEWAY")
            .put("controllerGenerated", false))
    }

    fun observeResult(value: MutationReply.Recorded) {
        check(value.operationId == operationId && value.originalActorId == owner.actorId && value.physicalStart == null)
        result?.let { requireSameCaptureResult(it, value) } ?: run { result = value }
    }
}

internal class NativeInvoiceCaptureOriginals(private val evidence: NativeLocalFixtureEvidence) {
    private val originals = linkedMapOf<OperationId, NativeInvoiceCaptureOriginal>()
    private val earlyLookups = mutableMapOf<OperationId, OperationIdentity>()
    val all: List<NativeInvoiceCaptureOriginal> get() = synchronized(this) { originals.values.toList() }

    @Synchronized fun staged(symbol: String, pending: LocalPendingOperation,
        expected: LocalCaptureSupplierInvoiceV2, owner: LocalClientIdentity): NativeInvoiceCaptureOriginal {
        check(originals[pending.operationId] == null && originals.values.none { it.symbol == symbol })
        check(pending.action == LocalAction.SUPPLIER_INVOICE_CAPTURE && pending.phase == LocalPendingPhase.STAGED &&
            pending.recordedDecision == null && pending.submitAllowed)
        val original = NativeInvoiceCaptureOriginal(symbol, pending.operationId, expected, owner)
        earlyLookups.remove(pending.operationId)?.let(original::observeLookup)
        originals[pending.operationId] = original
        evidence.bind(symbol, pending.operationId.value)
        evidence.event("${symbol}_ACTUAL_NATIVE_JOURNAL_STAGE_RETURNED", JSONObject()
            .put("inputOrigin", "PUBLIC_SHARED_APPLICATION_GATEWAY").put("submissionPerformed", false)
            .put("controllerGenerated", false))
        return original
    }

    @Synchronized fun observeLookup(value: OperationIdentity) {
        originals[value.operationId]?.let { it.observeLookup(value); return }
        earlyLookups[value.operationId]?.let { check(it == value) } ?: run {
            check(earlyLookups.isEmpty()) { "Unexpected unbound capture original" }
            earlyLookups[value.operationId] = value
        }
    }

    @Synchronized fun original(operationId: OperationId): NativeInvoiceCaptureOriginal =
        checkNotNull(originals[operationId]) { "The tested mutation did not select a retained application original" }
}

/** Gateway over the actually acquired original device and first-employee native journals.
 * Bootstrap/close custody is shared; NSUP's supplier-only inputs and census are not reused. */
internal class NativeInvoiceCaptureClient(
    override val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    publications: LocalCompanyBusinessPublications?,
    originals: NativeInvoiceCaptureOriginals,
    evidence: NativeLocalFixtureEvidence,
) : NativeLocalFixtureClient {
    val observer = NativeInvoiceCaptureEndpointObservation(original, originals, evidence)
    override val signIns: Int get() = observer.signIns
    private val connections = MutableStateFlow<LocalMasterClientConnection?>(LocalMasterClientConnection(
        observer, original.peer, original.serviceIncarnationId,
        LocalWorkflowInstallation(original.commandKeys, original.readKeys),
        LocalBranchDisplayLabels(original.peer.scope, original.companyName, original.material.branchName),
        retailPhysicalBinding = null, administrationClient = null, companyPublications = publications,
        currentLifetime = original::isCurrent))
    val gateway = LocalEndpointBranchGateway(connections, journals.credentials, journals)

    suspend fun intentOwner(): LocalClientIdentity {
        val c = original.material.coordinates
        // Selection over an already genuinely acquired journal; absence cannot create a new one.
        return checkNotNull(journals.current(LocalClientIdentity(c.scope, c.firstActorId, c.deviceId))).owner
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        observer.retire()
        connections.value = null
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (caught: Throwable) {
                if (failure == null) failure = caught
                else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
            }
        }
        attempt { observer.awaitRetiredNamedReads() }
        attempt { gateway.closeAndAwait() }
        attempt { journals.close() }
        failure?.let { throw it }
    }
}

/** Pass-through observation, not a replacement Master, credential issuer or accepting Client.
 * A runtime-only owned copy of the real capture login's access token permits independent
 * named protected reads. It is never serialized, reused after retirement or replaced on failure. */
internal class NativeInvoiceCaptureEndpointObservation(
    private val original: AndroidOriginalLocalClient,
    private val originals: NativeInvoiceCaptureOriginals,
    private val evidence: NativeLocalFixtureEvidence,
) : LocalMasterClient by original.client {
    private val gate = Any()
    private var namedReadAccess: OpaqueCredential? = null
    private val activeNamedReads = mutableMapOf<OpaqueCredential, CompletableDeferred<Unit>>()
    private var issued: LocalSessionView? = null
    private var verified: LocalSessionView? = null
    private var retired = false
    var signIns: Int = 0
        private set
    var dispatches: Int = 0
        private set
    var lookups: Int = 0
        private set
    var foundLookups: Int = 0
        private set

    val currentView: LocalSessionView get() = synchronized(gate) {
        check(!retired && original.isCurrent()) { "The original capture observation owner retired" }
        checkNotNull(verified) { "No actual issued capture session passed fresh Current" }
    }
    val hasNamedReadAccess: Boolean get() = synchronized(gate) {
        namedReadAccess != null || activeNamedReads.isNotEmpty()
    }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier,
        password: CharArray, explicitDeviceMove: Boolean): LocalAuthenticationResult {
        clearAccess()
        var returned: LocalAuthenticationResult? = null
        try {
            signIns++
            val result = original.client.signIn(connectionId, login, password, explicitDeviceMove).also { returned = it }
            if (result is LocalAuthenticationResult.Authenticated) captureIssued(result.tokens, connectionId)
            return result
        } catch (failure: Throwable) {
            clearAccess()
            (returned as? LocalAuthenticationResult.Authenticated)?.tokens?.destroy()
            throw failure
        }
    }

    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult {
        clearAccess()
        // This leg requests a new explicit login after close, never an unobserved renewed bearer.
        return original.client.refresh(credentials)
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = try {
        val result = original.client.current(credentials)
        if (result is LocalSessionViewResult.Current) synchronized(gate) {
            val expected = issued
            if (!retired && original.isCurrent() && expected != null && sameCaptureSession(expected, result.view))
                verified = result.view
            else clearAccessLocked()
        } else clearAccess()
        result
    } catch (failure: Throwable) { clearAccess(); throw failure }

    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult {
        clearAccess()
        return original.client.logout(credentials)
    }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply = try {
        check(credentials.connectionId == original.peer.connectionId && original.isCurrent())
        val saved = originals.original(request.operationId)
        saved.beforeDispatch(request, currentView, evidence)
        dispatches++
        original.client.execute(credentials, request).also {
            if (it is MutationReply.Recorded) saved.observeResult(it) else clearAccess()
        }
    } catch (failure: Throwable) { clearAccess(); throw failure }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply = try {
        originals.observeLookup(identity)
        lookups++
        original.client.lookup(credentials, identity).also {
            if (it is LookupReply.Found) {
                foundLookups++
                originals.original(identity.operationId).observeResult(it.result)
            }
            else if (it !is LookupReply.AbsentAtCurrentWriter) clearAccess()
        }
    } catch (failure: Throwable) { clearAccess(); throw failure }

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply = try {
        original.client.read(credentials, request).also { if (it !is ReadReply.Data) clearAccess() }
    } catch (failure: Throwable) { clearAccess(); throw failure }

    /** Fresh Current before and after the actual independent endpoint read; no response alias. */
    suspend fun namedRead(request: ReadRequest): ReadReply.Data {
        requireNamedQuery(request)
        var retained: OpaqueCredential? = null
        try {
            val (access, expected) = synchronized(gate) {
                check(!retired && original.isCurrent())
                val view = checkNotNull(verified)
                val copy = checkNotNull(namedReadAccess).useBytes(::OpaqueCredential)
                retained = copy
                activeNamedReads[copy] = CompletableDeferred()
                copy to view
            }
            val credentials = LocalGatewayCredentials(original.peer.connectionId, access)
            val before = original.client.current(credentials) as? LocalSessionViewResult.Current
                ?: error("Independent capture read has no current authority")
            check(sameCaptureSession(expected, before.view) && original.isCurrent())
            requireNamedReadOwner(access, expected)
            val reply = original.client.read(credentials, request) as? ReadReply.Data
                ?: error("Independent capture read is unavailable; no empty success is substituted")
            requireNamedReadOwner(access, expected)
            val after = original.client.current(credentials) as? LocalSessionViewResult.Current
                ?: error("Independent capture read lost current authority")
            check(sameCaptureSession(expected, after.view) && original.isCurrent())
            requireNamedReadOwner(access, expected)
            return reply
        } catch (failure: Throwable) { clearAccess(); throw failure }
        finally { synchronized(gate) {
            retained?.let { access -> access.destroy(); activeNamedReads.remove(access)?.complete(Unit) }
        } }
    }

    private fun requireNamedQuery(request: ReadRequest) {
        when (request.query) {
            LocalPurchasingProfile.READ_INVOICES, LocalPurchasingProfile.READ -> {
                val query = LocalPurchasingCodec.decodeQuery(request.payload)
                val section = if (request.query == LocalPurchasingProfile.READ_INVOICES) LocalPurchasingSectionV1.INVOICE
                    else LocalPurchasingSectionV1.ORDER
                check(query.section == section && query.limit == 1 && query.after == null && !query.export &&
                    LocalPurchasingCodec.query(query) == request.payload)
            }
            LocalConfigurationProfile.QUERY -> {
                val query = LocalConfigurationCodec.decodeQuery(request.payload)
                check(query.kind in setOf(LocalConfigurationProfile.SUPPLIER, LocalConfigurationProfile.SKU) &&
                    query.limit == 1 && query.id != null && query.after == null && query.barcode == null &&
                    LocalConfigurationCodec.query(query) == request.payload)
            }
            else -> error("Independent named capture read is outside the bounded public contract")
        }
    }

    private fun captureIssued(tokens: LocalSessionTokens, connectionId: DomainId) = synchronized(gate) {
        check(!retired && original.isCurrent() && connectionId == original.peer.connectionId)
        val c = original.material.coordinates
        val view = tokens.view
        check(view.scope == c.scope && view.actorId == c.firstActorId && view.deviceId == c.deviceId &&
            view.login == NativeInvoiceCaptureFixture.login)
        NativeInvoiceCaptureFixture.requireCapturePolicy(view.policy, c)
        val copy = tokens.access.useBytes(::OpaqueCredential)
        clearAccessLocked()
        namedReadAccess = copy
        issued = view
    }

    private fun requireNamedReadOwner(access: OpaqueCredential, expected: LocalSessionView) = synchronized(gate) {
        check(!retired && original.isCurrent() && access in activeNamedReads && namedReadAccess != null &&
            verified?.let { sameCaptureSession(expected, it) } == true) { "Named-read owner retired before delivery" }
    }

    fun retire() = synchronized(gate) { retired = true; clearAccessLocked() }
    suspend fun awaitRetiredNamedReads() {
        val pending = synchronized(gate) { check(retired); activeNamedReads.values.toList() }
        pending.forEach { it.await() }
        check(synchronized(gate) { activeNamedReads.isEmpty() && namedReadAccess == null })
    }
    private fun clearAccess() = synchronized(gate) { clearAccessLocked() }
    private fun clearAccessLocked() {
        namedReadAccess?.destroy(); namedReadAccess = null; issued = null; verified = null
        // Wiping is immediate, but actual calls keep their completion obligation until finally.
        activeNamedReads.keys.forEach { it.destroy() }
    }
}

internal fun sameCaptureSession(a: LocalSessionView, b: LocalSessionView): Boolean =
    a.actorId == b.actorId && a.scope == b.scope && a.deviceId == b.deviceId && a.sessionId == b.sessionId &&
        a.generation == b.generation && a.login == b.login && a.policy.revision == b.policy.revision

internal fun requireSameCaptureResult(a: MutationReply.Recorded, b: MutationReply.Recorded) {
    check(a.operationId == b.operationId && a.originalActorId == b.originalActorId && a.committed == b.committed &&
        a.code == b.code && a.result == b.result && a.authorityEpoch == b.authorityEpoch &&
        a.sequence == b.sequence && a.recordedAt == b.recordedAt && a.physicalStart == null && b.physicalStart == null) {
        "The immutable original capture result changed"
    }
}

internal fun captureBase64(bytes: StoredBytes): String {
    val copy = bytes.copyBytes()
    return try { Base64.encodeToString(copy, Base64.NO_WRAP) } finally { copy.fill(0) }
}
