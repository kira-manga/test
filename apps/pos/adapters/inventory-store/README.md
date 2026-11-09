# Bounded inventory storage profiles

LOCAL ONLY. This module provides bounded inventory-cut readers and pure write
contributions over the [Room command kernel](../../core/database/README.md).
**There is no production inventory receive handler or complete checkout here.**
The accepting genesis/receive handlers exist only in database JVM tests, with
synthetic employee, runtime/custody and upstream purchase identities.

## Original receipt-v1 contract (unchanged)

- Two branch-keyed CAS rows: `inventory.cut` uses the existing inventory codec;
  `inventory.companion` retains the complete receipt/source/position/value/expiry
  crosswalk and the latest ordered posted effect batch. Older batches remain in
  the immutable command journal, not a duplicated all-history sidecar.
- Explicit fixture genesis writes domain revision0/storage generation1. Each
  actual receive advances both rows and the domain once. A reader never seeds
  missing state. The test factory privately records its own fresh-file ceremony,
  owns the complete handler list, and freezes both kinds and both claim namespaces.
  A normal reopen cannot register the initializer by observing absent rows.
- Reading validates both current rows and independent scope/key/schema/generation
  metadata, complete receipt/valuation/position correspondence, every source and
  upstream allocation claim owner, and one allowlisted retained producer. Its
  normalized request, original result, two exact after-writes, two Acquire claims
  (zero for genesis), and absence of requested jobs must match. The producer is
  the cut's last inventory operation, not necessarily the global journal head.
- Translation requires the planner's **exact source object**, not merely equal
  scope/revision or a detached equal snapshot. Posted effects remain valuation
  then movement; kernel sorting of aggregate/claim frames is not effect ordering.
- Supported state is owned, released receipt stock only. Reservations, issues,
  supplier custody, recall/expired safety markers, non-receipt facts and cost
  targets/adjustments are rejected, never filtered or silently discarded.
- A receipt source claim binds receipt ID to producing operation. A separate
  synthetic document/allocation claim binds the upstream identity to receipt ID.
  Neither claim is released. These references do **not** prove procurement.
- The additional32-receipt fixture ceiling does not guarantee32 arbitrary lots
  or pools fit. The kernel64KiB row/1MiB proposal bounds still apply; no truncation.
  This whole-cut profile is not a production capacity or index design.

The test receiver supplies every known synthetic dependency and an opt-in STOCK
denial barrier: any retained STOCK hold prevents new work even after exact-alias
readmission. Authorized original-result replay precedes this new-work restriction;
current identity revocation still denies replay.

## Limits that remain deliberate

Current SQL point reads reject invalid storage types/lengths before hydration;
hashes and retained row equality establish internal consistency, not authenticity.
A coherent older pair may still pass with newer journal entries and unlisted
claims left behind. The integration suite records that **expected limitation**;
it does not call it rollback detection or attempt a new mutation afterward.
Qualified genesis/import, complete frontier/runtime custody and protected writer
fencing remain necessary before any accepting production path.

Future cost support must retain adjustment-event identities separately from
cumulative cost-source-to-pool/receipt bindings and upstream natural allocation
claims, including cancelled/zero targets. Full expiry, reservations, sale/return
finalization, procurement authority, recovery aliases, durable money-attempt
capacity and production normalized storage are not supplied by this receipt slice.

## Retail-v1 observations and contributions

The separate `inventory.companion/retail-v1` profile supports bounded purchase
receipts, retail reservations/issues and collateral expiry. It keeps the same
two current row kinds and inventory cut codec; it does not upgrade or broaden
receipt-v1. It still installs **no production handlers**.

- `InventoryRetailRepository.observe` returns explicitly **unqualified facts**:
  the complete current pair, cumulative receipt and issue claim ownership, and
  its selected retained inventory producer. This is not approval of that whole
  producer, authorization, a current-frontier proof or permission to finalize.
- `requireFixtureProducer` / `readFixtureReceipts` are concrete, closed validators
  for only synthetic retail genesis and receive. They check canonical request,
  result, exactly two writes, exact claims and no requested jobs. Unknown or
  composite command/version pairs reject. Additional inventory-kind writes are
  not permitted even under another ID.
- The companion retains complete receipt/lot/expiry/upstream provenance, a
  minimal issue-ID-to-producing-operation index, previous pair references and
  only the latest canonical A1 posting **string**. Genesis has no posting batch.
  No reservation birth index or duplicate all-history store is introduced.
- Whole-cut validation partitions every physical source and folds receipt/issue
  quantities in source order per position, checking units, operation ordering
  and nonnegative prefixes. `receive MAX -> issue MAX -> receive MAX` is valid;
  separately summing all lifetime receipts into a Long is not required. A later
  receipt cannot hide an earlier lot overdraw. Exhausted positions and zero
  Consumed destinations remain present.
- `projectContribution` requires the actual live plan and identical observed
  source object, returning exactly two inventory CAS writes, new inventory claims
  and the **original** exclusive commit deadline. It does not create an Apply,
  result, complete-producer acceptance or whole-envelope capacity guarantee.
  Historical decoding never reconstructs a plan/proof or renews a deadline.
- Only the inventory current pair must equal the selected producer's after-writes.
  Later cash/sales rows may have advanced. Any future sales-aware composer must
  validate the complete retained composite using its historical payloads, then
  separately check current actor/custody/coverage/quote/revisions/allocations.
- Additional fixture ceilings are 32 receipts, 32 issues, 32 reservations,
  64 physical sources and 128 inventory operations. Request/result caps are
  32KiB/4KiB; the complete cut and escaped companion each remain at most64KiB.
  These counts are not jointly achievable capacity promises. No pruning occurs;
  the outer composer still owes the full1MiB framing/input/claims/jobs budget.

The real-Room retail fixture stages genuine synthetic genesis and batched receives,
reopens the file and validates cumulative claims and complete producers. It retains
the fresh-install gate, independently frozen write/claim namespaces and STOCK
denial barrier. Its unsupported-producer negative control is not a supported
composite setup shortcut. Coherent old-pair rollback still passes as a documented
limitation; protected genesis/frontier, procurement authority and durable
pre-money capacity remain separate gates. The staged covered-money finalizer is
not implemented by this profile.

## Verification

Common tests exercise real pure planner transitions and bounded/canonical codecs.
Database JVM integration tests exercise actual temporary SQLite genesis/receives,
reopen, claims, concurrent IDs/CAS, semantic and actual outer-COMMIT failures,
recovery restrictions, malformed pairs and deliberate producer negative controls.
Android-host tests are not physical-device Room tests; Apple main klib compilation
is not linking or execution. Consult the [verification report](../../VERIFICATION_REPORT.md)
for the exact executed source cut, rather than inferring a pass from authored code.

The24 October A2 target passed1,198 cases across ten host profiles, including
104/104 inventory-store and320/84 database cases, plus53 harness self-tests and
six executed Apple main-klib compilations. An independent read-only evidence
audit found no discrepancy. See the report for the subsequent full combined
cut; this target alone does not qualify the whole application or a later edit.
