# Frozen FOOD CASH cold-recovery cases

Version **1.0.0**. All cases and variants are **NOT RUN**. Requirement aliases
and exact fixture calculations are defined in [README.md](README.md); stable IDs,
times and DATA vectors are in [fixtures.json](fixtures.json). No executor may
weaken these inputs, assertions or expected outcomes to fit an implementation.

## Common assertions

- **ORIGINAL:** I/O retain their whole original identity, amount/currency/tender,
  document/payable revision, originating employee/device/presentation, requirement
  and observation content, original authority evidence and timestamps. A is
  separately linked to them and retains its original financial result. Current
  state, a new session and current catalog never overwrite those original facts.
- **CURRENT:** A new protected V3 read uses one actual current Master observation
  and the complete order's native-selected price/portion/stock relationships.
  Original quoted total is explicitly historical. Known current amount is exactly
  the complete uncollected obligation; null in the excluded states is unavailable,
  never zero, paid proof or permission to collect. No fallback from selected V3.
- **EFFECTS:** Inspect independent durable order, money, cash, stock, task, receipt,
  audit and outbox views. Retry/reopen/lookup do not create a second application,
  cash movement, sold quantity, tax posting, reservation, issue, kitchen ticket or
  receipt. ACK/job state can progress independently without replaying business.
- **AUTHORITY:** Before new instruction/application or protected disclosure, use
  current Master employee/session/device/owner/action/amount-scope authority and
  authentic branch-applied activity evidence as applicable. Cached grants and
  notification/transport ACKs provide none of these permissions.
- **COLD:** The owner actually closes/reopens the relevant installed app. Record
  whether the native Master or originating Client storage owner actually stopped.
  A fresh native owner/connection must recover durable originals for a cold claim.
  UI-only closure with a surviving backend is a separate warm-UI observation.
  No service-stop/retirement bypass, new master, WAN login or new financial ID.
- **REFUSAL:** No rejected request or failed source lookup modifies a retained
  original, publishes a new physical action, applies money/stock or leaks private
  results. Missing evidence is not not-performed; no guessed historical/current
  amount, accepting null, rekeying or lower-version request hides the failure.

Generated native IDs/revisions are literal first-operation captures, checked
against the numeric/state oracles before being used for later equality. They are
not fabricated expected source views. Each case starts from its own synthetic
fixture reset. Network baseline is N2 unless a case adds a named variant.

## FCC-R-01 — complete uncollected Restaurant state survives actual reopen

**Status:** NOT RUN. **Layers:** L2/L3 and L4/L6 separately.
**Sources:** OPS §§3, 5, 6.1; CURRENT-V3 all sections; PHYSICAL-V1 recovery;
ACTIVITY Current authority/Original evidence.
**Scenario/gates:** KIT-01/02, TAX-03/04, REL-01/04/07;
Q-DB-02/03/04, Q-RES-09, Q-SEC-02/06, Q-UX-01/02.

Use R with two saved burger portions. Send both once; portion R1 becomes
Ready/Served, R2 becomes Preparing/NotHandedOver. Both remain wholly uncollected.
At fixture 18:18, select V3 page size 1, then actually close/reopen and repeat the
same currently authorized protected read. Keep business work quiescent during
this close; no artificial current-history object is provided.

Expected before and after reopen: two complete portions, the same actual order
revision and original batches; R1 Ready/Served, R2 Preparing/NotHandedOver;
Unpaid; historical quote 22,800 minor; current unpaid **22,800**, even when only
R1 is displayed. Net 20,000 and tax 2,800 are preserved. Bread 8, meat 1,200 g,
reserved zero; original two task/issue identities; no second send. Paid coverage,
cash received/change/applied movement and sale finalization are zero; drawer
ledger 100,000. Reads/reopen do not start R2, serve it, reserve stock or collect.
Repeat with the latest catalog changed only after the saved quote: it cannot
reprice the historical batches or the genuine current obligation.

## FCC-R-02 — genuine Restaurant CASH, paid-state reopen and exact originals

**Status:** NOT RUN. **Layers:** L2/L3, L4/L6.
**Sources:** OPS §§4.1–4.2, 5, 6.1, §7 lost-reply row, §§9–10;
DOMAIN §§4.6–4.7, 5; PHYSICAL-V1 interaction/financial rules;
CURRENT-V3 collection-locked/paid boundary; ACTIVITY current/original authority.
**Scenario/gates:** CASH-01/03/06, KIT-12, PAY-03, TAX-04/09,
REL-01/04/07, UX-06; Q-DB-03/04, Q-RES-02/09, Q-SEC-04/06,
Q-UX-01/02/04/05.

Finish ordinary service of R2, then the authorized original cashier begins full
CASH collection for 22,800 against the pinned payable version. Verify genuine
durable I before display at 18:21. I alone leaves money received/applied zero and
drawer 100,000; current collection is locked and V3 amount null. The operator
physically receives 30,000 and gives 7,200 change once at 18:22. Capture original
successful O; obtain the genuine durable A at 18:22:03 and finalize the paid
check at 18:23. Close/reopen at 18:24/18:25. Perform exact original lookup of I,
O and A under the original operation identity and a current permitted recovery
session; separately read the actual current check/cash/stock.

Expected: immutable I/O and A retain their original literal identities, employee,
device, cash session, requirement/content and distinct original timestamps.
Current check is Closed, collection Paid, both portions Ready/Served, remaining
obligation zero; V3 current amount **null**, historical quote 22,800. Paid coverage
22,800; one net drawer effect +22,800, received 30,000, change 7,200, drawer
122,800. One finalized sale/receipt with two sold portions, net 20,000/tax 2,800;
no refund or outstanding money. Bread 8/meat 1,200 g and the original issue/task
identities persist. Recovered history clearly states the recorded observation
and application in both languages and offers no new take-cash instruction.
Original lookup returns the original result, not today's Closed projection
substituted for the original collection result.

## FCC-C-01 — prepaid Café CASH and paid/unsent reservation survive reopen

**Status:** NOT RUN. **Layers:** L2/L3, L4/L6.
**Sources:** OPS §§4–6.1, 9–10; POLICY §6; PHYSICAL-V1 interaction/financial;
CURRENT-V3 unavailable states; ACTIVITY Drafts and kitchen capability.
**Scenario/gates:** CASH-03/06, KIT-15, PAY-03, TAX-09, REL-01/04/07;
Q-DB-02/03/04, Q-RES-02/09, Q-SEC-02/06, Q-UX-01/02/04.

Use C: two small lattes, genuine enabled CAFE activity and available kitchen Send
capability. Before collection, current quote/payable is 11,400; on-hand/available
beans 360 g and milk 2,000 ml; reserved zero. Authorized Begin collection pins
11,400 and reserves 36 g beans/400 ml milk before I instructs collection.
Receive 20,000 and actually give 8,600 change once; preserve successful O and
genuine A, and finalize the covered paid sale once while preparation remains
pending. Do not Send yet. Actually close/reopen at 08:12/08:13; exact original
lookups and a separately authorized current read follow.

Expected paid/unsent snapshot before/after: Paid, portions NotSent/NotHandedOver
with explicit pending preparation; historical quote 11,400, current V3 **null**;
coverage 11,400, remaining obligation zero. Beans on-hand 360/reserved 36/
available 324 g; milk on-hand 2,000/reserved 400/available 1,600 ml. No ingredient
issue or kitchen task exists for this unsent work; paid reservations neither
expire nor are resold merely because the app closed. Drawer is 111,400 from
100,000 + 20,000 − 8,600, one net +11,400 effect. One paid sale/receipt has two
sold lattes, net 10,000/tax 1,400, with pending fulfillment. I/O/A remain exact original
facts; lookup/reopen do not collect again or create a send.

## FCC-C-02 — send/pickup changes current FOOD without changing CASH originals

**Status:** NOT RUN. **Layers:** L2/L3, L4/L6.
**Sources:** OPS §§5–6.1, 9; DOMAIN §§4.4–4.6, 5, 7;
PHYSICAL-V1 original identity/no duplicate effects; CURRENT-V3 current boundary.
**Scenario/gates:** KIT-02/12/15, TAX-03/09, REL-04/07;
Q-DB-02/03/04, Q-RES-02/09, Q-UX-01/02.

Start from the genuine C paid/unsent state. The currently authorized owner Sends
the two selected portions at 08:15, then records ordinary start/Ready and actual
pickup, and closes the fulfilled check. Send consumes its own original reserved
allocation exactly once. Close/reopen the now-complete check at 08:21/08:22.
Lookup the earlier CASH I/O/A and the current check separately.

Expected current: Closed/Paid, both Ready/PickedUp; V3 **null** and historical
quote 11,400. Beans on-hand/available 324 g, milk 1,600 ml; both reservations
zero; one issue set for exactly 36 g/400 ml and two original kitchen tasks.
Drawer remains 111,400, coverage 11,400, sale net 10,000/tax 1,400, one receipt
with two sold lattes; check closure creates no second paid sale. CASH I at 08:10,
O at 08:11 and A at 08:11:03 retain their
original paid/unsent result and original content. They are not rewritten to
claim that pickup existed at collection. Current read reflects actual later
Send/Ready/PickedUp history. Lookup/reopen creates no cash, reservation, issue,
task, receipt or second fulfillment.

## FCC-O-01 — successful observation retained across Client cold reopen before application

**Status:** NOT RUN. **Layers:** L2 native originating-Client retention, L3 genuine
admitted-RPC/partition, and L4/L6 owner Client close/reopen recorded separately.
**Unmet boundary prerequisites:** genuine durable I on the Master; production
protected native observation storage on the admitted originating Client; a
bounded partition that prevents the financial application reaching the Master;
separate authoritative application-result lookup. A transport Receipt is not A.
**Sources:** PHYSICAL-V1 observation/application separation and retention after
session loss; DOMAIN §§4.2, 5.1–5.3; OPS §§4.2, 5, §7 lost-CASH-reply row;
OWNER current Master authority; CURRENT-V3 collection-locked boundary.
**Scenario/gates:** OWN-02, PAY-04 (local observation/recovery obligation),
REL-03/04/10, UX-06; Q-DB-03/04, Q-SEC-04/06, Q-RES-02/04/09.

Use R after genuine collection lock/I but before A. Partition only the original
Client after I, so the Master remains available to independent readers. The
original authorized operator receives 30,000 and gives 7,200 once, durably
captures successful O at 18:22 on that Client, and cannot deliver application.
Establish that application has not reached/committed at the Master; if that
boundary cannot be established, record the case unrun rather than infer zero
application from a missing reply. Actually close/reopen the Client with fresh
native observation storage. Do not perform physical money movement again.

Expected held state: O retains SUCCESS, original actor/device/I/document/cash
session, received 30,000, change 7,200, net 22,800 and original observation time.
The real physical drawer count is 122,800; the authoritative applied ledger is
still 100,000, applied coverage zero, original obligation 22,800. The difference
22,800 is explicitly observed cash awaiting financial application, never an
invented sale, automatic employee shortage, not-performed event or spendable
balance. Master check remains CollectionLocked; V3 **null**, not zero or 22,800
labeled as a permitted current collectible amount. Bread 8/meat 1,200 g persist.
Captured/received/acknowledged observation is not presented as financial success.

Reconnect and recheck genuine current authority before applying the same
original successful fact. With unchanged valid authority, commit A once without
another instruction or physical event: coverage +22,800, one net cash effect
+22,800, drawer 122,800, then ordinary paid finalization once. Original O remains
unchanged. In the revocation variant, current financial permission is withdrawn
before application: retain/re-deliver the protected original observation as
permitted, but hold financial application and new physical action. No revoked
credential is revived and no new user adopts O. Broader supervisor resolution
or unknown physical-outcome policy is not exercised.

## FCC-REC-01 — lost result, cancelled UI wait and exact duplicate recovery

**Status:** NOT RUN. **Layers:** L2/L3, L4/L6 separately. **Variants:** R after A;
C after A while paid/unsent; N4 lost response/duplicate delivery; N3 reconnect;
N5 actual cold reopen. Each starts independently.
**Sources:** OPS §5, §7 lost-CASH-reply row; DOMAIN §§5.2–5.3, 7;
PHYSICAL-V1 retry/wait/recovery; ACTIVITY Original evidence/compatibility.
**Scenario/gates:** KIT-02/12/15, REL-03/04/07, UX-06;
Q-DB-03/04, Q-RES-02/04/09, Q-SEC-04/09, Q-UX-05.

After the actual physical event and durable A, lose the application reply. Cancel
only the UI wait, close/reopen, then submit/query the exact saved original three
times, including two concurrent duplicate deliveries. Keep the original business
ID/payload/actor; new transport incarnation and freshly valid recovery session
are allowed. Also repeat original lookup after the subsequent C Send/pickup.

Expected: one original COMMITTED application with exactly original result/schema,
affected IDs/revisions, amounts and commit time under the genuine registered
contract. UI cancellation does not erase accepted work. R stays drawer 122,800,
stock 8/1,200 g; C stays drawer 111,400 and either its frozen paid/unsent reserved
state or its explicitly later sent/picked-up state. No extra money/stock/task/
receipt effect, no automatically new operation, no take-cash button from recovered
I, no original attributed to the new session. Actual current reads remain separate
and V3 null while paid; neither successful lookup nor transport Receipt changes it.

## FCC-REC-02 — same identity with changed business content is refused after reopen

**Status:** NOT RUN. **Layers:** L2/L3; L1 DATA is supporting only.
**Sources:** DOMAIN §§2.1, 5.1–5.4, 7, 10; PHYSICAL-V1 original identity;
ACTIVITY Original evidence and compatibility.
**Scenario/gates:** REL-04, UX-06; Q-DB-02/03, Q-RES-02, Q-SEC-05/07/09.

After R A and cold reopen, reuse the original operation with one changed content
vector: net collection 22,801/received 30,000/change 7,199; or another syntactically
valid same-branch target order identity. Do not hand over additional cash. Send
through the actual protected request boundary, not only a disabled UI. A changed
originating actor is exercised by FCC-USER-01 rather than granting that actor
historical access. Native command/schema changes, if exposed by the compatible
production contract, must likewise not silently normalize an original into new
money; do not invent an unsupported wire version to claim coverage.

Expected mismatch/refusal (`OPERATION_PAYLOAD_MISMATCH` under DOMAIN's typed
taxonomy where applicable; scope denial for unauthorized target). Original I/O/A
remain whole and unchanged; no new applied collection, cash movement, reservation,
stock issue or receipt. R drawer 122,800, bread 8/meat 1,200 g, coverage 22,800,
current V3 null. Exact original lookup after the refusal again returns the old
original, with its original time and amounts. No automatic rekey or overwrite.

## FCC-AUTH-01 — current authority defeats stale Client grants before new action/read

**Status:** NOT RUN. **Layers:** L2 protected local path, L3 admitted separate
Client, L4 UI; device run separately. **Variants:** N3 stale/disconnected Client,
N7 local activation, permission-before-publication race.
**Sources:** OWNER current Master authority; ARCH §§7–8;
DOMAIN §§5.2–5.4; PHYSICAL-V1 current instruction/application permission;
ACTIVITY Current authority; CURRENT-V3 protected disclosure.
**Scenario/gates:** OWN-03, REL-03, UX-06;
Q-SEC-01/03/04/06, Q-RES-01/04, Q-DB-03.

Use the valid R fresh-CASH negative-control check (one ordinary unpaid/unlocked
burger at 11,400, kitchen support and sufficient stock/cash custody). First bind
all otherwise valid current employee/device/session/activity/amount prerequisites.
Leave the Client's original grant/UI enabled and its existing transport session
open. At the Master activate one denial before admitting its new instruction:
operation permission withdrawn; employee disabled; session revoked; device no
longer admitted; or current authorized amount ceiling below 11,400. Deliver the
fresh request directly after activation without a notification ACK.

Expected current-authorization refusal before publication/commit: no new physical
instruction or cash event; control remains Unpaid/NotSent, coverage zero and quote
11,400, with no reservation, issue, task, receipt or money effect from the request.
Baseline already-paid R remains unchanged at 122,800 and bread 8/meat 1,200 g.
For the publication race, freeze denial-first order at the authoritative boundary:
denial must win. If instruction legitimately committed/disclosed first in a
separately recorded schedule, its original cannot be retroactively undisclosed;
further unauthorized publication/action is refused and any actual prior fact is
retained. Do not count that alternate schedule as denial-first PASS.

Separate read variant: after a baseline A, revoke current FOOD read permission
while cached UI/session remains; issue selected V3 current read. Deny disclosure
of food/kitchen/delivery/amount and clear private current presentation as applicable;
no fallback, money/stock change or accepting null result. Revoked-session/device
variants also deny. This variant does not exercise the explicitly unresolved
broader historical-original disclosure policy.

## FCC-AUTH-02 — retained original recovery grant is distinct from fresh collection

**Status:** NOT RUN. **Layers:** L2/L3, L4/L6.
**Sources:** OWNER current Master authority; PHYSICAL-V1 retention/current grant;
DOMAIN §§4.2, 5.3; ACTIVITY original compatibility/unresolved policy;
CURRENT-V3 current read authority.
**Scenario/gates:** CASH-06, OWN-02, REL-04, UX-06;
Q-SEC-04/06/09, Q-RES-02/09.

After genuine R or C A, withdraw permission for new collection, but deliberately
retain current owner/current-read and explicitly scoped original-recovery access.
Expire/revoke the old interactive session; actually close/reopen and authenticate
the same employee freshly. Recover the exact I/O/A through the permitted route,
then try the valid fresh-CASH control under the stale collection hint.

Expected old facts recovered under current scoped authorization without old-token
resurrection, new instruction or changed actor/custody. Original timestamps and
financial effects remain exactly R/C baseline. Actual current paid/closed or
paid/unsent state is preserved and V3 null. Fresh control is denied with zero
money/stock effects and no instruction. Historical authority is not a new current
grant; original SUCCESS is not rewritten as not-performed because rights changed.
If current original-recovery access cannot be bound, this case remains NOT RUN;
do not infer the broader read-revocation policy or grant full cashier privileges.

## FCC-USER-01 — account switch cannot inherit or rebind the old CASH presentation

**Status:** NOT RUN. **Layers:** L2 native Client retention, L3 protected reads,
L4/L6 UI/new-user close/reopen. **Variants:** pending O from FCC-O-01 or committed
A with delayed reply from FCC-REC-01.
**Sources:** OPS §2; ARCH §8; DOMAIN §§4.2, 5.1/5.3;
PHYSICAL-V1 original presentation/session loss; OWNER current authority.
**Scenario/gates:** OWN-02/03, CASH-06, REL-03/04, UX-06;
Q-SEC-04/06/08, Q-RES-02/09, Q-UX-05.

Log out original cashier A and log in ordinary cashier B on the same admitted
Client, including an actual Client close/reopen. B has a distinct assigned drawer,
own account and no A-order or supervisor recovery grant. Deliver A's delayed I/O/A
response into the old presentation generation and try B's direct-ID current/original
access or adopting A's operation. Later A may freshly authenticate with the
required current scoped recovery grant.

Expected no A-private instruction, amounts, credentials, customer/order workspace
or automatic resubmission under B; old-generation response is not shown as B's
new physical task. B's ordinary unauthorized direct access is denied, not upgraded
by possession of an ID or old DATA. Protected original O/A remain attributed to A
with A's cash session and actual received/change amounts. B's drawer/custody are
unchanged. A's permitted recovery again returns the original whole fact. Pending
financial application is not silently performed with B's token; retained/redelivered
observation does not confer financial authority.

## FCC-SRC-01 — complete/off-page required history cannot be replaced by subtotal/null

**Status:** NOT RUN. **Layers:** L2 actual native source failure, L3 selected V3,
L4 presentation. **Variants:** N5 source unavailable/corrupt/mismatched before or
after actual reopen; each on an isolated disposable synthetic working dataset.
**Sources:** CURRENT-V3 complete genuine source/refusal/no fallback;
ACTIVITY literal original evidence; DOMAIN §§3, 4.4, 5, 10;
PHYSICAL-V1 no accepting history fallback.
**Scenario/gates:** KIT-01/02, TAX-03/04, REL-04/10;
Q-DB-02/03/06, Q-RES-09, Q-SEC-07.

Use R FCC-R-01 (uncollected, two complete portions, V3 page size 1). Through a
bounded native-source fault boundary make required original price history for
off-page R2 absent, required portion history absent, price/portion identity point
at a different order, or original stock relationship mismatch. Keep current
authorization valid. Select advertised V3 once and query after reopen.

Expected read refusal, not a successful response containing 11,400 page subtotal,
22,800 historical quote relabeled current, guessed zero or accepting null. No
version-2/1/legacy request. No business effect from read/refusal; complete actual
order/issue/cash state is not rewritten to match the missing source. Restore only
that controlled fault to the literal original synthetic source and requery:
original two-portion state/current 22,800 and stock 8/1,200 g return unchanged.
The original saved quote, if separately available, remains explicitly historical.
A fake source view, mock DAO or in-memory corruption does not qualify the native
source-failure layer.

## FCC-SRC-02 — absent/mismatched original custody holds CASH verification safely

**Status:** NOT RUN. **Layers:** L2 literal native original selector, L3 permitted
original lookup, L4 truthful recovery. **Variants:** R paid/closed; C paid/unsent.
**Sources:** ACTIVITY Original evidence and compatibility; PHYSICAL-V1 original
fact retention; DOMAIN §§3, 5.3–5.4, 7–8, 10; OWNER Master authority.
**Scenario/gates:** REL-04/10, UX-06;
Q-DB-02/03/06, Q-RES-02/09, Q-SEC-07/09.

After capturing the genuine original I/O/A and economic snapshot, isolate one
required original native-selected source: instruction/observation association,
original branch-applied profile/publication/earlier activation evidence, or
required original price/portion/allocation evidence. Make that source unavailable
or materially mismatch original operation/branch/actor/document/content. Keep
otherwise authorized recovery credentials valid and actually reopen. Supply a
byte-identical inert descriptor or today's valid current profile as a control;
neither is native original authority.

Expected no verified exact-original result from incomplete/mismatched source;
show a held/unavailable original-verification state, never accepting history from
the DATA/current substitute. A previously actual cash event remains retained as
such; absence does not label it not-performed or invite collection again. No new
operation, money, receipt, issue, task or custody transfer; unaffected native ledger
controls retain R +22,800/drawer 122,800/stock 8/1,200 g or C +11,400/drawer 111,400/
on-hand 360/2,000/reserved 36/400. Required current reads whose sources are missing
also refuse rather than hiding failure with null. Return the isolated synthetic
source to its exact original and recover again: whole I/O/A and economic state
are unchanged, including after each earlier refusal. If the source boundary cannot
be bound genuinely, record NOT RUN; source-row existence alone is insufficient.

## FCC-SRC-03 — today's source absence cannot recreate a committed original

**Status:** NOT RUN. **Layers:** L2/L3, L4; native/owner run separately.
**Sources:** ACTIVITY Current authority/Original evidence/Unresolved policy;
PHYSICAL-V1 recovery/current grant; DOMAIN §§5, 7, 10;
OWNER current Master authority.
**Scenario/gates:** REL-04, UX-06; Q-DB-02/03, Q-RES-02/09, Q-SEC-06/09.

Keep complete genuine original activity/price/I/O/A evidence for R or C. Make
only the current branch-applied activity source unavailable/untrusted, not an
explicit business decision to disable/replace an existing check. Under retained
current scoped original-recovery authorization, query the committed original.
Attempt a fresh activity-protected CASH preparation/request with otherwise valid
current employee/device/session authority through the real guarded boundary.

Expected exact original recovered without rekeying, new proof retroactively added
to old format, current source substituted for original, or repeated effects.
Fresh operation is safely held/refused for missing authentic current activity
evidence, with no new instruction/physical event or partial order/stock/money
effect; use `CURRENT_SOURCE_UNAVAILABLE` where the published guarded profile
defines it. Original R/C cash/stock/application snapshots stay unchanged. This
does not decide what an intentionally disabled/replaced activity must do to
existing unpaid unsent checks or waive current permission on any disclosure.

## FCC-DATA-01 — supporting inert whole-original comparison and retained refusal

**Status:** NOT RUN. **Layer:** L1 DATA only; never native authority, current
permission, persistence, admitted-RPC, application acceptance or owner qualification.
**Sources:** PHYSICAL-V1 original identity/materially changed content;
ACTIVITY literal original evidence/compatibility; DOMAIN §§2.1, 5.1, 7, 10;
QUALITY §2 invariant 3 and §6 retry properties.
**Scenario/gates:** supporting only for REL-04/UX-06/Q-RES-02; no scenario/gate
rollup PASS from this comparison.

Use the inert descriptor in `fixtures.json`. Its requirement/observation byte
arrays are a deliberately specified fixture UTF-8 representation, not a claimed
native canonical encoding or SDK DATA protocol. Compare the entire original to
an identical copy: equality true. Separately change original identity, branch,
actor, original device/presentation, command family, fixture command version,
requirement bytes (material amount/document change), or observation bytes
(material outcome/received/change/time change): whole equality false. After each
refused changed candidate, compare the retained original again: exact equality
true and every original field/byte unchanged.

Report the outcome explicitly as **DATA comparison only**; **native authority not
verified**, **current authorization not checked**, **financial application not
confirmed**. No comparison result, DATA/transport Receipt or byte equality may
be labeled paid/committed native business, authorize a new instruction, replace
required native original source, or modify the retained original. Native canonical
schema/version, source keys and wire byte binding remain unbound prerequisites;
do not fabricate them from this inert fixture. The observed numerical contents
stay R received 30,000/change 7,200/net 22,800 at the original observation time.

## Residual coverage and execution ledger

All 14 case IDs and every platform/network/layer variant remain NOT RUN. Required
execution records are new records containing case version, actual source revision,
environment/OS/minimum OS, exact seed, native dependencies, chosen advertised
contract versions, original/generated ID and revision bindings, actual owner
close/reopen evidence, observed amounts/state and PASS/FAIL/BLOCKED/NOT RUN limits.
No run result is supplied here.

This increment exercises selected local portions of the listed original scenarios;
it does not roll up mixed/whole scenario PASS. In particular CASH-03/PAY-03 full
external/split-tender reconciliation, PAY-04 unknown physical/external outcomes,
KIT-15 cancellation/refund, REL-10 capacity/power-loss faults, Q-RES-09 full recovery
contents, Q-UX-07 full restaurant rehearsal and all-platform/peripheral qualification
remain unmet. Central-only and central portions remain held. Pending owner/device
runs are separate; missing production paths and required non-device failures remain
development work under OWNER-DEVICE-VERIFICATION.
