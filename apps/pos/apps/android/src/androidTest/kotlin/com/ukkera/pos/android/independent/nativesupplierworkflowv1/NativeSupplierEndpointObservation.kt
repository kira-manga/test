package com.ukkera.pos.android.independent.nativesupplierworkflowv1

import android.util.Base64
import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/** Expected frozen scalar values plus genuinely observed form identity/revision. No command ID
 * is created here; normal originals come from the actual shared UI's separate Stage action. */
internal data class NativeSupplierCommandInput(
    val supplierId: DomainId,
    val names: LocalNamesV1,
    val active: Boolean,
    val expectedStorageRevision: Revision?,
) {
    val action: LocalAction get() = if (expectedStorageRevision == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE
}

internal enum class NativeSupplierInputOrigin { SHARED_UI_STAGE, SYNTHETIC_APPLICATION_BOUNDARY }

/** One actual durably staged input. This data cannot stage, authenticate, or issue a command. */
internal class NativeSupplierOriginal(
    val symbol: String,
    val operationId: OperationId,
    val input: NativeSupplierCommandInput,
    val inputOrigin: NativeSupplierInputOrigin,
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
            value.originalActorId == owner.actorId)
        identity?.let { check(it == value) { "An original semantic identity changed" } } ?: run { identity = value }
    }

    fun beforeDispatch(value: MutationRequest, view: LocalSessionView, evidence: NativeSupplierEvidence) {
        check(request == null) { "This original has already reached first dispatch" }
        check(value.operationId == operationId && value.command == LocalConfigurationProfile.COMMAND && value.actionApprovalId == null)
        check(view.scope == owner.scope && view.actorId == owner.actorId && view.deviceId == owner.deviceId)
        val original = checkNotNull(identity) { "No genuine saved-original lookup preceded dispatch" }
        check(original.semanticDigestVersion == value.command.version && original.semanticSha256 ==
            semanticDigest(owner.scope, owner.actorId, value.command, value.payload).value)
        val decoded = LocalConfigurationCodec.decode(value.payload)
        check(decoded is LocalConfigurationRequestV1.ConfigureSupplier && LocalConfigurationCodec.encode(decoded) == value.payload &&
            decoded.supplier == LocalSupplierV1(input.supplierId, input.names, input.active) &&
            decoded.expectedStorageRevision == input.expectedStorageRevision)
        request = value
        session = view
        evidence.bind("${symbol}_PRE_DISPATCH", JSONObject()
            .put("operationId", value.operationId.value).put("originalActorId", owner.actorId.value)
            .put("deviceId", owner.deviceId.value).put("branchId", owner.scope.branchId.value)
            .put("sessionId", view.sessionId.value).put("policyRevision", view.policy.revision.value)
            .put("inputOrigin", inputOrigin.name)
            .put("semanticDigest", original.semanticSha256)
            .put("expectedStorageRevision", decoded.expectedStorageRevision?.value ?: JSONObject.NULL)
            .put("canonicalSupplierInput", supplierBase64(value.payload)))
    }

    fun observeResult(value: MutationReply.Recorded) {
        check(value.operationId == operationId && value.originalActorId == owner.actorId && value.physicalStart == null)
        result?.let { requireSameSupplierResult(it, value) } ?: run { result = value }
    }
}

internal class NativeSupplierOriginals(private val evidence: NativeSupplierEvidence) {
    private val values = linkedMapOf<OperationId, NativeSupplierOriginal>()
    private val unboundLookups = mutableMapOf<OperationId, OperationIdentity>()
    val all: List<NativeSupplierOriginal> get() = synchronized(this) { values.values.toList() }

    @Synchronized fun staged(symbol: String, pending: LocalPendingOperation, input: NativeSupplierCommandInput,
        actualOwner: LocalClientIdentity, inputOrigin: NativeSupplierInputOrigin): NativeSupplierOriginal {
        check(values[pending.operationId] == null && values.values.none { it.symbol == symbol })
        check(pending.action == input.action && pending.phase == LocalPendingPhase.STAGED && pending.recordedDecision == null)
        val value = NativeSupplierOriginal(symbol, pending.operationId, input, inputOrigin, actualOwner)
        unboundLookups.remove(pending.operationId)?.let(value::observeLookup)
        values[pending.operationId] = value
        evidence.bind(symbol, pending.operationId.value)
        evidence.event("${symbol}_ACTUAL_DURABLE_STAGE_RETURNED", JSONObject().put("inputOrigin", inputOrigin.name))
        return value
    }

    /** The shared controller can refresh pending immediately after Stage, before the caller
     * observes its return. Retain that genuine saved identity, never an absence or result. */
    @Synchronized fun observeLookup(identity: OperationIdentity) {
        values[identity.operationId]?.let { it.observeLookup(identity); return }
        val before = unboundLookups[identity.operationId]
        if (before != null) check(before == identity)
        else {
            check(unboundLookups.isEmpty()) { "Unexpected unbound original in the isolated supplier fixture" }
            unboundLookups[identity.operationId] = identity
        }
    }

    @Synchronized fun original(operationId: OperationId): NativeSupplierOriginal =
        checkNotNull(values[operationId]) { "A mutation or lookup did not use a retained application original" }
}

/**
 * Pass-through observation of the SAME original Master, not a replacement service or Client.
 * Authentication/workflow arguments and replies are forwarded unchanged. The real gateway
 * still owns the genuine credential and intent journals, durable stage, Current and recovery.
 *
 * The sole extra secret is an independently owned, runtime-only copy of that actual login's
 * access token, used for explicit named size-1 reads on the exact same protected endpoint.
 * It is never serialized and never supplies a principal, absence, policy or Current result.
 * This is NOT installed UI pagination or additional-device credential custody evidence.
 */
internal class NativeSupplierEndpointObservation(
    private val original: AndroidOriginalLocalClient,
    private val originals: NativeSupplierOriginals,
    private val evidence: NativeSupplierEvidence,
) : LocalMasterClient by original.client {
    private val secretGate = Any()
    private var namedReadAccess: OpaqueCredential? = null
    private val activeNamedReads = mutableMapOf<OpaqueCredential, CompletableDeferred<Unit>>()
    private val lookupGate = Any()
    private val lookupCompletions = mutableMapOf<OperationId, Long>()
    private var issued: LocalSessionView? = null
    private var verified: LocalSessionView? = null
    private var retired = false
    var signIns = 0
        private set

    val currentView: LocalSessionView get() = synchronized(secretGate) {
        if (retired || !original.isCurrent()) {
            clearAccessLocked()
            error("The original named-read/session observation owner has retired")
        }
        checkNotNull(verified) { "No actual issued session has passed fresh Current" }
    }

    /** Observation only: used to distinguish a fresh protected UI lookup from an old card. */
    fun completedOriginalLookups(operationId: OperationId): Long = synchronized(lookupGate) {
        lookupCompletions[operationId] ?: 0L
    }

    val hasNamedReadAccess: Boolean get() = synchronized(secretGate) {
        namedReadAccess != null || activeNamedReads.isNotEmpty()
    }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier,
        password: CharArray, explicitDeviceMove: Boolean): LocalAuthenticationResult {
        clearAccess()
        var reply: LocalAuthenticationResult? = null
        try {
            signIns++
            val result = original.client.signIn(connectionId, login, password, explicitDeviceMove).also { reply = it }
            if (result is LocalAuthenticationResult.Authenticated) captureIssued(result.tokens, connectionId)
            return result
        } catch (failure: Throwable) {
            clearAccess()
            (reply as? LocalAuthenticationResult.Authenticated)?.tokens?.destroy()
            throw failure
        }
    }

    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult {
        clearAccess()
        // This case never requests renewal; do not make an unobserved successor available to
        // the named-read helper. The production gateway still receives the unchanged reply.
        return original.client.refresh(credentials)
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = try {
        val result = original.client.current(credentials)
        if (result is LocalSessionViewResult.Current) {
            synchronized(secretGate) {
                val expected = issued
                if (!retired && original.isCurrent() && expected != null && sameSession(expected, result.view))
                    verified = result.view
                else clearAccessLocked()
            }
        } else clearAccess()
        result
    } catch (failure: Throwable) { clearAccess(); throw failure }

    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult {
        clearAccess() // Local retirement is immediate even if authoritative revocation is unknown.
        return original.client.logout(credentials)
    }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply = try {
        originals.observeLookup(identity)
        original.client.lookup(credentials, identity).also {
            if (it is LookupReply.Found) {
                originals.original(identity.operationId).observeResult(it.result)
                synchronized(lookupGate) {
                    lookupCompletions[identity.operationId] = (lookupCompletions[identity.operationId] ?: 0L) + 1L
                }
            } else if (it !is LookupReply.AbsentAtCurrentWriter) clearAccess()
        }
    } catch (failure: Throwable) { clearAccess(); throw failure }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply = try {
        check(credentials.connectionId == original.peer.connectionId && original.isCurrent())
        val saved = originals.original(request.operationId)
        saved.beforeDispatch(request, currentView, evidence)
        // No observer-created result, accepting transaction, journal or retry enters this call.
        original.client.execute(credentials, request).also {
            if (it is MutationReply.Recorded) saved.observeResult(it) else clearAccess()
        }
    } catch (failure: Throwable) { clearAccess(); throw failure }

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply = try {
        original.client.read(credentials, request).also { if (it !is ReadReply.Data) clearAccess() }
    } catch (failure: Throwable) { clearAccess(); throw failure }

    suspend fun namedRead(query: LocalConfigurationQueryV1): Pair<ReadReply.Data, LocalConfigurationPageV1> {
        var retained: OpaqueCredential? = null
        try {
            check(query.kind == LocalConfigurationProfile.SUPPLIER && query.limit == 1 && query.after == null)
            val (access, expected) = synchronized(secretGate) {
                check(!retired && original.isCurrent())
                val current = checkNotNull(verified)
                val copy = checkNotNull(namedReadAccess).useBytes(::OpaqueCredential)
                retained = copy // Retained even if registering the completion fails.
                activeNamedReads[copy] = CompletableDeferred()
                copy to current
            }
            val credentials = LocalGatewayCredentials(original.peer.connectionId, access)
            val before = original.client.current(credentials) as? LocalSessionViewResult.Current
                ?: error("Named supplier read has no current authority")
            check(sameSession(expected, before.view) && original.isCurrent())
            requireNamedReadOwner(access, expected)
            val reply = original.client.read(credentials,
                ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(query))) as? ReadReply.Data
                ?: error("Named supplier read is unavailable; no empty success is substituted")
            requireNamedReadOwner(access, expected)
            val after = original.client.current(credentials) as? LocalSessionViewResult.Current
                ?: error("Named supplier read lost current authority")
            check(sameSession(expected, after.view) && original.isCurrent())
            val page = LocalConfigurationCodec.decodePage(reply.payload)
            check(page.kind == query.kind)
            requireNamedReadOwner(access, expected)
            return reply to page
        } catch (failure: Throwable) { clearAccess(); throw failure }
        finally { synchronized(secretGate) {
            retained?.let { access ->
                access.destroy()
                activeNamedReads.remove(access)?.complete(Unit)
            }
        } }
    }

    private fun requireNamedReadOwner(access: OpaqueCredential, expected: LocalSessionView) = synchronized(secretGate) {
        check(!retired && original.isCurrent() && access in activeNamedReads && namedReadAccess != null &&
            verified?.let { sameSession(expected, it) } == true) { "Named-read owner retired before delivery" }
    }

    private fun captureIssued(tokens: LocalSessionTokens, connectionId: DomainId) = synchronized(secretGate) {
        check(!retired && original.isCurrent() && connectionId == original.peer.connectionId)
        val view = tokens.view
        val c = original.material.coordinates
        check(view.scope == c.scope && view.actorId == c.firstActorId && view.deviceId == c.deviceId &&
            view.login == NativeSupplierFixture.login)
        val copy = tokens.access.useBytes(::OpaqueCredential)
        clearAccessLocked()
        namedReadAccess = copy
        issued = view
    }

    fun retire() = synchronized(secretGate) { retired = true; clearAccessLocked() }

    /** Native root close waits for every genuine helper call to relinquish its owned copy.
     * Retirement denied acquisition synchronously; no same-key successor can join this set. */
    suspend fun awaitRetiredNamedReads() {
        val pending = synchronized(secretGate) { check(retired); activeNamedReads.values.toList() }
        pending.forEach { it.await() }
        check(synchronized(secretGate) { activeNamedReads.isEmpty() && namedReadAccess == null })
    }
    private fun clearAccess() = synchronized(secretGate) { clearAccessLocked() }
    private fun clearAccessLocked() {
        namedReadAccess?.destroy(); namedReadAccess = null; issued = null; verified = null
        // Wipe immediately, but retain the actual completion obligations until their finally
        // blocks finish; a cleared token is not proof that its native call already returned.
        activeNamedReads.keys.forEach { it.destroy() }
    }

    private fun sameSession(a: LocalSessionView, b: LocalSessionView): Boolean =
        a.actorId == b.actorId && a.scope == b.scope && a.deviceId == b.deviceId &&
            a.sessionId == b.sessionId && a.generation == b.generation && a.login == b.login
}

internal fun requireSameSupplierResult(a: MutationReply.Recorded, b: MutationReply.Recorded) {
    check(a.operationId == b.operationId && a.originalActorId == b.originalActorId && a.committed == b.committed &&
        a.code == b.code && a.result == b.result && a.authorityEpoch == b.authorityEpoch &&
        a.sequence == b.sequence && a.recordedAt == b.recordedAt) { "An immutable original result changed" }
}

internal fun supplierBase64(bytes: StoredBytes): String {
    val copy = bytes.copyBytes()
    return try { Base64.encodeToString(copy, Base64.NO_WRAP) } finally { copy.fill(0) }
}
