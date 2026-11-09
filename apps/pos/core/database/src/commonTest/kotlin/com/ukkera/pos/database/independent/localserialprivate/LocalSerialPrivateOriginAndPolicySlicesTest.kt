package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.OperationId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/** L1 origin-data and policy observations; no Room/native/transport dependencies are acquired. */
class LocalSerialPrivateOriginAndPolicySlicesTest {
    private val inputs = LocalSerialPrivateEvidenceInputs
    private val privateOperations = listOf(NativeSerialPrivatePublicationOperationV1.PREPARE,
        NativeSerialPrivatePublicationOperationV1.REVIEW, NativeSerialPrivatePublicationOperationV1.RECOVER,
        NativeSerialPrivatePublicationOperationV1.STATUS)
    private val recoveryOperations = privateOperations.drop(1)

    @Test
    fun lspr002AbsentOriginCannotAuthorizeAnyPrivateAccess() {
        val original = inputs.origin()
        val retained = inputs.evidence(original)
        val pinned = RuntimeSerialPublicationRequestEvidenceCodec.encode(retained)
        for (operation in privateOperations) {
            val request = inputs.access(original, operation)
            requireRuntimeSerialPublicationOrigin(request, retained, inputs.actorA)
            assertFails { requireRuntimeSerialPublicationOrigin(request, null, inputs.actorA) }
        }
        assertContentEquals(pinned, RuntimeSerialPublicationRequestEvidenceCodec.encode(retained))
    }

    @Test
    fun lspr002ChangedRequesterEvidenceCannotRepairOrAdoptOriginal() {
        val original = inputs.origin()
        val retained = inputs.evidence(original)
        val changed = inputs.evidence(original, inputs.authorization(actor = inputs.actorB))
        changed.validate() // Plausible DATA is deliberately not a native authentication proof.
        for (operation in privateOperations) {
            val request = inputs.access(original, operation)
            assertFails { requireRuntimeSerialPublicationOrigin(request, changed, inputs.actorA) }
            assertFails { requireRuntimeSerialPublicationOrigin(request, retained, inputs.actorB) }
        }
        assertEquals(inputs.actorA, retained.authorization.actorId)
        assertEquals(original.requestId, retained.requestValue().requestId)
    }

    @Test
    fun lspr003ChangedEntityAndContentRefuseAgainstPinnedOrigin() {
        val original = inputs.origin()
        val retained = inputs.evidence(original)
        val pinned = RuntimeSerialPublicationRequestEvidenceCodec.encode(retained)
        val changedContent = inputs.origin(inputs.definition().copy(serialCode = inputs.contentB)).contentDigest
        for (operation in privateOperations) {
            val request = inputs.access(original, operation)
            val entityCandidate = inputs.mutateScalar(request, original.entityId.value, inputs.otherEntity.value)
            val contentCandidate = inputs.mutateScalar(request, original.contentDigest.value, changedContent.value)
            assertEquals(original.requestId, entityCandidate.originRequestId)
            assertEquals(request.originRequestDigest, entityCandidate.originRequestDigest)
            assertEquals(request.originRequestDigest, contentCandidate.originRequestDigest)
            assertFails { requireRuntimeSerialPublicationOrigin(entityCandidate, retained, inputs.actorA) }
            assertFails { requireRuntimeSerialPublicationOrigin(contentCandidate, retained, inputs.actorA) }
        }
        assertContentEquals(pinned, RuntimeSerialPublicationRequestEvidenceCodec.encode(retained))
    }

    @Test
    fun lspr005OtherEmployeeCatalogAndSupervisorGrantsCannotAdoptPrivateOrigin(): Unit = runTest {
        val original = inputs.origin()
        val retained = inputs.evidence(original)
        val other = inputs.principal(actor = inputs.actorB)
        val state = inputs.snapshot(other, listOf(inputs.catalogGrant(), inputs.recoveryGrant(original, GrantEffect.ALLOW)))
        val provider = CurrentAuthorizationProvider { state }
        val recovery = ResultRecoveryPolicy(provider, ResultRecoveryAudit { true })
        val projection = assertIs<ResultRecoveryDecision.Allowed>(
            recovery.authorize(other, inputs.scope, inputs.actorA, OperationId(original.requestId.value)))
        assertEquals(ResultRecoveryAccess.SUPERVISOR_AUDITED, projection.access)
        for (operation in privateOperations) {
            val request = inputs.access(original, operation)
            val snapshot = inputs.authorization(inputs.actorB, other)
            val permitted = if (operation == NativeSerialPrivatePublicationOperationV1.PREPARE)
                assertIs<AuthorizationDecision.Allowed>(AuthorizationPolicy(provider).authorize(other,
                    runtimeSerialPublicationRequirement(request, inputs.actorB))).authorization else null
            assertFails {
                RuntimeSerialPublicationRequestCapture.capture(other, request, snapshot, inputs.event(request, snapshot),
                    retained, permitted, if (permitted == null) projection else null)
            }
        }
        assertEquals(inputs.actorA, retained.authorization.actorId)
    }

    @Test
    fun lspr006CatalogRemovalDeniesPreparationAndAllowsOriginalRecoveryCapture(): Unit = runTest {
        val original = inputs.origin()
        val retained = inputs.evidence(original)
        val principal = inputs.principal()
        var state = inputs.snapshot(principal, listOf(inputs.catalogGrant()))
        val provider = CurrentAuthorizationProvider { state }
        val prepare = inputs.access(original, NativeSerialPrivatePublicationOperationV1.PREPARE)
        val requirement = runtimeSerialPublicationRequirement(prepare, inputs.actorA)
        assertEquals(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), requirement.permission)
        assertEquals(inputs.entity, requireNotNull(requirement.binding).documentId)
        assertIs<AuthorizationDecision.Allowed>(AuthorizationPolicy(provider).authorize(principal, requirement))
        state = inputs.snapshot(principal)
        assertIs<AuthorizationDecision.Denied>(AuthorizationPolicy(provider).authorize(principal, requirement))

        val allowed = assertIs<ResultRecoveryDecision.Allowed>(ResultRecoveryPolicy(provider).authorize(
            principal, inputs.scope, inputs.actorA, OperationId(original.requestId.value)))
        assertEquals(ResultRecoveryAccess.ORIGINAL_ACTOR, allowed.access)
        for (operation in recoveryOperations) {
            val request = inputs.access(original, operation)
            val accessRequirement = runtimeSerialPublicationRequirement(request, inputs.actorA)
            assertEquals(Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM), accessRequirement.permission)
            assertEquals(original.requestId, requireNotNull(accessRequirement.binding).documentId)
            val snapshot = inputs.authorization(principal = principal)
            val capture = RuntimeSerialPublicationRequestCapture.capture(principal, request, snapshot,
                inputs.event(request, snapshot), retained, null, allowed)
            assertEquals(original.requestId, capture.request.originRequestId)
            assertEquals(original.contentDigest, capture.request.contentDigest)
            assertEquals(inputs.actorA, capture.actorId)
        }
    }

    @Test
    fun lspr006MatchingRecoveryDenyPrevailsDespiteCatalogAndRecoveryAllows(): Unit = runTest {
        val original = inputs.origin()
        val principal = inputs.principal()
        val allow = inputs.recoveryGrant(original, GrantEffect.ALLOW)
        val deny = inputs.recoveryGrant(original)
        for (grants in listOf(listOf(inputs.catalogGrant(), allow, deny), listOf(deny, allow, inputs.catalogGrant()))) {
            val provider = CurrentAuthorizationProvider { inputs.snapshot(principal, grants) }
            for (operation in recoveryOperations) {
                val request = inputs.access(original, operation)
                val requirement = runtimeSerialPublicationRequirement(request, inputs.actorA)
                assertEquals(OperationId(original.requestId.value), requireNotNull(requirement.binding).operationId)
                assertIs<ResultRecoveryDecision.Denied>(ResultRecoveryPolicy(provider).authorize(
                    principal, inputs.scope, inputs.actorA, OperationId(original.requestId.value)))
            }
        }
    }

    @Test
    fun lspr006RecoveryDenyForDifferentPublicationCannotDenyPinnedOriginal(): Unit = runTest {
        val original = inputs.origin()
        val decoy = inputs.origin(inputs.definition().copy(publicationId = inputs.otherPublication,
            registrationId = inputs.otherEntity))
        val principal = inputs.principal()
        val provider = CurrentAuthorizationProvider { inputs.snapshot(principal,
            listOf(inputs.catalogGrant(), inputs.recoveryGrant(decoy))) }
        val allowed = assertIs<ResultRecoveryDecision.Allowed>(ResultRecoveryPolicy(provider).authorize(
            principal, inputs.scope, inputs.actorA, OperationId(original.requestId.value)))
        assertEquals(ResultRecoveryAccess.ORIGINAL_ACTOR, allowed.access)
        assertEquals(OperationId(original.requestId.value), allowed.operationId)
        assertIs<ResultRecoveryDecision.Denied>(ResultRecoveryPolicy(provider).authorize(
            principal, inputs.scope, inputs.actorA, OperationId(decoy.requestId.value)))
    }

    @Test
    fun lspr006CurrentEmployeeSessionAndDeviceDenialsOverrideOldAllowedSnapshot(): Unit = runTest {
        val original = inputs.origin()
        val principal = inputs.principal()
        var state = inputs.snapshot(principal, listOf(inputs.catalogGrant()))
        val provider = CurrentAuthorizationProvider { state }
        val prepare = inputs.access(original, NativeSerialPrivatePublicationOperationV1.PREPARE)
        val requirement = runtimeSerialPublicationRequirement(prepare, inputs.actorA)
        assertIs<AuthorizationDecision.Allowed>(AuthorizationPolicy(provider).authorize(principal, requirement))
        assertIs<ResultRecoveryDecision.Allowed>(ResultRecoveryPolicy(provider).authorize(
            principal, inputs.scope, inputs.actorA, OperationId(original.requestId.value)))
        for (current in listOf(inputs.snapshot(principal, listOf(inputs.catalogGrant()), enabled = false),
            inputs.snapshot(principal, listOf(inputs.catalogGrant()), active = false),
            inputs.snapshot(principal, listOf(inputs.catalogGrant()), admitted = false))) {
            state = current
            assertIs<AuthorizationDecision.Denied>(AuthorizationPolicy(provider).authorize(principal, requirement))
            assertIs<ResultRecoveryDecision.Denied>(ResultRecoveryPolicy(provider).authorize(
                principal, inputs.scope, inputs.actorA, OperationId(original.requestId.value)))
        }
    }

    @Test
    fun lspr005FreshSessionAndMovedDeviceKeepOriginalAccountBinding(): Unit = runTest {
        val original = inputs.origin()
        val retained = inputs.evidence(original)
        for ((session, device) in listOf(inputs.id(31) to inputs.id(22), inputs.id(32) to inputs.id(33))) {
            val principal = inputs.principal(session = session, device = device)
            val provider = CurrentAuthorizationProvider { inputs.snapshot(principal, listOf(inputs.catalogGrant())) }
            val allowed = assertIs<ResultRecoveryDecision.Allowed>(ResultRecoveryPolicy(provider).authorize(
                principal, inputs.scope, inputs.actorA, OperationId(original.requestId.value)))
            assertEquals(ResultRecoveryAccess.ORIGINAL_ACTOR, allowed.access)
            for (operation in privateOperations) {
                val request = inputs.access(original, operation)
                val snapshot = inputs.authorization(principal = principal)
                val permitted = if (operation == NativeSerialPrivatePublicationOperationV1.PREPARE)
                    assertIs<AuthorizationDecision.Allowed>(AuthorizationPolicy(provider).authorize(principal,
                        runtimeSerialPublicationRequirement(request, inputs.actorA))).authorization else null
                val capture = RuntimeSerialPublicationRequestCapture.capture(principal, request, snapshot,
                    inputs.event(request, snapshot), retained, permitted, if (permitted == null) allowed else null)
                assertEquals(inputs.actorA, capture.actorId)
                assertEquals(session, capture.authorization.sessionId)
                assertEquals(device, capture.authorization.deviceId)
                assertEquals(original.requestId, capture.request.originRequestId)
                assertEquals(if (operation == NativeSerialPrivatePublicationOperationV1.REVIEW ||
                    operation == NativeSerialPrivatePublicationOperationV1.RECOVER) inputs.action else null,
                    capture.request.originalActionId)
            }
        }
    }

    @Test
    fun lspr005OtherBranchAccountCannotRecoverOriginalAccountResult(): Unit = runTest {
        val original = inputs.origin()
        val other = inputs.principal(actor = inputs.id(34), branch = inputs.scope.copy(branchId = inputs.id(35)))
        val provider = CurrentAuthorizationProvider { inputs.snapshot(other) }
        assertIs<ResultRecoveryDecision.Denied>(ResultRecoveryPolicy(provider).authorize(
            other, inputs.scope, inputs.actorA, OperationId(original.requestId.value)))
        assertEquals(inputs.scope, original.scope)
    }
}
