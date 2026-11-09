# CCJR-v1 — bounded core rejection-candidate DATA

Frozen expectations: [`cash-closing-rejection/README.md`](../../../../../../../../../../../../../qa/pos/designs/cash-closing-rejection/README.md),
[`cases.md`](../../../../../../../../../../../../../qa/pos/designs/cash-closing-rejection/cases.md) and
[`fixtures.json`](../../../../../../../../../../../../../qa/pos/designs/cash-closing-rejection/fixtures.json).

Selector: `com.ukkera.pos.database.CashClosingRejectionCandidateSlicesTest`
under `:core:database:jvmTest`. [Executed outcomes and limits](../../../../../../../../../../../../../qa/pos/traceability/cash-closing-rejection-20261103/README.md). These are 18 bounded
DATA methods, not 18 complete cases or native/whole-history acceptance.

The harness calls `RuntimeHistoricalConfigurationRejectionProfile.CASH_CLOSING`
`matches`/`acceptsRejection`, the authorization-evidence codec and requirement/money
DATA conversions. It never constructs `RuntimeHistoricalCommand`, an active
history owner, canonical cash request, native BEFORE or authenticated original.
The production `requireCashClosingRejection` boundary is **UNBOUND** here.

## Eight requirement-carrier shapes

| Family / frozen case | Represented action / exact reason | Owner / limit DATA |
|---|---|---|
| BeginClosing / CCJR-001 | CASH_CLOSE / `cash.close.begin` | E_A present / none |
| CancelClosing / CCJR-002 | CASH_CLOSE / `cash.close.cancel` | E_A present / none |
| RecordClosingCount / CCJR-003 | CASH_CLOSE / `cash.close.count` | E_A present / monetary count carrier 236000 |
| DeliverCountedClose / CCJR-004 | CASH_TRANSFER / `cash.close.deliver` | E_A present / monetary T1 carrier 186000 |
| SubmitCountedClose / CCJR-005 | CASH_CLOSE / `cash.close.submit-counted` | E_A present / monetary count carrier 236000 |
| Review / CCJR-006 | CASH_REVIEW / `cash.close.review` | unrestricted / none |
| ReceiveSafe / CCJR-028 | CASH_RECEIVE / `cash.safe.receive` | E_S present / monetary T1 carrier 186000 |
| SubmitClose compatibility / CCJR-007 | CASH_CLOSE / `cash.close.submit` | E_A present / monetary count carrier 236000 |

All use `sales.cash.control` v1, PERFORM, matched scope/operation/semantic-digest
copies, direct interactive LOCAL_EMPLOYEE DATA and no approval locator. These
are classifier shapes only. The document/revision/owner/amount choices do not
prove the genuine normalized subtype or action-specific monetary permission.
Both recognized `sales.*` refusal-family strings are candidate inputs; neither
is asserted to be the exact native refusal for any frozen case.

## Method mapping

| Methods / DATA observation | Frozen reference / undisclosed obligation |
|---|---|
| `ccjr013AllSeven…`, `ccjr013DifferentOuter…`, `ccjr013SupportedAuthorization…` | CCJR-013; eight carrier shapes, exact outer command/version classification and evidence-field roundtrip. Canonical typed payload/result relabelling remains UNBOUND. |
| `ccjr014NonEmployee…`, `ccjr014NonInteractive…`, `ccjr014AnApproval…` | CCJR-014; principal/purpose/approval-locator shape negatives only. No identity authenticity or native founding lane. |
| `ccjr015RequirementAndAuthorization…`, `ccjr015PrivateClosing…` | CCJR-015; one-sided organization/company/branch mismatch and owner-presence/review-unrestricted separation. Actual original custodian/actor/document/revision closure remains UNBOUND. |
| `ccjr016AnotherAllowed…`, `ccjr016WrongVerb…`, `ccjr016OperationAndSemantic…` | CCJR-016 and represented CCJR-012/013/014 binding copies; wrong action, verb, unknown reason and one-sided operation/digest changes. No actual canonical request/content hash, policy or approval binding. |
| `ccjr017FrozenCash…` | CCJR-008/017; exact Money DATA: expected 235000, first variance 500, final variance 1000, transfer 186000; external tender excluded from the supplied arithmetic. No ledger/count/disposition/receipt read. |
| `ccjr017Monetary…`, `ccjr017BeginCancel…`, `ccjr017UnsupportedCurrency…` | CCJR-017; action-specific none/monetary shapes, missing/negative/base/proportional/unknown-kind rejection and codec refusal of unsupported currency/missing/noncanonical money fields. Exact bound amount, zero substitutions and native limit enforcement remain UNBOUND. |
| `ccjr018HeaderAndEvidence…`, `ccjr018FiveChanges…` | CCJR-018; rejection purpose and five-change census candidates only. No actual mutation content, immutable count/transfer/ACK or business-job tamper proof. |
| `ccjr015CashOwnerException…` | CCJR-015 plus unchanged noncash compatibility; valid owner-null controls for the ten original CCJR-v1 profiles, not every present or future noncash profile, and independently changed private owner. No noncash workflow acceptance. |

## Remaining UNBOUND / NOT_RUN

Every complete CCJR-001–028 case remains **NOT RUN**. In particular: canonical
subtype/guard/error precedence; faithful original result/arguments; native founding
versus enrolled authorization; original identity/owner/document/revision and exact
action-specific amount/currency/limit binding; original request bytes/content;
original resources/reads/deadline/time and missing-original recovery; real five
nonbusiness row images versus any cash/count/review/transfer/ACK/job mutation;
durable rejection/audit, duplicate/restart, disclosure/revocation, receipt uncertainty
and close/payment races all require genuine production/native fixtures.

The marker digests, header coordinates and employee snapshots are inert data,
not self-attested proof that those obligations are satisfied. In particular,
candidate matching of some structurally valid data is never history acceptance.
No missing-original/resource/result/deadline API test is claimed without a genuine
active command. All native drivers/platform Master/Client, installed/LAN/device
and owner variants stay unverified; central work remains held.
