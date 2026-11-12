package com.ukkera.pos.rpc

import com.ukkera.pos.security.LocalEmployeeCreationConfigurationV1
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalApprovalPolicy
import com.ukkera.pos.security.LocalAuthenticationDenial
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.NoMonetaryCeiling
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TenantScope
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.CharArraySerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
internal enum class LocalRpcAuthenticationAction { LOGIN, REFRESH, CURRENT, LOGOUT }

/** Decoded buffers belong to this DTO, not to an application caller. No identity assertions. */
@Serializable(with = LocalRpcAuthenticationRequestSerializer::class)
internal class LocalRpcAuthenticationRequest(
    val exchangeId: String,
    val action: LocalRpcAuthenticationAction,
    val login: String? = null,
    val password: CharArray? = null,
    val bearer: ByteArray? = null,
    val explicitDeviceMove: Boolean = false,
) {
    private enum class Lifetime { UNCLAIMED, CLAIMED, DESTROYING, DESTROYED }
    private val lifetime = MutableStateFlow(Lifetime.UNCLAIMED)
    private val cleanupCompleted = CompletableDeferred<Unit>()
    /** Completed wiping, not just claim denial; decoded retention must keep DESTROYING inputs. */
    internal val isDestroyed: Boolean get() = lifetime.value == Lifetime.DESTROYED

    /** Claim synchronously at actual callback entry, before any suspend/admission gate. */
    internal fun claimForCallback(): Boolean = lifetime.compareAndSet(Lifetime.UNCLAIMED, Lifetime.CLAIMED)

    /** Ordinary resource expiry cannot clear inputs already owned by an entered callback. */
    internal fun destroyIfUnclaimed() {
        if (lifetime.compareAndSet(Lifetime.UNCLAIMED, Lifetime.DESTROYING)) finishDestruction()
    }

    fun validateShape(): Boolean {
        val observed = lifetime.value
        if (observed == Lifetime.DESTROYING || observed == Lifetime.DESTROYED) return false
        return try {
            DomainId(exchangeId)
            when (action) {
                LocalRpcAuthenticationAction.LOGIN -> login != null &&
                    LocalLoginIdentifier(login).value == login && password != null &&
                    password.size in 4..1024 && bearer == null
                else -> login == null && password == null && !explicitDeviceMove && bearer?.size == 52
            }
        } catch (_: IllegalArgumentException) { false }
    }

    /** Terminal callback/decoder/drained-owner cleanup; the lifetime can never be reclaimed. */
    fun destroy() {
        while (true) {
            val observed = lifetime.value
            if (observed == Lifetime.DESTROYING || observed == Lifetime.DESTROYED) return
            if (lifetime.compareAndSet(observed, Lifetime.DESTROYING)) {
                finishDestruction()
                return
            }
        }
    }
    /** Only the entered callback owner may end CLAIMED input; wait for the actual wipe owner. */
    internal suspend fun destroyAndAwaitCleanup() {
        destroy()
        awaitCleanup()
    }
    /** A drained census can wait without stealing a claimed callback's buffers. */
    internal suspend fun awaitCleanup() { cleanupCompleted.await() }

    private fun finishDestruction() {
        try { clearSecrets() }
        catch (failure: Throwable) {
            cleanupCompleted.completeExceptionally(failure)
            throw failure
        }
        // Only the CAS owner publishes completion, and only after both array wipes return.
        lifetime.value = Lifetime.DESTROYED
        cleanupCompleted.complete(Unit)
    }
    private fun clearSecrets() { password?.fill('\u0000'); bearer?.fill(0) }
    override fun toString(): String = "LocalRpcAuthenticationRequest([REDACTED])"
}

/** Staged decoding owns secret arrays even when a later field/missing-field check fails. */
@OptIn(ExperimentalSerializationApi::class)
internal object LocalRpcAuthenticationRequestSerializer : KSerializer<LocalRpcAuthenticationRequest> {
    override val descriptor = buildClassSerialDescriptor("com.ukkera.pos.rpc.LocalRpcAuthenticationRequest") {
        element<String>("exchangeId")
        element<LocalRpcAuthenticationAction>("action")
        element<String?>("login", isOptional = true)
        element<CharArray?>("password", isOptional = true)
        element<ByteArray?>("bearer", isOptional = true)
        element<Boolean>("explicitDeviceMove", isOptional = true)
    }
    override fun serialize(encoder: Encoder, value: LocalRpcAuthenticationRequest) = encoder.encodeStructure(descriptor) {
        encodeStringElement(descriptor, 0, value.exchangeId)
        encodeSerializableElement(descriptor, 1, LocalRpcAuthenticationAction.serializer(), value.action)
        value.login?.let { encodeStringElement(descriptor, 2, it) }
        value.password?.let { encodeSerializableElement(descriptor, 3, CharArraySerializer(), it) }
        value.bearer?.let { encodeSerializableElement(descriptor, 4, ByteArraySerializer(), it) }
        if (value.action == LocalRpcAuthenticationAction.LOGIN || value.explicitDeviceMove || shouldEncodeElementDefault(descriptor, 5))
            encodeBooleanElement(descriptor, 5, value.explicitDeviceMove)
    }
    override fun deserialize(decoder: Decoder): LocalRpcAuthenticationRequest {
        var exchangeId: String? = null
        var action: LocalRpcAuthenticationAction? = null
        var login: String? = null
        var password: CharArray? = null
        var bearer: ByteArray? = null
        var move: Boolean? = null
        try {
            decoder.decodeStructure(descriptor) {
                while (true) when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> exchangeId = decodeStringElement(descriptor, index)
                    1 -> action = decodeSerializableElement(descriptor, index, LocalRpcAuthenticationAction.serializer())
                    2 -> login = decodeNullableSerializableElement(descriptor, index, serializer<String>())
                    3 -> { password?.fill('\u0000'); password = decodeNullableSerializableElement(descriptor, index, CharArraySerializer()) }
                    4 -> { bearer?.fill(0); bearer = decodeNullableSerializableElement(descriptor, index, ByteArraySerializer()) }
                    5 -> move = decodeBooleanElement(descriptor, index)
                    else -> throw SerializationException("Unexpected authentication field")
                }
            }
            val decodedAction = action ?: throw SerializationException("Missing authentication action")
            if (decodedAction == LocalRpcAuthenticationAction.LOGIN && move == null)
                throw SerializationException("Missing explicit device move consent")
            return LocalRpcAuthenticationRequest(exchangeId ?: throw SerializationException("Missing exchange identity"),
                decodedAction, login, password, bearer, move ?: false).also {
                password = null; bearer = null
            }
        } finally { password?.fill('\u0000'); bearer?.fill(0) }
    }
}

@Serializable
internal enum class LocalRpcAuthenticationError { UNAVAILABLE }

internal val authenticationExchangeProcedure = RpcProcedure(
    name = "pos.auth.exchange", version = 1,
    request = LocalRpcAuthenticationRequest.serializer(),
    response = LocalRpcAuthenticationResponseSerializer,
    applicationError = LocalRpcAuthenticationError.serializer(),
    retrySafety = RpcRetrySafety.NeverReinvoke,
    requestLimitBytes = 16_384, responseLimitBytes = 1_048_576, errorLimitBytes = 64,
)

/** CURRENT-only renewal has its own original namespace; version 1 remains one-shot. */
internal val authenticationCurrentProcedure = RpcProcedure(
    name = authenticationExchangeProcedure.name, version = 2,
    request = authenticationExchangeProcedure.request,
    response = authenticationExchangeProcedure.response,
    applicationError = authenticationExchangeProcedure.applicationError,
    retrySafety = authenticationExchangeProcedure.retrySafety,
    requestLimitBytes = authenticationExchangeProcedure.requestLimitBytes,
    responseLimitBytes = authenticationExchangeProcedure.responseLimitBytes,
    errorLimitBytes = authenticationExchangeProcedure.errorLimitBytes,
)

/**
 * One-shot ownership of decoded or outgoing secrets. Serialization transfers the payload out of
 * the cleanup census and clears its buffers on success AND failure. Abandoned responses can be
 * destroyed without racing an encoder into a partially zeroed successful reply. This does not
 * promise erasure of immutable codec intermediates or encoded SDK-owned packet bytes.
 */
internal class LocalRpcAuthenticationResponse private constructor(payload: LocalRpcAuthenticationPayload) {
    private val owned = MutableStateFlow<LocalRpcAuthenticationPayload?>(payload)

    internal fun takePayload(): LocalRpcAuthenticationPayload {
        val payload = owned.value ?: throw SerializationException("Authentication response is unavailable")
        if (!owned.compareAndSet(payload, null)) throw SerializationException("Authentication response is unavailable")
        return payload
    }

    fun matchesAction(action: LocalRpcAuthenticationAction): Boolean = owned.value?.action == action

    fun destroy() {
        val payload = owned.value ?: return
        if (owned.compareAndSet(payload, null)) payload.destroy()
    }

    fun authenticationResult(): LocalAuthenticationResult = consume { payload ->
        require(payload.action in setOf(LocalRpcAuthenticationAction.LOGIN, LocalRpcAuthenticationAction.REFRESH))
        when (payload.status) {
            LocalRpcAuthenticationStatus.AUTHENTICATED -> {
                val view = checkNotNull(payload.view).value()
                val access = OpaqueCredential(checkNotNull(payload.access))
                try {
                    LocalAuthenticationResult.Authenticated(LocalSessionTokens(view, access,
                        OpaqueCredential(checkNotNull(payload.refresh))))
                } catch (failure: Throwable) { access.destroy(); throw failure }
            }
            LocalRpcAuthenticationStatus.DENIED -> LocalAuthenticationResult.Denied(checkNotNull(payload.denial))
            LocalRpcAuthenticationStatus.UNAVAILABLE -> LocalAuthenticationResult.Unavailable
            LocalRpcAuthenticationStatus.STORAGE_UNCERTAIN -> LocalAuthenticationResult.StorageUncertain
            else -> throw SerializationException("Unexpected authentication response")
        }
    }

    fun sessionViewResult(): LocalSessionViewResult = consume { payload ->
        require(payload.action == LocalRpcAuthenticationAction.CURRENT)
        when (payload.status) {
            LocalRpcAuthenticationStatus.CURRENT -> LocalSessionViewResult.Current(checkNotNull(payload.view).value())
            LocalRpcAuthenticationStatus.DENIED -> LocalSessionViewResult.Denied
            LocalRpcAuthenticationStatus.UNAVAILABLE -> LocalSessionViewResult.Unavailable
            LocalRpcAuthenticationStatus.STORAGE_UNCERTAIN -> LocalSessionViewResult.StorageUncertain
            LocalRpcAuthenticationStatus.BUSY -> LocalSessionViewResult.Busy
            else -> throw SerializationException("Unexpected session response")
        }
    }

    fun signOutResult(): LocalSignOutResult = consume { payload ->
        require(payload.action == LocalRpcAuthenticationAction.LOGOUT)
        when (payload.status) {
            LocalRpcAuthenticationStatus.REVOKED -> LocalSignOutResult.Revoked
            LocalRpcAuthenticationStatus.DENIED -> LocalSignOutResult.Denied
            LocalRpcAuthenticationStatus.UNAVAILABLE -> LocalSignOutResult.Unavailable
            LocalRpcAuthenticationStatus.STORAGE_UNCERTAIN -> LocalSignOutResult.StorageUncertain
            LocalRpcAuthenticationStatus.BUSY -> LocalSignOutResult.Busy
            else -> throw SerializationException("Unexpected sign-out response")
        }
    }

    private inline fun <T> consume(block: (LocalRpcAuthenticationPayload) -> T): T {
        val payload = takePayload()
        return try { block(payload) } finally { payload.destroy() }
    }

    override fun toString(): String = "LocalRpcAuthenticationResponse([REDACTED])"

    companion object {
        /** Transfers an owned service result; the original issued secrets are always destroyed. */
        fun capture(action: LocalRpcAuthenticationAction, result: LocalAuthenticationResult): LocalRpcAuthenticationResponse {
            if (result !is LocalAuthenticationResult.Authenticated) {
                require(action == LocalRpcAuthenticationAction.LOGIN || action == LocalRpcAuthenticationAction.REFRESH)
                return LocalRpcAuthenticationResponse(LocalRpcAuthenticationPayload(action, when (result) {
                    is LocalAuthenticationResult.Denied -> LocalRpcAuthenticationStatus.DENIED
                    LocalAuthenticationResult.Unavailable -> LocalRpcAuthenticationStatus.UNAVAILABLE
                    LocalAuthenticationResult.StorageUncertain -> LocalRpcAuthenticationStatus.STORAGE_UNCERTAIN
                    else -> error("Unexpected authentication result")
                }, denial = (result as? LocalAuthenticationResult.Denied)?.reason))
            }
            var access: ByteArray? = null
            var refresh: ByteArray? = null
            return try {
                require(action == LocalRpcAuthenticationAction.LOGIN || action == LocalRpcAuthenticationAction.REFRESH)
                val view = LocalRpcSessionView.capture(result.tokens.view)
                val a = result.tokens.access.useBytes { it.copyOf() }.also { access = it }
                val r = result.tokens.refresh.useBytes { it.copyOf() }.also { refresh = it }
                LocalRpcAuthenticationResponse(LocalRpcAuthenticationPayload(action,
                    LocalRpcAuthenticationStatus.AUTHENTICATED, view = view, access = a, refresh = r)).also {
                    access = null; refresh = null
                }
            } finally { access?.fill(0); refresh?.fill(0); result.tokens.destroy() }
        }

        fun capture(action: LocalRpcAuthenticationAction, result: LocalSessionViewResult): LocalRpcAuthenticationResponse {
            require(action == LocalRpcAuthenticationAction.CURRENT)
            return LocalRpcAuthenticationResponse(LocalRpcAuthenticationPayload(action, when (result) {
                is LocalSessionViewResult.Current -> LocalRpcAuthenticationStatus.CURRENT
                LocalSessionViewResult.Denied -> LocalRpcAuthenticationStatus.DENIED
                LocalSessionViewResult.Unavailable -> LocalRpcAuthenticationStatus.UNAVAILABLE
                LocalSessionViewResult.StorageUncertain -> LocalRpcAuthenticationStatus.STORAGE_UNCERTAIN
                LocalSessionViewResult.Busy -> LocalRpcAuthenticationStatus.BUSY
            }, view = (result as? LocalSessionViewResult.Current)?.let { LocalRpcSessionView.capture(it.view) }))
        }

        fun capture(action: LocalRpcAuthenticationAction, result: LocalSignOutResult): LocalRpcAuthenticationResponse {
            require(action == LocalRpcAuthenticationAction.LOGOUT)
            return LocalRpcAuthenticationResponse(LocalRpcAuthenticationPayload(action, when (result) {
                LocalSignOutResult.Revoked -> LocalRpcAuthenticationStatus.REVOKED
                LocalSignOutResult.Denied -> LocalRpcAuthenticationStatus.DENIED
                LocalSignOutResult.Unavailable -> LocalRpcAuthenticationStatus.UNAVAILABLE
                LocalSignOutResult.StorageUncertain -> LocalRpcAuthenticationStatus.STORAGE_UNCERTAIN
                LocalSignOutResult.Busy -> LocalRpcAuthenticationStatus.BUSY
            }))
        }

        internal fun decoded(payload: LocalRpcAuthenticationPayload): LocalRpcAuthenticationResponse =
            LocalRpcAuthenticationResponse(payload)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object LocalRpcAuthenticationResponseSerializer : KSerializer<LocalRpcAuthenticationResponse> {
    private val codec = LocalRpcAuthenticationPayload.serializer()
    override val descriptor = codec.descriptor
    override fun serialize(encoder: Encoder, value: LocalRpcAuthenticationResponse) {
        val payload = value.takePayload()
        try { codec.serialize(encoder, payload) } finally { payload.destroy() }
    }
    override fun deserialize(decoder: Decoder): LocalRpcAuthenticationResponse {
        var action: LocalRpcAuthenticationAction? = null
        var status: LocalRpcAuthenticationStatus? = null
        var denial: LocalAuthenticationDenial? = null
        var view: LocalRpcSessionView? = null
        var access: ByteArray? = null
        var refresh: ByteArray? = null
        try {
            decoder.decodeStructure(descriptor) {
                while (true) when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> action = decodeSerializableElement(descriptor, index, LocalRpcAuthenticationAction.serializer())
                    1 -> status = decodeSerializableElement(descriptor, index, LocalRpcAuthenticationStatus.serializer())
                    2 -> denial = decodeNullableSerializableElement(descriptor, index, serializer<LocalAuthenticationDenial>())
                    3 -> view = decodeNullableSerializableElement(descriptor, index, LocalRpcSessionView.serializer())
                    4 -> { access?.fill(0); access = decodeNullableSerializableElement(descriptor, index, ByteArraySerializer()) }
                    5 -> { refresh?.fill(0); refresh = decodeNullableSerializableElement(descriptor, index, ByteArraySerializer()) }
                    else -> throw SerializationException("Unexpected authentication response field")
                }
            }
            val payload = LocalRpcAuthenticationPayload(action ?: throw SerializationException("Missing response action"),
                status ?: throw SerializationException("Missing response status"), denial, view, access, refresh)
            return LocalRpcAuthenticationResponse.decoded(payload).also { access = null; refresh = null }
        } finally { access?.fill(0); refresh?.fill(0) }
    }
}

@Serializable
internal enum class LocalRpcAuthenticationStatus { AUTHENTICATED, CURRENT, REVOKED, DENIED, UNAVAILABLE, STORAGE_UNCERTAIN, BUSY }

@Serializable
internal class LocalRpcAuthenticationPayload(
    val action: LocalRpcAuthenticationAction,
    val status: LocalRpcAuthenticationStatus,
    val denial: LocalAuthenticationDenial? = null,
    val view: LocalRpcSessionView? = null,
    val access: ByteArray? = null,
    val refresh: ByteArray? = null,
) {
    init {
        try {
            when (action) {
                LocalRpcAuthenticationAction.LOGIN, LocalRpcAuthenticationAction.REFRESH -> {
                    require(status in setOf(LocalRpcAuthenticationStatus.AUTHENTICATED, LocalRpcAuthenticationStatus.DENIED,
                        LocalRpcAuthenticationStatus.UNAVAILABLE, LocalRpcAuthenticationStatus.STORAGE_UNCERTAIN))
                    require((status == LocalRpcAuthenticationStatus.DENIED) == (denial != null))
                }
                LocalRpcAuthenticationAction.CURRENT -> require(status in setOf(LocalRpcAuthenticationStatus.CURRENT,
                    LocalRpcAuthenticationStatus.DENIED, LocalRpcAuthenticationStatus.UNAVAILABLE,
                    LocalRpcAuthenticationStatus.STORAGE_UNCERTAIN, LocalRpcAuthenticationStatus.BUSY) && denial == null)
                LocalRpcAuthenticationAction.LOGOUT -> require(status in setOf(LocalRpcAuthenticationStatus.REVOKED,
                    LocalRpcAuthenticationStatus.DENIED, LocalRpcAuthenticationStatus.UNAVAILABLE,
                    LocalRpcAuthenticationStatus.STORAGE_UNCERTAIN, LocalRpcAuthenticationStatus.BUSY) && denial == null)
            }
            require((status in setOf(LocalRpcAuthenticationStatus.AUTHENTICATED, LocalRpcAuthenticationStatus.CURRENT)) == (view != null))
            if (status == LocalRpcAuthenticationStatus.AUTHENTICATED) require(access?.size == 52 && refresh?.size == 52)
            else require(access == null && refresh == null)
        } catch (failure: Throwable) { destroy(); throw failure }
    }
    fun destroy() { access?.fill(0); refresh?.fill(0) }
    override fun toString(): String = "LocalRpcAuthenticationPayload([REDACTED])"
}

/** Explicit display snapshot. Decoding it does not establish any Master permission or identity. */
@Serializable
internal class LocalRpcSessionView(
    val actorId: DomainId, val scope: BranchScope, val deviceId: DomainId, val sessionId: DomainId,
    val generation: SessionGeneration, val login: String, val policy: LocalRpcPolicy,
    val accessExpiresAt: MonotonicMoment, val workExpiresAt: MonotonicMoment,
) {
    fun value(): LocalSessionView {
        val policyValue = policy.value()
        require(policyValue.scope == scope && policyValue.actorId == actorId)
        require(accessExpiresAt.bootId == workExpiresAt.bootId && accessExpiresAt.elapsedMillis <= workExpiresAt.elapsedMillis)
        return LocalSessionView(actorId, scope, deviceId, sessionId, generation, LocalLoginIdentifier(login),
            policyValue, accessExpiresAt, workExpiresAt)
    }
    override fun toString(): String = "LocalRpcSessionView([REDACTED])"
    companion object {
        fun capture(view: LocalSessionView): LocalRpcSessionView = LocalRpcSessionView(view.actorId, view.scope,
            view.deviceId, view.sessionId, view.generation, view.login.value, LocalRpcPolicy.capture(view.policy),
            view.accessExpiresAt, view.workExpiresAt).also { it.value() }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class LocalRpcPolicy(
    val scope: BranchScope, val actorId: DomainId, val revision: Revision, val grants: List<LocalRpcGrant>,
    val maximumConcurrentSessions: Int, val freeFailures: Int, val initialDelayMillis: Long,
    val maximumDelayMillis: Long, val attemptTimeoutMillis: Long,
    val approvalValidForMillis: Long? = null, val namedSelfApprovalActions: Set<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val employeeCreationConfigurationV1: ByteArray? = null,
) {
    fun value(): ActivatedLocalPolicy {
        require(grants.size <= 1024)
        require((approvalValidForMillis == null) == (namedSelfApprovalActions == null))
        val approval = approvalValidForMillis?.let { duration ->
            val actions = checkNotNull(namedSelfApprovalActions)
            require(actions.size <= 256)
            LocalApprovalPolicy(duration, actions.map { SecurityAction.valueOf(it) }.toSet())
        }
        return ActivatedLocalPolicy(scope, actorId, revision, grants.map { it.value() },
            LocalSessionPolicy(maximumConcurrentSessions),
            ThrottlePolicy(freeFailures, initialDelayMillis, maximumDelayMillis, attemptTimeoutMillis), approval,
            employeeCreationConfigurationV1?.let(LocalEmployeeCreationConfigurationV1::decode))
    }
    override fun toString(): String = "LocalRpcPolicy([REDACTED])"
    companion object {
        fun capture(policy: ActivatedLocalPolicy): LocalRpcPolicy = LocalRpcPolicy(policy.scope, policy.actorId,
            policy.revision, policy.grants.map { LocalRpcGrant.capture(it) }, policy.sessionPolicy.maximumConcurrentSessions,
            policy.throttlePolicy.freeFailures, policy.throttlePolicy.initialDelayMillis, policy.throttlePolicy.maximumDelayMillis,
            policy.throttlePolicy.attemptTimeoutMillis, policy.approvalPolicy?.validForMillis,
            policy.approvalPolicy?.namedSelfApprovalActions?.map { it.name }?.toSet(),
            policy.employeeCreationConfiguration?.copyWireBytes())
    }
}

@Serializable
internal class LocalRpcGrant(
    val action: String, val verb: String, val scope: TenantScope, val effect: String,
    val limits: LocalRpcLimits, val ownerAccess: String, val namedSubjectId: DomainId?,
    val targetIds: Set<DomainId>?, val delegationCeiling: LocalRpcLimits?,
) {
    fun value(): PermissionGrant = PermissionGrant(Permission(SecurityAction.valueOf(action), PermissionVerb.valueOf(verb)),
        scope, GrantEffect.valueOf(effect), limits.value(), OwnerAccess.valueOf(ownerAccess), namedSubjectId,
        targetIds, delegationCeiling?.value())
    override fun toString(): String = "LocalRpcGrant([REDACTED])"
    companion object {
        fun capture(grant: PermissionGrant): LocalRpcGrant = LocalRpcGrant(grant.permission.action.name,
            grant.permission.verb.name, grant.scope, grant.effect.name, LocalRpcLimits.capture(grant.limits),
            grant.ownerAccess.name, grant.namedSubjectId, grant.targetIds, grant.delegationCeiling?.let { LocalRpcLimits.capture(it) })
    }
}

@Serializable
internal class LocalRpcLimits(val maximumAmount: Money?, val maximumPercentageBasisPoints: Int?, val noMonetaryCeiling: Currency?) {
    fun value(): PermissionLimits = PermissionLimits(maximumAmount, maximumPercentageBasisPoints, noMonetaryCeiling?.let(::NoMonetaryCeiling))
    companion object {
        fun capture(limits: PermissionLimits): LocalRpcLimits = LocalRpcLimits(limits.maximumAmount,
            limits.maximumPercentageBasisPoints, limits.noMonetaryCeiling?.currency)
    }
}
