package com.ukkera.pos.catalog

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlin.test.assertIs

internal fun cid(number: Int): DomainId = DomainId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

internal object CF {
    val company = CompanyScope(cid(1), cid(2))
    val branch = BranchScope(cid(1), cid(2), cid(3))
    val each = CatalogUnit(cid(10), Code.exact("each"), UnitDimension.COUNT, 6)
    val box = CatalogUnit(cid(11), Code.exact("box"), UnitDimension.COUNT, 6)
    val kg = CatalogUnit(cid(12), Code.exact("kg"), UnitDimension.MASS, 6)
    val litre = CatalogUnit(cid(13), Code.exact("litre"), UnitDimension.VOLUME, 6)
    val metre = CatalogUnit(cid(14), Code.exact("metre"), UnitDimension.LENGTH, 6)
    val tax = CatalogTaxCategory(cid(20), Code.exact("ordinary"))
    val standard = CatalogPriceContext(cid(30), Revision(1), Code.exact("standard"), PriceContextKind.STANDARD)
    val channelContext = CatalogPriceContext(cid(31), Revision(1), Code.exact("delivery"), PriceContextKind.CHANNEL)
    val tierContext = CatalogPriceContext(cid(32), Revision(1), Code.exact("quantity"), PriceContextKind.QUANTITY_TIER)
    val category = CatalogCategory(cid(40), Code.exact("retail"))
    val takeaway = Code.exact("takeaway")
    val dineIn = Code.exact("dine-in")
    val delivery = Code.exact("delivery")
    val start = UtcInstant.parseCanonical("2026-01-01T00:00:00Z")
    val now = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    val retail = SkuCapabilities(canPurchase = true, canSell = true, canProduce = false, tracking = StockTracking.QUANTITY)
    val simpleCapabilities = listOf(CatalogExecutionCapability.BASE_PRICE_SELECTION, CatalogExecutionCapability.STOCKED_RETAIL)
    val overridePermission = Permission(SecurityAction.PRICE_OVERRIDE, PermissionVerb.PERFORM)

    fun product(id: DomainId = cid(100)): CatalogProduct =
        CatalogProduct(id, Revision(1), Code.exact("Product-${id.value}"), listOf(category.id))

    fun sku(
        id: DomainId = cid(200),
        productId: DomainId = cid(100),
        capabilities: SkuCapabilities = retail,
    ): CatalogSku = CatalogSku(id, Revision(1), productId, Code.exact("SKU-${id.value}"), each.id, 0, capabilities)

    fun action(id: DomainId = cid(500)): CatalogActionDeclaration = CatalogActionDeclaration(
        id, overridePermission, cid(501), MonetaryCeilingDeclaration.Limited(Money(Currency.EGP, 100_000)), 2_000,
    )

    fun policy(
        revision: Revision = Revision(1),
        contexts: List<CatalogVersionRef> = listOf(standard.reference),
        channelPrices: List<Code> = emptyList(),
        actions: List<CatalogActionDeclaration> = emptyList(),
        overrides: Boolean = false,
    ): CompanyCatalogPolicy = CompanyCatalogPolicy(
        company, cid(4), revision, PriceSelectionProfile.PRIORITY_FIRST_V1, standard.reference, contexts,
        listOf(takeaway, dineIn, delivery), channelPrices, overrides, actions,
    )

    fun price(
        id: DomainId = cid(300),
        revision: Revision = Revision(5),
        amount: String = "100",
        target: CatalogPriceTarget = CatalogPriceTarget.Sku(cid(200)),
        context: CatalogVersionRef = standard.reference,
        priority: Int = 0,
        channel: Code? = null,
        quantity: CatalogPriceQuantityRange = CatalogPriceQuantityRange(Quantity(each.id, 0, 0)),
        period: CatalogEffectivePeriod = CatalogEffectivePeriod(start),
    ): CompanyPriceRule = CompanyPriceRule(
        id, revision, context, target, priority, channel, quantity, period,
        CatalogPriceTerms(UnitPrice.parse(Currency.EGP, amount), CatalogTaxInclusion.INCLUSIVE, tax.id),
    )

    fun definition(
        revision: Revision = Revision(1),
        policy: CompanyCatalogPolicy = policy(),
        units: List<CatalogUnit> = listOf(each),
        products: List<CatalogProduct> = listOf(product()),
        skus: List<CatalogSku> = listOf(sku()),
        contexts: List<CatalogPriceContext> = listOf(standard),
        prices: List<CompanyPriceRule> = listOf(price()),
        activePrices: List<CatalogVersionRef> = prices.map { it.reference },
        categories: List<CatalogCategory> = listOf(category),
        taxCategories: List<CatalogTaxCategory> = listOf(tax),
        namespaces: List<CatalogBarcodeNamespace> = emptyList(),
        barcodes: List<CatalogBarcodeBinding> = emptyList(),
        conversions: List<ProductUnitConversion> = emptyList(),
        compositions: List<CatalogComposition> = emptyList(),
        bounds: CatalogBounds = CatalogBounds(),
    ): CompanyCatalogDefinition = CompanyCatalogDefinition(
        company, revision, policy, units, products, skus, contexts, prices, activePrices, categories, taxCategories,
        namespaces, barcodes, conversions, compositions, bounds,
    )

    fun valid(
        definition: CompanyCatalogDefinition,
        previous: ValidatedCompanyCatalog? = null,
        usedSkus: List<DomainId> = emptyList(),
    ): ValidatedCompanyCatalog = when (val result = CompanyCatalogValidator.validate(
        definition, CatalogHistorySnapshot(definition.scope, previous?.definition?.revision, true, usedSkuIds = usedSkus), previous,
    )) {
        is CompanyCatalogValidation.Valid -> result.catalog
        is CompanyCatalogValidation.Rejected -> error("Unexpected invalid catalog: ${result.issues}")
    }

    fun invalid(
        definition: CompanyCatalogDefinition,
        previous: ValidatedCompanyCatalog? = null,
        usedSkus: List<DomainId> = emptyList(),
    ): CompanyCatalogValidation.Rejected = assertIs(CompanyCatalogValidator.validate(
        definition, CatalogHistorySnapshot(definition.scope, previous?.definition?.revision, true, usedSkuIds = usedSkus), previous,
    ))

    fun branchDefinition(
        company: ValidatedCompanyCatalog,
        revision: Revision = Revision(1),
        assortment: List<DomainId> = listOf(cid(200)),
        capabilities: List<CatalogExecutionCapability> = simpleCapabilities,
        actions: List<CatalogActionDeclaration> = emptyList(),
        overrides: List<BranchPriceOverride> = emptyList(),
        historicalOverrides: List<BranchPriceOverride> = emptyList(),
    ): BranchCatalogDefinition = BranchCatalogDefinition(
        branch, revision, company.definition.revision,
        BranchCatalogPolicy(branch, cid(5), Revision(1), company.definition.policy.reference, assortment, capabilities, actions),
        overrides, historicalOverrides,
    )

    fun plan(
        company: ValidatedCompanyCatalog,
        branch: BranchCatalogDefinition = branchDefinition(company),
        previous: BranchCatalogDefinition? = null,
        at: UtcInstant = now,
    ): CatalogActivationPlan = when (val result = BranchCatalogValidator.planActivation(
        company, branch, CatalogPublicationEvidence.CompanyPublicationReference(company.definition.scope, company.definition.revision, cid(900)),
        // Synthetic support declaration tests a pure plan; it does NOT qualify these workflows.
        CatalogExecutionManifest(cid(901), branch.policy.enabledCapabilities), at, previous,
    )) {
        is BranchCatalogValidation.Ready -> result.plan
        is BranchCatalogValidation.Rejected -> error("Unexpected invalid branch plan: ${result.issues}")
    }

    fun override(rule: CompanyPriceRule = price(), amount: String = "120"): BranchPriceOverride = BranchPriceOverride(
        branch, cid(400), Revision(1), CompanyPriceReference(company, cid(200), rule.context, rule.reference),
        UnitPrice.parse(Currency.EGP, amount), CatalogEffectivePeriod(start),
    )

    fun query(quantity: String = "1", scale: Int = 0, unit: DomainId = each.id, channel: Code = takeaway, at: UtcInstant = now, sku: DomainId = cid(200)): CatalogPriceQuery =
        CatalogPriceQuery(sku, channel, Quantity.parse(unit, quantity, scale), at)

    fun selected(plan: CatalogActivationPlan, query: CatalogPriceQuery = query()): SelectedCatalogPrice =
        assertIs<CatalogPriceOutcome.Selected>(CatalogPriceSelector.preview(plan, query).outcome).price
}

internal fun revised(
    source: CompanyCatalogDefinition,
    revision: Revision = source.revision.next(),
    policy: CompanyCatalogPolicy = source.policy,
    units: List<CatalogUnit> = source.units,
    products: List<CatalogProduct> = source.products,
    skus: List<CatalogSku> = source.skus,
    contexts: List<CatalogPriceContext> = source.priceContexts,
    prices: List<CompanyPriceRule> = source.priceRules,
    activePrices: List<CatalogVersionRef> = source.activePriceRules,
    namespaces: List<CatalogBarcodeNamespace> = source.barcodeNamespaces,
    barcodes: List<CatalogBarcodeBinding> = source.barcodes,
    conversions: List<ProductUnitConversion> = source.conversions,
    compositions: List<CatalogComposition> = source.compositions,
): CompanyCatalogDefinition = CompanyCatalogDefinition(
    source.scope, revision, policy, units, products, skus, contexts, prices, activePrices, source.categories,
    source.taxCategories, namespaces, barcodes, conversions, compositions, source.bounds,
)

internal fun revisedSku(
    source: CatalogSku,
    revision: Revision = source.revision.next(),
    productId: DomainId = source.productId,
    key: Code = source.key,
    baseUnit: DomainId = source.baseUnitId,
    scale: Int = source.quantityScale,
    capabilities: SkuCapabilities = source.capabilities,
    variants: List<VariantValue> = source.variantValues,
    saleUnits: List<CatalogSaleUnit> = source.saleUnits,
    status: CatalogStatus = source.status,
): CatalogSku = CatalogSku(source.id, revision, productId, key, baseUnit, scale, capabilities, variants, saleUnits, status)
