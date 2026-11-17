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
import com.ukkera.pos.database.LocalMasterLanBusinessRpcProtocolV1
import com.ukkera.pos.database.LocalMasterChangesRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRpcChangesRequestV1
import com.ukkera.pos.database.LocalMasterRpcChangesResponseV1
import com.ukkera.pos.database.LocalMasterChangesReplyV1
import com.ukkera.pos.database.LocalMasterChangesFailureV1
import com.ukkera.pos.database.LocalMasterChangesBatchRpcProtocolV1
import com.ukkera.pos.database.LocalMasterChangesBatchReplyV1
import com.ukkera.pos.database.LocalMasterRpcChangesBatchRequestV1
import com.ukkera.pos.database.LocalMasterRpcChangesBatchResponseV1
import com.ukkera.pos.database.LocalMasterChangesBindingV1
import com.ukkera.pos.database.LocalMasterChangesRpcAccessResultV1
import com.ukkera.pos.database.LocalMasterChangesRpcAuthorizationV1
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.LocalMasterRpcReadStage
import com.ukkera.pos.database.LocalMasterRpcReadTrace
import com.ukkera.pos.database.LocalMasterRpcReadTracePhase
import com.ukkera.pos.database.reportLocalMasterRpcReadFailure
import com.ukkera.pos.database.LocalMasterClientRpcErrorV1
import com.ukkera.pos.database.LocalMasterRpcOwnedRequestV1
import com.ukkera.pos.database.LocalMasterEmployeeCreationRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRpcEmployeeCreationRequestV1
import com.ukkera.pos.database.LocalMasterRpcEmployeeCreationResponseV1
import com.ukkera.pos.database.LocalMasterRpcReadRequestV1
import com.ukkera.pos.database.LocalMasterRpcReadResponseV1
import com.ukkera.pos.database.LocalMasterRpcExecuteRequestV1
import com.ukkera.pos.database.LocalMasterRpcExecuteResponseV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestV1
import com.ukkera.pos.database.LocalMasterRpcLookupResponseV1
import com.ukkera.pos.database.LocalMasterPhysicalRpcProtocolV1
import com.ukkera.pos.database.LocalMasterPhysicalRpcRequestV1
import com.ukkera.pos.database.LocalMasterPhysicalRpcResponseV1
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
import kotlin.time.Duration.Companion.seconds

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
    private val ownsRegistration: Boolean = true,
) {
    private val authenticationIngress = LocalRpcMasterAuthenticationIngress(registration, cleanupScope, parent, ownsRegistration)
    private val physicalOriginalIngress = LocalRpcMasterPhysicalOriginalIngress(registration, cleanupScope, parent)
    private val physicalCalls = LocalRpcPhysicalCallScopes()
    private val physicalReplies = LocalRpcPhysicalReplyRetention<com.ukkera.pos.database.LocalMasterPhysicalRpcDelivery>()
    private val businessRequestCustody = LocalRpcBusinessRequestCustody(::isRetired)
    private val currentIdentityRequestCustody = LocalRpcBusinessRequestCustody(::isRetired)
    private class Entry(
        val physical: RpcPhysicalSession,
        val sessionId: DomainId,
        val peer: LocalMasterRpcRegisteredPeer,
        val binding: LocalRpcIdentityBinding,
        val scope: RpcAuthorizationScope,
        val businessBearerDigest: PayloadDigest? = null,
        /** A bounded per-request employee-creation scope; never reused as an identity entry. */
        val creation: Boolean = false,
        val changesProfile: LocalMasterRpcWorkflowProfile? = null,
        val operationProfile: LocalMasterRpcWorkflowProfile? = null,
        val physicalInteractive: Boolean = false,
    ) { val scoped: Boolean get() = operationProfile != null || creation || changesProfile != null || physicalInteractive }
    private val changesFamilies = LocalRpcChangesFamilies()
    private val businessOperations = LocalRpcBusinessOperations()
    private val gate = Mutex()
    private val retired = MutableStateFlow(false)
    private val entries = mutableListOf<Entry>()
    private val maintenanceGate = Mutex()
    private val authenticationAdmissions = LocalRpcAuthenticationAdmission(MAXIMUM_CALLBACKS)
    private var callbacks = 0
    private val drained = CompletableDeferred<Unit>()
    private val failedCleanups = mutableListOf<LocalRpcIdentityBinding>()
    private val callbackSlots = Semaphore(MAXIMUM_CALLBACKS)
    private var cleanupFailure: Throwable? = null
    private val cleanupJob = cleanupScope.launch(start = CoroutineStart.LAZY) {
        try {
            while (isActive && !isRetired()) {
                delay(1000)
                maintenanceGate.withLock { discardExpiredEntries() }
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
        physicalOriginalIngress.install(configuration)
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(LocalMasterPhysicalRpcProtocolV1.INTERACTIVE),
            authenticate = ::authenticatePhysical, authorize = ::authorizePhysical, handler = ::physicalInteractive)
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
        installBusiness(configuration, LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
            LocalMasterLanBusinessRpcProtocolV1.READ, LocalMasterLanBusinessRpcProtocolV1.EXECUTE,
            LocalMasterLanBusinessRpcProtocolV1.LOOKUP)
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(LocalMasterChangesBatchRpcProtocolV1.SNAPSHOT),
            authenticate = { context, request -> authenticateChanges(context, request,
                LocalMasterRpcWorkflowProfile.LAN_BUSINESS, request.selections,
                LocalMasterChangesBatchRpcProtocolV1.SNAPSHOT.name, request.bindingDigest) },
            authorize = ::authorizeChanges,
            handler = ::snapshotBatchBusiness)
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(LocalMasterEmployeeCreationRpcProtocolV1.PROCEDURE),
            authenticate = ::authenticateCreation, authorize = ::authorizeCreation, handler = ::employeeCreation)
    }

    /** The registration fixes the profile; no request field or configuration key chooses it. */
    private fun installBusiness(configuration: RpcHostConfiguration, profile: LocalMasterRpcWorkflowProfile,
        read: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1>,
        execute: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1>,
        lookup: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1>,
    ) {
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(read),
            authenticate = { context, request ->
                authenticateChanges(context, request, profile, listOf(request.request), read.name,
                    LocalMasterChangesRpcProtocolV1.selectorBinding(request.request))
            }, authorize = ::authorizeChanges,
            handler = { context, request -> readBusiness(context, request, profile) })
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(execute),
            authenticate = { context, request ->
                val access = LocalMasterRpcBusinessAccess.Execute(request.request, profile)
                authenticateBusiness(context, request, access)
            }, authorize = ::authorizeBusiness,
            handler = { context, request -> executeBusiness(context, request, profile) })
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(lookup),
            authenticate = { context, request ->
                val access = LocalMasterRpcBusinessAccess.Lookup(request.identity, profile)
                authenticateBusiness(context, request, access)
            }, authorize = ::authorizeBusiness,
            handler = { context, request -> lookupBusiness(context, request, profile) })
        installChanges(configuration, profile)
    }

    /** Each registration fixes both the profile and the meaning of its progress field. */
    private fun installChanges(configuration: RpcHostConfiguration, profile: LocalMasterRpcWorkflowProfile) {
        val procedures = LocalMasterChangesRpcProtocolV1.procedures(profile)
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(procedures.SNAPSHOT),
            authenticate = { context, request ->
                authenticateChanges(context, request, profile, listOf(request.selection), procedures.SNAPSHOT.name, request.bindingDigest)
            }, authorize = ::authorizeChanges,
            handler = { context, request -> changesBusiness(context, request, profile, pull = false) })
        configuration.registerScopedWithPhysicalSession(ownedBusinessProcedure(procedures.PULL),
            authenticate = { context, request ->
                authenticateChanges(context, request, profile, listOf(request.selection), procedures.PULL.name, request.bindingDigest)
            }, authorize = ::authorizeChanges,
            handler = { context, request -> changesBusiness(context, request, profile, pull = true) })
    }

    /** Deny before awaiting any owned cleanup or invoking SDK/native operations. */
    fun deny() {
        retired.value = true
        businessRequestCustody.deny()
        currentIdentityRequestCustody.deny()
        authenticationIngress.deny()
        physicalOriginalIngress.deny()
        if (ownsRegistration) registration.retireAll()
    }

    internal fun isCurrentForServing(): Boolean = !isRetired()

    // Cleanup has its own lifetime so it survives application cancellation. Callback
    // authority does not: consult the actual retained application Job synchronously.
    private fun isRetired(): Boolean = retired.value || !parent.isActive

    suspend fun closeOwned() = withContext(NonCancellable) {
        deny()
        // Denial alone cannot stop output whose authorization already returned true.
        // Await the exact SDK session cutoff before waiting for any owned callback.
        if (ownsRegistration) registration.awaitSessionRevocation()
        else {
            // Local denial prevents further entry publication. Every scope that could
            // already have escaped an authenticate callback is now in this exact list.
            // Candidates not published here are refused and revoked by their retained
            // callback before drain can complete; they cannot authorize an SDK output.
            val sessions = gate.withLock { entries.map { it.scope.session } }
            sessions.forEach { registration.revokeApplicationSession(it) }
        }
        cleanupJob.cancelAndJoin()
        authenticationIngress.closeOwned()
        physicalOriginalIngress.closeOwned()
        gate.withLock { if (callbacks == 0) drained.complete(Unit) }
        // RPC closes links but does not join application callbacks. Drain our exact owned work.
        drained.await()
        currentIdentityRequestCustody.destroyAfterDrain()
        maintenanceGate.withLock {
            val owned = gate.withLock { entries.map { it.binding } + failedCleanups }
            var firstFailure: Throwable? = null
            for (binding in owned) {
                try { binding.retire() }
                catch (failure: Throwable) { if (firstFailure == null) firstFailure = failure }
            }
            (firstFailure ?: cleanupFailure)?.let { throw it }
            changesFamilies.removeAllRevoked()
            businessOperations.removeAllRevoked()
            physicalCalls.removeAllRevoked()
            physicalReplies.removeAllRevoked()
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
            changesFamilies.removeRevoked(entry.scope.session)
            businessOperations.removeRevoked(entry.scope.session)
            physicalCalls.removeRevoked(entry.scope.session)
            physicalReplies.removeRevoked(entry.scope.session)
            gate.withLock { entries.remove(entry) }
        }
    }

    /** Same physical callback admission remains serial; an unrelated Client must not
     * spend its SDK authentication budget waiting for another connection's native reads.
     * Callback slots bound all queued/active lanes, and close drains callbacks before cleanup. */
    private suspend fun withAuthenticationAdmission(context: RpcAuthenticationContext,
        work: suspend () -> RpcAuthorizationScope?): RpcAuthorizationScope? {
        val trace = LocalMasterRpcReadTrace(LocalMasterRpcReadTracePhase.AUTHENTICATION_QUEUE,
            LocalMasterRpcReadStage.BASE_SCOPE)
        return try {
            authenticationAdmissions.withPhysical(context.physicalSession, { null }) {
                trace.finish()
                work()
            }
        } finally { trace.finish() }
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
        }) { withAuthenticationAdmission(context) { authenticateOwned(context, request) } }
    }

    private suspend fun authenticateOwned(
        context: RpcAuthenticationContext,
        request: LocalRpcCurrentIdentityRequest,
        resource: String = RESOURCE,
        businessBearerDigest: PayloadDigest? = null,
        creation: Boolean = false,
        physicalInteractive: Boolean = false,
    ): RpcAuthorizationScope? {
        val scoped = creation || physicalInteractive
        var bearer: OpaqueCredential? = null
        var unretained: LocalRpcIdentityBinding? = null
        var unretainedScope: RpcAuthorizationScope? = null
        val trace = LocalMasterRpcReadTrace(LocalMasterRpcReadTracePhase.AUTHENTICATE, LocalMasterRpcReadStage.REQUEST_SHAPE)
        var stage by trace
        var accepted = false
        var diagnosticFailure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            if (isRetired() || request.access.size != 52) return null
            discardExpiredEntries()
            // Capture before the first authoritative read. A security write or Host
            // retirement that intervenes must reject even a freshly checked login.
            stage = LocalMasterRpcReadStage.SESSION_EPOCH
            val epoch = registration.captureSessionEpoch() ?: return null
            stage = LocalMasterRpcReadStage.PEER
            val peer = registration.register(context.physicalSession) ?: return null
            currentCoroutineContext().ensureActive()
            if (isRetired() || !peer.isCurrent() || context.peer != context.physicalSession.peer) return null
            val credential = OpaqueCredential(request.access).also { bearer = it }
            val credentials = LocalGatewayCredentials(peer.nativePeer.connectionId, credential)
            // Never adopt an existing entry without first validating THIS request's bearer.
            stage = LocalMasterRpcReadStage.PRINCIPAL
            val principal = registration.principals.authenticateLocal(credentials)
            currentCoroutineContext().ensureActive()
            if (principal == null || principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE ||
                principal.scope != peer.nativePeer.scope || principal.deviceId != peer.nativePeer.deviceId) return null
            stage = LocalMasterRpcReadStage.BASE_SCOPE
            val candidates = gate.withLock {
                if (isRetired() || creation) emptyList() else entries.filter {
                    (if (physicalInteractive) it.physicalInteractive else !it.scoped) &&
                        it.physical === context.physicalSession && it.sessionId == principal.sessionId &&
                        it.businessBearerDigest == sha256(request.access)
                }
            }
            // A native continuation needs a current resource through its owned operation.
            // Keep the original finite SDK lifetime and leave previous replies/custody intact;
            // a nearly expired pool entry cannot admit a new physical invocation. A fresh
            // scope still passes all original principal, epoch and protected resource checks.
            val existing = if (physicalInteractive) candidates.lastOrNull {
                it.binding.canAdmitResource(RESOURCE_ADMISSION_BUDGET)
            } else candidates.firstOrNull()
            if (physicalInteractive && candidates.isNotEmpty() && existing == null)
                reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.RESOURCE_RETENTION, null)
            if (existing != null) {
                stage = LocalMasterRpcReadStage.SCOPED_BINDING
                // Ordinary CURRENT already freshly verified THIS bearer above. Reuse
                // only the exact SDK/physical owner and explicitly match its original;
                // the retained census below and CURRENT result checks remain mandatory.
                val scope = (if (!scoped) existing.binding.retainedOperationScope(context, existing.scope, resource)
                    else existing.binding.authorizationScope(context, resource)) ?: return null
                val original = existing.binding.originalIdentityFor(scope) ?: return null
                if (!samePrincipal(original, principal)) return null
                stage = LocalMasterRpcReadStage.RESOURCE_RETENTION
                // This exact identity-only object has remained in the revocation census.
                // Fresh original verification above plus its irreversible retained cut
                // survives unrelated writes; it cannot admit a new or resource session.
                if (physicalInteractive) {
                    if (!registration.physicalInteractiveSessionCurrent(credentials, existing.peer, scope.session)) return null
                } else if (!registration.existingIdentitySessionCurrent(scope.session, original)) return null
                stage = LocalMasterRpcReadStage.FINAL_STATE
                return scope.takeIf { !isRetired() && peer.isCurrent() && it.session.isActive }.also { accepted = it != null }
            }
            if (gate.withLock { isRetired() || entries.size + failedCleanups.size >= MAXIMUM_LOGINS }) return null
            stage = LocalMasterRpcReadStage.ORIGINAL_BINDING
            val lifetime = registration.remainingSessionLifetimeMillis(credentials) ?: return null
            currentCoroutineContext().ensureActive()
            // Terminal SDK acknowledgement/record eviction does not revoke its application
            // session. Bound per-request creation custody independently of the employee's
            // longer login, so the existing expiry cleanup can reclaim its finite census.
            // Expiry with an uncertain delivery preserves the durable original for fresh lookup.
            val bindingLifetime = if (!scoped) lifetime
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
            stage = LocalMasterRpcReadStage.RESOURCE_RETENTION
            if (creation || physicalInteractive) {
                // The creation service itself re-checks ADMINISTER and current state per call.
                if (!registration.retainResourceApplicationSession(epoch, scope.session, original)) return null
                if (creation && registration.employeeCreationService() == null) return null
                if (physicalInteractive && !registration.physicalInteractiveSessionCurrent(credentials, peer, scope.session)) return null
            } else if (!registration.retainApplicationSession(epoch, scope.session, original)) return null
            stage = LocalMasterRpcReadStage.PUBLICATION
            val published = gate.withLock {
                if (isRetired() || !peer.isCurrent() || !scope.session.isActive || entries.size >= MAXIMUM_LOGINS) null
                else if (!scoped && entries.any { !it.scoped &&
                        it.physical === context.physicalSession && it.sessionId == principal.sessionId &&
                        it.businessBearerDigest == sha256(request.access) }) null
                else scope.also {
                    entries.add(Entry(context.physicalSession, principal.sessionId, peer, binding, scope,
                        businessBearerDigest = businessBearerDigest ?: sha256(request.access), creation = creation,
                        physicalInteractive = physicalInteractive))
                    unretained = null
                    unretainedScope = null
                }
            }
            accepted = published != null
            return published
        } catch (cancelled: CancellationException) {
            diagnosticFailure = cancelled
            throw cancelled
        } catch (failure: Exception) {
            diagnosticFailure = failure
            currentCoroutineContext().ensureActive()
            return null
        } finally {
            trace.finish()
            if (!accepted) reportLocalMasterRpcReadFailure(stage, diagnosticFailure)
            bearer?.destroy()
            withContext(NonCancellable) {
                unretained?.let { binding ->
                    try {
                        val candidate = unretainedScope
                        if (candidate == null) binding.releaseUnpublishedApplicationSession()
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

    private fun <Q : LocalMasterRpcOwnedRequestV1, R, E> ownedBusinessProcedure(
        shared: RpcProcedure<Q, R, E>,
    ): RpcProcedure<Q, R, E> = RpcProcedure(
        shared.name, shared.version,
        businessRequestCustody.serializer(shared.request, { it.isCleanupComplete },
            { it.destroyIfUnclaimed() }, { it.destroy() }, { it.awaitCleanup() }),
        shared.response, shared.applicationError, shared.retrySafety,
        shared.requestLimitBytes, shared.responseLimitBytes, shared.errorLimitBytes)

    private suspend fun authenticateBusiness(context: RpcAuthenticationContext,
        request: LocalMasterRpcOwnedRequestV1, access: LocalMasterRpcBusinessAccess,
        procedureNameOverride: String? = null, requestBinding: PayloadDigest? = null): RpcAuthorizationScope? {
        if (!request.claimForCallback()) return null
        return withCallback({ null }, cleanup = {
            request.destroy(); request.awaitCleanup(); businessRequestCustody.reap()
        }) { withAuthenticationAdmission(context) {
            authenticateBusinessOwned(context, request, access, procedureNameOverride ?: procedureName(access), requestBinding)
        } }
    }

    /** One genuine operation session per exact physical/principal/profile, with immutable call DATA. */
    private suspend fun authenticateBusinessOwned(context: RpcAuthenticationContext, request: LocalMasterRpcOwnedRequestV1,
        access: LocalMasterRpcBusinessAccess, procedure: String, requestBinding: PayloadDigest?): RpcAuthorizationScope? {
        var bearer: OpaqueCredential? = null
        var unretained: LocalRpcIdentityBinding? = null
        var unretainedScope: RpcAuthorizationScope? = null
        val trace = LocalMasterRpcReadTrace(LocalMasterRpcReadTracePhase.AUTHENTICATE, LocalMasterRpcReadStage.REQUEST_SHAPE)
        var stage by trace
        var accepted = false
        var diagnosticFailure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            if (isRetired() || access is LocalMasterRpcBusinessAccess.Read) return null
            discardExpiredEntries()
            stage = LocalMasterRpcReadStage.SESSION_EPOCH
            val epoch = registration.captureSessionEpoch() ?: return null
            stage = LocalMasterRpcReadStage.PEER
            val peer = registration.register(context.physicalSession) ?: return null
            if (isRetired() || !peer.isCurrent() || context.peer != context.physicalSession.peer) return null
            val credential = request.useBearer(::OpaqueCredential).also { bearer = it }
            val credentials = LocalGatewayCredentials(peer.nativePeer.connectionId, credential)
            val bearerDigest = request.useBearer(::sha256)
            fun reusable(entry: Entry): Boolean = entry.operationProfile == access.profile &&
                entry.physical === context.physicalSession && entry.peer === peer && entry.businessBearerDigest == bearerDigest
            // LAN's protected authorization below freshly verifies the bearer and matches
            // the exact retained original after its queue wait. Selection here is only DATA;
            // it supplies no current employee authority. Other profiles retain their separate
            // preliminary verification. A new binding always performs fresh verification.
            val retained = if (access.profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                gate.withLock { entries.lastOrNull(::reusable) }
            else if (gate.withLock { entries.none(::reusable) }) null else {
                stage = LocalMasterRpcReadStage.PRINCIPAL
                val principal = registration.principals.authenticateLocal(credentials) ?: return null
                if (principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE || principal.scope != peer.nativePeer.scope ||
                    principal.deviceId != peer.nativePeer.deviceId) return null
                gate.withLock { entries.lastOrNull {
                    reusable(it) && samePrincipal(it.binding.originalIdentityFor(it.scope), principal)
                } }
            }
            val existing = if (retained?.binding?.canAdmitResource(RESOURCE_ADMISSION_BUDGET) == true) retained else null
            val binding: LocalRpcIdentityBinding
            val base: RpcAuthorizationScope
            stage = LocalMasterRpcReadStage.BASE_SCOPE
            if (existing != null) {
                binding = existing.binding
                base = (if (access.profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                    binding.retainedOperationScope(context, existing.scope, OPERATIONS_SESSION_RESOURCE)
                else binding.authorizationScope(context, OPERATIONS_SESSION_RESOURCE)) ?: return null
            } else {
                if (gate.withLock { isRetired() || entries.size + failedCleanups.size >= MAXIMUM_LOGINS }) return null
                val authenticated = if (access.profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                    registration.authenticateResourceBinding(credentials) ?: return null else null
                val lifetime = (if (authenticated != null) authenticated.remainingLifetimeMillis()
                    else registration.remainingSessionLifetimeMillis(credentials)) ?: return null
                binding = LocalRpcIdentityBinding.bindAssociated(context, credentials, registration.principals,
                    LocalRpcAssociatedPhysicalPeerProvenance { physical ->
                        if (isRetired() || physical !== peer.physicalSession || !peer.isCurrent()) null
                        else LocalRpcAssociatedPhysicalPeer(peer.nativePeer, peer.association)
                    }, minOf(lifetime, MAXIMUM_BUSINESS_SESSION_LIFETIME_MILLIS).milliseconds, authenticated) ?: return null
                unretained = binding
                base = (if (access.profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                    binding.takeInitialAssociatedScope(context, OPERATIONS_SESSION_RESOURCE)
                else binding.authorizationScope(context, OPERATIONS_SESSION_RESOURCE)) ?: return null
                unretainedScope = base
            }
            stage = LocalMasterRpcReadStage.ORIGINAL_BINDING
            val original = binding.originalIdentityFor(base) ?: return null
            // New SDK resources require the captured epoch. Existing resources stay
            // continuously fenced; the protected authorization below freshly rejoins
            // this exact original identity and current operation requirements.
            stage = LocalMasterRpcReadStage.RESOURCE_RETENTION
            if (existing == null && !registration.retainResourceApplicationSession(epoch, base.session, original)) return null
            stage = LocalMasterRpcReadStage.QUERY_AUTHORIZATION
            val operationAdmission = access.profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS
            val allowed = if (operationAdmission) registration.authorizeChangesBusiness(credentials, access.profile,
                emptyList(), peer, base.session, capacityOnly = true).result == LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY
            else registration.authorizeBusiness(credentials, access, requireRecovery = false,
                peer = peer, resourceSession = base.session)
            if (!allowed) return null
            stage = LocalMasterRpcReadStage.SELECTOR_FAMILY
            val operation = businessOperations.admit(base.session, access, procedure, requestBinding) ?: return null
            stage = LocalMasterRpcReadStage.SCOPED_BINDING
            // LAN operation admission binds the exact request and freshly checked original
            // identity only. Execute, the protected handler and every data disclosure
            // retain their full current typed authorization. This immutable label
            // supplies neither operation authority nor result-disclosure permission.
            val scope = (if (access.profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                binding.retainedOperationScope(context, base, operation.resource)
            else binding.authorizationScope(context, operation.resource)) ?: return null
            currentCoroutineContext().ensureActive()
            stage = LocalMasterRpcReadStage.PUBLICATION
            if (existing == null) {
                val published = gate.withLock {
                    if (isRetired() || !peer.isCurrent() || !scope.session.isActive || entries.size >= MAXIMUM_LOGINS) false
                    else {
                        entries.add(Entry(context.physicalSession, original.sessionId, peer, binding, scope,
                            businessBearerDigest = bearerDigest, operationProfile = access.profile))
                        unretained = null; unretainedScope = null
                        true
                    }
                }
                if (!published) return null
            }
            stage = LocalMasterRpcReadStage.FINAL_STATE
            return scope.takeIf { !isRetired() && peer.isCurrent() && it.session.isActive }.also { accepted = it != null }
        } catch (cancelled: CancellationException) { diagnosticFailure = cancelled; throw cancelled }
        catch (failure: Exception) { diagnosticFailure = failure; currentCoroutineContext().ensureActive(); return null }
        finally {
            trace.finish()
            if (!accepted) reportLocalMasterRpcReadFailure(stage, diagnosticFailure)
            bearer?.destroy()
            withContext(NonCancellable) {
                unretained?.let { binding ->
                    try {
                        val scope = unretainedScope
                        if (scope == null) binding.releaseUnpublishedApplicationSession() else {
                            registration.revokeApplicationSession(scope.session)
                            binding.releaseExpiredApplicationSession()
                            businessOperations.removeRevoked(scope.session)
                        }
                    } catch (failure: Throwable) {
                        gate.withLock { failedCleanups.add(binding) }
                        throw failure
                    }
                }
            }
        }
    }


    private suspend fun authenticateChanges(context: RpcAuthenticationContext, request: LocalMasterRpcOwnedRequestV1,
        profile: LocalMasterRpcWorkflowProfile, selections: List<ReadRequest>, procedure: String,
        arguments: PayloadDigest): RpcAuthorizationScope? {
        if (!request.claimForCallback()) return null
        return withCallback({ null }, cleanup = {
            request.destroy(); request.awaitCleanup(); businessRequestCustody.reap()
        }) { withAuthenticationAdmission(context) { authenticateChangesOwned(context, request, profile, selections, procedure, arguments) } }
    }

    /** One genuine READ-only session per exact physical/principal/profile, not one per poll. */
    private suspend fun authenticateChangesOwned(context: RpcAuthenticationContext, request: LocalMasterRpcOwnedRequestV1,
        profile: LocalMasterRpcWorkflowProfile, selections: List<ReadRequest>, procedure: String,
        arguments: PayloadDigest): RpcAuthorizationScope? {
        var bearer: OpaqueCredential? = null
        var unretained: LocalRpcIdentityBinding? = null
        var unretainedScope: RpcAuthorizationScope? = null
        val trace = LocalMasterRpcReadTrace(LocalMasterRpcReadTracePhase.AUTHENTICATE, LocalMasterRpcReadStage.REQUEST_SHAPE)
        var stage by trace
        var accepted = false
        var diagnosticFailure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            if (isRetired() || !changesProcedure(profile, procedure) || selections.size !in 1..32 ||
                selections.sumOf { it.payload.size.toLong() } > 65_536) return null
            discardExpiredEntries()
            stage = LocalMasterRpcReadStage.SESSION_EPOCH
            val epoch = registration.captureSessionEpoch() ?: return null
            stage = LocalMasterRpcReadStage.PEER
            val peer = registration.register(context.physicalSession) ?: return null
            if (isRetired() || !peer.isCurrent() || context.peer != context.physicalSession.peer) return null
            val credential = request.useBearer(::OpaqueCredential).also { bearer = it }
            val credentials = LocalGatewayCredentials(peer.nativePeer.connectionId, credential)
            val bearerDigest = request.useBearer(::sha256)
            fun reusable(entry: Entry): Boolean = entry.changesProfile == profile &&
                entry.physical === context.physicalSession && entry.peer === peer && entry.businessBearerDigest == bearerDigest
            val retained = if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                gate.withLock { entries.lastOrNull(::reusable) }
            else {
                stage = LocalMasterRpcReadStage.PRINCIPAL
                val principal = registration.principals.authenticateLocal(credentials) ?: return null
                if (principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE || principal.scope != peer.nativePeer.scope ||
                    principal.deviceId != peer.nativePeer.deviceId) return null
                gate.withLock { entries.lastOrNull {
                    reusable(it) && samePrincipal(it.binding.originalIdentityFor(it.scope), principal)
                } }
            }
            val existing = if (retained?.binding?.canAdmitResource(RESOURCE_ADMISSION_BUDGET) == true) retained else null
            val binding: LocalRpcIdentityBinding
            val base: RpcAuthorizationScope
            stage = LocalMasterRpcReadStage.BASE_SCOPE
            if (existing != null) {
                binding = existing.binding
                // Only immutable SDK correlation DATA. The protected query must freshly
                // authenticate and match this exact original before a scope can escape.
                base = (if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                    binding.retainedOperationScope(context, existing.scope, CHANGES_SESSION_RESOURCE)
                else binding.authorizationScope(context, CHANGES_SESSION_RESOURCE)) ?: return null
            } else {
                if (gate.withLock { isRetired() || entries.size + failedCleanups.size >= MAXIMUM_LOGINS }) return null
                val authenticated = if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                    registration.authenticateResourceBinding(credentials) ?: return null else null
                val lifetime = (if (authenticated != null) authenticated.remainingLifetimeMillis()
                    else registration.remainingSessionLifetimeMillis(credentials)) ?: return null
                binding = LocalRpcIdentityBinding.bindAssociated(context, credentials, registration.principals,
                    LocalRpcAssociatedPhysicalPeerProvenance { physical ->
                        if (isRetired() || physical !== peer.physicalSession || !peer.isCurrent()) null
                        else LocalRpcAssociatedPhysicalPeer(peer.nativePeer, peer.association)
                    }, minOf(lifetime, MAXIMUM_BUSINESS_SESSION_LIFETIME_MILLIS).milliseconds, authenticated) ?: return null
                unretained = binding
                base = (if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                    binding.takeInitialAssociatedScope(context, CHANGES_SESSION_RESOURCE)
                else binding.authorizationScope(context, CHANGES_SESSION_RESOURCE)) ?: return null
                unretainedScope = base
            }
            stage = LocalMasterRpcReadStage.ORIGINAL_BINDING
            val original = binding.originalIdentityFor(base) ?: return null
            // Only a new resource enters the census under this captured epoch. Reused
            // resources remain continuously fenced; authorizeChangesBusiness below rejoins
            // the exact retained entry and freshly checks its original and protected query.
            // Re-registering it would reject an unrelated write during verification even
            // though that write preserved this actual resource. A retired entry cannot pass
            // the registry's retained-resource check or be installed again here.
            stage = LocalMasterRpcReadStage.RESOURCE_RETENTION
            if (existing == null && !registration.retainResourceApplicationSession(epoch, base.session, original)) return null
            stage = LocalMasterRpcReadStage.QUERY_AUTHORIZATION
            val authorization = registration.authorizeChangesBusiness(credentials, profile, selections, peer, base.session)
            val captured = when (authorization.result) {
                LocalMasterChangesRpcAccessResultV1.DENIED, LocalMasterChangesRpcAccessResultV1.UNAVAILABLE -> return null
                LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY -> LocalRpcChangesScope.capacity(procedure, arguments)
                LocalMasterChangesRpcAccessResultV1.MALFORMED_ONLY -> LocalRpcChangesScope.malformed(procedure, arguments)
                LocalMasterChangesRpcAccessResultV1.ALLOWED -> {
                    stage = LocalMasterRpcReadStage.SELECTOR_FAMILY
                    when (val admitted = changesFamilies.admit(base.session, selections)) {
                    LocalRpcChangesFamilies.Admission.Conflict -> return null
                    LocalRpcChangesFamilies.Admission.Capacity -> LocalRpcChangesScope.capacity(procedure, arguments)
                    is LocalRpcChangesFamilies.Admission.Retained -> LocalRpcChangesScope.data(procedure,
                        admitted.family.digest, arguments, checkNotNull(projectionBinding(authorization, procedure, profile)))
                    }
                }
            }
            // Malformed input is not query authority. Both immutable refusal modes must
            // independently qualify the current protected original before any SDK scope escapes.
            stage = LocalMasterRpcReadStage.REFUSAL_AUTHORIZATION
            if (captured.noDataOnly && registration.authorizeChangesBusiness(credentials, profile, emptyList(), peer,
                    base.session, capacityOnly = true).result != LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY) return null
            stage = LocalMasterRpcReadStage.SCOPED_BINDING
            // The same callback already verified this exact original binding and then
            // freshly joined it to the retained resource session and protected query.
            // Only immutable correlation metadata changes here. Invoke, Execute and every
            // disclosure still run their own fresh original/query authorization below.
            val scope = RpcAuthorizationScope(base.session, captured.resource)
            currentCoroutineContext().ensureActive()
            stage = LocalMasterRpcReadStage.PUBLICATION
            if (existing == null) {
                val published = gate.withLock {
                    if (isRetired() || !peer.isCurrent() || !scope.session.isActive || entries.size >= MAXIMUM_LOGINS) false
                    else {
                        entries.add(Entry(context.physicalSession, original.sessionId, peer, binding, scope,
                            businessBearerDigest = bearerDigest, changesProfile = profile))
                        unretained = null; unretainedScope = null
                        true
                    }
                }
                if (!published) return null
            }
            stage = LocalMasterRpcReadStage.FINAL_STATE
            return scope.takeIf { !isRetired() && peer.isCurrent() && it.session.isActive }.also { accepted = it != null }
        } catch (cancelled: CancellationException) { diagnosticFailure = cancelled; throw cancelled }
        catch (failure: Exception) { diagnosticFailure = failure; currentCoroutineContext().ensureActive(); return null }
        finally {
            trace.finish()
            if (!accepted) reportLocalMasterRpcReadFailure(stage, diagnosticFailure)
            bearer?.destroy()
            withContext(NonCancellable) {
                unretained?.let { binding ->
                    try {
                        val scope = unretainedScope
                        if (scope == null) binding.releaseUnpublishedApplicationSession() else {
                            registration.revokeApplicationSession(scope.session)
                            binding.releaseExpiredApplicationSession()
                            changesFamilies.removeRevoked(scope.session)
                        }
                    } catch (failure: Throwable) {
                        gate.withLock { failedCleanups.add(binding) }
                        throw failure
                    }
                }
            }
        }
    }

    private suspend fun authorizeChanges(context: RpcAuthorizationContext): Boolean = withCallback({ false }) {
        val phase = when (context.operation) {
            RpcAuthorizationOperation.Invoke -> LocalMasterRpcReadTracePhase.AUTHORIZE_INVOKE
            RpcAuthorizationOperation.Execute -> LocalMasterRpcReadTracePhase.AUTHORIZE_EXECUTE
            RpcAuthorizationOperation.Success -> LocalMasterRpcReadTracePhase.AUTHORIZE_SUCCESS
            else -> LocalMasterRpcReadTracePhase.AUTHORIZE_CONTROL
        }
        val trace = LocalMasterRpcReadTrace(phase, LocalMasterRpcReadStage.AUTHORIZATION_ENTRY)
        var stage by trace
        var accepted = false
        try {
            if (isRetired() || context.procedureVersion != 1) return@withCallback false
            val entry = gate.withLock { entries.firstOrNull { it.scope.session === context.scope.session } }
                ?: return@withCallback false
            stage = LocalMasterRpcReadStage.AUTHORIZATION_SCOPE
            val profile = entry.changesProfile ?: return@withCallback false
            if (!changesProcedure(profile, context.procedureName)) return@withCallback false
            val scope = LocalRpcChangesScope.parse(context.scope.resource, context.procedureName) ?: return@withCallback false
            stage = LocalMasterRpcReadStage.AUTHORIZATION_CREDENTIALS
            // The LAN protected endpoint freshly verifies the exact retained original
            // after its actual queue wait. Avoid a second native employee verification
            // before that SAME check; this loan is only DATA and cannot authorize output.
            val credentials = (if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                entry.binding.retainedCredentialsForProtectedOriginalCheck(context)
            else entry.binding.currentOriginalCredentials(context)) ?: return@withCallback false
            try {
                // SDK ApplicationError/Failure/Receipt cannot carry a business projection. A
                // no-data scope can never authorize Success, regardless of later query eligibility.
                val noData = context.operation == RpcAuthorizationOperation.ApplicationError ||
                    context.operation == RpcAuthorizationOperation.Failure || context.operation == RpcAuthorizationOperation.Receipt ||
                    context.operation == RpcAuthorizationOperation.Cancel
                stage = LocalMasterRpcReadStage.AUTHORIZATION_CURRENT
                val admission = profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS &&
                    context.operation in setOf(RpcAuthorizationOperation.Invoke, RpcAuthorizationOperation.Execute)
                val allowed = if (noData || scope.noDataOnly || admission) {
                    context.operation != RpcAuthorizationOperation.Success && registration.authorizeChangesBusiness(
                            credentials, profile, emptyList(), entry.peer, entry.scope.session, capacityOnly = true).result ==
                            LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY
                } else {
                    val family = changesFamilies.capture(entry.scope.session, checkNotNull(scope.family)) ?: return@withCallback false
                    val current = registration.authorizeChangesBusiness(credentials, profile, family.selections, entry.peer, entry.scope.session)
                    stage = LocalMasterRpcReadStage.AUTHORIZATION_BINDING
                    current.result == LocalMasterChangesRpcAccessResultV1.ALLOWED &&
                        (context.operation != RpcAuthorizationOperation.Success || projectionBinding(current, context.procedureName, profile) == scope.projection) &&
                        changesFamilies.current(entry.scope.session, family)
                }
                currentCoroutineContext().ensureActive()
                accepted = allowed && !isRetired() && entry.peer.isCurrent() && entry.scope.session.isActive
                accepted
            } finally { credentials.bearer.destroy() }

        } finally {
            trace.finish()
            if (!accepted) reportLocalMasterRpcReadFailure(stage, null)
        }
    }

    private fun changesProcedure(profile: LocalMasterRpcWorkflowProfile, name: String): Boolean {
        val procedures = LocalMasterChangesRpcProtocolV1.procedures(profile)
        return name == readProcedureName(profile) || name == procedures.SNAPSHOT.name || name == procedures.PULL.name ||
            profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS && name == LocalMasterChangesBatchRpcProtocolV1.SNAPSHOT.name
    }

    private fun readProcedureName(profile: LocalMasterRpcWorkflowProfile): String = when (profile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS -> LocalMasterClientRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.WAREHOUSES -> LocalMasterWarehouseRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.REPORTS -> LocalMasterReportRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> LocalMasterStockTransferRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.KITCHEN -> LocalMasterKitchenRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> LocalMasterRetailOrderRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> LocalMasterPaymentCorrectionRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> LocalMasterCustomerReceiptRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> LocalMasterFoodCheckRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> LocalMasterFoodCashFinancialRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> LocalMasterFoodTableAssignmentRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> LocalMasterInvoiceCaptureRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> LocalMasterInvoiceMatchingRpcProtocolV1.READ.name
        LocalMasterRpcWorkflowProfile.LAN_BUSINESS -> LocalMasterLanBusinessRpcProtocolV1.READ.name
    }

    private fun samePrincipal(left: com.ukkera.pos.security.LocalEmployeePrincipal?,
        right: com.ukkera.pos.security.LocalEmployeePrincipal): Boolean = left != null &&
        left.scope == right.scope && left.actorId == right.actorId && left.deviceId == right.deviceId &&
        left.sessionId == right.sessionId && left.sessionGeneration == right.sessionGeneration &&
        left.admissionRevision == right.admissionRevision && left.authenticationPurpose == right.authenticationPurpose

    private suspend fun authorizeBusiness(context: RpcAuthorizationContext): Boolean = withCallback({ false }) {
        val phase = when (context.operation) {
            RpcAuthorizationOperation.Invoke -> LocalMasterRpcReadTracePhase.AUTHORIZE_INVOKE
            RpcAuthorizationOperation.Execute -> LocalMasterRpcReadTracePhase.AUTHORIZE_EXECUTE
            RpcAuthorizationOperation.Success -> LocalMasterRpcReadTracePhase.AUTHORIZE_SUCCESS
            else -> LocalMasterRpcReadTracePhase.AUTHORIZE_CONTROL
        }
        val trace = LocalMasterRpcReadTrace(phase, LocalMasterRpcReadStage.AUTHORIZATION_ENTRY)
        var stage by trace
        var accepted = false
        try {
            if (isRetired()) return@withCallback false
            val entry = gate.withLock { entries.firstOrNull { it.scope.session === context.scope.session } }
                ?: return@withCallback false
            stage = LocalMasterRpcReadStage.AUTHORIZATION_SCOPE
            val profile = entry.operationProfile ?: return@withCallback false
            val operation = businessOperations.capture(entry.scope.session, context.scope.resource) ?: return@withCallback false
            val access = operation.access
            if (access.profile != profile || context.procedureName != operation.procedure || context.procedureVersion != 1 ||
                !entry.peer.isCurrent()) return@withCallback false
            stage = LocalMasterRpcReadStage.AUTHORIZATION_CREDENTIALS
            val credentials = (if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                entry.binding.retainedCredentialsForProtectedOriginalCheck(context)
            else entry.binding.currentOriginalCredentials(context)) ?: return@withCallback false
            try {
                val recovery = context.operation != RpcAuthorizationOperation.Invoke && context.operation != RpcAuthorizationOperation.Execute
                stage = LocalMasterRpcReadStage.AUTHORIZATION_CURRENT
                // These exact SDK controls contain no business result and cannot execute a
                // command. Rejoin the SAME current identity/resource after the queue wait,
                // as the changes route already does for bodyless controls. Revalidating a
                // complete stock/financial producer graph for a Receipt delays unrelated
                // requests behind acknowledgement work. Success, status, duplicates and all
                // execution still require their complete current typed authorization below.
                // This typed business procedure has exactly one application error:
                // LocalMasterClientRpcErrorV1.UNAVAILABLE, containing no original/result
                // data. Requiring disclosure authority for that same denial suppresses
                // it (for example on a wrong lookup digest) until the Client times out.
                val bodylessControl = profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS &&
                    (context.operation == RpcAuthorizationOperation.Receipt || context.operation == RpcAuthorizationOperation.Cancel ||
                        context.operation == RpcAuthorizationOperation.ApplicationError)
                val operationAdmission = profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS &&
                    context.operation in setOf(RpcAuthorizationOperation.Invoke, RpcAuthorizationOperation.Execute)
                // These SDK stages admit the exact request only. The handler performs the
                // complete typed check before entering the protected kernel. Every result,
                // status and duplicate release still requires its fresh complete proof.
                val allowed = if (bodylessControl || operationAdmission) registration.authorizeChangesBusiness(credentials, profile,
                    emptyList(), entry.peer, entry.scope.session, capacityOnly = true).result ==
                    LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY
                else {
                    stage = LocalMasterRpcReadStage.QUERY_AUTHORIZATION
                    registration.authorizeBusiness(credentials, access, requireRecovery = recovery,
                        peer = entry.peer, resourceSession = entry.scope.session)
                }
                currentCoroutineContext().ensureActive()
                stage = LocalMasterRpcReadStage.AUTHORIZATION_FINAL
                // Preserve the original short-circuit order; failed-cut observations contain
                // static source locations only, never original/result/credential DATA.
                if (!allowed) {
                    reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.AUTHORIZATION_CURRENT, IllegalStateException())
                    return@withCallback false
                }
                if (!businessOperations.current(entry.scope.session, operation)) {
                    reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.AUTHORIZATION_FAMILY, IllegalStateException())
                    return@withCallback false
                }
                if (isRetired()) {
                    reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.FINAL_STATE, IllegalStateException())
                    return@withCallback false
                }
                if (!entry.peer.isCurrent()) {
                    reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.PEER, IllegalStateException())
                    return@withCallback false
                }
                if (!entry.scope.session.isActive) {
                    reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.AUTHORIZATION_SCOPE, IllegalStateException())
                    return@withCallback false
                }
                accepted = true
                true
            } finally { credentials.bearer.destroy() }
        } finally {
            trace.finish()
            if (!accepted) reportLocalMasterRpcReadFailure(stage, null)
        }
    }

    private suspend fun readBusiness(context: RpcCallContext, request: LocalMasterRpcReadRequestV1,
        profile: LocalMasterRpcWorkflowProfile):
        RpcReply<LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        changesReply(context, request, profile, listOf(request.request), readProcedureName(profile),
            LocalMasterChangesRpcProtocolV1.selectorBinding(request.request),
            { credentials, peer, session -> registration.snapshotBusiness(credentials, profile, request.request, null, peer, session) },
            { (it as? LocalMasterChangesReplyV1.Unavailable)?.reason },
            { (it as? LocalMasterChangesReplyV1.Snapshot)?.let { snapshot -> listOf(snapshot.cursor) }.orEmpty() },
            { actor, result -> LocalMasterClientRpcProtocolV1.captureRead(actor,
                checkNotNull(result as? LocalMasterChangesReplyV1.Snapshot).data) },
            { LocalMasterClientRpcErrorV1.UNAVAILABLE })

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

    private suspend fun changesBusiness(context: RpcCallContext, request: LocalMasterRpcChangesRequestV1,
        profile: LocalMasterRpcWorkflowProfile, pull: Boolean):
        RpcReply<LocalMasterRpcChangesResponseV1, LocalMasterChangesFailureV1> {
        val procedures = LocalMasterChangesRpcProtocolV1.procedures(profile)
        return changesReply(context, request, profile, listOf(request.selection),
            if (pull) procedures.PULL.name else procedures.SNAPSHOT.name, request.bindingDigest,
            { credentials, peer, resourceSession ->
                if (!pull) registration.snapshotBusiness(credentials, profile, request.selection, request.cursor, peer, resourceSession)
                else request.cursor?.let { cursor ->
                    registration.pullBusiness(credentials, profile, request.selection, cursor, peer, resourceSession)
                } ?: LocalMasterChangesReplyV1.Unavailable(LocalMasterChangesFailureV1.MALFORMED_REQUEST)
            }, { (it as? LocalMasterChangesReplyV1.Unavailable)?.reason }, {
                when (it) {
                    is LocalMasterChangesReplyV1.Snapshot -> listOf(it.cursor)
                    is LocalMasterChangesReplyV1.Unchanged -> listOf(it.cursor)
                    is LocalMasterChangesReplyV1.Unavailable -> emptyList()
                }
            }, LocalMasterChangesRpcProtocolV1::capture, { it })
    }

    private suspend fun snapshotBatchBusiness(context: RpcCallContext, request: LocalMasterRpcChangesBatchRequestV1):
        RpcReply<LocalMasterRpcChangesBatchResponseV1, LocalMasterChangesFailureV1> =
        changesReply(context, request, LocalMasterRpcWorkflowProfile.LAN_BUSINESS, request.selections,
            LocalMasterChangesBatchRpcProtocolV1.SNAPSHOT.name, request.bindingDigest,
            { credentials, peer, session -> registration.snapshotBatchBusiness(credentials,
                LocalMasterRpcWorkflowProfile.LAN_BUSINESS, request.selections, peer, session) },
            { (it as? LocalMasterChangesBatchReplyV1.Unavailable)?.reason },
            { (it as? LocalMasterChangesBatchReplyV1.Snapshot)?.snapshots?.map { snapshot -> snapshot.cursor }.orEmpty() },
            LocalMasterChangesBatchRpcProtocolV1::capture, { it })

    /** A plain READ contains no retained cursor. Cursor-returning procedures remain exact-cut
     * bound; both modes reproduce the full protected projection and current identity. */
    private fun projectionBinding(authorization: LocalMasterChangesRpcAuthorizationV1,
        procedure: String, profile: LocalMasterRpcWorkflowProfile): PayloadDigest? =
        if (procedure == readProcedureName(profile)) authorization.readProjectionBinding else authorization.binding

    private suspend fun <T, R, E> changesReply(context: RpcCallContext, request: LocalMasterRpcOwnedRequestV1,
        profile: LocalMasterRpcWorkflowProfile, selections: List<ReadRequest>, procedure: String, arguments: PayloadDigest,
        call: suspend (LocalGatewayCredentials, LocalMasterRpcRegisteredPeer, RpcApplicationSession) -> T,
        failure: (T) -> LocalMasterChangesFailureV1?,
        cursors: (T) -> List<com.ukkera.pos.database.LocalMasterChangeCursorV1>,
        capture: (DomainId, T) -> R,
        error: (LocalMasterChangesFailureV1) -> E): RpcReply<R, E> {
        fun unavailable(reason: LocalMasterChangesFailureV1 = LocalMasterChangesFailureV1.STORAGE_UNCERTAIN):
            RpcReply<R, E> = RpcReply.ApplicationError(error(reason))
        if (!request.claimForCallback()) return unavailable()
        return withCallback({ unavailable() }, cleanup = {
            request.destroy(); request.awaitCleanup(); businessRequestCustody.reap()
        }) {
            val trace = LocalMasterRpcReadTrace(LocalMasterRpcReadTracePhase.HANDLER, LocalMasterRpcReadStage.AUTHORIZATION_ENTRY)
            var stage by trace
            try {
                val entry = gate.withLock {
                    if (isRetired()) null else entries.firstOrNull { it.scope.session === context.authorizationScope?.session }
                } ?: return@withCallback unavailable()
                if (entry.changesProfile != profile || entry.businessBearerDigest != request.useBearer(::sha256) ||
                    !entry.peer.isCurrent()) return@withCallback unavailable()
                val scoped = LocalRpcChangesScope.parse(context.authorizationScope?.resource ?: "", procedure)
                    ?: return@withCallback unavailable()
                if (scoped.arguments != arguments) return@withCallback unavailable()
                val original = entry.binding.originalIdentityFor(entry.scope) ?: return@withCallback unavailable()
                stage = LocalMasterRpcReadStage.AUTHORIZATION_CREDENTIALS
                val credentials = (if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS &&
                    procedure == LocalMasterChangesBatchRpcProtocolV1.SNAPSHOT.name)
                    entry.binding.retainedCredentialsForProtectedOriginalCheck(context)
                else entry.binding.currentOriginalCredentials(context)) ?: return@withCallback unavailable()
                try {
                    if (scoped.noDataOnly) {
                        // This branch has no callback capable of capturing DATA or a cursor.
                        val identity = registration.authorizeChangesBusiness(credentials, profile, emptyList(), entry.peer,
                            entry.scope.session, capacityOnly = true)
                        return@withCallback unavailable(if (identity.result == LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY)
                            checkNotNull(scoped.refusal) else LocalMasterChangesFailureV1.AUTHORIZATION_DENIED)
                    }
                    val family = changesFamilies.capture(entry.scope.session, checkNotNull(scoped.family))
                        ?: return@withCallback unavailable(LocalMasterChangesFailureV1.CURSOR_EXPIRED)
                    if (family.selections.size != selections.size ||
                        family.selections.indices.any { !sameSelection(family.selections[it], selections[it]) })
                        return@withCallback unavailable()
                    // The actual call performs the protected query/transaction authorization
                    // and returns its coherent cursor. Compare that result to the immutable
                    // scope below, then freshly authorize disclosure; a separate preview
                    // projection before this same read adds no authority or usable result.
                    stage = LocalMasterRpcReadStage.QUERY_EXECUTION
                    val result = call(credentials, entry.peer, entry.scope.session)
                    failure(result)?.let { return@withCallback unavailable(it) }
                    val observedCursors = cursors(result)
                    val observedBinding = if (procedure == readProcedureName(profile))
                        observedCursors.singleOrNull()?.let(LocalMasterChangesBindingV1::readProjectionDigest)
                    else LocalMasterChangesBindingV1.digest(observedCursors)
                    if (observedBinding != scoped.projection) {
                        reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.AUTHORIZATION_BINDING, IllegalStateException())
                        return@withCallback unavailable(LocalMasterChangesFailureV1.CURSOR_EXPIRED)
                    }
                    stage = LocalMasterRpcReadStage.AUTHORIZATION_CURRENT
                    // The actual read above already checked its complete current typed query
                    // and exact coherent cursor. A private return carries no data to the peer;
                    // SDK Success/Status/Duplicate still reproduce the full projection binding
                    // immediately before any actual disclosure, including cached output.
                    val current = if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                        registration.authorizeChangesBusiness(credentials, profile, emptyList(), entry.peer,
                            entry.scope.session, capacityOnly = true).result == LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY
                    else registration.authorizeChangesBusiness(credentials, profile, family.selections, entry.peer,
                        entry.scope.session).let { it.result == LocalMasterChangesRpcAccessResultV1.ALLOWED && projectionBinding(it, procedure, profile) == scoped.projection }
                    currentCoroutineContext().ensureActive()
                    if (!current ||
                        !changesFamilies.current(entry.scope.session, family) || isRetired() || !entry.peer.isCurrent() || !entry.scope.session.isActive)
                        return@withCallback unavailable(LocalMasterChangesFailureV1.CURSOR_EXPIRED)
                    stage = LocalMasterRpcReadStage.FINAL_STATE
                    RpcReply.Success(capture(original.actorId, result))
                } finally { credentials.bearer.destroy() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                reportLocalMasterRpcReadFailure(stage, failure)
                currentCoroutineContext().ensureActive(); unavailable()
            }
            finally { trace.finish() }
        }
    }

    private suspend fun <T, R> businessReply(context: RpcCallContext, request: LocalMasterRpcOwnedRequestV1,
        received: LocalMasterRpcBusinessAccess,
        call: suspend (LocalGatewayCredentials, LocalMasterRpcRegisteredPeer, RpcApplicationSession) -> T,
        capture: (DomainId, T) -> R,
        procedureNameOverride: String? = null, requestBinding: PayloadDigest? = null): RpcReply<R, LocalMasterClientRpcErrorV1> {
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
                val profile = entry.operationProfile ?: return@withCallback businessUnavailable()
                val operation = businessOperations.capture(entry.scope.session,
                    context.authorizationScope?.resource ?: return@withCallback businessUnavailable())
                    ?: return@withCallback businessUnavailable()
                val original = operation.access
                // The fixed typed handler registration selects the procedure. RpcCallContext
                // carries the SDK-authorized scope, not procedure name/version metadata.
                if (received.profile != profile || operation.procedure != (procedureNameOverride ?: procedureName(received)) ||
                    operation.requestBinding != requestBinding || !sameBusinessAccess(original, received) ||
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
                    val current = if (profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                        registration.authorizeChangesBusiness(credentials, profile, emptyList(), entry.peer,
                            entry.scope.session, capacityOnly = true).result == LocalMasterChangesRpcAccessResultV1.CAPACITY_ONLY
                    else registration.authorizeBusiness(credentials, original, requireRecovery = true,
                        peer = entry.peer, resourceSession = entry.scope.session)
                    // This private handler return is not disclosure. The official scoped SDK
                    // freshly checks the complete typed result before both retention/send and
                    // replay. Keep this original fenced without repeating that graph here.
                    if (!current ||
                        !businessOperations.current(entry.scope.session, operation) ||
                        isRetired() || !entry.peer.isCurrent() || !entry.scope.session.isActive)
                        return@withCallback businessUnavailable()
                    RpcReply.Success(capture(principal.actorId, result))
                } finally { credentials.bearer.destroy() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); businessUnavailable() }
        }
    }

    private suspend fun authenticateCreation(context: RpcAuthenticationContext,
        request: LocalMasterRpcEmployeeCreationRequestV1): RpcAuthorizationScope? {
        if (!request.claimForCallback()) return null
        return withCallback({ null }, cleanup = {
            request.destroy()
            request.awaitCleanup()
            businessRequestCustody.reap()
        }) {
            val temporary = LocalRpcCurrentIdentityRequest(request.useBearer { it.copyOf() })
            try {
                withAuthenticationAdmission(context) {
                    authenticateOwned(context, temporary, CREATION_RESOURCE, request.useBearer(::sha256), creation = true)
                }
            } finally { temporary.destroy() }
        }
    }

    /** Identity/resource admission only. Complete physical action authorization and native
     * preparation remain inside the genuine protected endpoint for each protocol stage. */
    private suspend fun authenticatePhysical(context: RpcAuthenticationContext,
        request: LocalMasterPhysicalRpcRequestV1): RpcAuthorizationScope? {
        if (!request.claimForCallback()) return null
        return withCallback({ null }, cleanup = {
            request.destroy(); request.awaitCleanup(); businessRequestCustody.reap()
        }) { withAuthenticationAdmission(context) {
            val temporary = LocalRpcCurrentIdentityRequest(request.useBearer { it.copyOf() })
            try {
                val base = authenticateOwned(context, temporary, PHYSICAL_RESOURCE,
                    request.useBearer(::sha256), physicalInteractive = true) ?: return@withAuthenticationAdmission null
                val entry = gate.withLock { entries.firstOrNull {
                    it.physicalInteractive && it.scope.session === base.session && it.physical === context.physicalSession
                } } ?: return@withAuthenticationAdmission null
                val call = physicalCalls.admit(base.session, request.bindingDigest) ?: return@withAuthenticationAdmission null
                // The protected exact resource check above has settled. This is immutable
                // request DATA only; Invoke/Execute/disclosure perform their current checks.
                entry.binding.retainedOperationScope(context, base, call.resource)
            } finally { temporary.destroy() }
        } }
    }

    private suspend fun authorizePhysical(context: RpcAuthorizationContext): Boolean = withCallback({ false }) {
        if (isRetired() || context.procedureName != LocalMasterPhysicalRpcProtocolV1.INTERACTIVE.name ||
            context.procedureVersion != LocalMasterPhysicalRpcProtocolV1.INTERACTIVE.version) return@withCallback false
        val entry = gate.withLock { entries.firstOrNull {
            it.physicalInteractive && it.scope.session === context.scope.session
        } } ?: return@withCallback false
        val call = physicalCalls.capture(entry.scope.session, context.scope.resource) ?: return@withCallback false
        val credentials = entry.binding.retainedCredentialsForProtectedOriginalCheck(context) ?: return@withCallback false
        try {
            val allowed = registration.physicalInteractiveSessionCurrent(credentials, entry.peer, entry.scope.session)
            currentCoroutineContext().ensureActive()
            if (!allowed || isRetired() || !entry.peer.isCurrent() || !physicalCalls.current(entry.scope.session, call))
                return@withCallback false
            // A cached or duplicate physical response stays tied to this exact SDK request,
            // never whichever later same-body poll most recently reached the handler.
            if (context.operation == RpcAuthorizationOperation.Success || context.operation == RpcAuthorizationOperation.Duplicate) {
                val delivery = physicalReplies.capture(entry.scope.session, context.requestId, call) ?: return@withCallback false
                if (!delivery.current()) return@withCallback false
            }
            !isRetired() && entry.peer.isCurrent() && physicalCalls.current(entry.scope.session, call)
        } finally { credentials.bearer.destroy() }
    }

    private suspend fun physicalInteractive(context: RpcCallContext, request: LocalMasterPhysicalRpcRequestV1):
        RpcReply<LocalMasterPhysicalRpcResponseV1, LocalMasterClientRpcErrorV1> {
        if (!request.claimForCallback()) return businessUnavailable()
        return withCallback({ businessUnavailable() }, cleanup = {
            request.destroy(); request.awaitCleanup(); businessRequestCustody.reap()
        }) {
            try {
                val entry = gate.withLock { entries.firstOrNull {
                    it.physicalInteractive && it.scope.session === context.authorizationScope?.session
                } } ?: return@withCallback businessUnavailable()
                val resource = context.authorizationScope?.resource ?: return@withCallback businessUnavailable()
                val call = physicalCalls.capture(entry.scope.session, resource) ?: return@withCallback businessUnavailable()
                if (call.binding != request.bindingDigest || entry.businessBearerDigest != request.useBearer(::sha256) ||
                    !entry.peer.isCurrent()) return@withCallback businessUnavailable()
                val replySlot = physicalReplies.reserve(entry.scope.session, context.requestId, call)
                    ?: return@withCallback businessUnavailable()
                val credentials = entry.binding.retainedCredentialsForProtectedOriginalCheck(context) ?: return@withCallback businessUnavailable()
                try {
                    val delivery = registration.physicalInteractive(credentials, request.body, entry.peer, entry.scope.session)
                        ?: return@withCallback businessUnavailable()
                    if (!physicalReplies.retain(entry.scope.session, replySlot, delivery)) return@withCallback businessUnavailable()
                    currentCoroutineContext().ensureActive()
                    if (isRetired() || !entry.peer.isCurrent() || !physicalCalls.current(entry.scope.session, call) || !delivery.current())
                        businessUnavailable()
                    else RpcReply.Success(delivery.response)
                } finally { credentials.bearer.destroy() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); businessUnavailable() }
        }
    }

    /** Transport currentness only. The Master creation service authorizes ADMINISTER per call. */
    private suspend fun authorizeCreation(context: RpcAuthorizationContext): Boolean = withCallback({ false }) {
        if (isRetired()) return@withCallback false
        val entry = gate.withLock { entries.firstOrNull { it.scope.session === context.scope.session } }
            ?: return@withCallback false
        if (!entry.creation || context.procedureName != LocalMasterEmployeeCreationRpcProtocolV1.PROCEDURE.name ||
            context.procedureVersion != LocalMasterEmployeeCreationRpcProtocolV1.PROCEDURE.version ||
            context.scope.resource != CREATION_RESOURCE || !entry.peer.isCurrent()) return@withCallback false
        val credentials = entry.binding.currentOriginalCredentials(context) ?: return@withCallback false
        try {
            val allowed = currentInteractive(credentials, entry) && registration.employeeCreationService() != null
            currentCoroutineContext().ensureActive()
            allowed && !isRetired() && entry.peer.isCurrent() && entry.scope.session.isActive
        } finally { credentials.bearer.destroy() }
    }

    private suspend fun currentInteractive(credentials: LocalGatewayCredentials, entry: Entry): Boolean {
        val principal = registration.principals.authenticateLocal(credentials) ?: return false
        return principal.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            principal.sessionId == entry.sessionId && principal.scope == entry.peer.nativePeer.scope &&
            principal.deviceId == entry.peer.nativePeer.deviceId
    }

    private suspend fun employeeCreation(context: RpcCallContext, request: LocalMasterRpcEmployeeCreationRequestV1):
        RpcReply<LocalMasterRpcEmployeeCreationResponseV1, LocalMasterClientRpcErrorV1> {
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
                if (!entry.creation || context.authorizationScope?.resource != CREATION_RESOURCE ||
                    entry.businessBearerDigest != request.useBearer(::sha256) || !entry.peer.isCurrent())
                    return@withCallback businessUnavailable()
                val principal = entry.binding.originalIdentityFor(entry.scope) ?: return@withCallback businessUnavailable()
                val credentials = entry.binding.currentOriginalCredentials(context) ?: return@withCallback businessUnavailable()
                try {
                    val service = registration.employeeCreationService()
                    if (service == null || !currentInteractive(credentials, entry)) return@withCallback businessUnavailable()
                    val response = LocalMasterEmployeeCreationRpcProtocolV1.dispatch(service, credentials, request, principal.actorId)
                    currentCoroutineContext().ensureActive()
                    // A committed creation is never rolled back; a stale output stays undisclosed
                    // and remains recoverable by its durable original through Lookup.
                    if (isRetired() || !entry.peer.isCurrent() || !entry.scope.session.isActive ||
                        !currentInteractive(credentials, entry)) return@withCallback businessUnavailable()
                    RpcReply.Success(response)
                } finally { credentials.bearer.destroy() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); businessUnavailable() }
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
        LocalMasterRpcWorkflowProfile.LAN_BUSINESS -> when (access) {
            is LocalMasterRpcBusinessAccess.Read -> LocalMasterLanBusinessRpcProtocolV1.READ.name
            is LocalMasterRpcBusinessAccess.Execute -> LocalMasterLanBusinessRpcProtocolV1.EXECUTE.name
            is LocalMasterRpcBusinessAccess.Lookup -> LocalMasterLanBusinessRpcProtocolV1.LOOKUP.name
        }
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
        private const val CREATION_RESOURCE = "pos.employee-creation"
        private const val CHANGES_SESSION_RESOURCE = "pos.changes.read-only"
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
        private const val LAN_BUSINESS_READ = "pos.lan-business.read"
        private const val LAN_BUSINESS_EXECUTE = "pos.lan-business.execute"
        private const val LAN_BUSINESS_LOOKUP = "pos.lan-business.lookup"
        private const val OPERATIONS_SESSION_RESOURCE = "pos.business.operations"
        private const val PHYSICAL_RESOURCE = "pos.physical.interactive"
        private const val MAXIMUM_LOGINS = 128
        private const val MAXIMUM_CALLBACKS = 128
        // Keep the existing SDK session cap. Refresh only pooled metadata before the
        // remaining window is too short for a 30s call plus authorization/writer tails.
        private val RESOURCE_ADMISSION_BUDGET = 60.seconds
        private const val MAXIMUM_BUSINESS_SESSION_LIFETIME_MILLIS = 120_000L
    }
}
