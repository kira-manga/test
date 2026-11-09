package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

/** ASR-10/12 supplemental codec slices. Inert metadata, never a qualified native origin. */
class ApprovalCrossSessionRepresentationTest {
    private val fixture = readFixture("approval-safe-replacement")
    private val base = readFixture("arrival-approval-workflow")
    private val actors = fixture.getValue("actors").jsonObject

    @Test fun freshAuthorityRoundTripPreservesTheOriginalBusinessSessionAndPinnedInput() {
        each { original, replacement ->
            val encoded = LocalActionApprovalCodec.encode(replacement)
            val decoded = decode(replacement, encoded)
            assertEquals(original.requestorSessionId, decoded.requestorSessionId)
            assertEquals(original.requestorGeneration, decoded.requestorGeneration)
            assertEquals(original.requestorDeviceId, decoded.requestorDeviceId)
            assertEquals(original.requestorCredentialRevision, decoded.requestorCredentialRevision)
            assertEquals(original.requestorAdmissionRevision, decoded.requestorAdmissionRevision)
            assertEquals(original.record.requestorId, decoded.record.requestorId)
            assertEquals(original.record.requirement, decoded.record.requirement)
            assertEquals(original.record.requestorPolicyRevision, decoded.record.requestorPolicyRevision)
            assertEquals(original.confirmationDigest, decoded.confirmationDigest)
            assertEquals(original.issueRequestDigest, decoded.issueRequestDigest)
            assertEquals(replacement.currentRequestorAuthority, decoded.currentRequestorAuthority)
            assertNotEquals(decoded.requestorSessionId, assertNotNull(decoded.currentRequestorAuthority).sessionId)
            assertNotEquals(decoded.requestorGeneration, assertNotNull(decoded.currentRequestorAuthority).generation)
            assertContentEquals(encoded, LocalActionApprovalCodec.encode(decoded))
        }
    }

    @Test fun replacementKeepsExactRootPredecessorAndRetirementCoordinatesWithoutRewritingTheOldIssue() {
        each { original, replacement ->
            val before = LocalActionApprovalCodec.encode(original)
            val decoded = decode(replacement, LocalActionApprovalCodec.encode(replacement))
            val origin = assertNotNull(decoded.replacementOrigin)
            assertEquals(original.record.approvalId, origin.rootApprovalId)
            assertEquals(runtimeApprovalDigest(original), origin.rootApprovalDigest)
            assertEquals(original.record.approvalId, origin.predecessorApprovalId)
            assertEquals(runtimeApprovalDigest(original), origin.predecessorApprovalDigest)
            assertEquals(actionApprovalRetirementId(scope(original), original.record.approvalId), origin.retirementId)
            assertEquals(replacement.replacementOrigin, decoded.replacementOrigin)
            assertNotEquals(original.record.approvalId, decoded.record.approvalId)
            assertNull(original.record.consumedByOperationId)
            assertContentEquals(before, LocalActionApprovalCodec.encode(original))
        }
    }

    @Test fun consumingTheDataCopyRetainsBothBindingsAndLeavesTheIssuedOriginalUnchanged() {
        each { _, replacement ->
            val before = LocalActionApprovalCodec.encode(replacement)
            val consumed = replacement.consumed(replacement.record.copy(revision = Revision(1),
                consumedByOperationId = assertNotNull(replacement.record.requirement.binding).operationId))
            val decoded = decode(consumed, LocalActionApprovalCodec.encode(consumed))
            assertEquals(replacement.currentRequestorAuthority, decoded.currentRequestorAuthority)
            assertEquals(replacement.replacementOrigin, decoded.replacementOrigin)
            assertEquals(replacement.requestorSessionId, decoded.requestorSessionId)
            assertEquals(replacement.requestorGeneration, decoded.requestorGeneration)
            assertEquals(replacement.record.requirement.binding?.operationId, decoded.record.consumedByOperationId)
            assertNull(replacement.record.consumedByOperationId)
            assertContentEquals(before, LocalActionApprovalCodec.encode(replacement))
        }
    }

    @Test fun originalV2BytesStayReadableAndReplacementInnerBaseRemainsCanonical() {
        each { original, replacement ->
            val originalBytes = LocalActionApprovalCodec.encode(original)
            val oldDecoded = decode(original, originalBytes)
            assertNull(oldDecoded.currentRequestorAuthority)
            assertNull(oldDecoded.replacementOrigin)
            assertContentEquals(originalBytes, LocalActionApprovalCodec.encode(oldDecoded))
            val wrapper = Json.parseToJsonElement(LocalActionApprovalCodec.encode(replacement).decodeToString()).jsonObject
            assertEquals("ukkera.local-action-approval/v3", wrapper.getValue("schema").jsonPrimitive.content)
            val innerBytes = wrapper.getValue("baseV2").jsonPrimitive.content.encodeToByteArray()
            val inner = decode(replacement, innerBytes)
            assertEquals(replacement.requestorSessionId, inner.requestorSessionId)
            assertEquals(replacement.requestorGeneration, inner.requestorGeneration)
            assertEquals(replacement.record.requirement, inner.record.requirement)
            assertNull(inner.currentRequestorAuthority)
            assertNull(inner.replacementOrigin)
            assertContentEquals(innerBytes, LocalActionApprovalCodec.encode(inner))
        }
    }

    @Test fun aV3EnvelopeCannotOmitOrNullEitherMandatoryBindingOrAcceptUnknownExtensions() {
        each { _, replacement ->
            val bytes = LocalActionApprovalCodec.encode(replacement)
            val fields = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            for (name in listOf("currentRequestorAuthority", "replacementOrigin")) {
                val absent = JsonObject(fields.toMutableMap().apply { remove(name) }).toString()
                val nulled = JsonObject(fields.toMutableMap().apply { put(name, JsonNull) }).toString()
                assertFails { decode(replacement, absent.encodeToByteArray()) }
                assertFails { decode(replacement, nulled.encodeToByteArray()) }
            }
            val unknown = JsonObject(fields.toMutableMap().apply { put("unboundOrigin", JsonPrimitive(true)) }).toString()
            val wrongProfile = JsonObject(fields.toMutableMap().apply {
                put("schema", JsonPrimitive("ukkera.local-action-approval/v2"))
            }).toString()
            assertFails { decode(replacement, unknown.encodeToByteArray()) }
            assertFails { decode(replacement, wrongProfile.encodeToByteArray()) }
            assertContentEquals(bytes, LocalActionApprovalCodec.encode(replacement))
        }
    }

    @Test fun currentAuthorityAndOriginalBindingCannotBeRepresentedAsAnUnpairedAcceptingDefault() {
        each { original, replacement ->
            assertFails { copyReplacement(original, replacement, authority = null) }
            assertFails { copyReplacement(original, replacement, origin = null) }
            assertNotNull(replacement.currentRequestorAuthority)
            assertNotNull(replacement.replacementOrigin)
            assertEquals(original.requestorSessionId, replacement.requestorSessionId)
        }
    }

    private fun each(block: (ProtectedLocalActionApproval, ProtectedLocalActionApproval) -> Unit) {
        fixture.getValue("targets").jsonArray.forEachIndexed { index, element ->
            val target = element.jsonObject
            val supplier = base.getValue("sources").jsonObject.getValue("supplier").jsonObject
            val scope = BranchScope(carrier(1), DomainId(supplier.getValue("companyId").jsonPrimitive.content),
                DomainId(supplier.getValue("branchId").jsonPrimitive.content))
            val pointer = target.getValue("completeOriginalActionFixture").jsonObject.getValue("jsonPointer").jsonPrimitive.content
            val action = pointer.removePrefix("/").split('/').fold(base as JsonElement) { current, key ->
                if (current is JsonArray) current[key.toInt()] else current.jsonObject.getValue(key)
            }.jsonObject
            val requester = DomainId(target.getValue("originalRequesterId").jsonPrimitive.content)
            val approver = DomainId(target.getValue("originalApproverId").jsonPrimitive.content)
            val policy = Revision(target.getValue("pinnedRequesterPolicyRevision").jsonPrimitive.content.toLong())
            val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
                scope, ActionBinding(DomainId(action.getValue("deliveryId").jsonPrimitive.content), Revision(0),
                    PayloadDigest(action.getValue("frozenContentDigest").jsonObject.getValue("digestRef").jsonPrimitive.content),
                    OperationId(target.getValue("originalOperationId").jsonPrimitive.content), "fixture.asr.original"))
            // Clock, UUID-only issuer, revisions and digest carriers are inert syntax, not native proofs.
            val record = ActionApprovalRecord(carrier(100 + index), requester, approver, requirement, policy,
                Revision(7), Revision(7), MonotonicMoment(carrier(2), 2_000), 300_000, Revision(0), null)
            val original = ProtectedLocalActionApproval(record, carrier(3),
                DomainId(actors.getValue("historicalSessionR").jsonPrimitive.content), SessionGeneration(1),
                DomainId(actors.getValue("originalDeviceR").jsonPrimitive.content), Revision(2), Revision(4),
                carrier(4), Revision(5), Revision(4), LocalActionApprovalCodec.confirmationDigest(requester, policy, requirement),
                Revision(6), SessionGeneration(2), PayloadDigest("8".repeat(64)))
            val authority = ActionApprovalRequestorAuthority(carrier(20), SessionGeneration(3), original.requestorDeviceId,
                Revision(3), Revision(4))
            val digest = runtimeApprovalDigest(original)
            val origin = ActionApprovalReplacementOrigin(original.record.approvalId, digest,
                original.record.approvalId, digest, actionApprovalRetirementId(scope, original.record.approvalId),
                PayloadDigest("9".repeat(64)))
            val replacement = ProtectedLocalActionApproval(record.copy(approvalId = carrier(300 + index),
                issuedAt = MonotonicMoment(carrier(2), 350_000)), carrier(30), original.requestorSessionId,
                original.requestorGeneration, original.requestorDeviceId, original.requestorCredentialRevision,
                original.requestorAdmissionRevision, original.approverDeviceId, original.approverCredentialRevision,
                original.approverAdmissionRevision, original.confirmationDigest, original.approverAccountRevision,
                original.approverGeneration, original.issueRequestDigest, authority, origin)
            block(original, replacement)
        }
    }

    private fun copyReplacement(original: ProtectedLocalActionApproval, replacement: ProtectedLocalActionApproval,
        authority: ActionApprovalRequestorAuthority? = replacement.currentRequestorAuthority,
        origin: ActionApprovalReplacementOrigin? = replacement.replacementOrigin): ProtectedLocalActionApproval =
        ProtectedLocalActionApproval(replacement.record, replacement.issuerContextId, original.requestorSessionId,
            original.requestorGeneration, original.requestorDeviceId, original.requestorCredentialRevision,
            original.requestorAdmissionRevision, replacement.approverDeviceId, replacement.approverCredentialRevision,
            replacement.approverAdmissionRevision, replacement.confirmationDigest, replacement.approverAccountRevision,
            replacement.approverGeneration, replacement.issueRequestDigest, authority, origin)

    private fun decode(value: ProtectedLocalActionApproval, bytes: ByteArray): ProtectedLocalActionApproval =
        LocalActionApprovalCodec.decode(ProtectedLocalBinding(
            ProtectedLocalRecordKey.ActionApproval(scope(value), value.record.approvalId), value.record.revision), bytes)

    private fun scope(value: ProtectedLocalActionApproval): BranchScope = value.record.requirement.scope as BranchScope
    private fun carrier(value: Int): DomainId = DomainId("af100000-0000-4000-8000-${value.toString().padStart(12, '0')}")
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
