package com.ukkera.pos.catalog

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class CatalogActivationPolicyTest {
    private fun validate(
        catalog: ValidatedCompanyCatalog,
        branch: BranchCatalogDefinition = CF.branchDefinition(catalog),
        publication: CatalogPublicationEvidence = CatalogPublicationEvidence.CompanyPublicationReference(CF.company, catalog.definition.revision, cid(900)),
        supported: List<CatalogExecutionCapability> = branch.policy.enabledCapabilities,
        previous: BranchCatalogDefinition? = null,
    ): BranchCatalogValidation = BranchCatalogValidator.planActivation(catalog, branch, publication, CatalogExecutionManifest(cid(901), supported), CF.now, previous)

    @Test fun catalogMetadataDoesNotQualifyStockExecutionOrSilentlyEnableACapability() {
        val catalog = CF.valid(CF.definition())
        val absentExecution = assertIs<BranchCatalogValidation.Rejected>(validate(catalog, supported = listOf(CatalogExecutionCapability.BASE_PRICE_SELECTION)))
        assertTrue(absentExecution.issues.any { it.code == CatalogIssueCode.EXECUTION_UNSUPPORTED })
        val branchDisabled = CF.branchDefinition(catalog, capabilities = listOf(CatalogExecutionCapability.BASE_PRICE_SELECTION))
        val notEnabled = assertIs<BranchCatalogValidation.Rejected>(validate(catalog, branchDisabled, supported = CF.simpleCapabilities))
        assertTrue(notEnabled.issues.any { it.code == CatalogIssueCode.CAPABILITY_NOT_ENABLED })
    }

    @Test fun purchasableMetadataDoesNotRequireUnrelatedOptionalPurchasingForRoutineCheckout() {
        val catalog = CF.valid(CF.definition())
        assertTrue(catalog.definition.skus.single().capabilities.canPurchase)
        val ready = assertIs<BranchCatalogValidation.Ready>(validate(catalog))
        assertTrue(CatalogExecutionCapability.PURCHASING !in ready.plan.requiredCapabilities)
        assertEquals("100", CF.selected(ready.plan).unitPrice.amount.canonicalString())
        val explicit = CF.branchDefinition(catalog, capabilities = CF.simpleCapabilities + CatalogExecutionCapability.PURCHASING)
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(validate(catalog, explicit, supported = CF.simpleCapabilities)).issues.any { it.code == CatalogIssueCode.EXECUTION_UNSUPPORTED })
    }

    @Test fun nonStockServiceDefinitionIsNotAnImplementedCompletionOrRefundWorkflow() {
        val service = CF.sku(capabilities = CF.retail.copy(canPurchase = false, tracking = StockTracking.NONE,
            fulfillment = FulfillmentKind.SERVICE, serviceCharging = ServiceCharging.FIXED))
        val catalog = CF.valid(CF.definition(skus = listOf(service)))
        val branch = CF.branchDefinition(catalog, capabilities = listOf(CatalogExecutionCapability.BASE_PRICE_SELECTION, CatalogExecutionCapability.NON_STOCK_SERVICE_COMPLETION))
        val failure = assertIs<BranchCatalogValidation.Rejected>(validate(catalog, branch, supported = listOf(CatalogExecutionCapability.BASE_PRICE_SELECTION)))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.EXECUTION_UNSUPPORTED })
    }

    @Test fun containerDepositRequiresExplicitReceiptLinkedLiabilityAndExecutionSupport() {
        val deposit = ContainerDepositDefinition(CatalogVersionRef(cid(800), Revision(1)), Money(Currency.EGP, 500), cid(801), CF.tax.id, CatalogTaxInclusion.EXCLUSIVE)
        val container = CF.sku(capabilities = CF.retail.copy(container = ContainerCapability(deposit)))
        val catalog = CF.valid(CF.definition(skus = listOf(container)))
        val capabilities = CF.simpleCapabilities + listOf(CatalogExecutionCapability.REUSABLE_CONTAINER_MOVEMENT, CatalogExecutionCapability.RECEIPT_LINKED_DEPOSIT)
        val branch = CF.branchDefinition(catalog, capabilities = capabilities)
        val noRuntime = assertIs<BranchCatalogValidation.Rejected>(validate(catalog, branch, supported = CF.simpleCapabilities))
        assertTrue(noRuntime.issues.count { it.code == CatalogIssueCode.EXECUTION_UNSUPPORTED } >= 2)
        assertEquals(500L, catalog.definition.skus.single().capabilities.container?.deposit?.amountPerContainer?.minor)
    }

    @Test fun localUniquenessCheckDoesNotMakeOfflineCodesCompanyPublished() {
        val catalog = CF.valid(CF.definition())
        val pending = assertIs<BranchCatalogValidation.Rejected>(validate(catalog, publication = CatalogPublicationEvidence.PendingCompanyPublication))
        assertTrue(pending.issues.any { it.code == CatalogIssueCode.PUBLICATION_REQUIRED })
        val wrongRevision = CatalogPublicationEvidence.CompanyPublicationReference(CF.company, Revision(99), cid(900))
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(validate(catalog, publication = wrongRevision)).issues.any { it.code == CatalogIssueCode.STALE_COMPANY_BINDING })
    }

    @Test fun branchCompanyCatalogAndPolicyBindingsAreExactAndTenantScoped() {
        val catalog = CF.valid(CF.definition())
        val original = CF.branchDefinition(catalog)
        val stale = BranchCatalogDefinition(original.scope, original.revision, Revision(0), original.policy)
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(validate(catalog, stale)).issues.any { it.code == CatalogIssueCode.STALE_COMPANY_BINDING })
        val otherBranch = BranchScope(cid(1), cid(99), cid(3))
        val foreign = BranchCatalogDefinition(otherBranch, Revision(1), catalog.definition.revision,
            BranchCatalogPolicy(otherBranch, cid(5), Revision(1), catalog.definition.policy.reference, listOf(cid(200)), CF.simpleCapabilities))
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(validate(catalog, foreign)).issues.any { it.code == CatalogIssueCode.WRONG_SCOPE })
    }

    @Test fun staleBranchRevisionsAndInPlaceDefinitionRewritesAreRejected() {
        val catalog = CF.valid(CF.definition())
        val previous = CF.branchDefinition(catalog, revision = Revision(2))
        val rollback = CF.branchDefinition(catalog, revision = Revision(1))
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(validate(catalog, rollback, previous = previous)).issues.any { it.code == CatalogIssueCode.REVISION_REGRESSION })
        val rewritten = CF.branchDefinition(catalog, revision = Revision(2), assortment = emptyList())
        assertTrue(assertIs<BranchCatalogValidation.Rejected>(validate(catalog, rewritten, previous = previous)).issues.any { it.code == CatalogIssueCode.VERSION_REWRITTEN })
    }

    @Test fun everyEnabledSaleUnitNeedsAnActualStandardFallbackRatherThanAMissingPriceDefault() {
        val catalog = CF.valid(CF.definition(prices = emptyList(), activePrices = emptyList()))
        val failure = assertIs<BranchCatalogValidation.Rejected>(validate(catalog))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.STANDARD_PRICE_REQUIRED })
    }

    @Test fun missingOptionalApprovalFieldsDoNotBlockCheckoutButFailTheRequestedOverrideExplicitly() {
        val missing = CF.action().copy(grantReferenceId = null, monetaryCeiling = MonetaryCeilingDeclaration.Missing, maximumPercentageBasisPoints = null)
        val catalog = CF.valid(CF.definition(policy = CF.policy(actions = listOf(missing), overrides = true)))
        val branch = CF.branchDefinition(catalog, actions = listOf(missing.copy(id = cid(510))))
        val plan = CF.plan(catalog, branch)
        assertEquals("100", CF.selected(plan).unitPrice.amount.canonicalString())
        val denied = assertIs<BranchOverrideEditAdmission.Rejected>(BranchPriceOverrideAdmission.checkEdit(plan, branch.revision, CF.override()))
        assertTrue(denied.companyPolicyIssues.containsAll(listOf(PolicyConfigurationIssue.GRANT_REFERENCE_MISSING,
            PolicyConfigurationIssue.MONETARY_CEILING_MISSING, PolicyConfigurationIssue.PERCENTAGE_CEILING_MISSING)))
        assertEquals(denied.companyPolicyIssues, denied.branchPolicyIssues)
    }

    @Test fun missingDeclarationAndZeroCeilingsAreDifferentFromUnlimited() {
        assertEquals(listOf(PolicyConfigurationIssue.DECLARATION_MISSING), CatalogPolicyConfiguration.issues(null))
        val zero = CF.action().copy(monetaryCeiling = MonetaryCeilingDeclaration.Limited(Money(Currency.EGP, 0)), maximumPercentageBasisPoints = 0)
        assertTrue(CatalogPolicyConfiguration.issues(zero).isEmpty())
        val mapped = assertIs<SecurityLimitMapping.Bounded>(CatalogPolicyConfiguration.mapToSecurityLimits(zero))
        assertEquals(0L, mapped.limits.maximumAmount?.minor)
        assertEquals(0, mapped.limits.maximumPercentageBasisPoints)
        val grantOnly = CatalogActionDeclaration(cid(510), Permission(SecurityAction.ORDER_CREATE, PermissionVerb.PERFORM), cid(511))
        assertTrue(CatalogPolicyConfiguration.issues(grantOnly).isEmpty(), "Existing security action requirements decide which numeric fields are mandatory")
    }

    @Test fun explicitNoMonetaryCeilingIsACompleteDefinitionButNotAnUnsupportedRuntimeBypass() {
        val unlimited = CF.action().copy(monetaryCeiling = MonetaryCeilingDeclaration.ExplicitNoCeiling)
        assertTrue(CatalogPolicyConfiguration.issues(unlimited).isEmpty())
        val mapping = assertIs<SecurityLimitMapping.Unavailable>(CatalogPolicyConfiguration.mapToSecurityLimits(unlimited))
        assertEquals(listOf(PolicyConfigurationIssue.EXPLICIT_NO_CEILING_EXECUTION_UNSUPPORTED), mapping.issues)
        val catalog = CF.valid(CF.definition(policy = CF.policy(actions = listOf(unlimited), overrides = true)))
        val branch = CF.branchDefinition(catalog, actions = listOf(unlimited.copy(id = cid(510))))
        val plan = CF.plan(catalog, branch)
        assertEquals("100", CF.selected(plan).unitPrice.amount.canonicalString())
        val denied = assertIs<BranchOverrideEditAdmission.Rejected>(BranchPriceOverrideAdmission.checkEdit(plan, branch.revision, CF.override()))
        assertTrue(BranchOverrideEditFailure.UNLIMITED_EXECUTION_UNSUPPORTED in denied.reasons)
    }

    @Test fun noLimitsFromUnrelatedDeclarationsAreCombinedToMakeACompleteAction() {
        val amountOnly = CF.action().copy(maximumPercentageBasisPoints = null)
        val percentageOnly = CF.action(cid(510)).copy(monetaryCeiling = MonetaryCeilingDeclaration.Missing)
        assertTrue(CatalogPolicyConfiguration.issues(amountOnly).contains(PolicyConfigurationIssue.PERCENTAGE_CEILING_MISSING))
        assertTrue(CatalogPolicyConfiguration.issues(percentageOnly).contains(PolicyConfigurationIssue.MONETARY_CEILING_MISSING))
        assertIs<SecurityLimitMapping.Unavailable>(CatalogPolicyConfiguration.mapToSecurityLimits(amountOnly))
        assertIs<SecurityLimitMapping.Unavailable>(CatalogPolicyConfiguration.mapToSecurityLimits(percentageOnly))
    }

    @Test fun duplicateCurrentOverridesCannotBeChosenByNewestRevisionOrDatabaseOrder() {
        val catalog = CF.valid(CF.definition(policy = CF.policy(actions = listOf(CF.action()), overrides = true)))
        val first = CF.override()
        val second = first.copy(id = cid(401), revision = Revision(99), unitPrice = UnitPrice.parse(Currency.EGP, "130"))
        val failure = assertIs<BranchCatalogValidation.Rejected>(validate(catalog, CF.branchDefinition(catalog, overrides = listOf(first, second))))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.OVERRIDE_AMBIGUITY })
        val branch = CF.branchDefinition(catalog, actions = listOf(CF.action(cid(510))), overrides = listOf(first))
        val plan = CF.plan(catalog, branch)
        val overlapping = assertIs<BranchOverrideEditAdmission.Rejected>(BranchPriceOverrideAdmission.checkEdit(plan, branch.revision, second))
        assertTrue(BranchOverrideEditFailure.OVERLAPPING_OVERRIDE in overlapping.reasons)
        val staleWriter = assertIs<BranchOverrideEditAdmission.Rejected>(BranchPriceOverrideAdmission.checkEdit(plan, Revision(0), first))
        assertTrue(BranchOverrideEditFailure.STALE_BRANCH_REVISION in staleWriter.reasons)
    }

    @Test fun supersededOfflineOverridesStayInertAndDoNotBlockUnrelatedRoutineCheckout() {
        val r5 = CF.price()
        val r6 = r5.copy(revision = Revision(6), terms = r5.terms.copy(unitPrice = UnitPrice.parse(Currency.EGP, "150")))
        val catalog = CF.valid(CF.definition(policy = CF.policy(overrides = true), prices = listOf(r5, r6), activePrices = listOf(r6.reference)))
        val stale1 = CF.override()
        val stale2 = stale1.copy(id = cid(401), revision = Revision(99), unitPrice = UnitPrice.parse(Currency.EGP, "1"))
        val plan = CF.plan(catalog, CF.branchDefinition(catalog, overrides = listOf(stale2, stale1)))
        val preview = CatalogPriceSelector.preview(plan, CF.query())
        assertEquals("150", assertIs<CatalogPriceOutcome.Selected>(preview.outcome).price.unitPrice.amount.canonicalString())
        assertTrue(preview.overrides.all { it.disposition == BranchOverrideDisposition.SUPERSEDED_COMPANY_REVISION })
    }

    @Test fun explicitCompanyOverrideDisablementWinsWithoutReadingUnrelatedOptionalLimits() {
        val catalog = CF.valid(CF.definition())
        val plan = CF.plan(catalog, CF.branchDefinition(catalog, overrides = listOf(CF.override())))
        val preview = CatalogPriceSelector.preview(plan, CF.query())
        assertEquals("100", assertIs<CatalogPriceOutcome.Selected>(preview.outcome).price.unitPrice.amount.canonicalString())
        assertEquals(BranchOverrideDisposition.OVERRIDES_DISABLED_BY_COMPANY, preview.overrides.single().disposition)
    }
}
