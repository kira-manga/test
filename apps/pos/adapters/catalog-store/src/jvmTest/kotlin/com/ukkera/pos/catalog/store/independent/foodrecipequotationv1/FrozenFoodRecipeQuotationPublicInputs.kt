package com.ukkera.pos.catalog.store.independent.foodrecipequotationv1

import com.ukkera.pos.sales.store.LocalFoodIngredientV1
import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.sales.store.LocalFoodSourceReferenceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/**
 * Constructor-only binding of food-recipe-quotation-v1 SHAPE-Q2. The parent
 * semantic fixture permits binding its unit aliases to existing public types.
 * These distinct synthetic unit identities do not establish an installed UOM.
 * FOOD stored revision, digests and authorizedBy were not specified in SHAPE-Q2;
 * their inert mandatory constructor fields below establish no source authority.
 *
 * Route identities are exclusively the separately frozen key-binding-v1 values,
 * not a reinterpretation or a passing execution of the parent's literal labels.
 * No signed producer, retained history, pricing or legitimate loan is supplied.
 */
internal object FrozenFoodRecipeQuotationPublicInputs {
    internal val pa: DomainId = DomainId("11111111-1111-1111-1111-111111111111")
    internal val pb: DomainId = DomainId("11111111-1111-1111-1111-111111111112")
    internal val u1: DomainId = DomainId("22222222-2222-2222-2222-222222222221")
    internal val u2: DomainId = DomainId("22222222-2222-2222-2222-222222222222")
    internal val cp1: DomainId = DomainId("30000000-0000-0000-0000-000000000001")
    internal val cp2: DomainId = DomainId("30000000-0000-0000-0000-000000000002")
    internal val choice: DomainId = DomainId("40000000-0000-0000-0000-000000000001")
    internal val ca: DomainId = DomainId("50000000-0000-0000-0000-000000000001")
    internal val cb: DomainId = DomainId("50000000-0000-0000-0000-000000000002")
    internal val m1: DomainId = DomainId("60000000-0000-0000-0000-000000000001")
    internal val m2: DomainId = DomainId("60000000-0000-0000-0000-000000000002")
    internal val output: DomainId = DomainId("70000000-0000-0000-0000-000000000001")
    internal val ingredientA: DomainId = DomainId("70000000-0000-0000-0000-000000000002")
    internal val ingredientB: DomainId = DomainId("70000000-0000-0000-0000-000000000003")
    internal val recipe: DomainId = DomainId("80000000-0000-0000-0000-000000000001")
    internal val priceSku: DomainId = DomainId("90000000-0000-0000-0000-000000000001")
    internal val recipeKey: DomainId = DomainId("a0000000-0000-0000-0000-000000000001")
    internal val catalogKey1: DomainId = DomainId("a0000000-0000-0000-0000-000000000002")
    internal val catalogKey2: DomainId = DomainId("a0000000-0000-0000-0000-000000000003")
    internal val recipeOperation: OperationId = OperationId("b0000000-0000-0000-0000-000000000001")
    internal val line: DomainId = DomainId("f0000000-0000-0000-0000-000000000001")
    internal val quotationId: DomainId = DomainId("e898beb9-91ad-94ba-9aad-9c1e80acef5c")

    // Explicit synthetic representation aliases only; no unit registry is bound.
    internal val serving: DomainId = DomainId("02000000-0000-0000-0000-000000000001")
    internal val gram: DomainId = DomainId("02000000-0000-0000-0000-000000000002")
    internal val milliliter: DomainId = DomainId("02000000-0000-0000-0000-000000000003")
    private val inertAuthor: DomainId = DomainId("0d000000-0000-0000-0000-000000000001")

    // Exact FRQK-001 / FRQK-C002 / FRQK-C003 runtime UUID mappings.
    internal val hotMain: DomainId = DomainId("01000000-0000-0000-0000-000000000001")
    internal val drinkBar: DomainId = DomainId("01000000-0000-0000-0000-000000000002")
    internal val replacementRoute: DomainId = DomainId("01000000-0000-0000-0000-000000000099")
    internal val routeKeys: List<DomainId> = listOf(hotMain, drinkBar)
    internal val modifiers: List<DomainId> = listOf(m2, m1)
    internal val description: String = "  Soup | لا بصل  "
    internal val signedPublication: String = "{}"
    internal val yieldQuantity: Quantity = Quantity(serving, 4, 0)
    internal val portionQuantity: Quantity = Quantity(serving, 2, 0)

    internal fun ingredients(): List<LocalFoodIngredientV1> = listOf(
        LocalFoodIngredientV1(cb, ingredientB, Revision(6),
            Quantity(milliliter, 400_000, 3), Quantity(milliliter, 200_000, 3)),
        LocalFoodIngredientV1(ca, ingredientA, Revision(5),
            Quantity(gram, 200_000, 3), Quantity(gram, 100_000, 3)),
    )

    internal fun sources(): List<LocalFoodSourceReferenceV1> = listOf(
        LocalFoodSourceReferenceV1("local.food.publication", u1, Revision(1),
            PayloadDigest("0".repeat(64)), inertAuthor, recipeKey, recipeOperation),
        LocalFoodSourceReferenceV1("local.catalog.publication", cp2, Revision(9),
            PayloadDigest("1".repeat(64)), inertAuthor, catalogKey2, null),
        LocalFoodSourceReferenceV1("local.catalog.publication", cp1, Revision(8),
            PayloadDigest("2".repeat(64)), inertAuthor, catalogKey1, null),
    )

    internal fun quotation(
        activity: LocalSalesActivity = LocalSalesActivity.RESTAURANT,
        channel: LocalSalesChannel = LocalSalesChannel.DINE_IN,
        recipeRevision: Revision = Revision(3),
        outputSkuRevision: Revision = Revision(7),
        modifiers: List<DomainId> = this.modifiers,
        description: String = this.description,
        yieldQuantity: Quantity = this.yieldQuantity,
        portionQuantity: Quantity = this.portionQuantity,
        ingredients: List<LocalFoodIngredientV1> = ingredients(),
        routeKeys: List<DomainId> = this.routeKeys,
        signedPublication: String = this.signedPublication,
        sources: List<LocalFoodSourceReferenceV1> = sources(),
    ): LocalFoodRecipeQuotationV1 = LocalFoodRecipeQuotationV1(
        quotationId = quotationId,
        portionId = pa,
        activity = activity,
        channel = channel,
        recipeId = recipe,
        recipeRevision = recipeRevision,
        publicationId = u1,
        outputSkuId = output,
        outputSkuRevision = outputSkuRevision,
        choiceId = choice,
        selectedModifiers = modifiers,
        description = description,
        yieldQuantity = yieldQuantity,
        portionQuantity = portionQuantity,
        pricingSkuId = priceSku,
        selectedIngredients = ingredients,
        requiredRouteKeys = routeKeys,
        signedPublication = signedPublication,
        publicationSources = sources,
    )

    /** The frozen single-operand vectors use CA; unchanged extra operands cannot mask them. */
    internal fun arithmeticQuotation(yieldScaled: Long, servingScaled: Long,
        perYieldScaled: Long, requiredScaled: Long): LocalFoodRecipeQuotationV1 {
        val ingredient = ingredients()[1].copy(
            quantityPerYield = Quantity(gram, perYieldScaled, 3),
            requiredBaseQuantity = Quantity(gram, requiredScaled, 3),
        )
        return quotation(yieldQuantity = Quantity(serving, yieldScaled, 0),
            portionQuantity = Quantity(serving, servingScaled, 0), ingredients = listOf(ingredient))
    }

    // The parent expressly prescribes unique identity generation for these counts.
    internal fun ingredientCount(count: Int): List<LocalFoodIngredientV1> {
        val original = ingredients()
        return List(count) { index -> original.getOrNull(index) ?: original[0].copy(
            componentId = generatedId("05000000", index + 1)) }
    }

    internal fun modifierCount(count: Int): List<DomainId> = List(count) { index ->
        modifiers.getOrNull(index) ?: generatedId("06000000", index + 1)
    }

    internal fun sourceCount(count: Int): List<LocalFoodSourceReferenceV1> {
        val original = sources()
        return List(count) { index -> original.getOrNull(index) ?: original[2].copy(
            id = generatedId("03000000", index + 1)) }
    }

    private fun generatedId(prefix: String, ordinal: Int): DomainId =
        DomainId("$prefix-0000-0000-0000-${ordinal.toString().padStart(12, '0')}")

    /** Structural observation, not an assumption that the production class has value equality. */
    internal fun facts(value: LocalFoodRecipeQuotationV1): Map<String, Any> = linkedMapOf(
        "quotationId" to value.quotationId, "portionId" to value.portionId,
        "activity" to value.activity, "channel" to value.channel,
        "recipeId" to value.recipeId, "recipeRevision" to value.recipeRevision,
        "publicationId" to value.publicationId, "outputSkuId" to value.outputSkuId,
        "outputSkuRevision" to value.outputSkuRevision, "choiceId" to value.choiceId,
        "modifierIds" to value.modifierIds.toList(), "description" to value.description,
        "yieldQuantity" to value.yieldQuantity, "portionQuantity" to value.portionQuantity,
        "pricingSkuId" to value.pricingSkuId, "ingredients" to value.ingredients.toList(),
        "routeKeys" to value.routeKeys.toList(), "signedPublication" to value.signedPublication,
        "sources" to value.sources.toList(),
    )

    /** Expected frozen facts plus the explicitly declared inert constructor bindings. */
    internal fun expectedShapeFacts(): Map<String, Any> = linkedMapOf(
        "quotationId" to quotationId, "portionId" to pa,
        "activity" to LocalSalesActivity.RESTAURANT, "channel" to LocalSalesChannel.DINE_IN,
        "recipeId" to recipe, "recipeRevision" to Revision(3), "publicationId" to u1,
        "outputSkuId" to output, "outputSkuRevision" to Revision(7), "choiceId" to choice,
        "modifierIds" to modifiers, "description" to description,
        "yieldQuantity" to yieldQuantity, "portionQuantity" to portionQuantity,
        "pricingSkuId" to priceSku, "ingredients" to ingredients(), "routeKeys" to routeKeys,
        "signedPublication" to signedPublication, "sources" to sources(),
    )
}

/** FRQ-009 / FRQK-C003 allow an exposed-list attempt to refuse or mutate a detached copy. */
internal fun <T> attemptExposedMutations(source: () -> List<T>, replacement: T, verify: () -> Unit) {
    val mutations: List<(MutableList<T>) -> Unit> = listOf(
        { it[0] = replacement },
        { it.clear() },
        { it.reverse() },
    )
    for (mutation in mutations) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val exposed = source() as MutableList<T>
            mutation(exposed)
        }
        verify()
    }
}

internal fun <T> mutateCallerList(source: MutableList<T>, replacement: T, verify: () -> Unit) {
    val original = source.toList()
    source[0] = replacement
    verify()
    source.clear()
    verify()
    source.addAll(original.reversed())
    verify()
}
