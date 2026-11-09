# Frozen cases

Version 1, 1 November 2026. All cases and variants are **NOT_RUN**. `MISMATCH` and
`NO_MISMATCH` are exact semantic labels, not invented error-code precedence.
Each negative variant changes only the named input from its valid baseline.
Use `fixtures.json`; obtain actual public proofs through the real evaluator and
approval path. Independently materialize the expected requirement from the
operation's exact semantic input, rather than copying it from the proof under test.

Every matcher invocation must leave its inputs unchanged, call no effectful
authority/storage/RPC adapter and create no authorization/qualification artifact,
stock/custody, invoice/allocation, cash/payable, evidence-use history or retention
effect. This purity oracle does not establish absence of effects in a production
transaction that has not run.

## QAM-01 — Explicit rule and exact purchasing coordinates

Layer: shared matcher. References: purchasing design §§2–4, 8–9; company policies
§1; CFG-02/03, PUR-01/04/06; matching contract M1–M3.

Invoke the matcher with rule `RULE-DIRECT` and exact baseline policy, publication,
branch, action, supplier and both product/catalog coordinates: **NO_MISMATCH**.
Another eligible rule existing beside it never replaces the explicit choice.

| Independent variation | Expected |
| --- | --- |
| Missing/unknown selected rule ID, selected ID differs from the provided body, or incomplete required body coordinates | MISMATCH |
| Different action, policy ID/revision, publication ID/revision or branch | MISMATCH |
| Supplier ID/revision differs | MISMATCH |
| Either product is excluded by the selected rule; first product matches but second does not | MISMATCH |
| Either product ID/revision or its catalog-release coordinates differs from the authoritative selection | MISMATCH |
| Required catalog release differs or is missing | MISMATCH |
| Combine supplier permission from one rule and product/evidence permission from another | MISMATCH |

No category membership may be inferred from SKU text/name. A category-only rule
without independently supplied authoritative membership cannot produce agreement.
Company provenance remains required in the separate unbound gate in QAM-05.

## QAM-02 — Actual PERFORM proof and bounded approval mode

Layer: shared matcher plus real public proof provisioning. References: company
policies §1; blueprint §§4.2, 5.1–5.2; CFG-02/03/04; M4.

| Rule and actual public output | Expected |
| --- | --- |
| Direct: requester R, approver/approver-policy revision/approval ID absent; exact expected requirement and employee-policy revision | NO_MISMATCH |
| Direct: any approver, approver-policy revision or approval ID present | MISMATCH |
| Separate: requester R, distinct actual approver A, actual approval ID and approver-policy revision; exact expected requirement | NO_MISMATCH |
| Separate: approver/approval coordinates missing, or actual requester equals approver | MISMATCH |
| NamedSelf: actual requester = actual approver = R, explicitly named R in this selected rule, with actual approval coordinates | NO_MISMATCH |
| NamedSelf: distinct actual requester R and approver A, both otherwise authorized, with actual approval coordinates | NO_MISMATCH |
| NamedSelf: actual requester equals approver but another/no account is explicitly named for that equality exception | MISMATCH |
| Any mode: proof actor differs from independently expected original requester, proof is not PERFORM, proof requirement differs in any operation-bound coordinate, or employee-policy revision differs from independently expected revision | MISMATCH |

Changing a body-claimed requester/approver pair cannot change these outcomes.
NamedSelf is the Separate route with an explicit action-scoped equality exception;
it does not exclude ordinary distinct authorized actors.
Revocation, fresh grants and approval expiry are external gates in QAM-05; historical
proof fields are never represented as a fresh authority check.

## QAM-03 — Required/optional exact zero-to-two originals

Layer: shared matcher. References: purchasing design §8; documentary public
contract "Explicit bounded profile"; documentary v2
fixture files JPEG-A/PDF-A; M5.

| Selected rule and ordered manifests | Expected |
| --- | --- |
| Optional evidence: [], [DOC-J], or [DOC-J, DOC-P], each exact and permitted | NO_MISMATCH |
| Required evidence: [DOC-J] or [DOC-J, DOC-P], each exact and permitted | NO_MISMATCH |
| Required evidence: [] | MISMATCH |
| Any rule: three selected originals, repeated same original ID, or incomplete original manifest | MISMATCH |
| Present optional/required evidence with forbidden media/purpose, wrong supplier/branch, or changed original ID/storage revision/length/digest against its operation-bound selection | MISMATCH |
| Ordered selection [DOC-J, DOC-P] but selected original manifests resolve as [DOC-P, DOC-J] | MISMATCH |

Optional evidence relaxes presence only. Every present original still needs its
exact permitted selector. Compare independently supplied operation-bound selections
with the corresponding resolved metadata, including order. JPEG/PDF, matching
hashes and a documentary purpose do not authenticate the supplier or qualify the
original for an arrival.

## QAM-04 — Explicit retention class and revision

Layer: shared matcher. References: purchasing design §8; company policies §5
(preservation principle); documentary intake v2 B5; M6.

`RET-PUR-ORIGINAL` revision 2 selected exactly as required by the chosen rule and
expected policy coordinates: **NO_MISMATCH**. Independently omit/change class ID
or class revision: **MISMATCH**. Another rule's retention class and a guessed
default duration never satisfy this choice. Retention-policy provenance and
company/branch scope remain required whole-action production/unbound gates in
QAM-05; no absent bounded field is reported as matcher coverage.
These expectations do not authorize deletion, assign a legal duration, install
retention policy or prove a locally accessible protected archive.

## QAM-05 — Staleness, scope, repeatability and external refusal gates

Layer: shared matcher for the first table; separate production lanes for the second.
References: blueprint §§4.2, 5.1–5.4, 7–8; company policies §1; CFG-02/05,
PUR-04/16; M2–M4, M7.

| Matcher variation | Expected |
| --- | --- |
| Old supplied policy/publication/product/catalog revision against a newer explicit expected coordinate; cross-branch supplier, product, document or proof requirement where its public coordinate is supplied | MISMATCH |
| Proof belongs to another operation ID, target revision or semantic content | MISMATCH |
| Repeat an identical call with identical supplied snapshots | Identical semantic result; zero effects |
| Pure comparison agrees although an actor/session/approval has since expired or been revoked outside the supplied snapshots | NO_MISMATCH conveys no current authority; protected-action gate must still refuse |

| Genuine production gate/variant | Expected outcome; execution |
| --- | --- |
| Company of policy/supplier/product/catalog/original/proof differs, or selected retention class's policy ID/revision/company/branch provenance differs or is unavailable (bounded public-field binding unconfirmed) | Refuse whole protected action; zero new arrival/stock/invoice/money/evidence-use qualification; preserve originals/history. Production/unbound, NOT_RUN |
| Current policy/publication/source unavailable, wrong scope, stale original manifest, revoked requester/actual approver, missing current actor grant, expired session/device admission or expired/consumed approval | Refuse new qualified arrival; zero new arrival/stock/invoice/money/evidence-use qualification; preserve captured originals and earlier committed history. NOT_RUN |
| Lost reply/retry, concurrent duplicate, process restart, changed actor/content under same operation ID | Recover original authorized durable result once, or refuse conflict; no duplicated effects or rewritten actor/history. NOT_RUN |
| Original captured evidence remains pending/unresolved after refusal or restart | Original bytes/manifests/history remain retained; no qualification manufactured by recovery. NOT_RUN |

The production gates require real current authority, durable storage and transaction
history, plus local RPC where applicable. Shared matcher results cannot mark them
PASS. No expiry boundary or historical-reader compatibility is invented here;
bind the actual public clock/approval/read contracts separately before execution.
