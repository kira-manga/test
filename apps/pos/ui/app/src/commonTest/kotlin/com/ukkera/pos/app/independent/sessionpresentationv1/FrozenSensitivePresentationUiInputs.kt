package com.ukkera.pos.app.independent.sessionpresentationv1

import com.ukkera.pos.types.OperationId

/** Verbatim selections; the unchanged independently frozen JSON remains the full oracle. */
internal object FrozenSensitivePresentationUiInputs {
    const val CASES_SHA256 = "600eca0f7fabc528338eb91f9fb38ce6eef40cb4e50b9c89f2696f8dc0a3f124"
    const val FIXTURES_SHA256 = "32f005a31903267406e83e27c502c18bcdebfe326eca995a5fbb3be4c28ffee2"
    const val FREEZE_SHA256 = "3dc17f0ce7fc990156abdbfde22e8d421bab65fe71523ce302de31b9ac7b536a"
    const val PRIVATE_MARKER = "PRIVATE-A-LSP-DRAFT"
    const val SAFE_SEARCH = "LSP 000031"
    const val CURRENT_MARKER = "CURRENT-WORKFLOW-LSP"
    const val COST_MINOR = "2000"
    const val MARGIN_MINOR = "8000"
    val PAYMENT_ID = OperationId("50a00000-0000-4000-8000-000000000101")
    val KITCHEN_ID = OperationId("50a00000-0000-4000-8000-000000000301")

    val RENEWAL_UI = FrozenSensitiveUiAssertion("LSP-001-A01",
        "Same active workflow can still recover its supported checkpointed PRIVATE-A-LSP-DRAFT and LSP 000031 safe fields. No user change, implicit confirmation or false payment/sale completion is shown.")
    val RENEWAL_IDENTITY = FrozenSensitiveUiAssertion("LSP-001-A02",
        "OP-PAY-ORIGINAL, originating A/company/branch, lock/attempt, pinned revisions and every semantic BODY-PAY-ORIGINAL field remain identical; only transient authentication/connection metadata may change.")
    val RENEWAL_BOUNDARY = FrozenSensitiveUiAssertion("LSP-001-A03",
        "Between accepted renewal and explicit lookup, newly initiated payment/charge/sale/approval/kitchen mutations are zero. The explicit lookup uses current authorization and original OP-PAY-ORIGINAL.")
    val NARROWED_UI = FrozenSensitiveUiAssertion("LSP-002-A01",
        "A remains the same enabled employee. Cost/margin sensitive views and revoked actions are cleared/denied after current policy becomes known; supported safe input in the still-permitted same cashier workflow remains recoverable.")
    val NARROWED_IDENTITY = FrozenSensitiveUiAssertion("LSP-002-A03",
        "Original pending payment ID/body/actor remain pinned; retired permission snapshot is never proof of a new action. No automatic payment retry or new body is generated.")
    val STALE_CURRENT_UI = FrozenSensitiveUiAssertion("LSP-010-A01",
        "Old cost/margin/private data never appears after current narrowed policy is known. Old error cannot logout current valid A, clear allowed current safe input, install a dialog/toast or navigate current workflow.")
    val DEPARTED_UI = FrozenSensitiveUiAssertion("LSP-018-A01",
        "Fresh generic workflow has no PRIVATE-A-LSP-DRAFT, LSP 000031 search, old unsent cart choice/confirmation, password/proof, report/media, error/dialog/toast or private former action state. Current authorized shared catalog can load independently.")
    val LATE_UI = FrozenSensitiveUiAssertion("LSP-020-A01",
        "Replacement marker/values/dialog/guard and valid current employee remain unchanged by the former result. No old field, toast/error, navigation, logout, submit state, auto-confirmation or guard removal occurs.")
    val LATE_BOUNDARY = FrozenSensitiveUiAssertion("LSP-020-A02",
        "Old completion/cancel registrations initiate zero new protected actions, approval or retry. Newly scheduled current protected read uses current context, not the departed gateway.")
    val FOOD_UI = FrozenSensitiveUiAssertion("LSP-027-A01",
        "Departed result never inserts old tickets/cart/toast/error or changes replacement actions/actor. Fresh authentication alone does not auto-send; recovery uses the explicit protected original route.")
    val FOOD_IDENTITY = FrozenSensitiveUiAssertion("LSP-027-A03",
        "Original OP-KITCHEN-ORIGINAL, portion/task/issue/source IDs, original recipe/quantity snapshot and restaurant account actor are preserved across loss/logout/restart/identical retry; no independent new ticket or child IDs.")
    val FOOD_BOUNDARY = FrozenSensitiveUiAssertion("LSP-027-A04",
        "No automatic kitchen send, preparation/print instruction, issue, sale or charge originates from cleanup/restart/late delivery. Identical explicit retry returns the one original outcome; no cancellation/restoration algorithm is tested here.")
    val COST_ONLY_UI = FrozenSensitiveUiAssertion("LSP-029-V02-A01",
        "Only retired report-cost/export fields/actions are cleared. A is still the valid employee; separately allowed current cashier actions remain usable and original pending tuple stays pinned.")
    val COST_ONLY_BOUNDARY = FrozenSensitiveUiAssertion("LSP-029-V02-A02",
        "Removed cost/export requests are denied/redacted. A permitted current quantity/cashier preflight read can succeed under current grants; do not submit a new collection during this variant. Limited original recovery does not reinstate report rights.")
    val PRIVACY = FrozenSensitiveUiAssertion("NO-PRIVATE-FRAME",
        "From accepted employee cleanup or actual replacement until a separately explicit current authorized protected reread/resume, no observed frame/semantic state exposes the previous private note/search/cart/customer/report fields, token/permission snapshot, unmasked proof, operation detail, error/dialog/confirmation or unauthorized media. A later explicit protected reread may display only its authorized current projection; it never restores old tokens/head secret/retired grants or permits departed callbacks to populate the current UI. Allowed shared catalog/public auth have current scope.")
    val RECOVERY = FrozenSensitiveUiAssertion("RECOVERY-ONLY",
        "Presentation cleanup cannot delete, relabel, compensate, auto-finalize or auto-replay original completed/unknown business evidence. Recovery detail comes only after explicit currently protected reread/reconciliation by the original authorized account or a separately scoped audited supervisor. A read itself changes no business totals.")
    val NO_AUTO = FrozenSensitiveUiAssertion("NO-AUTO-SUBMIT",
        "After the named renewal/refusal/departure/recreation cut, initiating-app new protected mutation/approval/charge/refund/kitchen-send invocations equal zero until the explicitly scheduled user action. Original in-flight delivery or committed effects are classified separately; no automatic resubmission under either the same or a new ID.")
}

internal data class FrozenSensitiveUiAssertion(val id: String, val expected: String) {
    fun message(observedSlice: String): String =
        "$id shared-presentation portion: $observedSlice. Full frozen expectation: $expected"
}

/** Unchanged supplement pointers, not newly assigned independent assertion IDs. */
internal object FrozenSensitiveSupplementUiInputs {
    const val NO_ACCESS_CASES_SHA256 = "2793bbde0a4c8821ff1da58e49779a5ed398a26db5f0eef21e519e32463cc2ba"
    const val NO_ACCESS_FIXTURES_SHA256 = "cbab5b991a6fef70a71ca5fd820b97f9cdf9111cd783885d86a26c9ac28e2615"
    val ORIGINAL_PARTIAL_PAYMENT_ID = OperationId("237ddda1-f245-44b4-895e-11a3d43ff126")
    const val ORIGINAL_PARTIAL_PAYMENT_MINOR = "5700"
    const val ORIGINAL_PAYABLE_REVISION = "18"
    const val ORIGINAL_TERMINAL_REFERENCE = "SYNTHETIC-NA-PARTIAL-57"
    val NO_AREAS_RETIRED = FrozenSensitiveUiAssertion("LSNA-01 steps[2].expected",
        "On that observation, the active protected screen, private transient cart/search, private report, subscriptions and protected navigation/confirmation presentation are retired. No protected data is visible in the common UI, accessibility semantics or rendered frames.")
    val NO_AREAS_UI = FrozenSensitiveUiAssertion("LSNA-01 steps[3].expected",
        "Show safe information that this employee has no available operational area. The valid employee is not represented as expired, disabled or explicitly logged out. No operational area, protected record, cost/export, alert content, approval or recovery action becomes available.")
    val NO_AREAS_BOUNDARY = FrozenSensitiveUiAssertion("LSNA-01 steps[4].expected",
        "A remains enabled and its originally valid session is not revoked by permission loss. Automatic mutation/retry/refund/acknowledgement requests are 0; B-SHELL business/custody and current master authority are unchanged.")
    val NO_ACCESS_LATE = FrozenSensitiveUiAssertion("LSNA-02 steps[3].expected",
        "Neither old result, error nor confirm/cancel completion reveals a cost frame, restores the cart, opens a former dialog, changes the replacement session status, navigates to former protected history or dispatches work.")
    val REGRANT_UI = FrozenSensitiveUiAssertion("LSNA-05 steps[3].expected",
        "The new private cart has no restored NA-SKU-A quantity-3 line/search or old pending confirmation/history. The fresh permitted projection shows the fixed SKU/0007 barcode/10 base units as applicable to its defined public fields and contains no cost or valuation fields.")
    val REGRANT_BOUNDARY = FrozenSensitiveUiAssertion("LSNA-05 steps[5].expected",
        "B-SHELL business/cash/master values remain unchanged. No retired command or original unresolved payment is automatically submitted, retried, recovered, refunded or acknowledged.")
    val UNKNOWN_CLEANUP = FrozenSensitiveUiAssertion("LSNA-06 steps[2].expected",
        "Private presentation clears; pending command/result/attestation is not deleted or changed. No automatic lookup/resubmission, new operation ID/body, actor change, sale, stock issue, cash movement, external charge/refund or alert acknowledgement occurs.")
    val NO_AUTH_RECOVERY = FrozenSensitiveUiAssertion("LSNA-06 observable_assertions[2]",
        "No supervisor, full cashier, company-head, service or master recovery authority is fabricated for A or B.")
    val LSNA_STORAGE_LIMIT = FrozenSensitiveUiAssertion("LSNA-06 observable_assertions[3]",
        "Actual protected storage persistence and real boundary evidence are mandatory. In-memory journals, fixture-only state transitions and mocked request counts cannot prove durability.")
    val LSNA_RECOVERY_GAP = FrozenSensitiveUiAssertion("LSNA-06 observable_assertions[4]",
        "The exact post-removal protected recovery projection/grant must be supplied separately; preservation alone does not close that required monetary-resolution mapping.")
}
