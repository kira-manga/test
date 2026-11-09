@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.returnstockrefusal

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.AcceptedReturn
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.ReturnClaimEvidence
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalReturnStockCodecV1
import com.ukkera.pos.sales.store.LocalReturnStockCustodyV1
import com.ukkera.pos.sales.store.LocalReturnStockPostingV1
import com.ukkera.pos.sales.store.LocalReturnStockProfileV1
import com.ukkera.pos.sales.store.LocalReturnStockRequestV1
import com.ukkera.pos.sales.store.LocalReturnStockSelectionV1
import com.ukkera.pos.sales.store.LocalReturnStockStateV1
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.sales.store.returnStockPostingValue
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/**
 * Unchanged literal fixture copy from
 * qa/pos/designs/return-stock-stale-disposition-refusal/fixtures.json (v1).
 * This copies inputs for supplemental common tests; all seven frozen whole cases,
 * variants and prerequisites retain their original NOT RUN state.
 * The conceptual labels do not prescribe UUIDs, counters, wire fields or algorithms.
 */
internal object FrozenReturnStockRefusalInputs {
    private val frozen: JsonObject = Json.parseToJsonElement(FIXTURES_JSON).jsonObject.also { root ->
        check(root.literal("specification_id") == "RETURN-STOCK-STALE-DISPOSITION-REFUSAL")
        check(root.getValue("frozen_version").jsonPrimitive.int == 1)
        check(root.literal("design_state") == "FROZEN" && root.literal("execution_state") == "NOT RUN")
    }

    val core: JsonObject = fixture("CORE")
    val invariants: JsonObject = core.objectAt("invariants_at_all_cuts")
    val timing: JsonObject = fixture("ORDINARY-TIMING-SUPPORT")
    val unitPost: JsonObject = core.objectAt("each_valid_current_unit_post")
    val scope: BranchScope = BranchScope(id(1), id(2), id(3))
    val eachUnit: DomainId = id(4)

    private fun fixture(name: String): JsonObject = frozen.getValue("fixtures").jsonArray.map { it.jsonObject }
        .single { it.literal("id") == name }

    fun cut(name: String): JsonObject = core.getValue("cuts").jsonArray.map { it.jsonObject }
        .single { it.literal("id") == name }

    fun quantity(literal: String): Quantity = Quantity.parse(eachUnit, literal,
        core.getValue("quantity_precision").jsonPrimitive.int)
    fun money(literal: String): Money = Money(Currency.EGP, ExactDecimal.parse(literal, 2).toScaledLongExact(2))
    fun instant(literal: String): UtcInstant = UtcInstant(Instant.parse(literal))

    /** Inert container coordinates only; no authentic source, actor or operation is claimed. */
    fun id(number: Int): DomainId = DomainId("75000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun operation(number: Int): OperationId = OperationId(id(number).value)

    private const val FIXTURES_JSON: String = """
{
  "specification_id": "RETURN-STOCK-STALE-DISPOSITION-REFUSAL",
  "frozen_version": 1,
  "frozen_date_utc": "2026-11-03",
  "design_state": "FROZEN",
  "execution_state": "NOT RUN",
  "data_class": "SYNTHETIC LOCAL ONLY",
  "supporting_original": {
    "specification_id": "RETURN-STOCK-DISPOSITION",
    "frozen_version": 1,
    "focused_case_ids": ["RSD-010", "RSD-011"],
    "original_execution_state": "NOT RUN",
    "original_changed": false
  },
  "interpretation": "Conceptual original business facts and exact independent expectations; labels are not API fields, numeric revisions, database records or source algorithms.",
  "money": {"currency": "EGP", "posted_minor_unit": "0.01", "representation": "exact decimal strings"},
  "prerequisites": [
    {"id": "GENUINE-CORE", "state": "NOT RUN", "requirement": "Actual original paid sale/portion, complete earlier return/refund/stock history, immutable genuinely accepted current return and original issued-cost reconstruction."},
    {"id": "CURRENT-AUTHORITY", "state": "NOT RUN", "requirement": "Actual active Master, admitted branch device, enabled current original return owner/session/permission and applicable approvals; protected result-read authority is checked separately."},
    {"id": "INSPECTED-SELLABLE", "state": "NOT RUN", "requirement": "Genuine configured source-bound employee inspection, branch-local sellable destination, released/unexpired/unblocked owned unreserved units."},
    {"id": "U-NATIVE-BINDING", "state": "NOT RUN", "requirement": "Genuine protected native original/history/resource/result binding and an isolated CURRENT DISPOSITION assertion; controlled accepted/balance assertions cannot substitute."},
    {"id": "U-ASSERTION-PRECEDENCE", "state": "NOT RUN", "requirement": "Mixed wrong-acceptance versus stale-disposition first-error ordering and literal acceptance error are not defined by the assigned sources; fixed isolated and no-effect assertions remain."}
  ],
  "fixtures": [
    {
      "id": "CORE",
      "state": "NOT RUN",
      "company": "SYN-C1",
      "selling_branch": "SYN-B1",
      "sku": "RSD-RETAIL-EACH",
      "base_unit": "EACH",
      "quantity_precision": 0,
      "lot_and_serial": "not tracked for this core fixture",
      "original_sale": {"quantity": "4", "paid_unit_amount": "50.00", "paid_amount": "200.00", "discount": "0.00", "tax": "0.00", "tender": "CASH", "issued_unit_cost": "20.00", "immutable_issued_cost": "80.00"},
      "independent_business_sequence": [
        {"fact": "owned opening inventory", "quantity": "4", "value": "80.00"},
        {"fact": "original sale issue", "quantity_delta": "-4", "value_delta": "-80.00", "remaining_quantity": "0", "remaining_value": "0.00"},
        {"fact": "earlier accepted return", "quantity": "1", "refund": "50.00", "refund_state": "SETTLED"},
        {"fact": "earlier inspected-sellable return", "quantity_delta": "1", "original_value_delta": "20.00"},
        {"fact": "later acquisition", "quantity_delta": "1", "unit_cost": "60.00", "value_delta": "60.00"},
        {"fact": "current genuine already accepted return, no stock disposition or payout yet", "quantity": "2", "refund_due": "100.00"}
      ],
      "current_catalog_irrelevant_to_original_return": {"unit_base_price": "80.00", "unit_discount": "5.00", "tax_rate": "0.20", "hypothetical_current_unit_payable": "90.00"},
      "invariants_at_all_cuts": {
        "current_accepted_quantity": "2",
        "current_accepted_original_cost": "40.00",
        "cumulative_original_accepted_quantity": "3",
        "remaining_original_return_entitlement_quantity": "1",
        "remaining_original_return_entitlement_money": "50.00",
        "current_refund_due": "100.00",
        "current_actual_refund": "0.00",
        "drawer_cash": "500.00",
        "drawer_derivation": "350.00 opening + 200.00 original collection - 50.00 earlier actual refund",
        "immutable_original_sale_quantity": "4",
        "immutable_original_sale_paid": "200.00",
        "immutable_original_sale_issued_cost": "80.00"
      },
      "cuts": [
        {"id": "C0", "state": "NOT RUN", "disposition_revision_label": "D0", "sellable_pool_quantity": "2", "sellable_pool_value": "80.00", "pool_average": "40.00", "available_quantity": "2", "current_disposed_quantity": "0", "current_disposed_original_cost": "0.00", "remaining_disposition_quantity": "2", "remaining_disposition_original_cost": "40.00"},
        {"id": "C1", "state": "NOT RUN", "disposition_revision_label": "D1", "sellable_pool_quantity": "3", "sellable_pool_value": "100.00", "available_quantity": "3", "current_disposed_quantity": "1", "current_disposed_original_cost": "20.00", "remaining_disposition_quantity": "1", "remaining_disposition_original_cost": "20.00"},
        {"id": "C2", "state": "NOT RUN", "disposition_revision_label": "D2", "sellable_pool_quantity": "4", "sellable_pool_value": "120.00", "pool_average": "30.00", "available_quantity": "4", "current_disposed_quantity": "2", "current_disposed_original_cost": "40.00", "remaining_disposition_quantity": "0", "remaining_disposition_original_cost": "0.00", "cumulative_disposed_original_quantity": "3", "cumulative_disposed_original_cost": "60.00", "original_unreturned_cost": "20.00"}
      ],
      "each_valid_current_unit_post": {"quantity_delta": "1", "original_value_delta": "20.00", "refund_delta": "0.00", "cash_delta": "0.00", "entitlement_consumption_delta": "0"}
    },
    {
      "id": "ORIGINAL-IDENTITY-ASSERTIONS",
      "state": "NOT RUN",
      "A-CORE": "Exact genuinely accepted original CORE sale/portion/quantity result, retained unchanged across C0/C1/C2.",
      "A-OTHER": "Independently genuine accepted original of a different sale/portion within the same admitted branch and authorized read scope, with independent identities; never fabricated or substituted for A-CORE.",
      "D0": "Actual CURRENT DISPOSITION observed at C0; stale at C1/C2.",
      "D1": "Actual CURRENT DISPOSITION observed at C1; current there and stale at C2.",
      "D2": "Actual CURRENT DISPOSITION observed at C2; not an original refusal-time revision.",
      "other_required_mutable_assertions": "Current at the attempted boundary for isolated stale-only and wrong-acceptance-only cases.",
      "original_owner": "Actual original owner of the handling return; original sale operator and prior inspection actor are preserved separately.",
      "revision_values": "No numeric revision, increment size, request layout or validation ordering prescribed."
    },
    {
      "id": "ORDINARY-TIMING-SUPPORT",
      "state": "NOT RUN",
      "scope": "Prerequisite for an otherwise genuinely accepted applicable CHANGE_OF_MIND refund with unchanged-condition inspection and one genuine receipt covering the original entire portion; no new eligibility grant.",
      "authoritative_customer_receipt_utc": "2026-10-01T10:00:00.250000Z",
      "captured_applicable_period_days": "14",
      "current_matching_applicable_ordinary_period_days": "14",
      "elapsed_hours": "336",
      "inclusive_expiry_utc": "2026-10-15T10:00:00.250000Z",
      "immutable_original_master_submission_utc": "2026-10-15T10:00:00.250000Z",
      "expected_submission_timely": true,
      "later_processing_example_utc": "2026-11-03T10:00:00.250000Z",
      "later_processing_reopens_eligibility_or_makes_original_submission_late": false,
      "customer_claimed_time_is_authority": false,
      "partial_or_multiple_delivery_policy_selected": false,
      "defective_product_policy_selected": false
    },
    {
      "id": "STALE-REQUEST-AND-RESULT",
      "state": "NOT RUN",
      "original_operation_label": "O-STALE",
      "request_business_facts": {"source": "CORE genuine original accepted sale/portion", "acceptance_assertion": "A-CORE", "current_disposition_assertion": "D0", "requested_quantity": "1", "base_unit": "EACH", "route": "configured inspected-sellable destination", "actor_and_inspection": "Exact genuine original owner/inspection preserved", "other_required_mutable_assertions": "current at C1"},
      "refusal_cut": "C1",
      "retained_result_label": "R-STALE",
      "retained_result_business_facts": {"outcome": "REJECTED", "reason_family": "stale CURRENT DISPOSITION revision conflict", "asserted_disposition_revision_label": "D0", "refusal_time_current_disposition_revision_label": "D1", "original_acceptance_label": "A-CORE", "original_operation_label": "O-STALE", "business_effect_set": "NONE", "exact_original_actor_source_result_and_authorized_details": "Captured durably from the original result and retained exactly across matching query/retry and supported restart."},
      "later_source_cut": "C2",
      "later_source_change_rewrites_retained_result": false,
      "same_id_with_changed_revision_is_exact_retry": false,
      "transport_credentials_are_stable_business_identity": false
    },
    {
      "id": "EVIDENCE-FAILURE-VARIANTS",
      "state": "NOT RUN",
      "source_variants": ["MISSING", "CORRUPT", "UNSUPPORTED", "AMBIGUOUS", "UNAVAILABLE"],
      "required_original_sources": ["original paid sale/portion", "genuine acceptance", "immutable issued cost", "earlier accepted/refunded/disposed return", "actual qualifying inspection", "genuine BEFORE/history/resource reconstruction", "retained original refusal result"],
      "last_trusted_cuts": ["C1", "C2"],
      "missing_or_unbindable_original_evidence_establishes_authoritative_stale_refusal": false,
      "current_balance_reconstructs_exact_original_refusal": false,
      "before_conclusive_result_unknown_establishes_retained_rejection": false,
      "inaccessible_current_reads_are_fabricated_as_known": false
    }
  ],
  "rejected_operation_effects": {"new_business_disposition": "0", "new_source_consumption": "0", "inventory_quantity_delta": "0", "inventory_value_delta": "0.00", "entitlement_delta": "0", "refund_obligation_delta": "0.00", "actual_refund_delta": "0.00", "drawer_cash_delta": "0.00", "new_sale_tax_replacement_or_credit": "NONE", "new_business_journal_or_outbox_effect": "NONE", "previously_committed_business_and_inspection": "PRESERVED", "permitted_security_refusal_or_recovery_audit": "Not a new stock or financial effect"},
  "cases": [
    {"id": "RSDF-001", "fixtures": ["CORE", "ORIGINAL-IDENTITY-ASSERTIONS", "STALE-REQUEST-AND-RESULT", "ORDINARY-TIMING-SUPPORT"], "state": "NOT RUN"},
    {"id": "RSDF-002", "variants": ["a", "b"], "fixtures": ["CORE", "ORIGINAL-IDENTITY-ASSERTIONS"], "state": "NOT RUN"},
    {"id": "RSDF-003", "variants": ["a", "b"], "fixtures": ["CORE", "STALE-REQUEST-AND-RESULT"], "state": "NOT RUN"},
    {"id": "RSDF-004", "variants": ["a", "b", "c"], "fixtures": ["CORE", "ORIGINAL-IDENTITY-ASSERTIONS", "STALE-REQUEST-AND-RESULT"], "state": "NOT RUN"},
    {"id": "RSDF-005", "variants": ["a", "b", "c"], "fixtures": ["CORE", "ORIGINAL-IDENTITY-ASSERTIONS", "STALE-REQUEST-AND-RESULT"], "state": "NOT RUN"},
    {"id": "RSDF-006", "variants": ["a", "b", "c"], "fixtures": ["CORE", "EVIDENCE-FAILURE-VARIANTS", "STALE-REQUEST-AND-RESULT"], "state": "NOT RUN"},
    {"id": "RSDF-007", "fixtures": ["CORE", "ORIGINAL-IDENTITY-ASSERTIONS", "ORDINARY-TIMING-SUPPORT"], "state": "NOT RUN"}
  ]
}

    """
}

internal fun JsonObject.objectAt(name: String): JsonObject = getValue(name).jsonObject
internal fun JsonObject.literal(name: String): String = getValue(name).jsonPrimitive.content

/**
 * Pure DATA carrier only. It deliberately supplies no paid sale, native CASE row,
 * original producer, signed acceptance, issued-cost history, stock read, grant,
 * inspection policy or financial ledger. The inaccessible domain constructor is
 * used only to exercise the pure scalar proposal, never a production acceptance read.
 * Empty price/tender/attempt lists are unasserted shape fillers, not complete history.
 * Native A-CORE, A-OTHER, C0/C1/C2, authority and no-ledger-effects remain unbound.
 */
internal class FrozenReturnStockRefusalData {
    private val frozen: FrozenReturnStockRefusalInputs = FrozenReturnStockRefusalInputs
    val actorId: DomainId = frozen.id(10)
    val saleId: DomainId = frozen.id(20)
    val caseId: DomainId = frozen.id(21)
    val returnId: DomainId = frozen.id(22)
    val portionId: DomainId = frozen.id(23)
    val issueId: DomainId = frozen.id(24)
    val allocationId: DomainId = frozen.id(25)
    private val acceptanceOperationId: OperationId = frozen.operation(26)
    private val acceptanceDigest: PayloadDigest = sha256("Pure RSDF-v1 data coordinate".encodeToByteArray())
    private val receiptAt: UtcInstant = frozen.instant(frozen.timing.literal("authoritative_customer_receipt_utc"))
    private val submissionAt: UtcInstant = frozen.instant(frozen.timing.literal("immutable_original_master_submission_utc"))
    val recordedAt: UtcInstant = frozen.instant(frozen.timing.literal("later_processing_example_utc"))
    val inspection: LocalCashEvidenceV1 = LocalCashEvidenceV1(frozen.id(27), recordedAt)
    val claimEvidence: ReturnClaimEvidence = ReturnClaimEvidence(frozen.id(28), Revision(1),
        receiptAt.value, submissionAt.value, null, "Synthetic numeric and pure proposal input",
        CashierEvidence(frozen.id(29), actorId, submissionAt.value))
    val accepted: AcceptedReturn = AcceptedReturn(returnId, frozen.scope, saleId, caseId, portionId,
        frozen.quantity(frozen.invariants.literal("current_accepted_quantity")),
        frozen.quantity(frozen.core.getValue("independent_business_sequence").jsonArray.map { it.jsonObject }
            .single { it.literal("fact") == "earlier accepted return" }.literal("quantity")),
        actorId, claimEvidence, ReturnDisposition.INSPECTED_SELLABLE,
        CashierEvidence(inspection.evidenceId, actorId, inspection.occurredAt.value),
        frozen.money(frozen.invariants.literal("current_refund_due")), emptyList(), emptyList(), Revision(0))

    fun request(before: LocalReturnStockStateV1? = null, restorationNumber: Int = 1): LocalReturnStockRequestV1 {
        val cut = when {
            before == null -> frozen.cut("C0")
            before.postedQuantity == frozen.quantity(frozen.cut("C1").literal("current_disposed_quantity")) -> frozen.cut("C1")
            before.postedQuantity == frozen.quantity(frozen.cut("C2").literal("current_disposed_quantity")) -> frozen.cut("C2")
            else -> error("No unchanged frozen CORE predecessor is bound")
        }
        return LocalReturnStockRequestV1(saleId, caseId, returnId,
            // CASE/issue revisions below are unused shape coordinates. Zero is the
            // existing adapter's absent pure frontier; it is NOT a claimed native D0.
            Revision(1), before?.revision ?: Revision(0), accepted.quantity,
            frozen.quantity(cut.literal("current_disposed_quantity")), issueId, Revision(1),
            frozen.id(100 + restorationNumber), listOf(LocalReturnStockSelectionV1(allocationId,
                frozen.quantity(frozen.unitPost.literal("quantity_delta")), LocalReturnStockCustodyV1.RELEASED)), inspection)
    }

    fun normalized(request: LocalReturnStockRequestV1): StoredBytes = LocalReturnStockCodecV1.encode(request)

    /** Fingerprint of a pure data value only; it is not retained BEFORE evidence. */
    fun fingerprint(before: LocalReturnStockStateV1?): PayloadDigest? = before?.let {
        sha256(localSalesEncode(it, LocalReturnStockStateV1.serializer(), 8_192).copyBytes())
    }

    fun post(request: LocalReturnStockRequestV1, before: LocalReturnStockStateV1? = null,
        operationNumber: Int = 1, beforeDigest: PayloadDigest? = fingerprint(before)): LocalReturnStockPostingV1 =
        returnStockPostingValue(frozen.scope, frozen.operation(300 + operationNumber),
            semanticDigest(frozen.scope, actorId, LocalReturnStockProfileV1.COMMAND, normalized(request)),
            actorId, recordedAt, request, accepted, acceptanceOperationId, acceptanceDigest, before, beforeDigest)
}
