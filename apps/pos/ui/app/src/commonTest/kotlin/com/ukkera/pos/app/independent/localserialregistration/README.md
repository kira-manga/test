# Local serial registration UI bindings

Contract: frozen `local-serial-registration-v1`, unchanged
`qa/pos/designs/local-serial-registration/{README.md,cases.md,fixtures.json}`.

**19 bounded serial JVM methods passed on 2 November 2026**: 12 editor slices and
7 controller slices. The final UI retry also passed 22 existing arrival-approval,
approval-safe-replacement and no-access regressions, for 41 passing UI methods
with no failures, errors or skips. This was a UI retry, not an all-module rerun.
See the [execution summary](../../../../../../../../../../../../../qa/pos/traceability/local-serial-master-client-20261102/README.md).

All 21 whole LSR cases, 164 specification variants, journeys, real composed-card
journeys, native/Master/durable integration and platform variants remain
**UNBOUND / NOT_RUN**.

| Test class | Frozen IDs | Bound observation | Result |
| --- | --- | --- | --- |
| `LocalSerialRegistrationFrozenEditorSlicesTest` | LSR-01, 03, 05–08, 10–13, 17, 19, 21 | Shared editor, source-coordinate checks, exact/frozen input, successor representation, negative holder cleanup/retention, unavailable publication composition | 12 PASS — bounded slices |
| `LocalSerialRegistrationFrozenControllerSlicesTest` | LSR-10–11, 13, 16–17 | Shared runtime, entry model, controller and navigation guard: saved-request review, same-original retry, current-denial cleanup, late attachment response refusal, logout/old callback refusal | 7 PASS — bounded slices |

The initial 41-method UI selection had 40 passes and one failure in
`lsr11And17_currentDeniedPendingReadHidesPrivateDraftButKeepsOriginalOperationIdentity`.
The computed `formFrozen` state incorrectly blocked a protected pending refresh
when an original operation was retained. The source fix allows that read and
known-denial cleanup while retaining original identity; real forms, approvals and
active work stay guarded. All 41 UI methods then passed unchanged expectations.
Editor epoch conversion uses the real `UtcInstant.value: kotlin.time.Instant`
API, not an assumed `epochMillis` member or a `Long` constructor.

The desired predecessor/revision, raw code, interval and original operation/actor
are frozen request/display blueprints. Placeholder authority/allocation/digests
are inert syntax. No native original, authentication, current Master grant,
qualified actual time, physical custody or successful activation is supplied.
Controlled pending/current projections and attachment changes exercise UI cleanup;
they do not establish a genuine durable saved original or real native replacement.
Submit returns uncertainty. No financial or stock success is fabricated.

The controller tests exercise saved-operation review and negative lifecycle
behavior. They do not render the publication card or bind its native review,
head-password signing, exact completion/staging, editable proven-unsigned error
or post-durable-stage permission-loss paths. Separate head signing without local
activation permission and original requester/signer attribution remain unfinished.
The serial native publication composition remains unavailable and its internal
candidate uninstalled. Stock consumers and qualified Android/retained serial time
are not established by these UI checks.
Frozen nonfinite quantity inputs are not rebound as invalid time inputs. Quantity/
transport resource bounds remain UNBOUND. Other-account recovery, restored-route
quarantine, durable restart, genuine NOT_RECORDED races and the independent ledger
ZERO vector remain UNBOUND. No frozen input or oracle was changed.

JVM selectors: `com.ukkera.pos.app.independent.localserialregistration.LocalSerialRegistrationFrozenEditorSlicesTest`
and `com.ukkera.pos.app.independent.localserialregistration.LocalSerialRegistrationFrozenControllerSlicesTest`.
