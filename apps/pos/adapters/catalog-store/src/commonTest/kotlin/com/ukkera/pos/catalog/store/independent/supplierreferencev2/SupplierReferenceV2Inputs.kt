package com.ukkera.pos.catalog.store.independent.supplierreferencev2

import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalSupplierV2
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Exact vectors from SUPPLIER-REFERENCE-V2-CASES-1.0.0 + CLARIFICATION-1.0.1. */
internal object SupplierReferenceV2Inputs {
    fun id(serial: Int) = DomainId("b21f0000-0000-4000-8000-${serial.toString().padStart(12, '0')}")
    fun operation(serial: Int) = OperationId(id(serial).value)
    val scope = BranchScope(id(1), id(10), id(11))
    val actor = id(31)
    val otherActor = id(32)
    val device = id(42)
    val legacy = id(101)
    val current = id(102)
    val filter = id(103)
    val initialNames = LocalNamesV1("Atlas Supply", "توريد أطلس")
    val editedNames = LocalNamesV1("Atlas Supply Updated", "توريد أطلس محدث")
    val filterNames = LocalNamesV1("Cedar Supply", "توريد الأرز")
    const val literal = " 0007-Ab  "
    const val mixed = "مورد-0007-A😀 "
    const val nfc = "Café-01"
    const val nfd = "Cafe\u0301-01"
    val escaped256 = "\\\"".repeat(128)

    data class Vector(val label: String, val text: String, val units: Int)
    val valid = listOf(
        Vector("LITERAL", literal, 10), Vector("MIXED", mixed, 14),
        Vector("NFC", nfc, 7), Vector("NFD", nfd, 8), Vector("SPACE", " ", 1),
        Vector("ONE", "Z", 1), Vector("BMP256", "م".repeat(256), 256),
        Vector("ASCII256", "A".repeat(256), 256), Vector("PAIR256", "😀".repeat(128), 256),
        Vector("PAIR_AT_END256", "A".repeat(254) + "😀", 256), Vector("ESCAPE256", escaped256, 256),
    )
    val invalid = listOf(
        Vector("PRESENT_EMPTY", "", 0), Vector("ASCII257", "A".repeat(257), 257),
        Vector("PAIR_PLUS_ASCII257", "😀".repeat(128) + "A", 257),
        Vector("PAIR_END257", "A".repeat(255) + "😀", 257),
        Vector("HIGH_ONLY", "\uD83D", 1), Vector("LOW_ONLY", "\uDE00", 1),
        Vector("REVERSED_PAIR", "\uDE00\uD83D", 2), Vector("HIGH_THEN_ASCII", "\uD83DA", 2),
    )
    val conflicts = listOf<String?>(null, "0007-Ab", " 0007-ab  ", " 0007-Ab   ")
    fun supplier(id: DomainId = current, reference: String? = literal,
        names: LocalNamesV1 = initialNames, active: Boolean = true) =
        LocalSupplierV2(id, names, active, reference?.let(Code::exact))
}

/** Exact 53-row input binding from SUPPLIER-REFERENCE-V2-PAGINATION-CASES-1.0.1. */
internal object SupplierReferenceV2PaginationInputs {
    fun id(serial: Int) = DomainId("b22f0000-0000-4000-8000-${serial.toString().padStart(12, '0')}")
    val scope = BranchScope(id(1), id(10), id(11))
    val actor = id(31)
    val device = id(42)
    private val bmpNames = LocalNamesV1("A".repeat(160), "م".repeat(160))
    private val escapedNames = LocalNamesV1("\\\"".repeat(80), "\\\"".repeat(79) + "مم")
    private val references = listOf(SupplierReferenceV2Inputs.escaped256, "م".repeat(256),
        "😀".repeat(128), "A".repeat(254) + "😀")
    data class Row(val supplier: LocalSupplierV2, val operation: OperationId)
    val rows = (1..53).map { ordinal ->
        Row(LocalSupplierV2(id(100 + ordinal), if (ordinal % 2 == 1) escapedNames else bmpNames,
            true, Code.exact(references[(ordinal - 1) % 4])), OperationId(id(200 + ordinal).value))
    }
}
