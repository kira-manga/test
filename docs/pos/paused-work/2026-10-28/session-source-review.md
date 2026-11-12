# Bounded correction source review — checkpoint

Date: 2026-10-28. Reviewer: `/root/android_installation_troubleshooter`.

## Scope and verified identities

This reviews only the correction of original findings R1–R3 and directly related holder/dispatch lifecycle edges. It is source peer review, **not** independent test design, acceptance execution, build/native/device qualification or integration approval. No tests/oracles/QA material were inspected; no builds, application/native/SDK execution, Git/network, delegation or live edits occurred. On the owner's checkpoint/pause request I finished this bounded handoff only; no implementation or additional review areas were started.

New sealed input: `.workspace/lead-local-session-attachment-correction-20261028`.

- `SEAL.sha256` SHA-256: `f5dbcfc06006ffd1655bac9191a0b9298016a5c2a36822d8014b5cce1d956925`.
- Bound `SHA256SUMS` SHA-256: `eee5672a9ae32155adf8242e809fee482877440f48f359cfcb8b5d2416b2f25b`.
- `corrections.patch` SHA-256: `4adff0981c40f100d7540e74ccb95f2df0722668368b1cfb962edbe5e889961c`.
- All 26 payload hashes and all ten manifest preimage/postimage hashes/lengths verified. New preimages equal the original sealed preimages. Both the exact 499-line original-postimage-to-corrected patch and the cumulative preimage-to-corrected patch were reconstructed from source bytes and matched. `DESIGN-INDEPENDENCE.json` was hashed only, not read.

Original input/review remain untouched. Original seal-file hash remains `80877dc9eea5aa150f646519d345d9318d68d9544a0710ff743b7c9d1779bfc7`; original `REVIEW.md` remains `855848edcc9f86cfc03ec171b00f6a0a0b48e84d55c5396193ad19141e23a423`.

References below are corrected sealed postimages under `ui/app/src/commonMain/kotlin/com/ukkera/pos/app/`, unless identified as public/live.

## Original findings: correction status

### R1 — Corrected at source

The erroneous workspace key members are removed. Immutable key captures now belong to `LocalRetailPhysicalController.kt:95` and `LocalSupplierCashController.kt:74`, alongside their physical gateways. Their existing activation/current/Start references bind to these controller members. The specific unresolved-binding source defect is gone. No compiler result is claimed.

### R3 — Corrected at source

Both controllers capture a separate immutable workflow ACK gateway at construction: retail line 97; supplier line 76. ACK dispatch uses that member at retail line 565/supplier line 365, rather than a late `session.workflows` getter.

Their lazy dispatch also checks `current(ticket)` before invoking the body: retail line 756/supplier line 496. Generic/admin helpers received corresponding dispatch checks (workflow line 1513; administration line 356). Result-side checks, physical undelivered-instruction cleanup, and transient-password completion clearing remain present. These changes repair the specifically identified successor-ACK getter path; service-side attachment/credential-lock fencing remains necessary.

The cards also add dispatch checks against their captured attachment ownership and local job generation (catalog line 151; food line 172). This is not proof of all navigation/dispatcher interleavings or of revocation of work already accepted by a service.

### R2 — Specific transient child-loss defect corrected; cleanup edge remains

Workflow and administration StateFlows synchronously seed safe continuation (workflow line 118; administration line 69). Catalog child selection now uses the actual route area, not temporary workflow projection (`ui/LocalWorkflowScreens.kt:281–300`).

`EntryViewModel` owns two distinct data-only holders (`UkkeraRuntime.kt:572–574`). Their editors/original IDs/notices are no longer child-local remembers. Holder retirement resets only never-staged approval while preserving frozen bodies/IDs (`LocalCompanyCatalogPublicationCard.kt:58–69`; `LocalFoodConfigurationCard.kt:57–76`; runtime 608–619). True ViewModel clearance clears both holders (runtime 620–633).

Child disposal no longer clears them: catalog 177–188; food 307–319. Jobs, status/current reviews, exact approval and head-login/password input remain attachment/composable-local and are cancelled/cleared. The old-attachment disposal callback checks ownership before reporting a guard, so it does not deliberately clear successor guard state. The parent reads holder draft state and reattaches dirty guards even offscreen (screen 190–222).

This materially closes the original composition-removal ownership hole. It does not establish the complete denial/inactive cleanup claim; see C1 and the remaining edge below.

## C1 — Native-card DENIED does not reach the new holder cleanup

**Supported source lifecycle issue; no executed security or financial failure claimed.**

The corrected packet says observed DENIED clears holders, but the only screen-level denial cleanup watches **generic** `state.readProblem` and `state.pendingProblem` (`ui/LocalWorkflowScreens.kt:244–251`).

The native cards have independent responses/problems. Their `unavailable` functions merely set local problem/nativeReason and handle SESSION_ENDED: catalog 124–127; food 145–148. A native-card DENIED therefore neither calls the holder's clear method nor reports that denial to the parent cleanup. Current review/completed/picker and head input are also not cleared by these handlers. Status failures explicitly null status, but other denied paths need not do so. The editor/original-ID holder remains intact after such a response and after later child disposal.

This is not merely a hypothetical error value: public/live company/food gateways propagate GatewayFailure problems into their native-card result types, and both also map `SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED` to DENIED. The latter is important: separate head-authentication refusal must **not** automatically be treated as employee-session retirement or proof that an original native request was never retained.

Before relying on a comprehensive denial-clearance claim, distinguish employee access denial from separate head-auth refusal and route the applicable denied presentation to explicit holder/projection/secret cleanup. Preserve real durable native/journal evidence; do not erase or recreate an original operation in response to this UI issue. I made no implementation change at checkpoint. This packet should stay staged if this lifecycle claim is an integration requirement.

Public/live sources consulted only for this denial routing:

- `adapters/app-gateway/.../LocalEndpointCompanyCatalogGateway.kt`, status/prepare problem propagation at 37–47/56–92, access at 308–326 and native refusal mapping at 329–332. SHA-256: `2fb9016cbe2ce761d09867d63d75dfdfdd1997c23957a54924b95447f2a29951`.
- `adapters/app-gateway/.../LocalEndpointFoodConfigurationGateway.kt`, status/read/recipe problem propagation and native refusal mapping at 395–438. SHA-256: `dfc2f48e8f3e1663e38ad15788eb1fc02fb556fd7ac3285f49c86dc3e4b05468`.

## Remaining bounded, unverified lifecycle edge

Inactive clearing is performed by a Compose effect keyed to the **active Boolean** (screen 176–182), or by ViewModel clearance. The correction supplies no synchronous holder-departure registration at navigation mutation. If an away-and-back is coalesced before the screen observes an inactive composition, an unchanged true key does not itself record that departure. The original navigation token fences old requests, but holder data are not navigation-revision keyed. Complete inactive/explicit-discard cleanup for that edge is consequently **not established by this source correction alone**. This is an unexecuted integration edge, not a newly claimed service-authorization bypass or an acceptance test specification.

Temporary null controller returns occur after registering the active-entry effect (screen 179–186), so they intentionally preserve the holder. Ordinary observed inactive composition clears it, and root/ViewModel teardown clears it. Offscreen disposal preserves it. These source behaviors should not be confused with evidence for every lifecycle schedule.

## Checkpoint disposition

R1 and R3's particular source defects are repaired; R2's child-local retention mechanism is replaced with real entry ownership. C1 and the explicitly unverified inactive edge prevent a blanket lifecycle approval. No new correction packet was authored and none of the staged UI bytes were integrated by this reviewer.

The SDK gateway remains a separate staged dependency. I did not rereview its moving implementation in this bounded correction pass; the original review's observed hash is not a seal of a later version. Builds, common/gateway compatibility, Compose scheduling, native/device rights and independent acceptance remain lead-owned and unverified here. The owner's safe-checkpoint/pause request takes precedence over any further work: this review is sealed as a handoff, then work stops.
