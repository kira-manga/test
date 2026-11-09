# FOOD financial RPC v1 — gateway and Client-journal supporting tests

Specification: `qa/pos/designs/food-financial-rpc-v1`, frozen **1.0.0**.
Status: **13 supporting methods passed**; [results and limits](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/food-financial-rpc-20261106/README.md). These files do not update the frozen execution
record or qualify a complete FFRC case.

The loader verifies the frozen README, cases and semantic fixture bytes against
the existing freeze, then binds A/B/M/CM/CA/AM/RM to deterministic synthetic UUIDs.
MIXED retains payable **57,013**, received **60,000** and change **2,987** minor
EGP units. Controlled source revisions are order storage 41, session domain 7,
collection domain 2 and covered-order storage 43. They are runtime DATA, not
revisions asserted by the semantic fixture. Original RecordOriginal bytes are
encoded only as immutable read DATA/digest; this harness never dispatches them.

## Assertions and exact boundary

| Frozen case | Supporting observations |
| --- | --- |
| FFRC-001 | Explicit gateway installation stays fixed; FOOD_CHECKS cannot execute, recover or expose a financial journal original; no native form2 or legacy financial fallback. |
| FFRC-003 | Shared production selector chooses /3, /2 or /1 exactly once; chosen /3 denial, loss, malformed output, unavailable source and valid null never request a lower version. Reduced registries test this read seam, not a remotely admitted profile. |
| FFRC-012 | Fresh Apply Stage compares recording/order/collection/amount/digest with controlled original DATA and rejects wrong-actor or absent original DATA. |
| FFRC-023 | All three form1 actions retain their exact canonical original/digest in the production Client journal before dispatch; failed Stage never dispatches; duplicate Stage and first Submit do not refresh sources. |
| FFRC-024 | A failed terminal-result write prevents disclosure/ACK; actual Client reopen, exact controlled Found and ordered durable remembrance precede actual archive ACK. Wrong identity, malformed output, recording/application/phase or revision inputs are not remembered or acknowledged. |
| FFRC-025 | Lost first reply followed by repeated Found, writer-absence, source-deferred, transport-loss or conflict Lookup retains the same original. No recovery Stage, second Execute, fresh read or replacement identity. |
| FFRC-013 / FFRC-026 | A retained terminal result survives actual Client reopen but does not substitute for a current source-deferred Lookup; pending is unavailable, not a successful empty list; no ACK. This is not Master restart qualification. |
| FFRC-027 | Supporting wrong-actor and retired Client-lease denial only. No genuine L1/L2 or physical-close schedule. |
| FFRC-028 | Client-lease loss before dispatch and after a controlled terminal reply preserves the original; an already received result is durably remembered before retired-client disclosure is refused. Not an actual network partition or Master effects test. |

`JvmEncryptedClientIntentJournal` supplies production AES-GCM, private POSIX file
ownership/locking, atomic writes, fsync, manifest reopen and archive persistence.
Barriers and pre-write failures surround its actual methods; only the production
delegate returns DURABLE. The newly allocated directory and synthetic JCA key are
fixture-owned. The current-custody callback is controlled: it does not qualify
hardware/native keys, rollback fencing or protected physical CASH capacity.

`LocalMasterClient` supplies controlled current-session, protected read, mutation
and Lookup replies. This harness does **not** supply a genuine Master ledger,
native retained original, admitted RPC connection, SDK callback, transport Receipt,
cash capture, application source qualification or Master restart. No controlled
DTO constitutes authoritative commit/source evidence. Full stock/money/audit/outbox
cuts and the native/admitted-RPC portions of the freeze require their separate
production checks. Unsupported original-refusal source qualification remains a
separate gap; a controlled Deferred response does not establish that source.

Selection from the independent POS build:

```text
./gradlew :adapters:app-gateway:jvmTest --tests 'com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1.*'
```

See the linked result record for source-specific compilation and execution outcomes.
