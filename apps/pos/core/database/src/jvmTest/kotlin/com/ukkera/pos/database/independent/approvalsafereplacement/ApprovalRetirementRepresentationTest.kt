package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** ASR-01/04/09/10/14 immutable-data slices. No Master, native authority or database is created. */
class ApprovalRetirementRepresentationTest {
    private val fixture = readFixture("approval-safe-replacement")
    private val base = readFixture("arrival-approval-workflow")
    private val actors = fixture.getValue("actors").jsonObject
    private val scope = base.getValue("sources").jsonObject.getValue("supplier").jsonObject.let {
        BranchScope(carrierId(1), DomainId(it.getValue("companyId").jsonPrimitive.content),
            DomainId(it.getValue("branchId").jsonPrimitive.content))
    }
    private val boot = carrierId(2)
    private val issuer = carrierId(3)
    private val changedDigest = PayloadDigest("e".repeat(64))

    @Test fun retirementEventPreservesEveryOriginalTargetAndActorAcrossAllFrozenRoutes() {
        each { original, requester ->
            val before = LocalActionApprovalCodec.encode(original)
            val event = actionApprovalRetirementEvent(original, requester)
            assertEquals(original.record.approvalId, event.targetId)
            assertEquals(original.record.requestorId, event.operatorId)
            assertEquals(actionApprovalRetirementId(scope, original.record.approvalId), event.eventId)
            assertEquals(runtimeApprovalDigest(original), event.contentDigest)
            assertEquals(requester.access.observedAt, event.at)
            requireActionApprovalRetirementEvent(original, event)
            assertEquals(Revision(0), original.record.revision)
            assertNull(original.record.consumedByOperationId)
            assertContentEquals(before, LocalActionApprovalCodec.encode(original))
        }
    }

    @Test fun retirementUsesFreshRequesterSessionWithoutRewritingIssuance() {
        each { original, requester ->
            val fresh = requester.copy(sessionId = carrierId(21), accountRevision = Revision(8),
                access = requester.access.copy(observedAt = MonotonicMoment(boot, 350_000)))
            val originalApprover = original.record.approverId
            val event = actionApprovalRetirementEvent(original, fresh)
            // Carrier AP expiry is 302000; the fresh requester window is still current.
            assertTrue(event.at.elapsedMillis > original.record.issuedAt.elapsedMillis + original.record.validForMillis)
            assertEquals(fresh.sessionId, event.sessionId)
            assertEquals(fresh.accountRevision, event.accountRevision)
            assertEquals(requester.sessionId, original.requestorSessionId)
            assertEquals(originalApprover, original.record.approverId)
            requireActionApprovalRetirementEvent(original, event)
        }
    }

    @Test fun duplicateRetirementLocatorIsStableAndDifferentOriginalCannotShareIt() {
        each { original, _ ->
            val id = actionApprovalRetirementId(scope, original.record.approvalId)
            assertEquals(id, actionApprovalRetirementId(scope, original.record.approvalId))
            assertNotEquals(id, original.record.approvalId)
            assertNotEquals(id, actionApprovalRetirementId(scope, carrierId(22)))
            assertNotEquals(id, actionApprovalRetirementId(scope.copy(branchId = carrierId(23)), original.record.approvalId))
        }
    }

    @Test fun mismatchedRequesterOrScopeCannotCreateRetirementData() {
        each { original, requester ->
            assertFailsWith<IllegalArgumentException> {
                actionApprovalRetirementEvent(original, requester.copy(actorId = DomainId(actors.getValue("X").jsonPrimitive.content)))
            }
            assertFailsWith<IllegalArgumentException> {
                actionApprovalRetirementEvent(original, requester.copy(scope = scope.copy(branchId = carrierId(23))))
            }
        }
    }

    @Test fun consumedOriginalCannotBeRepresentedAsUnusedRetirement() {
        each { original, requester ->
            val event = actionApprovalRetirementEvent(original, requester)
            val consumed = original.consumed(original.record.copy(revision = Revision(1),
                consumedByOperationId = original.record.requirement.binding!!.operationId))
            assertFailsWith<IllegalArgumentException> { actionApprovalRetirementEvent(consumed, requester) }
            assertFailsWith<IllegalArgumentException> { requireActionApprovalRetirementEvent(consumed, event) }
            assertEquals(Revision(1), consumed.record.revision)
            assertEquals(original.record.requirement.binding!!.operationId, consumed.record.consumedByOperationId)
        }
    }

    @Test fun alteredRetirementCoordinatesCannotJoinOriginalApproval() {
        each { original, requester ->
            val event = actionApprovalRetirementEvent(original, requester)
            for (changed in listOf<() -> LocalAuthEvent>(
                { copyEvent(event, eventId = carrierId(24)) },
                { copyEvent(event, targetId = carrierId(25)) },
                { copyEvent(event, operatorId = DomainId(actors.getValue("X").jsonPrimitive.content)) },
                { copyEvent(event, scope = scope.copy(branchId = carrierId(23))) },
                { copyEvent(event, sessionId = null) }, { copyEvent(event, accountRevision = null) },
                { copyEvent(event, contentDigest = changedDigest) },
                { copyEvent(event, kind = LocalAuthEventKind.ACTION_APPROVAL_CONSUMED) },
            )) assertFailsWith<IllegalArgumentException> { requireActionApprovalRetirementEvent(original, changed()) }
        }
    }

    @Test fun exactCanonicalRetirementEvidenceRetainsIssuedBytesAndSeparateAuditOnly() {
        each { original, requester ->
            val value = evidence(original, requester)
            val bytes = RuntimeActionApprovalRetirementEvidenceCodec.encode(value)
            val decoded = RuntimeActionApprovalRetirementEvidenceCodec.decode(bytes)
            assertContentEquals(bytes, RuntimeActionApprovalRetirementEvidenceCodec.encode(decoded))
            assertEquals(value.approval, decoded.approval)
            assertEquals(value.beforeHistorySequence, decoded.beforeHistorySequence)
            assertEquals(value.beforeHistoryDigest, decoded.beforeHistoryDigest)
            assertEquals(value.beforeCommandSequence, decoded.beforeCommandSequence)
            assertEquals(value.images, decoded.images)
            assertEquals(1, decoded.images.size)
            assertEquals(RuntimeHistoryTableV2.AUTH_EVENT.tag, decoded.images.single().tableTag)
            assertEquals(RuntimeHistoryMutationKindV2.INSERT.tag, decoded.images.single().mutationTag)
            assertEquals(Revision(0), decoded.images.single().revision)
            assertEquals(requester.sessionId, decoded.protectedFinalDeclaration().sessionId)
        }
    }

    @Test fun incompleteOrChangedCanonicalRetirementEvidenceCannotBecomeQualifiedData() {
        each { original, requester ->
            val value = evidence(original, requester)
            val text = RuntimeActionApprovalRetirementEvidenceCodec.encode(value).decodeToString()
            val body = Json.parseToJsonElement(text).jsonObject
            val malformed = mutableListOf("", text.dropLast(1), " $text", "$text\n")
            for (field in listOf("approval", "requestor", "nativeRecordDigest", "beforeHistoryDigest", "images"))
                malformed += JsonObject(body.toMutableMap().apply { remove(field) }).toString()
            malformed += JsonObject(body.toMutableMap().apply { put("schema", JsonPrimitive("unsupported/v999")) }).toString()
            malformed += JsonObject(body.toMutableMap().apply { put("images", JsonArray(emptyList())) }).toString()
            malformed += JsonObject(body.toMutableMap().apply { put("unexpected", JsonPrimitive(true)) }).toString()
            for (changed in malformed) assertFailsWith<RuntimeHistoryFormatRejectedV2> {
                RuntimeActionApprovalRetirementEvidenceCodec.decode(changed.encodeToByteArray())
            }
            assertFailsWith<RuntimeHistoryFormatRejectedV2> {
                RuntimeActionApprovalRetirementEvidenceCodec.encode(value.copy(images = value.images + value.images))
            }
        }
    }

    private fun each(block: (ProtectedLocalActionApproval, RuntimeCommandAuthorizationSnapshotV1) -> Unit) {
        fixture.getValue("targets").jsonArray.forEachIndexed { index, element ->
            val target = element.jsonObject
            val pointer = target.getValue("completeOriginalActionFixture").jsonObject.getValue("jsonPointer").jsonPrimitive.content
            val action = pointer.removePrefix("/").split('/').fold(base as JsonElement) { current, key ->
                if (current is JsonArray) current[key.toInt()] else current.jsonObject.getValue(key)
            }.jsonObject
            val requester = DomainId(target.getValue("originalRequesterId").jsonPrimitive.content)
            val approver = DomainId(target.getValue("originalApproverId").jsonPrimitive.content)
            val operation = OperationId(target.getValue("originalOperationId").jsonPrimitive.content)
            val requesterRevision = Revision(target.getValue("pinnedRequesterPolicyRevision").jsonPrimitive.content.toLong())
            // Unbound native clock/incarnation/digest values are inert ABI carriers, not real grants.
            val requestor = RuntimeCommandAuthorizationSnapshotV1(scope, requester,
                DomainId(actors.getValue("historicalSessionR").jsonPrimitive.content),
                DomainId(actors.getValue("originalDeviceR").jsonPrimitive.content), SessionGeneration(1),
                PrincipalKind.LOCAL_EMPLOYEE, AuthenticationPurpose.INTERACTIVE, Revision(3), Revision(2),
                Revision(4), requesterRevision,
                RuntimeCommandAccessWindowV1(issuer, MonotonicMoment(boot, 1_000), MonotonicMoment(boot, 5_000),
                    400_000, Revision(2), Revision(4)),
                RuntimeCommandAuthorizationDependenciesV1(PayloadDigest("1".repeat(64)), PayloadDigest("2".repeat(64)),
                    PayloadDigest("3".repeat(64)), PayloadDigest("4".repeat(64))))
            val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
                scope, ActionBinding(DomainId(action.getValue("deliveryId").jsonPrimitive.content), Revision(0),
                    PayloadDigest(action.getValue("frozenContentDigest").jsonObject.getValue("digestRef").jsonPrimitive.content),
                    operation, "fixture.asr.original"))
            val approverRevision = Revision(action.getValue("approverEmployeePolicyRevision").jsonPrimitive.content.toLong())
            val record = ActionApprovalRecord(carrierId(100 + index), requester, approver, requirement,
                requesterRevision, approverRevision, approverRevision, MonotonicMoment(boot, 2_000), 300_000, Revision(0), null)
            val original = ProtectedLocalActionApproval(record, issuer, requestor.sessionId, requestor.generation,
                requestor.deviceId, requestor.credentialRevision, requestor.admissionRevision, requestor.deviceId,
                Revision(if (approver == requester) 2 else 5), Revision(4),
                LocalActionApprovalCodec.confirmationDigest(requester, requesterRevision, requirement),
                Revision(if (approver == requester) 3 else 6), SessionGeneration(if (approver == requester) 1 else 2),
                PayloadDigest("8".repeat(64)))
            block(original, requestor)
        }
    }

    private fun evidence(original: ProtectedLocalActionApproval, requester: RuntimeCommandAuthorizationSnapshotV1): RuntimeActionApprovalRetirementEvidenceV1 {
        val event = actionApprovalRetirementEvent(original, requester)
        return RuntimeActionApprovalRetirementEvidenceV1(commandName = "fixture.asr.command", commandVersion = 1,
            approvalId = original.record.approvalId, approval = LocalActionApprovalCodec.encode(original).decodeToString(),
            requestor = requester, nativeRecordDigest = PayloadDigest("f".repeat(64)), beforeHistorySequence = 5,
            beforeHistoryDigest = PayloadDigest("9".repeat(64)), beforeCommandEpoch = Revision(1),
            beforeCommandSequence = JournalSequence(0), beforeCommandDigest = PayloadDigest("a".repeat(64)),
            images = listOf(RuntimeApprovalImageV1(RuntimeHistoryTableV2.AUTH_EVENT.tag, event.eventId,
                RuntimeHistoryMutationKindV2.INSERT.tag, Revision(0), sha256(LocalAuthenticationCodec.encodeEvent(event)))))
    }

    private fun copyEvent(event: LocalAuthEvent, scope: BranchScope = event.scope, eventId: DomainId = event.eventId,
        kind: LocalAuthEventKind = event.kind, targetId: DomainId = event.targetId, operatorId: DomainId? = event.operatorId,
        sessionId: DomainId? = event.sessionId, accountRevision: Revision? = event.accountRevision,
        contentDigest: PayloadDigest? = event.contentDigest): LocalAuthEvent =
        LocalAuthEvent(scope, eventId, kind, targetId, operatorId, sessionId, accountRevision, event.at, contentDigest)

    private fun carrierId(number: Int): DomainId = DomainId("af000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun readFixture(packet: String): JsonObject {
        val relative = Path.of("qa", "pos", "designs", packet, "fixtures.json")
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val candidate = directory.resolve(relative)
            if (Files.isRegularFile(candidate)) return Json.parseToJsonElement(Files.readString(candidate)).jsonObject
            directory = directory.parent
        }
        error("Missing frozen test specification: $relative")
    }
}
