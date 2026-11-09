package com.ukkera.pos.catalog.store.independent.foodrecipequotationoriginalv1

import com.ukkera.pos.catalog.store.JvmCompanyPublicationKeys
import com.ukkera.pos.catalog.store.LocalFoodConfigurationCodecV1
import com.ukkera.pos.catalog.store.LocalPublishedFoodRecipeV1
import com.ukkera.pos.catalog.store.LocalSignedFoodRecipeV1
import com.ukkera.pos.catalog.store.PinnedCompanyPublicationKey
import com.ukkera.pos.catalog.store.decodeValue
import com.ukkera.pos.catalog.store.encodeValue
import com.ukkera.pos.catalog.store.localConfigurationJson
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodRecipeQuotationV1
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.DomainId
import java.util.Base64
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sixteen public codec/JCA/type/structural variants from verification-binding-v1.
 * Exact frozen inputs are loaded rather than reconstructed. Synthetic pins prove
 * bounded Ed25519 verification only, not installed trust or source authority.
 * FQVB-05/06 and all prior original-v1 cases remain UNBOUND: no genuine normal or
 * original loan, source-method result, persistence, resource, RPC or price proof
 * is supplied. None of the three mandatory source verification methods is called.
 */
class FoodRecipeQuotationVerificationBindingV1Test {
    private val f = FrozenFoodRecipeQuotationVerificationBindingV1

    @Test
    fun fqvb01ValidProductionRecipeVector() {
        f.variant("FQVB-01", "valid-production-recipe-vector")
        val vector = f.root.obj("productionRecipeVector")
        val signedBytes = vector.text("signedPublication").encodeToByteArray()
        val signed = decodeSignedPublication(vector.text("signedPublication"))
        val definitionBytes = encodeValue(signed.definition, LocalPublishedFoodRecipeV1.serializer()).copyBytes()
        val canonicalMessage = LocalFoodConfigurationCodecV1.canonicalPublication(signed.definition)
        val encodedPublication = LocalFoodConfigurationCodecV1.signedPublication(signed).encodeToByteArray()

        assertContentEquals(vector.text("definitionJson").encodeToByteArray(), definitionBytes)
        assertContentEquals(base64(vector.text("definitionUtf8Base64")), definitionBytes)
        assertEquals(vector.text("definitionSha256"), sha256Hex(definitionBytes))
        assertEquals(vector.obj("definition"),
            localConfigurationJson.parseToJsonElement(definitionBytes.decodeToString()).jsonObject)
        assertContentEquals(vector.text("messagePrefix").encodeToByteArray() + definitionBytes, canonicalMessage)
        assertContentEquals(base64(vector.text("messageUtf8Base64")), canonicalMessage)
        assertEquals(vector.text("messageSha256"), sha256Hex(canonicalMessage))
        assertContentEquals(signedBytes, encodedPublication)
        assertContentEquals(base64(vector.text("signedPublicationUtf8Base64")), encodedPublication)
        assertEquals(vector.text("signedPublicationSha256"), sha256Hex(encodedPublication))
        assertEquals(vector.text("signingKeyId"), signed.signingKeyId.value)
        assertEquals(vector.text("signatureHex"), signed.signatureHex)
        assertEquals(f.root.obj("syntheticPinnedCryptoInput").text("rawPublicKeyHex"), vector.text("rawPublicKeyHex"))
        assertTrue(syntheticPinnedVerifier(vector.text("rawPublicKeyHex")).verify(
            signed.definition.company, signed.signingKeyId, canonicalMessage, signed.signatureHex))
    }

    @Test
    fun fqvb02SignatureBitChanged() = signatureRefusal("signature-bit-changed")

    @Test
    fun fqvb02DefinitionAmountChangedWithoutSigning() =
        signatureRefusal("definition-amount-changed-without-signing")

    @Test
    fun fqvb02SignatureForAnotherDomain() = signatureRefusal("signature-for-another-domain")

    @Test
    fun fqvb02WrongPinnedPublicKey() = signatureRefusal("wrong-pinned-public-key")

    @Test
    fun fqvb03BaselineTypedQuotation() = typedQuotation("baseline-typed-quotation")

    @Test
    fun fqvb03LiteralDescriptionSpaces() = typedQuotation("literal-description-spaces")

    @Test
    fun fqvb04RequiredAmountOneScaledUnitHigh() =
        structuralRefusal("required-amount-is-one-scaled-unit-high")

    @Test
    fun fqvb04IngredientDivisionNotRepresentable() =
        structuralRefusal("ingredient-division-not-representable")

    @Test
    fun fqvb04DuplicateComponentIdentity() = structuralRefusal("duplicate-component-identity")

    @Test
    fun fqvb04DuplicateSymbolicRouteIdentity() = structuralRefusal("duplicate-symbolic-route-identity")

    @Test
    fun fqvb04FreeTextRouteKey() = structuralRefusal("free-text-route-key")

    @Test
    fun fqvb04CatalogReferenceFabricatedOperation() =
        structuralRefusal("catalog-reference-fabricated-operation")

    @Test
    fun fqvb04FoodReferenceOperationMissing() = structuralRefusal("food-reference-operation-missing")

    @Test
    fun fqvb04RecipeDefinitionRevisionZero() = structuralRefusal("recipe-definition-revision-zero")

    @Test
    fun fqvb04CatalogStorageGenerationZero() = structuralRefusal("catalog-storage-generation-zero")

    private fun signatureRefusal(name: String) {
        val variant = f.variant("FQVB-02", name)
        // Decode/canonicalization errors are not credited as cryptographic refusal.
        val signed = decodeSignedPublication(variant.text("signedPublication"))
        assertEquals(variant.text("signedPublication"), LocalFoodConfigurationCodecV1.signedPublication(signed))
        val message = LocalFoodConfigurationCodecV1.canonicalPublication(signed.definition)
        assertFalse(syntheticPinnedVerifier(variant.text("pinnedVerificationKeyHex")).verify(
            signed.definition.company, signed.signingKeyId, message, signed.signatureHex), variant.text("expected"))
    }

    private fun typedQuotation(name: String) {
        val variant = f.variant("FQVB-03", name)
        val input = variant.text("quotationJson")
        val frozen = localConfigurationJson.parseToJsonElement(input).jsonObject
        val quotation = decodeQuotation(input)
        val encoded = encodeValue(quotation, LocalFoodRecipeQuotationV1.serializer()).copyBytes()
        val actual = localConfigurationJson.parseToJsonElement(encoded.decodeToString()).jsonObject

        assertContentEquals(input.encodeToByteArray(), encoded, variant.text("expected"))
        assertEquals(frozen, actual)
        assertEquals(f.root.obj("quotation").keys.toList(), actual.keys.toList())
        assertEquals(frozen.text("description"), quotation.description)
        assertEquals(f.root.obj("productionRecipeVector").text("signedPublication"), quotation.signedPublication)
        assertEquals(frozen.array("modifierIds").map { it.jsonPrimitive.content }, quotation.modifierIds.map { it.value })
        assertEquals(frozen.array("routeKeys").map { it.jsonPrimitive.content }, quotation.routeKeys.map { it.value })

        val generations = f.root.obj("referenceGenerationBinding")
        assertEquals(listOf(generations.text("foodStorageGeneration"), generations.text("outputCatalogStorageGeneration"),
            generations.text("pricingCatalogStorageGeneration"), generations.text("flourCatalogStorageGeneration"),
            generations.text("oilCatalogStorageGeneration")), quotation.sources.map { it.revision.value.toString() })
        assertEquals(generations.text("publishedRecipeRevision"), quotation.recipeRevision.value.toString())
        assertEquals(generations.text("publishedOutputSkuRevision"), quotation.outputSkuRevision.value.toString())
        assertEquals(generations.text("publishedFlourSkuRevision"), quotation.ingredients[0].skuRevision.value.toString())
        assertEquals(generations.text("publishedOilSkuRevision"), quotation.ingredients[1].skuRevision.value.toString())
        assertEquals(generations.text("publishedFlourSkuRevision"), quotation.ingredients[2].skuRevision.value.toString())
        for (source in actual.array("sources")) {
            assertEquals(listOf("kind", "id", "revision", "digest", "authorizedBy", "signingKeyId", "operationId"),
                source.jsonObject.keys.toList())
        }
        assertEquals(frozen.array("sources"), actual.array("sources"))
        val foodDigest = actual.array("sources")[0].jsonObject.text("digest")
        assertEquals(f.root.obj("referenceDigestBoundary").text("foodDigest"), foodDigest)
        assertEquals(sha256Hex(quotation.signedPublication.encodeToByteArray()), foodDigest)

        val amounts = f.root.obj("exactArithmetic")
        assertEquals(amounts.text("yieldScaled"), quotation.yieldQuantity.scaled.toString())
        assertEquals(amounts.text("portionScaled"), quotation.portionQuantity.scaled.toString())
        assertEquals(amounts.array("perYieldScaledInOrder").map { it.jsonPrimitive.content },
            quotation.ingredients.map { it.quantityPerYield.scaled.toString() })
        assertEquals(amounts.array("requiredScaledInOrder").map { it.jsonPrimitive.content },
            quotation.ingredients.map { it.requiredBaseQuantity.scaled.toString() })
        assertEquals(amounts.getValue("ingredientComponentCount").jsonPrimitive.int, quotation.ingredients.size)
        assertEquals(quotation.ingredients.size, quotation.ingredients.map { it.componentId }.distinct().size)
        assertEquals(amounts.getValue("selectedDistinctSkuCount").jsonPrimitive.int,
            (listOf(quotation.outputSkuId, quotation.pricingSkuId) + quotation.ingredients.map { it.skuId }).distinct().size)
        val flour = quotation.ingredients.filter { it.skuId.value == f.root.obj("domainIds").text("flour_sku") }
        val oil = quotation.ingredients.filter { it.skuId.value == f.root.obj("domainIds").text("oil_sku") }
        assertEquals(amounts.getValue("flourComponentCount").jsonPrimitive.int, flour.size)
        assertEquals(amounts.text("flourRequiredScaledSum").toLong(), flour.sumOf { it.requiredBaseQuantity.scaled })
        assertEquals(amounts.text("oilRequiredScaledSum").toLong(), oil.sumOf { it.requiredBaseQuantity.scaled })
    }

    private fun structuralRefusal(name: String) {
        val variant = f.variant("FQVB-04", name)
        val input = variant.text("quotationJson")
        val raw = localConfigurationJson.parseToJsonElement(input).jsonObject
        // Validate the frozen baseline outside the rejection block. Only the actual
        // public quotation decode/constructor may supply the structural refusal.
        decodeQuotation(f.root.text("quotationJson"))
        assertTrue(variant.getValue("unchangedSignedPublication").jsonPrimitive.boolean)
        assertEquals(f.root.obj("productionRecipeVector").text("signedPublication"), raw.text("signedPublication"))
        val failure = assertFails("${variant.text("expectedBoundary")}: ${variant.text("expected")}") {
            decodeQuotation(input)
        }
        // No exception-subtype oracle is invented. VM/linkage/assertion errors
        // nevertheless cannot be counted as a malformed-input refusal.
        if (failure is Error) throw failure
    }

    private fun decodeSignedPublication(input: String): LocalSignedFoodRecipeV1 =
        decodeValue(StoredBytes(input.encodeToByteArray()), LocalSignedFoodRecipeV1.serializer())

    private fun decodeQuotation(input: String): LocalFoodRecipeQuotationV1 =
        decodeValue(StoredBytes(input.encodeToByteArray()), LocalFoodRecipeQuotationV1.serializer())

    private fun syntheticPinnedVerifier(rawPublicKeyHex: String): JvmCompanyPublicationKeys {
        val pin = f.root.obj("syntheticPinnedCryptoInput")
        val company = pin.obj("company")
        val point = hexBytes(rawPublicKeyHex)
        require(point.size == 32)
        // RFC 8410 Ed25519 SubjectPublicKeyInfo: standard DER header plus the
        // exact frozen raw point. This adapts representation, not key material.
        val spki = hexBytes("302a300506032b6570032100") + point
        return JvmCompanyPublicationKeys(listOf(PinnedCompanyPublicationKey(
            CompanyScope(DomainId(company.text("organizationId")), DomainId(company.text("companyId"))),
            DomainId(pin.text("signingKeyId")), spki)))
    }

    private fun base64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun hexBytes(value: String): ByteArray {
        require(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
