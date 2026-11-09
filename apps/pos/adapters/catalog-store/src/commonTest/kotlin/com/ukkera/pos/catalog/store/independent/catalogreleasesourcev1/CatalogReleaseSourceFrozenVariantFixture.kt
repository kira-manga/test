package com.ukkera.pos.catalog.store.independent.catalogreleasesourcev1

import com.ukkera.pos.catalog.store.LocalCatalogReleaseMemberV1
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPriceV1
import com.ukkera.pos.catalog.store.LocalProductAttributeV1
import com.ukkera.pos.catalog.store.LocalProductAttributeValueV1
import com.ukkera.pos.catalog.store.LocalProductStatusV1
import com.ukkera.pos.catalog.store.LocalPublishedCatalogReleaseV1
import com.ukkera.pos.catalog.store.LocalPublishedProductV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.LocalVariantAxisV1
import com.ukkera.pos.catalog.store.LocalVariantValueV1
import com.ukkera.pos.catalog.store.localCatalogReleaseReference
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant

/**
 * Separate explicit-matrix/full-BULK projection of unchanged CSR-FROZEN-1 inputs.
 * The original CatalogReleaseSourceFrozenFixture stays unchanged for prior slices.
 * Publication aliases become disposable supplied-content IDs, NOT native originals.
 * Digests hash the exact instantiated canonical SKU bytes, never a claimed native cut.
 * Required wire-only IDs/status/tax/effectivity below are explicit inert carriers.
 * No unit, category, tax, grant, native custody or completed-history qualification follows.
 * Every declared frozen combination is carried explicitly, including all 65 BULK rows.
 * BULK SKU prices and Arabic names are unspecified; zero-EGP/empty-name carriers
 * satisfy required DTO fields without becoming frozen price/tax/translation oracles.
 */
internal object CatalogReleaseSourceFrozenVariantFixture {
    internal val company: CompanyScope = CompanyScope(metadataId(1),
        DomainId("10000000-0000-4000-8000-000000000001"))
    internal val otherCompany: CompanyScope = CompanyScope(company.organizationId,
        DomainId("20000000-0000-4000-8000-000000000001"))
    internal val branchId: DomainId = DomainId("11000000-0000-4000-8000-000000000001")
    internal val catalogId: DomainId = DomainId("30000000-0000-4000-8000-000000000001")
    internal val otherCatalogId: DomainId = DomainId("40000000-0000-4000-8000-000000000001")
    internal val bulkCatalogId: DomainId = DomainId("b2000000-0000-4000-8000-000000000001")

    private data class AxisInput(val label: String, val values: List<String>)
    private data class ProductInput(val alias: String, val id: DomainId, val revision: Revision,
        val names: LocalNamesV1, val category: String, val textAttributes: Map<String, String>, val axes: List<AxisInput>,
        val allowedCombinations: List<Map<String, String>>)
    private data class SkuInput(val alias: String, val id: DomainId, val revision: Revision,
        val productAlias: String, val names: LocalNamesV1, val selected: Map<String, String>,
        val barcode: String, val priceMinor: Long)
    private data class ReleaseInput(val alias: String, val revision: Revision,
        val previousAlias: String?, val memberAliases: List<String>)

    private val productInputs = listOf(
        ProductInput("SHIRT-P0", DomainId("50000000-0000-4000-8000-000000000001"), Revision(0),
            LocalNamesV1("Linen shirt", "قميص كتان"), "clothing", mapOf("material" to "linen"),
            listOf(AxisInput("size", listOf("M", "L")), AxisInput("color", listOf("BLUE", "BLACK"))),
            listOf(mapOf("size" to "M", "color" to "BLUE"), mapOf("size" to "L", "color" to "BLACK"))),
        ProductInput("SHIRT-P1", DomainId("50000000-0000-4000-8000-000000000001"), Revision(1),
            LocalNamesV1("Linen shirt, relaxed", "قميص كتان واسع"), "clothing", mapOf("material" to "linen"),
            listOf(AxisInput("size", listOf("M", "L")), AxisInput("color", listOf("BLUE", "BLACK"))),
            listOf(mapOf("size" to "M", "color" to "BLUE"), mapOf("size" to "L", "color" to "BLACK"))),
        ProductInput("SHIRT-P2", DomainId("50000000-0000-4000-8000-000000000001"), Revision(2),
            LocalNamesV1("Linen shirt, relaxed fit", "قميص كتان بقصة واسعة"), "clothing", mapOf("material" to "linen"),
            listOf(AxisInput("size", listOf("M", "L")), AxisInput("color", listOf("BLUE", "BLACK"))),
            listOf(mapOf("size" to "M", "color" to "BLUE"), mapOf("size" to "L", "color" to "BLACK"))),
        ProductInput("BAG-P0", DomainId("60000000-0000-4000-8000-000000000001"), Revision(0),
            LocalNamesV1("Cotton bag", "حقيبة قطنية"), "accessories", mapOf("material" to "cotton"),
            listOf(AxisInput("style", listOf("STANDARD"))),
            listOf(mapOf("style" to "STANDARD"))),
        ProductInput("BULK-P0", DomainId("b1000000-0000-4000-8000-000000000001"), Revision(0),
            LocalNamesV1("Bulk product", "منتج تجريبي"), "fixture", emptyMap(),
            listOf(AxisInput("code", listOf(
                "01", "02", "03", "04", "05", "06", "07", "08", "09", "10",
                "11", "12", "13", "14", "15", "16", "17", "18", "19", "20",
                "21", "22", "23", "24", "25", "26", "27", "28", "29", "30",
                "31", "32", "33", "34", "35", "36", "37", "38", "39", "40",
                "41", "42", "43", "44", "45", "46", "47", "48", "49", "50",
                "51", "52", "53", "54", "55", "56", "57", "58", "59", "60",
                "61", "62", "63", "64", "65",
            ))),
            listOf(
                mapOf("code" to "01"), mapOf("code" to "02"), mapOf("code" to "03"),
                mapOf("code" to "04"), mapOf("code" to "05"), mapOf("code" to "06"),
                mapOf("code" to "07"), mapOf("code" to "08"), mapOf("code" to "09"),
                mapOf("code" to "10"), mapOf("code" to "11"), mapOf("code" to "12"),
                mapOf("code" to "13"), mapOf("code" to "14"), mapOf("code" to "15"),
                mapOf("code" to "16"), mapOf("code" to "17"), mapOf("code" to "18"),
                mapOf("code" to "19"), mapOf("code" to "20"), mapOf("code" to "21"),
                mapOf("code" to "22"), mapOf("code" to "23"), mapOf("code" to "24"),
                mapOf("code" to "25"), mapOf("code" to "26"), mapOf("code" to "27"),
                mapOf("code" to "28"), mapOf("code" to "29"), mapOf("code" to "30"),
                mapOf("code" to "31"), mapOf("code" to "32"), mapOf("code" to "33"),
                mapOf("code" to "34"), mapOf("code" to "35"), mapOf("code" to "36"),
                mapOf("code" to "37"), mapOf("code" to "38"), mapOf("code" to "39"),
                mapOf("code" to "40"), mapOf("code" to "41"), mapOf("code" to "42"),
                mapOf("code" to "43"), mapOf("code" to "44"), mapOf("code" to "45"),
                mapOf("code" to "46"), mapOf("code" to "47"), mapOf("code" to "48"),
                mapOf("code" to "49"), mapOf("code" to "50"), mapOf("code" to "51"),
                mapOf("code" to "52"), mapOf("code" to "53"), mapOf("code" to "54"),
                mapOf("code" to "55"), mapOf("code" to "56"), mapOf("code" to "57"),
                mapOf("code" to "58"), mapOf("code" to "59"), mapOf("code" to "60"),
                mapOf("code" to "61"), mapOf("code" to "62"), mapOf("code" to "63"),
                mapOf("code" to "64"), mapOf("code" to "65"),
            )),
    )
    private val skuInputs = listOf(
        SkuInput("SKU-BLUE-0", DomainId("70000000-0000-4000-8000-000000000001"), Revision(0), "SHIRT-P0",
            LocalNamesV1("Blue shirt M", "قميص أزرق M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 10000L),
        SkuInput("SKU-BLUE-1", DomainId("70000000-0000-4000-8000-000000000001"), Revision(1), "SHIRT-P1",
            LocalNamesV1("Blue shirt M", "قميص أزرق M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 10000L),
        SkuInput("SKU-BLUE-2", DomainId("70000000-0000-4000-8000-000000000001"), Revision(2), "SHIRT-P2",
            LocalNamesV1("Blue shirt M", "قميص أزرق M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 10000L),
        SkuInput("SKU-BLUE-3", DomainId("70000000-0000-4000-8000-000000000001"), Revision(3), "SHIRT-P2",
            LocalNamesV1("Blue shirt / M", "قميص أزرق / M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 12000L),
        SkuInput("SKU-BLUE-4", DomainId("70000000-0000-4000-8000-000000000001"), Revision(4), "SHIRT-P2",
            LocalNamesV1("Blue shirt / M", "قميص أزرق / M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 12500L),
        SkuInput("SKU-BLACK-0", DomainId("80000000-0000-4000-8000-000000000001"), Revision(0), "SHIRT-P0",
            LocalNamesV1("Black shirt L", "قميص أسود L"), mapOf("size" to "L", "color" to "BLACK"), "00011002", 10000L),
        SkuInput("SKU-BLACK-1", DomainId("80000000-0000-4000-8000-000000000001"), Revision(1), "SHIRT-P1",
            LocalNamesV1("Black shirt L", "قميص أسود L"), mapOf("size" to "L", "color" to "BLACK"), "00011002", 10000L),
        SkuInput("SKU-BLACK-2", DomainId("80000000-0000-4000-8000-000000000001"), Revision(2), "SHIRT-P2",
            LocalNamesV1("Black shirt / L", "قميص أسود / L"), mapOf("size" to "L", "color" to "BLACK"), "00011002", 11000L),
        SkuInput("SKU-BAG-0", DomainId("90000000-0000-4000-8000-000000000001"), Revision(0), "BAG-P0",
            LocalNamesV1("Cotton bag", "حقيبة قطنية"), mapOf("style" to "STANDARD"), "00021001", 3000L),
        SkuInput("SKU-BAG-1", DomainId("90000000-0000-4000-8000-000000000001"), Revision(1), "BAG-P0",
            LocalNamesV1("Cotton bag, natural", "حقيبة قطنية طبيعية"), mapOf("style" to "STANDARD"), "00021001", 3500L),
    )
    // Frozen bulk descriptors omit price and Arabic SKU name. These required DTO
    // carriers are not price/tax/translation assertions or native originals.
    private val bulkSkuInputs = listOf(
        SkuInput("BULK-SKU-01", DomainId("b3000000-0000-4000-8000-000000000001"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 01", ""), mapOf("code" to "01"), "00990001", 0L),
        SkuInput("BULK-SKU-02", DomainId("b3000000-0000-4000-8000-000000000002"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 02", ""), mapOf("code" to "02"), "00990002", 0L),
        SkuInput("BULK-SKU-03", DomainId("b3000000-0000-4000-8000-000000000003"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 03", ""), mapOf("code" to "03"), "00990003", 0L),
        SkuInput("BULK-SKU-04", DomainId("b3000000-0000-4000-8000-000000000004"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 04", ""), mapOf("code" to "04"), "00990004", 0L),
        SkuInput("BULK-SKU-05", DomainId("b3000000-0000-4000-8000-000000000005"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 05", ""), mapOf("code" to "05"), "00990005", 0L),
        SkuInput("BULK-SKU-06", DomainId("b3000000-0000-4000-8000-000000000006"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 06", ""), mapOf("code" to "06"), "00990006", 0L),
        SkuInput("BULK-SKU-07", DomainId("b3000000-0000-4000-8000-000000000007"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 07", ""), mapOf("code" to "07"), "00990007", 0L),
        SkuInput("BULK-SKU-08", DomainId("b3000000-0000-4000-8000-000000000008"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 08", ""), mapOf("code" to "08"), "00990008", 0L),
        SkuInput("BULK-SKU-09", DomainId("b3000000-0000-4000-8000-000000000009"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 09", ""), mapOf("code" to "09"), "00990009", 0L),
        SkuInput("BULK-SKU-10", DomainId("b3000000-0000-4000-8000-000000000010"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 10", ""), mapOf("code" to "10"), "00990010", 0L),
        SkuInput("BULK-SKU-11", DomainId("b3000000-0000-4000-8000-000000000011"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 11", ""), mapOf("code" to "11"), "00990011", 0L),
        SkuInput("BULK-SKU-12", DomainId("b3000000-0000-4000-8000-000000000012"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 12", ""), mapOf("code" to "12"), "00990012", 0L),
        SkuInput("BULK-SKU-13", DomainId("b3000000-0000-4000-8000-000000000013"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 13", ""), mapOf("code" to "13"), "00990013", 0L),
        SkuInput("BULK-SKU-14", DomainId("b3000000-0000-4000-8000-000000000014"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 14", ""), mapOf("code" to "14"), "00990014", 0L),
        SkuInput("BULK-SKU-15", DomainId("b3000000-0000-4000-8000-000000000015"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 15", ""), mapOf("code" to "15"), "00990015", 0L),
        SkuInput("BULK-SKU-16", DomainId("b3000000-0000-4000-8000-000000000016"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 16", ""), mapOf("code" to "16"), "00990016", 0L),
        SkuInput("BULK-SKU-17", DomainId("b3000000-0000-4000-8000-000000000017"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 17", ""), mapOf("code" to "17"), "00990017", 0L),
        SkuInput("BULK-SKU-18", DomainId("b3000000-0000-4000-8000-000000000018"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 18", ""), mapOf("code" to "18"), "00990018", 0L),
        SkuInput("BULK-SKU-19", DomainId("b3000000-0000-4000-8000-000000000019"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 19", ""), mapOf("code" to "19"), "00990019", 0L),
        SkuInput("BULK-SKU-20", DomainId("b3000000-0000-4000-8000-000000000020"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 20", ""), mapOf("code" to "20"), "00990020", 0L),
        SkuInput("BULK-SKU-21", DomainId("b3000000-0000-4000-8000-000000000021"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 21", ""), mapOf("code" to "21"), "00990021", 0L),
        SkuInput("BULK-SKU-22", DomainId("b3000000-0000-4000-8000-000000000022"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 22", ""), mapOf("code" to "22"), "00990022", 0L),
        SkuInput("BULK-SKU-23", DomainId("b3000000-0000-4000-8000-000000000023"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 23", ""), mapOf("code" to "23"), "00990023", 0L),
        SkuInput("BULK-SKU-24", DomainId("b3000000-0000-4000-8000-000000000024"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 24", ""), mapOf("code" to "24"), "00990024", 0L),
        SkuInput("BULK-SKU-25", DomainId("b3000000-0000-4000-8000-000000000025"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 25", ""), mapOf("code" to "25"), "00990025", 0L),
        SkuInput("BULK-SKU-26", DomainId("b3000000-0000-4000-8000-000000000026"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 26", ""), mapOf("code" to "26"), "00990026", 0L),
        SkuInput("BULK-SKU-27", DomainId("b3000000-0000-4000-8000-000000000027"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 27", ""), mapOf("code" to "27"), "00990027", 0L),
        SkuInput("BULK-SKU-28", DomainId("b3000000-0000-4000-8000-000000000028"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 28", ""), mapOf("code" to "28"), "00990028", 0L),
        SkuInput("BULK-SKU-29", DomainId("b3000000-0000-4000-8000-000000000029"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 29", ""), mapOf("code" to "29"), "00990029", 0L),
        SkuInput("BULK-SKU-30", DomainId("b3000000-0000-4000-8000-000000000030"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 30", ""), mapOf("code" to "30"), "00990030", 0L),
        SkuInput("BULK-SKU-31", DomainId("b3000000-0000-4000-8000-000000000031"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 31", ""), mapOf("code" to "31"), "00990031", 0L),
        SkuInput("BULK-SKU-32", DomainId("b3000000-0000-4000-8000-000000000032"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 32", ""), mapOf("code" to "32"), "00990032", 0L),
        SkuInput("BULK-SKU-33", DomainId("b3000000-0000-4000-8000-000000000033"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 33", ""), mapOf("code" to "33"), "00990033", 0L),
        SkuInput("BULK-SKU-34", DomainId("b3000000-0000-4000-8000-000000000034"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 34", ""), mapOf("code" to "34"), "00990034", 0L),
        SkuInput("BULK-SKU-35", DomainId("b3000000-0000-4000-8000-000000000035"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 35", ""), mapOf("code" to "35"), "00990035", 0L),
        SkuInput("BULK-SKU-36", DomainId("b3000000-0000-4000-8000-000000000036"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 36", ""), mapOf("code" to "36"), "00990036", 0L),
        SkuInput("BULK-SKU-37", DomainId("b3000000-0000-4000-8000-000000000037"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 37", ""), mapOf("code" to "37"), "00990037", 0L),
        SkuInput("BULK-SKU-38", DomainId("b3000000-0000-4000-8000-000000000038"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 38", ""), mapOf("code" to "38"), "00990038", 0L),
        SkuInput("BULK-SKU-39", DomainId("b3000000-0000-4000-8000-000000000039"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 39", ""), mapOf("code" to "39"), "00990039", 0L),
        SkuInput("BULK-SKU-40", DomainId("b3000000-0000-4000-8000-000000000040"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 40", ""), mapOf("code" to "40"), "00990040", 0L),
        SkuInput("BULK-SKU-41", DomainId("b3000000-0000-4000-8000-000000000041"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 41", ""), mapOf("code" to "41"), "00990041", 0L),
        SkuInput("BULK-SKU-42", DomainId("b3000000-0000-4000-8000-000000000042"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 42", ""), mapOf("code" to "42"), "00990042", 0L),
        SkuInput("BULK-SKU-43", DomainId("b3000000-0000-4000-8000-000000000043"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 43", ""), mapOf("code" to "43"), "00990043", 0L),
        SkuInput("BULK-SKU-44", DomainId("b3000000-0000-4000-8000-000000000044"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 44", ""), mapOf("code" to "44"), "00990044", 0L),
        SkuInput("BULK-SKU-45", DomainId("b3000000-0000-4000-8000-000000000045"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 45", ""), mapOf("code" to "45"), "00990045", 0L),
        SkuInput("BULK-SKU-46", DomainId("b3000000-0000-4000-8000-000000000046"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 46", ""), mapOf("code" to "46"), "00990046", 0L),
        SkuInput("BULK-SKU-47", DomainId("b3000000-0000-4000-8000-000000000047"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 47", ""), mapOf("code" to "47"), "00990047", 0L),
        SkuInput("BULK-SKU-48", DomainId("b3000000-0000-4000-8000-000000000048"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 48", ""), mapOf("code" to "48"), "00990048", 0L),
        SkuInput("BULK-SKU-49", DomainId("b3000000-0000-4000-8000-000000000049"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 49", ""), mapOf("code" to "49"), "00990049", 0L),
        SkuInput("BULK-SKU-50", DomainId("b3000000-0000-4000-8000-000000000050"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 50", ""), mapOf("code" to "50"), "00990050", 0L),
        SkuInput("BULK-SKU-51", DomainId("b3000000-0000-4000-8000-000000000051"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 51", ""), mapOf("code" to "51"), "00990051", 0L),
        SkuInput("BULK-SKU-52", DomainId("b3000000-0000-4000-8000-000000000052"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 52", ""), mapOf("code" to "52"), "00990052", 0L),
        SkuInput("BULK-SKU-53", DomainId("b3000000-0000-4000-8000-000000000053"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 53", ""), mapOf("code" to "53"), "00990053", 0L),
        SkuInput("BULK-SKU-54", DomainId("b3000000-0000-4000-8000-000000000054"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 54", ""), mapOf("code" to "54"), "00990054", 0L),
        SkuInput("BULK-SKU-55", DomainId("b3000000-0000-4000-8000-000000000055"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 55", ""), mapOf("code" to "55"), "00990055", 0L),
        SkuInput("BULK-SKU-56", DomainId("b3000000-0000-4000-8000-000000000056"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 56", ""), mapOf("code" to "56"), "00990056", 0L),
        SkuInput("BULK-SKU-57", DomainId("b3000000-0000-4000-8000-000000000057"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 57", ""), mapOf("code" to "57"), "00990057", 0L),
        SkuInput("BULK-SKU-58", DomainId("b3000000-0000-4000-8000-000000000058"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 58", ""), mapOf("code" to "58"), "00990058", 0L),
        SkuInput("BULK-SKU-59", DomainId("b3000000-0000-4000-8000-000000000059"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 59", ""), mapOf("code" to "59"), "00990059", 0L),
        SkuInput("BULK-SKU-60", DomainId("b3000000-0000-4000-8000-000000000060"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 60", ""), mapOf("code" to "60"), "00990060", 0L),
        SkuInput("BULK-SKU-61", DomainId("b3000000-0000-4000-8000-000000000061"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 61", ""), mapOf("code" to "61"), "00990061", 0L),
        SkuInput("BULK-SKU-62", DomainId("b3000000-0000-4000-8000-000000000062"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 62", ""), mapOf("code" to "62"), "00990062", 0L),
        SkuInput("BULK-SKU-63", DomainId("b3000000-0000-4000-8000-000000000063"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 63", ""), mapOf("code" to "63"), "00990063", 0L),
        SkuInput("BULK-SKU-64", DomainId("b3000000-0000-4000-8000-000000000064"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 64", ""), mapOf("code" to "64"), "00990064", 0L),
        SkuInput("BULK-SKU-65", DomainId("b3000000-0000-4000-8000-000000000065"), Revision(0), "BULK-P0",
            LocalNamesV1("Bulk member 65", ""), mapOf("code" to "65"), "00990065", 0L),
    )

    private val releaseInputs = listOf(
        ReleaseInput("REL-0", Revision(0), null, listOf("SKU-BLUE-3", "SKU-BLACK-2", "SKU-BAG-0")),
        ReleaseInput("REL-1", Revision(1), "REL-0", listOf("SKU-BAG-1", "SKU-BLACK-2", "SKU-BLUE-3")),
        ReleaseInput("REL-2", Revision(2), "REL-1", listOf("SKU-BLUE-3", "SKU-BAG-1", "SKU-BLACK-2")),
        ReleaseInput("REL-3", Revision(3), "REL-2", listOf("SKU-BAG-1", "SKU-BLUE-4")),
        ReleaseInput("REL-4", Revision(4), "REL-3", listOf("SKU-BLUE-4", "SKU-BAG-1")),
    )

    private val axisIds = mapOf("size" to metadataId(201), "color" to metadataId(202),
        "style" to metadataId(203), "code" to metadataId(204))
    private val categoryIds = mapOf("clothing" to metadataId(301), "accessories" to metadataId(302),
        "fixture" to metadataId(303))
    private val baseUnitId = metadataId(401)

    /** Exact semantic inputs, not an accepting fixture-side validator. */
    internal val declaredAllowedCombinations: Map<String, List<Map<String, String>>> =
        productInputs.associate { it.alias to it.allowedCombinations }

    private val productDefinitions: Map<String, LocalPublishedProductV1> = productInputs.associate { input ->
        input.alias to LocalPublishedProductV1(input.id, input.revision, input.names,
            listOf(categoryIds.getValue(input.category)), input.axes.map { axis ->
                LocalVariantAxisV1(axisIds.getValue(axis.label), Code.exact(axis.label), axis.values.map { Code.exact(it) })
            }, input.textAttributes.map { (key, value) ->
                LocalProductAttributeV1(Code.exact(key), LocalProductAttributeValueV1.Text(Code.exact(value)))
            }, LocalProductStatusV1.ACTIVE, allowedVariantCombinations = input.allowedCombinations.map { row ->
                row.map { (axis, value) -> LocalVariantValueV1(axisIds.getValue(axis), Code.exact(value)) }
            })
    }

    internal val definitions: Map<String, LocalPublishedSkuV1> = buildDefinitions(skuInputs, 0)
    internal val bulkDefinitions: Map<String, LocalPublishedSkuV1> = buildDefinitions(bulkSkuInputs, skuInputs.size)

    private fun buildDefinitions(inputs: List<SkuInput>, offset: Int): Map<String, LocalPublishedSkuV1> =
        inputs.withIndex().associate { (at, input) ->
            val index = offset + at
            val product = productDefinitions.getValue(input.productAlias)
            // Regular and BULK frozen products agree on these SKU-owned traits:
            // STOCKED/UNIT/precision0, nonserial/nonlot/noexpiry, RETAIL, purchasable/sellable,
            // no production/recipe/kit/deposit, tax included and category STANDARD.
            // Product status and the old SKU catalogRevision are wire carriers, not inferred counters.
            input.alias to LocalPublishedSkuV1(company, metadataId(101 + index), input.id, product.id,
                Revision(0), input.revision, input.names, "CSR-${input.id.value}", listOf(input.barcode),
                baseUnitId, 0, LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL, false, true, true, true,
                emptyList(), listOf(LocalPriceV1(metadataId(501 + index), Revision(0), null, 0,
                    Quantity(baseUnitId, 0L, 0), effectiveFrom = UtcInstant.parseCanonical("1970-01-01T00:00:00Z"),
                    unitPrice = UnitPrice(Currency.EGP, exactEgp(input.priceMinor)), inclusion = TaxInclusion.INCLUSIVE,
                    tax = TaxSnapshot(metadataId(601), Revision(0), "STANDARD", PercentageRate.parse("0")))),
                productDefinition = product,
                variantValues = input.selected.map { (axis, value) -> LocalVariantValueV1(axisIds.getValue(axis), Code.exact(value)) })
        }

    internal val releases: List<LocalPublishedCatalogReleaseV1> = run {
        val byAlias = linkedMapOf<String, LocalPublishedCatalogReleaseV1>()
        for ((index, input) in releaseInputs.withIndex()) {
            val previous = input.previousAlias?.let { localCatalogReleaseReference(byAlias.getValue(it)) }
            byAlias[input.alias] = LocalPublishedCatalogReleaseV1(company, catalogId, metadataId(701 + index),
                input.revision, previous, input.memberAliases.map { member(sku(it)) })
        }
        byAlias.values.toList()
    }

    /** All 65 exact descriptors, with each digest taken from its supplied canonical body. */
    internal val bulkMembers: List<LocalCatalogReleaseMemberV1> = bulkDefinitions.values.map { member(it) }
    internal val bulkRelease64: LocalPublishedCatalogReleaseV1 = LocalPublishedCatalogReleaseV1(
        company, bulkCatalogId, metadataId(706), Revision(0), null, bulkMembers.take(64))

    internal fun sku(alias: String): LocalPublishedSkuV1 = definitions.getValue(alias)
    internal fun product(alias: String): LocalPublishedProductV1 = productDefinitions.getValue(alias)
    internal fun axisId(label: String): DomainId = axisIds.getValue(label)

    internal fun member(sku: LocalPublishedSkuV1): LocalCatalogReleaseMemberV1 {
        val product = checkNotNull(sku.productDefinition)
        return LocalCatalogReleaseMemberV1(sku.publicationId, digest(sku), sku.skuId, sku.skuRevision, product.id, product.revision)
    }

    internal fun digest(sku: LocalPublishedSkuV1): PayloadDigest {
        val canonical = LocalConfigurationCodec.canonicalPublication(sku)
        return try { sha256(canonical) } finally { canonical.fill(0) }
    }

    internal fun metadataId(index: Int): DomainId =
        DomainId("d0000000-0000-4000-8000-${index.toString().padStart(12, '0')}")

    private fun exactEgp(minor: Long): ExactDecimal =
        ExactDecimal.parse("${minor / 100}.${(minor % 100).toString().padStart(2, '0')}")
}
