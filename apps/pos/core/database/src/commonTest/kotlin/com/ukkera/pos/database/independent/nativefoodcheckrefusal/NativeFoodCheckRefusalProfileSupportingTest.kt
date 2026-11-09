package com.ukkera.pos.database.independent.nativefoodcheckrefusal

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bounded slices of frozen native-food-check-refusal-profile/v1, NRFP-S-001–008.
 * A candidate, marker or represented tuple is never a full original/source qualification.
 * No native ownership, current authorization, recording, disclosure or restart is supplied. */
class NativeFoodCheckRefusalProfileSupportingTest {
    private val inputs = NativeFoodCheckRefusalSupportingInputs
    private val profile = RuntimeHistoricalFoodCheckRejectionProfile.FOOD

    @Test
    fun nrfpS001S002CandidateHasOnlyExactFoodV2KeyAndTwoClosedRefusalCodes() {
        assertEquals(CommandKey("sales.food.stock", 2), profile.command)
        assertEquals(setOf("food.stock.rule.duplicate_source_allocation", "food.stock.rule.revision_conflict"),
            profile.rejectionCodes)
        assertEquals(profile, RuntimeHistoricalFoodCheckRejectionProfile.fromCommand(profile.command))
        for (key in listOf(CommandKey("sales.food.stock", 1), CommandKey("sales.food.stock", 3),
            StockStreamingCommandsV2.STOCK, StockStreamingCommandsV2.RETURN)) {
            assertNull(RuntimeHistoricalFoodCheckRejectionProfile.fromCommand(key))
        }
    }

    @Test
    fun nrfpS003DraftCandidateRequiresCreationRevisionZeroAndOriginalCreatingActor() {
        val original = inputs.evidence()
        assertCandidate(original)
        assertEquals(SecurityAction.ORDER_CREATE, original.requirement.action)
        assertEquals(Revision(0), original.requirement.documentRevision)
        assertEquals(OwnerConstraint.OwnerPrivate(inputs.actor), original.requirement.value().owner)
        assertNotCandidate(original.copy(requirement = original.requirement.copy(documentRevision = Revision(1))))
        assertNotCandidate(original.copy(requirement = original.requirement.copy(ownerId = inputs.beforeOwner)))
        assertNotCandidate(original.copy(requirement = original.requirement.copy(ownerId = null)))
        for (action in SecurityAction.entries.filter {
            it != SecurityAction.ORDER_CREATE && it != SecurityAction.ORDER_EDIT
        }) assertNotCandidate(original.copy(requirement = original.requirement.copy(action = action)))
        // There is no ORDER storage row here; requirement revision0 is not a storage assertion.
    }

    @Test
    fun nrfpS004AddRepresentationKeepsRequestedRevisionAndSeparatelySuppliedBeforeOwner() {
        val original = inputs.evidence(add = true)
        assertCandidate(original)
        assertEquals(SecurityAction.ORDER_EDIT, original.requirement.action)
        assertEquals(inputs.requestedRevision, original.requirement.value().binding?.revision)
        assertEquals(OwnerConstraint.OwnerPrivate(inputs.beforeOwner), original.requirement.value().owner)
        assertNotEquals(inputs.actor, inputs.beforeOwner)
        assertNotCandidate(original.copy(requirement = original.requirement.copy(ownerId = null)))
        // Carrier data does not select or prove the genuine rejected-operation BEFORE row.
    }

    @Test
    fun nrfpS003FullDraftRequirementRepresentationDistinguishesEverySingleFieldMutation() {
        assertFullTupleMutations(inputs.requirement())
    }

    @Test
    fun nrfpS004FullAddRequirementRepresentationDistinguishesEverySingleFieldMutation() {
        assertFullTupleMutations(inputs.requirement(add = true))
    }

    @Test
    fun nrfpS005RequirementScopeOperationAndDigestCopiesCannotDriftIndependently() {
        for (add in listOf(false, true)) {
            val original = inputs.evidence(add)
            val scopes = listOf(inputs.scope.copy(organizationId = inputs.id(18)),
                inputs.scope.copy(companyId = inputs.id(19)), inputs.scope.copy(branchId = inputs.id(20)))
            for (scope in scopes) {
                assertNotCandidate(original.copy(requirement = original.requirement.copy(scope = scope)))
                assertNotCandidate(original.copy(authorization = original.authorization.copy(scope = scope)))
            }
            assertNotCandidate(original.copy(operationId = inputs.otherOperation))
            assertNotCandidate(original.copy(requirement = original.requirement.copy(operationId = inputs.otherOperation)))
            assertNotCandidate(original.copy(semanticDigest = inputs.otherDigest))
            assertNotCandidate(original.copy(requirement = original.requirement.copy(payloadDigest = inputs.otherDigest)))
            assertCandidate(original)
        }
    }

    @Test
    fun nrfpS005OtherPrincipalsPurposesVerbsReasonsAndApprovalLocatorsCannotSupplyCandidate() {
        for (add in listOf(false, true)) {
            val original = inputs.evidence(add)
            for (kind in PrincipalKind.entries.filter { it != PrincipalKind.LOCAL_EMPLOYEE }) {
                assertNotCandidate(original.copy(authorization = original.authorization.copy(principalKind = kind)))
            }
            for (purpose in AuthenticationPurpose.entries.filter { it != AuthenticationPurpose.INTERACTIVE }) {
                assertNotCandidate(original.copy(authorization = original.authorization.copy(authenticationPurpose = purpose)))
            }
            for (verb in PermissionVerb.entries.filter { it != PermissionVerb.PERFORM }) {
                assertNotCandidate(original.copy(requirement = original.requirement.copy(verb = verb)))
            }
            for (reason in listOf("", "food.stock.rule.revision_conflict", "food.operation.extra")) {
                assertNotCandidate(original.copy(requirement = original.requirement.copy(reasonCode = reason)))
            }
            assertNotCandidate(original.copy(actionApprovalId = inputs.id(21)))
        }
    }

    @Test
    fun nrfpS005AbsentMarkerKeepsCompatibleOldOriginalInert() {
        val header = inputs.header()
        val value = inputs.evidence()
        assertFalse(profile.hasMarker(header, value, byteArrayOf()))
        assertFalse(profile.hasMarker(header, value, inputs.dependencyRows(listOf(listOf("other.dependency", "data")))))
    }

    @Test
    fun nrfpS005MarkerCarriesExactOriginalOperationDigestAndNativeParentData() {
        for (add in listOf(false, true)) {
            val header = inputs.header()
            val value = inputs.evidence(add)
            val expected = listOf("food-check.scalar-refusal/v1", inputs.operation.value, inputs.digest.value,
                header.parentSequence.toString(), header.parentDigest.value)
            assertEquals(expected, profile.markerFields(header, value))
            assertTrue(profile.hasMarker(header, value, inputs.dependencyRows(listOf(expected))))
            // Presence is a dependency discriminator only, not a native/source pass.
        }
    }

    @Test
    fun nrfpS005DuplicateOrSingleFieldContradictoryMarkerIsExplicitMalformedEvidence() {
        val header = inputs.header()
        val value = inputs.evidence()
        val exact = profile.markerFields(header, value)
        val alternatives = listOf(inputs.otherOperation.value, inputs.otherDigest.value,
            (header.parentSequence + 1).toString(), inputs.otherDigest.value)
        for (index in 1..4) {
            val changed = exact.toMutableList().also { it[index] = alternatives[index - 1] }
            assertMalformed { profile.hasMarker(header, value, inputs.dependencyRows(listOf(changed))) }
        }
        assertMalformed { profile.hasMarker(header, value, inputs.dependencyRows(listOf(exact, exact))) }
        assertMalformed { profile.hasMarker(header, value, inputs.dependencyRows(listOf(exact.dropLast(1)))) }
        assertMalformed { profile.hasMarker(header, value, inputs.dependencyRows(listOf(exact + "extra"))) }
    }

    @Test
    fun nrfpS005MalformedDependencyFramingOrUtf8CannotBecomeEmptySuccessfulCut() {
        val header = inputs.header()
        val value = inputs.evidence()
        for (bytes in listOf(byteArrayOf(0), byteArrayOf(0, 0, 0), byteArrayOf(0, 0, 0, 2, 1),
            inputs.fields(listOf(inputs.fields(listOf(byteArrayOf(0xc3.toByte(), 0x28))))),
            inputs.fields(listOf(inputs.fields(listOf(byteArrayOf(0))))))) {
            assertMalformed { profile.hasMarker(header, value, bytes) }
        }
    }

    @Test
    fun nrfpS005InvalidEvidenceSchemaRemainsMalformedRatherThanAcceptingDefault() {
        val value = inputs.evidence()
        assertMalformed { RuntimeCommandAuthorizationEvidenceCodec.encode(value.copy(schema = "unsupported")) }
        assertMalformed { RuntimeCommandAuthorizationEvidenceCodec.decode("{}".encodeToByteArray()) }
    }

    @Test
    fun nrfpS007RejectCandidateCannotRelabelApplyOrAlterOriginalCaptureCensus() {
        val value = inputs.evidence()
        val header = inputs.header()
        assertFalse(profile.matches(header.copy(purpose = RuntimeWritePurposeV2.COMMAND_APPLY), value))
        assertNotCandidate(value.copy(purposeTag = RuntimeWritePurposeV2.COMMAND_APPLY.tag))
        for (count in listOf(RuntimeHistoricalFoodCheckRejectionProfile.CAPTURED_CHANGES - 1,
            RuntimeHistoricalFoodCheckRejectionProfile.CAPTURED_CHANGES + 1)) {
            assertFalse(profile.matches(header.copy(changeCount = count), value))
            assertNotCandidate(value.copy(capturedChanges = count))
        }
        // Candidate census is not a metadata row-count oracle or a zero-business-effect pass.
    }

    @Test
    fun nrfpS005S007FiniteRefusalCodesAloneSelectHoldWithoutCallingProposalOrAuthority() {
        for ((code, expected) in listOf("food.stock.rule.duplicate_source_allocation" to DeferredReason.CURRENT_SOURCE_UNAVAILABLE,
            "food.stock.rule.revision_conflict" to DeferredReason.CURRENT_SOURCE_UNAVAILABLE,
            "precondition.expired" to DeferredReason.PRECONDITION_EXPIRED)) {
            val prepared = MissingNativeSupport()
            assertEquals(expected, nativeStreamingRejectionAdmission(StockStreamingCommandsV2.FOOD,
                prepared, StreamedMutationDecision.Reject(code, StoredBytes(byteArrayOf()))))
            assertEquals(0, prepared.unexpectedCalls)
        }
        // This selects nonrecording continuation/hold only; no durable observer is supplied.
    }

    @Test
    fun nrfpS008AuthorizationEvidenceRoundTripPreservesFullOriginalDataNotCurrentGrants() {
        for (add in listOf(false, true)) {
            val original = inputs.evidence(add)
            val bytes = RuntimeCommandAuthorizationEvidenceCodec.encode(original)
            try {
                val decoded = RuntimeCommandAuthorizationEvidenceCodec.decode(bytes)
                assertEquals(original, decoded)
                assertEquals(original.requirement.value(), decoded.requirement.value())
                assertEquals(original.authorization, decoded.authorization)
                assertEquals(original.nativeRecordDigest, decoded.nativeRecordDigest)
                val reencoded = RuntimeCommandAuthorizationEvidenceCodec.encode(decoded)
                try { assertTrue(bytes.contentEquals(reencoded)) } finally { reencoded.fill(0) }
            } finally { bytes.fill(0) }
            // No current permission/disclosure evaluation or founding/enrolled proof is invoked.
        }
    }

    private fun assertFullTupleMutations(original: RuntimeCommandRequirementV1) {
        val expected = original.value()
        assertEquals(original, RuntimeCommandRequirementV1.capture(expected))
        val variants = listOf(
            original.copy(action = if (original.action == SecurityAction.ORDER_CREATE) SecurityAction.ORDER_EDIT else SecurityAction.ORDER_CREATE),
            original.copy(verb = PermissionVerb.entries.first { it != PermissionVerb.PERFORM }),
            original.copy(scope = inputs.scope.copy(branchId = inputs.id(22))),
            original.copy(documentId = inputs.otherOrder),
            original.copy(documentRevision = original.documentRevision.next()),
            original.copy(operationId = inputs.otherOperation),
            original.copy(payloadDigest = inputs.otherDigest),
            original.copy(reasonCode = "food.operation.changed"),
            original.copy(ownerId = if (original.ownerId == inputs.actor) inputs.beforeOwner else inputs.actor),
        )
        for (changed in variants) {
            assertNotEquals(expected, changed.value())
            assertEquals(changed, RuntimeCommandRequirementV1.capture(changed.value()))
        }
        // Full represented equality is exercised. Native requirement/source qualification is NOT_RUN.
    }

    private fun assertCandidate(value: RuntimeCommandAuthorizationEvidenceV1) =
        assertTrue(profile.matches(inputs.header(), value))

    private fun assertNotCandidate(value: RuntimeCommandAuthorizationEvidenceV1) =
        assertFalse(profile.matches(inputs.header(), value))

    private fun assertMalformed(block: () -> Unit) {
        assertEquals(RuntimeHistoryFormatFailureV2.MALFORMED,
            assertFailsWith<RuntimeHistoryFormatRejectedV2>(block = block).failure)
    }

    private class MissingNativeSupport : PreparedStreamingMutation() {
        var unexpectedCalls: Int = 0
            private set
        private fun unexpected(): Nothing { unexpectedCalls++; error("No native support or authority is bound") }
        override val requirement: AuthorizationRequirement get() = unexpected()
        override val affectedResources: Set<RecoveryResourceKey> get() = unexpected()
        override suspend fun propose(authorization: AuthorizedAction, recordedAt: UtcInstant,
            sink: StreamedCommandSink): StreamedMutationDecision = unexpected()
    }
}
