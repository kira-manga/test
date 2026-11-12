# Counted cash closing and supervisor review

**27 October 2026 — connected production source, not full cash acceptance or an owner-ready POS.**

## Current native-history connection — 20:47 UTC

`LocalCashHistoryRead`, `LocalCashClosingHistory` and `LocalCashReceiptHistory`
now join the actual native BEFORE originals for begin/cancel, count/recount,
counted delivery, recipient receipt, counted submission, review and linked notes.
The same pure domain calculations compare complete snapshots; no employee
authorization or money movement is reconstructed. Empty food indexes use explicit
resource identities, not manufactured absence objects.

**Native admission still accepts only the founding actor.** Recipient receipt,
supervisor review and notes therefore cannot yet complete a genuine multi-employee
runtime history. Anti-self guards remain. Legacy combined-close, non-sale/safe-drop
and unsupported food histories remain separate work. Source3521bb4b… passed
compilation and601 unchanged regressions; no full cash/Room/native/LAN acceptance.
[Exact evidence and all original requirements](../../qa/pos/traceability/food-cash-runtime-20261027/README.md).

Authority: original `POS_OPERATIONAL_SPECIFICATION.md` §§4.1–4.3 and
`POS_ACCEPTANCE_SCENARIOS.md` CASH-04/05/08/09/10/11. Existing independent
`commerce-v1` COM-CASH-05-I/E separates count, transfer, receiving acknowledgement
and final submission. The production sequence now separates these too; no frozen
step, expected amount, bank integration or accounting policy was changed.

## Connected path

- Drawer controls distinguish current custody, an original own session by ID,
  closed-session review, an original transfer and the actor's own safe ledger.
  Review-only employees can enter Drawers without receiving CASH_READ permission.
- Begin Closing freezes the original ledger cut. Record Count stores an immutable
  actual count with its original operation, evidence, time and session revision.
  The initial closing presentation hides the expected total until count submission.
  A recount requires a reason and the preceding active count ID; it appends rather
  than overwriting. Cancelling before delivery preserves the old history, and a
  later closing cycle requires a new count.
- After a count, choose retain-all or actual safe delivery. Delivery binds the
  exact count, retained float, destination's actual revision, original transfer
  ID and separate evidence/time. It changes counted custody, not the frozen
  cash ledger. No safe, revision, count or recipient acknowledgement is guessed.
- Canonical intent review shows `actual count - retained float`, not expected
  cash. Original requirement: count2,360, retain500 means delivery1,860; the
  original2,350 cut and10 variance are not balanced away.
- Actual delivery blocks recount/cancellation and leaves both assignments held.
  The receiving custodian separately uses `ReceiveSafe`, binding the transfer,
  sender session and safe revisions. Receipt credits the safe once; it neither
  debits the sender again nor submits the close/releases its assignments.
- Submit Counted Close separately requires that exact count and received custody
  (or retain-all with no transfer), no blocking work and a variance reason when
  needed. The atomic handler retains the immutable report and releases assignments
  without another transfer, debit or credit. An incoming cashier independently
  counts retained float; the old report is not their opening record.
- A submitted session remains directly readable after its active assignment is
  released or a later shift opens. It is not replaced by the current cashier's
  session. The complete bounded close shows count evidence, original operation,
  cut, variance/reason, actual disposition, receipt evidence and review.
- Supervisor review is a **separate exact-ID read** under current CASH_REVIEW.
  It exposes only a submitted/reviewed close and its linked closing custody,
  never active private sessions, another safe's balance or the next shift.
  No enumerable cursor exposes unrelated session IDs. Self-review is refused;
  the existing revision-bound review command appends to the original report.
- Pending/retry uses the original journal ID/body/actor. Current hints bind the
  exact saved command target. Results validate action, original document and
  kind-specific **domain** revision; a non-sale movement ID is no longer treated
  as a cash-session ID. All returned cash revisions are shown or detail decoding
  fails explicitly—no truncation. A definite kernel outcome stays definite when
  its detail cannot be rendered. Document follow-up occurs only after durable ACK
  and performs its own current authorization, including review-only follow-up.
- Count history is an immutable predecessor chain plus a bounded latest/count-
  disposition index, not an indefinitely growing 64KiB list. Reads bind the
  actual session custodian and either own CASH_READ or separate, non-self,
  closed-only CASH_REVIEW. Each page rechecks the exact session revision and
  cursor. Failed/missing reads never become an empty successful history.

## Linked explanations after closing

Original operational specification §4.3 step7 requires later explanations to
remain linked to an immutable submitted close. The closed-session view now offers
a separate **Add linked close explanation** action to a currently authorized
`CASH_REVIEW` employee other than the original custodian. It works for submitted
or already reviewed closes. Adding a note does not perform the first review,
change the session revision or reopen the drawer.

- Each note retains its original close/session, author, evidence ID/time, master
  recorded time, reason and operation ID. Earlier notes are immutable records;
  a compare-and-set head and unique sequence claims order them without an
  indefinitely growing aggregate or history eviction.
- The command binds both the original session revision and latest note ID seen
  by the reviewer. A concurrent first review or note makes a fresh stale request
  refuse. Lost-response lookup/retry retains the exact original command identity;
  ACK is explicit and returns to the original closed-session review.
- Own `CASH_READ` and non-self closed-only `CASH_REVIEW` are separate projections.
  History is newest-first, five notes per page. Cursors bind the session and
  presentation role; each page is an authorized current read, not a claimed
  multi-page snapshot. Reload to see notes appended while browsing older pages.
- Absent or foreign caller-supplied cursors are unavailable, not proof of damaged
  storage. Genuine missing-row/claim mismatches or a broken stored chain still
  fail closed. The existing count-history cursor path receives the same distinction.
- The new command may write **only note/head aggregates and their claims**.
  Original counts, ledger cuts, close reports, first reviews, safe balances,
  assignment releases and later shifts are not monetary targets of this command.
  It records no amount, tender change, balancing entry, expense or employee debt.
- Cash explanation/review/recount/variance forms use the existing256-character
  reason limit. They no longer invite512 characters that the domain would reject.

**These notes do not implement monetary/tender corrections or PAY-10.** Original
§9 and COM-PAY-10-I/E still require the distinct linked historical reclassification,
unaltered original close, no new physical receipt and separately reconciled report
categories. A free-text explanation is not a substitute for those financial effects.

## Source navigation

Paths relative to `apps/pos`:

| Component | Source |
| --- | --- |
| Money/custody rules and separate counted transitions | `domain/sales/.../CashSessions.kt`, `CashCountedClosing.kt` |
| Atomic count/delivery/submission and receipt/review | `adapters/sales-store/.../LocalCashCountHandler.kt`, `LocalCashHandler.kt` |
| Immutable count records/index and authorized paging | `adapters/sales-store/.../LocalCashCountStorage.kt`, `LocalCashCountReads.kt` |
| Closed-only owner/reviewer projection | `adapters/sales-store/.../LocalCashCloseReads.kt` |
| Append-only explanations and bounded authorized reads | `adapters/sales-store/.../LocalCashCloseNotes.kt` |
| Explanation form, canonical intent/result and history | `adapters/app-gateway/.../LocalCashCloseNotesWorkflow.kt` |
| Registry/service read registration | `adapters/sales-store/.../LocalSalesServices.kt` |
| Selection, forms and ordinary cash operations | `adapters/app-gateway/.../LocalCashWorkflow.kt`, `LocalCashCountWorkflow.kt`, `LocalCashClosingWorkflow.kt` |
| Canonical close review and original result binding | `adapters/app-gateway/.../LocalCashResults.kt` |
| Entry-owned UI/read selection/ACK | `ui/app/.../LocalCashUiContracts.kt`, `LocalWorkflowController.kt`, `ui/LocalCashControls.kt` |

## Compatibility

Old cash command variants/bytes, domain snapshots, read identities and Room schema7
remain. The existing command contract adds distinct `RECORD_CLOSING_COUNT`,
`DELIVER_COUNTED_CLOSE` and `SUBMIT_COUNTED_CLOSE` variants; there are corresponding
stored result actions, UI actions and a new `sales.cash.count.read/1` contract.
New generic aggregate kinds `sales.cash.closing-count` and
`sales.cash.closing-count-index` require no Room table/migration. Format-bound V1
record/DTO names are intentional, not ordinary implementation iteration names.

Explanations add separate `sales.cash.close-note.write/1` and
`sales.cash.close-note.read/1` contracts, generic `sales.cash.close-note` and
`sales.cash.close-note-head` aggregates and unique claims. Existing cash command,
close/count read bytes and financial records are unchanged. No Room entity or
migration is added. The gateway reports an uninstalled explanation reader as
unavailable history, never as a successful empty history. New format-bound DTOs
keep V1; ordinary handler/workflow file names do not use iteration suffixes.

The unused fresh legacy combined-close form was removed, **not** its decoder,
preparer, result recovery or historical records. Original saved requests are
checked before fresh-form validation. A session with any new durable count history
refuses the legacy combined `SubmitClose` path, even after cancellation/re-entry;
otherwise it could bypass the separate count/delivery sequence. Old historical
closes without new history remain readable. New count/delivery changes also make
older expected session revisions stale; retries never acquire a new body or ID.

The new sequence requires a jointly upgraded master and client. Old binaries do
not understand its new variants, records or in-progress closing transfers. Do not
downgrade a branch with that history, erase pending journals or treat an old-client
decoder failure as success. Actual upgrade/downgrade compatibility is unverified;
earlier unrelated auth-history/client-journal rollback restrictions also remain.

## Verification and remaining work

Current source `b8a1ead60b813683077361c18419ece4a797d1f46c2fff145dc30c7819c2f4ac` (**778 inputs**) passes gateway/UI
JVM, Android and desktop Kotlin compilation in85.704s and153 unchanged sales/
sales-store/UI regressions in44.897s (no failures/errors/skips). Both packets have
identical before/after source manifests. These are **compile/regression checks**,
not new-note/count UI acceptance, native/Room/LAN integration or an assembled APK.
The first new-note compile failed on missing command write/claim declarations;
the corrected handler is restricted to note/head kinds. All failed/earlier packets
remain. [Exact packets/hashes](../../apps/pos/VERIFICATION_REPORT.md),
[all325 requirements and ownership](../../qa/pos/traceability/cash-close-notes-20261027/README.md).

The older source `a3037a8e…` and its disk-held attempt remain historical evidence.
Disk headroom is now restored; it is no longer the current build blocker. Available-
memory dips were handled by the existing guard without lowering the6GiB reserve.

Independent designer `/root/qa_commerce` froze the existing COM-CASH-04/05/08/09/
10/11 and COM-PAY-10 I/E cases. They are requirement/preservation anchors, **not
complete focused coverage for the new note command/read/UI or cursor correction**.
Focused independent design/review and real runtime execution remain incomplete.
No test input, order, assertion or expected amount was changed. No independent
acceptance was run, no agents used and no implementation-derived oracle authored.

The CASH-05 production sequence no longer merges count/delivery/receipt/submission,
but its frozen public-contract/fault binding and genuine runtime fixtures still
need full execution. CQ-CLOSE-DISPOSITION/CQ-PUBLIC-BINDING remain preserved;
source work does not close those acceptance obligations.

Still required: genuine default native/Room startup, protected client journals and
additional devices; restart/transport/backup recovery; independent history/note
integration and E2E execution; **PAY-10 monetary/tender corrections and reconciled
report categories**; complete historical discovery/business-day reconciliation;
physical-money and all-platform/peripheral qualification. Free-text notes do not
silently replace financial adjustments.

The latest security attempt on earlier authentication source executed139 with2
protected-table expectation failures. Its database attempt failed test compilation
at the removed raw throttle callback and executed zero DB tests. Historical623/461
native-fixture failures remain unresolved, not a result of that new attempt.
Restaurants, cafés, clothing and supermarkets remain in scope. Central
implementation **and testing remain held** pending owner hands-on approval.
