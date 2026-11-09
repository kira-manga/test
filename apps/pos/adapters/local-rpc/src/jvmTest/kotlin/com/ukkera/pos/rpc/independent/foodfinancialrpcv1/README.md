# FOOD financial RPC version 1 supporting binding

This harness binds frozen **1.0.0** at
`qa/pos/designs/food-financial-rpc-v1/`. The frozen README, cases and fixtures are
checked against their recorded hashes before use. All **19 JVM supporting methods
passed**. [Source-specific results and limits](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/food-financial-rpc-20261106/README.md)
remain separate from genuine native/admitted-RPC and whole-case qualification.

| Frozen cases | Bound supporting observations |
| --- | --- |
| FFRC-001 | Three exact version-1 procedure names, never-reinvoke policy, distinct ordinary FOOD procedure names, no request-carrier profile field |
| FFRC-002 | Exact two-key transport selection, incompatible versions and excluded transport keys; no canonical-body dispatch claim |
| FFRC-011, FFRC-025 | Capture/serialization preserves the five representable `OperationIdentity` coordinates, including changed-coordinate DATA; no stored-result validation claim |
| FFRC-013, FFRC-025 | Unavailable, identity conflict and writer-absence response DATA remain different wire variants |
| FFRC-016 | Exact seven-key read membership; no selector classification or grant claim |
| FFRC-018, FFRC-021 | Existing captured identity scopes recheck controlled current identity/peer answers; mismatched actor/session/device/scope and a late controlled denial suppress publication |
| FFRC-012, FFRC-027 | Synthetic equal-peer, repeated-generation successor contexts and unscoped/descriptor-only entries cannot adopt the retained original identity scope |
| FFRC-027 | Cancelled retirement keeps the exact synthetic lifetime, denies further identity use, and preserves confirmed/incomplete SDK-wrapper closure outcomes while leaving the successor untouched |
| FFRC-029 | All three actual lane request serializers/captures, full BEARER52 pattern, independent copies, rejected 0/51/53 lengths, held-borrow retirement, cancelled waiter, complete wiping, repeated cleanup and redacted diagnostics |
| FFRC-030 | Two real concurrent request claims have exactly one owner; equal-coordinate requests have independent owners; expiry before/after claim cannot seize a successful claimant |
| FFRC-031 | Exactly 85 Read + 85 Execute + 86 Lookup retained requests, all 256 claimed borrows held simultaneously; request 257 refused, terminal/incomplete capacity retention, no age-based seizure, exact target completion gives 255, one replacement restores 256, exact 29,999/30,000-ms expiry and age-zero completed cleanup |
| FFRC-032 | Deterministic decoder admissions before/after retirement, exact claimed/unclaimed census, request cleanup and cancelled waiter, no drained release while any exact loan remains, final buffers/census cleared, injected cleanup-source failure remains retained/unavailable |

The carrier payloads used for copy/custody checks are explicitly inert test data,
not a canonical financial command, qualified employee credential or native cash
original. Their semantic identities preserve A/R1/L1 labels without claiming that
the labels authenticate anything. The `OperationIdentity` carrier exposes branch,
operation, original actor and semantic digest version/hash; it does not expose
form/command versions or canonical body/revisions separately. Complete retained
identity and source validation therefore remain required elsewhere.

The actual request serializers, `LocalRpcBusinessRequestCustody` and
`LocalRpcIdentityBinding` are exercised. Controlled verifier/provenance answers
and JVM-public SDK data constructors supply supporting identity contexts only.
Synthetic close actions own no socket or native handle. There is no private SDK
access or replacement SDK implementation.

`TestTimeSource` supplies monotonic maintenance time. Synchronous loans use exact
entry/release/return barriers. All 256 capacity loans are nested on one thread;
returning the innermost target leaves the other 255 genuinely held. Cleanup
waiters enter UNDISPATCHED, and pending assertions run ready test continuations
before checking that they remain suspended. Timeouts guard missing barriers and
never serve as race evidence. Read-only reflection witnesses retained arrays and
the raw custody census; it never changes buffers/state or invokes census closures.

FFRC-001's actual attachment/UI/journal/reconnect profile fixation and conflicting
profile assertion remain unbound. FFRC-002's canonical Draft/Add/Send/RecordOriginal
refusal under shared keys requires the installed typed handlers. FFRC-016 shared
selectors and exact empty discovery likewise require the business layer. No
permitted-body success is inferred from key membership.

FFRC-018/021 checks do not validate production credential expiry/revocation,
employee/device/session storage, current business grants, scoped receipt access,
notification-independent Master policy activation or Arabic/English clearing.
The single null-source variant groups missing/expired/revoked/disabled supporting
answers; it does not execute those distinct production state transitions.
FFRC-019/020/022 admission-to-settlement revocation, durable commit/disclosure,
ownership change and source-bound original recovery remain unbound here.

FFRC-011/013/025 do not validate genuine retained sources, unsupported refusal
sources, exact committed result matching, authoritative absence, lost delivery,
reconnect, Stage/Submit/capture call counts or application ACK. Procedure retry
metadata and response DATA do not qualify the SDK retry/recovery runtime.

FFRC-030's Host admission denial, callback capacity refusal, work exception,
caller cancellation, noncancellable callback cleanup and accepted accounting/drain
remain unbound. The FFRC-032 controlled request cleanup owners and injected
`awaitCleanup` failure do not stand in for actual Host callback accounting or
genuine native buffer-wipe failure. No copied Host callback implementation is
used to claim those observations.

No production persistence, money/stock cut, audit/outbox, durable Client journal,
genuine admitted RPC, native payment custody or native close/reopen is supplied.
The frozen business-cut assertions remain required, including drawer 157,013,
the original cash/source identity and unchanged committed effects on shutdown.
All P/R/U/D portions and the other frozen financial business cases remain outside
this supporting binding. Fresh remote physical CASH capability and P2pKit #543
acceptance-before-transport-Receipt remain separate unresolved qualifications;
application ACK is not evidence of transport Receipt ordering.

Run from `apps/pos`:

```sh
./gradlew :adapters:local-rpc:jvmTest --tests 'com.ukkera.pos.rpc.independent.foodfinancialrpcv1.*'
```

Record the actual tested revision, case/version, outcome and limits separately.
Harness source and a future supporting pass do not complete the full frozen
cases, installed-platform campaign or owner acceptance.
