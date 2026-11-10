package com.ukkera.pos.database

import com.ukkera.pos.security.AuthorizationEvaluator
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.LocalAccessEligibility
import com.ukkera.pos.security.LocalAuthEvent
import com.ukkera.pos.security.LocalAuthEventKind
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalMasterLifecycleAction
import com.ukkera.pos.security.LocalMasterLifecycleAudit
import com.ukkera.pos.security.LocalMasterLifecycleDenial
import com.ukkera.pos.security.LocalMasterLifecycleReceipt
import com.ukkera.pos.security.LocalMasterLifecycleRequest
import com.ukkera.pos.security.LocalMasterLifecycleResult
import com.ukkera.pos.security.LocalMasterLifecycleService
import com.ukkera.pos.security.LocalMasterLifecycleStatus
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.lifecycleCoordinatesDigest
import com.ukkera.pos.security.lifecycleRequestDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore

/**
 * One protected lifecycle transaction over the SAME store/source/peer registry that issues and
 * revokes employee sessions. A successful STOP/RESUME audit, its current authorization cut and
 * the employee revocation lane share the concrete writer. A UI flag, setup command or cached
 * grant cannot enter here; the request carries no coordinates and the owner binds its exact
 * original. The action is a durable intent/result record, not a business command or session.
 */
internal class RoomLocalMasterLifecycleService(
    private val scope: BranchScope,
    private val coordinates: SelfOwnedAllocationCoordinatesV1,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peers: RoomLocalAccessPeerSource,
    private val principals: TrustedPrincipalFactory,
) : LocalMasterLifecycleService {
    private val slots = Semaphore(1)

    init {
        check(source.belongsTo(store) && coordinates.scope == scope)
    }

    override suspend fun apply(credentials: LocalGatewayCredentials,
        request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult =
        bounded(LocalMasterLifecycleResult.Busy, LocalMasterLifecycleResult.Unavailable,
            LocalMasterLifecycleResult.StorageUncertain,
            LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.NOT_AUTHORIZED)) {
            val (principal, peer) = authenticate(credentials)
            val result = localSecurityLane(store.database) {
                store.transaction { transaction ->
                    val permitted = authorize(transaction, principal, peer, request)
                    val existing = transaction.readAuthEvent(scope, request.requestId)
                    if (existing != null) return@transaction replay(existing, principal, request)
                    if (request.requestId == coordinates.installationId)
                        return@transaction LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.IDENTITY_CONFLICT)
                    val observed = source.observeCurrent(currentCoroutineContext())
                    val digest = lifecycleRequestDigest(coordinates, request)
                    val event = LocalAuthEvent(scope, request.requestId, LocalAuthEventKind.MASTER_LIFECYCLE,
                        coordinates.installationId, principal.actorId, principal.sessionId,
                        permitted.facts.account.revision, observed.moment, digest,
                        lifecycle = LocalMasterLifecycleAudit(request.action, coordinates.installationId,
                            lifecycleCoordinatesDigest(coordinates), digest, permitted.facts.policy.revision,
                            principal.deviceId, principal.sessionGeneration, permitted.facts.admission.revision,
                            observed.issuerContextId))
                    val evidence = RuntimeMasterLifecycleEvidenceV1(
                        schema = RuntimeMasterLifecycleEvidenceV1.PROFILE,
                        scope = scope, eventId = request.requestId, actorId = principal.actorId,
                        sessionId = principal.sessionId, accountRevision = permitted.facts.account.revision,
                        action = request.action, installationId = coordinates.installationId,
                        coordinatesDigest = lifecycleCoordinatesDigest(coordinates), requestDigest = digest,
                        policyRevision = permitted.facts.policy.revision, deviceId = principal.deviceId,
                        sessionGeneration = principal.sessionGeneration,
                        admissionRevision = permitted.facts.admission.revision,
                        issuerContextId = observed.issuerContextId, at = observed.moment)
                    transaction.historyMasterLifecycle(evidence, principal, permitted.authorization,
                        permitted.facts, observed)
                    transaction.historyPurpose(RuntimeWritePurposeV2.ROLE_TRANSITION, principal.actorId,
                        request.requestId, digest)
                    transaction.insertAuthEvent(event)
                    transaction.ensureAuthorizationHealthy()
                    LocalMasterLifecycleResult.Committed(receipt(event, request))
                }
            }
            // The result is meaningful only after the actual protected COMMIT and outer cleanup.
            currentCoroutineContext().ensureActive()
            if (!peer.isCurrent()) LocalMasterLifecycleResult.Unavailable else result
        }

    override suspend fun lookup(credentials: LocalGatewayCredentials,
        request: LocalMasterLifecycleRequest): LocalMasterLifecycleStatus =
        bounded(LocalMasterLifecycleStatus.Busy, LocalMasterLifecycleStatus.Unavailable,
            LocalMasterLifecycleStatus.StorageUncertain,
            LocalMasterLifecycleStatus.Denied(LocalMasterLifecycleDenial.NOT_AUTHORIZED)) {
            val (principal, peer) = authenticate(credentials)
            val result = localSecurityLane(store.database) {
                store.transaction { transaction ->
                    val permitted = authorize(transaction, principal, peer, request)
                    val existing = transaction.readAuthEvent(scope, request.requestId)
                        ?: return@transaction LocalMasterLifecycleStatus.NotRecorded
                    when (val replay = replay(existing, principal, request)) {
                        is LocalMasterLifecycleResult.Committed ->
                            LocalMasterLifecycleStatus.Committed(replay.receipt)
                        is LocalMasterLifecycleResult.Denied -> LocalMasterLifecycleStatus.Denied(replay.reason)
                        LocalMasterLifecycleResult.Unavailable -> LocalMasterLifecycleStatus.Unavailable
                        LocalMasterLifecycleResult.StorageUncertain -> LocalMasterLifecycleStatus.StorageUncertain
                        LocalMasterLifecycleResult.Busy -> LocalMasterLifecycleStatus.Busy
                    }.also { transaction.ensureAuthorizationHealthy() }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!peer.isCurrent()) LocalMasterLifecycleStatus.Unavailable else result
        }

    private fun replay(existing: LocalAuthEvent, principal: LocalEmployeePrincipal,
        request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult {
        val digest = lifecycleRequestDigest(coordinates, request)
        val audit = existing.lifecycle
        if (existing.scope != scope || existing.eventId != request.requestId ||
            existing.kind != LocalAuthEventKind.MASTER_LIFECYCLE ||
            existing.targetId != coordinates.installationId || existing.operatorId != principal.actorId ||
            existing.contentDigest != digest || audit == null || audit.action != request.action ||
            audit.installationId != coordinates.installationId ||
            audit.coordinatesDigest != lifecycleCoordinatesDigest(coordinates) || audit.requestDigest != digest ||
            existing.sessionId == null || existing.accountRevision == null ||
            audit.deviceId == null || audit.sessionGeneration == null ||
            audit.admissionRevision == null || audit.issuerContextId == null) {
            return LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.IDENTITY_CONFLICT)
        }
        return LocalMasterLifecycleResult.Committed(LocalMasterLifecycleReceipt(
            existing.eventId, audit.action, checkNotNull(existing.operatorId), checkNotNull(existing.sessionId),
            audit.installationId, audit.coordinatesDigest, audit.requestDigest,
            checkNotNull(existing.accountRevision), audit.policyRevision, existing.at))
    }

    private fun receipt(event: LocalAuthEvent, request: LocalMasterLifecycleRequest): LocalMasterLifecycleReceipt {
        val audit = checkNotNull(event.lifecycle)
        return LocalMasterLifecycleReceipt(event.eventId, audit.action, checkNotNull(event.operatorId),
            checkNotNull(event.sessionId), audit.installationId, audit.coordinatesDigest, audit.requestDigest,
            checkNotNull(event.accountRevision), audit.policyRevision, event.at)
    }

    private suspend fun authenticate(credentials: LocalGatewayCredentials):
        Pair<LocalEmployeePrincipal, RoomLocalAccessPeerBinding> {
        val principal = principals.authenticateLocal(credentials) ?: currentLocalAuthorizationDenied()
        val peer = peers.capture(credentials.connectionId)?.takeIf {
            it.scope == scope && it.connectionId == credentials.connectionId &&
                it.deviceId == principal.deviceId && it.isCurrent()
        } ?: currentLocalAuthorizationDenied()
        return principal to peer
    }

    private suspend fun authorize(transaction: RoomLocalSecurityTransaction, principal: LocalEmployeePrincipal,
        peer: RoomLocalAccessPeerBinding, request: LocalMasterLifecycleRequest): Permitted {
        if (principal.scope != scope || !peer.isCurrent()) currentLocalAuthorizationDenied()
        val facts = transaction.readAuthorizationFacts(principal) ?: currentLocalAuthorizationDenied()
        val now = source.observeCurrent(currentCoroutineContext())
        if (!peer.matchesAdmission(facts.admission) || !LocalAccessEligibility.matchesCurrentFacts(principal, facts.issuance,
                facts.account, facts.credentialRevision, facts.credentialPresent, facts.admission, now.issuerContextId, now.moment))
            currentLocalAuthorizationDenied()
        val snapshot = AuthorizationSnapshot(principal.actorId, principal.kind, principal.scope, principal.sessionId,
            principal.sessionGeneration, principal.deviceId, facts.admission.revision, facts.policy.revision,
            facts.account.enabled, true, facts.admission.admitted, facts.issuance.authenticationPurpose,
            facts.policy.grants)
        val requirement = AuthorizationRequirement(
            Permission(SecurityAction.MASTER_LIFECYCLE, PermissionVerb.ADMINISTER), scope,
            ActionBinding(coordinates.installationId, Revision(0), lifecycleRequestDigest(coordinates, request),
                OperationId(request.requestId.value), "local-master-lifecycle.${request.action.name.lowercase()}"))
        val allowed = AuthorizationEvaluator.evaluate(principal, snapshot, requirement)
            as? com.ukkera.pos.security.AuthorizationDecision.Allowed ?: currentLocalAuthorizationDenied()
        return Permitted(facts, allowed.authorization, now)
    }

    private suspend fun <T> bounded(busy: T, unavailable: T, uncertain: T, denied: T, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return busy
        return try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RoomCurrentLocalAuthorizationDenied) { denied }
        catch (_: RoomLocalStorageUncertain) { currentCoroutineContext().ensureActive(); uncertain }
        catch (_: RoomLocalAccessVerificationStorageUncertain) { currentCoroutineContext().ensureActive(); uncertain }
        catch (_: RoomLocalAccessVerificationBusy) { busy }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); unavailable }
        finally { slots.release() }
    }

    private class Permitted(val facts: RoomLocalAuthorizationFacts,
        val authorization: com.ukkera.pos.security.AuthorizedAction,
        val observation: RoomLocalAuthorizationObservation)
}

/** Same exact requirement used by the writer and historical replay. */
internal fun runtimeLifecycleRequirement(scope: BranchScope, audit: LocalMasterLifecycleAudit,
    event: LocalAuthEvent): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.MASTER_LIFECYCLE, PermissionVerb.ADMINISTER), scope,
    ActionBinding(audit.installationId, Revision(0), audit.requestDigest,
        OperationId(event.eventId.value), "local-master-lifecycle.${audit.action.name.lowercase()}"))
