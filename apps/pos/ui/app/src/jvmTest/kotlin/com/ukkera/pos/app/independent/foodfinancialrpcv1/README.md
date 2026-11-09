# FOOD financial RPC v1 — shared UI supporting tests

Specification: `qa/pos/designs/food-financial-rpc-v1`, frozen **1.0.0**.
Status: **11 supporting methods passed**; [results and limits](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/food-financial-rpc-20261106/README.md). No complete FFRC case or rendered/device behavior
is qualified by these files.

Frozen bytes are verified against the existing freeze before loading MIXED.
Logical actors/resources are deterministic synthetic UUIDs. Payable remains
**57,013** minor EGP units. Source revisions 41/7/2/43 and the bounded original
body digest are explicit controlled presentation DATA, not genuine source custody.
The Apply form's read-only recording/order/collection/amount/digest tuple is kept
separate from its canonical ApplyOriginal recording selector.

## Assertions and layer limits

| Frozen case | Supporting UI observations |
| --- | --- |
| FFRC-001 | FOOD_CASH_FINANCIAL_ONLY is explicit, ORDERS-only and does not infer an actor-journal action from setup credential disposition. A financial continuation stays inert in FOOD_CHECKS_ONLY with its original recovery ID/body retained. No actual setup attachment is performed. |
| FFRC-001 / FFRC-016 | Closed route/query/action predicates and controller entry points exclude editor, kitchen, handoff, claim, refund and physical collection work. Selector acceptance is not a protected read grant. |
| FFRC-012 / FFRC-023 | Apply review fields are read-only; each mismatched source tuple prevents use of the form. Form1 input values stay exact at the presentation boundary. Genuine original-source qualification is not supplied. |
| FFRC-023 | Arabic/English share the same controller gate: confirmation before Stage, no Submit during Stage, deterministic held-Stage Back/duplicate denial, uncertain Stage preserves the first identity. |
| FFRC-025 | Lost Submit and presentation recreation retain the same frozen original with no automatic Review/Stage/Submit/ACK. After explicit current attachment review, repeated Lookup performs no fresh read, second Submit or replacement Stage. |
| FFRC-024 | Wrong operation/actor/original/code/reference and missing typed results cannot complete or ACK. A valid typed result still requires explicit ACK and its successful reply; duplicate ACK is suppressed while held. |
| FFRC-013 / FFRC-026 | Retirement preserves terminal knowledge without its cached private details; unavailable source recovery cannot redisclose or ACK them. Unavailable/incompatible pending is not a usable empty list. No Master restart is performed. |

The production shared `LocalWorkflowController`, presentation predicates and
navigation policy are exercised against a **controlled** `LocalWorkflowGateway`
and a synthetic UI session. Port replies are not durable-storage, current policy,
authenticated admission, native CASH or RPC evidence. Recreation means disposing
and reattaching presentation in memory, not an OS process restart. Language
variants are controller observations, not rendered screenshots, IME, accessibility
or platform qualification. No physical payment controller is activated.

Production encrypted Client-journal persistence and ordered durable-before-ACK
assertions are in the separate app-gateway supporting tests; this module does not
depend on app-gateway. Full business cuts, genuine retained originals, current
Master rights, admitted RPC, transport Receipt and physical close/reopen remain
their own verification work.

Selection from the independent POS build:

```text
./gradlew :ui:app:jvmTest --tests 'com.ukkera.pos.app.independent.foodfinancialrpcv1.*'
```

See the linked result record for source-specific compilation and execution outcomes.
