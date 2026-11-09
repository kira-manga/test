package com.ukkera.pos.catalog.store.independent.foodfloordirectoryv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Semantic inputs transcribed from food-floor-directory-v1/fixtures.json. */
internal object FfdCatalogInputs {
    val scope = BranchScope(id("10ad0100-0000-4000-8000-000000000001"),
        id("20ad0200-0000-4000-8000-000000000001"), id("30ad0300-0000-4000-8000-000000000001"))
    val configurator = id("40ad0400-0000-4000-8000-000000000001")
    val unknownFloor = id("f1000000-0000-4000-8000-000000000098")
    val unknownTable = id("a2000000-0000-4000-8000-000000000098")
    val floorA = LocalFoodFloorDefinitionV1(id("f1000000-0000-4000-8000-000000000010"),
        LocalNamesV1("Dining 10", "صالة ١٠"), 700, true)
    val floorB = LocalFoodFloorDefinitionV1(id("f1000000-0000-4000-8000-000000000020"),
        LocalNamesV1("Terrace 02", "شرفة ٠٢"), 100, false)
    val table010 = LocalFoodTableDefinitionV1(id("a2000000-0000-4000-8000-000000000010"), floorA.id,
        LocalNamesV1("Table 002", "طاولة ٠٠٢"), 9000, true)
    val table020 = LocalFoodTableDefinitionV1(id("a2000000-0000-4000-8000-000000000020"), floorB.id,
        LocalNamesV1("", "طاولة الشرفة"), 3, false)
    val table030 = LocalFoodTableDefinitionV1(id("a2000000-0000-4000-8000-000000000030"), floorA.id,
        LocalNamesV1("007", ""), 0, false)
    val newFloor = LocalFoodFloorDefinitionV1(id("f1000000-0000-4000-8000-000000000030"),
        LocalNamesV1("New floor 003", "صالة جديدة ٠٠٣"), 42, true)
    val newTable = LocalFoodTableDefinitionV1(id("a2000000-0000-4000-8000-000000000040"), newFloor.id,
        LocalNamesV1("004", "طاولة ٠٠٤"), 17, true)
    val replacement = newTable.copy(names = LocalNamesV1(" 004 North ", "طاولة ٠٠٤ شمال"), layoutOrder = 0, active = false)
    val defaultNames = LocalNamesV1("Configured Update", "تحديث الإعداد")
    const val LARGE_REVISION = 9_007_199_254_740_993L
    val rejectionCodes = listOf("food.floor.stale", "food.floor.capacity", "food.floor.identity_conflict",
        "food.floor.active_tables", "food.table.floor_required", "food.table.floor_changed")

    data class NameVector(val label: String, val english: String, val arabic: String, val accepts: Boolean)
    val names = listOf(
        NameVector("ASCII160", "x".repeat(160), "", true),
        NameVector("ASCII161", "x".repeat(161), "", false),
        NameVector("EMOJI160", "😀".repeat(80), "", true),
        NameVector("EMOJI161", "😀".repeat(80) + "x", "", false),
        NameVector("BLANK", "  ", "", false),
        NameVector("ARABIC_ONLY", "", "صالة عربية ٠٠٧", true),
        NameVector("LITERAL_SPACES", "  Hall 007  ", "", true),
        NameVector("DECOMPOSED_ACCENT", "Cafe\u0301", "", true),
        NameVector("PRECOMPOSED_ACCENT", "Café", "", true),
        NameVector("NON_ASCII_FORMAT_LITERAL", "A\u202eB", "", true),
    )
    fun controls() = ((0..31).toList() + 127).map { code ->
        NameVector("ASCII_CONTROLS_$code", "A${code.toChar()}B", "", false)
    }
    fun capacityFloor(serial: Int) = LocalFoodFloorDefinitionV1(
        serialId("f2000000", serial), LocalNamesV1("Floor ${serial.toString().padStart(2, '0')}", "صالة"),
        9999 - serial, false)
    fun capacityTable(serial: Int) = LocalFoodTableDefinitionV1(
        serialId("a3000000", serial), capacityFloor(if (serial <= 45) 1 else 2).id,
        LocalNamesV1("Table ${serial.toString().padStart(3, '0')}", "طاولة"), 100 - serial, false)
    fun operation(case: Int, within: Int) = OperationId("d4000000-0000-4000-8000-${hex(case * 1000 + within)}")
    fun seedOperation(serial: Int) = OperationId("d3000000-0000-4000-8000-${hex(serial)}")
    fun serialId(prefix: String, serial: Int) = id("$prefix-0000-4000-8000-${hex(serial)}")
    private fun hex(serial: Int) = serial.toString(16).padStart(12, '0')
    private fun id(value: String) = DomainId(value)
}
