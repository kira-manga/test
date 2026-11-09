package com.ukkera.pos.app.independent.routerestorationv1

import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.types.DomainId

/** Selected literal inputs/outcomes from local-route-restoration-v1, frozen version 1.0. */
internal object FrozenRouteRestorationUiInputs {
    val EMPLOYEE_A = DomainId("3ad40000-0000-4000-8000-000000000301")
    val EMPLOYEE_B = DomainId("3ad40000-0000-4000-8000-000000000302")
    val COMPANY_A = DomainId("3ad40000-0000-4000-8000-000000000101")
    val BRANCH_A = DomainId("3ad40000-0000-4000-8000-000000000201")
    val DEVICE_A = DomainId("3ad40000-0000-4000-8000-000000000401")
    val SESSION_A = DomainId("3ad40000-0000-4000-8000-000000000501")
    val SESSION_B = DomainId("3ad40000-0000-4000-8000-000000000503")
    val ORDER_A = DomainId("3ad40000-0000-4000-8000-000000000601")
    const val P40 = 40
    const val P41 = 41
    const val P42 = 42

    // Areas/epochs are controlled presentation bindings, not activated permissions or authority.
    fun employeeAProjection(policy: Int): SessionDisplayContext = SessionDisplayContext(
        EMPLOYEE_A, COMPANY_A, BRANCH_A, "employee_a", "company_a", "branch_a",
        when (policy) {
            P40, P42 -> setOf(AppArea.ORDERS, AppArea.STOCK)
            P41 -> setOf(AppArea.STOCK)
            else -> error("Unbound frozen policy label.")
        },
        when (policy) {
            P40 -> LocalSessionDraftEpochs(1, 1)
            P41 -> LocalSessionDraftEpochs(2, 2)
            P42 -> LocalSessionDraftEpochs(3, 3)
            else -> error("Unbound frozen policy label.")
        },
    )

    fun employeeBProjection(): SessionDisplayContext = SessionDisplayContext(
        EMPLOYEE_B, COMPANY_A, BRANCH_A, "employee_b", "company_a", "branch_a",
        setOf(AppArea.STOCK), LocalSessionDraftEpochs(1, 1),
    )

    val QUARANTINE = FrozenRouteRestorationUiAssertion("LRR-001 exact_assertions[1]",
        "Before valid local human authentication and completion of current referenced-entity authorization/read checks, protected checkpoint frames and protected-data disclosures both equal 0.")
    val OTHER_EMPLOYEE = FrozenRouteRestorationUiAssertion("LRR-001-B",
        "Reject checkpoint restoration containing employee_a's owner-private order or cost-only reference. Show no protected checkpoint frame, old employee_a draft, cost field or private marker. Employee_b's separate valid session is preserved; no employee_a grant is inherited from the checkpoint or device.")
    val WITHDRAWN_ROUTE = FrozenRouteRestorationUiAssertion("LRR-002-P exact_assertions[0]",
        "After P41 is current, R-order/withdrawn private data has 0 rendered frames/disclosures from pending or earlier allowed results; no partial private payload is surfaced on rejection.")
    val VALID_SESSION = FrozenRouteRestorationUiAssertion("LRR-002-P exact_assertions[1]",
        "session_a remains a valid local human session. Permission loss alone causes no fabricated session expiry, logout or credential failure; presentation reports the missing permission and uses a current permitted safe surface.")
    val NO_AUTOMATIC_RETURN = FrozenRouteRestorationUiAssertion("LRR-002-P exact_assertions[3]",
        "P42 and stale completions cause 0 automatic reappearance of the withdrawn private route/draft/history. A fresh deliberate authorized entry loads LRR_CURRENT_ORDER_READ through a new permitted read and does not reuse LRR_WITHDRAWN_DRAFT_D1 or obsolete private history.")
}

internal data class FrozenRouteRestorationUiAssertion(val id: String, val expected: String) {
    fun message(slice: String): String = "$id: $slice. Frozen expectation: $expected"
}
