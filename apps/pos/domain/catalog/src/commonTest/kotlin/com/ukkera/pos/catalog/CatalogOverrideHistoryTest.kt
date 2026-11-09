package com.ukkera.pos.catalog

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class CatalogOverrideHistoryTest {
    private class Fixture {
        val company = CF.valid(CF.definition(policy = CF.policy(actions = listOf(CF.action()), overrides = true)))
        val one = CF.override()
        val two = one.copy(revision = Revision(2), unitPrice = UnitPrice.parse(Currency.EGP, "130"))
        val actions = listOf(CF.action(cid(510)))
        val current = CF.branchDefinition(company, revision = Revision(2), actions = actions,
            overrides = listOf(two), historicalOverrides = listOf(one))
        val retired = CF.branchDefinition(company, revision = Revision(3), actions = actions, historicalOverrides = listOf(one, two))
        val retiredPlan = CF.plan(company, retired, current)

        fun validate(next: BranchCatalogDefinition, previous: BranchCatalogDefinition? = retired): BranchCatalogValidation =
            BranchCatalogValidator.planActivation(company, next,
                CatalogPublicationEvidence.CompanyPublicationReference(CF.company, company.definition.revision, cid(900)),
                CatalogExecutionManifest(cid(901), CF.simpleCapabilities), CF.now, previous)
    }

    @Test fun removingThenReaddingLowerRevisionWithChangedTermsCannotResetTheHighWaterMark() {
        val f = Fixture()
        val replay = f.one.copy(unitPrice = UnitPrice.parse(Currency.EGP, "1"))
        val edit = assertIs<BranchOverrideEditAdmission.Rejected>(BranchPriceOverrideAdmission.checkEdit(f.retiredPlan, f.retired.revision, replay))
        assertTrue(BranchOverrideEditFailure.OVERRIDE_REVISION_CONFLICT in edit.reasons)
        // Moving historical r1 back to the current list does not authorize either its rewrite or rollback.
        val next = CF.branchDefinition(f.company, revision = Revision(4), actions = f.actions,
            overrides = listOf(replay), historicalOverrides = listOf(f.two))
        val activation = assertIs<BranchCatalogValidation.Rejected>(f.validate(next))
        assertTrue(activation.issues.any { it.code == CatalogIssueCode.VERSION_REWRITTEN })
        assertTrue(activation.issues.any { it.code == CatalogIssueCode.REVISION_REGRESSION })
    }

    @Test fun historicalAndLatestRetiredVersionsCannotBeRewrittenUnderAnExistingReference() {
        val f = Fixture()
        val rewrittenHistory = f.one.copy(unitPrice = UnitPrice.parse(Currency.EGP, "99"))
        val next = CF.branchDefinition(f.company, revision = Revision(3), actions = f.actions,
            overrides = listOf(f.two), historicalOverrides = listOf(rewrittenHistory))
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(f.validate(next, f.current)).issues.any { it.code == CatalogIssueCode.VERSION_REWRITTEN })
        // No lower revision here: this specifically exercises immutable same-version terms after removal.
        val rewrittenLatest = f.two.copy(unitPrice = UnitPrice.parse(Currency.EGP, "1"))
        val edit = assertIs<BranchOverrideEditAdmission.Rejected>(BranchPriceOverrideAdmission.checkEdit(f.retiredPlan, f.retired.revision, rewrittenLatest))
        assertTrue(BranchOverrideEditFailure.OVERRIDE_REVISION_CONFLICT in edit.reasons)
    }

    @Test fun retirementMustRetainThePreviousCurrentVersionAndAllOlderVersions() {
        val f = Fixture()
        val missingLatest = CF.branchDefinition(f.company, revision = Revision(3), actions = f.actions, historicalOverrides = listOf(f.one))
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(f.validate(missingLatest, f.current)).issues.any { it.code == CatalogIssueCode.HISTORY_RECORD_REMOVED })
        val missingOlder = CF.branchDefinition(f.company, revision = Revision(3), actions = f.actions, overrides = listOf(f.two))
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(f.validate(missingOlder, f.current)).issues.any { it.code == CatalogIssueCode.HISTORY_RECORD_REMOVED })
    }

    @Test fun identicalLatestRetiredOverrideCanBeExplicitlyReactivatedWithoutInventingARetirementBan() {
        val f = Fixture()
        assertEquals("100", CF.selected(f.retiredPlan).unitPrice.amount.canonicalString(), "Historical records are not implicitly selected")
        val ready = assertIs<BranchOverrideEditAdmission.ReadyForAuthorization>(BranchPriceOverrideAdmission.checkEdit(f.retiredPlan, f.retired.revision, f.two))
        assertEquals(f.two, ready.proposal)
        assertEquals(CF.overridePermission, ready.permission)
        assertEquals("100", CF.selected(f.retiredPlan).unitPrice.amount.canonicalString(), "Preflight never saves/authorizes reactivation")
        // Synthetic activated input only: the real gateway must authorize and commit this transition.
        val next = CF.branchDefinition(f.company, revision = Revision(4), actions = f.actions,
            overrides = listOf(f.two), historicalOverrides = listOf(f.one))
        val plan = assertIs<BranchCatalogValidation.Ready>(f.validate(next)).plan
        assertEquals("130", CF.selected(plan).unitPrice.amount.canonicalString())
        assertEquals(f.two.reference, CF.selected(plan).branchOverride)
    }

    @Test fun currentRevisionCannotFallBelowRetainedHistoryWithoutAnImmediateCurrentPredecessor() {
        val f = Fixture()
        val rollback = CF.branchDefinition(f.company, revision = Revision(4), actions = f.actions,
            overrides = listOf(f.one), historicalOverrides = listOf(f.two))
        val rejected = assertIs<BranchCatalogValidation.Rejected>(f.validate(rollback, previous = null))
        assertTrue(rejected.issues.any { it.code == CatalogIssueCode.REVISION_REGRESSION })
    }

    @Test fun historyRequiresCorrectScopeAndOneImmutableRecordPerReferenceAcrossBothLists() {
        val f = Fixture()
        val foreign = f.one.copy(scope = BranchScope(cid(1), cid(2), cid(99)))
        val wrongScope = CF.branchDefinition(f.company, historicalOverrides = listOf(foreign))
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(f.validate(wrongScope, previous = null)).issues.any { it.code == CatalogIssueCode.WRONG_SCOPE })
        val duplicate = CF.branchDefinition(f.company, overrides = listOf(f.two), historicalOverrides = listOf(f.two))
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(f.validate(duplicate, previous = null)).issues.any { it.code == CatalogIssueCode.DUPLICATE_IDENTITY })
    }

    @Test fun currentPlusHistoryCapacityIsCheckedBeforeCopyAndRetainedListsAreImmutable() {
        val f = Fixture()
        val history = mutableListOf(f.one)
        val definition = CF.branchDefinition(f.company, overrides = listOf(f.two), historicalOverrides = history)
        history.clear()
        assertEquals(listOf(f.one), definition.historicalOverrides)
        assertTrue(runCatching { (definition.historicalOverrides as MutableList<BranchPriceOverride>).clear() }.isFailure)
        val mustNotRead = object : AbstractList<BranchPriceOverride>() {
            override val size: Int = 1
            override fun get(index: Int): BranchPriceOverride = error("Must reject combined size before copying either history")
        }
        assertFailsWith<IllegalArgumentException> {
            BranchCatalogDefinition(CF.branch, Revision(3), f.company.definition.revision, f.current.policy,
                overrides = listOf(f.two), historicalOverrides = mustNotRead, bounds = CatalogBounds(maxPriceRules = 1))
        }
    }

    @Test fun historicalOverrideValidationConsumesTheExplicitWorkBudgetAndFailsClosed() {
        val catalog = CF.valid(CF.definition(bounds = CatalogBounds(maxValidationWork = 100)))
        val history = (0..100).map { CF.override().copy(id = cid(5_000 + it)) }
        val branch = CF.branchDefinition(catalog, historicalOverrides = history)
        val result = BranchCatalogValidator.planActivation(catalog, branch,
            CatalogPublicationEvidence.CompanyPublicationReference(CF.company, catalog.definition.revision, cid(900)),
            CatalogExecutionManifest(cid(901), CF.simpleCapabilities), CF.now)
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(result).issues.any { it.code == CatalogIssueCode.ENGINEERING_BOUND_EXCEEDED })
    }
}
