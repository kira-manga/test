package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Self-authored, NOT independent. Candidate DATA controls for the exact paired-device
 * NonSale refusal lane. These supply neither native admission nor historical authority;
 * the preserved genuine two-Client cash Master is reopened separately with its real keys. */
class AdditionalDeviceCashRejectionSupportingTest {
    private val inputs = CashClosingRejectionDataInputs
    private val profile = RuntimeHistoricalConfigurationRejectionProfile.CASH_CLOSING
    private fun nonSale(reason: String = "cash.non_sale.pay_in"): RuntimeCommandAuthorizationEvidenceV1 {
        val seed = inputs.evidence()
        return seed.copy(requirement = seed.requirement.copy(action = SecurityAction.CASH_MOVE,
            verb = PermissionVerb.PERFORM, reasonCode = reason, ownerId = inputs.employee,
            limitKind = "monetary", amount = Money(Currency.EGP, 11_400), baseAmount = null))
    }

    @Test fun exactNonSaleCandidatesDoNotEnableOtherCashPhasesOrProfiles() {
        for (reason in listOf("cash.non_sale.pay_in", "cash.operating_expense", "cash.inventory_settlement")) {
            val candidate = nonSale(reason)
            assertTrue(profile.matchesAdditionalDeviceNonSale(inputs.header(), candidate))
            for (other in RuntimeHistoricalConfigurationRejectionProfile.entries.filter { it != profile })
                assertFalse(other.matchesAdditionalDeviceNonSale(inputs.header(), candidate))
        }
        for (shape in inputs.shapes) {
            val closing = inputs.evidence(shape)
            assertTrue(profile.matches(inputs.header(), closing))
            assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header(), closing))
        }
    }

    @Test fun changedOwnerAmountVerbReasonAndCommandCannotSupplyTheCandidate() {
        val original = nonSale()
        val requirement = original.requirement
        val changes = listOf(requirement.copy(ownerId = null), requirement.copy(amount = Money(Currency.EGP, 0)),
            requirement.copy(baseAmount = Money(Currency.EGP, 1)), requirement.copy(limitKind = "none"),
            requirement.copy(verb = PermissionVerb.ADMINISTER), requirement.copy(action = SecurityAction.CASH_CLOSE),
            requirement.copy(reasonCode = "cash.move"), requirement.copy(reasonCode = "cash.non_sale.pay_in.extra"))
        for (changed in changes)
            assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header(), original.copy(requirement = changed)))
        assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header(), original.copy(commandVersion = 2)))
        assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header(), original.copy(commandName = "local.configuration.write")))
    }

    @Test fun approvalPurposeAndCaptureShapeCannotEnterAdditionalDeviceRefusalLane() {
        val original = nonSale()
        assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header(), original.copy(actionApprovalId = inputs.approvalLocator)))
        for (purpose in AuthenticationPurpose.entries.filter { it != AuthenticationPurpose.INTERACTIVE })
            assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header(),
                original.copy(authorization = original.authorization.copy(authenticationPurpose = purpose))))
        for (purpose in RuntimeWritePurposeV2.entries.filter { it != RuntimeWritePurposeV2.COMMAND_REJECT }) {
            assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header().copy(purpose = purpose), original))
            assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header(), original.copy(purposeTag = purpose.tag)))
        }
        assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header().copy(changeCount = 4), original))
        assertFalse(profile.matchesAdditionalDeviceNonSale(inputs.header(), original.copy(capturedChanges = 4)))
    }
}
