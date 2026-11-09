package com.ukkera.pos.catalog.store.independent.catalogunitsv3

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.LocalUnitConversionV1
import com.ukkera.pos.catalog.store.localConfigurationJson
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.CodeNormalization
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/** CUA-01/02/08 L1-V only; no value here grants publication or branch authority. */
class CatalogUnitValuesV3Test {
    private val f = CatalogUnitsV3Fixture

    @Test
    fun cua01_all_frozen_dimensions_and_scale_boundaries_preserve_meaning() {
        for (dimension in listOf(UnitDimension.COUNT, UnitDimension.MASS, UnitDimension.VOLUME,
            UnitDimension.LENGTH, UnitDimension.AREA, UnitDimension.DURATION)) {
            for (scale in listOf(0, 6)) {
                val unit = CatalogUnit(f.piece, Code.exact("000012"), dimension, scale)
                assertEquals("40000000-dddd-4000-8000-000000000001", unit.id.value)
                assertEquals("000012", unit.code.original)
                assertEquals("000012", unit.code.normalizedKey)
                assertEquals(CodeNormalization.EXACT_V1, unit.code.normalization)
                assertEquals(dimension, unit.dimension)
                assertEquals(scale, unit.maximumScale)
            }
        }
    }

    @Test
    fun cua01_exact_codes_retain_leading_zeroes_spaces_and_case() {
        for (text in listOf("a", "000012", " g ", "G", "g")) {
            val code = Code.exact(text)
            assertEquals(text, code.original)
            assertEquals(text, code.normalizedKey)
            assertEquals(CodeNormalization.EXACT_V1, code.normalization)
        }
        assertFailsWith<IllegalArgumentException> { Code(" g ", "g", CodeNormalization.EXACT_V1) }
    }

    @Test
    fun cua01_utf16_boundaries_include_paired_scalars() {
        for (text in listOf("a", "a".repeat(256), "\uD83D\uDE00".repeat(128))) {
            val code = Code.exact(text)
            assertEquals(text, code.original)
            assertEquals(text, code.normalizedKey)
        }
        for (text in listOf("", "a".repeat(257), "\uD83D\uDE00".repeat(128) + "a", "\uD800", "\uDC00")) {
            assertFailsWith<IllegalArgumentException>("Frozen invalid UTF-16 code") { Code.exact(text) }
        }
    }

    @Test
    fun cua01_noncanonical_ids_and_unsupported_unit_scales_are_refused() {
        for (text in listOf("40000000-DDDD-4000-8000-000000000001", "40000000dddd40008000000000000001", "")) {
            assertFailsWith<IllegalArgumentException> { DomainId(text) }
        }
        for (scale in listOf(-1, 7)) {
            assertFailsWith<IllegalArgumentException> { CatalogUnit(f.piece, Code.exact("piece"), UnitDimension.COUNT, scale) }
        }
    }

    @Test
    fun cua02_positive_components_reduce_to_frozen_canonical_rates() {
        assertEquals("12/1", UnitConversionRate.ratio(24, 2).canonicalString())
        assertEquals("1/3", UnitConversionRate.ratio(1, 3).canonicalString())
        assertEquals("1/1", UnitConversionRate.ratio(Long.MAX_VALUE, Long.MAX_VALUE).canonicalString())
    }

    @Test
    fun cua02_zero_negative_and_overflowing_components_are_refused() {
        for ((numerator, denominator) in listOf(0L to 1L, 12L to 0L, -12L to 1L, 12L to -1L, -12L to -1L)) {
            assertFailsWith<IllegalArgumentException> { UnitConversionRate.ratio(numerator, denominator) }
        }
        assertFailsWith<IllegalArgumentException> { UnitConversionRate.parseCanonical("9223372036854775808/1") }
    }

    @Test
    fun cua02_canonical_rate_reader_refuses_equivalent_noncanonical_spelling() {
        assertEquals("12/1", UnitConversionRate.parseCanonical("12/1").canonicalString())
        for (text in listOf("24/2", "12/01", "+12/1", " 12/1 ")) {
            assertFailsWith<IllegalArgumentException> { UnitConversionRate.parseCanonical(text) }
        }
    }

    @Test
    fun cua02_conversion_preserves_exact_identity_revision_and_nullable_evidence() {
        for (revision in listOf(0L, Long.MAX_VALUE)) {
            for (evidence in listOf(null, f.evidence)) {
                val value = LocalUnitConversionV1(f.conversion, Revision(revision), f.carton, f.piece,
                    UnitConversionRate.ratio(12, 1), evidence).domain(f.productA)
                assertEquals(f.conversion, value.id)
                assertEquals(revision, value.revision.value)
                assertEquals(f.productA, value.productId)
                assertEquals(f.carton, value.sourceUnitId)
                assertEquals(f.piece, value.targetUnitId)
                assertEquals("12/1", value.rate.canonicalString())
                assertEquals(evidence, value.massVolumeEvidenceId)
            }
        }
        assertFailsWith<IllegalArgumentException> { Revision(-1) }
        assertFails { localConfigurationJson.decodeFromString(Revision.serializer(), "\"9223372036854775808\"") }
        assertFailsWith<IllegalArgumentException> {
            LocalUnitConversionV1(f.conversion, Revision(1), f.piece, f.piece, UnitConversionRate.ratio(12, 1)).domain(f.productA)
        }
    }

    @Test
    fun cua08_signed_storage_boundaries_preserve_scaled_integer_and_scale() {
        for ((scaled, scale) in listOf(Long.MIN_VALUE to 0, 0L to 0, Long.MAX_VALUE to 6)) {
            val quantity = Quantity(f.kilogram, scaled, scale)
            assertEquals(f.kilogram, quantity.unitId)
            assertEquals(scaled, quantity.scaled)
            assertEquals(scale, quantity.scale)
        }
        assertEquals(Long.MAX_VALUE, Quantity.parse(f.kilogram, "9223372036854.775807", 6).scaled)
        assertEquals(Long.MIN_VALUE, Quantity.parse(f.kilogram, "-9223372036854775808", 0).scaled)
    }

    @Test
    fun cua08_storage_overflow_scales_and_nonpositive_business_input_are_refused() {
        for (text in listOf("-9223372036854775809", "9223372036854775808")) {
            assertFails { Quantity.parse(f.kilogram, text, 0) }
        }
        for (scale in listOf(-1, 7)) {
            assertFailsWith<IllegalArgumentException> { Quantity(f.kilogram, 1, scale) }
        }
        for (scaled in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> { Quantity(f.kilogram, scaled, 0).requirePositive() }
        }
    }
}
