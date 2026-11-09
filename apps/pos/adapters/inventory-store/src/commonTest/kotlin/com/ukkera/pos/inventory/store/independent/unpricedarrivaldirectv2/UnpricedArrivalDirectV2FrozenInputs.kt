package com.ukkera.pos.inventory.store.independent.unpricedarrivaldirectv2

import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Projection of the unchanged unpriced-arrival-direct-workflow-v2 freeze.
 * Fixture SHA-256: c3e974d641c26e85fdc01f76d3928f7642d4d59145f141caee55eb48259c9032.
 * Quantities/cuts, document manifests and named identities are frozen inputs, not results.
 *
 * The packet deliberately leaves actual native installation/field bindings UNBOUND.
 * Organization, UOM, source/custody/event identities, unspecified SKU/storage stamps,
 * policy-pointer digests and the retention-class UUID below are inert codec/physical
 * carriers. They do not supply missing originals, authentic policy, absence or authority.
 * Partial IDs are explicit request carriers; no returned-child derivation is invented.
 *
 * Bound slices: UAD2-01 physical cuts; UAD2-02 physical eligibility/identification;
 * UAD2-03 DTO/manifest representation; UAD2-05 normalized identity and the sequential
 * C5 quantity cut; UAD2-06 physical quantity/ancestry/known-metadata guards.
 * All seven WHOLE cases remain NOT_RUN. Actual current Master/source/original joins,
 * immutable action-use/retention, no-money ledgers, atomic persistence, retry/races,
 * history/restart, permanent cross-profile claims, RPC and UAD2-07 native UI are UNBOUND.
 */
internal object Uad2Inputs {
    private val ids = mapOf(
        "COMPANY-A" to "8891f6af-3003-4a10-a40c-56830e000324",
        "BRANCH-A" to "17256bce-b955-4c06-898f-b740d9c1f3de",
        "REQUESTER-R" to "bd706b87-eaf0-45a9-8212-0f40f038eafe",
        "REQUESTER-X" to "1c93ade3-3a6d-428d-b7c0-1c1a1b65fee8",
        "DEVICE-A" to "59f40efc-8cc3-4e86-9bec-8b3f42134b7b",
        "SESSION-R" to "f6b72d14-a371-4dc5-8ff1-1b3d0ac91d79",
        "SUPPLIER-S" to "0d92ac11-e8ca-46e1-afcb-e57f219a7277",
        "SUPPLIER-OTHER" to "717b02a4-1d2e-47b9-9de6-1837b6e82ec9",
        "CATALOG-A" to "f62e89ab-9991-4419-a3e5-b3b629045189",
        "CATALOG-RELEASE-7" to "999805ba-c8de-4949-a775-a3ea5d185976",
        "WAREHOUSE-HOLD" to "23e86e98-2c8c-48fb-b7d2-029fd7a440b8",
        "PRODUCT-A" to "286fb181-509a-4caa-9b52-f20c34c963e1",
        "PRODUCT-B" to "84455e18-4539-4b9b-a7b1-9ad3fa1af79c",
        "PRODUCT-L" to "c9e5c8b4-ed29-4bcb-af62-f9390e6b7020",
        "PRODUCT-T" to "8d85f3ef-557c-438d-9cce-2b94cf3f3fdc",
        "SKU-A" to "ee3fad73-3a68-4225-a5ec-701b656b3935",
        "SKU-B" to "62260de0-2d68-4b23-ae54-06023d543c9b",
        "SKU-L" to "1dd2b916-ac37-4e2e-a064-971f93e3ba26",
        "SKU-T" to "9d55596c-8ed6-4ae2-b640-43a21fd19d3d",
        "LOT-L" to "103af92c-c72c-4f2e-a80d-ca10ce2d9bed",
        "LOT-OTHER" to "e74411fc-c33b-4d83-af41-c0a36f3c4749",
        "DOC-A" to "d7b0f975-8ae0-40d7-a9ec-34e698c25c2e",
        "DOC-B" to "a3e37624-c21b-4c3b-b218-6bbd2d0e1459",
        "POLICY-A" to "bab7c46a-cfcc-4b24-9691-4100b966c048",
        "PUBLICATION-A" to "6d698fcb-05b5-4c68-8fb3-28167865b648",
        "RULE-RECORD" to "af19e1f8-1764-47f0-b148-eb52d5a50511",
        "RULE-QUALIFY" to "acbb9ffc-b4db-4977-90b4-96adee3ca73a",
        "RULE-REJECT" to "5f72125c-c0d6-47c6-9ea4-8a15729ec145",
        "RULE-DISPATCH" to "5f333fcf-902d-460c-9839-0d3ef9f53e06",
        "RULE-OPTIONAL" to "96486728-e87f-4d62-a4e9-838d0f46bf2a",
        "DELIVERY-MAIN" to "29c4009f-1c52-4343-a79d-1abf8579944f",
        "LINE-MAIN-A" to "cba935ae-feb0-4ad2-8715-a52b590b7765",
        "LINE-MAIN-B" to "2528c8d9-bf16-4114-ad47-7e2816898b23",
        "DELIVERY-TRACK" to "5f5b9397-36bf-421f-90a8-25b11358fffe",
        "LINE-TRACK-L" to "cde620b9-cf3d-4f29-aa23-b18995c7a1ec",
        "LINE-TRACK-T" to "25adb60f-f89a-4280-8caf-19b31f86b02b",
        "DELIVERY-DOOR" to "f2c00978-c918-4867-89f1-c69e839bc28f",
        "LINE-DOOR-A" to "4c95ead5-cc70-429e-bb4d-aa75d718eaf1",
        "OP-RECORD" to "6d78d48c-e301-40fe-9282-0526c8f8c8a8",
        "OP-QUALIFY" to "69f08582-bb29-406a-af83-92478dfaaa9b",
        "OP-REJECT" to "ba881b33-db0d-4f29-8c31-2cbc413873ee",
        "OP-DISPATCH" to "831baade-874e-4375-b056-4583301c271c",
    )
    fun id(label: String): DomainId = DomainId(ids.getValue(label))
    fun operation(label: String): OperationId = OperationId(ids.getValue(label))

    // Explicit inert bindings for labels/coordinates not supplied by the frozen packet.
    val organizationCarrier = DomainId("90000000-0000-4000-8000-000000000001")
    val unit = DomainId("90000000-0000-4000-8000-000000000002")
    val kilogram = DomainId("90000000-0000-4000-8000-000000000003")
    val carton = DomainId("90000000-0000-4000-8000-000000000004")
    val meter = DomainId("90000000-0000-4000-8000-000000000005")
    val conversionCarrier = DomainId("90000000-0000-4000-8000-000000000006")
    val retentionCarrier = DomainId("90000000-0000-4000-8000-000000000007")
    val otherRetentionCarrier = DomainId("90000000-0000-4000-8000-000000000008")
    val sourceA = DomainId("90000000-0000-4000-8000-000000000011")
    val custodyA = DomainId("90000000-0000-4000-8000-000000000012")
    val sourceB = DomainId("90000000-0000-4000-8000-000000000013")
    val custodyB = DomainId("90000000-0000-4000-8000-000000000014")
    val sourceL = DomainId("90000000-0000-4000-8000-000000000015")
    val custodyL = DomainId("90000000-0000-4000-8000-000000000016")
    val sourceT = DomainId("90000000-0000-4000-8000-000000000017")
    val custodyT = DomainId("90000000-0000-4000-8000-000000000018")
    val sourceDoor = DomainId("90000000-0000-4000-8000-000000000019")
    val custodyDoor = DomainId("90000000-0000-4000-8000-000000000020")
    val qualifySplit = LocalArrivalSplitV2(DomainId("90000000-0000-4000-8000-000000000021"), DomainId("90000000-0000-4000-8000-000000000022"))
    val rejectSplit = LocalArrivalSplitV2(DomainId("90000000-0000-4000-8000-000000000023"), DomainId("90000000-0000-4000-8000-000000000024"))
    val dispatchSplit = LocalArrivalSplitV2(DomainId("90000000-0000-4000-8000-000000000025"), DomainId("90000000-0000-4000-8000-000000000026"))
    val serialSplit = LocalArrivalSplitV2(DomainId("90000000-0000-4000-8000-000000000027"), DomainId("90000000-0000-4000-8000-000000000028"))
    val qualifyEvent = DomainId("90000000-0000-4000-8000-000000000031")
    val rejectEvent = DomainId("90000000-0000-4000-8000-000000000032")
    val dispatchEvent = DomainId("90000000-0000-4000-8000-000000000033")
    val scope = BranchScope(organizationCarrier, id("COMPANY-A"), id("BRANCH-A"))
    val expiry = UtcInstant.parseCanonical("2030-06-30T00:00:00Z")
    val changedExpiry = UtcInstant.parseCanonical("2031-06-30T00:00:00Z")
    const val newSerial = "SERIAL-NEW-A"
    const val differentSerial = "SERIAL-OTHER"
    val requesterPolicyRevision = Revision(11)
    private val digestCarrier = PayloadDigest("0".repeat(64))

    fun quantity(label: String, value: String): Quantity = if (label == "L") Quantity.parse(kilogram, value, 3)
        else Quantity.parse(unit, value, 0)

    private fun line(label: String, lineLabel: String, source: DomainId, custody: DomainId, value: String,
        disposition: LocalArrivalDispositionV1 = LocalArrivalDispositionV1.UNINSPECTED_HELD): LocalArrivalLineV2 =
        LocalArrivalLineV2(id(lineLabel), source, custody, id("SKU-$label"), id("WAREHOUSE-HOLD"), quantity(label, value), disposition = disposition)

    fun mainOriginal(): ArrivalPhysicalOriginal = ArrivalPhysicalOriginal(id("DELIVERY-MAIN"), id("SUPPLIER-S"),
        listOf(line("A", "LINE-MAIN-A", sourceA, custodyA, "24"), line("B", "LINE-MAIN-B", sourceB, custodyB, "6")),
        listOf(ArrivalPhysicalItem(id("SKU-A"), unit, 0, LocalArrivalTrackingV1.QUANTITY),
            ArrivalPhysicalItem(id("SKU-B"), unit, 0, LocalArrivalTrackingV1.QUANTITY)), ArrivalPhysicalProfile.V2_UNIDENTIFIED_SERIAL_PARTITIONS)

    fun trackingOriginal(): ArrivalPhysicalOriginal = ArrivalPhysicalOriginal(id("DELIVERY-TRACK"), id("SUPPLIER-S"),
        listOf(line("L", "LINE-TRACK-L", sourceL, custodyL, "2.5"), line("T", "LINE-TRACK-T", sourceT, custodyT, "2")),
        listOf(ArrivalPhysicalItem(id("SKU-L"), kilogram, 3, LocalArrivalTrackingV1.LOT),
            ArrivalPhysicalItem(id("SKU-T"), unit, 0, LocalArrivalTrackingV1.SERIAL)), ArrivalPhysicalProfile.V2_UNIDENTIFIED_SERIAL_PARTITIONS)

    fun doorOriginal(): ArrivalPhysicalOriginal = ArrivalPhysicalOriginal(id("DELIVERY-DOOR"), id("SUPPLIER-S"),
        listOf(line("A", "LINE-DOOR-A", sourceDoor, custodyDoor, "5", LocalArrivalDispositionV1.REFUSED)),
        listOf(ArrivalPhysicalItem(id("SKU-A"), unit, 0, LocalArrivalTrackingV1.QUANTITY)), ArrivalPhysicalProfile.V2_UNIDENTIFIED_SERIAL_PARTITIONS)

    fun transition(original: ArrivalPhysicalOriginal, action: LocalArrivalActionV1, custody: DomainId, quantity: Quantity,
        split: LocalArrivalSplitV2? = null, lot: DomainId? = null, expiresAt: UtcInstant? = null,
        serial: String? = null, passed: Boolean = false, recipient: DomainId? = null): ArrivalPhysicalTransition =
        ArrivalPhysicalTransition(action, original.arrivalId, custody, quantity, split, lot, expiresAt, null, serial, null, passed, recipient)

    // Literal fixture cuts: [uninspected, passed, rejected, refused, dispatched].
    internal data class MainCut(val name: String, val a: List<Long>, val bUninspected: Long, val aHeld: Long, val totalHeld: Long)
    val cuts = listOf(
        MainCut("C0", listOf(0L, 0L, 0L, 0L, 0L), 0, 0, 0),
        MainCut("C1", listOf(24L, 0L, 0L, 0L, 0L), 6, 24, 30),
        MainCut("C2", listOf(14L, 10L, 0L, 0L, 0L), 6, 24, 30),
        MainCut("C3", listOf(14L, 6L, 4L, 0L, 0L), 6, 24, 30),
        MainCut("C4", listOf(14L, 6L, 1L, 0L, 3L), 6, 21, 27),
        MainCut("C5", listOf(14L, 6L, 0L, 0L, 4L), 6, 20, 26),
    )

    fun documents(): List<LocalArrivalDocumentSelectionV2> = listOf(
        LocalArrivalDocumentSelectionV2(scope, id("SUPPLIER-S"), id("DOC-A"), Revision(1),
            PayloadDigest("58d3e9e632f1b54b8237e895eef84f80cf5d1da8e5f69370b9ccb70bbe058797"), 614, "PDF", "SUPPLIER_DELIVERY"),
        LocalArrivalDocumentSelectionV2(scope, id("SUPPLIER-S"), id("DOC-B"), Revision(1),
            PayloadDigest("2ea317ea2f23340a4f4bd5976f09d3019a26d8db465c5700f84ede93e2deeeb1"), 613, "PDF", "SUPPLIER_DELIVERY"),
    )

    fun evidence(ruleLabel: String = "RULE-RECORD", observation: String = "SYN Delivery 001",
        documents: List<LocalArrivalDocumentSelectionV2> = Uad2Inputs.documents()): LocalArrivalEvidenceSelectionV2 =
        LocalArrivalEvidenceSelectionV2(observation,
            LocalArrivalPolicySelectionV2(scope, id("POLICY-A"), id("PUBLICATION-A"), Revision(8),
                LocalArrivalSourceSelectionV2("uad2.policy-active-carrier", id("BRANCH-A"), Revision(1), digestCarrier),
                LocalArrivalSourceSelectionV2("uad2.policy-publication-carrier", id("PUBLICATION-A"), Revision(1), digestCarrier),
                UtcInstant.parseCanonical("2026-11-01T00:00:00Z"), null),
            LocalArrivalCatalogSelectionV2(scope, id("CATALOG-A"), id("CATALOG-RELEASE-7"), Revision(7),
                LocalArrivalSourceSelectionV2("uad2.catalog-active-carrier", id("BRANCH-A"), Revision(1), digestCarrier),
                LocalArrivalSourceSelectionV2("uad2.catalog-publication-carrier", id("CATALOG-RELEASE-7"), Revision(1), digestCarrier)),
            id(ruleLabel), documents, LocalArrivalRetentionSelectionV2(retentionCarrier, Revision(2)))

    fun item(label: String): LocalArrivalItemSelectionV2 = LocalArrivalItemSelectionV2(id("SKU-$label"), id("PRODUCT-$label"),
        Revision(when (label) { "A" -> 4L; "B" -> 5L; "L" -> 6L; "T" -> 7L; else -> error("Unbound product") }),
        if (label == "L") kilogram else unit, if (label == "L") 3 else 0, Revision(7), Revision(1), Revision(1), null)
    fun warehouse(): LocalArrivalWarehouseSelectionV2 = LocalArrivalWarehouseSelectionV2(id("WAREHOUSE-HOLD"), Revision(2))

    fun record(): LocalRecordUnpricedArrivalV2 = LocalRecordUnpricedArrivalV2(id("DELIVERY-MAIN"), id("SUPPLIER-S"), Revision(3),
        requesterPolicyRevision, supplierDeliveryReference = "SYN Delivery 001", items = listOf(item("A"), item("B")),
        warehouses = listOf(warehouse()), lines = mainOriginal().lines, evidence = evidence(), reasonCode = "syn.record")

    fun mainMutations(): List<MutationRequest> = listOf(
        LocalUnpricedArrivalCommandsV2.record(operation("OP-RECORD"), record()),
        LocalUnpricedArrivalCommandsV2.qualify(operation("OP-QUALIFY"), LocalQualifyUnpricedArrivalV2(qualifyEvent,
            id("DELIVERY-MAIN"), Revision(1), requesterPolicyRevision, Revision(3), item("A"), warehouse(), custodyA,
            quantity("A", "10"), qualifySplit, inspectionPassed = true,
            evidence = evidence("RULE-QUALIFY", "SYN Inspection 001"), reasonCode = "syn.inspection")),
        LocalUnpricedArrivalCommandsV2.reject(operation("OP-REJECT"), LocalRejectUnpricedArrivalV2(rejectEvent,
            id("DELIVERY-MAIN"), Revision(2), requesterPolicyRevision, Revision(3), item("A"), warehouse(), qualifySplit.custodyUnitId,
            quantity("A", "4"), rejectSplit, evidence("RULE-REJECT", "SYN damage observed in four retained units"), "syn.reject")),
        LocalUnpricedArrivalCommandsV2.dispatch(operation("OP-DISPATCH"), LocalDispatchUnpricedArrivalV2(dispatchEvent,
            id("DELIVERY-MAIN"), Revision(3), requesterPolicyRevision, Revision(3), item("A"), warehouse(), rejectSplit.custodyUnitId,
            quantity("A", "3"), dispatchSplit, id("SUPPLIER-S"), evidence("RULE-DISPATCH", "SYN Supplier dispatch 001"), "syn.dispatch")),
    )
}
