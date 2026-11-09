package com.ukkera.pos.catalog.store

import com.ukkera.pos.security.*

/** Exact, retained native recipe content. A SKU endorsement cannot be reinterpreted as a recipe. */
public fun CompanyBusinessPublicationAction.readNativeCompanyFoodRecipeDefinition(): LocalPublishedFoodRecipeV1 = proposal.readFoodRecipe()

public fun CompanyBusinessPublicationOutput.readNativeCompanyFoodRecipe(): LocalSignedFoodRecipeV1 =
    LocalSignedFoodRecipeV1(proposal.readFoodRecipe(), signingKeyId, signatureHex)

/** Pure compatibility validation on descriptive source facts, not a signature/activation grant. */
public fun requireCompatibleLocalFoodRecipe(recipe: LocalPublishedFoodRecipeV1, definitions: Map<com.ukkera.pos.types.DomainId, LocalPublishedSkuV1>) {
    require(definitions.values.all { it.company == recipe.company } && validFoodDefinition(recipe, definitions))
}

private fun CompanyBusinessPublicationProposal.readFoodRecipe(): LocalPublishedFoodRecipeV1 {
    require(kind == CompanyBusinessPublicationKind.FOOD_RECIPE_V1)
    val content = copyCanonicalContent()
    val prefix = kind.domain.encodeToByteArray()
    try {
        require(content.size > prefix.size && prefix.indices.all { content[it] == prefix[it] })
        val definition = localConfigurationJson.decodeFromString(LocalPublishedFoodRecipeV1.serializer(),
            content.decodeToString(startIndex = prefix.size, throwOnInvalidSequence = true))
        require(definition.company == company && definition.publicationId == publicationId && definition.recipeId == entityId)
        val canonical = LocalFoodConfigurationCodecV1.canonicalPublication(definition)
        try { require(canonical.contentEquals(content)) } finally { canonical.fill(0) }
        return definition
    } finally { content.fill(0); prefix.fill(0) }
}
