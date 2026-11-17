# LAN financial process-recovery supporting runner (LAN-FPR, observation revision 1.0.3)

Self-authored, **NOT independent**. Frozen on 2026-11-16 before its first execution.
Supporting evidence for [#113](https://github.com/ukkera/kmp-apps/issues/113): one
genuine unresolved financial original survives real Client and Master JVM interruptions.
It does not modify or replace the frozen inputs or expectations of
`qa/pos/designs/lan-master-client-v1` (LAN-R04/A07/D04/D06) or `lan-payment-supporting-v1`.
Whole frozen cases remain NOT RUN, regardless of this runner's exit status.

## Revision history

- **1.0.0 run fpr-01** FAILED, and its logs and original native evidence are
  preserved in the local verification record. Steps 1–3 passed: SQL_ORIGINAL_PENDING was observed and then SIGKILL exited 137.
  In step 4, the restart reconnected on the first attempt, sign-in succeeded and the killed
  process's session was `Denied`. However, `recoverSupplierCash(original)` did not report
  `SQL_ORIGINAL_PENDING` within 90 s.
- **1.0.1** is diagnostic only. The recovery poll logs each change of reply kind and fixed enum
  (Progress state or Unavailable problem), without IDs or payloads. No action or expectation changed.
- **1.0.2** was a separate diagnostic execution after the original failed expectation;
  a later sign-in recovered the same pending original, confirming the temporary
  native journal-ownership window. It is not the current runner's action sequence.
- **1.0.3** moves the unchanged independent read-only ledger assertions after both
  Client and actual Master native close. An earlier whole run reached the financial
  checks but the live observer was refused by SQLite exclusion; that failure remains
  preserved. Financial amounts, operation identities, assertions and deadlines are
  unchanged. Protocol/completion markers retain their original LAN-FPR v1.0.0 identity.

## Inputs and environment

- Two disposable network namespaces, `10.203.19.0/24`: Master `.1`, Client1 `.2`, joined
  by a bridge in a separate network-only switch namespace. Each application namespace
  has exactly one active LAN interface and loopback. Namespace keepers hold networking
  only. Every application stage is a fresh JVM on the same original role root.
- Native root is a new `0700` directory under `user.home`, because the physical spool
  rejects group/other-writable ancestors. Logs and exchange markers stay in that root.
  `ukkera.lan.paymentSupporting=true` selects the existing payment-only Linux SOFTWARE
  physical source (`LanPaymentPhysicalNativeSupport`) and Master physical gate. These are
  explicit test OS stand-ins, not Keychain/CNG/Android/iOS custody.
- Genuine company/Master installation, head-approved device admission, employee
  enrollment, protected encrypted Client journals, production UI sign-in and the
  production typed supplier-cash physical API (`BranchUiSession.retailPhysical`). The
  amounts are the same as `lan-payment-supporting-v1`: opening float 1000.00 EGP, one
  supplier cash advance of 228.00 EGP and expected cash 772.00 EGP. The Master's owner
  configures custodian, drawer and supplier only after Host start and Client1's
  authentication. Client1's employee `cashier.a` holds `CASH_READ`, `CATALOG_READ`,
  `INVENTORY_QUANTITY_READ` and `LanGenuinePaymentSupport.clientActions` (`OWN_ONLY`).
- No row injection, manufactured grant, cached login, replacement ID or new Start is
  used after any interruption. RPC, session, native and keepalive deadlines are
  unchanged. Orchestration watchdogs are harness limits, not product SLAs.

## Fixed actions and expected observations

1. **master/initial**: install and open the original Master with the physical gate
   (`ENROLL_ORIGINAL`). Pair Client1, enroll `cashier.a` and start the original Host.
2. **client1/initial**: pair, enroll protected journals, connect and sign in through the
   production UI gateway with the paired remote physical binding. `CASH_OPEN` is
   APPLIED. Stage `SupplierCash(228.00)` and Start it. Expected: one `Instruction`
   whose display amount is 228.00 and whose subject is SUPPLIER. Capture `Unknown`,
   expected `CapturedInMemory`. Then close the UI session and deny the lifetime (loss of
   UI delivery). Sign in again with the device-move option. Poll `recoverSupplierCash(original)`,
   bounded at 90 s as in the payment mapping, until it reports `SQL_ORIGINAL_PENDING`.
   `recoverSupplierCash(start)` must also be `Progress/SQL_ORIGINAL_PENDING`, never an
   `Instruction`. Record the start/original IDs and signal `killable`.
3. **Process interruption 1:** the executor sends SIGKILL to the Client1 JVM (no
   graceful close, no native close). Expected: non-zero exit (137). Master stays running.
4. **client1/client-restart** (fresh JVM, `openExisting`, journals `REOPEN`):
   - The recovered SDK identity and approved Master data must be exact. Retained
     credential material is present, but there is no actor access before current
     authentication.
   - Connect uses the same production-shaped retry as `LanRecoverySupporting` 1.0.2. It
     retries only `NotConnected/Negotiation/NotSent`, at most 7 attempts, from 1 s
     doubling to 30 s.
   - Explicit sign-in uses the device-move option. Afterwards, the killed process's
     retained session token must not be `Current` (old authority revoked by replacement).
   - Poll `recoverSupplierCash(original)` (bounded 90 s) until `SQL_ORIGINAL_PENDING`.
     The same original, start and display amount are required, and there is no `Instruction`.
   - Stage `SupplierCashApply(original, ORIGINAL)` and continue it. Expected:
     `OBSERVATION_APPLIED_UNRESOLVED`. Three replays return the identical progress.
   - Graceful close of gateway, journals and RPC owner.
5. **Process interruption 2:** after Client1 closes, the Master closes gracefully (Host,
   then service quiesce). The executor confirms exit 0.
6. **master/master-restart** (fresh JVM): open the existing original only, compose it
   with the physical gate reopened by `REOPEN_EXISTING`, and require operational mode and
   the same SDK identity. The Host then restarts.
7. **client1/master-restart** (fresh JVM, `openExisting`, `REOPEN`):
   - Same identity checks. No actor access before authentication. Production-shaped connect.
   - Explicit device-move sign-in. The previous process's token is not `Current`.
   - `recoverSupplierCash(original)` returns `OBSERVATION_APPLIED_UNRESOLVED` with the
     exact original ID. Replaying the retained ORIGINAL apply returns the identical
     progress, with no second application.
   - `pendingSupplierCash()` is recorded as an observation only.
   - Stage `SupplierCashResolve(original, CashPaid literal "228")` and continue it.
     Expected: `SQL_RESOLUTION_PENDING`. Its recovery links exactly this original and
     resolution, with `resolutionRecorded` set and a 228.00 review amount.
   - Stage `SupplierCashApply(original, RESOLUTION)` and continue it. Expected:
     `FINANCIALLY_APPLIED`. Three replays return the identical progress.
   - The CASH workflow read shows expected cash `772.00` EGP. Then graceful close.
8. **Master ledger, read-only** (fresh read-only SQLite connection, after Client1 AND
   the actual Master Host/service have closed;
   checks adapted from `LanGenuinePaymentSupport.verifyNativeLedger`, with attribution):
   - The raw ORIGINAL fact has outcome `Unknown`. The linked RESOLUTION fact is `CashPaid`
     228.00 and has the same target and body digest linkage. One `UNKNOWN` and one
     `CASH_PAID` application are present, each bound to its exact apply operation.
   - The cash session has expected cash 772.00, payOuts 228.00, and zero payIns and
     receipts.
   - Exactly one movement exists, at revision 1, with delta -22800 bound to the
     RESOLUTION apply operation.
   - Exactly one `operation_result`/`journal_envelope`/`audit_event`/`outbox_intent` row
     exists for each of the opening, start, original, ORIGINAL apply, resolution and
     RESOLUTION apply operations.
   - Before native close the Master persists the genuine STOP launch hint. The
     independent ledger verdict and final confirmed-close marker are emitted only
     after the unchanged assertions above pass.

Any deviation is a failure with the last reached marker, logs and the native root
retained. Prerequisite failure is a failure, never a skip.

## Deliberate coverage limits

- One Client and one supplier advance. No retail sale, refund, food, stock or second
  Client. Not a complete frozen LAN case.
- Interruption 1 is a real SIGKILL at a deterministic durable boundary
  (`SQL_ORIGINAL_PENDING` observed). It is not a fault injected within a native write,
  and not power loss or OS crash durability.
- Interruption 2 is a graceful Master process exit, not a Master SIGKILL.
- Linux SOFTWARE key/file stand-ins only. No Windows/macOS/Android/iOS, installed
  application or owner device is qualified.
