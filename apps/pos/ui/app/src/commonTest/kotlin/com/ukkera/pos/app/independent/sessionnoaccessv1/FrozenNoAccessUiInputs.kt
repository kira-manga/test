package com.ukkera.pos.app.independent.sessionnoaccessv1

import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.types.DomainId

/** Verbatim v1.0.0 fixture/expected selections; the immutable JSON remains the complete oracle. */
internal object FrozenNoAccessUiInputs {
    const val VERSION = "1.0.0"
    const val CASES_SHA256 = "2793bbde0a4c8821ff1da58e49779a5ed398a26db5f0eef21e519e32463cc2ba"
    const val FIXTURES_SHA256 = "cbab5b991a6fef70a71ca5fd820b97f9cdf9111cd783885d86a26c9ac28e2615"
    val employeeA = DomainId("4d40cd77-44f7-4f79-b23a-5cbb5e0a8d17")
    val company = DomainId("79d1e2f5-526b-41b8-bf1c-22620c598ee3")
    val branch = DomainId("16e207de-19d4-42fd-9d40-d1aec48a6fcb")
    val sku = DomainId("ed13d4d2-c0ea-4b65-95d6-fd25b8c0f6f7")
    const val EMPLOYEE_A_NAME = "Synthetic Local Employee A"
    const val PRIVATE_SEARCH = "NA-SKU-A"

    // Areas/epochs below are controlled presentation inputs. They never stand in for actual
    // local policy activation, permission limits, authentication admission or master grants.
    fun p10Display(): SessionDisplayContext = SessionDisplayContext(
        actorId = employeeA, companyId = company, branchId = branch, actorName = EMPLOYEE_A_NAME,
        companyName = "Company ${company.value}", branchName = "Branch ${branch.value}",
        allowedAreas = setOf(AppArea.CASHIER, AppArea.ORDERS, AppArea.DRAWER, AppArea.STOCK, AppArea.REPORTS),
        localDraftEpochs = LocalSessionDraftEpochs(1, 1),
    )
    fun p11Display(): SessionDisplayContext = p10Display().copy(
        allowedAreas = emptySet(), localDraftEpochs = LocalSessionDraftEpochs(2, 2),
    )
    fun p12Display(): SessionDisplayContext = p10Display().copy(
        allowedAreas = setOf(AppArea.CASHIER, AppArea.ORDERS, AppArea.STOCK),
        localDraftEpochs = LocalSessionDraftEpochs(3, 3),
    )

    val LSNA01_S3 = FrozenNoAccessUiStep("LSNA-01", 3,
        "On that observation, the active protected screen, private transient cart/search, private report, subscriptions and protected navigation/confirmation presentation are retired. No protected data is visible in the common UI, accessibility semantics or rendered frames.")
    val LSNA01_S4 = FrozenNoAccessUiStep("LSNA-01", 4,
        "Show safe information that this employee has no available operational area. The valid employee is not represented as expired, disabled or explicitly logged out. No operational area, protected record, cost/export, alert content, approval or recovery action becomes available.")
    val LSNA01_S5 = FrozenNoAccessUiStep("LSNA-01", 5,
        "A remains enabled and its originally valid session is not revoked by permission loss. Automatic mutation/retry/refund/acknowledgement requests are 0; B-SHELL business/custody and current master authority are unchanged.")
    val LSNA02_S4 = FrozenNoAccessUiStep("LSNA-02", 4,
        "Neither old result, error nor confirm/cancel completion reveals a cost frame, restores the cart, opens a former dialog, changes the replacement session status, navigates to former protected history or dispatches work.")
    val LSNA02_S5 = FrozenNoAccessUiStep("LSNA-02", 5,
        "All Back/input paths remain safe with identical authorization. Private cart quantity/search, cost view and confirmation do not return. The original alert is rechecked and does not disclose its private contents or acknowledge its obligation.")
    val LSNA04_S4 = FrozenNoAccessUiStep("LSNA-04", 4,
        "Local authentication/private UI closes immediately. Pending remote revocation is described truthfully while disconnected; no fabricated completed remote revocation is claimed. The original revocation intent is applied on permitted reconnect without adopting/replaying pending business work.")
    val LSNA05_S2 = FrozenNoAccessUiStep("LSNA-05", 2,
        "P11 still grants no area. Refresh, reentry and old successful authentication do not restore P10 rights or the private draft.")
    val LSNA05_S4 = FrozenNoAccessUiStep("LSNA-05", 4,
        "The new private cart has no restored NA-SKU-A quantity-3 line/search or old pending confirmation/history. The fresh permitted projection shows the fixed SKU/0007 barcode/10 base units as applicable to its defined public fields and contains no cost or valuation fields.")
    val LSNA05_S5 = FrozenNoAccessUiStep("LSNA-05", 5,
        "A protected fresh read may show that durable order with its original owner/revision/quantity 1; this is distinct from restoring the unsent cart. Old cost/history/draft/confirmation/callbacks do not alter the new workflow or reveal denied data.")
    val LSNA05_S6 = FrozenNoAccessUiStep("LSNA-05", 6,
        "B-SHELL business/cash/master values remain unchanged. No retired command or original unresolved payment is automatically submitted, retried, recovered, refunded or acknowledged.")
}

internal data class FrozenNoAccessUiStep(val caseId: String, val number: Int, val expected: String) {
    fun message(observedSlice: String): String =
        "$caseId step $number: $observedSlice. Frozen expectation: $expected"
}
