# Local inventory entry and reference selection

27 October 2026. **Connected production source, not complete local acceptance or
an owner-ready installation.** Restaurants, cafés, clothing and supermarkets all
remain required. See [CURRENT-STATUS.md](CURRENT-STATUS.md) for runtime blockers.

## Counted first opening stock — 28 October

Inventory now includes **Opening**, connected to the existing protected approval,
receiving, stock and valuation services. Approval is a ceiling, not a posted count
or an available balance. Browse the real bounded approval pages, or use `basis:ID`
and `receipt:ID` for exact authorized lookups. Filtering preserves the source cursor.

Select an existing approved catalog SKU to review all of its location/lot/serial
allocations together. A successful quantity read must show no prior stock item;
the atomic master checks that condition again. Enter actual counts and exact owned
values, not the approval maxima. Opening posts inventory/value, not a purchase,
sale, revenue, supplier obligation or drawer float. Quarantine and pending serial
registration remain unavailable to sell. Posted original values and current
carrying values are separate, permission-checked reads.

The fresh form suggests an editable receipt ID. It is not a reservation or grant:
preparation checks the chosen exact target before staging, and the master checks
again at execution. Saved normalized and legacy opening requests retain their
original receipt ID, payload and operation; recovery never reroutes or rewrites
them. Editing the draft clears its opening confirmation. This corrects both the
former basis/receipt target mismatch and the random, read-only target dead end.

This editor remains bounded to **16 physical slices for one first-entry SKU**.
Larger allocation groups are not silently truncated or offered as partial first
openings. Larger/later opening, bulk/resumable import and explicit migrated
supplier obligations remain required work. Empty profile initialization is still
neither opening stock nor a migration/reset.

Ordinary stock restart history now reuses the same retained receiving, inspection,
count and transfer plans to check the actual native final deadline decision.
This is not a replacement stock producer or a relaxed native/history guard.

Source review is separate from behavioral verification. The independently
designed [22 opening cases](../../qa/pos/designs/opening-inventory-v1/README.md)
are [frozen](../../qa/pos/freezes/opening-inventory-v1/FREEZE.json), not accepted
by source review or compilation. See the current handoff for exact build results
and remaining native/Room/LAN/device/recovery verification.

## Historical source correction — 27 October, 20:47 UTC

Saved source `f57dcda` already added normalized same-branch warehouse transfers,
their real gateway form/result navigation and protected detail/cost reads. The
current checked3521bb4b… cut retains this implementation. Old statements below
about a legacy-only transfer path are superseded: company-owned released stock
uses the actual normalized streaming writer; original claims, lot/serial identity,
warehouse authority and paired value lineage are checked. Reserved/held or unsafe
stock is not freed to make a transfer succeed. Saved legacy requests keep their
original bytes. Supplier-held/quarantined normalized transfer and linked cost
corrections remain unsupported; no complete transfer/device acceptance is claimed.

## Entry points and data ownership

The Stock workspace has explicit **Quantities**, **Costs**, **Setup / profile**
and **Opening** selectors. `LocalWorkflowController` owns only ephemeral selection/editor state.
`LocalEndpointWorkflowGateway` uses the current protected session and actual
master read/command services. Neither a displayed profile nor an empty list grants
permission, proves an empty database, or supplies an opening balance.

- `LocalStockWorkflow` keeps quantity/position, count, surplus approval, recall,
  receiving and profile-selected transfer paths. Costs are not added to quantity responses.
- `LocalStockAdministrationWorkflow` connects the existing cost and normalized
  profile reads, and the existing positive-empty initialization command.
- `LocalOpeningStockWorkflow` connects approval browsing, counted first opening
  and original posted-value reads to those same production services.
- `LocalStockCommands.costs` is the real quantity-plus-cost-authorized read for
  either admitted storage profile. It returns company-owned pool quantity and
  carrying value, with item/pool/valuation revisions and a bounded cursor. Values
  are not sales prices, supplier-owned custody, available-to-sell stock or profit.
  No rounded floating-point average is invented; zero quantity has no average.
- Empty initialization uses `LocalStockRelationalCommandsV2.initializeFresh` and
  the existing registered streaming handler. The atomic owner scans **all current
  rows, claims and retained originals**, including mixed commerce history, before
  any activation write. Existing stock/history produces the retained
  `MIGRATION_REQUIRED` rejection. This is not data migration, import, opening
  quantities, supplier-debt opening or a reset. The original request/result bytes
  and stock schema are unchanged; only a typed result projection was added.

Initialization requires an explicit review/confirmation and current
`INVENTORY_ADJUST/PERFORM`. The adapter saves one immutable original operation
before submission; retry/lookup/acknowledgement use that original entry, without
reading a newer profile to rewrite it. A successful result means only that the
empty storage profile was established—not that stock was received or the POS
became operational. A failed or missing read is not permission to initialize.

## Paged reference selection

`LocalReferenceSource` is a closed SKU/supplier/warehouse selector, not an arbitrary
query/command or a grant. The shared picker now serves purchase approvals,
receiving allowances, sales-route configuration, legacy same-branch transfers and
retail order lines. It replaces the old first-25-only preloaded lists.

`LocalReferencePickerDialog` → `LocalWorkflowController` → gateway
`references()` → `LocalConfigurationProfile.QUERY` → actual master authorization
and persistence. Each page rechecks the same current interactive session; the
master separately checks catalog/purchasing read permission. Selection/preparation
does not dispatch a stock, purchase or sales mutation.

- Retain only one bounded page (25 source rows) and the selected identities/labels.
  Browse Next/First or look up an exact ID. Text filtering is explicitly **this
  page only**, not a pretend global search. There is no hidden full-directory load.
- Validate ordered, unique source rows and the actual cursor before filtering out
  inactive records or the transfer's source warehouse. An empty visible page can
  still have a Next page; it does not terminate the directory or imply absence.
- Choose only a record returned to that dialog. Copy its exact kind/ID/revision;
  labels are presentation only and never enter the command as authority.
- New intent preparation re-reads the selected source identities/revisions. The
  production handler still owns current authorization and business validation at
  commit. Pages are separate current cuts, not a frozen cross-page snapshot.
- The retail editor retains its original catalog revision across page changes.
  It does not silently reprice or unlock a payment-locked order, broaden the
  existing base-unit/16-line editor, or erase variant/modifier selections.
- Closing a picker cancels only its read. Lock/logout/navigation invalidates late
  replies and clears private editor state, not the master service or durable
  pending operations. Optional selectors can be explicitly cleared.

English/Arabic labels, LTR technical IDs, minimum touch targets and keyboard-native
controls are present in source. This is not an Arabic/RTL or accessibility device
qualification claim.

## Explicit remaining boundaries

- Normalized company-owned/released same-branch transfers are source-connected;
  broader custody transfers, linked cost corrections and full original workflow/
  restart/failure acceptance remain required. See the current source correction.
- The initialization confirmation warns about that limitation. Existing data
  cannot be converted, cleared or quietly reopened through the fresh-only route.
- Full opening/import validation, transfer/cost-correction recovery, serial/lot/
  expiry journeys, purchasing/returns and full-size retail/food workflows remain.
  Paging a reference selector does not solve 256-line or 32×16 command/journal limits.
- Native company-publication trust, broader authentication/enrollment/restart
  histories, physical money authority, real LAN and complete backup/restore are
  still required. Current source connections are not a usable substitute for them.
- Focused independent picker/initialization coverage and complete execution remain
  missing. The new opening packet adds requirements-derived cases, not executed
  acceptance. Existing frozen commerce/scope cases remain authoritative; no
  altered expectations were added. The earlier picker/initialization checkpoint
  lacked separate source review; that limitation is not a passing review result.

Historical fingerprint `10ca821cba81df954264d443406e2405e294a043e31e3ff68f40a62ed38f25f9` (787 inputs) compiles
for gateway/UI JVM and Android/desktop and assembles the debug APK (147.708s).
338 unchanged inventory/store/UI regressions pass (68.645s); no failures/errors/skips.
These are regressions, not independent new-path or device acceptance.

Exact source-bound build/regression results and all original obligations are
recorded in the current handoff and verification/traceability packets. Historical
successes are not inherited by this change. Central work remains held.
