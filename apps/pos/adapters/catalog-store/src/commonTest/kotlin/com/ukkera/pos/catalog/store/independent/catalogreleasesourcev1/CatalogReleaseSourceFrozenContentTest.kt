package com.ukkera.pos.catalog.store.independent.catalogreleasesourcev1

import com.ukkera.pos.catalog.store.LocalCatalogReleaseCodecV1
import com.ukkera.pos.catalog.store.LocalCatalogReleaseProfileV1
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPublishedCatalogReleaseV1
import com.ukkera.pos.catalog.store.LocalPublishedProductV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalVariantValueV1
import com.ukkera.pos.catalog.store.localCatalogReleaseHistoryRejection
import com.ukkera.pos.catalog.store.localCatalogReleaseMemberContentRejection
import com.ukkera.pos.catalog.store.localCatalogReleaseReference
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSR-FROZEN-1 bounded supplied-content slices only. No native owner, completion,
 * branch grant, activation, current protected read, recovery or RPC is supplied.
 * All whole cases/profiles remain UNBOUND / NOT_RUN; see README.md for the exact
 * supported field projection, assertion mapping and unresolved schema facets.
 */
class CatalogReleaseSourceFrozenContentTest {
    private val fixture = CatalogReleaseSourceFrozenFixture

    @Test fun csr001_releaseCoordinateAndCodecKeepIndependentCounters() {
        val release = fixture.releases[0]
        val blue = fixture.sku("SKU-BLUE-3")
        val product = assertNotNull(blue.productDefinition)
        val canonical = LocalCatalogReleaseCodecV1.canonicalPublication(release)
        val decoded = LocalCatalogReleaseCodecV1.decodeCanonicalPublication(canonical)
        val reference = localCatalogReleaseReference(decoded)

        assertEquals(release, decoded)
        assertEquals(DomainId("30000000-0000-4000-8000-000000000001"), reference.catalogId)
        assertEquals(release.publicationId, reference.publicationId)
        assertEquals(Revision(0), reference.revision)
        assertEquals(sha256(canonical), reference.contentDigest)
        assertNotEquals(blue.publicationId, reference.publicationId)
        assertNotEquals(reference.catalogId, reference.publicationId)
        assertEquals(Revision(3), blue.skuRevision)
        assertEquals(Revision(2), product.revision)
        assertEquals(Revision(3), decoded.members[0].skuRevision)
        assertEquals(Revision(2), decoded.members[0].productRevision)
        assertNotEquals(Revision(72), reference.revision)

        assertFailsWith<IllegalArgumentException> {
            release.copy(publicationId = blue.publicationId, revision = blue.skuRevision)
        }
        assertNotNull(localCatalogReleaseHistoryRejection(listOf(release.copy(revision = Revision(72)))))
        assertContentEquals(canonical, LocalCatalogReleaseCodecV1.canonicalPublication(release))
    }

    @Test fun csr002_zeroAndOneMemberConstructorSlicesPreserveTheMember() {
        val release = fixture.releases[0]
        assertFailsWith<IllegalArgumentException> { release.copy(members = emptyList()) }
        val one = release.copy(members = listOf(release.members[0]))
        val decoded = LocalCatalogReleaseCodecV1.decodeCanonicalPublication(
            LocalCatalogReleaseCodecV1.canonicalPublication(one))
        assertEquals(listOf(release.members[0]), decoded.members)
        assertNull(localCatalogReleaseMemberContentRejection(decoded, fixture.definitions.values.toList()))
        // The full frozen 64/65-member product fixture cannot be translated into
        // the current 32-values-per-axis DTO. Do not replace it with other bodies.
        assertEquals(64, LocalCatalogReleaseProfileV1.MAX_MEMBERS)
    }

    @Test fun csr002_declaredOrderAndCommaNamesSurviveCanonicalRoundTrip() {
        val expected = listOf(
            listOf("Blue shirt / M", "Black shirt / L", "Cotton bag"),
            listOf("Cotton bag, natural", "Black shirt / L", "Blue shirt / M"),
            listOf("Blue shirt / M", "Cotton bag, natural", "Black shirt / L"),
        )
        for (index in expected.indices) {
            val release = fixture.releases[index]
            val decoded = LocalCatalogReleaseCodecV1.decodeCanonicalPublication(
                LocalCatalogReleaseCodecV1.canonicalPublication(release))
            assertEquals(release.members, decoded.members)
            val names = decoded.members.map { member ->
                fixture.definitions.values.single { it.publicationId == member.publicationId }.names.english
            }
            assertEquals(expected[index], names)
            assertEquals(3, names.size)
        }
    }

    @Test fun csr002_suppliedMutableManifestCannotRewriteItsOwnedOrder() {
        val source = fixture.releases[1]
        val members = source.members.toMutableList()
        val owned = source.copy(members = members)
        val before = LocalCatalogReleaseCodecV1.canonicalPublication(owned)
        members.reverse()
        members.clear()
        assertEquals(source.members, owned.members)
        assertContentEquals(before, LocalCatalogReleaseCodecV1.canonicalPublication(owned))
        assertContentEquals(LocalCatalogReleaseCodecV1.canonicalPublication(source), before)
    }

    @Test fun csr002_opaque49152And49153ByteProbesAreNotValidReleaseBodies() {
        val atBound = ByteArray(49_152) { 120.toByte() }
        val overBound = ByteArray(49_153) { 120.toByte() }
        assertEquals(49_152, LocalCatalogReleaseProfileV1.MAX_PUBLICATION_BYTES)
        assertTrue(atBound.size <= LocalCatalogReleaseProfileV1.MAX_PUBLICATION_BYTES)
        assertTrue(overBound.size > LocalCatalogReleaseProfileV1.MAX_PUBLICATION_BYTES)
        assertFailsWith<IllegalArgumentException> { LocalCatalogReleaseCodecV1.decodeCanonicalPublication(atBound) }
        assertFailsWith<IllegalArgumentException> { LocalCatalogReleaseCodecV1.decodeCanonicalPublication(overBound) }
        // The at-bound probe satisfies only size, never domain/content/native validity.
        assertTrue(atBound.all { it == 120.toByte() })
        assertTrue(overBound.all { it == 120.toByte() })
    }

    @Test fun csr003_eachMissingMemberCoordinateFailsStrictCanonicalDecode() {
        for (field in listOf("publicationId", "contentDigest", "skuId", "skuRevision", "productId", "productRevision")) {
            val bytes = changedCanonical(fixture.releases[0]) { root ->
                val members = root.getValue("members").jsonArray.toMutableList()
                members[0] = JsonObject(members[0].jsonObject.toMutableMap().apply { remove(field) })
                JsonObject(root.toMutableMap().apply { put("members", JsonArray(members)) })
            }
            assertFails(field) { LocalCatalogReleaseCodecV1.decodeCanonicalPublication(bytes) }
        }
    }

    @Test fun csr003_eachWrongMemberCoordinateIsIndependentlyRefused() {
        val release = fixture.releases[0]
        val original = release.members[0]
        val bag = fixture.sku("SKU-BAG-0")
        val candidates = listOf(
            "WRONG-PUBLICATION" to original.copy(publicationId = bag.publicationId),
            "WRONG-DIGEST" to original.copy(contentDigest = fixture.digest(fixture.sku("SKU-BLUE-2"))),
            "WRONG-SKU-ID" to original.copy(skuId = bag.skuId),
            "WRONG-SKU-REVISION" to original.copy(skuRevision = Revision(4)),
            "WRONG-PRODUCT-ID" to original.copy(productId = bag.productId),
            "WRONG-PRODUCT-REVISION" to original.copy(productRevision = Revision(1)),
        )
        assertNull(localCatalogReleaseMemberContentRejection(release, fixture.definitions.values.toList()))
        for ((label, member) in candidates) {
            assertMemberContentRefused(label) {
                release.copy(members = listOf(member) + release.members.drop(1))
            }
        }
    }

    @Test fun csr003_duplicateSkuAndPublicationRemainDuplicateDespiteDifferentCounters() {
        val release = fixture.releases[0]
        assertFailsWith<IllegalArgumentException> {
            release.copy(members = listOf(fixture.member(fixture.sku("SKU-BLUE-3")),
                fixture.member(fixture.sku("SKU-BLUE-4"))))
        }
        val members = release.members.toMutableList()
        members[1] = members[1].copy(publicationId = members[0].publicationId)
        assertFailsWith<IllegalArgumentException> { release.copy(members = members) }
    }

    @Test fun csr004_regularMappedProductBodiesPinTwoAcrossDifferentSkuRevisions() {
        val blue = fixture.sku("SKU-BLUE-3")
        val black = fixture.sku("SKU-BLACK-2")
        val blueProduct = assertNotNull(blue.productDefinition)
        val blackProduct = assertNotNull(black.productDefinition)
        assertEquals(Revision(3), blue.skuRevision)
        assertEquals(Revision(2), black.skuRevision)
        assertEquals(Revision(2), blueProduct.revision)
        assertEquals(blueProduct, blackProduct)
        assertEquals("Linen shirt, relaxed fit", blueProduct.names.english)
        assertEquals("قميص كتان بقصة واسعة", blueProduct.names.arabic)
        assertContentEquals(LocalConfigurationCodec.canonicalProductDefinition(blueProduct),
            LocalConfigurationCodec.canonicalProductDefinition(blackProduct))
        assertNull(localCatalogReleaseMemberContentRejection(fixture.releases[0], fixture.definitions.values.toList()))
    }

    @Test fun csr004_missingProductLegacyProductAndMissingVariantCannotQualify() {
        val blue = fixture.sku("SKU-BLUE-3")
        val candidates = listOf(
            "MISSING-PRODUCT" to blue.copy(productDefinition = null),
            "LEGACY-PRODUCT" to blue.copy(productDefinition = null, variantValues = null),
            "MISSING-VARIANT" to blue.copy(variantValues = null),
        )
        for ((label, candidate) in candidates) {
            val (release, definitions) = substitutedBody(candidate)
            assertNotNull(localCatalogReleaseMemberContentRejection(release, definitions), label)
        }
    }

    @Test fun csr004_exactFrozenMBlackMutationCannotQualifyTheFullSuppliedHistory() {
        val invalid = fixture.sku("SKU-BLUE-3").copy(variantValues = listOf(
            LocalVariantValueV1(fixture.axisId("size"), Code.exact("M")),
            LocalVariantValueV1(fixture.axisId("color"), Code.exact("BLACK")),
        ))
        assertTrue(fixture.declaredAllowedCombinations.getValue("SHIRT-P2").none {
            it["size"] == "M" && it["color"] == "BLACK"
        })
        val (release, definitions) = substitutedBody(invalid)
        assertNotNull(localCatalogReleaseMemberContentRejection(release, definitions))
        // This is a full-history identity/refusal slice. The standalone frozen
        // allowed-combination matrix has no DTO representation and stays UNBOUND.
    }

    @Test fun csr004_conflictingSameBodyVersionAndDifferentPinnedVersionsCannotQualify() {
        val black = fixture.sku("SKU-BLACK-2")
        val product = assertNotNull(black.productDefinition)
        val conflicting = black.copy(productDefinition = LocalPublishedProductV1(product.id, product.revision,
            LocalNamesV1("Conflicting shirt", product.names.arabic), product.categoryIds, product.variantAxes,
            product.attributes, product.status))
        val (bodyRelease, bodyDefinitions) = substitutedBody(conflicting)
        assertNotNull(localCatalogReleaseMemberContentRejection(bodyRelease, bodyDefinitions))

        val differentRevision = black.copy(productDefinition = fixture.product("SHIRT-P1"))
        val (versionRelease, versionDefinitions) = substitutedBody(differentRevision, Revision(1))
        assertNotNull(localCatalogReleaseMemberContentRejection(versionRelease, versionDefinitions))
    }

    @Test fun csr004_p0ToP2WithoutEitherP1ProvenanceSourceCannotQualify() {
        val complete = fixture.definitions.values.toList()
        assertNull(localCatalogReleaseMemberContentRejection(fixture.releases[0], complete))
        val gapped = complete.filterNot { it.productId == fixture.sku("SKU-BLUE-3").productId &&
            it.productDefinition?.revision == Revision(1) }
        assertTrue(gapped.none { it.productDefinition == fixture.product("SHIRT-P1") })
        assertNotNull(localCatalogReleaseMemberContentRejection(fixture.releases[0], gapped))
    }

    @Test fun csr006_completeReleaseSequenceKeepsEveryExactPredecessorAndFullMemberSlice() {
        val releases = fixture.releases
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), releases.map { it.revision.value })
        assertNull(releases[0].previous)
        for (index in 1 until releases.size) {
            assertEquals(localCatalogReleaseReference(releases[index - 1]), releases[index].previous)
        }
        assertNull(localCatalogReleaseHistoryRejection(releases))
        for (release in releases) {
            assertNull(localCatalogReleaseMemberContentRejection(release, fixture.definitions.values.toList()))
        }
        assertEquals(listOf(fixture.sku("SKU-BAG-1").skuId, fixture.sku("SKU-BLUE-4").skuId), releases[3].members.map { it.skuId })
        assertEquals(listOf(fixture.sku("SKU-BLUE-4").skuId, fixture.sku("SKU-BAG-1").skuId), releases[4].members.map { it.skuId })
    }

    @Test fun csr006_everyFrozenSequenceMutationIsRefusedByTheSuppliedHistoryGate() {
        val r = fixture.releases
        val predecessor = assertNotNull(r[2].previous)
        fun replacing(index: Int, replacement: LocalPublishedCatalogReleaseV1): List<LocalPublishedCatalogReleaseV1> =
            r.mapIndexed { at, release -> if (at == index) replacement else release }
        val candidates = listOf(
            "NONZERO-GENESIS" to listOf(r[1]),
            "MISSING-PREDECESSOR" to listOf(r[0], r[1].copy(previous = null)),
            "MISSING-INTERMEDIATE" to listOf(r[0], r[2]),
            "PLUS-TWO" to listOf(r[0], r[1].copy(revision = Revision(2))),
            "DUPLICATE-VERSION" to (r + r[1].copy(publicationId = fixture.metadataId(901))),
            "REWRITTEN-ORIGINAL" to replacing(1, r[1].copy(members = r[2].members)),
            "WRONG-PREDECESSOR-ID" to replacing(2, r[2].copy(previous = predecessor.copy(
                publicationId = fixture.sku("SKU-BLUE-3").publicationId))),
            "WRONG-PREDECESSOR-REVISION" to replacing(2, r[2].copy(previous = predecessor.copy(revision = Revision(0)))),
            "WRONG-PREDECESSOR-DIGEST" to replacing(2, r[2].copy(previous = predecessor.copy(
                contentDigest = localCatalogReleaseReference(r[0]).contentDigest))),
            "CATALOG-CHANGE" to replacing(2, r[2].copy(catalogId = fixture.otherCatalogId)),
            "COMPANY-CHANGE" to replacing(2, r[2].copy(company = fixture.otherCompany)),
        )
        for ((label, candidate) in candidates) assertNotNull(localCatalogReleaseHistoryRejection(candidate), label)
    }

    @Test fun csr006_negativeAndInt64OverflowScalarsCannotWrap() {
        for (decimal in listOf("-1", "9223372036854775808")) {
            val canonicalRevision = JsonPrimitive(decimal)
            assertTrue(canonicalRevision.isString)
            assertEquals("\"$decimal\"", canonicalRevision.toString())
            val rawRevision = Json.parseToJsonElement(decimal)
            assertTrue(rawRevision is JsonPrimitive && !rawRevision.isString)
            assertEquals(decimal, rawRevision.toString())
            for ((form, revision) in listOf("canonical-string" to canonicalRevision, "raw-numeric" to rawRevision)) {
                val bytes = changedCanonical(fixture.releases[0]) { root ->
                    JsonObject(root.toMutableMap().apply { put("revision", revision) })
                }
                assertFails("$form/$decimal") { LocalCatalogReleaseCodecV1.decodeCanonicalPublication(bytes) }
            }
        }
        val current = Revision(9_223_372_036_854_775_807L)
        assertFailsWith<ArithmeticException> { current.next() }
        assertEquals(9_223_372_036_854_775_807L, current.value)
    }

    private fun assertMemberContentRefused(label: String, candidate: () -> LocalPublishedCatalogReleaseV1) {
        val construction = runCatching(candidate)
        val failure = construction.exceptionOrNull()
        if (failure != null) {
            assertTrue(failure is IllegalArgumentException, label)
        } else {
            assertNotNull(localCatalogReleaseMemberContentRejection(construction.getOrThrow(), fixture.definitions.values.toList()), label)
        }
    }

    /** Recompute exact supplied bytes so an altered-body refusal is not just an old digest mismatch. */
    private fun substitutedBody(sku: LocalPublishedSkuV1, memberProductRevision: Revision? = null):
        Pair<LocalPublishedCatalogReleaseV1, List<LocalPublishedSkuV1>> {
        val release = fixture.releases[0].copy(members = fixture.releases[0].members.map { member ->
            if (member.publicationId != sku.publicationId) member else member.copy(contentDigest = fixture.digest(sku),
                productRevision = memberProductRevision ?: member.productRevision)
        })
        val definitions = fixture.definitions.values.map { if (it.publicationId == sku.publicationId) sku else it }
        return release to definitions
    }

    private fun changedCanonical(release: LocalPublishedCatalogReleaseV1, change: (JsonObject) -> JsonObject): ByteArray {
        val canonical = LocalCatalogReleaseCodecV1.canonicalPublication(release)
        val prefix = CompanyBusinessPublicationKind.CATALOG_RELEASE_V1.domain
        val text = canonical.decodeToString().removePrefix(prefix)
        val body = Json.parseToJsonElement(text).jsonObject
        return (prefix + change(body).toString()).encodeToByteArray()
    }
}
