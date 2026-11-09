# Local UI workflow integration

Status: production UI plumbing authored; application acceptance is **not** established by this document.
Owner directive: **LOCAL ONLY**, all four activities remain required, and all central/backend work is held until the owner approves the local system after hands-on testing.

## Ownership and limits

`app_shell` owns production `ui/app`, `adapters/app-gateway`, the currently released native client entrypoint sources, and their integration notes. The lead owns build/dependency/project changes, trusted runtime/provider composition and combined evidence. Independent designers/executors own new acceptance cases and expected results; this implementation does not author or modify them.

Requirements remain the original handoff at `/root/projects/ukkera/ukkera-pos-agent-handoff-2026-10-21` and the reviewed plan at `/root/projects/ukkera/ukkera-pos-plan-review/FINAL-PLAN.md`. A usable first checkout, a training screen, a successful compilation or a handler-level test is not completion of the agreed local product.

The new UI is a client of real services, **not** an in-memory financial implementation. Default dependencies still reject sign-in/provisioning and expose no live operations. A platform must explicitly inject actual adapters. Merely setting `configured`, returning a form descriptor or adding an enum entry is not capability qualification.

## Production entry points

- `AppDependencies.kt`: branch authentication/session, explicit local enrollment, platform hints and the central-phase gate.
- `LocalWorkflowContracts.kt`: bounded, closed workflow/action/field/reference descriptors and exact command values.
- `LocalFormInput.kt`: transient editors and exact localized scalar parsing.
- `LocalWorkflowController.kt`: entry-owned read, stage, submit and recovery coordination.
- `ui/LocalWorkflowScreens.kt`: searchable authorized projections, action forms, repeated lines, retained-operation review and recovery.
- `UkkeraRuntime.kt`, `UkkeraApp.kt`, `navigation/*`, `ui/EntryScreens.kt`: entry/root ownership, local admin navigation, explicit enrollment, sign-in, lock and sign-out.

### Routes and required local families

| Branch area | Workflow views | Actual service obligations (not implementation claims) |
|---|---|---|
| Sell | Checkout | Current catalog/variants/modifiers/recipes, exact quantities, price/tax authority, cashier ownership, reservations, prepared collection, external attestations and durable finalization for each supported activity/channel |
| Orders | Orders; kitchen; fulfillment; exceptions | Order changes/handoffs/cancellation, original-sale returns/refunds/disposition, real preparation/waste/fulfillment transitions and explicit reconciliation |
| Cash drawer | Cash | Drawer/custodian/safe configuration, opening source, close phases/review, safe transfers, authorized non-sale movements |
| Stock | Inventory; receiving | Separate ownership/custody quantities, receipt allocations/conversion versions/lot/expiry, inspection, protected opening, captured counts, recall and warehouse movement |
| Catalog | Catalog | Product/variant/recipe/pricing definitions and activation with current local authority |
| Purchasing | Purchasing; receiving | Suppliers, purchase commitments/approval/cancellation, receiving lineage; no receipt-as-invoice/settlement shortcut |
| Reports | Reports | Authorized source reads, exact totals and supported exports; unavailable is not zero |
| People | People | Current local employee/account administration, expected revisions and grant changes |
| Devices | Devices | Explicit device admission/revocation and local transport trust |
| Setup | Setup | Branch/activity policy, protected setup sources, backup/recovery and platform-qualified hosting/peripherals |

These branch-local areas are filtered by the session display's allowed areas for presentation. That display is **never authority**; every service request must check current local policy. Catalog, people, purchasing and devices do not require a central human login just because they are administrative.

Restaurant/café kitchen and fulfillment views remain visible as required local workflows. An installed adapter must report `NOT_IMPLEMENTED`/`NOT_CONFIGURED` when their actual transitions are absent; it must not reinterpret them as retail checkout or manufacture a successful screen. Clothing variants and supermarket lot/quantity rules likewise remain owning-service obligations.

## Session-owned workflow port

```kotlin
interface LocalWorkflowGateway {
    suspend fun read(query: LocalWorkflowQuery): LocalReadResult
    suspend fun stage(intent: LocalCommandIntent): LocalStageResult
    suspend fun submit(operationId: OperationId): LocalOperationResult
    suspend fun lookup(operationId: OperationId): LocalOperationResult
    suspend fun pending(cursor: String? = null): LocalPendingResult
    suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult
}
```

`BranchUiSession.workflows` supplies this port for that exact interactive session. The adapter owns credentials, transport lifetime, the principal, command codecs/dispatch, durable client intent storage, and the result journal. UI action enums are a closed mapping to versioned handlers, not arbitrary string commands or a server-selected program. Unknown action/field/version combinations must be refused.

### Reads and forms

1. A view requests the current authenticated pending page and an independently authorized workflow projection.
2. An action form is returned only for an actually installed, known handler with current read authorization. It carries versioned typed fields, bounded reference options and, where applicable, current initial values/lines.
3. Clicking a record obtains a fresh service projection. UI selectors are not trusted pricing, approval, actor, inventory, or revision snapshots.
4. A read failure clears the old page/form; a denied read also clears private projections and editor material. `SESSION_ENDED` immediately replaces the security root.
5. Pending reads have bounded cursor pages. A later empty page does not prove the entire queue empty. The UI conservatively prevents new commands while any unresolved operation, unknown queue state or later recovery page remains. Returning to an authenticated empty first page is required before new commands are offered.

Adapters must not silently truncate the durable recovery queue, treat missing/incomplete history as absence, or return an empty projection for storage/authorization failure. A workflow page is capped at 100 records and 32 forms; individual fields/options/lines are independently bounded. Handler and transport response limits still apply.

`LocalRecordReference.revision` is the service's **domain** revision. In particular, cash domain revision zero is not storage generation one. Stock's public per-item storage preconditions have a separately named `EXPECTED_ITEM_STORAGE_REVISION` input; adapters must obtain the actual value and preserve its meaning rather than derive it from a domain revision.

### Exact input

- Public values are typed text, identifier, integer, decimal string, date, choice, reference, bounded multi-selection or flag. Monetary/quantity values never cross the UI port as `Float`/`Double`.
- Arabic-Indic and Persian digits, and the Arabic decimal separator, are normalized without grouping, exponent notation, rounding or a floating-point conversion.
- Integer range, canonical identifier syntax, actual calendar dates and required fields are checked before staging. Owning services still enforce money/quantity scales, sign/range, currency, units, date policy and all business constraints.
- Required checkboxes require an affirmative value. Form-level confirmation is separate and never initialized as consent.
- Repeated-line identifiers are retained while editing and through staging. Whole proposals are submitted once; the UI does not turn one receipt/count/order into a loop of partial financial mutations.
- Editor and intent scalar material is capped at 48 KiB. This is not a promise that the resulting canonical DTO fits a handler's smaller wire bound; the adapter must validate that before staging. Do not split a required atomic operation merely to get around a bound.

### Durable operation lifecycle

1. **Edit:** transient form data has no business effect. Dirty entry guards prevent accidental discard.
2. **Save for review:** allocate one `OperationId`, freeze the exact form version/reference/values/lines, then call `stage`. The adapter must durably retain the original ID, immutable canonical request and original actor before allowing dispatch or external collection.
3. **Review:** only a `Staged` reply establishes durable intent storage. It is not a completed sale, payment, refund, stock movement or cash close. Input becomes read-only.
4. **Submit:** a separate explicit review/confirmation sends only the saved ID. `submit` must use the original stored body and actor and independently reauthorize continuation; it must not rebuild a request using current prices or assign a different actor.
5. **Recover:** cancellation, transport loss or an unknown reply never creates a new operation. The UI checks the original ID. Uncertain staging can retry only the same frozen intent. A restart/navigation transition relies on the adapter's durable queue, not an in-memory UI flag.
6. **Acknowledge a recorded result:** an explicit user action durably archives only the client presentation entry after authenticated master lookup matches the original actor/ID/body and retained result. A terminal result stays in the durable queue until this step succeeds. Cancellation before presentation cannot silently clean the queue. A lost acknowledgement retains the known business result, not an invented unknown payment; retry uses the same identity. The UI reloads an authenticated first pending page before permitting new work. No master/financial/audit evidence is deleted.

`LocalPendingPhase` distinguishes never-dispatched staged intent, unresolved dispatch, and recorded-but-unacknowledged result. The last phase carries the actual retained committed/rejected decision and never offers submit. A service-reserved financial observation uses `LocalActionForm.reservedOperationId` verbatim, rather than minting another collection/refund recording identity; the owning handler still checks the immutable reservation binding.

`LocalPendingOperation.summary` must describe the **stored immutable request**, not a newly reconstructed form. `submitAllowed` defaults false and is only a current continuation hint. Recovery submission requires an authorized retained-request review; after restart the UI does not blindly submit an unseen body using its label alone. The service remains authoritative even when the hint is true.

`NotStaged` is an unusually strong result: it certifies neither staging nor dispatch occurred. Only that contract permits corrected input/new attempt identity. A network/storage exception, missing reply, busy-after-admission or denied lookup is not `NotStaged`.

`Recorded(APPLIED/REJECTED)` may be returned only after the **outer** local transaction has durably committed its result, effects, audit and outbox together. Neither an inner transaction callback nor an external cashier attestation is sufficient. `Unresolved` is not a failed payment, authoritative absence, safe fresh-ID permission, or permission to collect/refund again.

The UI never invokes a payment processor. Actual external payments remain cashier attestations. Adapters must expose payment attestation/finalization only under the owning service's prepared collection and original-sale/refund constraints; no generic checkbox authorizes external collection.

## Authentication, enrollment, lock and cleanup

The 24 October authentication cut adds actual `LocalEndpointBranchGateway` and `LocalEndpointEnrollmentGateway` adapters, plus a separate JVM encrypted credential journal. See [LOCAL-GATEWAY-LIFECYCLE.md](LOCAL-GATEWAY-LIFECYCLE.md) for their precise composition, custody, cancellation and unresolved-authentication constraints. The no-argument platform launchers still have **no accepting provider**; injection entrypoints are not a completed platform installation.

Branch account identifiers remain exact and case-sensitive: no trimming, Unicode normalization or cloud aliases. Sign-in is single-flight and entry/root-fenced. Credential arrays are wiped even if their coroutine never starts; unclaimed late sessions are closed instead of being adopted by the next actor.

Local enrollment is a separate `LOCAL_ENROLLMENT` root. It requires an explicitly injected gateway and explicit user acknowledgement. The UI distinguishes `LOCAL_QUALIFICATION_ONLY` from `OWNER_AUTHORIZED_LOCAL` and never infers administrator/provisioning authority from an empty database or a UI checkbox.

```kotlin
interface LocalEnrollmentUiGateway {
    suspend fun status(): LocalEnrollmentStatus
    suspend fun enroll(requestId: DomainId, account: String, displayName: String,
                       password: CharArray): LocalEnrollmentResult
    suspend fun lookup(requestId: DomainId): LocalEnrollmentResult
}
```

The adapter must retain the original definition/ceremony identity (and credential-presence fact where the auth contract needs it) before provisioning. `status.pendingRequestId` re-exposes its own unresolved request after restart. UI lookup is limited to that original identity; it is not a free-form event-ID/credential existence oracle. The actual authentication service must recompute the original digest and reauthorize the current provisioning ceremony. Absence, denied authority and an unavailable lookup do not imply account creation or permission for a new request. Account creation does not automatically authenticate a session.

Passwords are transient input only. Compose input-string references are cleared after submission/disposal; the mutable arrays sent to gateways are zeroed. The UI does not claim that immutable strings can be securely overwritten in managed memory. Passwords/tokens never enter route checkpoints, saved form state, workflow records, notifications or logs.

**Lock now** is always available within a live branch workspace and bypasses dirty/critical leave guards to hide private data immediately. Lock invalidates interactive access, clears root/entry owners and does not claim durable master revocation.

**Sign out** also clears the root first, then invokes `BranchUiSession.logout()` for revocation-only cleanup after `close()`. Only `REVOKED` confirms cleanup. Pending, busy, denied or unavailable cleanup leaves an attention state; the adapter must preserve durable revocation evidence/credentials for its own authorized cleanup/recovery. UI/host removal must not stop the master or delete/relabel operation evidence. This UI does not fabricate background retry success.

The locked/sign-in screens can explicitly re-read and retry the adapter's saved revocations, including after client restart, without displaying prior accounts or credentials. A current workspace observes the session lifecycle and rereads protected current state periodically; a separately labeled renewal control calls actual refresh. Scheduling delay is not an expiry clock or authority. A changed policy requires a new authenticated root. Backgrounding Android/iOS locks immediately; it does not claim logout. Desktop UI closure no longer calls `System.exit` through Compose's default application exit behavior.

Session replacement requires a separate unchecked consent field, reset on account changes and each submission. It never deletes pending business operations. An unknown refresh is not blindly retried; old-token revocation is not successor revocation. An unknown queued password sign-in remains unresolved even after a later successful device move unless a qualified completion/fence is established. The UI reports this limitation rather than declaring cleanup complete.

The actual enrollment endpoint accepts an exact account identifier and approved requested policy, but not an employee display name. Its adapter reports `supportsDisplayName=false`, and the form explicitly explains and omits that unsupported field. A root-supplied requested definition is not a permit: the actual endpoint must authorize its complete digest. No local administrator/default credential/device is generated from a checkbox or empty directory.

`centralServicesEnabled` defaults **false**. Both navigation entry and the actual central sign-in branch enforce the gate, and the welcome view explains the owner hold. Explicit company **training** remains an isolated synthetic workspace, not central access.

## Actor/entry lifetime and presentation

- Each controller belongs to one entry ViewModel, one captured root and the exact authenticated session object. No cross-root controller/service singleton is introduced.
- Every async response checks cancellation plus the same activation/entry/session after the await. Departing entries invalidate their activation before canceling work, and clear private data even if a gateway ignores cancellation.
- Navigation actions and business actions require a current full navigation token. Guard changes may change navigation revision without replacing the entry; each async request captures its full navigation token **after** its own guard registration and also checks the same activation/session. Even rapid away-and-back navigation before Compose disposal invalidates the reply. A single immediately preceding **self-generated guard revision** may update only ephemeral IME/scanner editor text before recomposition; it never authorizes dispatch.
- Stage/submit/acknowledgement phases use a critical guard. Unsaved edits use a dirty guard. Durable/uncertain gateway evidence is not described as discarded when an entry leaves.
- Arabic/English resource pairs and RTL direction remain app-local. Numeric identifiers are directionally isolated. Native focus traversal, labeled fields, 48dp action targets, searchable selectors, polite/assertive status announcements and the existing text-size-aware compact/sidebar layout are retained.

## Verification and remaining gates

The lead's guarded two-stage packet beginning `20261024T122715` passed gateway JVM/Android and Android-debug/desktop compilation after the narrow nullable-smart-cast/POSIX-attribute corrections. The prior failed packet remains preserved. **That compilation predates this authentication/lifecycle source cut** and is not application/provider/UI acceptance. This newest source requires the next lead-owned guarded compile and independent verification. No build, application launch, QA-case authoring, or historical full verifier/backend task was performed by this implementer for this cut.

Current bounded workflow mapping includes actual cash/configuration/transfer reads and commands, retail base-unit orders/handoffs, zero-payable collection begin/finalization, stock quantity/count/recall/transfer projections, approved receipt/opening proposals and configuration reads. Positive physical payment/refund initiation remains disabled: neither ordinary client journal is qualified physical-outcome recording capacity. Serial UI, full variants/modifiers/recipes/prices, kitchen/fulfillment, returns/refunds, supplier settlement, full count-surplus approvals, safe-delivery close editing, reports and protected account/device administration projections remain incomplete or unavailable. These are required local obligations, not central-only deferrals.

This document does not certify that any platform has injected the required actual adapters or that all workflow families are implemented. Required remaining work includes actual authenticated service/transport/persistence composition, independent frozen-case execution through the real UI, interruption/restart/actor-change recovery, all four activity workflows, runnable local setup/account/data preparation, and owner hands-on verification. Windows/macOS/Android/iPhone/iPad runtime behavior and hardware/fiscal/peripheral qualification must remain unverified without actual evidence. Central/backend acceptance remains held.
