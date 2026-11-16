package com.ukkera.pos.database

import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Self-authored, NOT independent. Supporting candidate DATA only, linked to frozen
 * LAN-A07/D04/D07 and unchanged CCJR-013–018 authority/identity/refusal requirements.
 * Existing inert CCJR data supplies no native source or authority. Full native acceptance
 * is exercised separately by LanBusinessTransactionIntegrationTest. */
class CashNonSaleRejectionCandidateSupportingTest {
    private val inputs = CashClosingRejectionDataInputs
    private val profile = RuntimeHistoricalConfigurationRejectionProfile.CASH_CLOSING
    private fun data(reason: String): RuntimeCommandAuthorizationEvidenceV1 {
        val seed = inputs.evidence()
        return seed.copy(requirement = seed.requirement.copy(action = SecurityAction.CASH_MOVE,
            reasonCode = reason, ownerId = inputs.employee, limitKind = "monetary", amount = Money(Currency.EGP, 11_400),
            baseAmount = null))
    }

    @Test fun onlyExactNonSaleReasonsWithOriginalOwnerPositiveAmountAndPerformAreCandidates() {
        for (reason in listOf("cash.non_sale.pay_in", "cash.operating_expense", "cash.inventory_settlement")) {
            val actual = data(reason)
            assertTrue(profile.matches(inputs.header(), actual))
            assertTrue(profile.acceptsRejection(actual, "sales.domain_rejected"))
            assertTrue(profile.acceptsRejection(actual, "sales.profile_unavailable"))
            val requirement = actual.requirement
            val variants = listOf(requirement.copy(ownerId = null), requirement.copy(amount = Money(Currency.EGP, 0)),
                requirement.copy(amount = Money(Currency.EGP, -1)), requirement.copy(limitKind = "none"),
                requirement.copy(baseAmount = Money(Currency.EGP, 1)), requirement.copy(verb = PermissionVerb.ADMINISTER),
                requirement.copy(action = SecurityAction.CASH_CLOSE), requirement.copy(reasonCode = "$reason.extra"))
            for (changed in variants) {
                assertFalse(profile.matches(inputs.header(), actual.copy(requirement = changed)))
                assertFalse(profile.acceptsRejection(actual.copy(requirement = changed), "sales.domain_rejected"))
            }
            assertFalse(profile.acceptsRejection(actual, "sales.stock.rule_rejected"))
            assertFalse(profile.acceptsRejection(actual, "committed"))
            assertFalse(profile.matches(inputs.header(), actual.copy(commandVersion = 2)))
            assertFalse(profile.matches(inputs.header(), actual.copy(actionApprovalId = inputs.approvalLocator)))
        }
        assertFalse(profile.matches(inputs.header(), data("cash.move")))
        assertFalse(profile.matches(inputs.header(), data("cash.close")))
    }
}
