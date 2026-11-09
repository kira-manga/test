# FOOD CASH collection and cold recovery

Frozen design version **1.0.0** for the bounded local obligations of #51, #26
and #28. Every case is **NOT RUN**. This directory contains expectations, not
implementation, review, native-custody evidence or owner acceptance. The freeze
is recorded in `source-manifest.json`; preserve this version when adding results
or independently derived revisions.

The increment covers one complete Restaurant dine-in check and one complete
Café prepaid takeaway check, each collected in full using direct CASH. It covers
their immutable original instruction and observation, actual financial
application, current food/stock/cash observations, and recovery after the owner
actually closes and reopens the relevant installed application. A successful
original lookup must remain history; it must never become an instruction to take
cash again.

This is bounded coverage. It does not complete the whole FOOD workflow, any
owning Issue, all 141 scenarios, or local product acceptance. Refunds, unknown
physical-outcome resolution, ingredient restoration before preparation starts,
food cancellation/remake/defect decisions, partial collection, split bills,
other tenders, delivery/COD, replacement masters, backup restore, migration,
central services, peripheral printing and fiscal qualification remain outside
this increment. They remain required where the original scope requires them.

## Requirements and selected versions

| Reference | Authoritative design input and sections |
| --- | --- |
| OPS | [Operational specification](../../requirements/original/POS_OPERATIONAL_SPECIFICATION.md), revision 1.2: §§2–6, §7 lost-CASH-reply row, §§9–10 |
| DOMAIN | [Domain blueprint](../../requirements/original/POS_DOMAIN_AND_CONTRACT_BLUEPRINT.md): §§2.1, 3, 4.2, 4.4–4.7, 5.1–5.4, 7–8, 10 |
| ARCH | [Production architecture requirements](../../requirements/original/POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md), revision 3.2: §§3.1, 4.1, 7–8, 10–13, 18.1, 18.5, 19 |
| POLICY | [Company policies/catalog](../../requirements/original/POS_COMPANY_POLICIES_AND_CATALOG.md), revision 1.1: §§1, 3, 6–8 |
| ACCEPT | [Original scenarios](../../requirements/original/POS_ACCEPTANCE_SCENARIOS.md), revision 1.2: CASH-01/03/06, OWN-02/03, KIT-01/02/12/15, PAY-02/03/04, TAX-03/04/09, REL-01/03/04/07/10, UX-06 |
| QUALITY | [Test and quality plan](../../requirements/original/POS_TEST_AND_QUALITY_PLAN.md), revision 1.0: §§2–7, 9–10, 12–14 |
| OWNER | [Local completion and independent verification](../../requirements/OWNER-DIRECTIVE.md), [current Master authority](../../requirements/OWNER-MASTER-AUTHORITY.md), and [device-verification split](../../requirements/OWNER-DEVICE-VERIFICATION.md) |
| PHYSICAL-V1 | [Physical tender/observation public behavior v1](../../contracts/LOCAL-PHYSICAL-OBSERVATION-BEHAVIOR-v1.md): Business boundary, Public local interaction and recovery semantics, Financial and inventory invariants |
| CURRENT-V3 | [Current FOOD payable v3](../../contracts/LOCAL-FOOD-CURRENT-PAYABLE-V3.md): Business boundary, Compatible protected read, Shared presentation and verification |
| ACTIVITY | [FOOD activity authority](../../contracts/LOCAL-FOOD-ACTIVITY-AUTHORITY.md): Current authority, Drafts and kitchen capability, Original evidence and compatibility, Explicitly unresolved policy |

The current-state read selects the explicitly advertised
`sales.food.check-kitchen.read` version 3. Its request remains the bounded
`LocalFoodQueryV1.Order` selector: page size 1–8, complete order at most 32
portions, each saved batch 1–8 portions and at most 32 batches. Restaurant uses
page size 1 while the complete check contains 2 portions. Version 3 must use both
portions' genuine source history, not the displayed page subtotal. Once version 3
is selected, failure, denial or null must not trigger version-2/1/legacy fallback.
Older read versions keep their unchanged behavior; their complete compatibility
qualification is residual work.

The activity contract identifies an initial guarded empty-draft form version 2
and FOOD stock command version 2. This design does not claim that those versions
provide all quoted-addition, kitchen or collection integration. The public inputs
do not specify a native CASH command, instruction, observation or result-descriptor
encoding/version. Bind those genuine production contracts before execution and
record their actual versions. Do not invent a route, schema, native source key or
accepting compatibility default. The DATA fixture's version is its own inert
comparison profile, never a native CASH protocol version.

## Independently calculated business fixtures

All money is integer EGP minor units. The illustrative tax rate is 14%; it is
synthetic arithmetic, not a selection of the legally applicable restaurant or
coffee tax rate. There are no discounts, service/delivery charges, tips, deposits,
returns or unrelated cash movements. The half-up default monetary boundary from
DOMAIN §2.1 is recorded; these selected postings have no fractional-minor-unit
ties. Original posted net/tax/gross amounts remain exact after catalog changes
and reopening.

| Fixture | Item arithmetic | Full CASH observation | Applied cash and drawer |
| --- | --- | --- | --- |
| R: Restaurant | Burger I: inclusive 114.00 = net 100.00 + tax 14.00. Burger E: exclusive 100.00 + tax 14.00 = 114.00. Total net 200.00, tax 28.00, payable 228.00. | Received 300.00, change actually given 72.00, net 228.00. | Opening 1,000.00 + 300.00 − 72.00 = 1,228.00. Paid coverage 228.00; remaining obligation 0.00. |
| C: Café | Two small lattes, each inclusive 57.00 = net 50.00 + tax 7.00. Total net 100.00, tax 14.00, payable 114.00. | Received 200.00, change actually given 86.00, net 114.00. | Opening 1,000.00 + 200.00 − 86.00 = 1,114.00. Paid coverage 114.00; remaining obligation 0.00. |

Restaurant reuses the original KIT-01 recipe: 10 bread and 1,500 g meat; two
portions consume 2 bread and 300 g on Send. On-hand/available then remain 8 bread
and 1,200 g meat through ordinary preparation, service, payment and recovery.

Café uses a permitted versioned made-to-order coffee recipe: each small latte
requires 18 g beans and 200 ml milk. Opening stock is 360 g beans and 2,000 ml
milk. Collection before Send reserves 36 g and 400 ml while on-hand stays 360 g
and 2,000 ml; available becomes 324 g and 1,600 ml. Paid/unsent recovery preserves
that reservation. Send converts it once into issue, leaving on-hand/available
324 g and 1,600 ml, reservation zero. Payment never issues the recipe early or
deducts it a second time. The coffee selection follows OPS §6 and POLICY §6;
it does not introduce a prepared-batch, stock-tracked cup or deposit policy.

`fixtures.json` supplies stable synthetic IDs, exact original timestamps,
stage-specific snapshots, negative controls and the inert DATA vectors. Native
generated instruction/observation/receipt/issue/task/result identities are captured
literally at the first genuine operation, then must remain exactly equal. Their
actual field names and numeric revision increments are not specified by these
public requirements. Bind named revision handles to the real saved revisions;
never substitute a new current revision for a historical result. Numeric oracles,
quantity membership and observable state assertions are already frozen.

Instruction, observation and application share the stable original business
identity. Where a genuine production contract has separate phase-command IDs,
capture those actual IDs and their original linkage; this fixture does not
require invented separate native commands. The one-device topology appoints
the fixture's originating device as Master from the start; the separate-Client
topology uses its distinct listed Master. Do not rebind the original device
during recovery.

The fixed November 5 event times are a synthetic fixture clock. Use a supported
isolated test-clock boundary without changing unrelated system clocks. Separately
record the actual owner action/witness UTC times. A native run unable to bind the
fixture clock remains NOT RUN with that prerequisite; it does not quietly change
the original timestamp oracle.

## Original facts, application and current reads

1. **Instruction I** belongs to the original operation, amount, currency, CASH
   tender, document/payable revision, employee, device and interactive presentation.
   Genuine durable recording is required before displaying it. An instruction
   alone creates no physical receipt, paid coverage or cash posting.
2. **Observation O** retains the original physically received amount, actual change,
   success/not-performed/unknown distinction, actor and observation time. Capturing
   or receiving O is separate from the Master applying money. No transport Receipt
   or retained DATA descriptor proves application acceptance.
3. **Application A** is the genuine durable Master financial result linked to I/O,
   with its original operation/result identity, posted amounts, cash custody, audit
   and effects. Recovery of A creates no new physical instruction or financial
   effect and never overwrites I/O.
4. **Current state** is a new currently authorized Master observation of the actual
   order, portions, kitchen, stock and financial state. It is not A or the original
   quote relabeled current. V3 amount is 228.00/114.00 only in the complete ordinary
   uncollected profile; CollectionLocked, Paid, closed or picked-up work has
   `currentUnpaidPayable:null`. That null is neither zero nor unpaid-amount waiver.

Retain native-selected original profile/publication/activation and price/portion/
recipe/allocation evidence where required. Today's catalog, a copied DATA object,
a fabricated historical read view and mere source-row existence do not establish
literal original custody. Missing/mismatched required original sources hold
verification safely. A current source becoming unavailable does not rekey or
re-execute an otherwise recoverable committed original.

## Execution boundaries and unmet prerequisites

| Layer | Planned evidence and prerequisite |
| --- | --- |
| L1 DATA | Inert whole-descriptor comparison only. No production storage, current permission, native custody, instruction or money application is qualified. |
| L2 non-device | Actual production storage/driver, transactions, protected same-device adapter, fresh native connection/process and authoritative ledger/source queries. No in-memory replacement for this boundary. |
| L3 non-device | Genuine admitted local Client → Master RPC, current authentication/permission enforcement, real registration, durable original/application lookup, bounded response-loss/partition controls. A transport Receipt is not a durable financial result. |
| L4 application | Installed shared Restaurant/Café workflows and recovery presentation in Arabic and English, connected to those genuine local services. No central-service execution. |
| L6 owner/device | Actual owner closes/reopens the named installed application; witness process/runtime and native source recovery on the particular supported device. Physical/peripheral/owner runs remain separate acceptance evidence. |

Baseline is N2: WAN/central unavailable, healthy local authority, no standby
dependency. N3/N4 add an isolated client partition, lost response and duplicate
delivery; N5 adds actual close/reopen and required-source failures; N7 adds locally
activated permission/source changes. No central implementation or verification
is authorized by this design.

| Required platform | Required roles | Minimum OS in the allowed inputs | Current run state |
| --- | --- | --- | --- |
| Windows | Master and admitted Client; protected one-device path | Not specified; bind before native run | NOT RUN |
| macOS | Master and admitted Client; protected one-device path | Not specified; bind before native run | NOT RUN |
| Android phone/tablet | Master and admitted Client; real service/app lifecycle | Not specified; bind before native run | NOT RUN |
| iPhone | Master and admitted Client; physical active-app/reopen behavior | Not specified; bind before native run | NOT RUN |
| iPad | Master and admitted Client; physical tablet/reopen behavior | Not specified; bind before native run | NOT RUN |

Desktop cashier-window closure must leave its qualified Master service running;
that proves UI reopen, not a cold Master restart. Android swipe-away may likewise
leave a permitted service running. Claim cold recovery only after the relevant
native process/storage owner actually stops and a fresh native owner reopens it.
For iOS, actual app termination/reopen is required; a simulated lifecycle event is
supporting evidence only. Same-device recovery restores its still-active role and
exclusive writer without WAN approval. An intentional service-stop marker is a
different precondition and must not be silently cleared.

Production path/driver availability, complete native source selection, authentic
synthetic activity provisioning, real kitchen capability, current employee/device/
session authorization, clock binding and the owner/device environment are all
unmet execution prerequisites here. No code, build, test or owner action was run
for this design. Missing implementation, broken integration and failed required
non-device checks remain development gaps; they cannot be called deferred device
testing. Full 25 ordered Master/Client platform-pair qualification, capacity,
power-loss durability, backup recovery, migrations, physical peripherals and the
remaining original scenarios/gates are still required separately.

## Explicit unknowns

- The activity contract leaves disable/replacement behavior for existing unpaid
  unsent checks unresolved. This design does not choose a charge, cancellation,
  fulfillment or restoration policy for that situation.
- Broader original-result disclosure after read-permission revocation is
  unresolved. Cases retain an explicit current scoped original-recovery grant
  when requiring original disclosure, or test denial of the current protected
  read without asserting the unresolved historical-disclosure policy.
- Native CASH wire/schema/descriptor encodings, qualified minimum OS versions,
  source selectors, available production fault boundaries and owner hardware
  must be recorded before their corresponding execution. The DATA bytes below
  do not decide those formats.
- Applicable legal tax/fiscal mappings and excluded food remedy/partial-money
  decisions remain outside this arithmetic fixture. No owner policy decision is
  needed to freeze the selected ordinary full-CASH scenarios.

Results must identify case/version, actual tested revision, layer, platform/OS,
seed, genuine dependencies, actual owner action where applicable, observed
outcome and limits. Preserve every earlier freeze and every failure. A passed
supporting DATA/L2 case never implies admitted-RPC, native lifecycle or owner PASS.
