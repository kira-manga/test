package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Private requester access only. Native-head approval is a separate, owner-bound ceremony. */
public enum class NativeSerialPrivatePublicationOperationV1(internal val wireTag: Int) {
    ORIGIN_PREPARE(1), PREPARE(2), REVIEW(3), RECOVER(4), STATUS(5),
}

/**
 * Closed request DATA, never a requester, native membership or approval capability. The
 * original contains the exact proposal so restart cannot generate replacement authority IDs.
 * Actor and session come from the actual retained protected-final declarations, not this type.
 */
public class NativeSerialPrivatePublicationRequestV1 internal constructor(
    public val operation: NativeSerialPrivatePublicationOperationV1,
    public val requestId: DomainId,
    public val scope: BranchScope,
    public val originalAllocationDigest: PayloadDigest,
    public val kind: CompanyBusinessPublicationKind,
    public val publicationId: DomainId,
    public val entityId: DomainId,
    public val contentDigest: PayloadDigest,
    public val originalActionId: DomainId?,
    public val originRequestId: DomainId?,
    public val originRequestDigest: PayloadDigest?,
    canonicalContent: ByteArray?,
) {
    private val content = canonicalContent?.copyOf()

    init {
        try {
            require(kind.isOriginalSerialKindV1() && publicationId != entityId)
            val originId = NativeSerialPrivatePublicationRequestCodecV1.originRequestId(scope,
                originalAllocationDigest, kind, if (kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1) null else publicationId)
            when (operation) {
                NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE -> {
                    require(requestId == originId && originalActionId == null && originRequestId == null &&
                        originRequestDigest == null && content != null)
                    val proposal = originalProposal()
                    try { requireSerialPrivateProposal(proposal, scope, originalAllocationDigest) }
                    finally { proposal.discardUndeliveredNativeContent() }
                }
                NativeSerialPrivatePublicationOperationV1.PREPARE,
                NativeSerialPrivatePublicationOperationV1.STATUS -> require(content == null && originalActionId == null &&
                    originRequestId == originId && originRequestDigest != null && requestId != originId)
                NativeSerialPrivatePublicationOperationV1.REVIEW,
                NativeSerialPrivatePublicationOperationV1.RECOVER -> require(content == null && originalActionId != null &&
                    originRequestId == originId && originRequestDigest != null && requestId != originId)
            }
        } catch (failure: Throwable) { content?.fill(0); throw failure }
    }

    /** Exact proposal DATA only; native must independently authenticate the original request. */
    public fun originalProposal(): CompanyBusinessPublicationProposal {
        require(operation == NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE)
        val bytes = requireNotNull(content)
        val proposal = CompanyBusinessPublicationProposal(scope.company(), kind, publicationId, entityId, bytes)
        return try { require(proposal.contentDigest == contentDigest); proposal }
        catch (failure: Throwable) { proposal.discardUndeliveredNativeContent(); throw failure }
    }

    internal fun copyOriginalContent(): ByteArray = requireNotNull(content).copyOf()
    /** Called only on a native-owned decoded copy, never on a supplied request object. */
    internal fun clearOriginalContent() { content?.fill(0) }
    internal fun sameTarget(other: NativeSerialPrivatePublicationRequestV1): Boolean =
        scope == other.scope && originalAllocationDigest == other.originalAllocationDigest && kind == other.kind &&
            publicationId == other.publicationId && entityId == other.entityId && contentDigest == other.contentDigest

    override fun toString(): String = "NativeSerialPrivatePublicationRequestV1([REDACTED])"
}

/** One native-owned canonical request grammar. Dependency/fact encodings remain protected-writer DATA. */
public object NativeSerialPrivatePublicationRequestCodecV1 {
    public const val PURPOSE_TAG: Int = 19
    public const val PROFILE: String = "ukkera.serial-private-publication-request/v1"
    public const val MAX_REQUEST_BYTES: Int = CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES + 4 * 1024
    private const val DOMAIN: String = "native-serial-private-publication-request/v1"
    private const val ORIGIN_ID_DOMAIN: String = "native-serial-private-publication-origin-id/v1"

    /**
     * Stable slot DATA, not authority. Authority omits its generated IDs; a registration is
     * publication-bound, never digest-bound. Hash collisions/duplicate retained IDs refuse.
     */
    public fun originRequestId(scope: BranchScope, originalAllocationDigest: PayloadDigest,
        kind: CompanyBusinessPublicationKind, publicationId: DomainId?): DomainId {
        require(kind.isOriginalSerialKindV1())
        require((kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1) == (publicationId == null))
        val bytes = selfOwnedEncode(ORIGIN_ID_DOMAIN, 1024) {
            string(PROFILE); int(1)
            id(scope.organizationId); id(scope.companyId); id(scope.branchId)
            string(originalAllocationDigest.value); int(serialPrivateKindTag(kind))
            publicationId?.let { id(it) }
        }
        val hex = try { sha256(bytes).value.take(32).toCharArray() } finally { bytes.fill(0) }
        // UUIDv8 with the RFC variant; this is a domain-separated identifier, not a token.
        hex[12] = '8'; hex[16] = "89ab"[hex[16].digitToInt(16) and 3]
        val value = hex.concatToString()
        return DomainId("${value.substring(0, 8)}-${value.substring(8, 12)}-${value.substring(12, 16)}-" +
            "${value.substring(16, 20)}-${value.substring(20)}")
    }

    public fun origin(scope: BranchScope, originalAllocationDigest: PayloadDigest,
        proposal: CompanyBusinessPublicationProposal): NativeSerialPrivatePublicationRequestV1 {
        requireSerialPrivateProposal(proposal, scope, originalAllocationDigest)
        val content = proposal.copyCanonicalContent()
        return try { NativeSerialPrivatePublicationRequestV1(NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE,
            originRequestId(scope, originalAllocationDigest, proposal.kind,
                if (proposal.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1) null else proposal.publicationId),
            scope, originalAllocationDigest, proposal.kind, proposal.publicationId, proposal.entityId,
            proposal.contentDigest, null, null, null, content) }
        finally { content.fill(0) }
    }

    public fun access(operation: NativeSerialPrivatePublicationOperationV1, requestId: DomainId,
        origin: NativeSerialPrivatePublicationRequestV1, originalActionId: DomainId?): NativeSerialPrivatePublicationRequestV1 {
        require(origin.operation == NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE &&
            operation != NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE)
        return NativeSerialPrivatePublicationRequestV1(operation, requestId, origin.scope, origin.originalAllocationDigest,
            origin.kind, origin.publicationId, origin.entityId, origin.contentDigest, originalActionId,
            origin.requestId, digest(origin), null)
    }

    public fun encode(value: NativeSerialPrivatePublicationRequestV1): ByteArray =
        selfOwnedEncode(DOMAIN, MAX_REQUEST_BYTES) {
            string(PROFILE); int(1); int(value.operation.wireTag); id(value.requestId)
            id(value.scope.organizationId); id(value.scope.companyId); id(value.scope.branchId)
            string(value.originalAllocationDigest.value); int(serialPrivateKindTag(value.kind))
            id(value.publicationId); id(value.entityId); string(value.contentDigest.value)
            when (value.operation) {
                NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE -> {
                    val content = value.copyOriginalContent()
                    try { field(content) } finally { content.fill(0) }
                }
                NativeSerialPrivatePublicationOperationV1.PREPARE,
                NativeSerialPrivatePublicationOperationV1.STATUS -> {
                    id(requireNotNull(value.originRequestId)); string(requireNotNull(value.originRequestDigest).value)
                }
                NativeSerialPrivatePublicationOperationV1.REVIEW,
                NativeSerialPrivatePublicationOperationV1.RECOVER -> {
                    id(requireNotNull(value.originRequestId)); string(requireNotNull(value.originRequestDigest).value)
                    id(requireNotNull(value.originalActionId))
                }
            }
        }

    public fun decode(bytes: ByteArray): NativeSerialPrivatePublicationRequestV1 {
        require(bytes.size in 1..MAX_REQUEST_BYTES)
        var decoded: NativeSerialPrivatePublicationRequestV1? = null
        return try { selfOwnedDecode(bytes, DOMAIN) {
            require(string(64) == PROFILE && int() == 1)
            val tag = int()
            val operation = NativeSerialPrivatePublicationOperationV1.entries.singleOrNull { it.wireTag == tag }
                ?: throw IllegalArgumentException("Unknown private serial publication operation")
            val requestId = id(); val scope = BranchScope(id(), id(), id())
            val allocation = PayloadDigest(string(64)); val kind = serialPrivateKind(int())
            val publication = id(); val entity = id(); val digest = PayloadDigest(string(64))
            var action: DomainId? = null; var originId: DomainId? = null; var originDigest: PayloadDigest? = null
            val content = when (operation) {
                NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE -> field(CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES)
                NativeSerialPrivatePublicationOperationV1.PREPARE,
                NativeSerialPrivatePublicationOperationV1.STATUS -> {
                    originId = id(); originDigest = PayloadDigest(string(64)); null
                }
                NativeSerialPrivatePublicationOperationV1.REVIEW,
                NativeSerialPrivatePublicationOperationV1.RECOVER -> {
                    originId = id(); originDigest = PayloadDigest(string(64)); action = id(); null
                }
            }
            try { NativeSerialPrivatePublicationRequestV1(operation, requestId, scope, allocation, kind,
                publication, entity, digest, action, originId, originDigest, content).also { decoded = it } }
            finally { content?.fill(0) }
        } } catch (failure: Throwable) { decoded?.clearOriginalContent(); throw failure }
    }

    public fun digest(value: NativeSerialPrivatePublicationRequestV1): PayloadDigest {
        val bytes = encode(value)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
}

/** Bounded copies of untrusted DATA. Possession supplies no protected authorization or native fate. */
public class NativeSerialPrivateRequestCapsuleV1(requestBytes: ByteArray, dependencyBytes: ByteArray, factBytes: ByteArray) {
    init {
        require(requestBytes.size in 1..NativeSerialPrivatePublicationRequestCodecV1.MAX_REQUEST_BYTES &&
            dependencyBytes.isNotEmpty() && factBytes.isNotEmpty() &&
            requestBytes.size.toLong() + dependencyBytes.size.toLong() + factBytes.size.toLong() <=
            SelfOwnedNativeHistoryWireV2.MAX_CAPSULE_BYTES)
    }
    private val request = requestBytes.copyOf()
    private val dependencies = dependencyBytes.copyOf()
    private val facts = factBytes.copyOf()
    public fun copyRequestBytes(): ByteArray = request.copyOf()
    public fun copyDependencyBytes(): ByteArray = dependencies.copyOf()
    public fun copyFactBytes(): ByteArray = facts.copyOf()

    /** Invoked only inside the actual native task's admission. */
    internal fun copyNativeInputs(): NativeSerialPrivateRequestInputsV1 {
        val requestCopy = request.copyOf()
        var dependencyCopy: ByteArray? = null
        return try {
            val copiedDependencies = dependencies.copyOf(); dependencyCopy = copiedDependencies
            NativeSerialPrivateRequestInputsV1(requestCopy, copiedDependencies, facts.copyOf())
        } catch (failure: Throwable) { requestCopy.fill(0); dependencyCopy?.fill(0); throw failure }
    }
    override fun toString(): String = "NativeSerialPrivateRequestCapsuleV1([REDACTED])"
}

internal class NativeSerialPrivateRequestInputsV1(val requestBytes: ByteArray, val dependencyBytes: ByteArray, val factBytes: ByteArray) {
    fun intent(request: NativeSerialPrivatePublicationRequestV1, actorId: DomainId): NativeHistoryOriginalIntentV2 =
        NativeHistoryOriginalIntentV2(NativeSerialPrivatePublicationRequestCodecV1.PURPOSE_TAG,
            request.requestId, sha256(requestBytes), null, actorId, sha256(dependencyBytes), sha256(factBytes), emptyList())
    fun clear() { requestBytes.fill(0); dependencyBytes.fill(0); factBytes.fill(0) }
}

private fun serialPrivateKindTag(kind: CompanyBusinessPublicationKind): Int = when (kind) {
    CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 -> 1
    CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> 2
    else -> throw IllegalArgumentException("Not a private serial publication")
}
private fun serialPrivateKind(tag: Int): CompanyBusinessPublicationKind = when (tag) {
    1 -> CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1
    2 -> CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1
    else -> throw IllegalArgumentException("Unknown private serial publication kind")
}
private fun requireSerialPrivateProposal(proposal: CompanyBusinessPublicationProposal,
    scope: BranchScope, allocation: PayloadDigest) {
    require(proposal.company == scope.company())
    when (proposal.kind) {
        CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 -> proposal.readSerialAuthorityDefinition().let {
            require(it.origin.originalScope == scope && it.origin.originalAllocationDigest == allocation)
        }
        CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> proposal.readSerialRegistrationDefinition().let {
            require(it.scope == scope && it.allocationDigest == allocation)
        }
        else -> throw IllegalArgumentException("Not a private serial publication")
    }
}

/** Only an actual security-module native owner implements this optional composition seam. */
internal interface SelfOwnedSerialPrivatePublicationController {
    suspend fun planOriginalSerialAuthority(): LocalCompanyAuthorityResult<CompanyBusinessPublicationProposal>
    suspend fun prepareSerialPrivateRoomChild(frame: SelfOwnedNativeRoomFrame, child: NativeHistoryRecordedHeadV2,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1,
        declaration: NativeHistoryProtectedFinalDeclaration)
    suspend fun prepareOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>
    suspend fun reviewOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>
    suspend fun recoverOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput>
    suspend fun statusOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationReference?>
}

/** No-effect exact-ID planning only. An existing protected origin must be reread, never replanned. */
public suspend fun SelfOwnedNativeHistoryCustody.planOriginalSerialAuthority(): LocalCompanyAuthorityResult<CompanyBusinessPublicationProposal> =
    (controller as? SelfOwnedSerialPrivatePublicationController)?.planOriginalSerialAuthority()
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

public suspend fun SelfOwnedNativeHistoryCustody.prepareSerialPrivateRoomChild(frame: SelfOwnedNativeRoomFrame,
    child: NativeHistoryRecordedHeadV2, access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1,
    declaration: NativeHistoryProtectedFinalDeclaration): Unit =
    (controller as? SelfOwnedSerialPrivatePublicationController)?.prepareSerialPrivateRoomChild(frame, child, access, origin, declaration)
        ?: throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)

public suspend fun SelfOwnedNativeHistoryCustody.prepareOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
    access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
    LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
    (controller as? SelfOwnedSerialPrivatePublicationController)?.prepareOriginalPrivateSerialPublication(settled, access, origin)
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

public suspend fun SelfOwnedNativeHistoryCustody.reviewOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
    access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
    LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
    (controller as? SelfOwnedSerialPrivatePublicationController)?.reviewOriginalPrivateSerialPublication(settled, access, origin)
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

public suspend fun SelfOwnedNativeHistoryCustody.recoverOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
    access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
    LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> =
    (controller as? SelfOwnedSerialPrivatePublicationController)?.recoverOriginalPrivateSerialPublication(settled, access, origin)
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

/** Exact native bookkeeping only. Null is authenticated target absence, never permission to prepare. */
public suspend fun SelfOwnedNativeHistoryCustody.statusOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
    access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
    LocalCompanyAuthorityResult<CompanyBusinessPublicationReference?> =
    (controller as? SelfOwnedSerialPrivatePublicationController)?.statusOriginalPrivateSerialPublication(settled, access, origin)
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

/** Dispose an abandoned private response; this cannot prepare, sign or change a retained native action. */
public fun CompanyBusinessPublicationAction.discardUndeliveredSerialContent() {
    require(proposal.kind.isOriginalSerialKindV1())
    proposal.discardUndeliveredNativeContent()
}

/** Dispose an abandoned private response; this cannot erase its original retained native result. */
public fun CompanyBusinessPublicationOutput.discardUndeliveredSerialContent() {
    require(proposal.kind.isOriginalSerialKindV1())
    proposal.discardUndeliveredNativeContent()
}
