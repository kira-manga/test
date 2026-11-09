package com.ukkera.pos.catalog.store.independent.catalogreleasesourcev1

import com.ukkera.pos.catalog.store.LocalCatalogReleaseCodecV1
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalPublishedProductV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalVariantValueV1
import com.ukkera.pos.catalog.store.localCatalogProductShapeRejection
import com.ukkera.pos.catalog.store.localCatalogReleaseHistoryRejection
import com.ukkera.pos.catalog.store.localCatalogReleaseMemberContentRejection
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Additional unchanged CSR-FROZEN-1 supplied-content slices, separate from the
 * original fixture and methods. No native originals, signatures, completions,
 * grants, activation, recovery, protected read or RPC are supplied.
 */
class CatalogReleaseSourceFrozenVariantContentTest {
    private val fixture = CatalogReleaseSourceFrozenVariantFixture

    @Test fun csr004_exactFrozenAllowedMatricesSurviveCanonicalRoundTrip() {
        val shirtRows = listOf(
            listOf(LocalVariantValueV1(fixture.axisId("size"), Code.exact("M")),
                LocalVariantValueV1(fixture.axisId("color"), Code.exact("BLUE"))),
            listOf(LocalVariantValueV1(fixture.axisId("size"), Code.exact("L")),
                LocalVariantValueV1(fixture.axisId("color"), Code.exact("BLACK"))),
        )
        val bagRows = listOf(listOf(LocalVariantValueV1(fixture.axisId("style"), Code.exact("STANDARD"))))
        for ((alias, rows) in listOf("SHIRT-P0" to shirtRows, "SHIRT-P1" to shirtRows,
            "SHIRT-P2" to shirtRows, "BAG-P0" to bagRows)) {
            val product = fixture.product(alias)
            assertEquals(rows, product.allowedVariantCombinations, alias)
            val bytes = LocalConfigurationCodec.canonicalProductDefinition(product)
            val decoded = Json.decodeFromString(LocalPublishedProductV1.serializer(), bytes.decodeToString())
            assertEquals(product, decoded, alias)
            assertEquals(rows, decoded.allowedVariantCombinations, alias)
            assertContentEquals(bytes, LocalConfigurationCodec.canonicalProductDefinition(decoded), alias)
        }
        for ((alias, sku) in fixture.definitions) {
            assertNull(localCatalogProductShapeRejection(sku), alias)
            val bytes = LocalConfigurationCodec.canonicalPublication(sku)
            val decoded = decodeSuppliedSku(bytes)
            assertEquals(sku, decoded, alias)
            assertEquals(sku.productDefinition, decoded.productDefinition, alias)
            assertContentEquals(bytes, LocalConfigurationCodec.canonicalPublication(decoded), alias)
        }
    }

    @Test fun csr004_exactFrozenMBlackSelectionIsRefusedWithoutNeighboringHistory() {
        val valid = fixture.sku("SKU-BLUE-3")
        val selection = listOf(
            LocalVariantValueV1(fixture.axisId("size"), Code.exact("M")),
            LocalVariantValueV1(fixture.axisId("color"), Code.exact("BLACK")),
        )
        val invalid = valid.copy(variantValues = selection)
        val product = assertNotNull(invalid.productDefinition)
        assertEquals(selection, invalid.variantValues)
        assertTrue(selection.all { choice -> product.variantAxes.single { it.id == choice.axisId }.values.contains(choice.value) })
        assertTrue(assertNotNull(product.allowedVariantCombinations).none { row ->
            row.associate { it.axisId to it.value } == selection.associate { it.axisId to it.value }
        })
        assertNull(localCatalogProductShapeRejection(valid))
        assertNotNull(localCatalogProductShapeRejection(invalid))

        // Exact altered bytes, not an old digest or another SKU revision, drive refusal.
        val member = fixture.member(invalid)
        val singleton = fixture.releases[0].copy(members = listOf(member))
        assertEquals(sha256(LocalConfigurationCodec.canonicalPublication(invalid)), member.contentDigest)
        assertNotNull(localCatalogReleaseMemberContentRejection(singleton, listOf(invalid)))
        val fullRelease = fixture.releases[0].copy(members = fixture.releases[0].members.map {
            if (it.publicationId == invalid.publicationId) member else it
        })
        val fullDefinitions = fixture.definitions.values.map {
            if (it.publicationId == invalid.publicationId) invalid else it
        }
        assertNotNull(localCatalogReleaseMemberContentRejection(fullRelease, fullDefinitions))
    }

    @Test fun csr002_exactFrozenBulkAxisAndSixtyFourMembersQualifyAsSuppliedContent() {
        val expectedCodes = listOf(
            "01", "02", "03", "04", "05", "06", "07", "08", "09", "10",
            "11", "12", "13", "14", "15", "16", "17", "18", "19", "20",
            "21", "22", "23", "24", "25", "26", "27", "28", "29", "30",
            "31", "32", "33", "34", "35", "36", "37", "38", "39", "40",
            "41", "42", "43", "44", "45", "46", "47", "48", "49", "50",
            "51", "52", "53", "54", "55", "56", "57", "58", "59", "60",
            "61", "62", "63", "64", "65",
        )
        val product = fixture.product("BULK-P0")
        assertEquals(DomainId("b1000000-0000-4000-8000-000000000001"), product.id)
        assertEquals(Revision(0), product.revision)
        assertEquals("Bulk product", product.names.english)
        assertEquals("منتج تجريبي", product.names.arabic)
        assertTrue(product.attributes.isEmpty())
        val axis = product.variantAxes.single()
        assertEquals(Code.exact("code"), axis.code)
        assertEquals(expectedCodes.map { Code.exact(it) }, axis.values)
        val rows = expectedCodes.map { listOf(LocalVariantValueV1(fixture.axisId("code"), Code.exact(it))) }
        assertEquals(rows, product.allowedVariantCombinations)
        val productBytes = LocalConfigurationCodec.canonicalProductDefinition(product)
        val decodedProduct = Json.decodeFromString(LocalPublishedProductV1.serializer(), productBytes.decodeToString())
        assertEquals(product, decodedProduct)
        assertEquals(rows, decodedProduct.allowedVariantCombinations)
        assertContentEquals(productBytes, LocalConfigurationCodec.canonicalProductDefinition(decodedProduct))

        assertEquals(expectedCodes.map { "BULK-SKU-$it" }, fixture.bulkDefinitions.keys.toList())
        assertEquals(65, fixture.bulkMembers.size)
        for ((index, code) in expectedCodes.withIndex()) {
            val sku = fixture.bulkDefinitions.getValue("BULK-SKU-$code")
            assertEquals(DomainId("b3000000-0000-4000-8000-${code.padStart(12, '0')}"), sku.skuId)
            assertEquals(Revision(0), sku.skuRevision)
            assertEquals("Bulk member $code", sku.names.english)
            assertEquals(listOf("009900$code"), sku.barcodes)
            assertEquals(product, sku.productDefinition)
            assertEquals(rows[index], sku.variantValues)
            assertTrue(sku.canSell)
            assertTrue(sku.canPurchase)
            assertNull(localCatalogProductShapeRejection(sku), code)
            val bytes = LocalConfigurationCodec.canonicalPublication(sku)
            val decoded = decodeSuppliedSku(bytes)
            assertEquals(sku, decoded, code)
            assertContentEquals(bytes, LocalConfigurationCodec.canonicalPublication(decoded), code)
            val member = fixture.bulkMembers[index]
            assertEquals(sku.publicationId, member.publicationId)
            assertEquals(sha256(bytes), member.contentDigest)
            assertEquals(sku.skuId, member.skuId)
            assertEquals(Revision(0), member.skuRevision)
            assertEquals(product.id, member.productId)
            assertEquals(Revision(0), member.productRevision)
        }

        val release = fixture.bulkRelease64
        assertEquals(DomainId("b2000000-0000-4000-8000-000000000001"), release.catalogId)
        assertEquals(Revision(0), release.revision)
        assertNull(release.previous)
        assertEquals(64, release.members.size)
        assertEquals(fixture.bulkMembers.take(64), release.members)
        val bytes = LocalCatalogReleaseCodecV1.canonicalPublication(release)
        val decoded = LocalCatalogReleaseCodecV1.decodeCanonicalPublication(bytes)
        assertEquals(release, decoded)
        assertEquals(fixture.bulkMembers.take(64), decoded.members)
        assertContentEquals(bytes, LocalCatalogReleaseCodecV1.canonicalPublication(decoded))
        assertNull(localCatalogReleaseHistoryRejection(listOf(decoded)))
        assertNull(localCatalogReleaseMemberContentRejection(decoded, fixture.bulkDefinitions.values.toList()))
    }

    @Test fun csr002_exactSixtyFiveFrozenBulkMembersAreConstructorRefused() {
        val members = fixture.bulkMembers
        assertEquals(65, members.size)
        assertEquals(65, members.map { it.skuId }.distinct().size)
        assertEquals(65, members.map { it.publicationId }.distinct().size)
        for ((index, sku) in fixture.bulkDefinitions.values.withIndex()) {
            assertNull(localCatalogProductShapeRejection(sku))
            assertEquals(sha256(LocalConfigurationCodec.canonicalPublication(sku)), members[index].contentDigest)
        }
        assertEquals(members.take(64), fixture.bulkRelease64.members)
        assertFailsWith<IllegalArgumentException> { fixture.bulkRelease64.copy(members = members) }
    }

    /** The public SKU serializer decodes supplied bytes; this is not native envelope evidence. */
    private fun decodeSuppliedSku(bytes: ByteArray): LocalPublishedSkuV1 {
        val text = bytes.decodeToString()
        val prefix = CompanyBusinessPublicationKind.CATALOG_SKU_V1.domain
        assertTrue(text.startsWith(prefix))
        return Json.decodeFromString(LocalPublishedSkuV1.serializer(), text.removePrefix(prefix))
    }
}
