package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterRpcPeerRegistration
import com.ukkera.pos.database.LocalMasterRpcBusinessAccess
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.LocalMasterWarehouseRpcProtocolV1
import com.ukkera.pos.database.LocalMasterReportRpcProtocolV1
import com.ukkera.pos.database.LocalMasterStockTransferRpcProtocolV1
import com.ukkera.pos.database.LocalMasterKitchenRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRetailOrderRpcProtocolV1
import com.ukkera.pos.database.LocalMasterPaymentCorrectionRpcProtocolV1
import com.ukkera.pos.database.LocalMasterCustomerReceiptRpcProtocolV1
import com.ukkera.pos.database.LocalMasterFoodCheckRpcProtocolV1
import com.ukkera.pos.database.LocalMasterFoodCashFinancialRpcProtocolV1
import com.ukkera.pos.database.LocalMasterFoodTableAssignmentRpcProtocolV1
import com.ukkera.pos.database.LocalMasterInvoiceCaptureRpcProtocolV1
import com.ukkera.pos.database.LocalMasterInvoiceMatchingRpcProtocolV1
import com.ukkera.pos.database.LocalMasterClientRpcProtocolV1
import com.ukkera.pos.database.LocalMasterClientRpcErrorV1
import com.ukkera.pos.database.LocalMasterRpcOwnedRequestV1
import com.ukkera.pos.database.LocalMasterRpcReadRequestV1
import com.ukkera.pos.database.LocalMasterRpcReadResponseV1
import com.ukkera.pos.database.LocalMasterRpcExecuteRequestV1
import com.ukkera.pos.database.LocalMasterRpcExecuteResponseV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestV1
import com.ukkera.pos.database.LocalMasterRpcLookupResponseV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.database.LocalMasterRpcRegisteredPeer
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import dev.p2pkit.rpc.RpcApplicationSession
import dev.p2pkit.rpc.RpcAuthenticationContext
import dev.p2pkit.rpc.RpcAuthorizationContext
import dev.p2pkit.rpc.RpcAuthorizationOperation
import dev.p2pkit.rpc.RpcAuthorizationScope
import dev.p2pkit.rpc.RpcCallContext
import dev.p2pkit.rpc.RpcHostConfiguration
import dev.p2pkit.rpc.RpcPhysicalSession
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

/** Self-session identity only; neither grants nor business/resource authority are returned. */
@Serializable
internal class LocalRpcCurrentIdentityResponse(
    val organizationId: String,
    val companyId: String,
    val branchId: String,
    val actorId: String,
    val deviceId: String,
    val sessionId: String,
    val sessionGeneration: Long,
    val admissionRevision: Long,
) {
    override fun toString(): String = "LocalRpcCurrentIdentityResponse([REDACTED])"
}

@Serializable
internal enum class LocalRpcSessionError { UNAVAILABLE }

internal val currentIdentityProcedure = RpcProcedure(
    name = "pos.session.current", version = 1,
    request = LocalRpcCurrentIdentityRequest.serializer(),
    response = LocalRpcCurrentIdentityResponse.serializer(),
    applicationError = LocalRpcSessionError.serializer(),
    requestLimitBytes = CURRENT_IDENTITY_REQUEST_LIMIT_BYTES, responseLimitBytes = 2048, errorLimitBytes = 64,
)

/** Own the SDK's separately decoded handler request even if Execute authorization refuses it. */
internal fun currentIdentityProcedureWithCustody(custody: LocalRpcBusinessRequestCustody) = RpcProcedure(
    currentIdentityProcedure.name, currentIdentityProcedure.version,
    custody.serializer(currentIdentityProcedure.request, { it.isCleanupComplete },
        { it.destroyIfUnclaimed() }, { it.destroy() }, { it.awaitCleanup() }),
    currentIdentityProcedure.response, currentIdentityProcedure.applicationError, currentIdentityProcedure.retrySafety,
    currentIdentityProcedure.requestLimitBytes, currentIdentityProcedure.responseLimitBytes, currentIdentityProcedure.errorLimitBytes,
)

/**
 * Private registration installed only in the actual retained original Host factory closure.
 * A public physical descriptor/capture method cannot enter this source. The database token
 * joins admission; this callback closure supplies the separate actual Host-origin evidence.
 * Current employee/session/device state is checked again for every control and disclosure.
 * Identity/status and authentication exchange remain separate from fixed directory, report,
 * stock-transfer and kitchen scopes. Every business scope borrows current resource authorization.
 */
internal class LocalRpcMasterSessionIngress(
    private val registration: LocalMasterRpcPeerRegistration, cleanupScope: CoroutineScope,
    private val parent: Job,
) {
    private val authenticationIngress = LocalRpcMasterAuthenticationIngress(registration, cleanupScope, parent)
    private val businessRequestCustody = LocalRpcBusinessRequestCustody(::isRetired)
    private val currentIdentityRequestCustody = LocalRpcBusinessRequestCustody(::isRetired)
    private class Entry(
        val physical: RpcPhysicalSession,
        val sessionId: DomainId,
        val peer: LocalMasterRpcRegisteredPeer,
        val binding: LocalRpcIdentityBinding,
        val scope: RpcAuthorizationScope,
        val businessAccess: LocalMasterRpcBusinessAccess? = null,
        val businessBearerDigest: PayloadDigest? = null,
    )
    private val gate = Mutex()
    private val retired = MutableStateFlow(false)
    private val entries = mutableListOf<Entry>()
    private val authenticationGate = Mutex()
    private var callbacks = 0
    private val drained = CompletableDeferred<Unit>()
    private val failedCleanups = mutableListOf<LocalRpcIdentityBinding>()
    private val callbackSlots = Semaphore(MAXIMUM_CALLBACKS)
    private var cleanupFailure: Throwable? = null
    private val cleanupJob = cleanupScope.launch(start = CoroutineStart.LAZY) {
        try {
            while (isActive && !isRetired()) {
                delay(1000)
                authenticationGate.withLock { discardExpiredEntries() }
                businessRequestCustody.reap()
                currentIdentityRequestCustody.reap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            cleanupFailure = failure
            deny()
        }
    }
    init { cleanupJob.start() }

    fun install(configuration: RpcHostConfiguration) {
        configuration.registerScopedWithPhysicalSession(currentIdentityProcedureWithCustody(currentIdentityRequestCustody),
            authenticate = ::authenticate,
            authorize = ::authorize,
            handler = ::currentIdentity)
        authenticationIngress.install(configuration)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.SUPPLIERS,
            LocalMasterClientRpcProtocolV1.READ, LocalMasterClientRpcProtocolV1.EXECUTE, LocalMasterClientRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.WAREHOUSES,
            LocalMasterWarehouseRpcProtocolV1.READ, LocalMasterWarehouseRpcProtocolV1.EXECUTE, LocalMasterWarehouseRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.REPORTS,
            LocalMasterReportRpcProtocolV1.READ, LocalMasterReportRpcProtocolV1.EXECUTE, LocalMasterReportRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
            LocalMasterStockTransferRpcProtocolV1.READ, LocalMasterStockTransferRpcProtocolV1.EXECUTE, LocalMasterStockTransferRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.KITCHEN,
            LocalMasterKitchenRpcProtocolV1.READ, LocalMasterKitchenRpcProtocolV1.EXECUTE, LocalMasterKitchenRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.RETAIL_ORDERS,
            LocalMasterRetailOrderRpcProtocolV1.READ, LocalMasterRetailOrderRpcProtocolV1.EXECUTE, LocalMasterRetailOrderRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS,
            LocalMasterPaymentCorrectionRpcProtocolV1.READ, LocalMasterPaymentCorrectionRpcProtocolV1.EXECUTE,
            LocalMasterPaymentCorrectionRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
            LocalMasterCustomerReceiptRpcProtocolV1.READ, LocalMasterCustomerReceiptRpcProtocolV1.EXECUTE,
            LocalMasterCustomerReceiptRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.FOOD_CHECKS,
            LocalMasterFoodCheckRpcProtocolV1.READ, LocalMasterFoodCheckRpcProtocolV1.EXECUTE,
            LocalMasterFoodCheckRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL,
            LocalMasterFoodCashFinancialRpcProtocolV1.READ, LocalMasterFoodCashFinancialRpcProtocolV1.EXECUTE,
            LocalMasterFoodCashFinancialRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS,
            LocalMasterFoodTableAssignmentRpcProtocolV1.READ, LocalMasterFoodTableAssignmentRpcProtocolV1.EXECUTE,
            LocalMasterFoodTableAssignmentRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE,
            LocalMasterInvoiceCaptureRpcProtocolV1.READ, LocalMasterInvoiceCaptureRpcProtocolV1.EXECUTE,
            LocalMasterInvoiceCaptureRpcProtocolV1.LOOKUP)
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.INVOICE_MATCHING,
            LocalMasterInvoiceMatchingRpcProtocolV1.READ, LocalMasterInvoiceMatchingRpcProtocolV1.EXECUTE,
            LocalMasterInvoiceMatchingRpcProtocolV1.LOOKUP)
    }

    /** The registration fixes the profile; no request field or configuration key chooses it. */
    private fun installBusiness(configuration: RpcHostConfiguration, profile: LocalMasterRpcWorkflowProfile,
        read: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1>,
        execute: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1>,
        lookup: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1>,
    ) {
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(read),
            authenticate = { context, request ->
                val access = LocalMasterRpcBusinessAccess.Read(request.request, profile)
                authenticateBusiness(context, request, access, resource(access))
            }, authorize = ::authorizeBusiness,
            handler = { context, request -> readBusiness(context, request, profile) })
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(execute),
            authenticate = { context, request ->
                val access = LocalMasterRpcBusinessAccess.Execute(request.request, profile)
                authenticateBusiness(context, request, access, resource(access))
            }, authorize = ::authorizeBusiness,
            handler = { context, request -> executeBusiness(context, request, profile) })
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(lookup),
            authenticate = { context, request ->
                val access = LocalMasterRpcBusinessAccess.Lookup(request.identity, profile)
                authenticateBusiness(context, request, access, resource(access))
            }, authorize = ::authorizeBusiness,
            handler = { context, request -> lookupBusiness(context, request, profile) })
    }

    /** Deny before awaiting any owned cleanup or invoking SDK/native operations. */
    fun deny() {
        retired.value = true
        businessRequestCustody.deny()
        currentIdentityRequestCustody.deny()
        authenticationIngress.deny()
        registration.retireAll()
    }

    // Cleanup has its own lifetime so it survives application cancellation. Callback
    // authority does not: consult the actual retained application Job synchronously.
    private fun isRetired(): Boolean = retired.value || !parent.isActive

    suspend fun closeOwned() = withContext(NonCancellable) {
        deny()
        // Denial alone cannot stop output whose authorization already returned true.
        // Await the exact SDK session cutoff before waiting for any owned callback.
        registration.awaitSessionRevocation()
        cleanupJob.cancelAndJoin()
        authenticationIngress.closeOwned()
        gate.withLock { if (callbacks == 0) drained.complete(Unit) }
        // RPC closes links but does not join application callbacks. Drain our exact owned work.
        drained.await()
        currentIdentityRequestCustody.destroyAfterDrain()
        authenticationGate.withLock {
            val owned = gate.withLock { entries.map { it.binding } + failedCleanups }
            var firstFailure: Throwable? = null
            for (binding in owned) {
                try { binding.retire() }
                catch (failure: Throwable) { if (firstFailure == null) firstFailure = failure }
            }
            (firstFailure ?: cleanupFailure)?.let { throw it }
            gate.withLock { entries.clear(); failedCleanups.clear() }
            businessRequestCustody.destroyAfterDrain()
        }
    }

    private suspend fun <T> withCallback(refuse: () -> T, cleanup: suspend () -> Unit = {}, work: suspend () -> T): T {
        var acquired = false
        var accepted = false
        try {
            acquired = callbackSlots.tryAcquire()
            if (!acquired) return refuse()
            accepted = gate.withLock {
                if (isRetired()) false else { callbacks++; true }
            }
            return if (accepted) work() else refuse()
        } finally {
            withContext(NonCancellable) {
                try { cleanup() }
                catch (failure: Throwable) {
                    cleanupFailure = failure
                    deny()
                    throw failure
                } finally {
                    if (accepted) gate.withLock {
                        callbacks--
                        if (retired.value && callbacks == 0) drained.complete(Unit)
                    }
                    if (acquired) callbackSlots.release()
                }
            }
        }
    }

    private suspend fun discardExpiredEntries() {
        val expired = gate.withLock {
            entries.filter { !it.scope.session.isActive || !it.physical.isConnected }
        }
        for (entry in expired) {
            registration.revokeApplicationSession(entry.scope.session)
            entry.binding.releaseExpiredApplicationSession()
            gate.withLock { entries.remove(entry) }
        }
    }

    private suspend fun authenticate(
        context: RpcAuthenticationContext,
        request: LocalRpcCurrentIdentityRequest,
    ): RpcAuthorizationScope? {
        if (!request.claimForCallback()) return null
        return withCallback({ null }, cleanup = {
            request.destroy()
            request.awaitCleanup()
            currentIdentityRequestCustody.reap()
        }) { authenticationGate.withLock { authenticateOwned(context, request) } }
    }

    private suspend fun authenticateOwned(
        context: RpcAuthenticationContext,
        request: LocalRpcCurrentIdentityRequest,
        businessAccess: LocalMasterRpcBusinessAccess? = null,
        resource: String = RESOURCE,
        businessBearerDigest: PayloadDigest? = null,
    ): RpcAuthorizationScope? {
        var bearer: OpaqueCredential? = null
        var unretained: LocalRpcIdentityBinding? = null
        var unretainedScope: RpcAuthorizationScope? = null
        try {
            currentCoroutineContext().ensureActive()
            if (isRetired() || request.access.size != 52) return null
            discardExpiredEntries()
            // Capture before the first authoritative read. A security write or Host
            // retirement that intervenes must reject even a freshly checked login.
            val epoch = registration.captureSessionEpoch() ?: return null
            val peer = registration.register(context.physicalSession) ?: return null
            currentCoroutineContext().ensureActive()
            if (isRetired() || !peer.isCurrent() || context.peer != context.physicalSession.peer) return null
            val credential = OpaqueCredential(request.access).also { bearer = it }
            val credentials = LocalGatewayCredentials(peer.nativePeer.connectionId, credential)
            // Never adopt an existing entry without first validating THIS request's bearer.
            val principal = registration.principals.authenticateLocal(credentials)
            currentCoroutineContext().ensureActive()
            if (principal == null || principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE ||
                principal.scope != peer.nativePeer.scope || principal.deviceId != peer.nativePeer.deviceId) return null
            val existing = gate.withLock {
                if (isRetired() || businessAccess != null) null else entries.firstOrNull {
                    it.businessAccess == null && it.physical === context.physicalSession && it.sessionId == principal.sessionId
                }
            }
            if (existing != null) {
                val scope = existing.binding.authorizationScope(context, RESOURCE) ?: return null
                val original = existing.binding.originalIdentityFor(scope) ?: return null
                if (!registration.retainApplicationSession(epoch, scope.session, original)) return null
                return scope.takeIf { !isRetired() && peer.isCurrent() && it.session.isActive }
            }
            if (gate.withLock { isRetired() || entries.size + failedCleanups.size >= MAXIMUM_LOGINS }) return null
            val lifetime = registration.remainingSessionLifetimeMillis(credentials) ?: return null
            currentCoroutineContext().ensureActive()
            // Terminal SDK acknowledgement/record eviction does not revoke its application
            // session. Bound per-request business custody independently of the employee's
            // longer login, so the existing expiry cleanup can reclaim its finite census.
            // Expiry with an uncertain delivery preserves the durable original for fresh lookup.
            val bindingLifetime = if (businessAccess == null) lifetime
                else minOf(lifetime, MAXIMUM_BUSINESS_SESSION_LIFETIME_MILLIS)
            val binding = LocalRpcIdentityBinding.bindAssociated(context, credentials, registration.principals,
                LocalRpcAssociatedPhysicalPeerProvenance { physical ->
                    if (isRetired() || physical !== peer.physicalSession || !peer.isCurrent()) null
                    else LocalRpcAssociatedPhysicalPeer(peer.nativePeer, peer.association)
                }, bindingLifetime.milliseconds) ?: return null
            unretained = binding
            val scope = binding.authorizationScope(context, resource) ?: return null
            unretainedScope = scope
            currentCoroutineContext().ensureActive()
            if (scope.session.sessionId != principal.sessionId.value) return null
            // No native read or binding cleanup runs under the database's short fence.
            // Retain before local publication so close/write owns this exact SDK session.
            val original = binding.originalIdentityFor(scope) ?: return null
            if (businessAccess != null) {
                // Audience checks require the retained exact resource session. Refusal
                // leaves this candidate owned by the existing revocation cleanup below.
                if (!registration.retainResourceApplicationSession(epoch, scope.session, original) ||
                    !registration.authorizeBusiness(credentials, businessAccess, requireRecovery = false,
                        peer = peer, resourceSession = scope.session)) return null
            } else if (!registration.retainApplicationSession(epoch, scope.session, original)) return null
            val published = gate.withLock {
                if (isRetired() || !peer.isCurrent() || !scope.session.isActive || entries.size >= MAXIMUM_LOGINS) null
                else if (businessAccess == null && entries.any { it.businessAccess == null &&
                        it.physical === context.physicalSession && it.sessionId == principal.sessionId }) null
                else scope.also {
                    entries.add(Entry(context.physicalSession, principal.sessionId, peer, binding, scope, businessAccess, businessBearerDigest))
                    unretained = null
                    unretainedScope = null
                }
            }
            return published
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return null
        } finally {
            bearer?.destroy()
            withContext(NonCancellable) {
                unretained?.let { binding ->
                    try {
                        val candidate = unretainedScope
                        if (candidate == null) binding.retire()
                        else {
                            registration.revokeApplicationSession(candidate.session)
                            // This candidate never escaped into a Host entry. A rejected
                            // epoch must release its login, not close a shared current link.
                            binding.releaseExpiredApplicationSession()
                        }
                    }
                    catch (failure: Throwable) {
                        gate.withLock { failedCleanups.add(binding) }
                        throw failure
                    }
                }
            }
        }
    }

    private fun <Q : LocalMasterRpcOwnedRequestV1, R> ownedBusinessProcedure(
        shared: RpcProcedure<Q, R, LocalMasterClientRpcErrorV1>,
    ): RpcProcedure<Q, R, LocalMasterClientRpcErrorV1> = RpcProcedure(
        shared.name, shared.version,
        businessRequestCustody.serializer(shared.request, { it.isCleanupComplete },
            { it.destroyIfUnclaimed() }, { it.destroy() }, { it.awaitCleanup() }),
        shared.response, shared.applicationError, shared.retrySafety,
        shared.requestLimitBytes, shared.responseLimitBytes, shared.errorLimitBytes)

    private suspend fun authenticateBusiness(context: RpcAuthenticationContext,
        request: LocalMasterRpcOwnedRequestV1, access: LocalMasterRpcBusinessAccess,
        resource: String): RpcAuthorizationScope? {
        if (!request.claimForCallback()) return null
        return withCallback({ null }, cleanup = {
            request.destroy()
            request.awaitCleanup()
            businessRequestCustody.reap()
        }) {
            val temporary = LocalRpcCurrentIdentityRequest(request.useBearer { it.copyOf() })
            try {
                authenticationGate.withLock {
                    authenticateOwned(context, temporary, access, resource, request.useBearer(::sha256))
                }
            } finally { temporary.destroy() }
        }
    }

    private suspend fun authorizeBusiness(context: RpcAuthorizationContext): Boolean = withCallback({ false }) {
        if (isRetired()) return@withCallback false
        val entry = gate.withLock { entries.firstOrNull { it.scope.session === context.scope.session } }
            ?: return@withCallback false
        val access = entry.businessAccess ?: return@withCallback false
        if (context.procedureName != procedureName(access) || context.procedureVersion != 1 ||
            context.scope.resource != resource(access) || !entry.peer.isCurrent()) return@withCallback false
        val credentials = entry.binding.currentOriginalCredentials(context) ?: return@withCallback false
        try {
            val recovery = context.operation != RpcAuthorizationOperation.Invoke && context.operation != RpcAuthorizationOperation.Execute
            val allowed = registration.authorizeBusiness(credentials, access, requireRecovery = recovery,
                peer = entry.peer, resourceSession = entry.scope.session)
            currentCoroutineContext().ensureActive()
            allowed && !isRetired() && entry.peer.isCurrent() && entry.scope.session.isActive
        } finally { credentials.bearer.destroy() }
    }

    private suspend fun readBusiness(context: RpcCallContext, request: LocalMasterRpcReadRequestV1,
        profile: LocalMasterRpcWorkflowProfile):
        RpcReply<LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        businessReply(context, request, LocalMasterRpcBusinessAccess.Read(request.request, profile),
            { credentials, peer, resourceSession ->
                registration.readBusiness(credentials, request.request, profile, peer, resourceSession)
            }, LocalMasterClientRpcProtocolV1::captureRead)

    private suspend fun executeBusiness(context: RpcCallContext, request: LocalMasterRpcExecuteRequestV1,
        profile: LocalMasterRpcWorkflowProfile):
        RpcReply<LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        businessReply(context, request, LocalMasterRpcBusinessAccess.Execute(request.request, profile),
            { credentials, peer, resourceSession ->
                registration.executeBusiness(credentials, request.request, profile, peer, resourceSession)
            }, LocalMasterClientRpcProtocolV1::captureExecute)

    private suspend fun lookupBusiness(context: RpcCallContext, request: LocalMasterRpcLookupRequestV1,
        profile: LocalMasterRpcWorkflowProfile):
        RpcReply<LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        businessReply(context, request, LocalMasterRpcBusinessAccess.Lookup(request.identity, profile),
            { credentials, peer, resourceSession ->
                registration.lookupBusiness(credentials, request.identity, profile, peer, resourceSession)
            }, LocalMasterClientRpcProtocolV1::captureLookup)

    private suspend fun <T, R> businessReply(context: RpcCallContext, request: LocalMasterRpcOwnedRequestV1,
        received: LocalMasterRpcBusinessAccess,
        call: suspend (LocalGatewayCredentials, LocalMasterRpcRegisteredPeer, RpcApplicationSession) -> T,
        capture: (DomainId, T) -> R): RpcReply<R, LocalMasterClientRpcErrorV1> {
        if (!request.claimForCallback()) return businessUnavailable()
        return withCallback({ businessUnavailable() }, cleanup = {
            request.destroy()
            request.awaitCleanup()
            businessRequestCustody.reap()
        }) {
            try {
                val entry = gate.withLock {
                    if (isRetired()) null else entries.firstOrNull { it.scope.session === context.authorizationScope?.session }
                } ?: return@withCallback businessUnavailable()
                val original = entry.businessAccess ?: return@withCallback businessUnavailable()
                // The fixed typed handler registration selects the procedure. RpcCallContext
                // carries the SDK-authorized scope, not procedure name/version metadata.
                if (!sameAccess(original, received) || context.authorizationScope?.resource != resource(original) ||
                    entry.businessBearerDigest != request.useBearer(::sha256) || !entry.peer.isCurrent())
                    return@withCallback businessUnavailable()
                val principal = entry.binding.originalIdentityFor(entry.scope) ?: return@withCallback businessUnavailable()
                val credentials = entry.binding.currentOriginalCredentials(context) ?: return@withCallback businessUnavailable()
                try {
                    if (!registration.authorizeBusiness(credentials, original, requireRecovery = false,
                            peer = entry.peer, resourceSession = entry.scope.session))
                        return@withCallback businessUnavailable()
                    val result = call(credentials, entry.peer, entry.scope.session)
                    currentCoroutineContext().ensureActive()
                    // The protected operation has settled. Revocation does not roll it back;
                    // a denied/stale output remains undisclosed and recoverable by its original.
                    if (!registration.authorizeBusiness(credentials, original, requireRecovery = true,
                            peer = entry.peer, resourceSession = entry.scope.session) ||
                        isRetired() || !entry.peer.isCurrent() || !entry.scope.session.isActive)
                        return@withCallback businessUnavailable()
                    RpcReply.Success(capture(principal.actorId, result))
                } finally { credentials.bearer.destroy() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); businessUnavailable() }
        }
    }

    private fun resource(access: LocalMasterRpcBusinessAccess): String = when (access.profile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> SUPPLIER_READ
            is LocalMasterRpcBusinessAccess.Execute -> SUPPLIER_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> SUPPLIER_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.WAREHOUSES -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> WAREHOUSE_READ
            is LocalMasterRpcBusinessAccess.Execute -> WAREHOUSE_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> WAREHOUSE_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.REPORTS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> REPORT_READ
            is LocalMasterRpcBusinessAccess.Execute -> REPORT_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> REPORT_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> STOCK_TRANSFER_READ
            is LocalMasterRpcBusinessAccess.Execute -> STOCK_TRANSFER_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> STOCK_TRANSFER_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.KITCHEN -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> KITCHEN_READ
            is LocalMasterRpcBusinessAccess.Execute -> KITCHEN_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> KITCHEN_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> RETAIL_ORDER_READ
            is LocalMasterRpcBusinessAccess.Execute -> RETAIL_ORDER_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> RETAIL_ORDER_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> PAYMENT_CORRECTION_READ
            is LocalMasterRpcBusinessAccess.Execute -> PAYMENT_CORRECTION_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> PAYMENT_CORRECTION_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> CUSTOMER_RECEIPT_READ
            is LocalMasterRpcBusinessAccess.Execute -> CUSTOMER_RECEIPT_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> CUSTOMER_RECEIPT_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> FOOD_CHECK_READ
            is LocalMasterRpcBusinessAccess.Execute -> FOOD_CHECK_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> FOOD_CHECK_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> FOOD_CASH_FINANCIAL_READ
            is LocalMasterRpcBusinessAccess.Execute -> FOOD_CASH_FINANCIAL_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> FOOD_CASH_FINANCIAL_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> FOOD_TABLE_ASSIGNMENT_READ
            is LocalMasterRpcBusinessAccess.Execute -> FOOD_TABLE_ASSIGNMENT_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> FOOD_TABLE_ASSIGNMENT_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> INVOICE_CAPTURE_READ
            is LocalMasterRpcBusinessAccess.Execute -> INVOICE_CAPTURE_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> INVOICE_CAPTURE_LOOKUP
        }
        LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> INVOICE_MATCHING_READ
            is LocalMasterRpcBusinessAccess.Execute -> INVOICE_MATCHING_EXECUTE
            is LocalMasterRpcBusinessAccess.Lookup -> INVOICE_MATCHING_LOOKUP
        }
    }
    private fun procedureName(access: LocalMasterRpcBusinessAccess): String = when (access.profile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterClientRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterClientRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterClientRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.WAREHOUSES -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterWarehouseRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterWarehouseRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterWarehouseRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.REPORTS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterReportRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterReportRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterReportRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterStockTransferRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterStockTransferRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterStockTransferRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.KITCHEN -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterKitchenRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterKitchenRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterKitchenRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterRetailOrderRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterRetailOrderRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterRetailOrderRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterPaymentCorrectionRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterPaymentCorrectionRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterPaymentCorrectionRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterCustomerReceiptRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterCustomerReceiptRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterCustomerReceiptRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterFoodCheckRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterFoodCheckRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterFoodCheckRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterFoodCashFinancialRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterFoodCashFinancialRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterFoodCashFinancialRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterFoodTableAssignmentRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterFoodTableAssignmentRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterFoodTableAssignmentRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterInvoiceCaptureRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterInvoiceCaptureRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterInvoiceCaptureRpcProtocolV1.LOOKUP.name
        }
        LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterInvoiceMatchingRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterInvoiceMatchingRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterInvoiceMatchingRpcProtocolV1.LOOKUP.name
        }
    }
    private fun sameAccess(left: LocalMasterRpcBusinessAccess, right: LocalMasterRpcBusinessAccess): Boolean = when {
        left.profile != right.profile -> false
        left is LocalMasterRpcBusinessAccess.Read && right is LocalMasterRpcBusinessAccess.Read ->
            left.request.query == right.request.query && left.request.payload == right.request.payload
        left is LocalMasterRpcBusinessAccess.Execute && right is LocalMasterRpcBusinessAccess.Execute ->
            left.request.operationId == right.request.operationId && left.request.command == right.request.command &&
                left.request.payload == right.request.payload && left.request.actionApprovalId == right.request.actionApprovalId
        left is LocalMasterRpcBusinessAccess.Lookup && right is LocalMasterRpcBusinessAccess.Lookup -> left.identity == right.identity
        else -> false
    }
    private fun <R> businessUnavailable(): RpcReply<R, LocalMasterClientRpcErrorV1> =
        RpcReply.ApplicationError(LocalMasterClientRpcErrorV1.UNAVAILABLE)

    private suspend fun authorize(context: RpcAuthorizationContext): Boolean =
        withCallback({ false }) { authorizeOwned(context) }

    private suspend fun authorizeOwned(context: RpcAuthorizationContext): Boolean {
        if (isRetired() || context.procedureName != currentIdentityProcedure.name ||
            context.procedureVersion != currentIdentityProcedure.version || context.scope.resource != RESOURCE) return false
        val entry = gate.withLock {
            entries.firstOrNull { it.scope.session === context.scope.session }
        } ?: return false
        if (!entry.peer.isCurrent()) return false
        val principal = entry.binding.currentOriginalPrincipal(context)
        currentCoroutineContext().ensureActive()
        return principal != null && !isRetired() && entry.peer.isCurrent()
    }

    private suspend fun currentIdentity(
        context: RpcCallContext,
        request: LocalRpcCurrentIdentityRequest,
    ): RpcReply<LocalRpcCurrentIdentityResponse, LocalRpcSessionError> {
        if (!request.claimForCallback()) return RpcReply.ApplicationError(LocalRpcSessionError.UNAVAILABLE)
        return withCallback({ RpcReply.ApplicationError(LocalRpcSessionError.UNAVAILABLE) }, cleanup = {
            request.destroy()
            request.awaitCleanup()
            currentIdentityRequestCustody.reap()
        }) { currentIdentityOwned(context) }
    }

    private suspend fun currentIdentityOwned(
        context: RpcCallContext,
    ): RpcReply<LocalRpcCurrentIdentityResponse, LocalRpcSessionError> {
        val entry = gate.withLock {
            if (isRetired()) null else entries.firstOrNull { it.scope.session === context.authorizationScope?.session }
        } ?: return RpcReply.ApplicationError(LocalRpcSessionError.UNAVAILABLE)
        val principal = entry.binding.currentOriginalPrincipal(context)
        currentCoroutineContext().ensureActive()
        if (principal == null || isRetired() || !entry.peer.isCurrent())
            return RpcReply.ApplicationError(LocalRpcSessionError.UNAVAILABLE)
        return RpcReply.Success(LocalRpcCurrentIdentityResponse(
            principal.scope.organizationId.value, principal.scope.companyId.value, principal.scope.branchId.value,
            principal.actorId.value, principal.deviceId.value, principal.sessionId.value,
            principal.sessionGeneration.value, principal.admissionRevision.value))
    }

    companion object {
        private const val RESOURCE = "pos.session.current"
        private const val WAREHOUSE_READ = "pos.warehouse.read"
        private const val WAREHOUSE_EXECUTE = "pos.warehouse.execute"
        private const val WAREHOUSE_LOOKUP = "pos.warehouse.lookup"
        private const val SUPPLIER_READ = "pos.supplier.read"
        private const val SUPPLIER_EXECUTE = "pos.supplier.execute"
        private const val SUPPLIER_LOOKUP = "pos.supplier.lookup"
        private const val REPORT_READ = "pos.report.read"
        private const val REPORT_EXECUTE = "pos.report.execute"
        private const val REPORT_LOOKUP = "pos.report.lookup"
        private const val STOCK_TRANSFER_READ = "pos.stock-transfer.read"
        private const val STOCK_TRANSFER_EXECUTE = "pos.stock-transfer.execute"
        private const val STOCK_TRANSFER_LOOKUP = "pos.stock-transfer.lookup"
        private const val KITCHEN_READ = "pos.kitchen.read"
        private const val KITCHEN_EXECUTE = "pos.kitchen.execute"
        private const val KITCHEN_LOOKUP = "pos.kitchen.lookup"
        private const val RETAIL_ORDER_READ = "pos.retail-order.read"
        private const val RETAIL_ORDER_EXECUTE = "pos.retail-order.execute"
        private const val RETAIL_ORDER_LOOKUP = "pos.retail-order.lookup"
        private const val PAYMENT_CORRECTION_READ = "pos.payment-correction.read"
        private const val PAYMENT_CORRECTION_EXECUTE = "pos.payment-correction.execute"
        private const val PAYMENT_CORRECTION_LOOKUP = "pos.payment-correction.lookup"
        private const val CUSTOMER_RECEIPT_READ = "pos.customer-receipt.read"
        private const val FOOD_TABLE_ASSIGNMENT_READ = "pos.food-table-assignment.read"
        private const val FOOD_TABLE_ASSIGNMENT_EXECUTE = "pos.food-table-assignment.execute"
        private const val FOOD_TABLE_ASSIGNMENT_LOOKUP = "pos.food-table-assignment.lookup"
        private const val INVOICE_CAPTURE_READ = "pos.invoice-capture.read"
        private const val INVOICE_CAPTURE_EXECUTE = "pos.invoice-capture.execute"
        private const val INVOICE_CAPTURE_LOOKUP = "pos.invoice-capture.lookup"
        private const val INVOICE_MATCHING_READ = "pos.invoice-matching.read"
        private const val INVOICE_MATCHING_EXECUTE = "pos.invoice-matching.execute"
        private const val INVOICE_MATCHING_LOOKUP = "pos.invoice-matching.lookup"
        private const val FOOD_CHECK_READ = "pos.food-check.read"
        private const val FOOD_CASH_FINANCIAL_READ = "pos.food-cash-financial.read"
        private const val FOOD_CASH_FINANCIAL_EXECUTE = "pos.food-cash-financial.execute"
        private const val FOOD_CASH_FINANCIAL_LOOKUP = "pos.food-cash-financial.lookup"
        private const val CUSTOMER_RECEIPT_EXECUTE = "pos.customer-receipt.execute"
        private const val FOOD_CHECK_EXECUTE = "pos.food-check.execute"
        private const val CUSTOMER_RECEIPT_LOOKUP = "pos.customer-receipt.lookup"
        private const val FOOD_CHECK_LOOKUP = "pos.food-check.lookup"
        private const val MAXIMUM_LOGINS = 128
        private const val MAXIMUM_CALLBACKS = 128
        private const val MAXIMUM_BUSINESS_SESSION_LIFETIME_MILLIS = 120_000L
    }
}
