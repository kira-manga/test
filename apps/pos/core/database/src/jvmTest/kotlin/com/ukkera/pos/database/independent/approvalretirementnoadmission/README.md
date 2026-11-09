# Received no-admission — bounded database slices

The unchanged [ARNA version 1 cases](../../../../../../../../../../../../../qa/pos/designs/approval-retirement-no-admission/cases.md)
and [fixtures](../../../../../../../../../../../../../qa/pos/designs/approval-retirement-no-admission/fixtures.json)
are the expectations. The twelve mapped methods passed on the integrated source, with zero failures,
errors or skips; see the [scoped results](../../../../../../../../../../../../../qa/pos/traceability/approval-no-admission-20261101/README.md).
This is not a whole-case or native acceptance result.

## Mapped variants

| Suite/method | Frozen case and bounded evidence |
| --- | --- |
| Representation: exact frozen body/capture | ARNA-01: public immutable invocation context; manual-discount scalars 7/10000/1000/9000 preserved |
| Representation: changed operation/command/payload/attached approval | ARNA-01/04: public request matching only |
| Representation: equal coordinates/different origin | ARNA-04: distinct invocation/reference carriers; not a gateway acceptance or physical-dispatch fence |
| Representation: invalid sequence/approved original | ARNA-01: malformed public construction safely rejected |
| Representation: generic legacy API results | ARNA-01/08: Denied/Busy/Unavailable/StorageUncertain unchanged |
| Representation: legacy negative sanitation/mismatch | ARNA-01/06: older API supplies no exact accepting origin default; not an old journal migration |
| Scheduling: queue-full | ARNA-01/12: actual prequeue refusal retains the exact live attempt, no prior accepted copy, and no service execution after one scheduler drain |
| Scheduling: stopped endpoint | ARNA-01/12: actual unavailable prequeue refusal; no later service work |
| Scheduling: accepted queue then stop | ARNA-01/08: accepted work abandoned with generic Unavailable, not retroactive no-admission |
| Scheduling: accepted generic replies | ARNA-01/08: admitted Denied/Busy/Unavailable are not upgraded |
| Scheduling: accepted caller cancellation | ARNA-08/12: real caller-independent worker finishes controlled refusal; one dispatch, no native commit claim |
| Scheduling: malformed/legacy ingress | ARNA-01/06: invalid binding rejects generically; legacy prequeue refusal stays generic |

The fixture reads only this frozen packet. Manual-discount JSON is an inert exact
request-body carrier on an **uninstalled fixture command key**, not a claimed public
manual-discount action. A fresh file-backed Room database uses BundledSQLiteDriver,
the generic durable builder and all ordinary protected/immutable callbacks. It has
no seeded branch, native history owner, accepting principal verifier, authorization,
handler, grants, preview or receipt. Exact empty application-table counts before and
after establish no fixture database mutation, not the frozen stock/cash business
baseline. Worker/watchdog scheduling is controlled, not platform-qualified.

## Explicitly UNBOUND

- The actual manual-discount gateway/controller/journal workflow: the current action
  approval client is restricted to unpriced arrival v2; there is no supported manual-
  discount action/approval ceremony to bind ARNA-02–11 here.
- Real supported legacy journal migration, completion persistence/reopen, all actor/
  session/drawer and prior invocation-history preservation assertions.
- Public no-admission serialization/remote origin, copied physical dispatch D2, durable
  replay disposition, reconnect/late-execution fencing and lost-negative recovery.
- Genuine current-Master retirement/REQUEST/APPROVE/PERFORM checks, native authority,
  accepting business outcomes, terminal receipt authenticity and postcommit release.
- Native/device/process/power-loss schedules, numeric stock/cash campaign, language,
  physical gestures, OS lifecycle and navigation capacity.

Only individual mapped slices may receive execution outcomes. All twelve whole
frozen cases remain NOT RUN/UNBOUND; no full-case or native closure is implied.

Selectors:

```text
com.ukkera.pos.database.independent.approvalretirementnoadmission.ApprovalRetirementNoAdmissionRepresentationTest
com.ukkera.pos.database.independent.approvalretirementnoadmission.ApprovalRetirementNoAdmissionEndpointSchedulingTest
```
