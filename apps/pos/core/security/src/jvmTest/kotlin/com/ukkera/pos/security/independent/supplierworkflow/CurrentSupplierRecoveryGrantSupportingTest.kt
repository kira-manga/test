package com.ukkera.pos.security.independent.supplierworkflow

import com.ukkera.pos.security.AuthenticatedPrincipal
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationDenial
import com.ukkera.pos.security.AuthorizationPolicy
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.BranchServicePurpose
import com.ukkera.pos.security.CentralGatewayCredentials
import com.ukkera.pos.security.DeviceGatewayCredentials
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.ResultRecoveryAccess
import com.ukkera.pos.security.ResultRecoveryAudit
import com.ukkera.pos.security.ResultRecoveryAuditEvent
import com.ukkera.pos.security.ResultRecoveryDecision
import com.ukkera.pos.security.ResultRecoveryPolicy
import com.ukkera.pos.security.CurrentAuthorizationProvider
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.ServiceGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TenantScope
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedCentralIdentity
import com.ukkera.pos.security.VerifiedDeviceIdentity
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.security.VerifiedServiceIdentity
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Supporting shared-policy checks derived from frozen F-AUTHORITY and SWF-010.
 * Synthetic verified identities/snapshots are not native custody, policy activation,
 * Master persistence, RPC disclosure, or a passing whole supplier-workflow case.
 */
class CurrentSupplierRecoveryGrantSupportingTest {
    @Test
    fun frozenAuthorityMatrixRequiresRecoveryIndependentlyOfReadAndAdministration(): Unit = runTest {
        val actor = local()
        val rows = listOf(
            GrantRow("AUTH-000", read = false, administer = false, recover = false, expectedAllowed = false),
            GrantRow("AUTH-001", read = false, administer = false, recover = true, expectedAllowed = true),
            GrantRow("AUTH-010", read = false, administer = true, recover = false, expectedAllowed = false),
            GrantRow("AUTH-011", read = false, administer = true, recover = true, expectedAllowed = true),
            GrantRow("AUTH-100", read = true, administer = false, recover = false, expectedAllowed = false),
            GrantRow("AUTH-101", read = true, administer = false, recover = true, expectedAllowed = true),
            GrantRow("AUTH-110", read = true, administer = true, recover = false, expectedAllowed = false),
            GrantRow("AUTH-111", read = true, administer = true, recover = true, expectedAllowed = true),
        )
        var current = snapshot(actor)
        var currentReads = 0
        val audits = mutableListOf<ResultRecoveryAuditEvent>()
        val policy = ResultRecoveryPolicy(
            CurrentAuthorizationProvider { currentReads++; current },
            ResultRecoveryAudit { audits.add(it); true },
        )
        for (row in rows) {
            current = snapshot(actor, buildList {
                if (row.read) add(readGrant())
                if (row.administer) add(administrationGrant())
                if (row.recover) add(recoveryGrant())
            })
            val decision = policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION)
            if (row.expectedAllowed) {
                assertOriginal(assertIs<ResultRecoveryDecision.Allowed>(decision, row.id), row.id)
            } else {
                assertEquals(denied(AuthorizationDenial.PERMISSION_DENIED), decision, row.id)
            }
        }
        assertEquals(8, currentReads)
        assertTrue(audits.isEmpty(), "An original-owner grant is not an inferred supervisor route")
    }

    @Test
    fun explicitRecoveryDenyWinsButReadAndAdministrationDeniesDoNotReplaceRecovery(): Unit = runTest {
        val actor = local()
        var current = snapshot(actor, listOf(
            readGrant(), administrationGrant(), recoveryGrant(),
            recoveryGrant(effect = GrantEffect.DENY, namedSubjectId = ACTOR, targetIds = setOf(operationTarget())),
        ))
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { current })
        assertEquals(denied(AuthorizationDenial.EXPLICIT_DENY),
            policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION))
        current = snapshot(actor, listOf(
            readGrant(GrantEffect.DENY), administrationGrant(GrantEffect.DENY), recoveryGrant(),
        ))
        assertOriginal(assertIs(policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION)))
        current = snapshot(actor, listOf(recoveryGrant(),
            recoveryGrant(effect = GrantEffect.DENY, targetIds = setOf(OTHER_OPERATION_TARGET))))
        assertOriginal(assertIs(policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION)))
    }

    @Test
    fun currentRecoveryGrantMustMatchPerformVerbNamedActorAndExactOperationTarget(): Unit = runTest {
        val actor = local()
        var current = snapshot(actor)
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { current })
        val nonmatching = listOf(
            recoveryGrant(verb = PermissionVerb.REQUEST),
            recoveryGrant(verb = PermissionVerb.ADMINISTER),
            recoveryGrant(namedSubjectId = OTHER_ACTOR),
            recoveryGrant(targetIds = setOf(OTHER_OPERATION_TARGET)),
        )
        for (grant in nonmatching) {
            current = snapshot(actor, listOf(readGrant(), administrationGrant(), grant))
            assertEquals(denied(AuthorizationDenial.PERMISSION_DENIED),
                policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION))
        }
        current = snapshot(actor, listOf(recoveryGrant(namedSubjectId = ACTOR,
            targetIds = setOf(operationTarget()))))
        assertOriginal(assertIs(policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION)))
        assertEquals(denied(AuthorizationDenial.PERMISSION_DENIED),
            policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OperationId(OTHER_OPERATION_TARGET.value)))
    }

    @Test
    fun foreignGrantAndQueryScopesCannotAuthorizeOriginalRecovery(): Unit = runTest {
        val actor = local()
        var current = snapshot(actor)
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { current })
        val foreignScopes = listOf(
            SCOPE.copy(branchId = id(41)),
            SCOPE.copy(companyId = id(42)),
            SCOPE.copy(organizationId = id(43)),
        )
        for (foreign in foreignScopes) {
            current = snapshot(actor, listOf(readGrant(), administrationGrant(), recoveryGrant(scope = foreign)))
            assertEquals(denied(AuthorizationDenial.PERMISSION_DENIED),
                policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION))
            current = snapshot(actor, listOf(recoveryGrant()))
            assertEquals(denied(AuthorizationDenial.WRONG_SCOPE),
                policy.authorizeWithCurrentGrant(actor, foreign, ACTOR, OPERATION))
        }
    }

    @Test
    fun grantsOnlySnapshotChangeKeepsIdentityButCannotCacheRecoveryAuthority(): Unit = runTest {
        val actor = local()
        var current = snapshot(actor, listOf(recoveryGrant()), policyRevision = Revision(1))
        var currentReads = 0
        val provider = CurrentAuthorizationProvider { currentReads++; current }
        val policy = ResultRecoveryPolicy(provider)
        assertOriginal(assertIs(policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION)))
        current = snapshot(actor, listOf(readGrant(), administrationGrant()), policyRevision = Revision(2))
        assertNull(AuthorizationPolicy(provider).currentIdentityDenial(actor))
        assertEquals(denied(AuthorizationDenial.PERMISSION_DENIED),
            policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION))
        current = snapshot(actor, listOf(recoveryGrant()), policyRevision = Revision(3))
        assertOriginal(assertIs(policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION)))
        assertEquals(4, currentReads, "Every decision requests current authority, never the prior allow")
    }

    @Test
    fun currentIdentityFailuresDenyRecoveryEvenWithAllThreeBusinessGrants(): Unit = runTest {
        val actor = local()
        val grants = listOf(readGrant(), administrationGrant(), recoveryGrant())
        val failures = listOf(
            CurrentFailure("disabled", snapshot(actor, grants, enabled = false), AuthorizationDenial.ACCOUNT_DISABLED),
            CurrentFailure("revoked", snapshot(actor, grants, active = false), AuthorizationDenial.SESSION_REVOKED),
            CurrentFailure("not admitted", snapshot(actor, grants, admitted = false), AuthorizationDenial.DEVICE_NOT_ADMITTED),
            CurrentFailure("actor", snapshot(actor, grants, subjectId = OTHER_ACTOR), AuthorizationDenial.STALE_AUTHENTICATION),
            CurrentFailure("realm", snapshot(actor, grants, kind = PrincipalKind.ADMITTED_DEVICE), AuthorizationDenial.STALE_AUTHENTICATION),
            CurrentFailure("scope", snapshot(actor, grants, scope = SCOPE.copy(branchId = id(41))), AuthorizationDenial.STALE_AUTHENTICATION),
            CurrentFailure("session", snapshot(actor, grants, authenticationId = id(44)), AuthorizationDenial.STALE_AUTHENTICATION),
            CurrentFailure("generation", snapshot(actor, grants, generation = SessionGeneration(2)), AuthorizationDenial.STALE_AUTHENTICATION),
            CurrentFailure("device", snapshot(actor, grants, deviceId = id(45)), AuthorizationDenial.STALE_AUTHENTICATION),
            CurrentFailure("admission", snapshot(actor, grants, admissionRevision = Revision(2)), AuthorizationDenial.STALE_AUTHENTICATION),
            CurrentFailure("purpose", snapshot(actor, grants, purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL),
                AuthorizationDenial.STALE_AUTHENTICATION),
        )
        var current = snapshot(actor, grants)
        val audits = mutableListOf<ResultRecoveryAuditEvent>()
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { current }, ResultRecoveryAudit { audits.add(it); true })
        for (failure in failures) {
            current = failure.snapshot
            assertEquals(denied(failure.denial), policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION), failure.name)
        }
        assertTrue(audits.isEmpty())
    }

    @Test
    fun unauthenticatedOtherRealmsAndStepUpIdentitiesNeverAcquireOriginalRecoveryAuthority(): Unit = runTest {
        var currentReads = 0
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { currentReads++; null })
        assertEquals(denied(AuthorizationDenial.UNAUTHENTICATED),
            policy.authorizeWithCurrentGrant(null, SCOPE, ACTOR, OPERATION))
        val verifier = SyntheticVerifier(
            central = VerifiedCentralIdentity(ACTOR, SCOPE.company(), SESSION, SessionGeneration(1)),
            device = VerifiedDeviceIdentity(DEVICE, SCOPE, Revision(1)),
            service = VerifiedServiceIdentity(ACTOR, DEVICE, SCOPE, SESSION, SessionGeneration(1), Revision(1),
                BranchServicePurpose.MASTER),
        )
        val factory = TrustedPrincipalFactory(verifier)
        val bearer = credential()
        val otherRealms = try {
            listOf(
                checkNotNull(factory.authenticateCentral(CentralGatewayCredentials(bearer))),
                checkNotNull(factory.authenticateDevice(DeviceGatewayCredentials(CONNECTION))),
                checkNotNull(factory.authenticateService(ServiceGatewayCredentials(CONNECTION, bearer))),
            )
        } finally { bearer.destroy() }
        for (principal in otherRealms) {
            assertEquals(denied(AuthorizationDenial.WRONG_PRINCIPAL_KIND),
                policy.authorizeWithCurrentGrant(principal, SCOPE, ACTOR, OPERATION))
        }
        for (purpose in listOf(AuthenticationPurpose.PRIVILEGED_APPROVAL, AuthenticationPurpose.PREPARED_LOCAL_RECOVERY)) {
            assertEquals(denied(AuthorizationDenial.PRIVILEGED_AUTHENTICATION_REQUIRED),
                policy.authorizeWithCurrentGrant(local(purpose = purpose), SCOPE, ACTOR, OPERATION))
        }
        assertEquals(0, currentReads, "Rejected identity kinds do not become a current business authority lookup")
    }

    @Test
    fun missingOrFailedCurrentAuthorityFailsClosedWithoutAuditing(): Unit = runTest {
        val actor = local()
        val audits = mutableListOf<ResultRecoveryAuditEvent>()
        val audit = ResultRecoveryAudit { audits.add(it); true }
        val providers = listOf(
            CurrentAuthorizationProvider { null },
            CurrentAuthorizationProvider { throw IllegalStateException("Synthetic unavailable authority") },
        )
        for (provider in providers) {
            assertEquals(denied(AuthorizationDenial.AUTHORITY_UNAVAILABLE),
                ResultRecoveryPolicy(provider, audit).authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION))
        }
        assertTrue(audits.isEmpty())
    }

    @Test
    fun cancelledCurrentAuthorityIsNotConvertedToARecoveryDecision(): Unit = runTest {
        val actor = local()
        val cancelled = CancellationException("Synthetic current authority cancellation")
        var audits = 0
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { throw cancelled },
            ResultRecoveryAudit { audits++; true })
        assertSame(cancelled, assertFailsWith<CancellationException> {
            policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION)
        })
        assertEquals(0, audits)
    }

    @Test
    fun sharedSupervisorRouteRequiresExplicitScopedGrantAndDurableAuditPreservingOriginal(): Unit = runTest {
        // This tests the shared policy only, not availability of a delegated supplier RPC route.
        val reader = local(actorId = OTHER_ACTOR, sessionId = id(46))
        var current = snapshot(reader, listOf(readGrant(), administrationGrant()))
        var auditSucceeds = true
        val audits = mutableListOf<ResultRecoveryAuditEvent>()
        val provider = CurrentAuthorizationProvider { current }
        val policy = ResultRecoveryPolicy(provider, ResultRecoveryAudit { audits.add(it); auditSucceeds })
        assertEquals(denied(AuthorizationDenial.PERMISSION_DENIED),
            policy.authorizeWithCurrentGrant(reader, SCOPE, ACTOR, OPERATION))
        assertTrue(audits.isEmpty())
        current = snapshot(reader, listOf(recoveryGrant(namedSubjectId = OTHER_ACTOR,
            targetIds = setOf(operationTarget()))), policyRevision = Revision(5))
        val allowed = assertIs<ResultRecoveryDecision.Allowed>(
            policy.authorizeWithCurrentGrant(reader, SCOPE, ACTOR, OPERATION))
        assertEquals(ResultRecoveryAccess.SUPERVISOR_AUDITED, allowed.access)
        assertEquals(SCOPE, allowed.scope)
        assertEquals(OPERATION, allowed.operationId)
        assertEquals(ACTOR, allowed.originalActorId)
        assertEquals(OTHER_ACTOR, allowed.readingActorId)
        assertEquals(ResultRecoveryAuditEvent(SCOPE, OPERATION, ACTOR, OTHER_ACTOR, Revision(5)), audits.single())
        auditSucceeds = false
        assertEquals(denied(AuthorizationDenial.AUTHORITY_UNAVAILABLE),
            policy.authorizeWithCurrentGrant(reader, SCOPE, ACTOR, OPERATION))
        assertEquals(2, audits.size)
        assertEquals(denied(AuthorizationDenial.AUTHORITY_UNAVAILABLE),
            ResultRecoveryPolicy(provider).authorizeWithCurrentGrant(reader, SCOPE, ACTOR, OPERATION))
        current = snapshot(reader, listOf(recoveryGrant(), recoveryGrant(effect = GrantEffect.DENY)))
        assertEquals(denied(AuthorizationDenial.EXPLICIT_DENY),
            policy.authorizeWithCurrentGrant(reader, SCOPE, ACTOR, OPERATION))
        assertEquals(2, audits.size, "Explicit deny cannot manufacture an audited disclosure")
    }

    @Test
    fun ordinaryOriginalRecoveryContractStillAllowsNoGrantButHonorsDenyAndRevocation(): Unit = runTest {
        val actor = local()
        var current = snapshot(actor)
        val policy = ResultRecoveryPolicy(CurrentAuthorizationProvider { current })
        assertOriginal(assertIs(policy.authorize(actor, SCOPE, ACTOR, OPERATION)))
        assertEquals(denied(AuthorizationDenial.PERMISSION_DENIED),
            policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION))
        current = snapshot(actor, listOf(readGrant(), administrationGrant()))
        assertOriginal(assertIs(policy.authorize(actor, SCOPE, ACTOR, OPERATION)))
        current = snapshot(actor, listOf(recoveryGrant(effect = GrantEffect.DENY)))
        assertEquals(denied(AuthorizationDenial.EXPLICIT_DENY), policy.authorize(actor, SCOPE, ACTOR, OPERATION))
        current = snapshot(actor, listOf(recoveryGrant()), active = false)
        assertEquals(denied(AuthorizationDenial.SESSION_REVOKED), policy.authorize(actor, SCOPE, ACTOR, OPERATION))
        assertEquals(denied(AuthorizationDenial.SESSION_REVOKED),
            policy.authorizeWithCurrentGrant(actor, SCOPE, ACTOR, OPERATION))
    }

    private fun assertOriginal(decision: ResultRecoveryDecision.Allowed, message: String? = null) {
        assertEquals(ResultRecoveryAccess.ORIGINAL_ACTOR, decision.access, message)
        assertEquals(SCOPE, decision.scope, message)
        assertEquals(OPERATION, decision.operationId, message)
        assertEquals(ACTOR, decision.originalActorId, message)
        assertEquals(ACTOR, decision.readingActorId, message)
    }

    private data class GrantRow(val id: String, val read: Boolean, val administer: Boolean,
        val recover: Boolean, val expectedAllowed: Boolean)

    private data class CurrentFailure(val name: String, val snapshot: AuthorizationSnapshot, val denial: AuthorizationDenial)

    private class SyntheticVerifier(
        private val local: VerifiedLocalIdentity? = null,
        private val central: VerifiedCentralIdentity? = null,
        private val device: VerifiedDeviceIdentity? = null,
        private val service: VerifiedServiceIdentity? = null,
    ) : PrincipalVerifier {
        override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? = local
        override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = central
        override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = device
        override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = service
    }

    private suspend fun local(actorId: DomainId = ACTOR, sessionId: DomainId = SESSION,
        purpose: AuthenticationPurpose = AuthenticationPurpose.INTERACTIVE): LocalEmployeePrincipal {
        val bearer = credential()
        return try {
            checkNotNull(TrustedPrincipalFactory(SyntheticVerifier(local = VerifiedLocalIdentity(
                actorId, SCOPE, DEVICE, sessionId, SessionGeneration(1), Revision(1), purpose,
            ))).authenticateLocal(LocalGatewayCredentials(CONNECTION, bearer)))
        } finally { bearer.destroy() }
    }

    private fun snapshot(principal: AuthenticatedPrincipal, grants: List<PermissionGrant> = emptyList(),
        enabled: Boolean = true, active: Boolean = true, admitted: Boolean = true,
        policyRevision: Revision = Revision(1), subjectId: DomainId = principal.subjectId,
        kind: PrincipalKind = principal.kind, scope: TenantScope = principal.scope,
        authenticationId: DomainId = principal.authenticationId, generation: SessionGeneration = principal.generation,
        deviceId: DomainId? = principal.deviceId, admissionRevision: Revision? = principal.admissionRevision,
        purpose: AuthenticationPurpose = (principal as? LocalEmployeePrincipal)?.authenticationPurpose
            ?: if (principal.kind == PrincipalKind.CENTRAL_HUMAN) AuthenticationPurpose.INTERACTIVE else AuthenticationPurpose.SERVICE,
    ): AuthorizationSnapshot = AuthorizationSnapshot(subjectId, kind, scope, authenticationId, generation,
        deviceId, admissionRevision, policyRevision, enabled, active, admitted, purpose, grants)

    private fun readGrant(effect: GrantEffect = GrantEffect.ALLOW): PermissionGrant =
        PermissionGrant(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM), SCOPE, effect)

    private fun administrationGrant(effect: GrantEffect = GrantEffect.ALLOW): PermissionGrant =
        PermissionGrant(Permission(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER), SCOPE, effect)

    private fun recoveryGrant(effect: GrantEffect = GrantEffect.ALLOW, scope: TenantScope = SCOPE,
        verb: PermissionVerb = PermissionVerb.PERFORM, namedSubjectId: DomainId? = null,
        targetIds: Set<DomainId>? = null): PermissionGrant =
        PermissionGrant(Permission(SecurityAction.OPERATION_RESULT_RECOVERY, verb), scope, effect,
            namedSubjectId = namedSubjectId, targetIds = targetIds)

    private fun denied(reason: AuthorizationDenial): ResultRecoveryDecision.Denied = ResultRecoveryDecision.Denied(reason)

    private fun operationTarget(): DomainId = DomainId(OPERATION.value)

    private fun credential(): OpaqueCredential = OpaqueCredential(ByteArray(32) { (it + 1).toByte() })

    private companion object {
        fun id(number: Int): DomainId = DomainId("00000000-0000-4000-8000-" + number.toString().padStart(12, '0'))
        val SCOPE = BranchScope(id(1), id(2), id(3))
        val ACTOR = id(4)
        val OTHER_ACTOR = id(5)
        val DEVICE = id(6)
        val SESSION = id(7)
        val CONNECTION = id(8)
        val OPERATION = OperationId(id(10).value)
        val OTHER_OPERATION_TARGET = id(11)
    }
}
