# Local endpoint client: authentication and durable lifecycle

24 October 2026 source cut. **LOCAL ONLY; authored, not independently verified or owner-accepted.** No backend work, payment provider, default administrator, accepting synthetic peer, OS-key qualification or protected physical-outcome capacity is supplied here.

## Concrete entrypoints and ownership

`adapters/app-gateway` now contains:

- `LocalEndpointBranchGateway(connections, credentials, intents, dispatcher)`: a real client of `LocalMasterEndpoint.signIn/current/refresh/logout`, supplying the existing typed workflow gateway.
- `LocalMasterClientConnection(endpoint, peer, serviceIncarnationId, installed, labels, currentLifetime)`: an exact trusted-root observation. `peer` must be the actual authenticated physical connection used by the endpoint; command/login UUIDs cannot create it. `installed` contains actual registry keys, not advertised capabilities.
- `LocalClientIntentJournalSource.current(owner)`: an already-open, root-owned actor/device/scope journal. It must not create an empty queue after a missing/unreadable journal or silently substitute another actor's journal.
- `LocalEndpointEnrollmentGateway(connections, credentials, offerings, dispatcher)`: actual provisioning and original-definition lookup. `LocalEnrollmentOffering` supplies a current, explicitly configured **requested definition**, never a permit or default account.
- `JvmEncryptedClientCredentialJournal.createNew/openExisting(JvmClientCredentialCustody)`: actual encrypted binary token and pending-authentication storage, separate from `JvmEncryptedClientIntentJournal` business intent storage.

The lead composes these with the real JVM host. The connection lifetime must capture the exact `JvmMasterHost.status.active` instance, its service incarnation/endpoint and `isCurrent()`. Publish null before maintenance; a qualified resumed host uses a new instance. The client never invokes master store factories, `stopAccepting`, maintenance, restore, promotion or host closure.

On client-connector teardown, the root can call each gateway's `closeAndAwait()` before closing its shared journals. This waits for **client** owned buffers/IO only, not completion of already accepted master work. Blocking provider IO still requires actual platform supervision; no timeout is called proof of master quiescence.

Native source entrypoints are `runUkkeraDesktop(AppDependencies, args)`, the Android `PosApplication.clientDependencies()` composition override, and `IosAppHost(AppDependencies)`. Default entrypoints remain visibly unconfigured. Root still owns module dependencies, protected provider loading, platform projects and actual runtime installation. Desktop's Compose application uses `exitProcessOnExit=false` so closing the UI does not terminate a separately supervised cohost. Android/iOS background handling clears local interaction without asserting master revocation.

## Password sign-in and client cancellation

1. Validate the exact case-sensitive identifier; do not trim or normalize another account.
2. Obtain the actual current peer/endpoint/incarnation and protected client credential journal.
3. Durably record a client-local sign-in dispatch marker **before** calling the endpoint. This identifier is not a server authentication idempotency key: that public sign-in API has none.
4. A bounded client-owned worker awaits the actual endpoint result even when Compose cancels its wait. It owns/wipes a separate password array. Undelivered session capabilities are closed; neither cancellation nor a late reply may attach the next actor's UI.
5. Persist the returned access and refresh credentials plus exact actor/device/scope/session/generation/deadline binding before publishing an interactive session. Re-read `endpoint.current`, bind the actual intent journal, and construct display hints from protected policy. Account text is the actual login identifier; company/branch labels must come from actual root metadata or explicitly labeled IDs.

Tokens are never a substitute for current authorization. Every workflow call obtains an owned access snapshot, re-reads protected current state and passes credentials through the actual endpoint/kernel. A connection/peer/endpoint/lifecycle change invalidates leases immediately on observation. The adapter watches host connection and endpoint-health flows and checks local peer lifetime; UI current polling is scheduling only, never a wall-clock expiry approximation.

No stored credential can automatically restore an interactive login. After process restart all retained material is cleanup/recovery material until a new actual password sign-in. Lock invalidates interaction synchronously; no financial intent or master result is deleted.

## Refresh, logout and ambiguity

Refresh is serialized with this session's workflow journal access. Before dispatch, `REFRESH_PENDING` is durable and previous interactive leases are invalidated. Only a validated actual response can atomically replace the old stored tokens with both new credentials and the new session identity. Original work expiry must remain identical; the service is the expiry authority. New policy revision requires another authenticated UI root rather than preserving stale hints.

An unavailable/lost/uncertain refresh is not resent. **Logout with an old token revokes only its original session, not a possibly committed successor.** The journal retains `REFRESH_PENDING` even if that old-token call returns `Revoked`. Explicit password sign-in with user-confirmed device move can fence prior same-actor refreshes under the qualified writer; it does not rewrite business operation IDs/actors.

Unknown **password sign-in** is different: an earlier queued request may not reach server capture until after a later device move. A new successful login therefore does not blanket-clear old unknown login markers. No invented completion, queue ordering, current-epoch flag or ordinary journal receipt resolves them. This cut leaves those markers for qualified supervised completion/fencing/reconciliation; it has no client "dismiss uncertainty" operation.

For known sessions, explicit logout first persists `REVOCATION_PENDING`, then uses a current same-device authenticated connection to call the endpoint. It remains callable after the UI closes the capability. Only the actual `Revoked` response plus durable client completion removes that cleanup entry. A cancelled UI wait does not cancel accepted client cleanup; process termination leaves the encrypted marker. Locked-screen status/retry reveals only availability/counts/uncertainty, never previous logins, actors, tokens or policy data. It excludes the current interactive attachment from orphan cleanup and never shuts down the master.

## Encrypted storage profile

Both concrete JVM journals require a pre-existing canonical private POSIX directory (`0700`), private files (`0600`), exclusive file locking, current file/directory identity, an injected actual AES `SecretKey`/provider handle and protected process-context lifetime. `createNew` is an explicit initialization ceremony for a genuinely empty directory; `openExisting` cannot repair, recreate, reset or erase missing/corrupt/unknown evidence.

The credential file is one bounded binary snapshot (`sessions.v1.enc`, up to 4 MiB plaintext). It allows at most 32 combined login/session records and 32 bounded requested-enrollment receipts. Access and refresh are exact domain-separated 52-byte owned binary values; syntax/locator checking is not authentication. They never become JSON/base64/hex strings. Managed login/policy/enrollment metadata strings cannot be securely wiped, a limitation not hidden by encryption.

AES-GCM uses a fresh 12-byte nonce, a complete tag, and domain-separated AAD binding schema, branch/device, journal/key and purpose. Writes force a temporary file, atomically replace the encrypted head and force the directory. No nonatomic/plaintext fallback exists. Unknown temporary files, validation/tag/IO failures or unexpected encrypted-head changes withhold access and preserve evidence. In-process head hashes are **not restart rollback proof**. Root qualification must establish no copied/rolled-back credential or intent journal and permitted OS key/provider/filesystem custody. Windows, Android and iOS storage profiles are not provided by this JVM implementation.

Factories transfer file-lock/channel ownership only **after** the cancellable IO-to-caller dispatcher boundary. A completed open whose result is cancelled releases resources in non-cancellable cleanup without deleting evidence. `close()` similarly releases locks/channels even from a cancelled caller. Decoded credential snapshots have the same cancellation-safe owned handoff and are wiped after use.

The ordinary business journal remains a separate immutable canonical request/result lifecycle. It is not the credential store and contains no passwords/tokens. Neither journal proves preallocated, protected physical-payment/refund outcome capacity. Positive collection/refund initiation remains disabled until separately qualified master **and** client capacity exists; a disk-space check, ordinary slot count, `SecretKey`, operation ID or allocation selector is not such proof.

## Enrollment and administration boundaries

Before initial provisioning, the client retains the original request ID, actor/login, complete requested grants/limits/delegation ceilings/throttle/session/token/approval policy, exact admitted-device set and credential-presence fact. It retains no password/verifier. The endpoint's protected current ceremony must authorize the complete digest. Retry of a retained ID performs only original-definition lookup, never another password submission or a newly generated definition.

`NotRecorded` is an authenticated writer-cut absence, not proof no in-flight ceremony can commit. Denied/unavailable/uncertain lookup stays unresolved. Actual `Applied` is credential administration only, not login; a failed client receipt cannot undo that known server result. The endpoint has no separate employee display-name field, so this adapter explicitly hides that field rather than pretending to save it.

Current account/device target projections and exact administration revisions are not public in the installed cut. Although the endpoint exposes reset/disable/session/device revocation APIs, the UI does not invent targets/revisions or promise physical peer retirement from a historical receipt. Approval issuance/root endpoint wiring and full employee/device administration remain follow-on work.

## Evidence and remaining gates

This implementer did not author/edit tests or expectations, inspect QA source/cases, run builds, launch the app, run the historical full verifier, or work on backend acceptance. Incidental team-status output containing completed QA summary metadata was disclosed to the lead and not used to derive tests. The earlier guarded successful compile packet beginning `20261024T122715` predates this source cut.

Required before any readiness claim: lead-owned combined compilation; actual root/runtime/provider/platform composition; independently designed/executed UI/service/durable-storage/communication/failure-recovery evidence; real native platform and hardware qualification; the remaining full restaurant/café/clothing/supermarket workflows; runnable local setup/data/accounts and owner hands-on testing. Central remains held.
