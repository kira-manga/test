# Original business scenarios

**All NOT RUN for this manual catalog.** Use [the execution guide](README.md) and [original fixtures](ENVIRONMENTS.md). Each expected outcome below is copied from the frozen scope register; steps reorganize the original trigger, not the implementation. Required automation stays under the linked development Issues.

## CASH-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Open a drawer with counted float 1,000
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** One cashier/one active session; opening cash 1,000 and sales zero; retry creates no second opening

**Local obligations (unchanged):**
- One cashier/one active session; opening cash 1,000 and sales zero; retry creates no second opening

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L13) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Attempt a second cashier session on the occupied drawer
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject competing custody without changing either session

**Local obligations (unchanged):**
- Reject competing custody without changing either session

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L14) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Opening 1,000; received 5,000; change 400; cash refunds 200; pay-in 100; pay-out 150; safe drop 3,000; external tenders 4,000
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Expected closing cash 2,350; external tenders shown separately; no double subtraction of change

**Local obligations (unchanged):**
- Expected closing cash 2,350; external tenders shown separately; no double subtraction of change

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L15) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Count 2,360 against expected 2,350
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Overage 10; preserve actual count; no invented sale or forced employee debt

**Local obligations (unchanged):**
- Overage 10; preserve actual count; no invented sale or forced employee debt

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L16) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Retain float 500 from actual closing cash 2,360
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Closing safe transfer 1,860; safe acknowledgement shares the transfer ID and is not revenue

**Local obligations (unchanged):**
- Closing safe transfer 1,860; safe acknowledgement shares the transfer ID and is not revenue

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L17) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Logout, lock screen or move to another admitted device
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Human session changes do not close, transfer or duplicate cash custody

**Local obligations (unchanged):**
- Human session changes do not close, transfer or duplicate cash custody

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L18) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Refund a previous day's receipt from today's cashier drawer
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Today's actual cash decreases; original sale linked; previous closing report remains immutable

**Local obligations (unchanged):**
- Today's actual cash decreases; original sale linked; previous closing report remains immutable

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L19) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Close during an unknown collection outcome
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Recover the original command or preserve explicit controlled exception; no silently lost payment or duplicate next-shift collection

**Local obligations (unchanged):**
- Recover the original command or preserve explicit controlled exception; no silently lost payment or duplicate next-shift collection

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L20) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Submit closing while another request attempts a payment
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Deterministic closing cut: request belongs to the open session before the cut or is rejected; no uncounted post-cut movement

**Local obligations (unchanged):**
- Deterministic closing cut: request belongs to the open session before the cut or is rejected; no uncounted post-cut movement

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L21) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-10

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Manager reviews a submitted variance after the next shift opens
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Append review/adjustment evidence; no reopening or overwriting of the original count

**Local obligations (unchanged):**
- Append review/adjustment evidence; no reopening or overwriting of the original count

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L22) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-11

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Safe transfer receipt is lost/duplicated
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Recover one transfer; show unresolved custody until acknowledged; never count it twice

**Local obligations (unchanged):**
- Recover one transfer; show unresolved custody until acknowledged; never count it twice

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L23) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CASH-12

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Refund/pay-out exceeds available drawer cash
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Do not claim cash paid; retain obligation and require actual replenishment/allowed alternative

**Local obligations (unchanged):**
- Do not claim cash paid; retain obligation and require actual replenishment/allowed alternative

**Required verification needs (unchanged):**
- Use actual drawer/opening/movement/close/review screens and production cash service; independently reconcile signed ledger, physical-cash observation, cutoff, actors and immutable reports.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L24) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. A user from branch A attempts login/action in branch B
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Require a separate enabled B account; no inherited A permissions, drawer or orders

**Local obligations (unchanged):**
- Require a separate enabled B account; no inherited A permissions, drawer or orders

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L30) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. User B logs into a device previously used by A
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Clear A's private UI/tokens; retain protected unresolved operations attributed to A

**Local obligations (unchanged):**
- Clear A's private UI/tokens; retain protected unresolved operations attributed to A

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L31) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Another cashier lists, opens or edits an owner's active order
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Deny ordinary access through list, direct ID, subscription and cached projection

**Local obligations (unchanged):**
- Deny ordinary access through list, direct ID, subscription and cached projection

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L32) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Owner offers a handoff and recipient accepts twice
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Exactly one ownership transition; original creator and earlier payment actors preserved

**Local obligations (unchanged):**
- Exactly one ownership transition; original creator and earlier payment actors preserved

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L33) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Handoff races with payment/another handoff
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Version/lock policy gives one valid outcome; no second owner or relabelled collection

**Local obligations (unchanged):**
- Version/lock policy gives one valid outcome; no second owner or relabelled collection

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L34) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Manager reassigns an absent employee's order
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Explicit permission/reason and audit; cash already collected remains with its original session

**Local obligations (unchanged):**
- Explicit permission/reason and audit; cash already collected remains with its original session

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L35) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Kitchen or courier opens its assigned projection
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Required task data visible; unrelated prices/customer/financial records remain inaccessible

**Local obligations (unchanged):**
- Required task data visible; unrelated prices/customer/financial records remain inaccessible

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L36) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Split or merge a bill with shared fees and partial payments
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Exact quantities, charges and collected amounts preserved once; no repeated ingredient issue

**Local obligations (unchanged):**
- Exact quantities, charges and collected amounts preserved once; no repeated ingredient issue

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L37) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Split/merge across incompatible branch/company or without both ownership grants
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject without partial mutation

**Local obligations (unchanged):**
- Reject without partial mutation

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L38) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-10

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Authorized return operator looks up another cashier's historical receipt
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Only necessary receipt data accessible; create a new linked return, preserving original ownership/history

**Local obligations (unchanged):**
- Only necessary receipt data accessible; create a new linked return, preserving original ownership/history

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L39) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## OWN-11

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-ORDER, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Staff accidentally creates two separate orders for one intended request
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Explicitly link/cancel the duplicate; preserve any real preparation/payment effects instead of treating different operation IDs as automatic transport duplicates

**Local obligations (unchanged):**
- Explicitly link/cancel the duplicate; preserve any real preparation/payment effects instead of treating different operation IDs as automatic transport duplicates

**Required verification needs (unchanged):**
- Use actual branch login/order/handoff/table/receipt screens plus production list/direct-ID/subscription and command paths; inspect ownership transitions, previous actors and preserved stock/tender allocations.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L40) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Send two burgers; initial bread 10, meat 1,500 g; each requires one bread and 150 g meat
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Bread 8 and meat 1,200 g; two durable portion tasks and one set of issue effects

**Local obligations (unchanged):**
- Bread 8 and meat 1,200 g; two durable portion tasks and one set of issue effects

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L46) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Retry the same kitchen send after a lost reply/restart
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Same tasks/issue IDs and quantities; no second deduction or new independent ticket

**Local obligations (unchanged):**
- Same tasks/issue IDs and quantities; no second deduction or new independent ticket

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L47) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Cancel one of those two portions before verified start
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Bread 9 and meat 1,350 g; reverse original allocation for one portion only

**Local obligations (unchanged):**
- Bread 9 and meat 1,350 g; reverse original allocation for one portion only

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L48) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Cancel both after preparation starts
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Bread stays 8 and meat 1,200 g; waste/cost disposition recorded without another negative issue

**Local obligations (unchanged):**
- Bread stays 8 and meat 1,200 g; waste/cost disposition recorded without another negative issue

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L49) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Some portions started and others not
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Restore only verified unstarted quantities; preserve started portion cost

**Local obligations (unchanged):**
- Restore only verified unstarted quantities; preserve started portion cost

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L50) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Start and confirm-before-start-cancellation arrive concurrently
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** One transition wins; a cancelled portion cannot subsequently start from a stale screen

**Local obligations (unchanged):**
- One transition wins; a cancelled portion cannot subsequently start from a stale screen

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L51) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N8.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Paper ticket printed but preparation state unknown
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** No automatic restock; cancellation awaits kitchen/authorized confirmation

**Local obligations (unchanged):**
- No automatic restock; cancellation awaits kitchen/authorized confirmation

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY, L6_PHYSICAL_UNVERIFIED_UNTIL_EXECUTED.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L52) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N8.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Printer response lost or printer unavailable after kitchen send
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Committed issue remains durable; printing/reprint state explicit; no blind cancellation or duplicate preparation instruction

**Local obligations (unchanged):**
- Committed issue remains durable; printing/reprint state explicit; no blind cancellation or duplicate preparation instruction

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY, L6_PHYSICAL_UNVERIFIED_UNTIL_EXECUTED.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L53) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Recipe, yield, modifier or lot configuration changes after issue
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Cancellation uses the original snapshot/allocation; current recipe does not rewrite old consumption

**Local obligations (unchanged):**
- Cancellation uses the original snapshot/allocation; current recipe does not rewrite old consumption

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L54) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-10

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Merchant remakes an incorrectly prepared item
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** New ingredients checked/issued; original waste recorded; one customer charge and no doubled sold quantity

**Local obligations (unchanged):**
- New ingredients checked/issued; original waste recorded; one customer charge and no doubled sold quantity

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L55) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-11

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Replacement ingredients insufficient
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject new preparation; offer authorized available alternative/refund, without negative stock

**Local obligations (unchanged):**
- Reject new preparation; offer authorized available alternative/refund, without negative stock

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L56) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-12

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Finalize payment for portions already sent to kitchen
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Preserve prior ingredient deductions; finalization does not deduct them again

**Local obligations (unchanged):**
- Preserve prior ingredient deductions; finalization does not deduct them again

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L57) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-13

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Customer changes an already-started order
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Explicit charge/waiver and replacement decisions; old ingredients remain consumed and any paid difference is settled explicitly

**Local obligations (unchanged):**
- Explicit charge/waiver and replacement decisions; old ingredients remain consumed and any paid difference is settled explicitly

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L58) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-14

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. One kitchen station starts while another still shows queued
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Portion counts as started; no whole-recipe before-start reversal; ready requires every required task

**Local obligations (unchanged):**
- Portion counts as started; no whole-recipe before-start reversal; ready requires every required task

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L59) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## KIT-15

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-KITCHEN, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Customer prepays before kitchen send
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Ingredients reserved before collection, retained while paid/unsent, then converted into one issue on send; cancellation releases reservation and resolves refund

**Local obligations (unchanged):**
- Ingredients reserved before collection, retained while paid/unsent, then converted into one issue on send; cancellation releases reservation and resolves refund

**Required verification needs (unchanged):**
- Use real order and station/production screens plus production recipe/reservation/task/issue/waste and print-outbox persistence; independently count per-portion stock/cost and unchanged customer charges.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L60) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## STK-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Sell a tracked item with zero available quantity
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject before requesting collection; no manager negative-stock bypass

**Local obligations (unchanged):**
- Reject before requesting collection; no manager negative-stock bypass

**Required verification needs (unchanged):**
- Use actual scan/inventory/receiving/count/inspection screens plus production stock allocation/ledger queries from fresh connections; reconcile sellable/reserved/held quantities and source identities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L66) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## STK-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Two clients compete for the last available quantity
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** At most the available quantity reserved/sold; loser receives current shortage information

**Local obligations (unchanged):**
- At most the available quantity reserved/sold; loser receives current shortage information

**Required verification needs (unchanged):**
- Use actual scan/inventory/receiving/count/inspection screens plus production stock allocation/ledger queries from fresh connections; reconcile sellable/reserved/held quantities and source identities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L67) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## STK-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. One recipe/kit component is short despite others being available
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject the selected batch atomically; do not partially issue hidden components

**Local obligations (unchanged):**
- Reject the selected batch atomically; do not partially issue hidden components

**Required verification needs (unchanged):**
- Use actual scan/inventory/receiving/count/inspection screens plus production stock allocation/ledger queries from fresh connections; reconcile sellable/reserved/held quantities and source identities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L68) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## STK-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Stock exists only as expired, blocked, quarantined or reserved units
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Those quantities are not advertised or sold as available

**Local obligations (unchanged):**
- Those quantities are not advertised or sold as available

**Required verification needs (unchanged):**
- Use actual scan/inventory/receiving/count/inspection screens plus production stock allocation/ledger queries from fresh connections; reconcile sellable/reserved/held quantities and source identities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L69) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## STK-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5, N8.

**Fixture:** F-BASE, F-AUTH, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Scan weighted/pack barcode or edit decimal quantities
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Exact configured conversion and authoritative price; leading-zero identifiers preserved; malformed formats rejected

**Local obligations (unchanged):**
- Exact configured conversion and authoritative price; leading-zero identifiers preserved; malformed formats rejected

**Required verification needs (unchanged):**
- Use actual scan/inventory/receiving/count/inspection screens plus production stock allocation/ledger queries from fresh connections; reconcile sellable/reserved/held quantities and source identities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY, L6_PHYSICAL_UNVERIFIED_UNTIL_EXECUTED.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L70) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## STK-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Duplicate receiving/transfer receipt after lost acknowledgement
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** One stock increase with the original source ID; discrepancies remain visible

**Local obligations (unchanged):**
- One stock increase with the original source ID; discrepancies remain visible

**Required verification needs (unchanged):**
- Use actual scan/inventory/receiving/count/inspection screens plus production stock allocation/ledger queries from fresh connections; reconcile sellable/reserved/held quantities and source identities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L71) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## STK-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Stock count overlaps sales/receipts
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Apply a verified movement cut/deltas or require recount; no silent balance overwrite

**Local obligations (unchanged):**
- Apply a verified movement cut/deltas or require recount; no silent balance overwrite

**Required verification needs (unchanged):**
- Use actual scan/inventory/receiving/count/inspection screens plus production stock allocation/ledger queries from fresh connections; reconcile sellable/reserved/held quantities and source identities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L72) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## STK-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Collection outcome unknown while reservation would normally expire
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Preserve the reservation/evidence until explicit resolution; no double allocation to another sale

**Local obligations (unchanged):**
- Preserve the reservation/evidence until explicit resolution; no double allocation to another sale

**Required verification needs (unchanged):**
- Use actual scan/inventory/receiving/count/inspection screens plus production stock allocation/ledger queries from fresh connections; reconcile sellable/reserved/held quantities and source identities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L73) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## STK-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-STOCK, F-PAY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Return accepted as damaged rather than sellable
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Quantity moves to quarantine/waste disposition; cannot be sold merely because money was refunded

**Local obligations (unchanged):**
- Quantity moves to quarantine/waste disposition; cannot be sold merely because money was refunded

**Required verification needs (unchanged):**
- Use actual scan/inventory/receiving/count/inspection screens plus production stock allocation/ledger queries from fresh connections; reconcile sellable/reserved/held quantities and source identities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L74) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Cashier records external terminal success while internet is unavailable
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Local validation and completion; no POS provider verification call required

**Local obligations (unchanged):**
- Local validation and completion; no POS provider verification call required

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L80) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Cashier records not-paid or an insufficient partial tender
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** No false paid-sale finalization; remaining amount visible

**Local obligations (unchanged):**
- No false paid-sale finalization; remaining amount visible

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L81) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Cash plus external tender with cash change
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Correct coverage and one net physical cash effect; no card change invented

**Local obligations (unchanged):**
- Correct coverage and one net physical cash effect; no card change invented

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L82) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. External payment occurred, then reply/storage availability is uncertain
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Preserve attestation and operation ID; recover outcome without instructing another charge

**Local obligations (unchanged):**
- Preserve attestation and operation ID; recover outcome without instructing another charge

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L83) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Served check 300, customer pays 100 then leaves
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Actual collection 100; unpaid incident 200; no inventory restoration or automatic cashier shortage 200

**Local obligations (unchanged):**
- Actual collection 100; unpaid incident 200; no inventory restoration or automatic cashier shortage 200

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L84) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Collect the remaining 200 in a later shift
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Current collecting session receives 200; original incident reduced once; no second sale/stock issue

**Local obligations (unchanged):**
- Current collecting session receives 200; original incident reduced once; no second sale/stock issue

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L85) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Manager writes off an unpaid fulfilled check
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Approved exception and required accounting/fiscal treatment; no fictional cash movement or erasure of supply

**Local obligations (unchanged):**
- Approved exception and required accounting/fiscal treatment; no fictional cash movement or erasure of supply

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L86) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. COD order delivered but collector has not remitted
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Delivery, customer payment and collector custody remain separate; no second sale on remittance

**Local obligations (unchanged):**
- Delivery, customer payment and collector custody remain separate; no second sale on remittance

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L87) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Delivery refused/failed with prepared food or sealed goods
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Appropriate inspection/waste and fee/refund decision; no automatic ingredient restock

**Local obligations (unchanged):**
- Appropriate inspection/waste and fee/refund decision; no automatic ingredient restock

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L88) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-10

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Correct wrong tender classification after shift close
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Linked adjustment explains original custody; no new physical cash entry without money moving

**Local obligations (unchanged):**
- Linked adjustment explains original custody; no new physical cash entry without money moving

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L89) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-11

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Attempt customer credit, stored balance or loyalty redemption
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Feature absent/disabled in release one; cannot use a generic tender to bypass paid coverage

**Local obligations (unchanged):**
- Feature absent/disabled in release one; cannot use a generic tender to bypass paid coverage

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L90) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-12

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Prepared takeaway is never collected
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Abandoned-pickup exception, actual food disposition and explicit prepaid refund/charge decision; not falsely delivered or converted into customer credit

**Local obligations (unchanged):**
- Abandoned-pickup exception, actual food disposition and explicit prepaid refund/charge decision; not falsely delivered or converted into customer credit

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L91) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-13

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Recorded external payment is later disputed/reversed
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Preserve original attestation; append evidence, reconciliation and actual subsequent cash/tender effects; no silent inventory restoration

**Local obligations (unchanged):**
- Preserve original attestation; append evidence, reconciliation and actual subsequent cash/tender effects; no silent inventory restoration

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L92) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PAY-14

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-PAY, F-CASH, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Authorized exceptional retail release occurs before collection
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Availability checked, actual fulfillment/stock issue recorded once and unpaid obligation retained; no fictional payment or later second issue

**Local obligations (unchanged):**
- Availability checked, actual fulfillment/stock issue recorded once and unpaid obligation retained; no fictional payment or later second issue

**Required verification needs (unchanged):**
- Use real collection/fulfillment/incident/courier/correction screens and production payment/custody/stock persistence; synthetic terminal evidence records an external event, never an actual charge or a fake bank verification.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L93) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Same-branch partial return while offline
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Original local snapshot and entitlement used; no central lookup prerequisite

**Local obligations (unchanged):**
- Original local snapshot and entitlement used; no central lookup prerequisite

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L99) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Return receipt from another branch of the same company
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject and identify selling branch; no entitlement or stock mutation

**Local obligations (unchanged):**
- Reject and identify selling branch; no entitlement or stock mutation

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L100) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Simultaneous partial returns exceed remaining quantity/amount
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Atomically accept only valid remaining entitlement; duplicates return original result

**Local obligations (unchanged):**
- Atomically accept only valid remaining entitlement; duplicates return original result

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L101) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Accept return while external refund is pending/unknown
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Entitlement not reusable; refund due/unknown stays visible and recoverable

**Local obligations (unchanged):**
- Entitlement not reusable; refund due/unknown stays visible and recoverable

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L102) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Exchange return value 100 for replacement 130
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Linked return/new sale and restricted offset 100; actual collection 30 only

**Local obligations (unchanged):**
- Linked return/new sale and restricted offset 100; actual collection 30 only

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L103) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Exchange return value 100 for replacement 80
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Restricted offset 80 and actual refund 20; no spendable store-credit balance

**Local obligations (unchanged):**
- Restricted offset 80 and actual refund 20; no spendable store-credit balance

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L104) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Replacement becomes unavailable or collection fails during exchange
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Preserve accepted return and refund obligation; no fabricated completed replacement

**Local obligations (unchanged):**
- Preserve accepted return and refund obligation; no fabricated completed replacement

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L105) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Prepared/served food refunded
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Refund handled separately; original ingredients not automatically restored

**Local obligations (unchanged):**
- Refund handled separately; original ingredients not automatically restored

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L106) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Return after company price/tax/discount changes
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Use original permitted refundable snapshots/allocations, not today's price

**Local obligations (unchanged):**
- Use original permitted refundable snapshots/allocations, not today's price

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L107) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-10

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Receiptless return without enabled policy/approval
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject; exceptional permission cannot bypass evidence/valuation/inspection rules

**Local obligations (unchanged):**
- Reject; exceptional permission cannot bypass evidence/valuation/inspection rules

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L108) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-11

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Order was created before actual customer delivery; an eligible goods return/defect claim arrives near its deadline
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Use actual receipt/delivery date and applicable category/reason policy; record claim/deadline independently of inspection or actual payout

**Local obligations (unchanged):**
- Use actual receipt/delivery date and applicable category/reason policy; record claim/deadline independently of inspection or actual payout

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L109) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-12

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Change-of-mind exception applies to prepared/perishable goods, but customer reports merchant error/defect
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Separate remedy path remains available; no blanket denial and no automatic ingredient restock

**Local obligations (unchanged):**
- Separate remedy path remains available; no blanket denial and no automatic ingredient restock

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L110) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## RET-13

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N4, N5.

**Fixture:** F-BASE, F-AUTH, F-RETURN, F-PAY, F-STOCK, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Company shortens its commercial policy after an earlier sale or ordinary operator limit blocks a remedy
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Preserve promised/applicable rights, record and escalate the claim; no erased obligation or silent application of a less favorable current rule

**Local obligations (unchanged):**
- Preserve promised/applicable rights, record and escalate the claim; no erased obligation or silent application of a less favorable current rule

**Required verification needs (unchanged):**
- Use actual historical-receipt/claim/inspection/refund/exchange screens and production entitlement/reservation/cash/stock records; calculate original refundable allocations and remaining obligations independently.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L111) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## TAX-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-ORDER. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Company 100/revision 5; branch 120/revision 5; company 150/revision 6
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Activating revision 6 makes effective price 150; late old branch update cannot restore 120

**Local obligations (unchanged):**
- Through the real local activation path, start with company SKU price 100/revision 5 and authorized branch override 120/revision 5. Activate valid revision 6 at 150; the effective local price becomes 150. A late revision-5 branch edit cannot restore 120, and superseded history remains visible.

**Required verification needs (unchanged):**
- Use actual price/tax/policy/receipt/report screens and local production calculation/activation/ledger paths; independently calculate allocations and compare stored historical snapshots before and after changes.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Actual central company-authorized release publication, delivery and acceptance/acknowledgement across the central services.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L117) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## TAX-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-ORDER. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Company changes one SKU while other SKU has a branch price
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Apply only the relevant price revisions; unrelated branch changes remain intact

**Local obligations (unchanged):**
- Activate a valid local package for SKU A only; preserve the unrelated current branch override for SKU B. Reorder/retry packages and reject obsolete changes without partial activation.

**Required verification needs (unchanged):**
- Use actual price/tax/policy/receipt/report screens and local production calculation/activation/ledger paths; independently calculate allocations and compare stored historical snapshots before and after changes.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Central publication/distribution of the SKU-specific company release and central acknowledgement state.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L118) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## TAX-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-ORDER. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Company update reaches preserved versus reprice-eligible open orders
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Apply configured policy; paid/collection-locked/customer-committed snapshots not silently changed

**Local obligations (unchanged):**
- Exercise both configured local preserve and reprice policies on eligible open orders; update eligible unpaid/unlocked versions with visible totals before collection. Paid, partially collected, collection-locked and customer-committed snapshots stay protected.

**Required verification needs (unchanged):**
- Use actual price/tax/policy/receipt/report screens and local production calculation/activation/ledger paths; independently calculate allocations and compare stored historical snapshots before and after changes.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Central authoring, authorization and delivery of the company update/open-order policy.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L119) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## TAX-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-ORDER. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Inclusive 114 and exclusive 100, each at illustrative 14%
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Combined net 200, tax 28 and payable 228 before other charges

**Local obligations (unchanged):**
- Combined net 200, tax 28 and payable 228 before other charges

**Required verification needs (unchanged):**
- Use actual price/tax/policy/receipt/report screens and local production calculation/activation/ledger paths; independently calculate allocations and compare stored historical snapshots before and after changes.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L120) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## TAX-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-ORDER. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Fixed discount, mixed tax lines and partial return
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Advertised reduction, deterministic allocations and exact receipt totals; refund never exceeds original allocation

**Local obligations (unchanged):**
- Advertised reduction, deterministic allocations and exact receipt totals; refund never exceeds original allocation

**Required verification needs (unchanged):**
- Use actual price/tax/policy/receipt/report screens and local production calculation/activation/ledger paths; independently calculate allocations and compare stored historical snapshots before and after changes.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L121) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## TAX-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-ORDER. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Dine-in service, takeaway and delivery charge policies
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Correct applicable charge bases/tax; no hidden delivery fee on takeaway or duplicate inclusive tax

**Local obligations (unchanged):**
- Correct applicable charge bases/tax; no hidden delivery fee on takeaway or duplicate inclusive tax

**Required verification needs (unchanged):**
- Use actual price/tax/policy/receipt/report screens and local production calculation/activation/ledger paths; independently calculate allocations and compare stored historical snapshots before and after changes.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L122) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## TAX-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-UI. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Food and clothing companies share one organization
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Independent catalogs/profiles/accounts; unauthorized cross-company IDs, sync and caches rejected

**Local obligations (unchanged):**
- Provision separate restaurant and clothing companies with separate branches; enforce independent catalog, accounts, effective profiles and all local command/read/subscription/cache/attachment scopes. Reject cross-company IDs and incoming cross-scope package/replication data without business mutation.

**Required verification needs (unchanged):**
- Use actual price/tax/policy/receipt/report screens and local production calculation/activation/ledger paths; independently calculate allocations and compare stored historical snapshots before and after changes.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Actual central catalog/account management, central ingestion/queries and consolidated permissions across the organization.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L123) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## TAX-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-ORDER. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Fiscal submission is delayed/retried/rejected
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Truthful document status and durable retry/exception under applicable regime; no duplicate fiscal document or rewritten original sale

**Local obligations (unchanged):**
- Create immutable locally numbered fiscal/document intents with original sale/correction links; persist pending/retry/rejected/accepted-as-reported states, bounded retries and audit without repeating a sale or creating a duplicate document. Offline/unknown submission must never be displayed as accepted. Do not fabricate authority acceptance in the fixture.

**Required verification needs (unchanged):**
- Use actual price/tax/policy/receipt/report screens and local production calculation/activation/ledger paths; independently calculate allocations and compare stored historical snapshots before and after changes.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Real central-hosted fiscal submission/deduplication/receipt integration, if that is the selected connector path; actual authorized external fiscal sandbox/production acceptance also remains separately unverified, not an implied local pass.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L124) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## TAX-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CASH, F-PAY, F-RETURN, F-PRICE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Compare sales, collections, refunds, safe drops and incidents in reports
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reconcile each category separately to its ledger; cash count is not equated with total sales

**Local obligations (unchanged):**
- Reconcile each category separately to its ledger; cash count is not equated with total sales

**Required verification needs (unchanged):**
- Use actual price/tax/policy/receipt/report screens and local production calculation/activation/ledger paths; independently calculate allocations and compare stored historical snapshots before and after changes.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L125) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. One-device branch with no standby and central outage
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Existing local accounts and healthy local master continue ordinary checkout and same-branch returns

**Local obligations (unchanged):**
- Existing local accounts and healthy local master continue ordinary checkout and same-branch returns

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L131) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Central/standby return after a long backlog
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Durable idempotent catch-up while new work continues; no repeated sales, stock issues or cash effects

**Local obligations (unchanged):**
- With central disconnected, retain a durable local upload backlog while new work proceeds. Reconnect an actual optional local standby after a long backlog and copy a verified contiguous cut idempotently with bounded memory; never replay sales, stock, cash or print actions. Preserve the independent destination cursors and pending central work.

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Actual central authentication, durable ingestion, acknowledgements and catch-up under concurrent new business.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L132) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Cashier client disconnected from master
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** No independent financial finalization; preserve private drafts and unresolved evidence

**Local obligations (unchanged):**
- No independent financial finalization; preserve private drafts and unresolved evidence

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L133) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Master restarts after local commit but before response
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Original operation/result and actors recovered; outboxes remain durable

**Local obligations (unchanged):**
- Original operation/result and actors recovered; outboxes remain durable

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L134) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Planned handover interrupted before/after sealing
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Safe abort before seal or controlled continuation after; no two active writers

**Local obligations (unchanged):**
- Safe abort before seal or controlled continuation after; no two active writers

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L135) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Replaced old master returns
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Remains retired/isolated until controlled return; no automatic reclaiming of authority

**Local obligations (unchanged):**
- Remains retired/isolated until controlled return; no automatic reclaiming of authority

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L136) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Mobile master activation, lock/background, force-stop and reopening
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Notice/idle policy applied; actual availability reported; reopening recovers only its still-active role

**Local obligations (unchanged):**
- Notice/idle policy applied; actual availability reported; reopening recovers only its still-active role

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY, L6_PHYSICAL_UNVERIFIED_UNTIL_EXECUTED.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L137) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Software upgrade with pending payment/kitchen/return work
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Compatible recovery/migration; stable operation IDs and no lost financial/stock evidence

**Local obligations (unchanged):**
- Compatible recovery/migration; stable operation IDs and no lost financial/stock evidence

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L138) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Restore branch from supported central recovery data or optional standby
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Verify coherent checkpoint, accounts/policies/orders/locks/custody/entitlements; disclose gaps; no stale bearer-token resurrection

**Local obligations (unchanged):**
- Restore a complete verified independent local backup/optional standby into a non-authoritative replacement; verify accounts/policies/orders/locks/custody/entitlements and all other required recovery state, then use delegated activation and fresh sessions. Reject bad copies, disclose missing tail, and never resurrect old bearer tokens or clone device keys. Keep the no-independent-copy exception visible.

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Production central recovery-package creation/download/key-recovery and full restoration from that actual central source. A synthetic qualification package is not evidence of the real central recovery path.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L139) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## REL-10

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N6, N7.

**Fixture:** F-BASE, F-AUTH, F-RECOVERY, F-PAY, F-STOCK, F-CASH, F-PURCHASE. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Disk full or power interruption during atomic command
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** No false success; committed result recoverable or no effects; external physical payment evidence retained for reconciliation

**Local obligations (unchanged):**
- No false success; committed result recoverable or no effects; external physical payment evidence retained for reconciliation

**Required verification needs (unchanged):**
- Use actual installed branch runtime and protected same-device/P2pKit adapters with real production storage, process termination, reconnect and supported recovery; inspect durable results, authority, complete state and visible gaps.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L140) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CFG-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-UI. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Company head sets both amount and percentage discount ceilings
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Enforce both ceilings on master command and approval; changing the UI/request cannot bypass either

**Local obligations (unchanged):**
- Activate a policy containing both amount and percentage discount ceilings. Real local commands and inline approvals enforce both, including direct edited requests and boundary amounts; reject excess without changing the order or cash/stock.

**Required verification needs (unchanged):**
- Use actual policy/approval/report/alert screens and every registered protected local command/read/export/subscription surface; test direct bypass attempts and inspect no side effects and no sensitive-data leakage.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Central company-head authentication, authorized policy authoring/publication and distribution.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L146) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CFG-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-UI. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. An employee changes amount/lines after a manager approved the earlier document version
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject stale approval and request a valid grant; original cashier/approver identities preserved

**Local obligations (unchanged):**
- Reject stale approval and request a valid grant; original cashier/approver identities preserved

**Required verification needs (unchanged):**
- Use actual policy/approval/report/alert screens and every registered protected local command/read/export/subscription surface; test direct bypass attempts and inspect no side effects and no sensitive-data leakage.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L147) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CFG-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-UI. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Branch administrator tries to exceed delegated limits or grant themselves company-wide reports
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject escalation; explicit deny and company ceiling win; audit the attempt

**Local obligations (unchanged):**
- A delegated local branch administrator cannot exceed company limits, override explicit denies, mint self-elevating grants or view/export wider reports. Local attempts are denied and audited with no grant or report-data leakage.

**Required verification needs (unchanged):**
- Use actual policy/approval/report/alert screens and every registered protected local command/read/export/subscription surface; test direct bypass attempts and inspect no side effects and no sensitive-data leakage.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Equivalent denial at actual central company-wide grant/report administration endpoints.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L148) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CFG-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-UI. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Named sole operator has company-authorized self-approval for one action
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Permit only that bounded action; no implicit self-approval for cash write-off, policies or master recovery

**Local obligations (unchanged):**
- Permit only that bounded action; no implicit self-approval for cash write-off, policies or master recovery

**Required verification needs (unchanged):**
- Use actual policy/approval/report/alert screens and every registered protected local command/read/export/subscription surface; test direct bypass attempts and inspect no side effects and no sensitive-data leakage.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L149) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CFG-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-UI. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Company revokes report/cost access while branch is offline, then reconnects
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Show central update pending; enforce current local policy until activation, then recheck sessions, queries, subscriptions and cached views

**Local obligations (unchanged):**
- While disconnected, current valid local policy remains active and displayed freshness does not claim a remote change is applied. On valid revocation-package activation, immediately recheck sessions, commands, reads, subscriptions and cached cost/report fields; suppress stale callbacks.

**Required verification needs (unchanged):**
- Use actual policy/approval/report/alert screens and every registered protected local command/read/export/subscription surface; test direct bypass attempts and inspect no side effects and no sensitive-data leakage.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Real central revocation creation, pending-delivery status, authenticated transport and receipt of the master application acknowledgement.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L150) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CFG-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-UI. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Cashier or inventory user requests hidden margin fields/export by direct ID or alternate endpoint
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Server filters/denies according to company-head grants; viewing quantities does not grant costs/export

**Local obligations (unchanged):**
- Through actual local report/query/export/direct-ID and alternate registered paths, quantity permission never exposes costs, margin or export. Redact before serialization, scope related IDs, and clear sensitive cached views after revocation.

**Required verification needs (unchanged):**
- Use actual policy/approval/report/alert screens and every registered protected local command/read/export/subscription surface; test direct bypass attempts and inspect no side effects and no sensitive-data leakage.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Equivalent company-head grants and field/export filtering on the actual central report/API surfaces.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L151) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CFG-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-UI. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. A low-stock/payment alert is retried, opened by a removed recipient or acknowledged
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Deduplicate delivery; recheck access; acknowledgement does not create stock, resolve payment or expose sensitive lock-screen totals

**Local obligations (unchanged):**
- Deduplicate delivery; recheck access; acknowledgement does not create stock, resolve payment or expose sensitive lock-screen totals

**Required verification needs (unchanged):**
- Use actual policy/approval/report/alert screens and every registered protected local command/read/export/subscription surface; test direct bypass attempts and inspect no side effects and no sensitive-data leakage.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L152) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CFG-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-UI. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Optional numeric approval limit was never configured
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Do not interpret omission as unlimited authority; show setup/action resolution without blocking unrelated routine checkout

**Local obligations (unchanged):**
- Do not interpret omission as unlimited authority; show setup/action resolution without blocking unrelated routine checkout

**Required verification needs (unchanged):**
- Use actual policy/approval/report/alert screens and every registered protected local command/read/export/subscription surface; test direct bypass attempts and inspect no side effects and no sensitive-data leakage.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L153) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PRC-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-RETURN, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Variant, category, channel, quantity and dated price rules overlap
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Stable documented precedence and preview; exact same result across retry/devices; protected order snapshot retained

**Local obligations (unchanged):**
- Stable documented precedence and preview; exact same result across retry/devices; protected order snapshot retained

**Required verification needs (unchanged):**
- Use real pricing/promotion preview and cashier/return paths; independently calculate eligible unit allocations, stacking, locked totals and source cost-versus-price separation.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L159) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PRC-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-RETURN, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Two automatic offers and a manual order discount target the same units
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Default exclusive group selects the eligible best reduction; only explicitly permitted stacking and limits apply

**Local obligations (unchanged):**
- Default exclusive group selects the eligible best reduction; only explicitly permitted stacking and limits apply

**Required verification needs (unchanged):**
- Use real pricing/promotion preview and cashier/return paths; independently calculate eligible unit allocations, stacking, locked totals and source cost-versus-price separation.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L160) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PRC-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-RETURN, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Two equal-value items cost 100 together; one is returned
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Original paid allocations are 50 each; one refund of 50, bounded remaining entitlement and no repricing the retained unit

**Local obligations (unchanged):**
- Original paid allocations are 50 each; one refund of 50, bounded remaining entitlement and no repricing the retained unit

**Required verification needs (unchanged):**
- Use real pricing/promotion preview and cashier/return paths; independently calculate eligible unit allocations, stacking, locked totals and source cost-versus-price separation.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L161) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PRC-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-RETURN, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Promotion requires unavailable component/quantity or basket changes during collection
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject insufficient stock/revalidate before lock; never silently change the locked payable or award nonexistent units

**Local obligations (unchanged):**
- Reject insufficient stock/revalidate before lock; never silently change the locked payable or award nonexistent units

**Required verification needs (unchanged):**
- Use real pricing/promotion preview and cashier/return paths; independently calculate eligible unit allocations, stacking, locked totals and source cost-versus-price separation.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L162) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PRC-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-RETURN, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Company requests a globally single-use campaign across disconnected branches
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject unsupported configuration or require explicit allocated entitlements/coordination; ordinary branch code does not promise global uniqueness

**Local obligations (unchanged):**
- Reject unsupported configuration or require explicit allocated entitlements/coordination; ordinary branch code does not promise global uniqueness

**Required verification needs (unchanged):**
- Use real pricing/promotion preview and cashier/return paths; independently calculate eligible unit allocations, stacking, locked totals and source cost-versus-price separation.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L163) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PRC-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PRICE, F-RETURN, F-STOCK. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Supplier cost changes while a price campaign is active
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Update purchase valuation only; selling price requires its authorized company release and promotion context

**Local obligations (unchanged):**
- Update purchase valuation only; selling price requires its authorized company release and promotion context

**Required verification needs (unchanged):**
- Use real pricing/promotion preview and cashier/return paths; independently calculate eligible unit allocations, stacking, locked totals and source cost-versus-price separation.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L164) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-STOCK, F-KITCHEN. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Create size/color variants with only a subset of valid combinations
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Create only selected SKUs; correct independent stock/barcodes and inherited defaults; import retry produces no duplicates

**Local obligations (unchanged):**
- Native local draft/import preview creates only selected size/color combinations, stable independent SKU/barcode identities and inherited defaults; repeat/resume import cannot duplicate local staging or activated records. Exercise any delegated/preallocated local creation path against real persistence. Global publication must remain pending rather than masquerading as branch authority.

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Actual central company catalog publication and company-wide SKU/barcode uniqueness across branches.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L170) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-STOCK, F-KITCHEN. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Receive two 12-unit cartons, later change future pack definition to 10
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Original receipt remains 24 base units; later document uses its own approved conversion

**Local obligations (unchanged):**
- Original receipt remains 24 base units; later document uses its own approved conversion

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L171) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N8.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-STOCK, F-KITCHEN. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Weigh an item, scan a malformed/ambiguous weighted code or use an invalid unit conversion
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Exact supported quantity; reject ambiguity/incompatible units; embedded price cannot silently bypass current company price

**Local obligations (unchanged):**
- Exact supported quantity; reject ambiguity/incompatible units; embedded price cannot silently bypass current company price

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY, L6_PHYSICAL_UNVERIFIED_UNTIL_EXECUTED.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L172) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-STOCK, F-KITCHEN. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Receive/return serialized product with duplicate serial or fractional quantity
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject invalid identity/quantity; valid return matches original sold serial and remaining entitlement

**Local obligations (unchanged):**
- Reject invalid identity/quantity; valid return matches original sold serial and remaining entitlement

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L173) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-STOCK, F-KITCHEN. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. An expiry-tracked variant exists in available and quarantined/expired lots
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Select only valid released quantities; later inspection release occurs once and retains provenance

**Local obligations (unchanged):**
- Select only valid released quantities; later inspection release occurs once and retains provenance

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L174) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-STOCK, F-KITCHEN. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Prepare 10 finished units from a versioned recipe, then sell 3
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Ingredient issue occurs on production kitchen send; actual finished output posted once; sale reduces finished stock to 7 without another ingredient issue

**Local obligations (unchanged):**
- Ingredient issue occurs on production kitchen send; actual finished output posted once; sale reduces finished stock to 7 without another ingredient issue

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L175) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-STOCK, F-KITCHEN. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Sell virtual kit with mixed kitchen/retail/service components versus previously assembled stocked kit
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Virtual components preserve their own issue timing; parent payment never reissues them; assembled kit uses stocked output; no double deduction

**Local obligations (unchanged):**
- Virtual components preserve their own issue timing; parent payment never reissues them; assembled kit uses stocked output; no double deduction

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L176) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-STOCK, F-KITCHEN. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Configure recursive kit, fractional serial or incompatible ingredient dimensions
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject invalid capability combination before activation; existing valid catalog remains usable

**Local obligations (unchanged):**
- Reject invalid capability combination before activation; existing valid catalog remains usable

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L177) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-STOCK, F-KITCHEN. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Change a tracked physical SKU to non-stock after selling it out
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Block casual conversion; require controlled version/migration/new SKU with preserved history, not a zero-stock bypass

**Local obligations (unchanged):**
- Block casual conversion; require controlled version/migration/new SKU with preserved history, not a zero-stock bypass

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L178) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## CAT-10

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-CATALOG, F-RETURN, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Record a refundable container deposit, return once, then retry from another client
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Separate container/liability and actual cash effects; receipt entitlement consumed once; no spendable customer balance

**Local obligations (unchanged):**
- Separate container/liability and actual cash effects; receipt entitlement consumed once; no spendable customer balance

**Required verification needs (unchanged):**
- Use real catalog/import/variant/unit/recipe/kit/production/deposit interfaces and production persistence; distinguish unpublished global draft from authorized activated local capability; inspect identities and historical quantities.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L179) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Quick-receive without PO, with invoice and cash payment on one screen
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Separate linked receipt, invoice and actual pay-out; stock/cash/debt each change exactly once

**Local obligations (unchanged):**
- Separate linked receipt, invoice and actual pay-out; stock/cash/debt each change exactly once

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L185) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Supplier invoice arrives before goods
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Save actual invoice/unmatched status; no sellable stock increase and no fictional physical receipt

**Local obligations (unchanged):**
- Save actual invoice/unmatched status; no sellable stock increase and no fictional physical receipt

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L186) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Receive 24 units: accept 22 and hold 2 damaged
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Custody 24, sellable 22, rejected-held 2; invoice for 24 exposes mismatch; two units need fulfillment/claim resolution with no double replacement allowance; correct ownership/value treatment

**Local obligations (unchanged):**
- Custody 24, sellable 22, rejected-held 2; invoice for 24 exposes mismatch; two units need fulfillment/claim resolution with no double replacement allowance; correct ownership/value treatment

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L187) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Two clients receive the last PO allocation concurrently or retry after lost reply
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** At most remaining approved quantity posted; original operation recovered; no duplicate stock or billed allocation

**Local obligations (unchanged):**
- At most remaining approved quantity posted; original operation recovered; no duplicate stock or billed allocation

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L188) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Partial delivery followed by excess goods with zero allowed tolerance
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Keep outstanding quantity; record excess physical custody in non-sellable hold until authorized acceptance/return; no hidden PO expansion

**Local obligations (unchanged):**
- Keep outstanding quantity; record excess physical custody in non-sellable hold until authorized acceptance/return; no hidden PO expansion

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L189) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Missing required inspection/lot/serial or wrong SKU supplied
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Do not release sellable stock; preserve held quantity and explicit inspection/substitution resolution

**Local obligations (unchanged):**
- Do not release sellable stock; preserve held quantity and explicit inspection/substitution resolution

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L190) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Invoice quantity/price differs from PO and accepted receipts
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Retain supplier claim; classify discrepancy, enforce configured approval and allow only authorized uncontested settlement

**Local obligations (unchanged):**
- Retain supplier claim; classify discrepancy, enforce configured approval and allow only authorized uncontested settlement

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L191) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Invoice 1,000; prior allocated advance 200; pay cash 500; later credit 100
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Initially due 300, then 200; today's physical pay-out 500 only; no duplicate advance, cash, stock or expense

**Local obligations (unchanged):**
- Initially due 300, then 200; today's physical pay-out 500 only; no duplicate advance, cash, stock or expense

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L192) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-09

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Same supplier invoice entered twice locally and independently in disconnected branches
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Local duplicate guarded; cross-branch conflict detected on sync and routed to one settlement authority; no false claim of prior global uniqueness or auto-deletion of receipts

**Local obligations (unchanged):**
- On each provisioned branch, normalized supplier-reference duplicate entry and repeated operation IDs yield only one local invoice/effect. Independent offline branches retain their actual receipts and durable conflict-detection evidence without claiming prior global uniqueness or silently deleting either receipt.

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Detect the cross-branch conflict through real central synchronization and route it to one actual designated settlement authority with controlled resolution.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L193) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-10

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Company allocates a shared PO to branches, then an offline branch tries another branch's allowance
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Enforce its own remaining allocation; no unallocated shared offline budget/quantity spend

**Local obligations (unchanged):**
- Using authenticated previously provisioned fixed branch PO allocations, each local master consumes only its own remaining allowance. Wrong-branch, excessive and concurrent requests reject atomically; missing shared central approval stays pending and routine sales continue.

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Actual company allocation authoring/reallocation, release acknowledgement and distribution across branches.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L194) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-11

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Supplier return includes units already reserved/sold or returns only rejected supplier-owned goods
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Reject unavailable quantities; preserve correct custody/value basis and separate supplier claim; no negative purchased stock

**Local obligations (unchanged):**
- Reject unavailable quantities; preserve correct custody/value basis and separate supplier claim; no negative purchased stock

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L195) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-12

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Supplier credit arrives without physical return, or replacement arrives after dispatch
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Credit changes financial claim only; actual replacement creates one inspected receipt with explicit free/charged terms; no doubled stock or payable

**Local obligations (unchanged):**
- Credit changes financial claim only; actual replacement creates one inspected receipt with explicit free/charged terms; no doubled stock or payable

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L196) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-13

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Existing 10 units at cost 20; receive 10 at cost 30
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Quantity 20, value 500, average 25; old sales/issue cost remains immutable

**Local obligations (unchanged):**
- Quantity 20, value 500, average 25; old sales/issue cost remains immutable

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L197) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-14

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Goods A 100, goods B 100, unrelated service 100 and eligible freight 30
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Allocate inventory freight 15 each to A/B, none to unrelated service; payable source and cost allocation not counted twice

**Local obligations (unchanged):**
- Allocate inventory freight 15 each to A/B, none to unrelated service; payable source and cost allocation not counted twice

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L198) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-15

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N7.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Late acquisition-cost change after part of receipt is consumed
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Explicit exact adjustment for remaining inventory and consumed-cost variance; unchanged quantities and immutable original sale/closing snapshots

**Local obligations (unchanged):**
- Explicit exact adjustment for remaining inventory and consumed-cost variance; unchanged quantities and immutable original sale/closing snapshots

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L199) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## PUR-16

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N4, N5, N6.

**Fixture:** F-BASE, F-AUTH, F-PURCHASE, F-STOCK, F-CASH, F-RECOVERY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Power loss during receipt/settlement, or restoring a branch with open purchasing disputes
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Recover operation/result, quality custody, allocations and obligations; do not replay a physical payment or silently approve a disputed invoice

**Local obligations (unchanged):**
- Recover operation/result, quality custody, allocations and obligations; do not replay a physical payment or silently approve a disputed invoice

**Required verification needs (unchanged):**
- Use actual supplier/PO/receiving/inspection/invoice/settlement/claim/cost screens and production stock/custody/payable/cash services; reconcile ordered, held, accepted, billed and actually paid amounts separately.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L200) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## UX-01

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N7, N8.

**Fixture:** F-BASE, F-AUTH, F-SETUP, F-UI, F-RECOVERY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Setup interrupted after catalog/stock import and resumed
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Continue saved wizard state; stable import/opening operation IDs prevent duplicate SKU, stock or supplier balances

**Local obligations (unchanged):**
- Interrupt the real local wizard after catalog/opening import; resume the saved step and original import/opening IDs without duplicate SKU, stock, valuation or opening supplier balance. Validate dry-run errors and rejected rows without partial unintended posting.

**Required verification needs (unchanged):**
- Use the actual native application and production local persistence/services rather than a demo screen; inspect UI state, actors, operation IDs and durable business outcomes through interruption and restart.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Initial central company/branch establishment and global catalog publication steps of the full wizard; local continuation does not claim those services were exercised.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L206) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## UX-02

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N7, N8.

**Fixture:** F-BASE, F-AUTH, F-SETUP, F-UI, F-RECOVERY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Opening inventory/valuation and drawer float are entered
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Correct opening ledgers with sales/revenue zero; no fictional purchases or duplicate cash receipt

**Local obligations (unchanged):**
- Correct opening ledgers with sales/revenue zero; no fictional purchases or duplicate cash receipt

**Required verification needs (unchanged):**
- Use the actual native application and production local persistence/services rather than a demo screen; inspect UI state, actors, operation IDs and durable business outcomes through interruption and restart.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L207) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## UX-03

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N7, N8.

**Fixture:** F-BASE, F-AUTH, F-SETUP, F-UI, F-RECOVERY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. A training sale/return/close is exercised, then branch goes live
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Isolated training data never enters live ledger/synchronization/fiscal submission; live setup is auditable

**Local obligations (unchanged):**
- Exercise training sale, return and close in a separate database and identity/transport/numbering namespace. Go-live is auditable and excludes all training stock/money/documents from live local ledgers and live sync/fiscal jobs.

**Required verification needs (unchanged):**
- Use the actual native application and production local persistence/services rather than a demo screen; inspect UI state, actors, operation IDs and durable business outcomes through interruption and restart.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** HELD — Production central live-ingestion and fiscal endpoints must also reject training identities/events when that phase is authorized.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L208) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## UX-04

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N7, N8.

**Fixture:** F-BASE, F-AUTH, F-SETUP, F-UI, F-RECOVERY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Setup a single mobile master with no standby; later lose central access
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Apply notice/idle contract and provision local users/recovery; healthy provisioned branch continues without recurring wizard/cloud lease

**Local obligations (unchanged):**
- With a clearly identified already-provisioned synthetic branch fixture, complete actual local credential activation, account lifecycle, client admission, recovery-access checks and mobile notice/idle policy. No standby is required, and subsequent WAN loss/restart does not trigger a recurring wizard or cloud lease. This does not establish first-time trusted central registration.

**Required verification needs (unchanged):**
- Use the actual native application and production local persistence/services rather than a demo screen; inspect UI state, actors, operation IDs and durable business outcomes through interruption and restart.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY, L6_PHYSICAL_UNVERIFIED_UNTIL_EXECUTED.

**Central portion:** HELD — Actual initial central branch/device enrollment and verification of the supported central recovery route. An authenticated offline first-install product is not assumed or silently required by the original baseline.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L209) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## UX-05

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N7, N8.

**Fixture:** F-BASE, F-AUTH, F-SETUP, F-UI, F-RECOVERY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Use Arabic RTL and English with touch, keyboard and scanner during rapid item entry
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Correct labels/layout/price interpretation and focus; shortcuts preserve identical quantity, authorization and confirmation rules

**Local obligations (unchanged):**
- Correct labels/layout/price interpretation and focus; shortcuts preserve identical quantity, authorization and confirmation rules

**Required verification needs (unchanged):**
- Use the actual native application and production local persistence/services rather than a demo screen; inspect UI state, actors, operation IDs and durable business outcomes through interruption and restart.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY, L6_PHYSICAL_UNVERIFIED_UNTIL_EXECUTED.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L210) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## UX-06

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N7, N8.

**Fixture:** F-BASE, F-AUTH, F-SETUP, F-UI, F-RECOVERY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. External payment/kitchen response is unknown and user taps Back, switches account or retries
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Preserve original actor/operation and recovery status; no automatic new charge, stock issue or preparation ticket

**Local obligations (unchanged):**
- Preserve original actor/operation and recovery status; no automatic new charge, stock issue or preparation ticket

**Required verification needs (unchanged):**
- Use the actual native application and production local persistence/services rather than a demo screen; inspect UI state, actors, operation IDs and durable business outcomes through interruption and restart.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L211) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## UX-07

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N7, N8.

**Fixture:** F-BASE, F-AUTH, F-SETUP, F-UI, F-RECOVERY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Manager approves within cashier flow, then cashier logs out on master device
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Cashier remains collector for that operation; logout clears private UI but master backend continues

**Local obligations (unchanged):**
- Cashier remains collector for that operation; logout clears private UI but master backend continues

**Required verification needs (unchanged):**
- Use the actual native application and production local persistence/services rather than a demo screen; inspect UI state, actors, operation IDs and durable business outcomes through interruption and restart.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY.

**Central portion:** None deferred for this scenario.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L212) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).

## UX-08

**Environment:** actual local application/master and durable storage; ALL_MASTER, ALL_CLIENT, CMP_LOCAL_ADMIN_OR_SCOPED_OFFLINE_ADMIN_UI. Network/failure profiles: BASE-OFFLINE, N3, N4, N5, N7, N8.

**Fixture:** F-BASE, F-AUTH, F-SETUP, F-UI, F-RECOVERY. Set up a fresh isolated fixture as defined in [ENVIRONMENTS.md](ENVIRONMENTS.md).

1. Establish the listed fixture and required actor/device roles.
2. Printer fails or a company report includes an offline branch
3. Inspect the resulting application state and independent persisted observations required below.
4. Record actual results separately for each required variant; do not infer a pass from the command response alone.

**Expected result (unchanged):** Distinguish committed local sale from print failure and stale central totals; identified reprint does not create a second sale

**Local obligations (unchanged):**
- A committed sale survives printer failure/unknown print outcome; the real print job and marked reprint reuse the original document without another sale. Native report views clearly label any cached/provisioned company data as stale/incomplete and do not invent offline-branch totals.

**Required verification needs (unchanged):**
- Use the actual native application and production local persistence/services rather than a demo screen; inspect UI state, actors, operation IDs and durable business outcomes through interruption and restart.
- Rejected business operations must leave domain, cash, stock, entitlement and downstream jobs unchanged, except explicit rejection/security audit; existing external physical evidence remains unresolved rather than erased.

**Variants:** valid_transition, wrong_actor_scope_direct_request, boundary_input, stale_version_policy_or_approval, duplicate_same_operation_same_payload, same_operation_changed_payload_or_actor, concurrent_delivery, failure_before_commit, failure_after_commit_before_response, process_restart_supported_restore, back_logout_account_switch_background.

**Network applicability (unchanged):** BASE-OFFLINE retains local portions of N0 plus N1/N2; no real central service is started in this phase. Required central-positive profile variants remain deferred, never passed. N4/N5 apply to every financial mutator.

**Required layers:** L1_INDEPENDENT_EXPECTATIONS, L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_BUSINESS_E2E, L5_FAULT_SECURITY, L6_PHYSICAL_UNVERIFIED_UNTIL_EXECUTED.

**Central portion:** HELD — Actual central consolidated company report computation and per-offline-branch freshness over the real synchronization service.

[Original requirement](../original-handoff/POS_ACCEPTANCE_SCENARIOS.md#L213) · Result: **NOT_RUN**; record actual build/environment/evidence in [the result template](RESULT-TEMPLATE.csv).
