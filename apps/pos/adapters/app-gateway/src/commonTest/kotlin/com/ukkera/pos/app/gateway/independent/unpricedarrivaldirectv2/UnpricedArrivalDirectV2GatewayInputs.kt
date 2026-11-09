package com.ukkera.pos.app.gateway.independent.unpricedarrivaldirectv2

import com.ukkera.pos.app.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Identity-only projection of the unchanged unpriced-arrival-direct-workflow-v2 freeze.
 * Fixture SHA-256: c3e974d641c26e85fdc01f76d3928f7642d4d59145f141caee55eb48259c9032.
 * Named IDs, manifest lengths/digests, publication/requester/retention revisions and
 * original order come from that packet. Organization, source kind/storage/digests,
 * retention UUID and custody coordinates are explicitly inert schema carriers.
 * The rule's required-file lower bound is represented as one, not a new authority.
 * The supplemental parent/head is supplied input, not proof of completed membership.
 * No policy installation, original bytes/history, principal, session or runtime exists here.
 */
internal object Uad2GatewayInputs {
    private val ids = mapOf(
        "COMPANY-A" to "8891f6af-3003-4a10-a40c-56830e000324",
        "COMPANY-B" to "4fbca744-6a3f-4b4f-bb9c-fac3db509d45",
        "BRANCH-A" to "17256bce-b955-4c06-898f-b740d9c1f3de",
        "BRANCH-B" to "0dfae824-fff7-44a9-98d9-6e5296512712",
        "SUPPLIER-S" to "0d92ac11-e8ca-46e1-afcb-e57f219a7277",
        "SUPPLIER-OTHER" to "717b02a4-1d2e-47b9-9de6-1837b6e82ec9",
        "CATALOG-A" to "f62e89ab-9991-4419-a3e5-b3b629045189",
        "CATALOG-RELEASE-7" to "999805ba-c8de-4949-a775-a3ea5d185976",
        "WAREHOUSE-HOLD" to "23e86e98-2c8c-48fb-b7d2-029fd7a440b8",
        "SKU-A" to "ee3fad73-3a68-4225-a5ec-701b656b3935",
        "DOC-A" to "d7b0f975-8ae0-40d7-a9ec-34e698c25c2e",
        "DOC-B" to "a3e37624-c21b-4c3b-b218-6bbd2d0e1459",
        "DOC-S" to "f9b8538a-2981-4b62-84f8-5553528d0ab4",
        "POLICY-A" to "bab7c46a-cfcc-4b24-9691-4100b966c048",
        "PUBLICATION-A" to "6d698fcb-05b5-4c68-8fb3-28167865b648",
        "RULE-RECORD" to "af19e1f8-1764-47f0-b148-eb52d5a50511",
        "RULE-OPTIONAL" to "96486728-e87f-4d62-a4e9-838d0f46bf2a",
        "DELIVERY-MAIN" to "29c4009f-1c52-4343-a79d-1abf8579944f",
        "LINE-MAIN-A" to "cba935ae-feb0-4ad2-8715-a52b590b7765",
        "OP-RECORD" to "6d78d48c-e301-40fe-9282-0526c8f8c8a8",
    )
    fun id(label: String): DomainId = DomainId(ids.getValue(label))
    val operation: OperationId = OperationId(ids.getValue("OP-RECORD"))
    val organizationCarrier: DomainId = DomainId("90000000-0000-4000-8000-000000000001")
    val custodyCarrier: DomainId = DomainId("90000000-0000-4000-8000-000000000012")
    val zeroDigest: PayloadDigest = PayloadDigest("0".repeat(64))
    val changedDigest: PayloadDigest = PayloadDigest("1".repeat(64))
    val scope: BranchScope = BranchScope(organizationCarrier, id("COMPANY-A"), id("BRANCH-A"))
    val requester: Revision = Revision(11)
    val retention: LocalArrivalRetentionCoordinateV2 = LocalArrivalRetentionCoordinateV2(
        DomainId("90000000-0000-4000-8000-000000000007"), Revision(2))
    val policy: LocalArrivalPolicyCoordinateV2 = LocalArrivalPolicyCoordinateV2(
        scope, id("POLICY-A"), id("PUBLICATION-A"), Revision(8),
        LocalArrivalSourceCoordinateV2("uad2.policy-active-carrier", id("BRANCH-A"), Revision(1), zeroDigest),
        LocalArrivalSourceCoordinateV2("uad2.policy-publication-carrier", id("PUBLICATION-A"), Revision(1), zeroDigest),
        UtcInstant.parseCanonical("2026-11-01T00:00:00Z"), null)
    val catalog: LocalArrivalCatalogCoordinateV2 = LocalArrivalCatalogCoordinateV2(
        scope, id("CATALOG-A"), id("CATALOG-RELEASE-7"), Revision(7),
        LocalArrivalSourceCoordinateV2("uad2.catalog-active-carrier", id("BRANCH-A"), Revision(1), zeroDigest),
        LocalArrivalSourceCoordinateV2("uad2.catalog-publication-carrier", id("CATALOG-RELEASE-7"), Revision(1), zeroDigest))

    fun documents(): List<LocalArrivalOriginalCoordinateV2> = listOf(
        LocalArrivalOriginalCoordinateV2(scope, id("SUPPLIER-S"), id("DOC-A"), Revision(1),
            PayloadDigest("58d3e9e632f1b54b8237e895eef84f80cf5d1da8e5f69370b9ccb70bbe058797"),
            614, PurchasingEvidenceMediaV1.PDF, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY),
        LocalArrivalOriginalCoordinateV2(scope, id("SUPPLIER-S"), id("DOC-B"), Revision(1),
            PayloadDigest("2ea317ea2f23340a4f4bd5976f09d3019a26d8db465c5700f84ede93e2deeeb1"),
            613, PurchasingEvidenceMediaV1.PDF, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY))

    fun supplemental(): LocalArrivalOriginalCoordinateV2 = LocalArrivalOriginalCoordinateV2(
        scope, id("SUPPLIER-S"), id("DOC-S"), Revision(1),
        PayloadDigest("28559abd8a96f8dce523b85b59994a6ac126b71309fa51d7fc48778b54afe33e"),
        609, PurchasingEvidenceMediaV1.PDF, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY,
        id("DOC-A"), LocalArrivalSourceCoordinateV2("uad2.document-parent-head-carrier", id("DOC-A"), Revision(2), zeroDigest))

    fun rule(
        id: DomainId = Uad2GatewayInputs.id("RULE-RECORD"),
        direct: Boolean = true,
        minimum: Int = 1,
        maximum: Int = 2,
        media: List<PurchasingEvidenceMediaV1> = listOf(PurchasingEvidenceMediaV1.PDF),
        purposes: List<PurchasingEvidencePurposeV1> = listOf(PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY),
        retention: LocalArrivalRetentionCoordinateV2 = Uad2GatewayInputs.retention,
    ): LocalArrivalRuleOptionV2 = LocalArrivalRuleOptionV2(id, LocalAction.ARRIVAL_RECORD, direct,
        minimum, maximum, media, purposes, retention)

    fun selection(
        policy: LocalArrivalPolicyCoordinateV2 = Uad2GatewayInputs.policy,
        catalog: LocalArrivalCatalogCoordinateV2 = Uad2GatewayInputs.catalog,
        requester: Revision = Uad2GatewayInputs.requester,
        rule: LocalArrivalRuleOptionV2 = Uad2GatewayInputs.rule(),
        documents: List<LocalArrivalOriginalCoordinateV2> = Uad2GatewayInputs.documents(),
        refreshPin: PayloadDigest = zeroDigest,
    ): LocalArrivalEvidenceInputV2 = LocalArrivalEvidenceInputV2(policy, catalog, requester,
        rule, rule.retention, documents, refreshPin)

    /** Fixed scalar/line input deliberately excludes preparation-only required fields.
     * Only the framed input-identity function is bound; this is not a stage-ready command. */
    fun intent(selection: LocalArrivalEvidenceInputV2): LocalCommandIntent = LocalCommandIntent(
        operation, LocalAction.ARRIVAL_RECORD, 2,
        LocalRecordReference(LocalRecordKind.ARRIVAL_WAREHOUSE_V2, id("WAREHOUSE-HOLD"), parentId = id("SUPPLIER-S")),
        listOf(
            LocalScalarInput(LocalField.ARRIVAL_ID, LocalScalarValue.Identifier(id("DELIVERY-MAIN"))),
            LocalScalarInput(LocalField.SUPPLIER_ID, LocalScalarValue.Identifier(id("SUPPLIER-S"))),
            LocalScalarInput(LocalField.ARRIVAL_SELECTION_DIGEST, LocalScalarValue.Text(zeroDigest.value)),
            LocalScalarInput(LocalField.ARRIVAL_SELECTION_DETAILS, LocalScalarValue.Text(Uad2GatewayInputs.selection().sourceDescription)),
            LocalScalarInput(LocalField.ARRIVAL_RULE_ID, LocalScalarValue.Choice(id("RULE-RECORD").value)),
            LocalScalarInput(LocalField.ARRIVAL_RETENTION, LocalScalarValue.Choice(retention.choiceCode)),
            LocalScalarInput(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS, LocalScalarValue.References(documents().map { it.reference })),
            LocalScalarInput(LocalField.EXPECTED_REQUESTER_POLICY_REVISION, LocalScalarValue.Integer(11)),
            LocalScalarInput(LocalField.REASON, LocalScalarValue.Text("syn.record"))),
        LocalField.LINES, listOf(LocalInputLine(id("LINE-MAIN-A"), listOf(
            LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Reference(LocalRecordReference(LocalRecordKind.SKU, id("SKU-A")))),
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal("24"))))), arrivalEvidence = selection)
}
