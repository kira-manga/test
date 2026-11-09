package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Bounded CCJR-v1 DATA slices. Matching a candidate never proves an original refusal,
 * a valid history, authority, five permitted mutations or any cash/native business result. */
class CashClosingRejectionCandidateSlicesTest {
    private val inputs = CashClosingRejectionDataInputs
    private val profile = RuntimeHistoricalConfigurationRejectionProfile.CASH_CLOSING

    @Test
    fun ccjr013AllSevenCountedPhasesAndLegacySubmitHaveDistinctRequirementData() {
        assertEquals(8, inputs.shapes.size)
        assertEquals(8, inputs.shapes.map { it.reason }.toSet().size)
        assertEquals(CommandKey("sales.cash.control", 1), profile.command)
        for (shape in inputs.shapes) {
            val value = inputs.evidence(shape)
            assertCandidate(value, shape.family)
            // These two strings are classifier data, not a native code binding for this case.
            assertTrue(profile.acceptsRejection(value, "sales.domain_rejected"), shape.family)
            assertTrue(profile.acceptsRejection(value, "sales.profile_unavailable"), shape.family)
        }
    }

    @Test
    fun ccjr013DifferentOuterCommandOrUnsupportedVersionCannotRelabelCashData() {
        val wrongKeys = listOf(
            CommandKey("sales.cash.control", 2),
            CommandKey("sales.cash.control.count", 1),
            CommandKey("sales.cash.control.review", 1),
            CommandKey("local.configuration.write", 1),
            CommandKey("purchasing.document.begin", 1),
        )
        assertEquals(profile, RuntimeHistoricalConfigurationRejectionProfile.fromCommand(
            CommandKey("sales.cash.control", 1)))
        for (shape in inputs.shapes) {
            val original = inputs.evidence(shape)
            for (key in wrongKeys) {
                assertNotEquals(profile, RuntimeHistoricalConfigurationRejectionProfile.fromCommand(key))
                assertNotCandidate(original.copy(commandName = key.name, commandVersion = key.version), shape.family)
            }
        }
    }

    @Test
    fun ccjr013SupportedAuthorizationEvidenceRoundTripPreservesRepresentedFields() {
        for (shape in inputs.shapes) {
            val original = inputs.evidence(shape)
            val bytes = RuntimeCommandAuthorizationEvidenceCodec.encode(original)
            try {
                val decoded = RuntimeCommandAuthorizationEvidenceCodec.decode(bytes)
                assertEquals(original, decoded, shape.family)
                assertEquals(shape.action, decoded.requirement.action, shape.family)
                assertEquals(shape.reason, decoded.requirement.reasonCode, shape.family)
                assertEquals(shape.amount, decoded.requirement.amount, shape.family)
                assertEquals(shape.owner, decoded.requirement.ownerId, shape.family)
                assertCandidate(decoded, shape.family)
            } finally { bytes.fill(0) }
        }
    }

    @Test
    fun ccjr014NonEmployeePrincipalKindsCannotSupplyAnEmployeeCandidate() {
        for (shape in inputs.shapes) {
            val original = inputs.evidence(shape)
            for (kind in PrincipalKind.entries.filter { it != PrincipalKind.LOCAL_EMPLOYEE }) {
                assertNotCandidate(original.copy(authorization = original.authorization.copy(principalKind = kind)),
                    "${shape.family}: $kind")
            }
        }
    }

    @Test
    fun ccjr014NonInteractiveAuthenticationCannotSupplyTheOriginalInteractiveShape() {
        for (shape in inputs.shapes) {
            val original = inputs.evidence(shape)
            for (purpose in AuthenticationPurpose.entries.filter { it != AuthenticationPurpose.INTERACTIVE }) {
                assertNotCandidate(original.copy(authorization = original.authorization.copy(authenticationPurpose = purpose)),
                    "${shape.family}: $purpose")
            }
        }
    }

    @Test
    fun ccjr014AnApprovalLocatorCannotReplaceTheDirectOriginalAuthorityShape() {
        for (shape in inputs.shapes) {
            assertNotCandidate(inputs.evidence(shape).copy(actionApprovalId = inputs.approvalLocator), shape.family)
        }
    }

    @Test
    fun ccjr015RequirementAndAuthorizationOrganizationCompanyAndBranchMustAgree() {
        val changedScopes = listOf(
            inputs.scope.copy(organizationId = inputs.otherOrganization),
            inputs.scope.copy(companyId = inputs.otherCompany),
            inputs.scope.copy(branchId = inputs.otherBranch),
        )
        for (shape in inputs.shapes) {
            val original = inputs.evidence(shape)
            for (changed in changedScopes) {
                assertNotCandidate(original.copy(requirement = original.requirement.copy(scope = changed)), shape.family)
                assertNotCandidate(original.copy(authorization = original.authorization.copy(scope = changed)), shape.family)
            }
        }
    }

    @Test
    fun ccjr015PrivateClosingCannotOmitOwnerAndReviewCannotBorrowPrivateOwner() {
        for (shape in inputs.shapes) {
            val original = inputs.evidence(shape)
            val expectedOwner = shape.owner?.let { OwnerConstraint.OwnerPrivate(it) } ?: OwnerConstraint.Unrestricted
            assertEquals(expectedOwner, original.requirement.value().owner, shape.family)
            val changedOwner = if (shape.owner == null) inputs.employee else null
            assertNotCandidate(original.copy(requirement = original.requirement.copy(ownerId = changedOwner)), shape.family)
        }
        // Actual D1/S1/T1 custodian identity, actor substitution and FND origin are UNBOUND.
    }

    @Test
    fun ccjr016AnotherAllowedCashActionCannotReplaceTheRequiredAction() {
        val alternatives = listOf(SecurityAction.CASH_CLOSE, SecurityAction.CASH_TRANSFER,
            SecurityAction.CASH_RECEIVE, SecurityAction.CASH_REVIEW, SecurityAction.CASH_MOVE,
            SecurityAction.CASH_READ, SecurityAction.CASH_CONFIGURE)
        for (shape in inputs.shapes) {
            val original = inputs.evidence(shape)
            for (action in alternatives.filter { it != shape.action }) {
                assertNotCandidate(original.copy(requirement = original.requirement.copy(action = action)),
                    "${shape.family}: $action")
            }
        }
    }

    @Test
    fun ccjr016WrongVerbOrUnknownReasonCannotSupplyTheRequiredActionBinding() {
        val unknownReasons = listOf("", "cash.close", "cash.close.unknown", "cash.close.count.extra",
            "cash.close.submit_counted", "cash.safe.receive.extra", "Cash.close.review")
        for (shape in inputs.shapes) {
            val original = inputs.evidence(shape)
            for (verb in PermissionVerb.entries.filter { it != PermissionVerb.PERFORM }) {
                assertNotCandidate(original.copy(requirement = original.requirement.copy(verb = verb)), shape.family)
            }
            for (reason in unknownReasons) {
                assertNotCandidate(original.copy(requirement = original.requirement.copy(reasonCode = reason)), shape.family)
            }
        }
    }

    @Test
    fun ccjr016OperationAndSemanticDigestCopiesCannotDriftIndependently() {
        for (shape in inputs.shapes) {
            val original = inputs.evidence(shape)
            val variants = listOf(
                original.copy(operationId = inputs.otherOperation),
                original.copy(requirement = original.requirement.copy(operationId = inputs.otherOperation)),
                original.copy(semanticDigest = inputs.otherDigest),
                original.copy(requirement = original.requirement.copy(payloadDigest = inputs.otherDigest)),
            )
            variants.forEach { assertNotCandidate(it, shape.family) }
            assertCandidate(original, shape.family)
        }
    }

    @Test
    fun ccjr017FrozenCashAmountsAndRepresentedLimitsKeepCountDistinctFromExpectedCash() {
        val expected = inputs.opening + inputs.received - inputs.changeGiven - inputs.refundsPaid +
            inputs.payIn - inputs.payOut - inputs.safeDrop
        assertEquals(inputs.money(235_000), expected)
        assertEquals(inputs.money(500), inputs.firstCount - expected)
        assertEquals(inputs.money(1_000), inputs.finalCount - expected)
        assertEquals(inputs.money(186_000), inputs.finalCount - inputs.retainedFloat)
        assertNotEquals(expected, inputs.finalCount)
        assertNotEquals(expected + inputs.externalTender, expected)
        for (shape in inputs.shapes) {
            val value = inputs.evidence(shape).requirement.value()
            val expectedLimit = shape.amount?.let { LimitUse.Monetary(it) } ?: LimitUse.NotApplicable
            assertEquals(expectedLimit, value.limitUse, shape.family)
        }
        // This is integer/requirement DATA, not actual count, transfer or policy-limit proof.
    }

    @Test
    fun ccjr017MonetaryCountDeliveryAndReceiptRejectMissingNegativeOrOtherLimitShapes() {
        for (shape in inputs.shapes.filter { it.amount != null }) {
            val original = inputs.evidence(shape)
            val requirement = original.requirement
            val variants = listOf(
                requirement.copy(amount = null),
                requirement.copy(amount = inputs.money(-1)),
                requirement.copy(limitKind = "none"),
                requirement.copy(limitKind = "proportional", baseAmount = inputs.finalCount),
                requirement.copy(baseAmount = inputs.retainedFloat),
                requirement.copy(limitKind = "amount"),
                requirement.copy(limitKind = ""),
            )
            variants.forEach { assertNotCandidate(original.copy(requirement = it), shape.family) }
        }
    }

    @Test
    fun ccjr017BeginCancelAndReviewCannotAcquireAnAmountOrProportionalBase() {
        for (shape in inputs.shapes.filter { it.amount == null }) {
            val original = inputs.evidence(shape)
            val requirement = original.requirement
            val variants = listOf(
                requirement.copy(amount = inputs.finalCount),
                requirement.copy(baseAmount = inputs.finalCount),
                requirement.copy(limitKind = "monetary", amount = inputs.finalCount),
                requirement.copy(limitKind = "proportional", amount = inputs.finalCount, baseAmount = inputs.finalCount),
                requirement.copy(limitKind = "unknown"),
            )
            variants.forEach { assertNotCandidate(original.copy(requirement = it), shape.family) }
        }
    }

    @Test
    fun ccjr017UnsupportedCurrencyAndMissingOrNoncanonicalMoneyFieldsFailDataDecoding() {
        val original = inputs.evidence(inputs.shapes.single { it.family == "RecordClosingCount" })
        val bytes = RuntimeCommandAuthorizationEvidenceCodec.encode(original)
        try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            assertEquals(original, RuntimeCommandAuthorizationEvidenceCodec.decode(bytes))
            val replacements = listOf(
                "\"currency\":\"EGP\"" to "\"currency\":\"USD\"",
                "\"amount\":{\"currency\":\"EGP\",\"minor\":\"236000\"}" to "\"amount\":null",
                "\"limitKind\":\"monetary\"," to "",
                "\"minor\":\"236000\"" to "\"minor\":236000",
            )
            for ((from, to) in replacements) {
                assertTrue(from in text, "Selected evidence field must exist before tampering")
                val changed = text.replace(from, to).encodeToByteArray()
                try {
                    assertFailsWith<RuntimeHistoryFormatRejectedV2> {
                        RuntimeCommandAuthorizationEvidenceCodec.decode(changed)
                    }
                } finally { changed.fill(0) }
            }
        } finally { bytes.fill(0) }
    }

    @Test
    fun ccjr018HeaderAndEvidenceMustBothHaveRejectionPurpose() {
        val original = inputs.evidence()
        val header = inputs.header()
        assertTrue(profile.matches(header, original))
        for (purpose in RuntimeWritePurposeV2.entries.filter { it != RuntimeWritePurposeV2.COMMAND_REJECT }) {
            assertFalse(profile.matches(header.copy(purpose = purpose), original), "Header: $purpose")
            assertFalse(profile.matches(header, original.copy(purposeTag = purpose.tag)), "Evidence: $purpose")
        }
    }

    @Test
    fun ccjr018FiveChangesAreOnlyACandidateCountAndBothCopiesMustMatchIt() {
        val original = inputs.evidence()
        val header = inputs.header()
        assertTrue(profile.matches(header, original))
        for (count in listOf(0, 1, 2, 3, 4, 6)) {
            assertFalse(profile.matches(header.copy(changeCount = count), original), "Header: $count")
            assertFalse(profile.matches(header, original.copy(capturedChanges = count)), "Evidence: $count")
            assertFalse(profile.matches(header.copy(changeCount = count), original.copy(capturedChanges = count)),
                "Both: $count")
        }
        assertFalse(profile.matches(header, original.copy(capturedChanges = -1)))
        assertFailsWith<RuntimeHistoryFormatRejectedV2> { header.copy(changeCount = -1) }
        // No row images or jobs exist here; a five-change shape does not prove zero cash effects.
    }

    @Test
    fun ccjr015CashOwnerExceptionDoesNotWeakenAnyExistingNoncashOwnerNullGate() {
        val controls = inputs.nonCashOwnerNullCandidates()
        assertEquals(10, controls.size)
        for ((otherProfile, control) in controls) {
            assertEquals(null, control.requirement.ownerId)
            assertTrue(otherProfile.matches(inputs.header(), control), "$otherProfile owner-null control")
            assertFalse(otherProfile.matches(inputs.header(), control.copy(
                requirement = control.requirement.copy(ownerId = inputs.employee))), "$otherProfile private-owner tamper")
            assertTrue(otherProfile.matches(inputs.header(), control), "$otherProfile unchanged control")
        }
    }

    private fun assertCandidate(value: RuntimeCommandAuthorizationEvidenceV1, label: String) {
        assertTrue(profile.matches(inputs.header(), value), label)
    }

    private fun assertNotCandidate(value: RuntimeCommandAuthorizationEvidenceV1, label: String) {
        assertFalse(profile.matches(inputs.header(), value), label)
        assertFalse(profile.acceptsRejection(value, "sales.domain_rejected"), label)
    }
}
