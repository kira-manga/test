# Food collections, obligations and claims

## Current unpaid checks — 6 November 2026

The shared version-3 check read includes ordinary queued, preparing, ready and
dine-in served work in an open, uncollected check's current unpaid amount. It
sums the saved portion item-net/tax postings, not current catalog prices or the
visible page. Genuine never-sent cancellations remain excluded; an eligible empty
check is known zero. Financial, cancellation/remedy and unsupported-money states
remain unavailable rather than becoming zero or a payment authorization.

The read closes complete required history and current stock relationships,
including off-page members and otherwise unavailable-money cuts. Master authority
still governs disclosure. The shared client selects the highest installed
supported version before one request; failure, denial or null never falls back.
V1/V2 behavior is preserved. Fixed local profile membership is not negotiated
peer-capability or LAN evidence.

[Contract](../../qa/pos/contracts/LOCAL-FOOD-CURRENT-PAYABLE-V3.md) ·
[Source review, automated results and remaining integration](../../qa/pos/traceability/food-current-payable-v3-20261106/README.md).
Complete native/RPC/restart qualification and broader restaurant/café workflows
remain tracked in [#28](https://github.com/ukkera/kmp-apps/issues/28),
[#59](https://github.com/ukkera/kmp-apps/issues/59) and Epic
[#6](https://github.com/ukkera/kmp-apps/issues/6). Device/owner acceptance is separate.

## Historical 28 October — financial-current history source integration

The actual installed FOOD owner now joins the same native census to financial
roots/chunks, exact claims and writers. Supported current states include authentic
CASH attempts, partial/complete coverage, paid sale/entitlement, SendReserved,
kitchen/PaidPickup and whole-unsent cancellation/refund. Complete unissued
reservation allocation/value membership and deterministic issue absence are
checked, along with closed owner/task/station membership. It does not replay
commands, fabricate historical readers or take a second stock snapshot.

The existing unsent and uncollected sent-state support remains. Restoration,
responsibility handoff, quality-claim, non-CASH and legacy profiles remain explicit
holds. Whole-unsent CASH refund source is connected, not full prepared/served
remedies, all tenders or completed cold-restart admission.

Nine reviewed production paths are integrated. Source `b9a94c08…`
passes gateway/Desktop/Android compile, 526 unchanged regressions and Android
assembly; **actual native/restart/capacity acceptance remains NOT_RUN**. No new
acceptance result is inferred; frozen designs stay unchanged.
[Source and results](../../qa/pos/traceability/supplier-food-financial-20261028/README.md).

---

## Historical 28 October — native-current sent kitchen continuity

The same installed FOOD history owner now closes actual uncollected queued,
preparing, ready, dine-in served, cancellation-pending and after-start-waste
states. It joins each portion to the native original/current issue, complete
reservation allocation/value membership, permanent task claims and two-way active
station membership. Cancellation-pending keeps consumption; after-start waste
retains cost and removes active station locators. No task or print job is treated
as proof of physical delivery.

The prior unsent/uncollected subset remains supported. Before-start confirmation/
restoration, financial, handoff and legacy current states remain held. Financial
restart continuation is staged separately, not completed. Existing payment/refund
command source is not evidence of a complete admitted cold restart.

Gateway/Desktop/Android compile and 526 unchanged regressions pass on source
`1ece954b…`; actual native restart, station delivery and capacity remain unverified.
[Source and results](../../qa/pos/traceability/sent-food-sdk-tools-20261028/README.md).

---

## Historical 28 October — FOOD service-detail source addition

The Check form now offers **Edit table/pickup reference and seat note** in English/Arabic, prefilled from the actual order. Master-side owner and expected-revision checks apply to both durable producer paths and exact native history. Channel remains immutable; this is an order-only label edit, not money/stock/preparation/kitchen-job mutation, table occupancy management or delivery support. Collection/ownership constraints remain intact.

Whole-unsent CASH refund recording/reporting from the previous checkpoint remains; prepared/served remedies and full restaurant/café scope are not completed. Combined compilation,526 historical regressions and signed Android packaging pass; native/LAN/device and independent complete workflow acceptance remain unverified. [Current evidence](../../qa/pos/traceability/returns-service-details-20261028/README.md). Earlier sections below retain historical boundaries.

---

**27 October 2026 — connected production source, not an operational/accepted POS.**

## Connected path

Food check → lock original quote → collection/reserved portions → actually covered
finalization → original receipt; whole-never-sent cancellation → refund obligation;
resolved work → close. Separate quality/fault claim intake accepts unknown asserted
order/receipt/quantity without requiring a receipt, current stock or current policy.
Arabic/English pages use actual production reads, not example balances.

| Action | Actual boundary |
| --- | --- |
| Begin collection | Exact order storage revision and explicitly supplied open cash-session domain revision; reserves never-sent ingredients, takes no money. |
| Finalize paid | Requires stored complete coverage; selects normal/zero-finalization permission from the original payable. No second collection or ingredient issue. |
| Send reserved | Selected 1–8 eligible portions; consumes existing reservations once. Unresolved money does not alone prohibit preparation. No physical print/start assertion. |
| Paid pickup | Exact fresh order/channel check, finalized coverage, ready portion; COUNTER/TAKEAWAY only. Dine-in Serve remains separate. |
| Cancel whole unsent | Releases unissued reservations, retains actual original-tender liability; no payout, no sent-food cancellation. |
| Close resolved | Production rules check resolved liabilities/claims/charges and cancelled-released or actually handed-over portions. Waste alone is not customer fulfillment. |
| Quality/fault claim | Preserves original customer assertions as unresolved intake; not delivery proof, approved remedy, waiver, restock or refund. |

Collection, receipt, refund, exact claim and own-claims pages reauthorize each read.
One page represents one master response, never merged financial/preparation cuts.
All returned amounts, components, tender allocations, attempts and claims are
rendered. Explicit bounded capacity failure replaces silent truncation (2,048
groups, 32 values/group, 96 links). These display limits do not fix the retained
64 KiB command/row/journal or full-order capacity problem.

## Code and durability

`adapters/app-gateway/.../LocalFoodFinancial{Forms,Workflow,Intent,Results}.kt`
contains forms, bounded projection, command binding and definite-result rendering.
`LocalWorkflowMapping`, `LocalFoodWorkflow` and shared food controller/screens
connect them to existing production handlers and durable client-intent recovery.
The actual order collection ID prevents editing/sending locked portions through
the ordinary preparation route even when optional financial detail is absent.

Fresh stock-dependent operations select the actual installed stock profile before
journaling. Claims use the stock-independent financial route. Retained retries
reuse exact command/profile/identity; no later profile read rebuilds them. Explicit
terminal ACK precedes navigation to the exact collection/receipt/refund/claim/check.
Storage generations never occupy domain-revision fields. Undisplayable details do
not turn an outer definite committed/rejected result into unknown physical money.

No Room schema, migration, financial wire or handler transition changed here.
New persisted `LocalAction` names are forward-only for old client binaries: an
older binary cannot decode these pending intents. Do not downgrade with outstanding
intents, discard a journal or reinterpret an action. Resolve/ACK using a compatible
client and qualify coordinated rollback/restore before permitting it.

## Verification and remaining work

The recorded 27 October source passed gateway/UI JVM, Android and desktop Kotlin
compilation and 153 unchanged sales/sales-store/UI JVM regressions, with no
failures/errors/skips. See [the source-specific result and requirement mapping](../../qa/pos/traceability/food-financial-20261027/README.md).
Existing KIT01/KIT15/RET12/OWN07 cases are anchors, not complete UI/recovery
coverage. These historical results do not qualify later changes.

Physical Start/Observe/Apply payment/refund variants remain unsupported and are
not exposed. Positive payments, refunds, fault remedies, broader cancellation,
full capacity, qualified runtime/journals/LAN, recovery and independent E2E/Room/
device/peripheral/accessibility acceptance remain required. No APK assembled for
this cut. Earlier two security and461 database-fixture failures remain open.
Full local POS is incomplete and NOT owner-ready. Central work remains held.
