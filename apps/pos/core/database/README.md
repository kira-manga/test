# Bounded durable master command store

LOCAL ONLY. This is a tested foundation under continued independent review, not
a complete POS database, an accepting production service or a recovery ceremony.
Original authority: [preserved requirements](../../../../docs/pos/original-handoff/), especially
BLUE §§2/5/6/7, ARCH durability/recovery, and the consolidated review's F13–F15.

## Public boundary

`openLocalMasterStore` currently opens an absolute JVM file path in an existing
directory. It does not provision a branch, unlock protected identities, acquire
an installed-service custody lock, appoint a writer or enable any UI/RPC gateway.
Both `UnavailableTransactionAuthorization` and `UnqualifiedMasterRuntime` deny
by default. Accepting fixtures exist **only in tests**.

`MutationRequest` contains a stable operation ID, versioned command key and bounded
bytes. It cannot choose actor, branch, authority, digest or a handler class. A
trusted composition root registers typed handlers. `normalize` decodes bounded
command DTOs and canonicalizes semantics; `prepare` reads only its branch-bound
transaction view and derives permission, current revision, source dependencies
and recovery footprint; `evaluate` produces a pure proposal. No network, printer,
payment, UI, detached coroutine or unbounded calculation belongs in that scope.

The kernel:

1. Validates current local employee identity inside the writer transaction.
2. Recovers existing `(scope, operationId)` using the stored original actor and
   current result-recovery policy before exposing result/conflict. Supervisor
   recovery appends an audit in the same transaction; it does not adopt the sale.
3. For absent operations, requires qualified writer and complete absence evidence,
   exact action authorization and reconciled affected resources. The actual
   actor's AUTHORIZATION resource is mandatory, not merely any employee key.
   A trusted handler may additionally select denial-only recovery hold kinds;
   the kernel captures both sets once and checks all matching retained selectors
   before evaluation. Readmission cannot bypass this optional restriction, while
   authorized original-result replay still precedes new-work admission.
4. Checks changed-row revisions, actual unique source/custody claims and deadlines,
   then commits aggregate effects, result, journal, audit and immutable job intents
   all-or-none. Acknowledgement is published only after the **outer** commit.
5. For its internal protected-current source, rechecks authenticated dependencies
   on every normal execute/lookup exit, including replay/conflict and supervisor
   audit. All suspendable reads/provider-health checks and any wall-time capture
   precede the final synchronous monotonic sample. Final denial throws out of
   the transaction before mapping to `Deferred(AUTHORIZATION_DENIED)`; finally
   only closes the borrowed view. This is not login or production composition.
6. Converts storage/commit uncertainty to `Deferred(STORAGE_UNCERTAIN)`, never a
   definitive rejection. Cancellation propagates; callers retain the original ID.

Handlers must additionally validate **all read dependencies**, approval consumption,
full pending/closing/source indexes, original snapshot links and exact paired
inventory plans. A changed-row CAS alone cannot establish those business facts.
No production typed handler is registered by this module.

## Current aggregate and claim reads

Both current point accessors use scalar-only, key-only SQL preflight before Room
hydrates any row payload or arbitrary text. SQLite REAL revisions, TEXT payloads,
BLOB owners and invalid length/type/NUL metadata cannot be silently coerced into
valid observations. Invalid existing rows are not filtered into apparent absence.
Hydration must still be present and match scope/key, a positive storage revision,
canonical schema/owner/hash and exact bounded payload bytes. A private copy is
hashed and returned; expired views and cancellation are checked around every
suspended query. Storage/parser errors lose their private cause and details.

Proposal CAS and claim validation use those same transaction reads, not a raw DAO
bypass. The checks do not authenticate the file, prove a current frontier, scan
omitted claims or qualify all other database query families.
The invalid-versus-absent distinction applies only to rows matching the canonical
SQL key; deleted or retagged keys are not diagnosed by a point lookup. Existing binary
aggregate values may still be empty or arbitrary within64KiB; typed adapters own
domain decoding. Schema entities/version and normal admission semantics remain
unchanged by this read hardening.

## File/durability candidate

Pinned Room3 3.0.3 + bundled SQLite2.7.1 public APIs: TRUNCATE journal, FULL
synchronous setting, one Room connection, no internally pooled driver. Before
each command/read transaction the actual borrowed writer is checked **outside**
a transaction for TRUNCATE/FULL, foreign keys and persistent `main` file. Work
uses `IMMEDIATE`. No WAL/onOpen tuning, private SQL interception, in-memory master
fallback or destructive migration fallback exists.

Append-only tables have UPDATE/DELETE guards **and BEFORE INSERT identity guards**:
SQLite REPLACE can bypass DELETE triggers when recursive triggers are off. The
same exact DDL map creates and verifies guard table/body definitions on reopen;
names alone are insufficient. This detects accidental schema drift, not hostile
code with arbitrary live-file access. A protected directory/custody boundary and
verified backups are still required. No attacker-resistant disk signature is
implied by local content hashes.

**Replacement protection under the reviewed DDL:** the original seven financial/
four protected guard sets missed deliberately supplied hidden-rowid collisions.
Four unchanged regression tests first demonstrated actual destructive UPDATE
OR REPLACE on v3; they now pass with18 additive v4 guards. All11 old tables reject
global rowid INSERT collisions, not just same-scope collisions. All seven mutable
tables reject any rowid relocation on UPDATE; branch scope and claim scope/
namespace/key are also immutable. Existing aggregate/protected key and revision
guards stay unchanged. Ordinary DAO payload/revision changes, terminal account
updates and claim release/reacquisition remain available. No new DELETE policy
or required recursion setting is introduced. Normal DAOs do not specify rowid
or use REPLACE; no exposed production replacement call path was identified.

Every rowid alias and negative value is covered. The fifth issuance table retains
its separate logical-key OR global-rowid guard. An existing raw rowid=-1 can
conservatively deny an omitted-rowid append because SQLite's BEFORE INSERT
sentinel is also -1. No persisted row is renumbered to avoid this availability
limitation. RAISE(ABORT) rolls back the failing statement, not earlier statements
when the caller catches the exception; an uncaught failure rolls back the actual
outer Room transaction. Tests distinguish these cases using real changed rows.
This is a fixed reviewed uniqueness topology, not an arbitrary-DDL/UNIQUE-index
inspector, hostile-file defense or authentication of raw protected DML.

The current schema4 and preserved schemas1/2/3 are **unreleased development
candidates**. No live migration has been run. The12 entities/indexes and all33
historical guard bodies remain unchanged; v4 adds only18 guards for51 total.
Schema4's Room identity intentionally equals3's; the version and exact callback
guard verification distinguish them. No dummy DDL forces a new identity.
`buildMasterRoom` registers direct1→4,2→4 and strict3→4 routes, retaining old routes.
The first two invoke unchanged1→3/2→3 after the new-name preflight, not strict3→4
while Room still reports1/2. The genuine3 route checks TRUNCATE/FULL, a persistent
file, exact v3 version/identity and all33 corrected historical guards first.
Unrelated legacy/marker qualification databases retain the generic builder.

Before even inherited migration DDL, every route rejects any of the18 reserved
new names across all main-schema object types using ASCII-case-insensitive name
matching, even a correct existing guard body. Original single id42/identity bytes
remain strictly typed and byte-exact; NOCASE cannot bless an uppercase identity.
Invalid source provenance, required old guards or reserved names cause zero
CREATE preparation/execution. This is NOT a zero-DDL promise for every arbitrary
corrupt old object or data/FK failure: those can fail later and must roll back.
No IF NOT EXISTS/adoption/repair, re-encryption or rowid rewrite is used.

Separately generated genuine7/11/12-entity legacy-v1/v2/v3 fixtures retain actual
1→2/1→3/2→3 targets and old-version reopen. Current direct routes preserve exact
typed cells, ciphertext/bindings, unusual rowids, table/index DDL and old guards;
populated v3 includes a genuine synthetic encrypted issuance. Healthy injected
failures after1/9/18 actual new guards restore the old file and permit old-version
reopen/retry. Already FK-corrupt sources stay refused and preserved, not repaired
or represented as healthy retries. Live-fixture raw observations hold the sole
Room writer outside a transaction to exclude asynchronous invalidation work;
this test-only coordination is not external-process fencing. The old marker-v2
test remains a separate fault probe. No destructive fallback or downgrade exists;
unsupported files must remain quarantined/preserved.

## Inert protected local security storage

Five additive tables hold account+credential+existing session aggregate,
activated effective/session/throttle policy, device admission, the paired
login-throttle buckets and immutable access issuance. The only FK is each scope to `branch_authority`; rows
are not automatically initialized or cross-populated by opening or reading.
They are internal data storage, **not** provisioned identities, authentication,
policy activation, device enrollment, credential reset or an accepting runtime.

`DeviceProtectedLocalStorage` is an injected, non-exporting fixed AES-256-GCM
contract. Production remains unavailable; actual AEAD exists only in JVM
tests with explicit synthetic keys. Canonical AAD binds format/algorithm,
configured key ID, table/schema, full scope, record/dimension key and revision.
Stored key IDs cannot select arbitrary keys. SQL scalar type/byte checks precede
full-row hydration, followed by exact header, tag, expected payload identity
and canonical codec validation. Missing is distinct from corrupt/unavailable;
no plaintext, adjacent-key, reset-to-zero or empty-state fallback exists.

Separate plaintext ceilings are account256KiB, policy1MiB, admission16KiB,
throttle4KiB and issuance4KiB, plus a16-byte tag and12-byte nonce. They do **not** expand kernel
financial64KiB/1MiB limits. Both count and actual byte limits apply. Preserve
all256 sessions, full meaningful same-company and exact-branch grants,
credentialRevision0 and explicitly credentialless records retaining revision.
Account/session CAS preserves ordered history and credential bytes. Near
exhaustion can jump a low account revision to both countersMAX, disabled/all
revoked. Stale/history-changing replacements cannot reactivate a terminal row;
an exact terminal retry is a no-write result, not an UPDATE pastMAX.

The internal throttle wrapper captures the exact Room instance, scope, actor,
device, attempt and account/generation/credential/policy/admission incarnations.
It rechecks them in each IMMEDIATE transaction before the unchanged paired
helper callback, restricts two exact bucket keys and uses the activated policy.
Each captured reserve and completion capability is single-use, including
denial/cancellation/unknown COMMIT. Original attempt-start continuity rejects
same-ID reuse; one-shot completion also covers equal monotonic timestamps.
Unequal bucket revisions/failures are legitimate and never normalized. Expensive
password work belongs outside the transaction. No session/token is issued by
successful throttle completion; a future sign-in must recheck original boot,
deadline, key custody and all incarnations before its own atomic acceptance.

Borrowed views cannot escape their transaction. Caught storage failures or a
CAS conflict combined with any write poison the whole transaction; outer COMMIT
errors never return provisional success. Ordinary failures lose private causes,
while cancellation propagates. Tests include real SQL guard behavior, actual
AEAD corruption, metadata-before-hydration controls and before/after-COMMIT
error-report injection. These are not physical power-loss or OS key evidence.

The internal current-local source borrows the exact kernel writer and retains
one source/principal-object-bound view. Ordinary public/fake/wrapped views cannot
inspect protected records. It reads complete authenticated account, policy,
admission and issuance snapshots; missing/currently ineligible facts deny, while
malformed storage, AEAD or ownership/lifetime failures remain storage uncertainty
even if a policy catches them. No password record escapes this projection.

One supervised source lifetime retains trusted-current high-water across SQL
rollbacks. Normally returned observations are recorded before caller cancellation
can escape; thrown cancellation without a returned observation invents no clock
fact. Actual clock regression, boot/context discontinuity or unavailable continuity
latches the source. An old/expired issuance denies only its transaction, not other
healthy sessions. All provider callbacks finish before the final captured-fact
comparison. Source recreation under an old context is unsupported; every service
activation/recovery requires a separately qualified fresh, non-reused context.
The existing-access verifier below adds an internal PrincipalVerifier/parser;
there is still no sign-in/refresh or accepting application wiring.

**Explicit limitation:** authentic historical rows restored together with old
revisions can still pass AEAD. A regression demonstrates that limitation. This
is not disk rollback resistance, database-wide freshness/authenticity, whole
financial-database encryption or a production sales/identity integration.
Protected OS keys/custody, backup provenance, boot clocks and production
authentication/current-policy/financial composition remain separate gates.

## Existing-access authentication (D055 bounded implementation)

`RoomLocalAccessPrincipalVerifier` implements only local INTERACTIVE verification.
It authenticates EXISTING immutable issuances through real protected reads and
the real `TrustedPrincipalFactory`; it does not issue sessions, tokens or grants.
Central/device/service methods remain deny-all. Source/tests are not a claim of
execution: see the verification report for this increment's actual status.

The internal binary parser requires exactly52 bytes: ASCII `ULA1`,16 session UUID
bytes in canonical/network order,32 secret bytes. No text/Base64/JSON fallback,
trailing bytes or request-supplied actor/scope/device/grants. Scope is configured
by the service; the peer source captures the actual authenticated physical
lifetime/key. Every non-null protected admission descriptor must match that same
key's evidence; descriptor strings/capability descriptions are not authentication.

An explicit positive service-lifetime semaphore uses `tryAcquire` before copies,
providers and Room; excess work receives fixed sanitized unavailable, never a
password-throttle update. This bounds admitted work, not duration, fairness,
hung-provider handling or physical-money capacity. Normal credential/ineligibility
refusal AND D053 unavailable/latched continuity return null. Neither may be
classified as a password mismatch. Storage/hash-provider/ordinary peer failures
instead throw sanitized unavailable; cancellation propagates.

The verifier reads issuance first, then current account/credential presence and
revision, policy and admission. No password record escapes. It retains an outer
bare writer across the existing protected transaction's COMMIT, then observes
the SAME captured peer lifetime followed by the shared current environment and
pure eligibility. Only successful return of the ENTIRE outer loan permits an
identity to escape. Later Room cleanup may fail; no finalizer publishes success.
The kernel and verifier MUST use the same D053 source instance. The constructor
checks exact store ownership but cannot prove how a separately built kernel is
wired. Normally returned clock/latch evidence survives cancellation and rollback.

**Required supported-entry precondition, NOT mechanically enforced:** invoke in
an ordinary structured request coroutine outside all Room loan/transaction blocks,
without inherited/shared/retained/re-entered borrowed contexts. Authenticate,
receive the principal, then invoke the kernel separately; do not wrap both in a
caller-held loan. Room3 can reuse a bare loan in concurrent children and nest
SAVEPOINTs. `requireDurableWriter` rejects enclosing SQL transactions, not every
bare loan. The characterization test proves shared wrappers, not an experimentally
reproduced SAVEPOINT rollback escape. Changing dispatcher/Job does not prove clean
entry. Accepting composition must qualify context lineage and non-re-entry.

Independent supported callers and same-owner revocation use the sole writer.
Another pool/process is not fenced. Peer/time observations are NOT simultaneous
freshness or transport disclosure fencing; revocation can commit after loan
release but before the caller resumes. Real commands and retained-result reads
still use D053 current authorization. The source does not bind any accepting app,
qualify OS keys/physical lifetime/time/service context, implement password sign-in,
refresh, provisioning or solve coherent historical file rollback.

The injected outer-recycle-failure test exposes a pinned Room liveness limitation:
`markRecycled` can throw before releasing the writer, leaving invalidation waiting
with a close blocker so a subsequent synchronous `RoomDatabase.close()` spins.
No identity may escape that cleanup failure, but limiter recovery is NOT pool or
service recovery. The regression runs this real fault in a deadline-bounded child
JVM, requires both refusal variants and an exact post-assertion success witness,
then verifies all12 tables with a fresh Room owner in the parent after child exit. It deliberately does NOT
close the poisoned pool. Accepting service composition still needs a qualified,
bounded retirement/shutdown/restart protocol; coroutine timeout/dispatcher changes
cannot make a noncooperative close safe, and another concurrent pool is not fencing.

## Identities, positions and bounds

- Operation IDs deduplicate across epochs. Journal/operation position uniqueness
  is `(scope, epoch, sequence)`, with committed positions starting at1 per epoch.
  Results expose their **original** authority epoch and sequence. A new epoch's
  sequence1 cannot replace old epoch1/sequence1. Actual appointment/parent-checkpoint
  installation is not provided by the synthetic multi-epoch fixture.
- Store rows start at revision1. The implemented whole-cash-session adapter
  starts its domain session at0 and uses checked
  `storeRevision = domainRevision + 1` on hydration/CAS. This is not a universal
  mapping for normalized children: one branch transition can advance a child
  several domain revisions while its stored row advances only once. Such rows
  need independently checked storage generations and explicit domain metadata;
  never pass a row generation directly into a domain codec.
- Maximum stored value64KiB,128 changed aggregates **plus**128 claim changes,
 32 requested jobs, and1MiB summed result/write-payload/job-body bytes. The latter
  excludes normalized input, claim metadata and framing; each binary value is
  still separately bounded. These are kernel limits, not permission to
  truncate a larger receipt/inventory history. Normalized/paged typed storage is
  required before promising larger production workloads. Payment admission must
  reserve recording capacity for later actual-outcome evidence.
- Expiry is checked at the final validation linearization point; this is not a
  promise that fsync or response delivery finishes before a wall-clock deadline.
- General scalar instants remain wider, but command and supervisor-audit evidence
  use the explicitly checked UTC year0000..9999 evidence-v1 subset.

## Retained command facts, not current authority

`CommandReadView.retainedCommand(operationId)` is a branch-bound internal-handler
read in the existing borrowed transaction, **not a public employee result route**.
It validates the original immutable operation/journal pair and returns historical
after-writes with their actual storage generations, claim events and requested
job intents. These are not live `StoredAggregate` values or reusable proposals.
The automatic upload intent is excluded; duplicate ordinary requested jobs retain
their original order. Returned bytes/lists are defensively immutable.

Both rows absent means only **no retained pair here**. It is never authoritative
absence, a not-paid decision, an empty genesis, permission to retry, complete
history or a current resource frontier. Orphans, oversized/unsupported/corrupt
records fail closed; the kernel reports storage uncertainty without creating a
rejection or new effects. SQL metadata/type/byte-length preflight checks **both**
rows before either result/envelope BLOB is hydrated, and cannot hide an invalid
row using an inner join/length predicate. Reads recheck borrowed-view lifetime
after suspension; cancellation passes through.

The strict v1 decoder preserves the existing encoder and three independent
golden vectors:20 outer fields,4MiB framing limit, canonical scalars and exact
original-byte result/journal/semantic hashes. Text fields require strict UTF-8;
binary payloads may be empty or arbitrary. Five-field aggregate writes are
distinguished from four-field claims by arity, not a reserved kind prefix.
All operation/journal/header metadata must agree. No current handler, schema
allowlist, authorization, global head or domain transition is replayed.

Consumers must bind their exact producer/schema/current cut and companion writes
separately. Original assignment/epoch/actor/time remain original even after later
updates or actor disablement; this does not bypass current public authorization.
The journal did not store original device ID, recovery footprint or commit
deadline; the reader does not fabricate them. Matching hashes cannot establish
authentic origin, exclusive producer ownership or absence of an unknown tail.
Protected composition, import/restore and current-frontier qualification remain
required; this module supplies no accepting production implementation of them.

## Journal/outbox is not yet a central wire adapter

The local journal uses versioned, big-endian4-byte length framing and SHA256, not
JSON. Its semantic command digest differs from a backend signed content hash.
The immutable `journal.upload` intent points to
`[ukkera.journal-upload/v1, branchId, epoch, sequence, envelopeHash]` in that framing.
It is not an ACK, a worker, a financial/print action or permission to delete history.

Every durably recorded rejection, claim-only or administrative outcome also gets
a journal position. Frozen backend evidence-v1 currently admits specific nonzero
cash/stock effects, not this complete journal. Skipping other positions would break
contiguous coverage. Before wiring upload, qualify a new complete outcome/effect
contract (preferred) or an explicitly separate projection stream; never fabricate
effects or rename projection ACKs as recovery coverage. Immutable origin device,
key/catalog/authority provenance, exact signed bytes and replacement-master replay
must be sealed at the appropriate original commit boundary, not reconstructed
from current branch state. Delivery attempt/receipt/cursor state and uncertain
physical-job reconciliation remain unimplemented.

## Evidence and remaining qualification

See the [verification report](../../VERIFICATION_REPORT.md) for exact
commands, test snapshots and failures. Targeted20:57 UTC verification passed
112 JVM tests and28 Android-host decoder tests, including15 public typed cash-
handler integrations,7 recovery-barrier Room tests,14 retained-reader real-Room tests,28 common codec cases,
4 actual COMMIT-step failures,2 real test-only migrations and4 independent wire
oracles. Both Apple main klibs compiled; cash adapter33/33 reran successfully.
Source fingerprints matched before/after; schema1 bytes stayed unchanged.
The preceding full16:00 baseline had61 database JVM tests/1,132 total host cases;
the reader-only20:33 snapshot had104/28. Neither is reused to qualify the later
barrier delta. Later combined results are recorded separately in the verification report. No production handler opts into the new restriction or implements full
checkout.
Process `halt`/reopen, SQLite-full and simulated COMMIT errors are useful evidence,
**not physical power-loss, fsync/VFS or hardware qualification**. Android/iOS main
compilation is not Android instrumentation or Apple linking/execution. Single
connection performance, OS keys, native file factories, typed schemas, full recovery,
query/subscription authorization, outbox delivery and live service wiring remain
gates. Sensitive rc6 RPC stays disabled independently of database test success.
