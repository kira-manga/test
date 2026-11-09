# Stock-count safety preservation v1 — supporting JVM wiring

Frozen input: `qa/pos/designs/stock-count-safety-preservation-v1/`, version
`stock-count-safety-preservation-v1`, frozen 4 November 2026. The fixture loader
reads that exact version's quantities and identities. This package does not
change the frozen specification or claim a complete case/layer pass.

## Mapped portions

`StockCountSafetyPreservationV1SupportingTest` has 15 test methods. All count
sequences use the actual normalized `countPlan`, including capture and post.
The recorded output is encoded/decoded with the production row/document codec
and becomes the next scalar input. Availability is calculated from the actual
resulting positions and preserved allocation memberships, not from an assumed
post-count snapshot.

| Frozen case | Bounded supporting wiring | Still unmapped |
| --- | --- | --- |
| SCS-C01 | 9 of 12 reason/count combinations: RECALL, EXPIRED and QUARANTINED, each observed at 1, 7 and 10. Checks signed adjustment, physical quantity, original restriction, availability, count observation/cut and unchanged inventory history. | The 3 generic BLOCKED combinations; actual protected selection/refusal, original safety evidence and durable workflow. `COUNT_REVIEW` is not relabeled as a generic quality block. |
| SCS-C02 | 4 of 8 reason/claim combinations: RECALL and EXPIRED, each with ORDINARY or UNKNOWN. Checks 10→8, adjustment -2, original claim 4, zero availability, unchanged released allocation identity and order/lock/actor/exposure/expiry binding. | The 2 BLOCKED and 2 QUARANTINED combinations; full original operation, quote/attempt/payment evidence, passage of more time and retry/restart. A quarantined allocation key is not substituted for the originally released key. |
| SCS-C04 | 2 supporting sequences: actual recalled/expired count 10→7 followed by actual `receiptPlan` receiving 5 of the same lot into a previously absent QUARANTINE custody position. Checks 7+5=12, both restrictions/expiry/availability, the later receipt and the distinct earlier count. | Physical locations A/B, inspection release/refusal, explicit selection, duplicate receipt and original count retry/conflict. |

The C04 boundary has company/branch/SKU/warehouse/ownership/lot/custody/serial
keys, **not a physical sublocation field**. Its two distinct custody positions
reuse the frozen 7/5 quantities only as supporting same-lot regression evidence;
they do not stand in for full A/B location coverage. In particular, quarantine
alone is not taken as proof of recall/expiry propagation: the newly held
position's original RECALLED/EXPIRED safety is explicitly checked.

SCS-P01, C03, C05, C06 and D01 are not wired here. No outcome is invented for U1
(count below claim), U2 (new count-shortage holds), U3 (other hold scopes), U4
(legacy compatibility) or U5 (count-loss valuation policy). Zero variance means
zero net adjustment, not an asserted absence of movement rows.

## Boundary and evidence limits

This is a test-only scalar planning boundary, **not** a native or in-memory
replacement repository presented as L2. Input origins, activation, catalog,
basis and stamp coordinates are synthetic scalar data, not authenticated
producers, permission grants or genuine source qualification. `current` checks
only the explicitly registered test stamps; `absent` checks only this fixture's
maps. The receiving claim provider supplies one exact typed ownership claim;
its current and historical authority methods remain unavailable. The passive
`StoredAggregate` constructor is used only to carry encoded test documents.
None of these helpers is part of production accepting defaults or composition.

The complete small valuation input chain includes receipt 12, historical issue
2 and its original 400-minor cost. Receipt value 2,400, initial carrying value
2,000 and the later receipt value 1,000 are incidental scalar setup, **not new
count-loss expected values**. No count-loss monetary amount is asserted.
Historical inventory links/costs are checked, but neither the confirmed
1,000-minor payment projection nor the unresolved 2,000-minor attempt/quote is
an input to this planner; their actual durable preservation remains unbound.

No Room/SQLite driver, protected master command, native current authority,
complete retained-original checker, historical authentication/read permissions,
transactional result/outbox, same-device adapter, RPC, crash/restart or
backup/restore is exercised. In particular, scalar row decoding cannot prove
original safety evidence or its historical document quantity. These remain
required integration/runtime work, not passing or deferred acceptance claims.
Central implementation and verification remain held.

Suggested focused selector (from the POS build):

```text
:adapters:inventory-store:jvmTest --tests "*StockCountSafetyPreservationV1SupportingTest"
```

**Executed 5 November 2026:** all 15 supporting methods passed as part of the
180-method inventory JVM suite; affected Android compilation passed. Exact source,
commands and remaining integration limits are recorded in
`qa/pos/traceability/stock-count-safety-20261105/README.md`. No whole frozen case
or native/persistence/restart pass is claimed.
