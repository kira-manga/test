package com.ukkera.pos.database.independent.admitteddevicewithdrawal

import com.ukkera.pos.database.ProtectedLocalSecurityCodec
import com.ukkera.pos.database.RoomLocalAuthorizationFacts
import com.ukkera.pos.database.RoomLocalAuthorizationObservation
import com.ukkera.pos.database.RuntimeDeviceRevocationAccountV1
import com.ukkera.pos.database.RuntimeDeviceRevocationCaptureV2
import com.ukkera.pos.database.RuntimeDeviceRevocationEvidenceV2
import com.ukkera.pos.database.RuntimeDeviceRevocationImageV1
import com.ukkera.pos.database.RuntimeHistoryTableV2
import com.ukkera.pos.database.LocalAuthenticationCodec
import com.ukkera.pos.database.deviceRevocationAdmissionAfterV1
import com.ukkera.pos.database.deviceRevocationRequirementV1
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * F-ADAH-BASE logical relationships only: A0's S0 is on M; A1 has no session;
 * neither account has a target-N session. No enrollment, installed-verifier
 * completion, target acquisition, native admission or protected write occurs.
 * Additional identifiers, revisions and the explicit access duration below are
 * constructor scaffolding, not frozen timing, credential or history oracles.
 */
internal class AdmittedDeviceWithdrawalV2SupplementaryData {
    val scope = BranchScope(logicalId(1), logicalId(2), logicalId(3))
    val master = logicalId(4)
    val target = logicalId(5)
    val foundingAccount = logicalId(6)
    val staffAccount = logicalId(7)
    val admissionRequest = logicalId(10)
    val withdrawalRequest = logicalId(11)
    val foreignScope = BranchScope(scope.organizationId, scope.companyId,
        DomainId("20000000-0000-4000-8000-000000000003"))
    val foreignTarget = DomainId("20000000-0000-4000-8000-000000000005")

    val session = scaffoldId(1)
    val issuer = scaffoldId(2)
    val boot = scaffoldId(3)
    private val connection = scaffoldId(4)
    private val pepper = ProtectedPepperReference(scaffoldId(5))

    val operatorAdmission = LocalDeviceAdmission(scope, master, Revision(0), true,
        "synthetic-master-M", null, emptySet())
    val targetAdmission = LocalDeviceAdmission(scope, target, Revision(0), true,
        "synthetic-target-N", null, emptySet())
    val command = LocalAdministrationCommand(withdrawalRequest,
        LocalAdministrationAction.REVOKE_DEVICE, target, targetAdmission.revision)

    val operatorAccount = protectedAccount(foundingAccount, Revision(1), SessionGeneration(1),
        listOf(LocalSessionRecord(session, scope, foundingAccount, master, SessionGeneration(1),
            SessionStatus.ACTIVE, AuthenticationPurpose.INTERACTIVE)))
    val enrolledAccount = protectedAccount(staffAccount, Revision(0), SessionGeneration(0), emptyList())
    val accounts = listOf(operatorAccount, enrolledAccount)

    private val throttle = ThrottlePolicy(0, 1_000L, 2_000L, 5_000L)
    val operatorPolicy = ActivatedLocalPolicy(scope, foundingAccount, Revision(0), listOf(
        PermissionGrant(Permission(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER),
            scope, GrantEffect.ALLOW, namedSubjectId = foundingAccount, targetIds = setOf(target)),
    ), LocalSessionPolicy(1), throttle)
    val staffPolicy = ActivatedLocalPolicy(scope, staffAccount, Revision(0), listOf(
        PermissionGrant(Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM),
            scope, GrantEffect.ALLOW, namedSubjectId = staffAccount),
        PermissionGrant(Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM),
            scope, GrantEffect.DENY, namedSubjectId = staffAccount),
        PermissionGrant(Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT),
            scope, GrantEffect.DENY, namedSubjectId = staffAccount),
        PermissionGrant(Permission(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER),
            scope, GrantEffect.DENY, namedSubjectId = staffAccount),
    ), LocalSessionPolicy(1), throttle)

    // An inert token-hash value and explicit duration, never an issued bearer or
    // a mandatory lifetime. These supplied ordinal moments qualify no real clock.
    val issuance = LocalAccessIssuance(scope, foundingAccount, session, master, SessionGeneration(1),
        operatorAccount.credentialRevision, operatorAdmission.revision, issuer,
        MonotonicMoment(boot, 10L), 1_000L, LocalAccessTokenHash.fromCanonicalSha256("a".repeat(64)))
    val observed = RoomLocalAuthorizationObservation(issuer, MonotonicMoment(boot, 20L))
    val facts = RoomLocalAuthorizationFacts(operatorAccount.state, operatorAccount.credentialRevision,
        operatorAccount.credential != null, operatorPolicy, operatorAdmission, issuance, listOf(
            accountDigest(operatorAccount), digest(ProtectedLocalSecurityCodec.encodePolicy(operatorPolicy)),
            digest(ProtectedLocalSecurityCodec.encodeAdmission(operatorAdmission)),
            digest(ProtectedLocalSecurityCodec.encodeIssuance(issuance)),
        ))
    val event = LocalAuthEvent(scope, withdrawalRequest, LocalAuthEventKind.DEVICE_REVOKED,
        target, foundingAccount, session, null, observed.moment,
        localAdministrationRequestDigest(scope, foundingAccount, command))

    val census = accounts.map { RuntimeDeviceRevocationAccountV1.capture(it, target) }

    // The unchanged-account expectation comes from F-ADAH-BASE, not from the
    // account AFTER reducer under test. No N session exists in either input.
    val expectedAccounts = accounts.associate { it.state.actorId to accountDigest(it) }
    val expectedImages: List<RuntimeDeviceRevocationImageV1> = run {
        // Reuse the supported profile's revision/event encodings only to construct
        // declaration inputs. Neither encoding is a new frozen withdrawal oracle.
        val after = deviceRevocationAdmissionAfterV1(targetAdmission)
        listOf(
            RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.Admission(scope, target),
                after.revision, ProtectedLocalSecurityCodec.encodeAdmission(after)),
            RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.AuthEvent(scope, withdrawalRequest),
                event.revision, LocalAuthenticationCodec.encodeEvent(event)),
        )
    }

    /** Substituted identity adapter, not the production credential or transport verifier. */
    suspend fun principal(): LocalEmployeePrincipal {
        val identity = VerifiedLocalIdentity(foundingAccount, scope, master, session, SessionGeneration(1),
            operatorAdmission.revision, AuthenticationPurpose.INTERACTIVE)
        val verifier = object : PrincipalVerifier by DenyAllPrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? =
                if (credentials.connectionId == connection) identity else null
        }
        val bearer = OpaqueCredential(ByteArray(32) { 1 })
        return try {
            requireNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(LocalGatewayCredentials(connection, bearer)))
        } finally { bearer.destroy() }
    }

    /** A capability from supplied policy/status values only, never a current Room observation. */
    fun permitted(principal: LocalEmployeePrincipal): AuthorizedAction {
        val snapshot = AuthorizationSnapshot(foundingAccount, PrincipalKind.LOCAL_EMPLOYEE, scope, session,
            principal.generation, master, operatorAdmission.revision, operatorPolicy.revision,
            true, true, true, AuthenticationPurpose.INTERACTIVE, operatorPolicy.grants)
        val decision = AuthorizationEvaluator.evaluate(principal, snapshot,
            deviceRevocationRequirementV1(scope, foundingAccount, command))
        return requireNotNull((decision as? AuthorizationDecision.Allowed)?.authorization)
    }

    /** Mutated declarations deliberately retain the ORIGINAL requirement/capability. */
    suspend fun capture(
        selectedCommand: LocalAdministrationCommand = command,
        selectedBefore: LocalDeviceAdmission = targetAdmission,
        selectedEvent: LocalAuthEvent = event,
        selectedObserved: RoomLocalAuthorizationObservation = observed,
        selectedFacts: RoomLocalAuthorizationFacts = facts,
        selectedAccounts: List<RuntimeDeviceRevocationAccountV1> = census,
        selectedExpectedAccounts: Map<DomainId, PayloadDigest> = expectedAccounts,
    ): RuntimeDeviceRevocationCaptureV2 {
        val principal = principal()
        return RuntimeDeviceRevocationCaptureV2.capture(principal, selectedFacts, permitted(principal),
            selectedCommand, selectedObserved, selectedEvent, selectedBefore,
            selectedAccounts, expectedImages, selectedExpectedAccounts)
    }

    /** Submits DATA directly. This is not a protected seal or a native-owned image. */
    fun submitDeclaredImage(capture: RuntimeDeviceRevocationCaptureV2, image: RuntimeDeviceRevocationImageV1) {
        val key = when (image.table) {
            RuntimeHistoryTableV2.ADMISSION -> ProtectedLocalRecordKey.Admission(scope, image.recordId)
            RuntimeHistoryTableV2.AUTH_EVENT -> ProtectedLocalRecordKey.AuthEvent(scope, image.recordId)
            else -> error("Only the no-target-session declaration images belong in this fixture")
        }
        capture.captureImage(ProtectedLocalBinding(key, image.revision), image.plaintextDigest)
    }

    suspend fun completedDeclaration(): RuntimeDeviceRevocationEvidenceV2 {
        val capture = capture()
        expectedImages.forEach { submitDeclaredImage(capture, it) }
        return capture.evidence()
    }

    fun accountDigest(account: ProtectedLocalAccount): PayloadDigest =
        digest(ProtectedLocalSecurityCodec.encodeAccount(account))

    private fun protectedAccount(actor: DomainId, revision: Revision, generation: SessionGeneration,
        sessions: List<LocalSessionRecord>): ProtectedLocalAccount {
        val salt = ByteArray(16) { 1 }
        val verifier = ByteArray(32) { 2 }
        val credentialRevision = Revision(0)
        // Syntactic password-record bytes only: no password hashing/verification
        // or key-provider qualification is performed or claimed.
        val credential = try {
            PasswordHashRecord(CredentialBinding(scope, actor, credentialRevision),
                PasswordHashParameters(), pepper, salt, verifier)
        } finally { salt.fill(0); verifier.fill(0) }
        return ProtectedLocalAccount(LocalAccountSessionState(scope, actor, true, revision, generation, sessions),
            credentialRevision, credential)
    }

    private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
    private fun logicalId(ordinal: Int): DomainId =
        DomainId("10000000-0000-4000-8000-" + ordinal.toString().padStart(12, '0'))
    private fun scaffoldId(ordinal: Int): DomainId =
        DomainId("30000000-0000-4000-8000-" + ordinal.toString().padStart(12, '0'))
}
