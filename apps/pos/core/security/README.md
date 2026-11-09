# Local identity and authorization foundation

LOCAL ONLY. This module implements bounded foundations from the preserved
[original handoff](../../../../docs/pos/original-handoff/) BLUE §2/4, TECH §7
and OPS identities, and [reviewed plan](../../../../docs/pos/plan-review/FINAL-PLAN.md)
§3/12 / F04.
It is **not completion or production qualification of T08**.

## Trusted boundary

- TrustedPrincipalFactory accepts transport credentials, not actor/grant request DTOs.
  Only a process-composition PrincipalVerifier can return verified registry identities.
  Principals have no public source constructor, copy, or serializer. In-process callers
  use the same boundary; a body cannot select a company, employee or service identity.
  Verified identity records, resource ownership and policy snapshots are **trusted adapter
  outputs**, never HTTP/RPC parameters. Public port types are not cryptographic proof
  against arbitrary malicious code already running in the service.
- Organization/company/branch relationships must be resolved by that adapter. Local
  employees, selected-company central humans, admitted devices, and purpose-limited
  branch services remain distinct even when UUIDs happen to match. Privileged-approval
  contexts only approve; prepared-recovery contexts only authorize recovery actions.
  Neither is a general cashier session or an original-actor result-read shortcut.
- CurrentAuthorizationProvider must read the current protected account, token/session,
  device admission and activated policy in the **same serialized transaction** as the
  operation. AuthorizedAction is transaction-local, not a cacheable bearer.
  Pure reducers compare its complete requirement and originating actor. Grant/account/
  device changes must serialize with financial admission and bump the relevant revision.
  The identity-only currentIdentityDenial helper returns no reusable proof: fresh commands
  still need typed action authorization; existing/absent results still need recovery policy.
- The gateway recomputes SHA-256 over its own versioned canonical semantic bytes.
  PayloadDigest validates syntax only. Canonicalization belongs to the command adapter,
  not to this hash primitive. A supplied digest, UUID, session generation or epoch grants
  no authority. All 64-bit serialized security counters use exact decimal strings.

## Policies implemented

- Independent perform/request/approve/administer/export rights; explicit deny wins.
  Branch scope never expands to a company or another branch. Central/company rights
  never imply local cashier or recovery authority.
- Monetary use requires an explicit maximum amount. Proportional use requires **both**
  amount and basis-point maxima, from one complete grant, plus any delegation ceiling.
  Comparisons are exact and inclusive; percentage ceilings round down, never up.
  Missing limits are not infinity. Action metadata prevents omitting required money/
  owner/binding information. Domain adapters must map each mutation to the correct action,
  not hide a discount or refund behind a generic order-edit permission.
- Owner-private queries/direct IDs/subscriptions/exports/cached views use the same policy.
  ProjectionPolicy supplies closed masks and applies them before serialization; quantity
  access does not expose cost/margin. Same-branch return receipt lookup is a limited shape,
  not general other-owner order access. Delivery contacts require stored courier ownership
  (or an explicit supervisory any-owner grant); kitchen tasks require target-scoped grants.
  Related records need separate authorization. Private cache keys include tenant/actor/
  session/generation/policy/resource revision; every delivery still needs current policy.
- Result recovery keeps the **stored original actor**. An enabled current interactive
  employee session may recover an old operation without new cashier mutation grants.
  Another employee needs explicit scoped recovery permission and a durable audit append;
  the supervisor-audit port defaults to deny. Authorize before returning existence,
  result bytes, absence or recovery coverage information.
- Approvals require independently authorized request and privileged-approval contexts.
  They bind original actor, scope, action, document/revision, operation, canonical
  lines/payload, reason, amount and eligible percentage base. Self-approval requires an
  explicit named actor/action/scope exception. Consumption rechecks policy revisions,
  account enablement, binding and same-boot monotonic expiry, and emits an original-actor
  perform permit. **Approval CAS + effects + result/audit must commit together**; pure
  reducers alone do not supply single-use persistence. Reboot conservatively invalidates
  an approval whose expiry cannot be proven. Recovery activation additionally requires
  prepared local recovery authentication and named target-specific delegation.

## Sessions and throttling

- LocalSessions reduces durable account/session state. One active session is the
  default; another device requires explicit move. Optional bounded concurrent sessions
  retain individual generations. Moving/revoking never edits pending operation ownership
  or cash custody; the master/service lifetime is independent.
- A current session's generation, not the account's latest allocation counter, belongs
  in its authorization snapshot. Disabled accounts cannot sign in through this reducer.
  Counters never wrap; their final value is reserved for terminal disabled/all-revoked
  state. The storage adapter must reject every active replacement after terminalization,
  including stale same-revision replacements. No reset-to-zero recovery path is provided.
- InteractiveSessionFence is a synchronous **UI-event-loop-confined presentation gate**.
  Invalidate before asynchronous logout/root changes; gate even the *first* late financial
  reply. A stale response cannot publish into B's UI, and A's durable pending evidence is
  not cleared or relabeled. This is not a server lock or transport confidentiality control.
- PersistentLoginThrottle requires provisioned, protected, transactional account-wide
  and admitted-device/account buckets. Both reserve before checking a credential and
  complete durably before authentication success. Missing/unreadable state fails closed;
  there is no production in-memory store. Outstanding crash/timeout guesses count as
  failures. Changing devices cannot bypass the account bucket.
- No wall time participates in lockout. Same-boot monotonic rollback denies; reboot
  conservatively preserves remaining delay. Boot identity/monotonic providers themselves
  require platform qualification. Malicious rollback/deletion of protected disk state is
  **not** solved by a pure clock/state machine.

## Immutable access issuance prerequisite

`LocalAccessIssuance` is inert INTERACTIVE-access data, not a verified bearer or
principal. One protected revision-zero record binds the complete scope, actor,
session, device, individual session generation, original credential/admission
revisions, separate service issuer context and same-boot issue time. Its explicit
positive duration has a representable, exclusive deadline; there is no default
TTL or read-time backfill for old sessions. The hash profile is
SHA-256(UTF-8(`ukkera.local-access-token/sha256/v1`) || 0x00 || 32 secret bytes).
This hash helper neither generates entropy nor defines/verifies a bearer format;
canonical hash parsing and ordinary equality are not authentication.

`matchesSecret` computes that same hash and compares distinct owned32-byte digests
using `MessageDigest.isEqual` on JVM/Android or pinned `timingsafe_bcmp` on Apple.
Provider failures propagate rather than becoming mismatches. Owned buffers are
wiped best-effort; no total-erasure or end-to-end constant-time claim. Published
source/binding availability and klib compilation do not qualify native runtime.

`LocalAccessEligibility` compares supplied current facts only. It checks the
individual retained session, not equality with the account's latest allocation
generation. `core/database` supplies a separate internal same-writer current
authorization source for an ALREADY trusted principal. The candidate-identity
overload is equally pure: `VerifiedLocalIdentity` syntax alone establishes no
trust. The separate internal D055 database verifier authenticates existing ULA1
issuances, without accepting application wiring. Qualified keys/time/service
context, supported request-entry confinement, sign-in and refresh remain gates.
See the verification report for this source cut's executed/reviewed status.

## Cryptography and unresolved gates

The new `ProtectedLocalStorage` types are inert record/envelope contracts, not
authentication proofs. Fixed AES-256-GCM AAD binds the configured key reference,
logical table/schema, complete scope/key/dimension and revision. Defensive
copies and redacted diagnostics are part of the boundary. The default provider
returns unavailable; no exported key or production encryption implementation
is installed here. `core/database` contains a separately reviewed internal
five-table Room adapter and strict canonical codec; its actual JCA AEAD provider
is **test-only** with explicit synthetic keys.

Separate256KiB/1MiB/16KiB/4KiB/4KiB account/policy/admission/throttle/issuance plaintext limits
preserve256-session history without changing command-kernel budgets. Keep
credentialRevision0, explicit null credentials with retained revision, terminal
low-revision→MAX lockdown and complete meaningful same-company policy grants.
Count and byte bounds reject rather than truncate. AEAD cannot detect coherent
historical rollback; the database tests explicitly demonstrate that limit.

SHA-256 uses JCA MessageDigest on JVM/Android and public CommonCrypto on iOS.
It is not a password hash or a storage encryption mechanism.

The optional JVM password adapter uses JCA PBKDF2WithHmacSHA256 (600,000 minimum,
5,000,000 maximum iterations, 256-bit output), SecureRandom 128-bit per-credential
salts, and a non-exporting device-protected HMAC-SHA256 pepper port. The post-hash MAC
is domain-separated and bound to tenant/account/credential revision. Verifier comparisons
use MessageDigest.isEqual; passwords are bounded, not trimmed/normalized, and temporary
arrays are cleared best-effort. JVM memory erasure is not guaranteed. Scope one hasher to the
service process: work is limited to two concurrent derivations; excess callers fail busy
instead of joining an unbounded password queue. Calibrate **upward on actual hardware**.

There is **no qualified OS pepper/key provider**, production protected identity
composition, credential reset/enrollment flow, complete login/token-rotation
gateway, whole-financial-database encryption, recovery key envelope or platform
service-context qualification in this module. The inert Room security-row
adapter does not satisfy those production gates or activate local accounts.
Unsupported/unqualified key contexts fail closed; there is no adjacent-key file,
hardcoded production credential, exported pepper, or in-memory production fallback.
Do not reuse existing online/convenience DefaultAuthRepository, clear-on-error
SessionManager or adjacent-key JVM token storage for master/pending financial material.
Key/storage failure must preserve evidence and enter recoverable failure, never erase
or silently create a new identity. iOS/Android password providers remain explicit gates.

The independent P2pKit rc6 review found retained/terminal/queued reply paths can outlive
an employee session on the same device; resultCacheBytes=0 alone does not fix outbound
authorization. Sensitive RPC integration remains **blocked/unqualified** pending a
request-bound and outbound-session-bound solution. UI fences do not remediate wire/device
cache disclosure. No live network/payment/database experiment was performed here.

## Tests and review

Synthetic tests exercise identity realms, exact dual limits, deny/owner/tenant rules,
revocation, session moves/terminal counters, late replies, single-use/stale/self approvals,
projection masks, audited result recovery, conservative persistent throttling, transactional
failure/concurrency, published SHA vectors and a Python-independent PBKDF2/HMAC fixture.
The test-only fake durable store/pepper are **not** platform persistence/key evidence.

Relevant module commands (examples, not new execution results):

    ./gradlew :core:security:jvmTest --max-workers=1 --no-parallel
    ./gradlew :core:security:testAndroidHostTest --max-workers=1 --no-parallel
    ./gradlew :core:security:compileKotlinIosArm64 --max-workers=1 --no-parallel
    ./gradlew :core:security:compileKotlinIosSimulatorArm64 --max-workers=1 --no-parallel

Android host tests run on the build host, not physical Android. Native main klib compilation
does not prove framework linking, iOS execution or service keys. Native disabled/skipped
tasks are not compilation evidence either. Actual commands, results, independent findings and
remaining gates belong in the verification records; tests existing in source are not
a claim that they ran successfully.
