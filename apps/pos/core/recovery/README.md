# Bounded recovery protocol core (LOCAL ONLY)

This module implements executable **L1 protocol rules**, not a backup system or
T25/T26 acceptance. Baselines: the preserved handoff's BLUE §§5/7/8, ARCH §18,
QA Q-SEC-09/Q-RES-02/05–10/14, and [reviewed plan](../../../../docs/pos/plan-review/FINAL-PLAN.md)
§§4/5/11 (F01/F02). No source handoff files are changed.

## Public integration surface

- `HistoryCoverage`: serializable per-appointment epoch/checkpoint coverage,
  available local/protected-archive ranges, known gaps, unavailable archives,
  terminal-cut versus transaction-bound live-head knowledge, and unknown tail.
- `OperationOutcomePolicy.classify`: metadata-only `COMMITTED`, `REJECTED`,
  `IN_PROGRESS`, `NOT_RECORDED`, `HISTORY_INCOMPLETE`, or identity conflict.
  A verified positive result survives incomplete history. Absence needs verified
  genesis/chain, every available range, terminal/head evidence and coordinated
  identity lookup. The highest copied sequence never proves a terminal cut.
- `RecoveryAdmissionPolicy.evaluate(state, branchId, affectedResources)`:
  fresh mutations need trusted resource state. The server derives the complete
  footprint, including the activated `AUTHORIZATION` resource. **There is no
  operation-ID exemption.** A new ID cannot spend restored stock, refund/deposit
  rights, exchange offsets, cash, PO allowances, supplier obligations or grants.
- The explicit four-argument admission overload adds a **denial-only** trusted
  profile restriction. Any retained whole-branch hold, matching kind selector or
  exact-resource selector of a selected kind denies new work, even after exact
  readmission and even outside the proposed footprint. It scans all holds, not
  just restore holds. Empty restrictions preserve the original policy. This is
  neither a wildcard grant nor a complete footprint/provenance proof; unrelated
  kinds still use ordinary admission. No production stock handler opts in yet.
- `RecoveryAdmissionMachine.restore`: starts a new incident under a whole-branch
  hold. `boundAffectedScope` needs explicit verified independence evidence and
  never releases restored authorization. `reconcile` and `readmit` are separate
  evidence-linked actions. Every relevant hold and preserved obligation is bound
  into the decision. Stock counts cannot prove unused refund/PO rights.
- `recordLateTail`: retains original operation/actor/effects as **evidence only**.
  It does not emit business deltas or payment/print/kitchen jobs. Exact one-to-one
  source-effect/adjustment matches are deduplicated; unmatched effects create
  fresh resource holds. Conflicting original content is retained in quarantine
  with a new branch-wide hold, without overwriting the original evidence.
- `AuthorityLifecycleMachine`: target/predecessor/incident-bound planned
  Preparing → Draining → Sealed → Activated → Completed, pre-seal abort only,
  and emergency issue/activate/cancel/complete. Repeated matching commands are
  idempotent; changed bindings reject. Source seal must be independently proven
  durable before target activation. Competing local appointments serialize on
  the protected revision/ceremony record.
- `AuthorityWritePolicy`: checks persisted role/stop/drain/retirement and fresh
  exclusive local-storage ownership. It must be combined with resource admission;
  an active authority does not imply that recovered balances/grants are usable.

`AuthorityIdentity.assignmentId` is the immutable appointment identity.
`AuthorityIdentity.epoch` is the monotonically next numeric generation, using
the checked decimal-string `Revision` scalar, **not** a row revision. Each
replacement edge binds the full predecessor and final/source checkpoint.
Same-master restart changes neither field. Journal positions use the separate
`JournalSequence` scalar. All domain/device/actor IDs use shared `DomainId`.

## Trusted boundary: mandatory, not an optional integration detail

There are **no HTTP/RPC routes or result bodies in this module**. The gateway
must authenticate admitted device and current local human/service identity
before loading any operation or recovery metadata. The original actor or an
explicitly authorized/audited result-recovery delegate may retrieve a permitted
projection. Losing fresh-mutation authority does not relabel an old operation,
transfer its drawer, discharge an obligation, or grant general cashier rights.

Reducers return **proposed state**, not a durable success. The real store must:

1. Authorize scope and the original operation/result-recovery path.
2. Coordinate lookup and submissions through the same durable `(branchId,
   operationId)` identity protocol and unique constraints. Check original actor
   and versioned semantic digest; exclude routing epochs/tokens from that digest.
3. Load transaction-consistent authority/coverage/admission and evaluate them
   inside the controlled writer transaction. Never persist/cache negative lookup
   as a permanent financial result. A later handler can still commit through the
   original identity; retry is the **same** command, not a new charge.
4. Compare-and-swap the persisted revision; atomically commit any audited
   reconciliation adjustment, result, protocol state, audit and required durable
   outbox. Return success only after commit. Readmission needs an explicit newer
   reconciliation when a new late-effect hold was introduced.
5. Preserve earlier incident aggregates and their evidence in protected storage
   when starting a new incident. The one-incident in-memory aggregate is not
   permission to overwrite old recovery history. Restoring a serialized state
   must enter a **new quarantined incident**, not resume copied admission/writer
   status, old bearer sessions or an old device's private key.

`RecoveryEvidenceVerifier` is a **trusted, transaction-bound local adapter**.
It must validate the complete typed assertion against authenticated protected
evidence, including actor/grant, branch, incident, target, source, immutable
content, cutoff and availability as appropriate. A client-supplied reference,
hash, signature, `verified=true`, latest sequence or in-memory acknowledgement
is not sufficient. Digest fields validate syntax only; this module does not
claim to hash/encrypt/verify a real recovery manifest. No accepting production
adapter is supplied: the default returns `UNAVAILABLE` and fails closed.

## F01/F02 challenge and unresolved owner evidence

- **Readmission is not history repair.** Current counted stock can become usable
  while an absent historical sale still returns `HISTORY_INCOMPLETE`. Neither a
  count nor an epoch proves unused refund entitlement or an unpaid invoice.
- **Known pending clients do not bound an unknown tail.** Without independently
  verified scope evidence, all potentially affected branch resources stay held.
  Unresolved external payments, refund claims/deadlines and custody obligations
  remain attributable to their original actors.
- **A signature/one-use token/next epoch is not offline global exclusivity.**
  Two copied issuer databases can independently issue to different targets. The
  tests explicitly model this counterexample; the default physical gate blocks
  both activations rather than pretending the counter is consensus.
- `PhysicalRecoveryGate` is a separate owner/deployment prerequisite. Activation
  requires a qualified, owner-accepted procedure proving exclusive issuance
  custody, exclusion of the old writer **and competing successors**, exclusive
  target storage, and new target identity/sessions. Target change requires
  cancellation and physical exclusion of the earlier target; lost issuer custody
  does not authorize another issuance. An isolated old device cannot learn its
  retirement from this process. Keep physical exclusion until controlled local
  retirement/return has actually been verified.
- No actual custody/fencing arrangement, independent complete encrypted/WAL-safe
  copy, key recovery, all-platform runtime, client re-pairing, full replacement
  rehearsal or physical RPO/RTO has been qualified here. **The owner physical
  gate remains unqualified.** Replacing the default gate with an always-accepting
  adapter would invalidate safety, not implement a missing consensus feature.

## Bounds and limitations

Evidence-reference lists are bounded to 64 and aggregate/range/effect lists to
4,096 items. Exhaustion fails closed and requires protected archive/storage work;
it never evicts deduplication or financial evidence. The model is not a scalable
archive implementation. Late-tail automatic matching is deliberately restricted
to exact one-effect/one-adjustment correspondence. Many-to-many/netted matches
need separately audited reconciliation; they are not automatically replayed.

There is no real snapshot/chunk transport, encryption/key adapter, database
backup/WAL operation, operation handler, cloud lease, network election, banking,
printer, fiscal or migration implementation in this module. Healthy provisioned
work has no standby/cloud acknowledgement prerequisite. Asynchronous recovery
does not provide universally lossless replacement.

## Verification scope

Common tests exercise 511 small range-coverage masks, 16 lookup/submission/retry
permutations, 720 planned-action permutations, modeled before/after-CAS crashes
and lost replies, scope/actor/epoch/checkpoint negatives, copied-issuer refusal,
every resource kind, explicit readmission, preserved obligations, and late-tail
race/quarantine/deduplication. JVM tests execute the versioned 12-case
`src/commonTest/resources/recovery/outcome-hold-v1.json` fixture.

The evidence and physical-gate helpers are **L1 model fixtures only**. Their
acceptance does not establish real authentication, Room crash durability,
cryptographic backup correctness, OS exclusivity or physical fencing.

Targeted23October20:57UTC verification passed55 JVM/54 Android-host tests and
both Apple main klib compilations. Thirteen new common tests cover the denial-only
barrier with actual restore/bound/reconcile/readmit transitions, including an
AUTH-only first hold followed by a late-tail STOCK alias hold. The database tests also exercise replay ordering and unchanged cash defaults. See
[verification report](../../VERIFICATION_REPORT.md) for the exact frozen input
snapshot. This is not Apple linking/runtime or recovery-system acceptance.

Relevant module commands (examples, not new execution results):

```sh
./gradlew :core:recovery:jvmTest :core:recovery:testAndroidHostTest --no-daemon
```

Native/physical platforms and full T25/T26 acceptance remain separate gates.
