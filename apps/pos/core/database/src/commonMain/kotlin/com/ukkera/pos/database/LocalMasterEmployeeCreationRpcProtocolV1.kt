@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.database

import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAuthConfiguration
import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalEmployeeCreationCancellationV1
import com.ukkera.pos.security.LocalEmployeeCreationConfigurationV1
import com.ukkera.pos.security.LocalEmployeeCreationContextResultV1
import com.ukkera.pos.security.LocalEmployeeCreationContextV1
import com.ukkera.pos.security.LocalEmployeeCreationDefinitionResultV1
import com.ukkera.pos.security.LocalEmployeeCreationDraftV1
import com.ukkera.pos.security.LocalEmployeeCreationExecutionV1
import com.ukkera.pos.security.LocalEmployeeCreationInputFailureV1
import com.ukkera.pos.security.LocalEmployeeCreationReceiptV1
import com.ukkera.pos.security.LocalEmployeeCreationReviewV1
import com.ukkera.pos.security.LocalEmployeeCreationServiceV1
import com.ukkera.pos.security.LocalEmployeeCreationStatusV1
import com.ukkera.pos.security.LocalEmployeeCreationTargetV1
import com.ukkera.pos.security.LocalEmployeeDelegationFailureV1
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.CharArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Fixed remote employee-creation route of one retained original Client session. The Host derives
 * the operator from its transport-authenticated bearer, never from body DATA, and the actual
 * Master creation service reauthorizes the current creator/exact target for every call. A
 * delivered result is DATA bound to the queried target/original and verified requesting actor;
 * the Client refuses any other binding. Lost or refused delivery is uncertainty, never absence.
 */
public object LocalMasterEmployeeCreationRpcProtocolV1 {
    public const val MAXIMUM_WIRE_BYTES: Int = 1_048_576

    public val PROCEDURE: RpcProcedure<LocalMasterRpcEmployeeCreationRequestV1, LocalMasterRpcEmployeeCreationResponseV1,
        LocalMasterClientRpcErrorV1> = RpcProcedure(
        name = "pos.employee-creation", version = 1,
        request = LocalMasterRpcEmployeeCreationRequestSerializerV1,
        response = LocalMasterRpcEmployeeCreationResponseV1.serializer(),
        applicationError = LocalMasterClientRpcErrorV1.serializer(),
        retrySafety = RpcRetrySafety.NeverReinvoke,
        requestLimitBytes = MAXIMUM_WIRE_BYTES,
        responseLimitBytes = MAXIMUM_WIRE_BYTES,
        errorLimitBytes = LocalMasterClientRpcProtocolV1.ERROR_LIMIT_BYTES,
    )

    /**
     * Master side. [operator] must be the Host's transport-authenticated credentials and
     * [requestingActorId] its verified original actor. Malformed body DATA refuses before the
     * service; the service retains every authorization, currentness and native decision.
     */
    public suspend fun dispatch(service: LocalEmployeeCreationServiceV1, operator: LocalGatewayCredentials,
        request: LocalMasterRpcEmployeeCreationRequestV1, requestingActorId: DomainId): LocalMasterRpcEmployeeCreationResponseV1 {
        val result: LocalMasterRpcEmployeeCreationResultV1 = when (val call = request.call) {
            is LocalMasterRpcEmployeeCreationCallV1.Context -> encodeContext(service.employeeCreationContext(operator,
                LocalEmployeeCreationTargetV1(call.requestId, call.actorId)))
            is LocalMasterRpcEmployeeCreationCallV1.Capture -> {
                val draft = try { call.draft() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (draft == null) outcome(Code.DENIED)
                else when (val captured = service.captureEmployeeCreationDefinition(operator, draft)) {
                    is LocalEmployeeCreationDefinitionResultV1.Captured ->
                        LocalMasterRpcEmployeeCreationResultV1.Captured(captured.original.copyWireBytes())
                    is LocalEmployeeCreationDefinitionResultV1.Refused -> refused(captured.review)
                    LocalEmployeeCreationDefinitionResultV1.Denied -> outcome(Code.DENIED)
                    LocalEmployeeCreationDefinitionResultV1.Unavailable -> outcome(Code.UNAVAILABLE)
                    LocalEmployeeCreationDefinitionResultV1.StorageUncertain -> outcome(Code.STORAGE_UNCERTAIN)
                    LocalEmployeeCreationDefinitionResultV1.Busy -> outcome(Code.BUSY)
                }
            }
            else -> {
                val original = try { call.original() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (original == null) outcome(Code.DENIED) else when (call) {
                    is LocalMasterRpcEmployeeCreationCallV1.Review -> encodeReview(service.reviewEmployeeCreation(operator, original))
                    is LocalMasterRpcEmployeeCreationCallV1.Lookup -> encodeStatus(service.lookupEmployeeCreation(operator, original), existing = false)
                    is LocalMasterRpcEmployeeCreationCallV1.Cancel -> when (val value = service.cancelEmployeeCreation(operator, original)) {
                        is LocalEmployeeCreationCancellationV1.Recorded -> receipt(ReceiptKind.RECORDED, value.receipt)
                        is LocalEmployeeCreationCancellationV1.Existing -> encodeStatus(value.status, existing = true)
                        LocalEmployeeCreationCancellationV1.Denied -> outcome(Code.DENIED)
                        LocalEmployeeCreationCancellationV1.Unavailable -> outcome(Code.UNAVAILABLE)
                        LocalEmployeeCreationCancellationV1.StorageUncertain -> outcome(Code.STORAGE_UNCERTAIN)
                        LocalEmployeeCreationCancellationV1.Busy -> outcome(Code.BUSY)
                    }
                    is LocalMasterRpcEmployeeCreationCallV1.Execute -> {
                        val password = request.usePassword { owned -> owned?.copyOf() }
                        try {
                            when (val value = service.executeEmployeeCreation(operator, original, password)) {
                                is LocalEmployeeCreationExecutionV1.Inserted -> receipt(ReceiptKind.INSERTED, value.receipt)
                                is LocalEmployeeCreationExecutionV1.Existing -> encodeStatus(value.status, existing = true)
                                is LocalEmployeeCreationExecutionV1.Refused -> refused(value.review)
                                LocalEmployeeCreationExecutionV1.Denied -> outcome(Code.DENIED)
                                LocalEmployeeCreationExecutionV1.Unavailable -> outcome(Code.UNAVAILABLE)
                                LocalEmployeeCreationExecutionV1.StorageUncertain -> outcome(Code.STORAGE_UNCERTAIN)
                                LocalEmployeeCreationExecutionV1.Busy -> outcome(Code.BUSY)
                            }
                        } finally { password?.fill('\u0000') }
                    }
                    else -> outcome(Code.DENIED)
                }
            }
        }
        return LocalMasterRpcEmployeeCreationResponseV1(requestingActorId, result)
    }

    // ---- Client side: accept only the exact queried binding. ----

    public fun decodeContext(target: LocalEmployeeCreationTargetV1, actor: DomainId,
        response: LocalMasterRpcEmployeeCreationResponseV1): LocalEmployeeCreationContextResultV1 =
        when (val value = bound(actor, response)) {
            is LocalMasterRpcEmployeeCreationResultV1.Context -> {
                binding(value.operatorId == actor && value.requestId == target.requestId && value.actorId == target.actorId)
                LocalEmployeeCreationContextResultV1.Available(value.context())
            }
            is LocalMasterRpcEmployeeCreationResultV1.Refused -> LocalEmployeeCreationContextResultV1.Refused(value.review())
            is LocalMasterRpcEmployeeCreationResultV1.Outcome -> when (value.code) {
                Code.DENIED -> LocalEmployeeCreationContextResultV1.Denied
                Code.UNAVAILABLE -> LocalEmployeeCreationContextResultV1.Unavailable
                Code.STORAGE_UNCERTAIN -> LocalEmployeeCreationContextResultV1.StorageUncertain
                Code.BUSY -> LocalEmployeeCreationContextResultV1.Busy
                else -> mismatch()
            }
            else -> mismatch()
        }

    public fun decodeCapture(draft: LocalEmployeeCreationDraftV1, actor: DomainId,
        response: LocalMasterRpcEmployeeCreationResponseV1): LocalEmployeeCreationDefinitionResultV1 =
        when (val value = bound(actor, response)) {
            is LocalMasterRpcEmployeeCreationResultV1.Captured -> {
                val original = LocalDelegatedEmployeeCreationV1.decode(value.original)
                binding(original.operatorId == actor && draft.matchesRequested(original))
                LocalEmployeeCreationDefinitionResultV1.Captured(original)
            }
            is LocalMasterRpcEmployeeCreationResultV1.Refused -> LocalEmployeeCreationDefinitionResultV1.Refused(value.review())
            is LocalMasterRpcEmployeeCreationResultV1.Outcome -> when (value.code) {
                Code.DENIED -> LocalEmployeeCreationDefinitionResultV1.Denied
                Code.UNAVAILABLE -> LocalEmployeeCreationDefinitionResultV1.Unavailable
                Code.STORAGE_UNCERTAIN -> LocalEmployeeCreationDefinitionResultV1.StorageUncertain
                Code.BUSY -> LocalEmployeeCreationDefinitionResultV1.Busy
                else -> mismatch()
            }
            else -> mismatch()
        }

    public fun decodeReview(original: LocalDelegatedEmployeeCreationV1, actor: DomainId,
        response: LocalMasterRpcEmployeeCreationResponseV1): LocalEmployeeCreationReviewV1 =
        when (val value = bound(actor, response, original)) {
            is LocalMasterRpcEmployeeCreationResultV1.Checked -> {
                binding(value.requestId == original.requestId && value.operatorId == original.operatorId &&
                    value.actorId == original.actorId && value.contentDigest == original.contentDigest)
                LocalEmployeeCreationReviewV1.InputsChecked(original.requestId, original.operatorId, original.actorId, original.contentDigest)
            }
            is LocalMasterRpcEmployeeCreationResultV1.Refused -> value.review()
            is LocalMasterRpcEmployeeCreationResultV1.Outcome -> when (value.code) {
                Code.DENIED -> LocalEmployeeCreationReviewV1.Denied
                Code.UNAVAILABLE -> LocalEmployeeCreationReviewV1.Unavailable
                Code.STORAGE_UNCERTAIN -> LocalEmployeeCreationReviewV1.StorageUncertain
                Code.BUSY -> LocalEmployeeCreationReviewV1.Busy
                else -> mismatch()
            }
            else -> mismatch()
        }

    public fun decodeLookup(original: LocalDelegatedEmployeeCreationV1, actor: DomainId,
        response: LocalMasterRpcEmployeeCreationResponseV1): LocalEmployeeCreationStatusV1 =
        when (val value = bound(actor, response, original)) {
            is LocalMasterRpcEmployeeCreationResultV1.Receipt -> when (value.kind) {
                ReceiptKind.APPLIED -> LocalEmployeeCreationStatusV1.Applied(value.receipt(original))
                ReceiptKind.CANCELLED -> LocalEmployeeCreationStatusV1.Cancelled(value.receipt(original))
                else -> mismatch()
            }
            is LocalMasterRpcEmployeeCreationResultV1.Outcome -> when (value.code) {
                Code.NOT_OBSERVED -> LocalEmployeeCreationStatusV1.NotObserved
                Code.IDENTITY_CONFLICT -> LocalEmployeeCreationStatusV1.IdentityConflict
                Code.DENIED -> LocalEmployeeCreationStatusV1.Denied
                Code.UNAVAILABLE -> LocalEmployeeCreationStatusV1.Unavailable
                Code.STORAGE_UNCERTAIN -> LocalEmployeeCreationStatusV1.StorageUncertain
                Code.BUSY -> LocalEmployeeCreationStatusV1.Busy
                else -> mismatch()
            }
            else -> mismatch()
        }

    public fun decodeExecution(original: LocalDelegatedEmployeeCreationV1, actor: DomainId,
        response: LocalMasterRpcEmployeeCreationResponseV1): LocalEmployeeCreationExecutionV1 =
        when (val value = bound(actor, response, original)) {
            is LocalMasterRpcEmployeeCreationResultV1.Receipt -> when (value.kind) {
                ReceiptKind.INSERTED -> LocalEmployeeCreationExecutionV1.Inserted(value.receipt(original))
                ReceiptKind.EXISTING_APPLIED -> LocalEmployeeCreationExecutionV1.Existing(
                    LocalEmployeeCreationStatusV1.Applied(value.receipt(original)))
                ReceiptKind.EXISTING_CANCELLED -> LocalEmployeeCreationExecutionV1.Existing(
                    LocalEmployeeCreationStatusV1.Cancelled(value.receipt(original)))
                else -> mismatch()
            }
            is LocalMasterRpcEmployeeCreationResultV1.Refused -> LocalEmployeeCreationExecutionV1.Refused(value.review())
            is LocalMasterRpcEmployeeCreationResultV1.Outcome -> when (value.code) {
                Code.EXISTING_IDENTITY_CONFLICT -> LocalEmployeeCreationExecutionV1.Existing(LocalEmployeeCreationStatusV1.IdentityConflict)
                Code.DENIED -> LocalEmployeeCreationExecutionV1.Denied
                Code.UNAVAILABLE -> LocalEmployeeCreationExecutionV1.Unavailable
                Code.STORAGE_UNCERTAIN -> LocalEmployeeCreationExecutionV1.StorageUncertain
                Code.BUSY -> LocalEmployeeCreationExecutionV1.Busy
                else -> mismatch()
            }
            else -> mismatch()
        }

    public fun decodeCancellation(original: LocalDelegatedEmployeeCreationV1, actor: DomainId,
        response: LocalMasterRpcEmployeeCreationResponseV1): LocalEmployeeCreationCancellationV1 =
        when (val value = bound(actor, response, original)) {
            is LocalMasterRpcEmployeeCreationResultV1.Receipt -> when (value.kind) {
                ReceiptKind.RECORDED -> LocalEmployeeCreationCancellationV1.Recorded(value.receipt(original))
                ReceiptKind.EXISTING_APPLIED -> LocalEmployeeCreationCancellationV1.Existing(
                    LocalEmployeeCreationStatusV1.Applied(value.receipt(original)))
                ReceiptKind.EXISTING_CANCELLED -> LocalEmployeeCreationCancellationV1.Existing(
                    LocalEmployeeCreationStatusV1.Cancelled(value.receipt(original)))
                else -> mismatch()
            }
            is LocalMasterRpcEmployeeCreationResultV1.Outcome -> when (value.code) {
                Code.EXISTING_IDENTITY_CONFLICT -> LocalEmployeeCreationCancellationV1.Existing(LocalEmployeeCreationStatusV1.IdentityConflict)
                Code.DENIED -> LocalEmployeeCreationCancellationV1.Denied
                Code.UNAVAILABLE -> LocalEmployeeCreationCancellationV1.Unavailable
                Code.STORAGE_UNCERTAIN -> LocalEmployeeCreationCancellationV1.StorageUncertain
                Code.BUSY -> LocalEmployeeCreationCancellationV1.Busy
                else -> mismatch()
            }
            else -> mismatch()
        }

    private fun bound(actor: DomainId, response: LocalMasterRpcEmployeeCreationResponseV1,
        original: LocalDelegatedEmployeeCreationV1? = null): LocalMasterRpcEmployeeCreationResultV1 {
        binding(response.requestingActorId == actor && (original == null || original.operatorId == actor))
        return response.result
    }

    private fun LocalMasterRpcEmployeeCreationResultV1.Receipt.receipt(original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationReceiptV1 {
        binding(scope == original.scope && requestId == original.requestId && operatorId == original.operatorId &&
            actorId == original.actorId && contentDigest == original.contentDigest)
        return LocalEmployeeCreationReceiptV1(scope, requestId, operatorId, actorId, contentDigest)
    }

    private fun LocalMasterRpcEmployeeCreationResultV1.Refused.review(): LocalEmployeeCreationReviewV1.Refused =
        try { LocalEmployeeCreationReviewV1.Refused(reason, delegationFailure) }
        catch (_: IllegalArgumentException) { mismatch() }

    private fun binding(matches: Boolean) { if (!matches) mismatch() }
    private fun mismatch(): Nothing = throw SerializationException("Master employee-creation response binding differs")

    // ---- Master side encoders. ----

    private fun encodeContext(value: LocalEmployeeCreationContextResultV1): LocalMasterRpcEmployeeCreationResultV1 = when (value) {
        is LocalEmployeeCreationContextResultV1.Available -> value.context.let { context ->
            LocalMasterRpcEmployeeCreationResultV1.Context(context.scope, context.operatorId, context.target.requestId,
                context.target.actorId, context.policyRevision, context.authenticationChoices.copyWireBytes(),
                context.copyAdmissions().map { admission ->
                    require(admission.scope == context.scope)
                    LocalMasterRpcEmployeeCreationAdmissionV1(admission.deviceId, admission.revision, admission.admitted,
                        admission.publicKeyIdentifier, admission.publicFingerprint, admission.capabilities.sorted())
                })
        }
        is LocalEmployeeCreationContextResultV1.Refused -> refused(value.review)
        LocalEmployeeCreationContextResultV1.Denied -> outcome(Code.DENIED)
        LocalEmployeeCreationContextResultV1.Unavailable -> outcome(Code.UNAVAILABLE)
        LocalEmployeeCreationContextResultV1.StorageUncertain -> outcome(Code.STORAGE_UNCERTAIN)
        LocalEmployeeCreationContextResultV1.Busy -> outcome(Code.BUSY)
    }

    private fun encodeReview(value: LocalEmployeeCreationReviewV1): LocalMasterRpcEmployeeCreationResultV1 = when (value) {
        is LocalEmployeeCreationReviewV1.InputsChecked ->
            LocalMasterRpcEmployeeCreationResultV1.Checked(value.requestId, value.operatorId, value.actorId, value.contentDigest)
        is LocalEmployeeCreationReviewV1.Refused -> refused(value)
        LocalEmployeeCreationReviewV1.Denied -> outcome(Code.DENIED)
        LocalEmployeeCreationReviewV1.Unavailable -> outcome(Code.UNAVAILABLE)
        LocalEmployeeCreationReviewV1.StorageUncertain -> outcome(Code.STORAGE_UNCERTAIN)
        LocalEmployeeCreationReviewV1.Busy -> outcome(Code.BUSY)
    }

    private fun encodeStatus(value: LocalEmployeeCreationStatusV1, existing: Boolean): LocalMasterRpcEmployeeCreationResultV1 = when (value) {
        is LocalEmployeeCreationStatusV1.Applied -> receipt(if (existing) ReceiptKind.EXISTING_APPLIED else ReceiptKind.APPLIED, value.receipt)
        is LocalEmployeeCreationStatusV1.Cancelled -> receipt(if (existing) ReceiptKind.EXISTING_CANCELLED else ReceiptKind.CANCELLED, value.receipt)
        LocalEmployeeCreationStatusV1.IdentityConflict -> outcome(if (existing) Code.EXISTING_IDENTITY_CONFLICT else Code.IDENTITY_CONFLICT)
        LocalEmployeeCreationStatusV1.NotObserved -> if (existing) outcome(Code.STORAGE_UNCERTAIN) else outcome(Code.NOT_OBSERVED)
        LocalEmployeeCreationStatusV1.Denied -> outcome(Code.DENIED)
        LocalEmployeeCreationStatusV1.Unavailable -> outcome(Code.UNAVAILABLE)
        LocalEmployeeCreationStatusV1.StorageUncertain -> outcome(Code.STORAGE_UNCERTAIN)
        LocalEmployeeCreationStatusV1.Busy -> outcome(Code.BUSY)
    }

    private fun receipt(kind: ReceiptKind, value: LocalEmployeeCreationReceiptV1) = LocalMasterRpcEmployeeCreationResultV1.Receipt(
        kind, value.scope, value.requestId, value.originalOperatorId, value.actorId, value.contentDigest)
    private fun refused(value: LocalEmployeeCreationReviewV1.Refused) =
        LocalMasterRpcEmployeeCreationResultV1.Refused(value.reason, value.delegationFailure)
    private fun outcome(code: Code) = LocalMasterRpcEmployeeCreationResultV1.Outcome(code)
}

private typealias Code = LocalMasterRpcEmployeeCreationResultV1.Code
private typealias ReceiptKind = LocalMasterRpcEmployeeCreationResultV1.ReceiptKind

/** Inert body DATA. Byte fields are canonical encodings; none is authority or a credential. */
@Serializable
public sealed interface LocalMasterRpcEmployeeCreationCallV1 {
    @Serializable @SerialName("context")
    public data class Context(public val requestId: DomainId, public val actorId: DomainId) : LocalMasterRpcEmployeeCreationCallV1

    @Serializable @SerialName("capture")
    public class Capture(
        public val requestId: DomainId,
        public val scope: BranchScope,
        public val actorId: DomainId,
        public val credentialWasProvided: Boolean,
        @Serializable(with = CreationBytes::class) private val policy: ByteArray,
        @Serializable(with = CreationBytes::class) private val configuration: ByteArray,
        public val admittedDeviceIds: List<DomainId>,
    ) : LocalMasterRpcEmployeeCreationCallV1 {
        /** Strict canonical decode of the requested definition; any mismatch refuses. */
        public fun draft(): LocalEmployeeCreationDraftV1 {
            require(admittedDeviceIds.size in 1..256 && admittedDeviceIds.distinct().size == admittedDeviceIds.size)
            val decodedPolicy: ActivatedLocalPolicy = LocalEffectivePolicyCodec.decodePolicy(
                ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(scope, actorId), Revision(0)), policy.copyOf())
            val decodedConfiguration: LocalAuthConfiguration = LocalAuthenticationCodec.decodeConfiguration(
                ProtectedLocalBinding(ProtectedLocalRecordKey.AuthConfiguration(scope, actorId), Revision(0)), configuration.copyOf())
            require(decodedPolicy.scope == scope && decodedPolicy.actorId == actorId &&
                decodedConfiguration.scope == scope && decodedConfiguration.actorId == actorId)
            return LocalEmployeeCreationDraftV1(requestId, LocalAccountProvisioning(actorId, decodedConfiguration.login,
                decodedPolicy, decodedConfiguration.tokens, admittedDeviceIds.toSet()), credentialWasProvided)
        }
        override fun toString(): String = "LocalMasterRpcEmployeeCreationCallV1.Capture([REDACTED])"

        internal companion object {
            fun of(draft: LocalEmployeeCreationDraftV1): Capture {
                val definition = draft.copyDefinition()
                val policy = LocalEffectivePolicyCodec.encodePolicy(definition.policy)
                val configuration = LocalAuthenticationCodec.encodeConfiguration(LocalAuthConfiguration(draft.scope,
                    definition.actorId, Revision(0), definition.login, definition.tokenPolicy))
                return Capture(draft.requestId, draft.scope, definition.actorId, draft.credentialWasProvided, policy,
                    configuration, definition.admittedDeviceIds.sortedBy { it.value })
            }
        }
    }

    @Serializable @SerialName("review")
    public class Review(@Serializable(with = CreationBytes::class) internal val bytes: ByteArray) : LocalMasterRpcEmployeeCreationCallV1
    @Serializable @SerialName("lookup")
    public class Lookup(@Serializable(with = CreationBytes::class) internal val bytes: ByteArray) : LocalMasterRpcEmployeeCreationCallV1
    @Serializable @SerialName("execute")
    public class Execute(@Serializable(with = CreationBytes::class) internal val bytes: ByteArray) : LocalMasterRpcEmployeeCreationCallV1
    @Serializable @SerialName("cancel")
    public class Cancel(@Serializable(with = CreationBytes::class) internal val bytes: ByteArray) : LocalMasterRpcEmployeeCreationCallV1

    /** The exact canonical original for original-bound calls; null for context/capture. */
    public fun original(): LocalDelegatedEmployeeCreationV1? = when (this) {
        is Review -> LocalDelegatedEmployeeCreationV1.decode(bytes)
        is Lookup -> LocalDelegatedEmployeeCreationV1.decode(bytes)
        is Execute -> LocalDelegatedEmployeeCreationV1.decode(bytes)
        is Cancel -> LocalDelegatedEmployeeCreationV1.decode(bytes)
        else -> null
    }
}

/** Owns an independently copied bearer and, for Execute only, a bounded password copy. */
@Serializable(with = LocalMasterRpcEmployeeCreationRequestSerializerV1::class)
public class LocalMasterRpcEmployeeCreationRequestV1 private constructor(
    access: ByteArray,
    public val call: LocalMasterRpcEmployeeCreationCallV1,
    password: CharArray?,
) : LocalMasterRpcOwnedRequestV1(access) {
    private val secret: CharArray? = password?.copyOf()
    init {
        require((call is LocalMasterRpcEmployeeCreationCallV1.Execute) || secret == null)
        require(secret == null || secret.size in 4..1024)
        if (call is LocalMasterRpcEmployeeCreationCallV1.Execute) {
            val provided = checkNotNull(call.original()).copyRequestedDefinition().credentialWasProvided
            require(provided == (secret != null))
        }
    }

    /** A disposable synchronous loan; the request retains ownership and wipes on destroy. */
    public fun <T> usePassword(block: (CharArray?) -> T): T = useBearer { block(secret) }

    override fun destroyOwnedSecrets() { secret?.fill('\u0000') }

    public companion object {
        public fun context(credentials: LocalGatewayCredentials, target: LocalEmployeeCreationTargetV1): LocalMasterRpcEmployeeCreationRequestV1 =
            of(credentials, LocalMasterRpcEmployeeCreationCallV1.Context(target.requestId, target.actorId))
        public fun capture(credentials: LocalGatewayCredentials, draft: LocalEmployeeCreationDraftV1): LocalMasterRpcEmployeeCreationRequestV1 =
            of(credentials, LocalMasterRpcEmployeeCreationCallV1.Capture.of(draft))
        public fun review(credentials: LocalGatewayCredentials, original: LocalDelegatedEmployeeCreationV1): LocalMasterRpcEmployeeCreationRequestV1 =
            of(credentials, LocalMasterRpcEmployeeCreationCallV1.Review(original.copyWireBytes()))
        public fun lookup(credentials: LocalGatewayCredentials, original: LocalDelegatedEmployeeCreationV1): LocalMasterRpcEmployeeCreationRequestV1 =
            of(credentials, LocalMasterRpcEmployeeCreationCallV1.Lookup(original.copyWireBytes()))
        public fun cancel(credentials: LocalGatewayCredentials, original: LocalDelegatedEmployeeCreationV1): LocalMasterRpcEmployeeCreationRequestV1 =
            of(credentials, LocalMasterRpcEmployeeCreationCallV1.Cancel(original.copyWireBytes()))
        /** Copies caller-owned [password]; the caller keeps and clears its own array. */
        public fun execute(credentials: LocalGatewayCredentials, original: LocalDelegatedEmployeeCreationV1,
            password: CharArray?): LocalMasterRpcEmployeeCreationRequestV1 =
            of(credentials, LocalMasterRpcEmployeeCreationCallV1.Execute(original.copyWireBytes()), password)

        private fun of(credentials: LocalGatewayCredentials, call: LocalMasterRpcEmployeeCreationCallV1,
            password: CharArray? = null): LocalMasterRpcEmployeeCreationRequestV1 =
            credentials.bearer.useBytes { LocalMasterRpcEmployeeCreationRequestV1(it, call, password) }

        internal fun decoded(access: ByteArray, call: LocalMasterRpcEmployeeCreationCallV1, password: CharArray?) =
            LocalMasterRpcEmployeeCreationRequestV1(access, call, password)
    }
}

public object LocalMasterRpcEmployeeCreationRequestSerializerV1 : KSerializer<LocalMasterRpcEmployeeCreationRequestV1> {
    private val callSerializer = LocalMasterRpcEmployeeCreationCallV1.serializer()
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.ukkera.pos.database.LocalMasterRpcEmployeeCreationRequestV1") {
        element("access", LocalMasterRpcBearerSerializerV1.descriptor)
        element("call", callSerializer.descriptor)
        element("password", CharArraySerializer().descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: LocalMasterRpcEmployeeCreationRequestV1) {
        try {
            value.useBearer { access ->
                value.usePassword { password ->
                    encoder.encodeStructure(descriptor) {
                        encodeSerializableElement(descriptor, 0, LocalMasterRpcBearerSerializerV1, access)
                        encodeSerializableElement(descriptor, 1, callSerializer, value.call)
                        if (password != null) encodeSerializableElement(descriptor, 2, CharArraySerializer(), password)
                    }
                }
            }
        } finally { value.destroy() }
    }

    override fun deserialize(decoder: Decoder): LocalMasterRpcEmployeeCreationRequestV1 {
        var access: ByteArray? = null
        var call: LocalMasterRpcEmployeeCreationCallV1? = null
        var password: CharArray? = null
        var seen = 0
        try {
            decoder.decodeStructure(descriptor) {
                while (true) {
                    val index = decodeElementIndex(descriptor)
                    if (index == CompositeDecoder.DECODE_DONE) break
                    if (index !in 0..2 || seen and (1 shl index) != 0) throw SerializationException("Unexpected creation request field")
                    seen = seen or (1 shl index)
                    when (index) {
                        0 -> access = decodeSerializableElement(descriptor, index, LocalMasterRpcBearerSerializerV1)
                        1 -> call = decodeSerializableElement(descriptor, index, callSerializer)
                        2 -> password = decodeSerializableElement(descriptor, index, CharArraySerializer())
                    }
                }
            }
            if (seen and 3 != 3) throw SerializationException("Missing creation request field")
            return LocalMasterRpcEmployeeCreationRequestV1.decoded(checkNotNull(access), checkNotNull(call), password)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Decoder exceptions can include input text; never attach their cause or payload.
            throw SerializationException("Malformed employee-creation request")
        } finally { access?.fill(0); password?.fill('\u0000') }
    }
}

@Serializable
public data class LocalMasterRpcEmployeeCreationResponseV1(
    public val requestingActorId: DomainId,
    public val result: LocalMasterRpcEmployeeCreationResultV1,
) {
    override fun toString(): String = "LocalMasterRpcEmployeeCreationResponseV1(data omitted)"
}

@Serializable
public data class LocalMasterRpcEmployeeCreationAdmissionV1(
    public val deviceId: DomainId,
    public val revision: Revision,
    public val admitted: Boolean,
    public val publicKeyIdentifier: String?,
    public val publicFingerprint: String?,
    public val capabilities: List<String>,
)

@Serializable
public sealed interface LocalMasterRpcEmployeeCreationResultV1 {
    public enum class Code { DENIED, UNAVAILABLE, STORAGE_UNCERTAIN, BUSY, NOT_OBSERVED, IDENTITY_CONFLICT, EXISTING_IDENTITY_CONFLICT }
    public enum class ReceiptKind { INSERTED, RECORDED, APPLIED, CANCELLED, EXISTING_APPLIED, EXISTING_CANCELLED }

    @Serializable @SerialName("outcome")
    public data class Outcome(public val code: Code) : LocalMasterRpcEmployeeCreationResultV1

    @Serializable @SerialName("refused")
    public data class Refused(public val reason: LocalEmployeeCreationInputFailureV1,
        public val delegationFailure: LocalEmployeeDelegationFailureV1? = null) : LocalMasterRpcEmployeeCreationResultV1

    @Serializable @SerialName("context")
    public class Context(
        public val scope: BranchScope,
        public val operatorId: DomainId,
        public val requestId: DomainId,
        public val actorId: DomainId,
        public val policyRevision: Revision,
        @Serializable(with = CreationBytes::class) private val configuration: ByteArray,
        public val admissions: List<LocalMasterRpcEmployeeCreationAdmissionV1>,
    ) : LocalMasterRpcEmployeeCreationResultV1 {
        internal fun context(): LocalEmployeeCreationContextV1 = LocalEmployeeCreationContextV1(scope, operatorId,
            LocalEmployeeCreationTargetV1(requestId, actorId), policyRevision,
            LocalEmployeeCreationConfigurationV1.decode(configuration.copyOf()), admissions.map {
                LocalDeviceAdmission(scope, it.deviceId, it.revision, it.admitted, it.publicKeyIdentifier,
                    it.publicFingerprint, it.capabilities.toSet())
            })
        override fun toString(): String = "LocalMasterRpcEmployeeCreationResultV1.Context([REDACTED])"
    }

    @Serializable @SerialName("captured")
    public class Captured(@Serializable(with = CreationBytes::class) internal val original: ByteArray) : LocalMasterRpcEmployeeCreationResultV1

    @Serializable @SerialName("checked")
    public data class Checked(public val requestId: DomainId, public val operatorId: DomainId, public val actorId: DomainId,
        public val contentDigest: PayloadDigest) : LocalMasterRpcEmployeeCreationResultV1

    @Serializable @SerialName("receipt")
    public data class Receipt(public val kind: ReceiptKind, public val scope: BranchScope, public val requestId: DomainId,
        public val operatorId: DomainId, public val actorId: DomainId, public val contentDigest: PayloadDigest) :
        LocalMasterRpcEmployeeCreationResultV1
}

/** Bounded canonical bytes; decoding stages into owned memory and never echoes input. */
private object CreationBytes : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = ByteArraySerializer().descriptor
    override fun serialize(encoder: Encoder, value: ByteArray) {
        if (value.size !in 1..LocalMasterEmployeeCreationRpcProtocolV1.MAXIMUM_WIRE_BYTES)
            throw SerializationException("Creation byte field exceeds profile")
        ByteArraySerializer().serialize(encoder, value)
    }
    override fun deserialize(decoder: Decoder): ByteArray =
        decodeBoundedRpcBytes(decoder, descriptor, 1, LocalMasterEmployeeCreationRpcProtocolV1.MAXIMUM_WIRE_BYTES)
}
