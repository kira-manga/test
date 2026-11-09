package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Inert syntax and substituted identity/policy inputs for v2 L1 observations.
 * Nothing here opens Room, enrolls an employee, creates native originals or
 * supplies a retained native final decision. The frozen content is carried in
 * a printable serial-code field only for canonical comparison, not issuance.
 */
internal object LocalSerialPrivateEvidenceInputs {
    val scope = BranchScope(id(1), id(2), id(3))
    val actorA = id(4)
    val actorB = id(5)
    val publication = id(6)
    val entity = id(7)
    val action = id(8)
    val otherPublication = id(9)
    val otherEntity = id(10)
    val allocationDigest = PayloadDigest("1".repeat(64))
    const val contentA = "Synthetic local registration; branch=B1; location=East; currency=EGP; marker=LSPR-v1-A."
    const val contentB = "Synthetic local registration; branch=B1; location=West; currency=EGP; marker=LSPR-v1-B."

    fun definition(): LocalPublishedSerialRegistrationV1 = LocalPublishedSerialRegistrationV1(
        scope = scope, allocationId = id(11), allocationDigest = allocationDigest,
        serialAuthority = LocalSerialAuthorityReferenceV1(id(12), id(13),
            PayloadDigest("2".repeat(64)), PayloadDigest("3".repeat(64))),
        publicationId = publication, registrationId = entity, revision = Revision(1), previous = null,
        serialId = id(14), serialCode = contentA, skuId = id(15), skuPublicationId = id(16),
        skuPublicationDigest = PayloadDigest("4".repeat(64)),
        validFrom = UtcInstant.parseCanonical("2031-01-01T00:00:00Z"), validUntil = null,
    )

    fun origin(value: LocalPublishedSerialRegistrationV1 = definition()): NativeSerialPrivatePublicationRequestV1 =
        NativeSerialPrivatePublicationRequestCodecV1.origin(value.scope, value.allocationDigest,
            makeNativeSerialRegistrationProposal(value))

    fun access(origin: NativeSerialPrivatePublicationRequestV1,
        operation: NativeSerialPrivatePublicationOperationV1,
        requestId: DomainId = id(20)): NativeSerialPrivatePublicationRequestV1 =
        NativeSerialPrivatePublicationRequestCodecV1.access(operation, requestId, origin,
            if (operation == NativeSerialPrivatePublicationOperationV1.REVIEW ||
                operation == NativeSerialPrivatePublicationOperationV1.RECOVER) action else null)

    suspend fun principal(actor: DomainId = actorA, branch: BranchScope = scope,
        session: DomainId = id(21), device: DomainId = id(22)): LocalEmployeePrincipal {
        val identity = VerifiedLocalIdentity(actor, branch, device, session, SessionGeneration(1),
            Revision(1), AuthenticationPurpose.INTERACTIVE)
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity = identity
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val bearer = OpaqueCredential(ByteArray(32) { 1 })
        return try { requireNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(LocalGatewayCredentials(id(23), bearer))) }
        finally { bearer.destroy() }
    }

    fun snapshot(principal: LocalEmployeePrincipal, grants: List<PermissionGrant> = emptyList(),
        enabled: Boolean = true, active: Boolean = true, admitted: Boolean = true): AuthorizationSnapshot =
        AuthorizationSnapshot(principal.actorId, principal.kind, principal.scope, principal.sessionId,
            principal.generation, principal.deviceId, principal.admissionRevision, Revision(2),
            enabled, active, admitted, principal.authenticationPurpose, grants)

    fun catalogGrant(): PermissionGrant = PermissionGrant(
        Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), scope, GrantEffect.ALLOW,
        targetIds = setOf(entity))

    fun recoveryGrant(origin: NativeSerialPrivatePublicationRequestV1,
        effect: GrantEffect = GrantEffect.DENY): PermissionGrant = PermissionGrant(
        Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM), scope, effect,
        targetIds = setOf(origin.requestId))

    fun authorization(actor: DomainId = actorA, principal: LocalEmployeePrincipal? = null): RuntimeCommandAuthorizationSnapshotV1 =
        RuntimeCommandAuthorizationSnapshotV1(scope, actor, principal?.sessionId ?: id(21),
            principal?.deviceId ?: id(22), principal?.generation ?: SessionGeneration(1),
            PrincipalKind.LOCAL_EMPLOYEE, AuthenticationPurpose.INTERACTIVE,
            Revision(1), Revision(1), Revision(1), Revision(2),
            RuntimeCommandAccessWindowV1(id(24), MonotonicMoment(id(25), 1_000),
                MonotonicMoment(id(25), 2_000), 20_000, Revision(1), Revision(1)),
            RuntimeCommandAuthorizationDependenciesV1(PayloadDigest("5".repeat(64)),
                PayloadDigest("6".repeat(64)), PayloadDigest("7".repeat(64)), PayloadDigest("8".repeat(64))))

    fun event(request: NativeSerialPrivatePublicationRequestV1,
        authorization: RuntimeCommandAuthorizationSnapshotV1): LocalAuthEvent = LocalAuthEvent(
        request.scope, request.requestId, LocalAuthEventKind.SERIAL_PUBLICATION_REQUEST,
        request.publicationId, authorization.actorId, authorization.sessionId,
        authorization.accountRevision, authorization.access.observedAt,
        NativeSerialPrivatePublicationRequestCodecV1.digest(request))

    fun evidence(request: NativeSerialPrivatePublicationRequestV1,
        authorization: RuntimeCommandAuthorizationSnapshotV1 = authorization()): RuntimeSerialPublicationRequestEvidenceV1 {
        val eventBytes = LocalAuthenticationCodec.encodeEvent(event(request, authorization))
        val eventDigest = try { sha256(eventBytes) } finally { eventBytes.fill(0) }
        return RuntimeSerialPublicationRequestEvidenceV1(request = requestText(request),
            requirement = RuntimeCommandRequirementV1.capture(expectedRequirement(request, authorization.actorId)),
            authorization = authorization, at = authorization.access.observedAt,
            images = listOf(RuntimeApprovalImageV1(RuntimeHistoryTableV2.AUTH_EVENT.tag, request.requestId,
                RuntimeHistoryMutationKindV2.INSERT.tag, Revision(0), eventDigest)))
    }

    /** Wire binding scaffolding, not a permission oracle or authority capability. */
    private fun expectedRequirement(request: NativeSerialPrivatePublicationRequestV1,
        originalActor: DomainId): AuthorizationRequirement {
        val prepare = request.operation == NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE ||
            request.operation == NativeSerialPrivatePublicationOperationV1.PREPARE
        if (prepare) return AuthorizationRequirement(
            Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), request.scope,
            ActionBinding(request.entityId, Revision(0), NativeSerialPrivatePublicationRequestCodecV1.digest(request),
                OperationId(request.requestId.value), "local-serial-publication.prepare"))
        val originId = requireNotNull(request.originRequestId)
        val branch = request.scope
        val query = listOf("result-recovery-v1", branch.organizationId.value, branch.companyId.value,
            branch.branchId.value, originId.value, originalActor.value).joinToString("\n").encodeToByteArray()
        val digest = try { sha256(query) } finally { query.fill(0) }
        return AuthorizationRequirement(Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM),
            branch, ActionBinding(originId, Revision(0), digest, OperationId(originId.value), "supervisor.result-recovery"))
    }

    fun requestText(value: NativeSerialPrivatePublicationRequestV1): String {
        val bytes = NativeSerialPrivatePublicationRequestCodecV1.encode(value)
        return try { bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') } }
        finally { bytes.fill(0) }
    }

    /** Mutate one equal-length encoded scalar, leaving the original lookup/digest fixed. */
    fun mutateScalar(value: NativeSerialPrivatePublicationRequestV1, before: String,
        after: String): NativeSerialPrivatePublicationRequestV1 {
        val bytes = NativeSerialPrivatePublicationRequestCodecV1.encode(value)
        val needle = before.encodeToByteArray()
        val replacement = after.encodeToByteArray()
        require(needle.size == replacement.size)
        try {
            val matches = (0..bytes.size - needle.size).filter { offset ->
                needle.indices.all { bytes[offset + it] == needle[it] }
            }
            require(matches.size == 1) { "Mutation must select exactly one represented scalar" }
            replacement.copyInto(bytes, matches.single())
            return NativeSerialPrivatePublicationRequestCodecV1.decode(bytes)
        } finally { bytes.fill(0); needle.fill(0); replacement.fill(0) }
    }

    fun id(ordinal: Int): DomainId =
        DomainId("81000000-0000-4000-8000-" + ordinal.toString().padStart(12, '0'))
}
