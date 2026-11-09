package com.ukkera.pos.sales.store.independent.foodoriginalresourcesv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingProfiles
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.PromotionResolution
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxPostingPolicy
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.store.FoodBodyV1
import com.ukkera.pos.sales.store.FoodEnvelopeV1
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodRecipeSnapshotV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.LocalSalesPriceSourceV2
import com.ukkera.pos.sales.store.foodOriginalQuotationResources
import com.ukkera.pos.sales.store.foodQuotationResources
import com.ukkera.pos.sales.store.foodStoredEnvelope
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Revision
import com.ukkera.pos.sales.store.independent.foodoriginalresourcesv1.FrozenFoodResourceValues.authorization
import com.ukkera.pos.sales.store.independent.foodoriginalresourcesv1.FrozenFoodResourceValues.key
import com.ukkera.pos.sales.store.independent.foodoriginalresourcesv1.FrozenFoodResourceValues.operation
import com.ukkera.pos.sales.store.independent.foodoriginalresourcesv1.FrozenFoodResourceValues.price
import com.ukkera.pos.sales.store.independent.foodoriginalresourcesv1.FrozenFoodResourceValues.quantity
import com.ukkera.pos.sales.store.independent.foodoriginalresourcesv1.FrozenFoodResourceValues.u
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Frozen FQR-001–008 structural supplement assertions, not loader/source/native qualification. */
internal class FoodOriginalQuotationResourcesV1Test {
    @Test fun fqr001ExactQuotedAliasesAndStrictWrapperSuccess() {
        val f = FrozenFoodResourceFixture() // Baseline construction failure must fail, never count as refusal.
        assertPricing(f.pricing, 16500, 33000)
        assertEquals(1533, FrozenFoodResourceValues.signedPublication.encodeToByteArray().size)
        assertEquals(f.t, (f.priceBatch.body as FoodBodyV1.PriceBatch).quote)
        val checks = mutableListOf<Check>()
        for (member in 1..2) for (helper in Helper.entries) {
            val expected = if (member == 1) FrozenFoodResourceValues.e1 else FrozenFoodResourceValues.e2
            checks += Check("FQR-001/member-$member/${helper.label}") {
                val result = success("FQR-001/member-$member/${helper.label}", f.quotedTriple(member), helper, expected)
                assertCounts(result, 4, 12, 3)
                if (member == 2) assertFalse(key(RecoveryResourceKind.ORDER_CUSTODY, u(4)) in result)
            }
        }
        runChecks(checks)
    }

    @Test fun fqr002RoutedAliasesAndStrictWrapperRefusal() {
        val f = FrozenFoodResourceFixture()
        val variants = listOf(
            Triple("A0", f.routedTriple(f.a0), FrozenFoodResourceValues.ra),
            Triple("B0", f.routedTriple(f.b0), FrozenFoodResourceValues.rb),
            Triple("A1", f.routedTriple(f.a1), FrozenFoodResourceValues.ra),
            Triple("A2", f.routedTriple(f.a2), FrozenFoodResourceValues.ra2),
            Triple("later-operation-U121", f.routedTriple(f.a0, laterOperation = operation(121)), FrozenFoodResourceValues.ra),
        )
        val checks = mutableListOf<Check>()
        for ((name, input, expected) in variants) {
            checks += Check("FQR-002/$name/general") {
                val result = success("FQR-002/$name/general", input, Helper.GENERAL, expected)
                assertCounts(result, 7, 19, 4)
            }
            checks += Check("FQR-002/$name/strict") {
                refusal("FQR-002/$name/strict", input, Helper.STRICT) { it }
            }
        }
        runChecks(checks)
    }

    @Test fun fqr003ExactSelectedOptionalPriceProvenance() {
        val f = FrozenFoodResourceFixture()
        val other = f.ps2.copy(companyPublicationId = u(145), companyPriceRuleId = u(146))
        val bOther = f.batch(sources = listOf(f.ps1, other))
        val policy = withPolicy(f.ps1)
        val override = policy.copy(branchOverrideEventId = u(132), branchOverrideRevision = Revision(3),
            companyUnitPrice = price("100.00"))
        val collision = policy.copy(policyEntryId = u(108), policyPublicationId = u(109),
            branchOverrideEventId = u(26), branchOverrideRevision = Revision(3))
        val quoted = listOf(
            Triple("Bother/member-1", f.quotedTriple(1, bOther), FrozenFoodResourceValues.e1),
            Triple("Bother/member-2", f.quotedTriple(2, bOther), FrozenFoodResourceValues.e2Other),
            Triple("selected-policy-absent", f.quotedTriple(1, bOther), FrozenFoodResourceValues.e1),
            Triple("selected-policy", f.quotedTriple(1, f.batch(sources = listOf(policy, other))), FrozenFoodResourceValues.e1Policy),
            Triple("selected-override", f.quotedTriple(1, f.batch(sources = listOf(override, other))), FrozenFoodResourceValues.e1Override),
            Triple("selected-optional-collisions", f.quotedTriple(1, f.batch(sources = listOf(collision, other))), FrozenFoodResourceValues.e1),
        )
        val checks = mutableListOf<Check>()
        for ((name, input, expected) in quoted) for (helper in Helper.entries) {
            checks += Check("FQR-003/$name/${helper.label}") {
                val result = success("FQR-003/$name/${helper.label}", input, helper, expected)
                assertCounts(result, 4, expected.size - 7, 3)
                if (name != "Bother/member-2") assertTrue(authorization(145, 146).none { it in result })
            }
        }
        checks += Check("FQR-003/routed-selected-override/general") {
            val input = f.routedTriple(f.a0, f.batch(sources = listOf(override, other)))
            val result = success("FQR-003/routed-selected-override/general", input, Helper.GENERAL, FrozenFoodResourceValues.raOverride)
            assertCounts(result, 7, 22, 4)
        }
        runChecks(checks)
    }

    @Test fun fqr004EnvelopeRelationsGenerationsAndStrictBodyBinding() {
        val f = FrozenFoodResourceFixture()
        val base = f.quotedTriple()
        val deltas = mutableListOf<EnvelopeDelta>()
        for (envelope in 0..2) for (component in 0..2) {
            deltas += EnvelopeDelta("scope-envelope-$envelope-component-$component") { input ->
                val value = input.envelopes[envelope]
                val scope = when (component) {
                    0 -> value.scope.copy(organizationId = u(90))
                    1 -> value.scope.copy(companyId = u(90))
                    else -> value.scope.copy(branchId = u(90))
                }
                replaceEnvelope(input, envelope, value.copy(scope = scope))
            }
        }
        deltas += EnvelopeDelta("portion-order") { it.withQuoted { body -> body.copy(orderId = u(121)) } }
        deltas += EnvelopeDelta("sidecar-order") { it.withSidecar { body -> body.copy(orderId = u(121)) } }
        deltas += EnvelopeDelta("batch-order") { it.withBatch { body -> body.copy(orderId = u(121)) } }
        deltas += EnvelopeDelta("portion-batch-link") { it.withQuoted { body -> body.copy(priceBatchId = u(122)) } }
        deltas += EnvelopeDelta("sidecar-batch-link") { it.withSidecar { body -> body.copy(priceBatchId = u(122)) } }
        deltas += EnvelopeDelta("portion-own-id") { it.copy(portion = it.portion.copy(id = u(90))) }
        deltas += EnvelopeDelta("sidecar-own-id") { it.copy(sidecar = it.sidecar.copy(id = u(90))) }
        deltas += EnvelopeDelta("quoted-sidecar-link") { it.withQuoted { body -> body.copy(quotationId = FrozenFoodResourceValues.quotation2Id) } }
        deltas += EnvelopeDelta("sidecar-generation-2") { it.copy(sidecar = it.sidecar.copy(revision = Revision(2))) }
        deltas += EnvelopeDelta("batch-generation-2") { it.copy(priceBatch = it.priceBatch.copy(revision = Revision(2))) }
        deltas += EnvelopeDelta("sidecar-operation-U121") { it.copy(sidecar = it.sidecar.copy(operationId = operation(121))) }
        deltas += EnvelopeDelta("batch-operation-U121") { it.copy(priceBatch = it.priceBatch.copy(operationId = operation(121))) }
        deltas += EnvelopeDelta("quoted-operation-U121") { it.copy(portion = it.portion.copy(operationId = operation(121))) }
        val wrongIdSnapshot = f.pricingSnapshot(snapshotId = u(122))
        val wrongRevisionSnapshot = f.pricingSnapshot(revision = Revision(1))
        val wrongIdBatch = f.batch(StoredPricingSnapshotCodec.encode(wrongIdSnapshot), listOf(f.ps1, f.ps2))
        val wrongRevisionBatch = f.batch(StoredPricingSnapshotCodec.encode(wrongRevisionSnapshot), listOf(f.ps1, f.ps2))
        deltas += EnvelopeDelta("snapshot-id-U122") { it.copy(priceBatch = wrongIdBatch) }
        deltas += EnvelopeDelta("snapshot-revision-1") { it.copy(priceBatch = wrongRevisionBatch) }
        deltas += EnvelopeDelta("quoted-storage-2-preparation-0") { it.copy(portion = it.portion.copy(revision = Revision(2))) }
        val checks = quotedRefusals("FQR-004", base, deltas).toMutableList()
        val routed = f.routedTriple()
        checks += Check("FQR-004/routed-initial-state/general") {
            refusal("FQR-004/routed-initial-state/general", routed, Helper.GENERAL) { input ->
                val body = input.portion.body as FoodBodyV1.Portion
                input.copy(portion = input.portion.copy(revision = Revision(1),
                    body = body.copy(preparation = body.preparation.copy(revision = Revision(0)))))
            }
        }
        checks += Check("FQR-004/routed-storage-3-preparation-1/general") {
            refusal("FQR-004/routed-storage-3-preparation-1/general", routed, Helper.GENERAL) {
                it.copy(portion = it.portion.copy(revision = Revision(3)))
            }
        }
        checks += Check("FQR-004/quoted-bytes-under-stock-schema/strict-decode") {
            strictDecodeRefusal("FQR-004/quoted-bytes-under-stock-schema", base, FrozenFoodResourceValues.routedSchema)
        }
        checks += Check("FQR-004/routed-bytes-under-quotation-schema/strict-decode") {
            strictDecodeRefusal("FQR-004/routed-bytes-under-quotation-schema", routed, FrozenFoodResourceValues.quotedSchema)
        }
        runChecks(checks)
    }

    @Test fun fqr005SelectedFactsOriginalPriceAndPublicProvenanceShape() {
        val f = FrozenFoodResourceFixture()
        val base = f.quotedTriple()
        val selections = listOf<Pair<String, (LocalFoodPortionSelectionV1) -> LocalFoodPortionSelectionV1>>(
            "item" to { it.copy(itemId = u(90)) },
            "recipe" to { it.copy(recipeId = u(90)) },
            "recipe-revision" to { it.copy(expectedRecipeRevision = Revision(12)) },
            "choice" to { it.copy(choiceId = u(90)) },
            "missing-modifier" to { it.copy(modifierIds = listOf(u(11))) },
            "substituted-modifier" to { it.copy(modifierIds = listOf(u(11), u(90))) },
            "quantity" to { it.copy(quantity = quantity(13, 200, 2)) },
            "commercial-line" to { it.copy(lineId = u(104)) },
        )
        val deltas = selections.map { (name, change) ->
            EnvelopeDelta("selection-$name") { input -> input.withQuoted { it.copy(selection = change(it.selection)) } }
        }.toMutableList()
        deltas += EnvelopeDelta("price-sources-missing-PS1") { it.withBatch { body -> body.copy(sources = listOf(f.ps2)) } }
        deltas += EnvelopeDelta("price-sources-duplicate-PS1") { it.withBatch { body -> body.copy(sources = listOf(f.ps1, f.ps1, f.ps2)) } }
        deltas += EnvelopeDelta("price-sources-reversed") { it.withBatch { body -> body.copy(sources = listOf(f.ps2, f.ps1)) } }
        val provenance = listOf<Pair<String, (LocalSalesPriceSourceV2) -> LocalSalesPriceSourceV2>>(
            "scope-branch" to { it.copy(scope = it.scope.copy(branchId = u(90))) },
            "portion" to { it.copy(portionId = u(104)) },
            "commercial-line" to { it.copy(lineId = u(104)) },
            "sku" to { it.copy(skuId = u(90)) },
            "revision" to { it.copy(companyPriceRevision = Revision(6)) },
            "company-and-effective-price" to { it.copy(companyUnitPrice = price("111.00"), effectiveUnitPrice = price("111.00")) },
        )
        for ((name, change) in provenance) deltas += EnvelopeDelta("PS1-$name") {
            it.withBatch { body -> body.copy(sources = listOf(change(f.ps1), f.ps2)) }
        }
        val quantitySnapshot = f.pricingSnapshot(firstQuantity = quantity(13, 200, 2))
        val revisionSnapshot = f.pricingSnapshot(firstRevision = Revision(6))
        val priceSnapshot = f.pricingSnapshot(firstPrice = price("111.00"))
        assertPricing(quantitySnapshot, 22000, 38500)
        assertPricing(revisionSnapshot, 16500, 33000)
        assertPricing(priceSnapshot, 16650, 33150)
        val variantBatches = listOf(
            "quantity" to f.batch(StoredPricingSnapshotCodec.encode(quantitySnapshot), listOf(f.ps1, f.ps2)),
            "price-revision" to f.batch(StoredPricingSnapshotCodec.encode(revisionSnapshot), listOf(f.ps1, f.ps2)),
            "unit-price" to f.batch(StoredPricingSnapshotCodec.encode(priceSnapshot), listOf(f.ps1, f.ps2)),
        )
        for ((name, batch) in variantBatches) deltas += EnvelopeDelta("snapshot-$name") { it.copy(priceBatch = batch) }
        val checks = quotedRefusals("FQR-005", base, deltas).toMutableList()
        val routed = f.routedTriple()
        for (name in listOf("item", "quantity")) {
            val change = selections.single { it.first == name }.second
            checks += Check("FQR-005/routed-selection-$name/general") {
                refusal("FQR-005/routed-selection-$name/general", routed, Helper.GENERAL) { input ->
                    val body = input.portion.body as FoodBodyV1.Portion
                    input.copy(portion = input.portion.copy(body = body.copy(selection = change(body.selection))))
                }
            }
        }
        checks += Check("FQR-005/routed-snapshot-unit-price/general") {
            refusal("FQR-005/routed-snapshot-unit-price/general", routed, Helper.GENERAL) {
                it.copy(priceBatch = variantBatches.single { row -> row.first == "unit-price" }.second)
            }
        }
        checks += Check("FQR-005/partial-policy-quartet/construction") {
            constructorRefusal("FQR-005/partial-policy-quartet", base) { f.ps1.copy(policyEntryId = u(130)) }
        }
        checks += Check("FQR-005/override-without-policy/construction") {
            constructorRefusal("FQR-005/override-without-policy", base) {
                f.ps1.copy(branchOverrideEventId = u(132), branchOverrideRevision = Revision(3))
            }
        }
        checks += Check("FQR-005/override-without-revision/construction") {
            constructorRefusal("FQR-005/override-without-revision", base) { withPolicy(f.ps1).copy(branchOverrideEventId = u(132)) }
        }
        runChecks(checks)
    }

    @Test fun fqr006EveryFrozenOriginalRoutingDeltaRefuses() {
        val f = FrozenFoodResourceFixture()
        val base = f.routedTriple()
        val prefix = f.originals
        val suffix = listOf(f.ra21, f.sa23, f.sa22)
        val i1 = f.i1
        val i2 = f.i2
        val r1 = f.routesA[0]
        val r2 = f.routesA[1]
        val deltas = listOf(
            RecipeDelta("FORC-002/portion-and-corresponding-id") { it.copy(portionId = u(90), snapshotId = DomainId("50e6acd1-5cd2-487f-c7e0-a6d09f729d99")) },
            RecipeDelta("FORC-002/activity") { it.copy(activity = LocalSalesActivity.CAFE) },
            RecipeDelta("FORC-002/channel") { it.copy(channel = LocalSalesChannel.DELIVERY) },
            RecipeDelta("FORC-002/recipe-id") { it.copy(recipeId = u(91)) },
            RecipeDelta("FORC-002/recipe-revision") { it.copy(recipeRevision = Revision(12)) },
            RecipeDelta("FORC-002/publication-corresponding-id-and-F5") { it.copy(publicationId = u(92),
                snapshotId = DomainId("4e11f9a1-8daf-66af-d02f-7b39cb9c0431"), sources = listOf(f.c28, f.f5.copy(id = u(92)), f.c27) + suffix) },
            RecipeDelta("FORC-002/output-sku") { it.copy(outputSkuId = u(93)) },
            RecipeDelta("FORC-002/output-revision") { it.copy(outputSkuRevision = Revision(8)) },
            RecipeDelta("FORC-002/choice") { it.copy(choiceId = u(94)) },
            RecipeDelta("FORC-002/pricing-sku") { it.copy(pricingSkuId = u(95)) },
            RecipeDelta("FORC-002/description-trailing-space") { it.copy(description = FrozenFoodResourceValues.description + " ") },
            RecipeDelta("FORC-002/signed-byte") { it.copy(signedPublication = FrozenFoodResourceValues.signedPublication.replace(
                "\"signatureHex\":\"${"0".repeat(128)}\"", "\"signatureHex\":\"${"0".repeat(127)}1\"")) },
            RecipeDelta("FORC-002/yield-and-required") { it.copy(yieldQuantity = quantity(13, 800, 2),
                ingredients = listOf(i1.copy(requiredBaseQuantity = quantity(12, 225, 3)), i2.copy(requiredBaseQuantity = quantity(12, 150, 3)))) },
            RecipeDelta("FORC-002/portion-and-required") { it.copy(portionQuantity = quantity(13, 200, 2),
                ingredients = listOf(i1.copy(requiredBaseQuantity = quantity(12, 600, 3)), i2.copy(requiredBaseQuantity = quantity(12, 400, 3)))) },
            RecipeDelta("FORC-002/yield-and-portion-units") { it.copy(yieldQuantity = quantity(90, 400, 2), portionQuantity = quantity(90, 150, 2)) },
            RecipeDelta("FORC-002/yield-and-portion-scales") { it.copy(yieldQuantity = quantity(13, 4000, 3), portionQuantity = quantity(13, 1500, 3)) },
            RecipeDelta("FORC-003/modifier-order") { it.copy(modifierIds = listOf(u(10), u(11))) },
            RecipeDelta("FORC-003/modifier-missing") { it.copy(modifierIds = listOf(u(11))) },
            RecipeDelta("FORC-003/modifier-substituted") { it.copy(modifierIds = listOf(u(11), u(90))) },
            RecipeDelta("FORC-003/modifier-duplicated") { it.copy(modifierIds = listOf(u(11), u(11))) },
            RecipeDelta("FORC-003/ingredient-order") { it.copy(ingredients = listOf(i2, i1)) },
            RecipeDelta("FORC-003/ingredient-missing") { it.copy(ingredients = listOf(i1)) },
            RecipeDelta("FORC-003/ingredient-component") { it.copy(ingredients = listOf(i1.copy(componentId = u(90)), i2)) },
            RecipeDelta("FORC-003/ingredient-sku") { it.copy(ingredients = listOf(i1.copy(skuId = u(91)), i2)) },
            RecipeDelta("FORC-003/ingredient-revision") { it.copy(ingredients = listOf(i1.copy(skuRevision = Revision(14)), i2)) },
            RecipeDelta("FORC-003/ingredient-amounts") { it.copy(ingredients = listOf(i1.copy(quantityPerYield = quantity(12, 1208, 3), requiredBaseQuantity = quantity(12, 453, 3)), i2)) },
            RecipeDelta("FORC-003/ingredient-units") { it.copy(ingredients = listOf(i1.copy(quantityPerYield = quantity(90, 1200, 3), requiredBaseQuantity = quantity(90, 450, 3)), i2)) },
            RecipeDelta("FORC-003/ingredient-scales") { it.copy(ingredients = listOf(i1.copy(quantityPerYield = quantity(12, 120, 2), requiredBaseQuantity = quantity(12, 45, 2)), i2)) },
            RecipeDelta("FORC-003/ingredient-required-only") { it.copy(ingredients = listOf(i1.copy(requiredBaseQuantity = quantity(12, 451, 3)), i2)) },
            RecipeDelta("FORC-003/merged-same-sku") { it.copy(ingredients = listOf(i1.copy(quantityPerYield = quantity(12, 2000, 3), requiredBaseQuantity = quantity(12, 750, 3)))) },
            RecipeDelta("FORC-004/prefix-reordered") { it.copy(sources = listOf(f.f5, f.c28, f.c27) + suffix) },
            RecipeDelta("FORC-004/prefix-missing") { it.copy(sources = listOf(f.c28, f.f5) + suffix) },
            RecipeDelta("FORC-004/prefix-duplicate-substitution") { it.copy(sources = listOf(f.c28, f.f5, f.c28) + suffix) },
            RecipeDelta("FORC-004/prefix-extra-original") { it.copy(sources = prefix + f.c28 + suffix) },
            RecipeDelta("FORC-004/prefix-interleaved-routing") { it.copy(sources = listOf(f.c28, f.ra21, f.f5, f.c27, f.sa23, f.sa22)) },
            RecipeDelta("FORC-004/C28-kind") { it.copy(sources = listOf(f.c28.copy(kind = "local.food.configuration-version"), f.f5, f.c27) + suffix) },
            RecipeDelta("FORC-004/C28-id") { it.copy(sources = listOf(f.c28.copy(id = u(90)), f.f5, f.c27) + suffix) },
            RecipeDelta("FORC-004/C28-revision") { it.copy(sources = listOf(f.c28.copy(revision = Revision(6)), f.f5, f.c27) + suffix) },
            RecipeDelta("FORC-004/C28-digest") { it.copy(sources = listOf(f.c28.copy(digest = PayloadDigest("993ae89ef7480edd74db4993358b632c9a72e089cb7edfbaddb320ab08c95f81")), f.f5, f.c27) + suffix) },
            RecipeDelta("FORC-004/C28-authorized-by") { it.copy(sources = listOf(f.c28.copy(authorizedBy = u(90)), f.f5, f.c27) + suffix) },
            RecipeDelta("FORC-004/C28-signing-key") { it.copy(sources = listOf(f.c28.copy(signingKeyId = u(90)), f.f5, f.c27) + suffix) },
            RecipeDelta("FORC-004/F5-operation") { it.copy(sources = listOf(f.c28, f.f5.copy(operationId = operation(90)), f.c27) + suffix) },
            RecipeDelta("FORC-005/wrong-derived-id") { it.copy(snapshotId = u(90)) },
            RecipeDelta("FORC-005/changed-route-version-without-reference") { it.copy(routeVersionId = u(29), snapshotId = FrozenFoodResourceValues.snapshotBId) },
            RecipeDelta("FORC-005/routes-reordered") { it.copy(routes = listOf(r2, r1)) },
            RecipeDelta("FORC-005/routes-missing") { it.copy(routes = listOf(r1)) },
            RecipeDelta("FORC-005/route-key-wrong") { it.copy(routes = listOf(r1, r2.copy(routeKey = u(90)))) },
            RecipeDelta("FORC-005/route-key-duplicate") { it.copy(routes = listOf(r1, r2.copy(routeKey = u(18)))) },
            RecipeDelta("FORC-005/station-duplicate") { it.copy(routes = listOf(r1, r2.copy(stationId = u(20)))) },
            RecipeDelta("FORC-005/station-revision-zero") { it.copy(routes = listOf(r1.copy(stationRevision = Revision(0)), r2)) },
            RecipeDelta("FORC-005/route-reference-missing") { it.copy(sources = prefix + listOf(f.sa23, f.sa22)) },
            RecipeDelta("FORC-005/station-reference-missing") { it.copy(sources = prefix + listOf(f.ra21, f.sa23)) },
            RecipeDelta("FORC-005/station-reference-duplicate") { it.copy(sources = prefix + listOf(f.ra21, f.sa23, f.sa23)) },
            RecipeDelta("FORC-005/original-as-station-reference") { it.copy(sources = prefix + listOf(f.ra21, f.sa23, f.c28)) },
            RecipeDelta("FORC-005/route-reference-revision-zero") { it.copy(sources = prefix + listOf(f.ra21.copy(revision = Revision(0)), f.sa23, f.sa22)) },
            RecipeDelta("FORC-005/route-reference-signing-key") { it.copy(sources = prefix + listOf(f.ra21.copy(signingKeyId = u(24)), f.sa23, f.sa22)) },
            RecipeDelta("FORC-005/route-reference-operation-missing") { it.copy(sources = prefix + listOf(f.ra21.copy(operationId = null), f.sa23, f.sa22)) },
            RecipeDelta("FORC-005/route-reference-operation-mismatch") { it.copy(sources = prefix + listOf(f.ra21.copy(operationId = operation(90)), f.sa23, f.sa22)) },
            RecipeDelta("additional-shape/F5-operation-null") { it.copy(sources = listOf(f.c28, f.f5.copy(operationId = null), f.c27) + suffix) },
            RecipeDelta("additional-shape/C28-signing-null") { it.copy(sources = listOf(f.c28.copy(signingKeyId = null), f.f5, f.c27) + suffix) },
            RecipeDelta("additional-shape/RA21-signing-U24") { it.copy(sources = prefix + listOf(f.ra21.copy(signingKeyId = u(24)), f.sa23, f.sa22)) },
        )
        assertEquals(61, deltas.size)
        runChecks(deltas.map { delta -> Check("FQR-006/${delta.name}/general") {
            refusal("FQR-006/${delta.name}/general", base, Helper.GENERAL) { input ->
                val body = input.portion.body as FoodBodyV1.Portion
                input.copy(portion = input.portion.copy(body = body.copy(recipe = delta.change(body.recipe))))
            }
        } })
    }

    @Test fun fqr007DeduplicationKindsAndLawfulNullableReferenceFields() {
        val f = FrozenFoodResourceFixture()
        val e1 = success("FQR-007/FQR-001-quoted-baseline", f.quotedTriple(), Helper.GENERAL, FrozenFoodResourceValues.e1)
        val ra = success("FQR-007/FQR-002-A0-baseline", f.routedTriple(), Helper.GENERAL, FrozenFoodResourceValues.ra)
        assertCounts(e1, 4, 12, 3)
        assertCounts(ra, 7, 19, 4)
        assertEquals(listOf(u(15), u(14)), f.quotation1.ingredients.map { it.componentId })
        assertEquals(listOf(u(16), u(16)), f.quotation1.ingredients.map { it.skuId })
        assertEquals(f.quotation1.publicationId, f.f5.id)
        assertEquals(listOf(u(34), u(34), u(34)), f.quotation1.sources.map { it.authorizedBy })
        assertEquals(listOf(u(24), u(24), u(24)), f.quotation1.sources.map { it.signingKeyId })
        assertEquals(null, f.c28.operationId)
        assertEquals(null, f.c27.operationId)
        assertEquals(operation(26), f.f5.operationId)
        for (id in listOf(5, 16, 24, 26, 34)) assertEquals(1, e1.count { it == key(RecoveryResourceKind.AUTHORIZATION, u(id)) })
        assertEquals(1, e1.count { it == key(RecoveryResourceKind.STOCK, u(16)) })
        assertEquals(f.originals, f.a0.sources.take(3))
        for (reference in listOf(f.ra21, f.sa23, f.sa22)) {
            assertEquals(u(35), reference.authorizedBy)
            assertEquals(null, reference.signingKeyId)
            assertEquals(reference.id.value, assertNotNull(reference.operationId).value)
            assertEquals(1, ra.count { it == key(RecoveryResourceKind.AUTHORIZATION, reference.id) })
        }
        for (id in listOf(5, 16, 24, 26, 34, 35)) assertEquals(1, ra.count { it == key(RecoveryResourceKind.AUTHORIZATION, u(id)) })
        for (station in listOf(19, 20)) {
            assertTrue(key(RecoveryResourceKind.ORDER_CUSTODY, u(station)) in ra)
            assertTrue(key(RecoveryResourceKind.AUTHORIZATION, u(station)) in ra)
        }
        assertTrue(key(RecoveryResourceKind.AUTHORIZATION, u(30)) in ra)
        assertTrue(key(RecoveryResourceKind.STOCK, u(30)) in ra)
    }

    @Test fun fqr008RetainedResultsIgnoreCallerAndResultCollectionMutation() {
        val f = FrozenFoodResourceFixture()
        val modifiers = f.quotation1.modifierIds.toMutableList()
        val ingredients = f.quotation1.ingredients.toMutableList()
        val routeKeys = f.quotation1.routeKeys.toMutableList()
        val originals = f.quotation1.sources.toMutableList()
        val routes = f.a0.routes.toMutableList()
        val routedSources = f.a0.sources.toMutableList()
        val priceSources = listOf(f.ps1, f.ps2).toMutableList()
        val quotation = f.quotation(modifiers = modifiers, ingredients = ingredients, routeKeys = routeKeys, sources = originals)
        val sidecar = f.sidecar1.copy(body = (f.sidecar1.body as FoodBodyV1.RecipeQuotation).copy(quotation = quotation))
        val batch = f.batch(sources = priceSources)
        val quoted = f.quotedTriple().copy(sidecar = sidecar, priceBatch = batch)
        val routed = f.routedTriple(f.a0.copy(modifierIds = modifiers, ingredients = ingredients,
            routes = routes, sources = routedSources), batch).copy(sidecar = sidecar)
        val retained = listOf(
            "quoted-general" to success("FQR-008/quoted-general", quoted, Helper.GENERAL, FrozenFoodResourceValues.e1),
            "quoted-strict" to success("FQR-008/quoted-strict", quoted, Helper.STRICT, FrozenFoodResourceValues.e1),
            "routed-general" to success("FQR-008/routed-general", routed, Helper.GENERAL, FrozenFoodResourceValues.ra),
        )
        fun inspectAll() {
            for ((name, result) in retained) assertRetained(name, result,
                if (name == "routed-general") FrozenFoodResourceValues.ra else FrozenFoodResourceValues.e1)
        }
        val quotationBefore = rawFacts(sidecar)
        val routedBefore = rawFacts(routed.portion)
        val holders: List<Pair<String, MutableList<*>>> = listOf("modifiers" to modifiers, "ingredients" to ingredients,
            "route-keys" to routeKeys, "original-sources" to originals, "routes" to routes,
            "routed-sources" to routedSources, "price-sources" to priceSources)
        for ((name, holder) in holders) {
            holder.reverse()
            inspectAll()
            holder.clear()
            inspectAll()
            stage("FQR-008/holder-$name", "REVERSE_AND_CLEAR_RETAINED_RESULTS_UNCHANGED")
        }
        // These observations describe public constructor retention separately, not helper capture timing.
        stage("FQR-008/quotation-constructor-retention", "FACTS_RETAINED=${quotationBefore.facts == rawFacts(sidecar).facts}")
        stage("FQR-008/routed-constructor-retention", "FACTS_RETAINED=${routedBefore.facts == rawFacts(routed.portion).facts}")
        for ((name, result) in retained) mutateExposedResult(name, result,
            if (name == "routed-general") FrozenFoodResourceValues.ra else FrozenFoodResourceValues.e1)
        val later = FrozenFoodResourceFixture()
        val rb = success("FQR-008/independent-later-B0", later.routedTriple(later.b0), Helper.GENERAL, FrozenFoodResourceValues.rb)
        inspectAll()
        assertRetained("later-B0", rb, FrozenFoodResourceValues.rb)
        mutateExposedResult("later-B0", rb, FrozenFoodResourceValues.rb)
        inspectAll()
        assertRetained("later-B0", rb, FrozenFoodResourceValues.rb)
    }

    // FQR-N001 is intentionally not a synthetic helper test: no genuine loader/native union fixture is supplied.

    private enum class Helper(val label: String) {
        GENERAL("general"), STRICT("strict");
        fun invoke(input: FrozenFoodResourceTriple): Set<RecoveryResourceKey> = when (this) {
            GENERAL -> foodOriginalQuotationResources(input.portion, input.sidecar, input.priceBatch)
            STRICT -> foodQuotationResources(input.portion, input.sidecar, input.priceBatch)
        }
    }

    private data class Check(val id: String, val action: () -> Unit)
    private data class EnvelopeDelta(val name: String, val change: (FrozenFoodResourceTriple) -> FrozenFoodResourceTriple)
    private data class RecipeDelta(val name: String, val change: (LocalFoodRecipeSnapshotV1) -> LocalFoodRecipeSnapshotV1)
    private data class EnvelopeImage(val facts: JsonElement, val bytes: ByteArray)
    private data class TripleImage(val schemas: List<String>, val envelopes: List<EnvelopeImage>)

    private fun runChecks(checks: List<Check>) {
        val failures = mutableListOf<String>()
        for (check in checks) {
            try { check.action() }
            catch (failure: AssertionError) { failures += "${check.id}: ${failure.message}" }
            catch (failure: Exception) { failures += "${check.id}: ${failure::class.simpleName}: ${failure.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun success(id: String, input: FrozenFoodResourceTriple, helper: Helper,
        expected: Set<RecoveryResourceKey>): Set<RecoveryResourceKey> {
        val result = invokePreserving(id, input) { helper.invoke(input) }.getOrThrow()
        assertEquals(expected, result, "$id exact alias set")
        stage(id, "HELPER_ACCEPTED_EXACT_SET_INPUTS_UNCHANGED")
        return result
    }

    private fun refusal(id: String, base: FrozenFoodResourceTriple, helper: Helper,
        construct: (FrozenFoodResourceTriple) -> FrozenFoodResourceTriple) {
        val original = capture(base)
        val candidate = try { construct(base) } catch (_: Exception) {
            assertPreserved(id, original, base)
            stage(id, "CONSTRUCTION_REFUSED; HELPER_COVERAGE_UNBOUND")
            return
        }
        assertPreserved("$id/variant-construction", original, base)
        val result = invokePreserving(id, candidate) { helper.invoke(candidate) }
        assertTrue(result.isFailure, "$id expected propagated refusal, not null/partial/success aliases")
        stage(id, "HELPER_REFUSED_INPUTS_UNCHANGED")
    }

    private fun constructorRefusal(id: String, base: FrozenFoodResourceTriple, construct: () -> Any) {
        val before = capture(base)
        val result = try { Result.success(construct()) } catch (failure: Exception) { Result.failure(failure) }
        assertPreserved(id, before, base)
        assertTrue(result.isFailure, "$id malformed public provenance constructor must refuse")
        stage(id, "CONSTRUCTION_REFUSED; HELPER_COVERAGE_UNBOUND")
    }

    private fun strictDecodeRefusal(id: String, input: FrozenFoodResourceTriple, wrongSchema: String) {
        val bytes = StoredBytes(rawFacts(input.portion).bytes)
        val beforeBytes = bytes.copyBytes()
        val result = invokePreserving(id, input) { foodStoredEnvelope(wrongSchema, bytes) }
        assertContentEquals(beforeBytes, bytes.copyBytes(), "$id caller bytes")
        assertTrue(result.isFailure, "$id wrong schema/body must refuse strict stored decoding")
        stage(id, "STRICT_DECODING_REFUSED; HELPER_NOT_CALLED")
    }

    private fun <T> invokePreserving(id: String, input: FrozenFoodResourceTriple, action: () -> T): Result<T> {
        val before = capture(input)
        return try { Result.success(action()) }
        catch (failure: Exception) { Result.failure(failure) }
        finally { assertPreserved(id, before, input) }
    }

    private fun rawFacts(envelope: FoodEnvelopeV1): EnvelopeImage {
        // Raw canonical serialization captures malformed-but-publicly-constructed values without validating them early.
        val bytes = localSalesEncode(envelope, FoodEnvelopeV1.serializer(), 28_672).copyBytes()
        return EnvelopeImage(Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)), bytes)
    }

    private fun capture(input: FrozenFoodResourceTriple): TripleImage = TripleImage(input.schemas.toList(), input.envelopes.map(::rawFacts))
    private fun assertPreserved(id: String, before: TripleImage, input: FrozenFoodResourceTriple) {
        val after = capture(input)
        assertEquals(before.schemas, after.schemas, "$id assigned schemas")
        for (index in before.envelopes.indices) {
            assertEquals(before.envelopes[index].facts, after.envelopes[index].facts, "$id envelope-$index complete facts")
            assertContentEquals(before.envelopes[index].bytes, after.envelopes[index].bytes, "$id envelope-$index canonical bytes")
        }
    }

    private fun quotedRefusals(id: String, base: FrozenFoodResourceTriple, deltas: List<EnvelopeDelta>): List<Check> =
        deltas.flatMap { delta -> Helper.entries.map { helper -> Check("$id/${delta.name}/${helper.label}") {
            refusal("$id/${delta.name}/${helper.label}", base, helper, delta.change)
        } } }

    private fun FrozenFoodResourceTriple.withQuoted(change: (FoodBodyV1.QuotedPortion) -> FoodBodyV1.QuotedPortion) =
        copy(portion = portion.copy(body = change(portion.body as FoodBodyV1.QuotedPortion)))
    private fun FrozenFoodResourceTriple.withSidecar(change: (FoodBodyV1.RecipeQuotation) -> FoodBodyV1.RecipeQuotation) =
        copy(sidecar = sidecar.copy(body = change(sidecar.body as FoodBodyV1.RecipeQuotation)))
    private fun FrozenFoodResourceTriple.withBatch(change: (FoodBodyV1.PriceBatch) -> FoodBodyV1.PriceBatch) =
        copy(priceBatch = priceBatch.copy(body = change(priceBatch.body as FoodBodyV1.PriceBatch)))
    private fun replaceEnvelope(input: FrozenFoodResourceTriple, index: Int, value: FoodEnvelopeV1): FrozenFoodResourceTriple =
        when (index) { 0 -> input.copy(portion = value); 1 -> input.copy(sidecar = value); else -> input.copy(priceBatch = value) }

    private fun withPolicy(source: LocalSalesPriceSourceV2) = source.copy(policyEntryId = u(130), policyEntryRevision = Revision(2),
        policyPublicationId = u(131), policyEntryDigest = PayloadDigest(FrozenFoodResourceValues.policyDigest))

    private fun assertPricing(snapshot: PricingSnapshot, firstMinor: Long, totalMinor: Long) {
        assertEquals(2, snapshot.lines.size)
        assertEquals(Money(Currency.EGP, firstMinor), snapshot.lines[0].payable)
        assertEquals(Money(Currency.EGP, 16500), snapshot.lines[1].payable)
        assertEquals(Money(Currency.EGP, totalMinor), snapshot.beforeDiscountPayable)
        assertEquals(Money(Currency.EGP, totalMinor), snapshot.payable)
        assertEquals(ExactRational.ratio(firstMinor, 100), snapshot.lines[0].exactOriginalItemNet)
        assertEquals(ExactRational.ratio(firstMinor, 100), snapshot.lines[0].exactItemNet)
        assertEquals(ExactRational.ratio(16500, 100), snapshot.lines[1].exactOriginalItemNet)
        assertEquals(ExactRational.ratio(16500, 100), snapshot.lines[1].exactItemNet)
        assertEquals(ExactRational.ratio(totalMinor, 100), snapshot.exactBeforeDiscountPayable)
        assertEquals(ExactRational.ratio(totalMinor, 100), snapshot.exactPayable)
        assertEquals(PricingProfiles.LINEAR_PROTECTED_V2, snapshot.profile)
        assertEquals(PromotionResolution.NONE, snapshot.promotionResolution)
        assertEquals(TaxPostingPolicy.OPERATIONAL_COMPONENT_ALLOCATION, snapshot.taxPostingPolicy)
        assertTrue(snapshot.charges.isEmpty())
        assertTrue(snapshot.discounts.isEmpty())
        for (line in snapshot.lines) {
            assertEquals(u(110), line.tax.policyId)
            assertEquals(Revision(1), line.tax.revision)
            assertEquals("FOOD", line.tax.categoryCode)
            assertEquals(PercentageRate.parse("0"), line.tax.rate)
        }
        for (component in snapshot.components.filter { it.kind == PriceComponentKind.ITEM_TAX }) {
            assertEquals(Money(Currency.EGP, 0), component.postedBeforeDiscounts)
            assertEquals(Money(Currency.EGP, 0), component.posted)
        }
    }

    private fun assertCounts(result: Set<RecoveryResourceKey>, custody: Int, authorization: Int, stock: Int) {
        assertEquals(custody + authorization + stock, result.size)
        assertEquals(custody, result.count { it.kind == RecoveryResourceKind.ORDER_CUSTODY })
        assertEquals(authorization, result.count { it.kind == RecoveryResourceKind.AUTHORIZATION })
        assertEquals(stock, result.count { it.kind == RecoveryResourceKind.STOCK })
    }

    private fun assertRetained(id: String, result: Set<RecoveryResourceKey>, expected: Set<RecoveryResourceKey>) {
        assertEquals(expected, result, "FQR-008/$id same retained result")
        assertFalse(key(RecoveryResourceKind.STOCK, u(90)) in result, "FQR-008/$id no sentinel")
    }

    private fun mutateExposedResult(id: String, result: Set<RecoveryResourceKey>, expected: Set<RecoveryResourceKey>) {
        val mutable = result as? MutableSet<RecoveryResourceKey>
        if (mutable == null) {
            assertRetained(id, result, expected)
            stage("FQR-008/result-$id", "NO_PUBLIC_MUTABLE_SET_VIEW")
            return
        }
        val mutations = listOf<Pair<String, () -> Unit>>(
            "clear" to { mutable.clear() },
            "remove" to { mutable.remove(key(RecoveryResourceKind.ORDER_CUSTODY, u(100))); Unit },
            "add-sentinel" to { mutable.add(key(RecoveryResourceKind.STOCK, u(90))); Unit },
        )
        for ((name, mutate) in mutations) {
            try { mutate() } catch (_: Exception) { /* Public mutation refusal is allowed. */ }
            assertRetained(id, result, expected)
            stage("FQR-008/result-$id/$name", "SAME_RETAINED_RESULT_UNCHANGED")
        }
    }

    private fun stage(id: String, outcome: String) { println("$id: $outcome") }
}
