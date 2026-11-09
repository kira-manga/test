package com.ukkera.pos.catalog.store.independent.catalogunitsv3

import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.decodeValue
import com.ukkera.pos.catalog.store.localCatalogUnitShapeRejection
import com.ukkera.pos.database.StoredBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** CUA-03: the two byte oracles are literal frozen inputs, never encoder output. */
class CatalogPublicationCodecV3Test {
    private val f = CatalogUnitsV3Fixture

    private fun readCanonical(text: String): LocalPublishedSkuV1 = decodeValue(
        StoredBytes(text.substringAfter('\n').encodeToByteArray()), LocalPublishedSkuV1.serializer())

    @Test
    fun cua03_legacy_encoding_is_the_independent_literal_golden() {
        assertContentEquals(f.LEGACY_CANONICAL.encodeToByteArray(), LocalConfigurationCodec.canonicalPublication(f.legacy()))
    }

    @Test
    fun cua03_new_reader_preserves_legacy_unknown_authority_and_reencoding() {
        val value = readCanonical(f.LEGACY_CANONICAL)
        assertEquals("000012", value.code)
        assertEquals(1L, value.catalogRevision.value)
        assertEquals(1L, value.skuRevision.value)
        assertNull(value.unitDefinitions)
        assertNull(value.returnPromise)
        assertContentEquals(f.LEGACY_CANONICAL.encodeToByteArray(), LocalConfigurationCodec.canonicalPublication(value))
    }

    @Test
    fun cua03_extended_encoding_and_reader_are_the_independent_literal_golden() {
        val definition = f.legacy().copy(unitDefinitions = listOf(f.pieceDefinition))
        assertContentEquals(f.EXTENDED_CANONICAL.encodeToByteArray(), LocalConfigurationCodec.canonicalPublication(definition))
        val read = readCanonical(f.EXTENDED_CANONICAL)
        assertEquals(listOf(f.pieceDefinition), read.unitDefinitions)
        assertContentEquals(f.EXTENDED_CANONICAL.encodeToByteArray(), LocalConfigurationCodec.canonicalPublication(read))
    }

    @Test
    fun cua03_present_empty_extension_remains_distinct_and_cannot_resolve_base() {
        val expected = f.LEGACY_CANONICAL.dropLast(1) + ",\"unitDefinitions\":[]}"
        val value = f.legacy().copy(unitDefinitions = emptyList())
        assertContentEquals(expected.encodeToByteArray(), LocalConfigurationCodec.canonicalPublication(value))
        assertEquals(emptyList(), readCanonical(expected).unitDefinitions)
        assertNotNull(localCatalogUnitShapeRejection(value))
    }

    @Test
    fun cua03_changed_unit_precision_changes_canonical_content() {
        val changed = f.legacy().copy(unitDefinitions = listOf(f.pieceDefinition.copy(maximumScale = 1)))
        assertNotEquals(f.EXTENDED_CANONICAL, LocalConfigurationCodec.canonicalPublication(changed).decodeToString())
    }

    @Test
    fun cua03_reader_refuses_frozen_unknown_fields_and_invalid_unit_or_sku_shapes() {
        val mutations = listOf(
            f.EXTENDED_CANONICAL.dropLast(1) + ",\"unknown\":true}",
            f.EXTENDED_CANONICAL.replace("\"maximumScale\":0}", "\"maximumScale\":0,\"unknown\":true}"),
            f.EXTENDED_CANONICAL.replace("\"code\":\"000012\"", "\"code\":{\"original\":\"000012\",\"normalizedKey\":\"000012\",\"normalization\":\"EXACT_V1\"}"),
            f.EXTENDED_CANONICAL.replace("\"dimension\":\"COUNT\"", "\"dimension\":\"WEIGHT\""),
            f.EXTENDED_CANONICAL.replace("\"maximumScale\":0", "\"maximumScale\":7"),
            f.EXTENDED_CANONICAL.replace("\"normalizedKey\":\"piece\"", "\"normalizedKey\":\"g\""),
        )
        for (mutation in mutations) assertFails("Frozen malformed reader variant") { readCanonical(mutation) }
    }

    @Test
    fun cua03_reader_refuses_noncanonical_revision_json_shapes() {
        for (field in listOf("catalogRevision", "skuRevision")) {
            for (replacement in listOf("1", "{\"value\":1}", "\"01\"", "\"1e0\"")) {
                val mutation = f.LEGACY_CANONICAL.replace("\"$field\":\"1\"", "\"$field\":$replacement")
                assertFails("Frozen noncanonical $field") { readCanonical(mutation) }
            }
        }
    }
}
