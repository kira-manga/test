# Approval provenance cases — frozen v1.0

Status: **FROZEN, NOT RUN**. Layer: L1 authorization unit assertions only.
Original requirement references R1–R5 are defined in [README.md](README.md).
Production/platform verification remains separately required there.

## Exact synthetic fixture

The following tuples are canonical business identities, not a serialization
format. Employee-account identity includes its organization/company/branch;
display names, device IDs and login presentation cannot substitute for it.

| Label | Organization | Company | Branch | Employee account |
| --- | --- | --- | --- | --- |
| R, original requester | ORG-PROV-001 | COMP-PROV-001 | BR-PROV-001 | EMP-PROV-REQ-001 |
| M, authorized approver | ORG-PROV-001 | COMP-PROV-001 | BR-PROV-001 | EMP-PROV-APPROVER-001 |
| N, another authorized approver | ORG-PROV-001 | COMP-PROV-001 | BR-PROV-001 | EMP-PROV-APPROVER-002 |
| D, direct actor | ORG-PROV-001 | COMP-PROV-001 | BR-PROV-001 | EMP-PROV-DIRECT-001 |
| S, named sole operator | ORG-PROV-001 | COMP-PROV-001 | BR-PROV-001 | EMP-PROV-SOLE-001 |
| RB, other branch account | ORG-PROV-001 | COMP-PROV-001 | BR-PROV-002 | EMP-PROV-REQ-B2-001 |
| RC, other company account | ORG-PROV-001 | COMP-PROV-002 | BR-PROV-003 | EMP-PROV-REQ-C2-001 |

R and M deliberately have the identical display label `Synthetic User`. Devices
`DEVICE-PROV-001` and `DEVICE-PROV-002` are admitted devices for BR-PROV-001;
moving R between them preserves R's canonical account identity.

Baseline current activated local policy version is **10**. All relevant local
accounts/sessions are enabled, unexpired and unrevoked. The protected action is
**post a local goods receipt** in COMP-PROV-001/BR-PROV-001. R and M can request
approval but cannot execute this action directly. M and N can approve another
authorized requester's action in this scope; R cannot approve another requester.
D can execute directly. S has an explicit active NamedSelf exception for this
action only. Ordinary approval for other accounts requires Separate.

Baseline bound request: operation `OP-PROV-001`, receipt `GR-PROV-001`, document
version **7**, line `GR-LINE-PROV-001`, quantity **2**, amount **200.00 EGP**, reason
`synthetic accepted supplier delivery`. All unrelated business prerequisites are
valid; these cases observe authorization, not receipt/inventory calculations.
Approval `APP-PROV-001` binds that entire request, R, M and policy version 10.
It is authorized and unconsumed; fixture time is `2026-11-01T00:05:00Z`, within
its expressly specified validity ending `2026-11-01T00:10:00Z`. This fixture does
not prescribe a production approval lifetime.

Unless a case says otherwise, start independently from this baseline. Change
only the stated input for each negative variant. Refusal means **0 new accepting
proofs, 0 accepted consuming actions and 0 additional successful approval
consumptions**. It must not become a direct or null-approver success. Denial may
produce an honest attempt audit; it cannot fabricate successful provenance or
authorize any business effects. These counts describe unit observations, not
proof that persistent business records were inspected.

## Frozen cases

| Stable ID | Inputs/actions | Exact observable assertions | Original references |
| --- | --- | --- | --- |
| APV-01 — direct null | D requests the action directly for `OP-PROV-DIRECT-001` / `GR-PROV-DIRECT-001`, with no approval. Repeat as an independent direct invocation after APV-02 in the same test context. | Both direct outcomes accept with requester exactly D and approver exactly `null`. Each is bound to its own operation and current policy 10. No approver is inferred from D, a prior approval, session, display label, device or Master identity; no approval is consumed. | R1 §2; R2 §1; R4 invariant 2; assigned direct/transaction-local behavior. |
| APV-02 — actual separate approver | R consumes `APP-PROV-001` for the baseline request. Then, in a distinct transaction, use `APP-PROV-002`, operation `OP-PROV-002`, receipt `GR-PROV-002`, requester R and actual approver N; all other bound values remain as specified above. | First accepting proof has requester exactly R and approver exactly M. Second has requester exactly R and approver exactly N. Each approval is successfully consumed once for its own operation; the consumer receives the corresponding pair and policy 10. R is never replaced by M/N; the first proof retains R/M after the second invocation. Same display labels cannot collapse R and M. | R1 §2; R2 §1 binding/preservation; CFG-02; UX-07; Q-SEC-04/05; R5 §8. |
| APV-03 — explicit NamedSelf | For operation `OP-PROV-SELF-001` and receipt `GR-PROV-SELF-001`, S consumes `APP-PROV-SELF-001`, expressly bound to S as requester and approver, the fixture action/scope/policy and S's active exception. | One accepting proof identifies requester exactly S and approver exactly S; approver is not null. One successful consumption belongs to this operation. Equality is permitted only by the stated active named exception. | R2 §1; CFG-04; Q-SEC-05. |
| APV-04 — Separate refusal | (a) M requests its own action and claims M's approval under Separate, without a named exception. (b) M requests its own action and claims R as approver; R lacks approval authority although requester and approver differ. | Both variants refuse with the common zero expectations. Being an approver for other people does not authorize M's own Separate request; distinct identities do not authorize R's approval. No self elevation or forged R/M provenance. | R2 §1; CFG-03/04; Q-SEC-05. |
| APV-05 — NamedSelf boundaries | Independently repeat APV-03 with S's exception (a) absent, (b) explicitly inactive, (c) granted only to M, or (d) limited to **record a supplier cash settlement** instead of the fixture action. | Every variant refuses with the common zero expectations. An enabled account or general role is insufficient; a different named account/action grants S no exception. APV-03 remains the positive control. | R2 §1; CFG-04; Q-SEC-05. |
| APV-06 — current revocation | Issue the baseline approval while authorized, then before consumption: (a) revoke R's current session; (b) disable M's local account; (c) revoke M's qualifying approval session; (d) activate policy 11 removing M's approval permission. Control (e): policy 11 carrying that revocation is only pending, while activated policy remains 10 and relevant local sessions remain valid. | (a)–(d) refuse with the common zero expectations; old approval/token authority cannot override current checks. (e) accepts once with exactly R/M, bound to activated policy 10; pending central intent cannot be treated as locally activated authority. Do not relabel historical R/M provenance after later revocation. | R2 §1 activated-policy/offline rule; CFG-05; Q-SEC-03/06; R4 invariants 2/5/8. |
| APV-07 — exact binding | Independently apply the mismatch matrix below to `APP-PROV-001`. | Every mismatch refuses with the common zero expectations even where the substituted employee otherwise has permission. No acceptance by omitting, overriding or substituting a bound field. | R2 §1 binding; CFG-02/03; Q-SEC-05/09; R5 §§2/9. |
| APV-08 — stale request/validity | Independently change (a) receipt to `GR-PROV-002`; (b) document version to 8; (c) line to `GR-LINE-PROV-002`; (d) quantity to 3; (e) amount to 201.00 EGP; (f) reason to `synthetic changed supplier delivery`; (g) time to `2026-11-01T00:10:01Z`, after the explicit validity ended. | All variants refuse with the common zero expectations. No fresh accepting proof inherits the old R/M authorization for changed or expired input. Original bound values and historical provenance remain unchanged; valid new authorization is required. | R2 §1; CFG-02; Q-SEC-03/05; R4 §5 stale-state variant. |
| APV-09 — rejected/consumed and duplicate | (a) Attempt consumption of an explicitly denied approval. (b) Attempt consumption of an explicitly revoked approval. (c) After the successful APV-02 first operation, attempt a new authorization using its consumed approval. (d) Retry lookup/submission of exactly `OP-PROV-001` with the original payload after its success. (e) Reuse `OP-PROV-001` with quantity 3. | (a)–(c) refuse with zero new proofs/actions/successful consumption. (d) recovers only the original committed outcome identifying exactly R/M; cumulative successful action and consumption counts stay **1**, and no new authorization/proof is minted. Recovery of an immutable historical result is distinct from accepting a consumed approval again. (e) refuses the changed payload with no additional action/consumption and no altered R/M result. Durable result recovery is a separate L2/L3 obligation. | R4 invariants 3/5, §5 duplicate/recovery, Q-SEC-09; R5 §9; PUR-04/PUR-16; UX-06. |

### APV-07 mismatch matrix

All substitutions are independent; unlisted baseline inputs stay unchanged.

| Variant ID | Exact mismatch |
| --- | --- |
| APV-07-A | Requester's canonical identity is M instead of bound R. |
| APV-07-B | Claimed approving identity is N instead of bound M. N's otherwise valid approval permission cannot rewrite M's original approval. |
| APV-07-C | Consuming company scope is COMP-PROV-002 instead of COMP-PROV-001. |
| APV-07-D | Consuming branch scope is BR-PROV-002 instead of BR-PROV-001. |
| APV-07-E | Requester uses canonical RB or RC instead of R, including the corresponding different branch/company account. |
| APV-07-F | Consuming operation is OP-PROV-003 instead of OP-PROV-001. |
| APV-07-G | Consuming action is **record a supplier cash settlement** instead of **post a local goods receipt**. |
| APV-07-H | Current activated policy is 11, still granting the same permissions, while approval remains bound to policy 10. A binding mismatch alone suffices to refuse. |

Network faults, simultaneous consumers, persistent rollback/commit, real
authentication, logout/UI isolation, driver/platform execution and recovery
must be verified at the separately stated production layers. None has been
executed or passed by freezing these unit assertions.
