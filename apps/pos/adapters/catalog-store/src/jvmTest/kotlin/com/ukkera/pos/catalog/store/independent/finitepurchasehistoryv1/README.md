# Finite purchase approval history — supporting planner bindings

Frozen expectations: `qa/pos/designs/finite-purchase-approval-history-v1/`,
version **1.0.0**. **Twelve JVM supporting methods passed** on 5 November 2026,
with no failures, errors or skips; see the
[source-specific results](../../../../../../../../../../../../../../qa/pos/traceability/finite-purchase-history-20261105/README.md).
Nine methods call the
actual captured-match slice/effects and financial requirement/due/settlement
helpers with immutable synthetic inputs, not a replacement history verifier.

| Frozen obligation | Supporting assertion |
| --- | --- |
| FPH-008, C24/X22 | The original 2 CARTON × 12 EACH PO and 60,000-minor claim recognize 22 units/55,000 minor, with zero tax, 5,000 unmatched, 2 raw units unmatched, zero accepted capacity and no settlement eligibility. |
| FPH-008, M1 | Exactly the combined match/progress/billing/payable proposal; no receipt, stock, cash, capture or approval rewrite. Used match identity cannot produce a second proposal. |
| FPH-009, MV-CAPACITY | Selecting 23 against 22 accepted units refuses before any proposed change. |
| FPH-006, C24 | The actual requirement carries the whole 60,000 claim, not the 55,000 increment; this does not exercise current authority or monetary-limit decisions. |
| FPH-007, H-NON-EXPIRING | The same C24 planner recognition with a null PO deadline; this does not qualify an old or non-expiring native history. |
| FPH-001/002, evaluation only | Three methods exercise six frozen time rows through the real shared live/history evaluation predicate: T-START, T-MID and T-LAST are inside; T-BEFORE, T-EVAL-END and T-EVAL-LATE are outside. T-LAST is two milliseconds before the deadline. |
| FPH-008/011, C22/R22 | The literal 22-EACH claim against the unchanged CARTON12 PO recognizes 55,000 minor, leaves no quantity/amount unmatched and produces only the combined financial changes. Used-ID/competing-request checks are serial planner guards, not concurrent commit or restart tests. |
| FPH-008/011, C22/R12 then R22 | Distinct 12/10 requests add 30,000 then 25,000 minor using fresh synthetic heads and one payable identity; the first stage leaves 10 units/25,000 unmatched. |
| FPH-009, MV-PRICE/MV-TAX | The exact frozen 55,022-minor altered price/tax claims are retained and refused with no proposed changes. |

Positive revisions, source/provenance fields and receipt facts are inert carriers.
They do not establish protected storage heads, completed attachment authority,
native authentication, an original approval or current employee permission. The
receipt carrier includes only the selected released 22-unit line; no damaged-held
source is invented. No database, historical command or accepting native owner is
constructed. Proposal shape proves no durable commit/audit/outbox atomicity.
The raw capture remains untouched by the proposal; actual capture status/overlay,
source quotes, UI, RPC, persistence and fresh-connection physical reads are unbound.

## Explicit unbound obligations

- FPH-001–005: complete original approval and strict retained final-decision
  predicates, exact native bindings, missing/corrupt/old evidence, preparation,
  late completion and restart qualification. Scalar times cannot replace them.
- The original CARTON live approval cannot obtain qualified dimensional catalog
  conversions from a legacy SKU lacking unit definitions. The modern conversion
  path requires genuine authenticated native publications. Live evaluation and
  exact approval `mustCommitBefore` propagation are therefore unbound here beyond
  the shared evaluation predicate; no
  EACH PO, accepting authority or vacuous missing-SKU rejection substitutes for it.
- The C22 full/staged numeric planner slices above are bound without replacing the
  original PO or inventing a 22/12-CARTON decimal. Genuine capture/PO/receipt source
  qualification, protected heads, status overlay, full physical snapshots and
  original operation/result identity remain unbound. MV-HISTORY-UOM and SKU
  substitution have no exact supporting binding here.
- Retained older V2 literal-unit variance refusals have no exact frozen archived
  original input/result binding. Their source compatibility path is reviewed,
  not a native historical replay/result-preservation pass.
- Remaining FPH-006/007/009 and all FPH-010–013 actual authority, compatibility,
  source-unavailability, original-result recovery, concurrency, revocation,
  durability, new-receipt/payment denial and network/platform obligations remain
  unverified. The reused-ID planner guard is not FPH-011 lost-reply/restart recovery.

All frozen case, variant and platform outcomes remain **NOT RUN**. No native
history, Room integration, restart, transport or physical-device result is claimed.
