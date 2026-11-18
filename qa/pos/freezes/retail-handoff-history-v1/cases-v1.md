# Frozen cases v1 — ordinary unpaid retail handoff history/restart

Every case and subvariant below has status **NOT_RUN**. Outcomes are semantic requirements; method names, raw enum strings, schemas, serialized fixtures, error booleans and exact revision increments require a public execution binding.

## Source references

All paths below are relative to `qa/pos/requirements/`.

| Reference | Original source and sections | Binding requirement |
| --- | --- | --- |
| O2 | `original/POS_OPERATIONAL_SPECIFICATION.md`, §2, lines 26–38 | Branch-local authentication; original creator/current owner/every handoff/actual actors; owner-filtered access; durable request ID and expected order version; owner remains until atomic acceptance; handoff does not move drawer custody |
| O8 | Same file, §8, lines 190–192 | Ordinary draft/parked retail carts do not reserve stock by default; available sellable stock excludes reservations and unsafe stock |
| B2 | `original/POS_DOMAIN_AND_CONTRACT_BLUEPRINT.md`, §§2.1–2.2, lines 31–32, 40–42, 50–54 | Stable operation identity; immutable origin/actor/content/version; tenant relationships enforced; historic actor survives rename/disable |
| B4 | Same file, §§4.2, 4.4, lines 111–115, 140–143 | Local session and pending-operation identity; scoped recovery access; offer/accept/cancel records and one atomic acceptance |
| B5 | Same file, §§5.1, 5.3–5.4, lines 272–274, 291–309 | Trusted transport supplies current identity; immutable semantic identity keeps originating actor; original durable result; caller-authorized recovery; mismatch/scope/immutable/storage failures remain honest |
| B7 | Same file, §7, lines 335–339, 343–347 | Same-master sequence continuity; stable operation/result across supported recovery; journal is immutable effects, not business re-execution; incompatible content is a conflict; honest recovery coverage |
| B8 | Same file, §8, lines 362–366 | Startup checks persisted authority/exclusive storage; coherent recovery includes users/policies/orders/transfers/results/audit/outboxes; fresh sessions without copied bearer-token resurrection |
| B10 | Same file, §10, lines 389–395 | Supported historic readers/migrations; retain original representations, policy/contract versions and stable request identity; unsupported compatibility refuses safely |
| P1 | `original/POS_COMPANY_POLICIES_AND_CATALOG.md`, §1, lines 21–25 | Activated current policy protects commands/queries; role changes do not relabel earlier sales; posted records cannot be rewritten |
| P2 | Same file, §2, lines 29–35 | Own-order/report scope; current access checks; revoked cached sensitive views; report access does not itself grant exports |
| P8 | Same file, §8, lines 144–148 | Truthful saved/unknown state; recovery of the original operation; visible acceptance/ownership; logout preserves backend work |
| A-OWN | `original/POS_ACCEPTANCE_SCENARIOS.md`, OWN-01–05, lines 30–34 | Separate branch accounts; device/account privacy; ordinary owner access; duplicate acceptance; races have one valid outcome |
| A-REL | Same file, REL-01, REL-03–04, REL-07–10, lines 131–140 | Local availability; honest partition outcome; original actor/result after restart; supported lifecycle/recovery/compatibility; atomic failure |
| A-CFG | Same file, CFG-05, line 150 | Pending central revocation differs from activated local policy; sessions/queries/subscriptions/caches rechecked on activation |
| Q | `original/POS_TEST_AND_QUALITY_PLAN.md`, §§2–5, 7, 9–11 | Independent ledger/read views; real L2/L3/L4 dependencies; platform/network profiles; command variants; cited Q-gates below |
| T | `original/POS_TEST_TRACEABILITY.csv`, OWN-01–05, REL-01/03/04/07/08/09/10 and CFG-05 rows | Scenario scope and required layer/platform/failure expansion; no historical pass inherited |
| D | `OWNER-DIRECTIVE.md`, local scope and mandatory separation | Real production/persistence/communication evidence; synthetic isolation; duplicate/concurrent/lost-reply/restart/offline/permission/session/restore variants; owner manual acceptance separate |

The assigned public business premise also binds this design:

- **PP1:** An ordinary unpaid local order offer leaves the old owner authoritative until the intended recipient separately authenticates and accepts. Cancellation retains the old owner. Acceptance changes current owner while preserving original actors and money/stock custody.
- **PP2:** Recovery/restart verifies committed immutable originals without replaying effects or inventing current employee authority.
- **PP3:** Native historical admission rejects incomplete, mismatched, foreign or corrupted original authorization or business sources; genuine old committed results survive later permission revocation, subject to current disclosure permission.

PP1–PP3 are assigned observable requirements. They do not establish any concrete native-history interface or the existence/absence of an implementation.

## Fixtures and shared assertions

Use the semantic fixtures in `fixtures-v1.json`. Every case starts from its own clean synthetic committed cut. `A` creates and owns ordinary retail order `R`; `B` is the intended same-branch recipient; `C` is a different employee. A and B have separate local credentials and separate authenticated employee contexts. The cart has two units at EGP 100.00 each: quoted payable EGP 200.00, collected EGP 0.00. It has no fulfillment, collection lock, reservation or paid/preparation state.

The stock cut is ten sellable units at cost EGP 20.00 each, total value EGP 200.00. A's drawer is EGP 1,000.00 and B's EGP 500.00, each with its own custodian. No handoff operation moves those balances or custodians.

**Invariant I0, checked from independent committed views:** quoted line identity/quantity/allocation and EGP 200.00 payable remain unchanged; collected/refunded EGP 0.00; drawers remain EGP 1,000.00 and EGP 500.00 with original custodians; sellable stock remains 10, reserved/issued/restored quantities remain 0, stock value remains EGP 200.00; no sale finalization, fulfillment, stock issue, return entitlement, preparation task or physical side-effect job is created. Existing operation/result/audit/outbox identities and immutable content remain stable. Existing pending/completed jobs are not reset or replayed; outbox delivery/acknowledgement may make authorized progress under the same original identity. Successful commands may add their required handoff/audit/result/outbox evidence once. Rejections may append authorized denial/security evidence; they do not add accepted handoff effects or change business custody.

**Invariant I1:** recovered history preserves the original creator and each actual offer/accept/cancel actor, originating operation identity, business source and immutable authorization/content/version context. Current owner is a separate projection. No current session, role, employee name, current order revision or current catalog silently replaces an original fact.

**Invariant I2:** every current disclosure/list/direct-ID/subscription/cache/export or new mutation uses the current authenticated branch identity and activated permission. Admission of genuine history does not mint a login, approval, current employee role or new command authority.

Semantic truth table (not storage row counts or wire status names):

| Committed stage | Creator | Current owner | Offer actor | Terminal actor | Accepted owner changes |
| --- | --- | --- | --- | --- | --- |
| Baseline | A | A | None | None | 0 |
| Offered to B | A | A | A | None | 0 |
| Authorized cancellation | A | A | A | Actual authorized cancellation actor | 0 |
| B separately accepts | A | B | A | B | 1 |
| Rejected attempt | Same as preceding committed cut | Same as preceding committed cut | Unchanged | No new successful terminal actor | Unchanged |

## Cases

### RHH-001 — committed offer survives restart

**Status:** NOT_RUN. **References:** O2, B2/B4/B7, PP1–2; OWN-04, REL-04; Q-DB-03/04, Q-RES-02/09.

**Fixture/actions:** From baseline R, A offers to B using a durably saved operation identity and the correct expected order version. Commit, terminate the master process, and reopen the same durable store under its still-valid role. Obtain authorized independent order, history and result views.

**Expected:** The offered transfer and its original result remain durable; creator/current owner are A; intended recipient is B; offer actor is A; accepted owner-change count is 0. B has not accepted merely because restart or reconnection occurred. I0–I2 hold. Existing outboxes are retained without reissuing the offer as a new operation.

**Layer/variants:** Complete L2+L3+L4+L5; same-device protected adapter and admitted separate-device local RPC; N0/N1/N2/N4/N5; each supported master/driver and explicit mobile reopen variant.

### RHH-002 — committed cancellation survives restart

**Status:** NOT_RUN. **References:** O2, B4/B7, PP1–2; OWN-04, REL-04; Q-DB-03, Q-RES-02/09.

**Fixture/actions:** Start with a genuine committed offer to B. Perform the public ordinary cancellation with the actor permitted by that contract, commit, lose the response, and restart. Recover the original cancellation outcome under permitted disclosure, then try an acceptance for the cancelled offer.

**Expected:** Cancellation remains terminal, current owner and creator remain A, accepted owner-change count is 0, and the actual cancellation actor remains original. The later acceptance is rejected without business mutation or reopening the offer. Recovery repeats no cancellation effects. I0–I2 hold.

**Layer/variants:** Complete L2+L3+L4+L5; N0/N1/N2/N4/N5. Binding G2 must identify the authorized cancellation actor/ordinary flow before execution; it cannot be invented from storage internals.

### RHH-003 — intended recipient acceptance and original attribution survive restart

**Status:** NOT_RUN. **References:** O2, B2/B4/B5/B7, PP1–2; OWN-02/04, REL-04; Q-SEC-04, Q-RES-02/09/10.

**Fixture/actions:** A genuinely commits an offer to B. End A's interactive context on a reused admitted device where applicable. B separately authenticates with B's local credentials, accepts using the bound offer/order version and a stable acceptance operation identity, then restarts after commit.

**Expected:** Creator remains A; current owner becomes B exactly once; offer actor remains A; acceptance actor is B. The original durable acceptance result remains recoverable; neither account switching nor reopening changes actor attribution. A's and B's drawer/stock/amount invariants hold. A's old cached ordinary access is revalidated; B's owner access does not authorize unrelated employees. I0–I2 hold.

**Layer/variants:** Complete L2+L3+L4+L5; separate devices and sequential users on one device; N0/N1/N2/N4/N5; physical mobile lifecycle remains separately unverified until run.

### RHH-004 — current caller cannot impersonate the accepting recipient

**Status:** NOT_RUN. **References:** O2, B2/B4/B5, PP1/3; OWN-01/03/04; Q-SEC-01/03/04/06/09.

**Fixture/actions:** Against a genuine pending offer to B, independently attempt acceptance with (a) no employee authentication, (b) device admission only, (c) A's session plus claimed B identity, (d) C's session plus claimed B identity, (e) B's expired/revoked session, and (f) the same human's separately scoped other-branch account. Exercise the actual direct local boundary, not only unavailable UI controls.

**Expected:** Each invalid caller is denied; the offer remains pending for B, current owner remains A, accepted owner-change count is 0. No current session/role is invented from original history, caller fields or device admission. No private order data is disclosed in the denial. I0–I2 hold.

**Layer/variants:** Complete L2+L3+L5 plus L4 account/privacy observations; N1/N2/N4/N5/N7. Each invalid fixture is separate; a previously valid result is not deleted by denial.

### RHH-005 — stale or conflicting lifecycle input cannot rewrite history

**Status:** NOT_RUN. **References:** O2, B2/B4/B5/B7, PP1–3; OWN-04/05; Q-RES-01/02, Q-SEC-09.

**Fixture/actions:** Attempt a fresh acceptance with an obsolete expected order version; separately attempt fresh acceptance of a cancelled offer and fresh ordinary cancellation of an already accepted offer. Where a normal handback is supported, it is a separate new operation outside this terminal-record mutation attempt. Read original committed outcomes after restart.

**Expected:** An invalid stale/lifecycle transition is rejected without partial mutation. Cancelled history keeps A owner and 0 owner changes; accepted history keeps B owner and 1 owner change. Earlier offer/accept/cancel originals remain unchanged. An exact duplicate operation is evaluated as original-result recovery, not as a fresh terminal transition (RHH-006). I0–I2 hold.

**Layer/variants:** Complete L2+L3+L5; N4/N5; documented version-conflict behavior required by G2, with no invented numeric increment policy.

### RHH-006 — duplicate/reordered delivery and changed payload across restart

**Status:** NOT_RUN. **References:** O2, B2/B5/B7/B10, PP1–2; OWN-04, REL-04/08; Q-DB-03, Q-RES-02.

**Fixture/actions:** Independently for offer, authorized cancellation and B acceptance, repeat the same stable operation and original semantic payload after lost reply/reconnect/restart. Also attempt the same operation identity with changed target order, intended recipient, originating actor, expected version or semantic payload. Reorder duplicate transport deliveries without replacing durable operation identity.

**Expected:** An authorized exact duplicate recovers one original outcome and original actors with no new business effects or accepted owner transition. A changed semantic payload/origin under the same identity is rejected as a conflict; the original result remains genuine. Legitimately changed connection/session routing is not itself new semantic content. Another employee needs an authorized read/recovery route, not adoption of A's command identity. I0–I2 hold.

**Layer/variants:** Complete L2+L3+L5; N0/N1/N2/N3/N4/N5; supported compatibility-reader variant under B10, with immutable original representation retained.

### RHH-007 — older committed stage remains history after later lifecycle changes

**Status:** NOT_RUN. **References:** B2/B5/B7/B10, PP2–3; OWN-04, REL-04; Q-RES-02/09.

**Fixture/actions:** Produce offer→accept and, separately, offer→cancel as genuine committed histories. Restart. Recover/admit the original offer outcome as well as the later terminal outcome using their original immutable sources and a currently permitted disclosure context.

**Expected:** The original offer remains an offer authored by A to B at its original bound context; it is not replaced with a result freshly evaluated against the later revision/current owner. Its recovery does not make the current offer pending again or change the terminal state. Accepted branch: creator A/current B/one owner change. Cancelled branch: creator A/current A/zero owner changes. All original terminal actors/content survive. I0–I2 hold.

**Layer/variants:** Complete L2+L3+L5; supportive history/admission slice allowed only for its boundary; N4/N5/N7; old supported immutable representation under B10.

### RHH-008 — genuine committed history survives revoked original permissions

**Status:** NOT_RUN. **References:** B2/B5/B7/B10, P1/P2, PP2–3; CFG-05, OWN-02/04, REL-04; Q-SEC-06/09, Q-RES-09/10.

**Fixture/actions:** Genuinely commit each of offer, cancellation and acceptance in separate fixtures while its original actor is authorized. Activate a later permission revocation, and separately disable/rename that original employee. Restart. Verify/admit the committed originals. Request disclosure through a presently authorized route; also request fresh ordinary work with the revoked account/permission.

**Expected:** Genuine committed originals and their actors are retained and remain valid historic facts; current revocation does not retroactively invalidate/rewrite them. A permitted current recovery caller receives only its authorized original-result projection. Fresh work without current authority is denied. A disabled/renamed employee retains stable identity and appropriate historic display snapshot. No session, collector custody or current employee authority is resurrected. I0–I2 hold.

**Layer/variants:** Complete L2+L3+L5 with L4 privacy checks; N1/N2/N5/N7. Current disclosure behavior is a separate gate, not evidence that the old action had original authorization. G3 binds the permitted route; the case does not grant a new role.

### RHH-009 — incomplete original authorization is not repaired by current privilege

**Status:** NOT_RUN. **References:** B2/B4/B5/B7, PP2–3; OWN-01/04, REL-04/09; Q-DB-02/10, Q-SEC-09, Q-RES-09.

**Fixture/actions:** Use disposable negative variants of genuine committed histories whose bound original authorization source is absent, truncated or incomplete for the original actor/branch/action/order/version. Present them for native historical admission/recovery while a current employee has otherwise sufficient permission. Keep the genuine control copy intact.

**Expected:** Incomplete history is excluded from accepted authoritative history/recovery and reports a safe integrity/recovery exception. Current privilege cannot fill missing original proof or invent original authorization. No accepted owner transition or custody change is produced from the bad variant; the genuine control copy still recovers its original outcome. I0–I2 hold for accepted state.

**Layer/variants:** Required production native admission/persistence L2+L5, complete recovery/read boundary L3 where exposed; supportive history slice is narrower. Repeat for offered/cancelled/accepted originals and all supported native readers. G4 must bind incompleteness and exception presentation; no raw boolean is presumed.

### RHH-010 — mismatched, foreign or corrupted original authorization fails admission

**Status:** NOT_RUN. **References:** B2/B5/B7/B8/B10, PP2–3; OWN-01/04, REL-04/09; Q-DB-02/10, Q-SEC-09, Q-RES-09/10.

**Fixture/actions:** In separate disposable variants, bind the original authorization to another actor, action, order/version, intended recipient or original operation; substitute a foreign branch/company/organization authorization source; invalidate its required integrity evidence; or use an origin authority outside the verified historic cut. Present the variants under a valid current local caller.

**Expected:** Each semantically mismatched/foreign/corrupted authorization is rejected from accepted history. Current caller authority, a plausible order ID or a newly activated permission cannot legitimize it. No accepted transition, synthetic actor, automatic fresh handoff or replayed business effect is created. Genuine controls retain original results. The exact integrity-evidence representation remains G4; an approved compatible recovery of genuine historic evidence is not foreign merely because current routing differs. I0–I2 hold.

**Layer/variants:** Production native admission/persistence L2+L5 and exposed L3 recovery; N5 plus historic-reader compatibility; supportive slice alone cannot prove durable restart or employee authorization.

### RHH-011 — original business source must agree with genuine handoff history

**Status:** NOT_RUN. **References:** B2/B4/B5/B7/B10, PP1–3; OWN-01/03/04, REL-04/09; Q-DB-02/03/10, Q-SEC-09, Q-RES-09.

**Fixture/actions:** For each stage, separately remove/truncate the necessary original order/offer/terminal business source; substitute another order or foreign tenant/branch source; mismatch creator, previous owner, intended recipient, actual terminal actor, originating operation or bound original version; or corrupt original immutable content/integrity. Include coherent accepted A→B and coherent cancelled A→A controls.

**Expected:** Invalid source combinations are excluded from accepted authoritative history/recovery; a valid-looking result or valid current employee does not supply missing business facts. No partial owner transition or fabricated original snapshot is admitted. Complete coherent controls are accepted as historical facts with exactly the truth-table owners/actors and I0. Current order projection is not mistaken for the immutable original source. I1–I2 hold.

**Layer/variants:** Production native history/admission+persistence L2+L5 and exposed L3; complete process restart required for durable-recovery claim. G4 binds the representation and atomic failure behavior without prescribing a fixture-injection API.

### RHH-012 — historic admission does not bypass current disclosure isolation

**Status:** NOT_RUN. **References:** O2, B4/B5, P1/P2/P8, PP2–3; OWN-01/02/03, CFG-05; Q-SEC-04/06/08/09, Q-NAV-07.

**Fixture/actions:** Recover genuine accepted and cancelled histories. Request current list/direct-ID/history/result/subscription/cache/export projections as the currently permitted employee, an unrelated same-branch C, a foreign account, no session, and a revoked/expired session. Switch users on one device; activate a disclosure revocation while a sensitive view/stream is open; restart before reopening it.

**Expected:** Current permitted callers receive only their permitted projection. Other callers are denied or filtered without private data; cached/streamed sensitive data is invalidated on activated revocation and account change. Tokens/private UI from the preceding employee are cleared; master history remains genuine and attributed to original actors. No generic historical admission grants ordinary order browsing or exporting. Existing accepted/cancelled state and I0–I2 remain unchanged.

**Layer/variants:** Complete L2+L3+L4+L5; N1/N2/N3/N5/N7; all actual public read surfaces need G1/G3 binding. Pending central policy intent alone does not pretend to be activated local revocation; central delivery verification remains held.

### RHH-013 — interrupted command or recovery has no partial business success

**Status:** NOT_RUN. **References:** B4/B5/B7/B8, PP1–3; OWN-04, REL-04/10; Q-DB-03/04/06/10, Q-RES-02/09.

**Fixture/actions:** For offer, ordinary cancellation and B acceptance independently, fail before commit and after commit/before response, including process termination and selected storage failures. Restart from a fresh process/connection. Separately interrupt validation/import of a bad historical copy while an existing genuine cut remains usable.

**Expected:** Before business commit: no committed success result or successful business transition; preceding truth-table state remains. A truthful rejection/recovery record may exist without accepted effects. After commit: one original result and its complete handoff/audit/outbox state recover; truth-table state advances only once. A client missing the reply reports uncertainty until authoritative recovery, not invented success/failure or a new operation. Bad-copy recovery never overwrites the existing good cut or enables a partial store. I0–I2 hold.

**Layer/variants:** Complete L2+L3+L5; N3/N4/N5. Actual process kill versus real device shutdown are separate variants; simulator/process results do not prove power-loss durability.

### RHH-014 — ordinary unpaid competing transitions have one valid serialized outcome

**Status:** NOT_RUN. **References:** O2, B4/B5, PP1; OWN-05; Q-RES-01/02, Q-DB-03.

**Fixture/actions:** Against one committed offer to B, schedule B acceptance versus authorized cancellation with the required original versions. Separately deliver duplicate acceptance concurrently and compete two different eligible ordinary handoff attempts from the same owner/version. Save deterministic schedules; inspect committed history and current owner after restart.

**Expected:** Acceptance/cancellation competition ends in one permitted terminal state: accepted A→B with one owner change, or cancelled with owner A and zero changes. Both terminal effects never coexist for that offer. Concurrent duplicate acceptance has one original accepted result and one owner change. Competing distinct offers/transitions obey the bound version/lock policy with no competing accepted owners, no silently erased originals and I0–I2. No paid/collection race is included in this assigned slice.

**Layer/variants:** Complete L2+L3+L5; N4/N5. G2 binds the documented valid outcome set for competing distinct offers; this case does not invent queue priority, timeout or automatic winner selection.

### RHH-015 — coherent supported local recovery preserves history without replay

**Status:** NOT_RUN. **References:** B2/B7/B8/B10, PP2–3; REL-04/07/08/09; Q-DB-02/08/09/10, Q-RES-08/09/10/13.

**Fixture/actions:** Recover offered, cancelled and accepted histories from a supported coherent synthetic local durable cut, including retained operation/results, original authorization/business sources, policy/accounts, audit/outboxes and custody. Use the supported historic reader/compatible local recovery path; separately present incomplete/corrupted/incompatible cuts. Obtain fresh current employee authentication before protected reads/new work.

**Expected:** A complete compatible cut retains original IDs/content/actors, truth-table owner and I0; it verifies history and resumes existing pending/completed work without replaying handoff, cash, stock or physical effects. Old bearer tokens and current authority are not reconstructed from history. Incomplete/corrupt/incompatible cuts remain explicit recovery/compatibility failures, do not invent an unavailable terminal outcome, and preserve the prior usable cut. Coverage gaps are disclosed. Current authorization still gates reads and subsequent work.

**Layer/variants:** Complete L2+L3+L5 with applicable physical L6 lifecycle/restoration observation; N5 and the prepared-local-copy subset of N6. G5 binds supported local recovery/compatibility. Central recovery, replacement appointment and the broader master-transfer workflow are outside this design and remain held/unverified.

## Execution expansion and rollup

For every applicable case retain separate records for N0 (healthy), N1 (WAN unavailable, LAN/master healthy), N2 (WAN unavailable and no optional standby), N3 (client/master partition where relevant), N4 (duplicate/reorder/lost reply/concurrency), N5 (restart/storage/lifecycle), and N7 (locally activated policy changes). A disconnected client preserves its original pending evidence and cannot claim a committed owner change from its cache. Reconnect revalidates the employee and recovers the original operation before proposing fresh work.

Use each supported production SQLite driver and each required master platform: Windows, macOS, Android, physical iPhone and physical iPad. Test the corresponding admitted client or protected same-device path; critical restart/account-change variants cannot be replaced by a JVM-only or simulator-only result. Wider platform pair selection needs a documented risk basis under Q §4.3. Hand-off-only evidence does not satisfy the complete 25-pair sale/return/close qualification or all 141 scenarios.

Assertions inspect independent committed history/result/order/custody/stock views, not only a successful response. Each run records frozen version, actual bound public contract, fixture cut, candidate revision, platform/driver, authenticated identities without secrets, fault schedule, observations, outcome and gaps. The executor may bind these semantic expectations to production; it may not weaken them to match an implementation. No run records, tested revision or passed layer exist for this design.
