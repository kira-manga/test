package com.ukkera.pos.catalog.store.independent.catalogproductversionv1

import com.ukkera.pos.catalog.store.LocalCatalogProductSelectionV1
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalProductAttributeValueV1
import com.ukkera.pos.catalog.store.LocalProductStatusV1
import com.ukkera.pos.catalog.store.LocalVariantAxisV1
import com.ukkera.pos.catalog.store.LocalVariantValueV1
import com.ukkera.pos.catalog.store.localCatalogProductActivationRejection
import com.ukkera.pos.catalog.store.localCatalogProductHistoryRejection
import com.ukkera.pos.catalog.store.localCatalogProductShapeRejection
import com.ukkera.pos.catalog.store.selectLocalCatalogProductVersion
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen CPR-FROZEN-1 L1 comparisons of independently supplied complete content.
 * Even a contiguous definition list is not proof of native completion, empty
 * history, root authenticity, active branch history or current employee authority.
 * All whole L2/L3 cases, actual signing/recovery and business effects stay UNBOUND / NOT_RUN.
 */
class CatalogProductVersionFrozenHistoryTest {
    private val f = FrozenCatalogProductInputs

    @Test fun cpr002_emptyContentListAndUsedLegacyIdentitySupplyNoVersion() {
        val absent = assertIs<LocalCatalogProductSelectionV1.NoVersion>(selectLocalCatalogProductVersion(f.shirtId, emptyList()))
        assertEquals(false, absent.legacyIdentityPresent)
        val legacy = f.sku(product = null, selections = null)
        val used = assertIs<LocalCatalogProductSelectionV1.NoVersion>(selectLocalCatalogProductVersion(f.shirtId, listOf(legacy)))
        assertEquals(true, used.legacyIdentityPresent)
        val attemptedUpgrade = f.sku(f.body(revision = Revision(0)), publicationId = f.publication(43), skuRevision = Revision(42))
        assertNotNull(localCatalogProductHistoryRejection(listOf(legacy, attemptedUpgrade)), "CPR-002.E")
        assertIs<LocalCatalogProductSelectionV1.Conflict>(selectLocalCatalogProductVersion(f.shirtId, listOf(legacy, attemptedUpgrade)))
        assertNull(legacy.productDefinition)
        assertNull(legacy.variantValues)
        // No supplied list distinguishes actual unavailable/unreadable/partial native
        // history. CPR-002.A-D require the real boundary; the Room slice covers absence.
    }

    @Test fun cpr002_rawNonzeroBodyWithoutBaselineAndGappedContentRefuseSelection() {
        val rawSeven = listOf(f.sku())
        val gap = f.contentChain().filter { it.productDefinition?.revision?.value in setOf(0L, 1L, 3L) }
        for ((variant, definitions) in listOf("F raw 7 without baseline" to rawSeven, "G missing 2" to gap)) {
            val before = definitions.map { LocalConfigurationCodec.canonicalPublication(it) }
            assertNotNull(localCatalogProductHistoryRejection(definitions), "CPR-002.$variant")
            assertIs<LocalCatalogProductSelectionV1.Conflict>(selectLocalCatalogProductVersion(f.shirtId, definitions), variant)
            definitions.zip(before).forEach { (definition, bytes) ->
                assertContentEquals(bytes, LocalConfigurationCodec.canonicalPublication(definition), variant)
            }
        }
        // These raw inputs have no root signature/completion evidence. A real signed
        // candidate's immutable retention and operational refusal remain unverified.
    }

    @Test fun cpr003_unchangedBodiesSelectSevenIndependentlyOfSkuAndCatalogCounters() {
        val definitions = f.contentChain()
        assertNull(localCatalogProductHistoryRejection(definitions))
        val selected = assertIs<LocalCatalogProductSelectionV1.Selected>(selectLocalCatalogProductVersion(f.shirtId, definitions))
        f.assertShirt(selected.definition, "CPR-003 exact reused body")
        assertEquals(Revision(41), definitions.first { it.skuId == f.blueMediumId && it.productDefinition?.revision == Revision(7) }.skuRevision)
        assertEquals(Revision(12), definitions.first { it.skuId == f.blackSmallId }.skuRevision)
        assertEquals(setOf(Revision(101)), definitions.map { it.catalogRevision }.toSet())
        assertEquals(f.blueMedium(), definitions.first { it.skuId == f.blueMediumId }.variantValues)
        assertEquals(f.blackSmall(), definitions.first { it.skuId == f.blackSmallId }.variantValues)
    }

    @Test fun cpr004_eachExplicitFrozenEditKeepsIdentityAndSelectsExactlyEight() {
        for ((template, edited) in f.edits()) {
            val original = f.shirt()
            val bytes = LocalConfigurationCodec.canonicalProductDefinition(original)
            val candidate = f.sku(edited, publicationId = f.publication(43), skuRevision = Revision(42),
                active = edited.status != LocalProductStatusV1.ARCHIVED)
            val definitions = f.contentChain() + candidate
            assertNull(localCatalogProductHistoryRejection(definitions), template)
            val selected = assertIs<LocalCatalogProductSelectionV1.Selected>(selectLocalCatalogProductVersion(f.shirtId, definitions), template)
            assertEquals(f.shirtId, selected.definition.id, template)
            assertEquals(Revision(8), selected.definition.revision, template)
            assertEquals(edited, selected.definition, template)
            assertEquals(Revision(42), candidate.skuRevision, template)
            assertEquals(Revision(101), candidate.catalogRevision, template)
            f.assertShirt(original, template)
            assertContentEquals(bytes, LocalConfigurationCodec.canonicalProductDefinition(original), template)
        }
        // The archived template uses an inactive content specimen solely for revision
        // comparison; this makes no assertion about a cascade or archive activation.
    }

    @Test fun cpr005_identicalBodyCanDescribeAnotherExplicitDistinctCombination() {
        val blueSmall = listOf(LocalVariantValueV1(f.color, Code.exact("blue")), LocalVariantValueV1(f.size, Code.exact("s")))
        val extra = f.sku(skuId = f.extraSku, publicationId = f.publication(43), skuRevision = Revision(0), selections = blueSmall)
        val definitions = f.contentChain() + extra
        assertNull(localCatalogProductShapeRejection(extra))
        assertNull(localCatalogProductHistoryRejection(definitions))
        val selected = assertIs<LocalCatalogProductSelectionV1.Selected>(selectLocalCatalogProductVersion(f.shirtId, definitions))
        f.assertShirt(selected.definition, "CPR-005.A")
        assertEquals(blueSmall, extra.variantValues)
        assertEquals(setOf(f.blueMediumId, f.blackSmallId, f.extraSku), definitions.map { it.skuId }.toSet())
    }

    @Test fun cpr005_everyCompleteBodyFieldRewriteAtSameVersionRefusesComparison() {
        val original = f.shirt()
        val changedAttributes = original.attributes.toMutableList().also {
            it[0] = it[0].copy(value = LocalProductAttributeValueV1.Text(Code.exact("linen")))
        }
        val candidates = listOf(
            "B English" to f.body(names = LocalNamesV1("Summer cotton shirt", "")),
            "C Arabic" to f.body(names = LocalNamesV1("Cotton shirt", "قميص قطني")),
            "D categories" to f.body(categories = listOf(f.clothing, f.tops, f.seasonal)),
            "E axes" to f.body(axes = listOf(LocalVariantAxisV1(f.color, Code.exact("color"),
                listOf(Code.exact("blue"), Code.exact("black"), Code.exact("green"))), original.variantAxes[1])),
            "F typed attributes" to f.body(attributes = changedAttributes),
            "G status" to f.body(status = LocalProductStatusV1.ARCHIVED))
        val originalBytes = LocalConfigurationCodec.canonicalProductDefinition(original)
        for ((variant, changed) in candidates) {
            val candidate = f.sku(changed, publicationId = f.publication(43), skuRevision = Revision(42),
                active = changed.status != LocalProductStatusV1.ARCHIVED)
            assertNull(localCatalogProductShapeRejection(candidate), variant)
            assertNotNull(localCatalogProductHistoryRejection(f.contentChain() + candidate), "CPR-005.$variant")
            assertIs<LocalCatalogProductSelectionV1.Conflict>(selectLocalCatalogProductVersion(f.shirtId, f.contentChain() + candidate), variant)
            f.assertShirt(original, variant)
            assertContentEquals(originalBytes, LocalConfigurationCodec.canonicalProductDefinition(original), variant)
        }
    }

    @Test fun cpr007_signedCategoryAxisValueAndAttributeOrderCannotBeSortedAway() {
        val original = f.shirt()
        val variants = listOf(
            "categories" to f.body(categories = original.categoryIds.reversed()),
            "axes" to f.body(axes = original.variantAxes.reversed()),
            "axis values" to f.body(axes = listOf(LocalVariantAxisV1(f.color, Code.exact("color"),
                original.variantAxes[0].values.reversed()), original.variantAxes[1])),
            "attributes" to f.body(attributes = original.attributes.reversed()))
        val originalBytes = LocalConfigurationCodec.canonicalProductDefinition(original)
        for ((variant, changed) in variants) {
            val candidate = f.sku(changed, publicationId = f.publication(43), skuRevision = Revision(42))
            assertNull(localCatalogProductShapeRejection(candidate), variant)
            assertNotNull(localCatalogProductHistoryRejection(f.contentChain() + candidate), "CPR-007.G $variant")
            assertTrue(!originalBytes.contentEquals(LocalConfigurationCodec.canonicalProductDefinition(changed)), variant)
            f.assertShirt(original, variant)
            assertContentEquals(originalBytes, LocalConfigurationCodec.canonicalProductDefinition(original), variant)
        }
    }

    @Test fun cpr006_freshRegressionRewriteAndResetCannotReplaceEight_contentGateOnly() {
        val latest = f.edits().first { it.first == "SHIRT_8_NAME_EDIT" }.second
        val latestSku = f.sku(latest, publicationId = f.publication(43), skuRevision = Revision(42))
        val current = f.contentChain() + latestSku
        val originalBytes = LocalConfigurationCodec.canonicalProductDefinition(f.shirt())
        val variants = listOf(
            "A regression 7" to f.shirt(),
            "B rewrite 8" to f.body(revision = Revision(8), names = LocalNamesV1("Mutable later draft", "draft second name")),
            "C reset 0" to f.body(revision = Revision(0)))
        for ((variant, product) in variants) {
            val candidate = f.sku(product, publicationId = f.publication(44), skuRevision = Revision(43))
            assertNotNull(localCatalogProductActivationRejection(candidate, latestSku, current + candidate), "CPR-006.$variant")
            val selected = assertIs<LocalCatalogProductSelectionV1.Selected>(selectLocalCatalogProductVersion(f.shirtId, current), variant)
            assertEquals(Revision(8), selected.definition.revision, variant)
            assertEquals(latest, selected.definition, variant)
            assertContentEquals(originalBytes, LocalConfigurationCodec.canonicalProductDefinition(f.shirt()), variant)
        }
        // Original native re-review, root retention, active pointers, durable refusal
        // and business conservation require their unchanged L2/L3 cases.
    }
}
