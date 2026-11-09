package com.ukkera.pos.app.gateway.independent.catalogproductversionv1

import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.gateway.productDetailValues
import com.ukkera.pos.catalog.store.LocalConfigurationDetailV1
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalProductAttributeV1
import com.ukkera.pos.catalog.store.LocalProductAttributeValueV1
import com.ukkera.pos.catalog.store.LocalProductStatusV1
import com.ukkera.pos.catalog.store.LocalPublishedProductV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.LocalVariantAxisV1
import com.ukkera.pos.catalog.store.LocalVariantValueV1
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * CPR-FROZEN-1 L1 mapping of supplied pinned read projections only.
 * Fixture IDs, both names, ordered lists, typed Decimal 30 and selections are
 * bound unchanged from CPR-FIXTURE-1. Presentation separators are parsed only
 * to inspect those fields; localized copy is not a new product oracle.
 * No native source, authorized Master read, signing, activation, history/recovery,
 * business effects or transport is supplied. Whole L2/L3 variants stay UNBOUND / NOT_RUN.
 */
class CatalogProductVersionFrozenMappingTest {
    private val shirtId = DomainId("50000000-0000-4000-8000-000000000002")
    private val newId = DomainId("50000000-0000-4000-8000-000000000001")
    private val clothing = DomainId("a1000000-0000-4000-8000-000000000001")
    private val tops = DomainId("a1000000-0000-4000-8000-000000000002")
    private val retail = DomainId("a1000000-0000-4000-8000-000000000004")
    private val color = DomainId("a2000000-0000-4000-8000-000000000001")
    private val size = DomainId("a2000000-0000-4000-8000-000000000002")

    @Test fun cpr007_and015_pinnedShirtDisplaysCompleteOrderedIndependentBody() {
        val rows = productDetailValues(detail(shirt()))
        assertIdentityAndNames(rows, shirtId, "7", "Cotton shirt", "")
        assertEquals("active", rows[3].value.english.lowercase())
        assertShirtMetadata(metadata(rows))
        // The independently supplied SKU/publication counters are 41/101; the
        // displayed product field is 7 because the pinned body says 7.
    }

    @Test fun cpr013_newerProjectionCannotChangeMappingOfOriginalPinnedSeven() {
        val original = detail(shirt())
        val before = productDetailValues(original)
        val newer = detail(shirt(8, "Summer cotton shirt", "")).copy(skuRevision = Revision(42))
        val latestRows = productDetailValues(newer)
        assertIdentityAndNames(latestRows, shirtId, "8", "Summer cotton shirt", "")
        assertShirtMetadata(metadata(latestRows))
        val reread = productDetailValues(original)
        assertEquals(before, reread)
        assertIdentityAndNames(reread, shirtId, "7", "Cotton shirt", "")
        assertShirtMetadata(metadata(reread))
        // Original native identity/cut and authentic re-review remain unbound.
    }

    @Test fun cpr007_arabicOnlyBodyKeepsBothLiteralNameFieldsIndependent() {
        val rows = productDetailValues(detail(shirt(8, "", "قميص قطني")))
        assertIdentityAndNames(rows, shirtId, "8", "", "قميص قطني")
        assertShirtMetadata(metadata(rows))
    }

    @Test fun cpr001_newSimpleBodyMapsZeroAndExplicitEmptySelection_contentOnly() {
        val body = LocalPublishedProductV1(newId, Revision(0), LocalNamesV1("Plain stocked item", ""),
            listOf(retail), emptyList(), listOf(LocalProductAttributeV1(Code.exact("note"),
                LocalProductAttributeValueV1.Text(Code.exact("synthetic")))), LocalProductStatusV1.ACTIVE)
        val input = detail(body, emptyList()).copy(skuId = DomainId("60000000-0000-4000-8000-000000000001"),
            names = LocalNamesV1("Plain stocked item", ""), code = "NEW-01", barcodes = emptyList())
        val rows = productDetailValues(input)
        assertIdentityAndNames(rows, newId, "0", "Plain stocked item", "")
        val lines = metadata(rows).lines()
        assertEquals(listOf(retail.value), categoryIds(lines))
        assertEquals(emptyList(), axisLines(lines))
        assertEquals(emptyList(), selectionLines(lines))
        assertEquals(listOf(Triple("note", "TEXT", "synthetic")), attributes(lines))
        // A supplied body is not native known-empty proof or permission to create it.
    }

    @Test fun cpr008_absentBodyMapsUnavailableWithoutRevisionFallback() {
        val rows = productDetailValues(detail(null, null))
        assertEquals(1, rows.size)
        assertTrue(rows.single().value.english.contains("unavailable", ignoreCase = true))
        assertTrue(!rows.single().value.english.contains(shirtId.value))
        // Supported historic bytes/signatures and migration are not supplied here.
    }

    @Test fun cpr010_and015_missingSelectionsOrProductMismatchDoNotMapAcceptingBody() {
        assertFails("CPR-010.F missing selections") { productDetailValues(detail(shirt(), null)) }
        assertFails("CPR-015 wrong product") { productDetailValues(detail(shirt()).copy(productId = newId)) }
    }

    @Test fun cpr011_literalBusinessLikeTextAndDecimalStayCompleteMetadata() {
        val rows = productDetailValues(detail(shirt(8, note = "stock.receive(100)")))
        assertIdentityAndNames(rows, shirtId, "8", "Cotton shirt", "")
        assertShirtMetadata(metadata(rows), "stock.receive(100)")
    }

    private fun shirt(revision: Long = 7, english: String = "Cotton shirt", arabic: String = "",
        note: String = "synthetic"): LocalPublishedProductV1 = LocalPublishedProductV1(shirtId, Revision(revision),
        LocalNamesV1(english, arabic), listOf(clothing, tops), listOf(
            LocalVariantAxisV1(color, Code.exact("color"), listOf(Code.exact("blue"), Code.exact("black"))),
            LocalVariantAxisV1(size, Code.exact("size"), listOf(Code.exact("s"), Code.exact("m")))), listOf(
            LocalProductAttributeV1(Code.exact("material"), LocalProductAttributeValueV1.Text(Code.exact("cotton"))),
            LocalProductAttributeV1(Code.exact("wash_temperature"), LocalProductAttributeValueV1.Decimal(ExactDecimal.parse("30"))),
            LocalProductAttributeV1(Code.exact("note"), LocalProductAttributeValueV1.Text(Code.exact(note)))), LocalProductStatusV1.ACTIVE)

    private fun detail(body: LocalPublishedProductV1?, selections: List<LocalVariantValueV1>? = listOf(
        LocalVariantValueV1(color, Code.exact("blue")), LocalVariantValueV1(size, Code.exact("m")))): LocalConfigurationDetailV1.Sku =
        LocalConfigurationDetailV1.Sku(DomainId("60000000-0000-4000-8000-000000000002"), body?.id ?: shirtId,
            Revision(41), Revision(101), LocalNamesV1("Cotton shirt", ""), "SHIRT-BLUE-M-01", listOf("0000000000002"),
            DomainId("b1000000-0000-4000-8000-000000000001"), 0, LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL,
            true, true, false, false, emptyList(), emptyList(), productDefinition = body, variantValues = selections)

    private fun assertIdentityAndNames(rows: List<LocalRecordValue>, id: DomainId, revision: String,
        english: String, arabic: String) {
        assertEquals(listOf(id.value, revision), rows[0].value.english.split('/').map { it.trim() })
        assertEquals(english, rows[1].value.english)
        assertEquals(arabic, rows[2].value.english)
    }

    private fun metadata(rows: List<LocalRecordValue>): String = rows.drop(4).joinToString("") { it.value.english }

    private fun assertShirtMetadata(value: String, note: String = "synthetic") {
        val lines = value.lines()
        assertEquals(listOf(clothing.value, tops.value), categoryIds(lines))
        val axes = axisLines(lines).map { line ->
            Triple(line.substringBefore(" / "), line.substringAfter(" / ").substringBefore(": "),
                line.substringAfter(": ").split(", "))
        }
        assertEquals(listOf(Triple(color.value, "color", listOf("blue", "black")),
            Triple(size.value, "size", listOf("s", "m"))), axes)
        val choices = selectionLines(lines).map { it.substringBefore(" / ") to it.substringAfter(" / ") }
        assertEquals(listOf(color.value to "blue", size.value to "m"), choices)
        assertEquals(listOf(Triple("material", "TEXT", "cotton"), Triple("wash_temperature", "DECIMAL", "30"),
            Triple("note", "TEXT", note)), attributes(lines))
    }

    private fun categoryIds(lines: List<String>): List<String> = lines.filter {
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(it)
    }

    private fun axisLines(lines: List<String>): List<String> = lines.filter {
        (it.startsWith("${color.value} / ") || it.startsWith("${size.value} / ")) && ": " in it
    }

    private fun selectionLines(lines: List<String>): List<String> = lines.filter {
        (it.startsWith("${color.value} / ") || it.startsWith("${size.value} / ")) && ": " !in it
    }

    private fun attributes(lines: List<String>): List<Triple<String, String, String>> = lines.filter {
        it.startsWith("material: ") || it.startsWith("wash_temperature: ") || it.startsWith("note: ")
    }.map { line ->
        Triple(line.substringBefore(": "), line.substringAfter(": ").substringBefore(" / "), line.substringAfter(" / "))
    }
}
