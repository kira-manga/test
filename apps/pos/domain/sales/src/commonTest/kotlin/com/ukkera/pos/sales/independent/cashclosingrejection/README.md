# CCJR-v1 domain BEFORE-value checks

Frozen expectations: [cash-closing-rejection design](../../../../../../../../../../../../../qa/pos/designs/cash-closing-rejection/README.md).

`CashClosingBeforeFrozenTest` checks only supplied immutable domain values. It
creates no command, principal, historical `CommandReadView`, native original,
storage owner or RPC service. Passing guards do not authorize an operation.

| Method | Bounded frozen expectation |
|---|---|
| `lawfulBeforeGuardsDoNotInventRejectionFromAnAbsentAfterImage` | CCJR-011: valid BEFORE guards succeed without requiring or comparing a fictitious AFTER image. |
| `submittedCountsAndReportCannotBeOrdinarilyReopenedOrRecounted` | CCJR-001–003: submitted state rejects ordinary closing/cancel/recount value validation, preserving the input. |
| `reviewNeedsAnActualSubmittedReportAndADifferentReviewer` | CCJR-006/014: no review of an open session or ordinary self-review. |
| `actualCountAndDispositionRemainSeparateFromExpectedAndExternalCash` | CCJR-008/017/025: exact integer fixture amounts; pending ACK or wrong retained amount cannot validate submission. |
| `receiptGuardsRejectWrongRecipientStaleRevisionAndAlreadyReceivedData` | CCJR-028: receipt domain guards reject supplied recipient/revision/status mismatches. |
| `exactBeforeOwnerRevisionAndCountSourcesCannotBeReplaced` | CCJR-014–015/020/022: owner, revision, count predecessor and custody value mismatches fail. |
| `missingCountedDispositionAndWrongEvidenceNeverCreateAReceipt` | CCJR-014/022/028: missing count and mismatched evidence cannot validate counted receipt/count. |

Every method compares all supplied session/transfer snapshots and immutable
count/report values before and after. The fixed fixture matches the design's
EGP minor-unit arithmetic; it is not a real ledger or native BEFORE cut.

These tests assert domain refusal, not the exact durable handler's first refusal
code. In particular, receipt claim checks and current owner-private authorization
can refuse earlier than the domain guard. No CCJR whole case, native history
comparison, exact resource/mutation closure, current authorization, SQLite/RPC,
restart, later shift or platform run is established by these methods. Missing-count
adapter guards (CCJR-004/005), legacy rejection routing (007), and native/live
variants remain separately unverified. A counted close cannot be submitted with
an outstanding receipt; a pending-after-submission fixture requires its genuine
legacy origin rather than a fabricated counted workflow.

[Executed outcomes and limits](../../../../../../../../../../../../../qa/pos/traceability/cash-closing-rejection-20261103/README.md).
