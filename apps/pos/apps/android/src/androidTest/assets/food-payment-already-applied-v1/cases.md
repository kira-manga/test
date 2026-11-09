# Frozen cases — FOOD CASH already applied, v1.0.0

Every case/variant is **NOT RUN**. Source abbreviations are resolved in
[source-manifest.json](source-manifest.json). Citations refer to original scenarios
and source sections, not existing tests or implementation behavior.

## Prerequisites and common assertions

`FAA-F01` requires a genuine native CASH original accounted once, and a genuinely
prepared/committed ordinary `ApplyOriginal` outcome classified `AlreadyApplied`.
The latter must be the source under observation. Replaying an earlier `Applied`
result does not establish it. Real production preparation/commit is required at
the integration layer; its public setup binding is unknown (`FAA-Q02`). This is
not permission to add an action, choose a replacement identity, or manufacture a
physical original. Read/disclose the recorded ordinary outcome or recover its
existing operation; never execute again merely to make it reachable.

Before each case independently qualify and pin the original branch, actor,
operation, command/version, canonical body/digest, order, collection, collecting
cash session, attempt and recording identity, plus genuine source observations.
Use exact posted facts, independently observable ledgers and fresh persistence
connections. Internal revision numbers and wire encoding are not prescribed.

The common no-effect oracle is `fixtures.json:all_post_accounting_actions`:

- Original cash received EGP 500 less change EGP 200 is **EGP 300**. Drawer A remains
  **EGP 1,300**, drawer B **EGP 700**, coverage **EGP 300**, remaining payable **0**.
  The original has exactly one accounting effect and one net EGP 300 cash movement.
- Every observation, duplicate, recovery and refusal adds **zero** physical
  captures, money movements, refunds, sale finalizations, ingredient issues or
  reversals, reservations, kitchen tasks, or original recordings. Preserve the
  existing stock/reservation state and posted allocation values exactly.
- Preserve original actors, custody, command identity and unresolved evidence.
  A denial is not proof of physical reversal or absence. Permitted security audit
  and durable client result remembrance are separate from business effects.
- Protected disclosure checks current Master employee, admitted device, session,
  activated policy, `CASH_READ`, current original-recovery access and source-bound
  original disclosure rights. Cached grants, notifications/ACKs and today's owner
  cannot substitute. Refusal has no private original payload or leaked amounts.
- An unavailable observation is neither a successful empty/zero result nor
  writer-qualified absence. Match semantic outcome classes; do not require
  incidental error wording. An opaque `UNAVAILABLE` may be valid.

Sources: C §§Financial behavior (49–63), Journal (67–81), Failure (85–96);
B §§2.1–2.2, 4.2, 4.6–4.7, 5.1–5.4; Q §§2, 5–7.

## Cases

### FAA-001 — Genuine ordinary AlreadyApplied source, issued food

**References:** PAY-03/PAY-04, KIT-01/KIT-12, REL-04; Q-DB-03/Q-RES-02;
C §§Financial behavior (49–54), Journal (75–81); B §§4.6, 5.2–5.3.

**Fixture/action:** Qualify F01's ordinary AlreadyApplied source and current full
rights. Observe/disclose it through its supported payment source and recover the
same recorded operation through `pos.food-cash-financial.lookup`. Repeat the
read/recovery once with the same identity.

**Expected:** It is an original already-applied observation, not a fresh payment
or synthesized native fact. Exact authorized original identity and EGP 300 cash
coverage are preserved; sent stock stays **bread 8, meat 1,200 g**, reservations
**0**, with the existing two task/issue allocations. All common no-effects hold.
The exact public result projection is unresolved in Q01; an earlier Applied
carrier replay alone cannot pass this case. **Layers:** L1 and L2/L3; L4 recovery
presentation; N2/N4.

### FAA-002 — AlreadyApplied preserves paid-unsent reservations

**References:** KIT-15, KIT-12, PAY-04; Q-DB-03/Q-RES-02;
C §§Financial behavior (44–54), Journal (71–73).

**Fixture/action:** Use F02, qualify its ordinary recorded AlreadyApplied source,
then perform the same observations and original lookup as FAA-001.

**Expected:** Bread on hand **10**, meat **1,500 g**, reservations **2 bread/300 g**,
available **8 bread/1,200 g**, issued portions/tasks **0**. No release, duplicate
reservation, Send, stock issue, finalization or charge. Money remains F01's exact
baseline. **Layers:** L1 and L2/L3; N2/N4.

### FAA-003 — Master-current CASH_READ governs original disclosure

**References:** OWN-03, CFG-05/CFG-07; Q-SEC-06;
C §§Fixed scope (26–40), Financial behavior (52–63); P §1 (21–23).

**Fixture/action:** Starting with F01, separately remove Master-current
`CASH_READ`, disable employee A, expire/revoke the requesting authentication
session, or retire admission of the requesting device. Keep Client cached grants
and any notification ACK. Attempt the ordinary retained-result disclosure,
payment read /1 Own AttemptRecording, Original and OwnPending, and original lookup.
Repeat missing `CASH_READ` while `COLLECTION_ATTEST` remains granted.

**Expected:** Each applicable protected path refuses private disclosure and
preserves all common no-effects. `ORDER_READ`, attest permission and ACKs do not
grant original access. Re-established current access permits only the qualified
original projection; an empty page is not a successful substitute. **Layers:**
L1, L2/L3 and L4 cache/session isolation; N2/N7.

### FAA-004 — Original recovery and source rights are independent gates

**References:** OWN-01/OWN-02/OWN-03/OWN-06/OWN-10; Q-SEC-04/Q-NAV-07;
C §Journal (75–81); B §§4.2 (113), 5.3 (297).

**Fixture/action:** With `CASH_READ` retained, independently use no current
original-recovery access, no source-bound rights, today's owner B without an
original-scoped grant, and a generic supervisor. Then exercise the qualified
original-account route and explicit scoped supervisor recovery route with their
current required rights. Do not turn supervisor recovery into an ordinary Own read.

**Expected:** Negative variants disclose no original. Qualified recovery remains
attributed to **A/cash session A**, never B/supervisor or drawer B; supervisor
recovery has scoped audit. No adoption, cash transfer or broader order/receipt
access. Apply the common no-effects. Exact supervisor projection/admission binding
remains Q01/Q03. **Layers:** L1, L2/L3, L4; N2/N7.

### FAA-005 — One-field identity substitutions never adopt the original

**References:** OWN-01/OWN-03, REL-04/REL-08; Q-RES-02/Q-SEC-09;
C §§Journal (67–81), Failure (85–91); B §§2.2, 5.1–5.4.

**Fixture/action:** For exact-original read/recovery independently substitute one
of order, collection, collecting cash session, attempt, recording ID, originating
actor or operation with `wrong_identity`; use `other_branch`/`other_company` for
scope substitutions. Independently change command version from **1** to **2**, or
change one supported canonical body selection away from the pinned original under
the same operation. No cash/physical-fact DTO field is invented. Submit only through
the publicly supported binding; exact body selection/bytes/digest are Q04.

**Expected:** Identity conflict, access refusal, or unsupported contract as
appropriate; never successful original adoption/private disclosure. No new
operation, original, charge, partial mutation or rollback. The genuine record and
common baseline remain intact. **Layers:** L1, L2/L3; N4/N5.

### FAA-006 — Reconnect changes credentials, preserves business identity

**References:** CASH-06, OWN-02, REL-03/REL-04; Q-RES-04/Q-SEC-09;
C §Journal (67–78); B §§2.1, 5.1 (272–274).

**Fixture/action:** Lose the connection after the ordinary source/result was
recorded. Reconnect A through admitted device B and a fresh authorized
authentication session. Lookup the saved original operation without re-stage,
reprice, refresh preparation, native Start, Submit or execution. Repeat reordered
and duplicate lookups with changed transient transport request identifiers.

**Expected:** Current credentials are checked; only the same original identity,
body/digest and recorded outcome may be disclosed. The new authentication session
does not replace the original collecting cash session. No money moves between
drawers. During partition retain unknown/pending evidence, without independent
Client financial completion. All common no-effects hold. **Layers:** L2/L3/L4,
L5 network overlay; N2/N3/N4.

### FAA-007 — Lost result and acknowledgement retain one outcome

**References:** PAY-04, CASH-08, REL-04/REL-10; Q-DB-03/Q-RES-02;
C §Journal (67–73), §Failure (93–100); B §§5.2–5.3.

**Fixture/action:** At the genuine ordinary prerequisite's Stage/Submit/result
boundary, separately lose the response after durable Master settlement, lose the
connection after validating a terminal result but before application ACK, and
duplicate/drop the ACK. From a fresh Client connection recover the saved operation.

**Expected:** Review/Stage preceded separate Submit; validated terminal result
is durably remembered before application acknowledgement. Recover the one ordinary
recorded outcome when qualified; uncertainty retains the original and offers
lookup, never another charge/identity. No durable-result loss or common business
effect. Application ACK does not prove durable acceptance before transport Receipt;
that ordering needs its own qualified evidence and cannot be marked passing here.
**Layers:** L2/L3/L4, L5 fault overlay; N4/N5.

### FAA-008 — Real close/reopen retains original and rechecks authority

**References:** REL-04/REL-07/REL-08/REL-10, CASH-06; Q-DB-02/Q-DB-04/Q-RES-09;
C §§Journal (75–81), Failure (93–96); O §§2, 4.

**Fixture/action:** Separately terminate/reopen Client and same Master after the
ordinary result is durable, using actual production native storage. Open a fresh
persistence connection; authenticate currently admitted A and lookup the same
operation. Repeat with `CASH_READ` revoked while offline, then explicitly restored.

**Expected:** Original operation/fact/actor/custody and pending recovery survive
the supported restart. Disclosure honors today's activated rights; revocation
does not erase the durable original. Restoration returns the same original, no
second accounting or task. A missing source stays unavailable. A process/in-memory
simulation cannot qualify native close/reopen. **Layers:** L2/L3/L5, L6 lifecycle
campaign separately; N5/N7.

### FAA-009 — Policy/disclosure race preserves committed evidence

**References:** CFG-05/CFG-07, PAY-04; Q-SEC-06/Q-RES-01;
C §Financial behavior (59–63); P §1 (23).

**Fixture/action:** Use barriers to activate removal of `CASH_READ` or the scoped
original-recovery grant before protected disclosure. Separately activate after
the ordinary outcome is committed but before it is disclosed. Compare a removal
package that is still pending against one atomically activated by Master.

**Expected:** If revocation wins the applicable protected check, private payload
is withheld. Later revocation never reverses original EGP 300 money or stock.
Pending intent alone does not replace the active policy. Fresh authorized recovery
after explicit restoration preserves the original and all common no-effects.
No notification delivery/ACK acts as a grant. **Layers:** L1, L2/L3/L5; N4/N7.

### FAA-010 — Missing authentic original-before source is unavailable

**References:** PAY-04, REL-04/REL-09/REL-10; Q-DB-06/Q-RES-09;
C §§Journal (75–81), Failure (85–91); B §§5.3–5.4, 7 (337–339).

**Fixture/action:** Independently make the genuine original-before source
unavailable while today's post-accounting order/collection remain readable, make
the native original absent, provide a mismatching source tuple, or expose only an
unsupported original refusal source. Also lose transport without a writer-qualified
observation. Request disclosure/recovery with otherwise current rights.

**Expected:** Unsupported/unqualified sources cannot establish the original:
unavailable/deferred, no successful empty/zero, reconstructed fact, accepted success
ACK, fresh application, replacement identity or erasure of retained unresolved
evidence. Transport loss is not authoritative absence. Preserve all common
no-effects and any genuinely retained source. Which native sources qualify as
authentic original-before observations is Q01, not a prescribed loader algorithm.
**Layers:** L1, L2/L3/L5; N3/N5.

### FAA-011 — Request lifetime loss does not cancel native original

**References:** OWN-02, CASH-06, PAY-04; Q-SEC-04/Q-NAV-07;
C §§Journal (75–81), Failure (93–96); B §§3, 4.2.

**Fixture/action:** Cancel the requesting coroutine or close its remote connection
while its genuine native original/result is retained. Clear the old user's private
presentation, switch to B, then re-establish A's authorized recovery path.

**Expected:** Native original remains tied to its genuine native lifetime and
unchanged source identity; requester disappearance creates no replacement capture,
rollback or deletion. B sees no A original/private amounts. Authorized A recovers
only the qualified original; an unsupported retained source remains unavailable.
All common no-effects hold. **Layers:** L1 controlled lifetime checks, L2/L3 actual
native-source and transport checks, L4 user isolation; N3/N5.

### FAA-012 — Today's price, owner and session cannot replace the source

**References:** OWN-05/OWN-06, CASH-06, KIT-12, REL-08; Q-RES-02/Q-SEC-04;
C §§Financial behavior (49–54), Journal (71–81); O §2 (34–36).

**Fixture/action:** F03 qualifies a separately authorized supervised transition,
preserving authentic original sources. Today's owner is B; current price is
EGP **200** per portion and recipe **175 g** meat. Observe/recover A's recorded
ordinary AlreadyApplied outcome through A's current original-scoped rights. Repeat
using only B's current owner rights, and with original-before sources missing.

**Expected:** Qualified recovery preserves original payable/net collection
**EGP 300**, original recipe issue **300 g** meat, A's recording/cash session and
drawer balances. It cannot reconstruct a new EGP 400 quote or 350 g issue, retag
custody, refresh preparation or repeat execution. B-only access refuses; missing
sources follow FAA-010. All common no-effects hold. **Layers:** L1, L2/L3/L4;
N2/N7.

## Layer and platform variants

L1 qualification covers only its exercised business/source-disclosure boundary.
L2 must use genuine production persistence and fresh reads, L3 the protected
same-device adapter and separately real admitted `FOOD_CASH_FINANCIAL` RPC on an
actual original remote connection. An attached FOOD_CHECKS profile cannot be
silently upgraded. L4 observes existing recovery/permission presentation without
adding a new action. L5 supplies deterministic faults/races with explicit barriers
and bounded waits. L6 is the separately tracked actual device/native lifecycle campaign.

For applicable cases run N2 (no WAN/central/standby) plus the listed N3–N7 variants.
Run each required production SQLite driver and supported master/client platform:
Windows, macOS, Android phone/tablet, iPhone, iPad. Record exact OS/minimum versions,
roles, network, public-source qualification, prepared revision and actual results.
Payment uncertainty, account changes and lifecycle require explicit variants;
simulator evidence never replaces physical iPhone/iPad qualification. No fresh
remote physical cash or transport-Receipt qualification is claimed by this suite.

Sources: Q §§3–5, 7, 9–11, 14, 16; C §Failure (93–101); D §Test-design separation;
V implementation/deferred-runtime directive. Missing required non-device paths
remain integration work; unrun device/owner tests remain separate acceptance gaps.

## Public-contract questions — unknown, without guessed expectations

- **FAA-Q01:** What public retained-result projection and native source
  qualifications establish an ordinary AlreadyApplied observation, including
  authentic original-before order/collection/session sources and supported original
  refusal sources? C requires source-bound identity and fail-closed unavailability,
  but does not define that projection or qualification binding.
- **FAA-Q02:** How is a genuinely prepared/committed ordinary AlreadyApplied source
  provided through the supported production contract, distinct from an earlier
  Applied carrier replay? Require it as a prerequisite; do not invent a new UI
  action, alternative operation identity or repeat-execution policy.
- **FAA-Q03:** What is the complete current permission binding for ordinary
  AlreadyApplied disclosure over execute versus Original/OwnPending reads and
  original lookup, including scoped supervisor recovery? `CASH_READ`, current
  original-recovery access and original-source rights are mandatory where stated.
  The inputs do not fully enumerate minimum rights or decide whether an existing
  execute binding additionally checks `COLLECTION_ATTEST`. No broader grant or
  unauthorized disclosure is prescribed; no-effects remain mandatory.
- **FAA-Q04:** What public versioned canonical ApplyOriginal/form/digest encoding
  binds the exact immutable business tuple? These inputs name identities and
  continuity rules, not byte-level fixtures. Bind faithfully to an independently
  qualified public format or mark that variant unsupported; never derive expected
  digest bytes from the implementation being tested.
