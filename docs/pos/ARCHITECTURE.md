# Navigating the local POS

Start with [the continuation guide](AGENT-HANDOFF.md) for integration boundaries and
[CURRENT-STATUS.md](CURRENT-STATUS.md) for delivery limits. This document explains
the source layout; it is **not a claim that all runtime connections are complete**.
The POS is its own Gradle build under `apps/pos`. Queue is a separate product.

## Module map

Paths below are relative to `apps/pos`.

| Path | Responsibility | Start here |
| --- | --- | --- |
| `core/types` | Exact money/quantity, IDs, time and shared value types | `Money`, `Quantity`, `DomainId` |
| `core/security` | Local identity, permissions, sessions, protected storage, original company/installation authority | Local authentication/authorization contracts; platform key owners |
| `core/recovery` | Recovery state, affected resources, admission/fencing rules | Recovery policy and resource selectors |
| `core/database` | Room storage, atomic command kernel, retained results/history, protected local auth and native-writer integration | `MasterRoomDatabase`, `AtomicMasterStore`, `LocalMasterEndpoint` |
| `domain/catalog` | Catalog, company policy and purchasing rules | Catalog and purchasing domain types |
| `domain/pricing` | Exact pricing, promotion selection, taxes and rounding | Pricing/quote calculators |
| `domain/inventory` | Inventory quantities, allocations, reservations and valuation | Inventory planners and state |
| `domain/sales` | Orders, collections, returns, drawers and food business transitions | `Orders`, `Collections` and food domain types |
| `adapters/inventory-store` | Durable inventory commands, reads and retained-producer checks | Stock receiving, normalized stock repository and planners |
| `adapters/sales-store` | Durable sales/food/cash/returns, physical observations and reports | Order/collection handlers, financial contracts and food handlers |
| `adapters/catalog-store` | Authoritative catalog/policy/purchasing joins and production command registration | `LocalBranchCommerceRegistry` |
| `adapters/app-gateway` | UI-to-local-service mapping, request journals, result recovery | `LocalEndpointBranchGateway`, `LocalEndpointWorkflowGateway` |
| `adapters/android-native-files` | API24+ protected native descriptor operations and streaming directory enumeration | `AndroidNativeFiles`; JNI/NDK only, no business authority |
| `adapters/local-rpc` | Official P2pKit dependencies, original login, exact physical-session binding and scoped business RPC | `LocalRpcFactoryOwner`, `LocalRpcMasterSessionIngress`, `LocalRpcIdentityBinding`; shared and Android composition is connected, with full native/admission/RPC qualification still incomplete |
| `adapters/recovery-store` | Shared backup/recovery policy with concrete JVM/POSIX storage | `LocalBackupPolicy`, `LocalBackupSnapshotPolicy`, JVM recovery ports |
| `adapters/local-master-host` | Concrete JVM/POSIX host composition | Host lifecycle/composition; not a qualified mobile host |
| `ui/app` | Shared Compose UI, navigation, drafts, controllers and UI-facing contracts | `AppDependencies`, `UkkeraRuntime`, `UkkeraApp` |
| `apps/android`, `apps/desktop`, `apps/ios` | Platform entry points and application-owned lifecycle | Android `MainActivity`, desktop `Main.kt`, iOS entry project |

## Shared policy and native adapters

The architecture is one KMP implementation, not an Android workflow copied to
other platforms. Reusable setup, business, authentication, permission, RPC and
recovery decisions belong in `commonMain`. Native code supplies actual OS keys,
private files, clocks, SDK sessions, lifecycle and resource ownership; interfaces
are composition ports, not permission or proof of native acquisition.

The 28 October consolidation puts these live paths in shared code:

| Shared implementation | Native boundary / preserved invariant |
| --- | --- |
| `app-gateway/.../setup/LocalSetupCoordinator` | Original company/branch/account and employee workflows, private/locked state, uncertain outcomes, retirement and original-only recovery. Android injects actual factories and retains incomplete acquisitions until confirmed close. |
| `LocalMasterLaunchRecord`, `LocalEmployeeSetupIntents` | Common compatible codecs and recovery decisions. Android adapters alone perform checked private-file read/replace/force. A RUN hint cannot install, repair, acknowledge, promote a writer or sign in. Explicit STOP is durable before retirement. |
| `LocalClientPublication` | Owner-scoped publication, stale-sign-in rejection and secret clearing. Only the actual native owner publishes or withdraws its client; role is presentation, never authority. |
| `security/.../ProtectedLocalPasswordHasher` | One input/binding/work-admission, credential-v1 framing, protected-HMAC, verification/rehash and secret-buffer workflow. Android/JVM supply JCA; iOS supplies CommonCrypto. A hash match still cannot grant a session or bypass master throttling/policy. |
| `recovery-store/.../LocalBackupPolicy`, `LocalBackupSnapshotPolicy`, `LocalBackupFormats`, `LocalBackupPorts` | Common create/verify/retention/restore/replay and metadata rules. JVM retains actual private files, SQLite, AEAD, quarantine, locks and stopped-maintenance custody. Restore does not admit a writer. |
| `LocalMasterLifecycleMode`, original-installation/journal action types | Shared state/contract declarations. Genuine native custody and master checks remain required for every protected transition. |
| `database/.../RuntimeHistoryStartup` | Shared original allocation, pending fencing, complete scan/actual close and hold/ACK/admission decisions. Android retains the unchanged native Room driver/open/cleanup adapter; JVM/Apple still require genuine native custody and driver composition. |
| `LocalMasterOperatingNotice` | Shared English/Arabic/RTL presentation. Android supplies foreground service and screen-on integration, without promising OS survival. |

`AndroidLocalSetupOwner` is now a thin composition wrapper around the shared
coordinator. `AndroidLocalSetupNativeServices`, `AndroidLocalSetupExecution`,
`AndroidSetupIntentStorage` and `AndroidLocalMasterLaunchStorage` implement only
their native seams. The service remains nonexported and holds its original owner
until real close. Leaving the setup screen locks private presentation without
silently shutting down the authoritative master.

**Consolidation remains incremental, not platform parity.** The audit also found
JVM master-lifecycle decisions and policy mixed into larger Android journal/native
owners. Backup orchestration and password workflow are now shared; their native
storage and crypto integrations still require separate qualification. Password
JCA/CommonCrypto calls intentionally remain separate adapters.
Android/JVM credential codecs have different historical formats; do not merge
their bytes or remove compatibility readers without independent fixtures. iOS
still lacks the actual local master/LAN/Keychain composition and device execution.
New admission/RPC work must put portable policy in common code rather than grow
another Android-only workflow.

Android compatibility policy is shared in `AndroidNativeKeyPolicy`; Android-only
helpers acquire actual key evidence, package/storage identity and protected native
file handles. API24 support does not permit guessed hardware evidence, downgrade
of a required policy or replacement of unproved original state. See
[Android compatibility](ANDROID-COMPATIBILITY.md) for capability and verification limits.

The setup cut compiles on Android/Desktop/JVM and has one frozen STOP-policy
slice executed on JVM and Android host. That fake-storage slice is **not** native
storage, restart, fsync, process or platform-integration evidence. See
[the source-bound verification record](../../qa/pos/traceability/shared-setup-kmp-20261028/README.md).

The password cut separately passes the same eight historical cases on JVM and
Android host plus the JVM-only reflection case. These use real crypto and synthetic
pepper keys, not native key-custody qualification; iOS execution is still unverified.
[Password source/ownership/results](../../qa/pos/traceability/shared-password-kmp-20261028/README.md).

The retained native-operation deadline and disposal-phase policy is now shared
in `SelfOwnedNativeOperationBudget`. Android retains native elapsed-time/process
observations, watchdog, atomic holder/gate, invocation resources, exclusion and
actual lease release. A diagnostic phase transition cannot renew the admitted
30,000-ms budget. The shared value is internal timing data, never a native
capability or accepting platform adapter. Desktop/Apple owner composition and
focused native timing qualification remain outstanding. See
[the source-bound regression/compilation limits](../../qa/pos/traceability/shared-native-operation-budget-20261111/README.md).

Windows namespace continuity now retains a native boot observer, samples it
before acquiring namespace handles, and brackets continuity checks with same-boot
and monotonic elapsed observations. The full boot GUID remains DATA; it grants
no original or Master authority. The observer depends on a version-sensitive NT
boot-environment ABI that still needs Windows qualification. Its synchronous native
queries and namespace lock cannot supply independent watchdog supervision.
Windows original-owner/clock supervision and actual runtime composition remain
unfinished. [Source and verification limits](../../qa/pos/traceability/windows-native-boot-continuity-20261111/README.md).

macOS namespace continuity now also retains its real kernel boot UUID and native
continuous-time observer. Observation failure is terminal; acquisition transfers
descriptor/lease custody once before namespace initialization. These checks retain
native file/close guards and do not supply an independent watchdog or original
history owner. macOS provider/runtime composition and native qualification remain
unfinished. [Source and execution limits](../../qa/pos/traceability/macos-native-boot-continuity-20261111/README.md).

### Remaining platform-policy audit queue

These are inspected boundaries, not declarations that every large native file is
misplaced or that a staged extraction already works:

- `JvmLocalBackupStore` now delegates create/verify/retention/restore/replay to shared
  policy. The later `79ae408` increment adds explicit compatible-read profiles,
  per-schema retention and Room 7's exact 29-table/native SQLite inspection.
  Its recovery-store/JVM-host compilation passed; no full backup or restore test
  ran. The earlier 55 core recovery regressions do not verify these adapters.
  Native locks, stopped leases and quarantine custody remain concrete. A complete
  native-history verifier is mandatory but has no accepting implementation;
  Room 7 restore refuses before maintenance. Key/anchor recovery, compatible
  migration and mobile composition remain unfinished. See the
  [source-bound backup record](../../qa/pos/traceability/backup-schema-integration-20261028/README.md).
  The later shared-target increment declares Android and both Apple targets for
  the common policy. Android compiled; Apple compilation and native ports remain
  unverified. It does not move POSIX storage into common code or make restore
  available. [Current source and checks](../../qa/pos/traceability/shared-foundation-20261029/README.md).
- `JvmMasterHost` / `JvmStoppedMaintenance` mix resumability/retirement decisions
  with process and ledger ownership. Share the decisions incrementally, not the JVM
  process assumptions or a permissive mobile host.
  The legacy JVM opener's current-profile guard is intentional: real original
  native context/history/owned-driver acquisition is not yet composed on JVM.
  Replacing its schema constant cannot supply that authority.
  Original startup selection is now shared and separately source-reviewed; JVM
  and Android database compilation executed successfully. The Android native
  opener is unchanged. This removes duplicate decision-workflow needs, not the
  native ownership blocker. No new startup/restart test ran.
  [Exact extraction and limits](../../qa/pos/traceability/shared-history-startup-20261029/README.md).
- Android installer, company and client-journal owners contain native acquisition
  plus compatible historical validation/recovery. Extract proven portable portions
  while preserving their actual source/close/unknown-outcome checks. Android/JVM
  journal formats must not be merged just because their classes have similar names.
- New LAN work must keep peer/session registries, admission matching, bounds,
  fresh-connection and uncertain-close rules common. Android supplies real SDK
  initialization, persistent keys, LAN and lifecycle composition only.
- Successful same-live-connection refresh and protected current-policy adoption
  publish generation-bound adapters. Cached UI/physical adapters cannot inherit
  successor credentials. Shared presentation retires private projections/history
  before adopting the policy; revision-only input and report/cost-only ordinary
  cashier input have bounded continuity. Monotone presentation epochs are cleanup
  hints, not master authority or proof of unobserved policy history. Eight gateway
  slices with real encrypted JVM journals and two runtime/navigation slices passed;
  this is not admitted RPC or complete session acceptance. Native reconnection
  still requires confirmed old close; finer-grained continuity and all-area-loss
  recovery remain unfinished. Forced re-login is not a substitute for normal
  refresh; unknown refresh remains non-reinvokable. See
  [source and verification limits](../../qa/pos/traceability/session-policy-continuity-20261029/README.md).

`core/database` has **test-only** reverse dependencies on business adapters so
integration tests can use real services and SQLite. Its production source must
not depend on those adapters. Platform-specific implementations are not duplicates
merely because Android/JVM/Apple each require their own `actual` declaration.

## Follow one operation

For original-account sign-in/refresh/logout, persistent throttling and bounded
startup evidence, see [RUNTIME-AUTHENTICATION-RECOVERY.md](RUNTIME-AUTHENTICATION-RECOVERY.md).
Actual protected writes and historical verification share session maintenance and
throttle rules; signed facts do not replace original dependency/image joins.
The authentication source now compiles; authentication/native acceptance has not
passed. General pending, business and
changed-identity/policy histories remain held. [The earlier paired-throttle cut](RUNTIME-THROTTLE-RECOVERY.md)
retains its own, nontransferable compilation evidence.

For counted cash disposition, an old session after assignment release, a narrow
review-only supervisor read and original-command result/ACK navigation, see
[CASH-CLOSING.md](CASH-CLOSING.md). The existing cash rules/writes are reused;
ordinary owner-private reads were not broadened to expose another active drawer.
The same document covers separate append-only closed-session explanations. Their
handler's write permissions exclude all cash/session financial aggregates; notes
are not monetary/tender corrections or a substitute for PAY-10.

For food collections/reserved sending/paid pickup/obligations and legal claim
intake, see [FOOD-FINANCIAL.md](FOOD-FINANCIAL.md). These are nonphysical actions;
separate receipt/refund authorization and original retained command profiles remain.


For protected People/Devices management, see
[LOCAL-ADMINISTRATION.md](LOCAL-ADMINISTRATION.md). Its dedicated UI/gateway uses
the actual security store and credential journal, not generic business mutations.
Account/device administration, pairing, provisioning and writer appointment are
different authorities. New cancellation events/client intents have explicit
compatibility profiles; ordinary container/controller names are unversioned.

For original return claims, see [RETURN-CASES.md](RETURN-CASES.md). Legal intake,
receipt evidence, qualification, acceptance, inventory disposition and physical
refunds are deliberately separate; the missing authority/runtime joins stay explicit.

For the connected purchasing document path, see [PURCHASING.md](PURCHASING.md):
read selection/forms, canonical preparation, immutable journal, production
commands, result handling, and the still-required runtime/business boundaries.
The shared `LocalPurchasingPolicyServices` separately retains native-issued policy
envelopes and an explicitly unqualified active pointer. Android obtains trust from
the original company owner before Room/history opening; the same registry installs
the command, protected read and committed-original history producer. This is not
product/document qualification or positive receiving authority. No JVM/Apple
native purchasing-policy adapter or complete release catch-up is supplied.

For quantities, separately authorized cost reads and explicit empty-stock setup,
see [INVENTORY.md](INVENTORY.md). Shared paged reference selection serves retail,
purchasing, receiving allowances, sales routes and legacy warehouse transfers.
Each read uses the actual current session; new-intent preparation rechecks selected
identities/revisions. Page changes never rewrite a staged request or its pinned
catalog revision. The controller uses one query builder for refresh and post-ACK
reads so section selectors cannot be lost. Normalized transfers/cost corrections
remain required, not replaced by the fresh-only initialization path.

1. A screen/controller holds presentation and drafts, never authoritative stock,
   permissions, payment outcomes or a database writer.
2. `adapters/app-gateway` maps an intent to a typed command and preserves the
   original request identity in the client journal for retries/result recovery.
3. The local endpoint checks current device/session/permission admission and
   dispatches the registered production handler.
4. The handler reads an authoritative transaction cut, invokes domain rules and
   proposes effects. The atomic kernel owns persistence, audit/outbox, retained
   result and history/native settlement. An adapter must not independently commit.
5. A response is mapped back to presentation. Lost responses are recovered using
   the same operation identity, not by creating another sale or payment.

Physical payment/refund observations have additional native ownership and
reconciliation rules. Do not replace their lifecycle with an ordinary network
retry. Normalized stock streams and legacy bounded stock records also have
different retained formats; a new request must not reinterpret an old record.

## Naming and compatibility

- Ordinary screens, controllers, helpers, ports and owners use stable names.
- `Contract`, `Codec`, `Wire`, `Schema` and format-bound records describe a
  representation. Their explicit versions may be necessary and are not release
  numbers for the whole application.
- A `Handler` handles a new command. A retained `Producer` verifier proves the
  historical source/effects of an existing record; it is not a second checkout UI.
- A `Read` service supplies an authorized projection. A `Profile` usually fixes
  format identities and finite validation limits, not user-selected permissions.
- An `Owner`, `Loan`, `Lease` or `Frame` expresses a concrete resource lifetime.
  Do not merge them merely because they all contain a database reference: caller,
  service, transaction and native-close ownership are not interchangeable.
- Keep persisted command keys, discriminator strings, table names, signatures,
  key aliases, migrations and old operation readers stable. Use Git—not another
  `V2` implementation—for routine changes. See [CLEANUP.md](CLEANUP.md) for the
  reviewed rename/removal record.

## What belongs where

- **Current code:** normal module source sets; archived proposals are not runtime inputs.
- **Requirements:** `docs/pos/original-handoff` and `docs/pos/plan-review` at the
  repository root. Original files are immutable authority, not editable status.
- **Independent cases:** `qa/pos`. Read its access instructions before assigning
  a designer/executor; source-aware implementers cannot design their own oracles.
- **Current verification:** ordinary POS Gradle tasks and meaningful independent
  acceptance harnesses; see [BUILDING.md](BUILDING.md). Historical mixed/backend tools are
  held and are not a shortcut for local verification.
- **Historical records:** dated decisions, execution packets, frozen cases and
  source manifests. Earlier passes never apply automatically to changed source.
- **Saved unfinished source:** `docs/pos/paused-work/2026-10-28` contains inert
  source patches, dependency hashes and blockers for the owner-requested pause.
  These are outside application source sets, not an alternate runtime; do not
  apply them as a batch or count them as completed features.
- **Central phase:** `backend`, `tools/backend` and associated archived inputs
  remain held until the owner approves after local hands-on testing.

## Windows/macOS Master and Client hosting

Every device installs the same POS application; setup selects Master or Client.
Owner decisions of 12 November 2026 keep this Master/Client model unchanged.

- **Master desktop:** the original requirements require an OS-supervised branch
  process separate from the cashier window ([technology guide](original-handoff/POS_TECHNOLOGY_AND_LIBRARY_GUIDE.md),
  Windows/macOS rows; [production proposal](original-handoff/POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md)
  desktop hosting). It is a background mode of the same application: on Windows,
  a packaged LocalService service in the same MSIX (`UkkeraPOS.exe --service`),
  started only when the device is configured as Master. Master keys, storage and
  database belong to that service identity. Closing the window does not stop it.
  The window on the Master device reaches it through authenticated OS-local IPC,
  not loopback P2pKit RPC.
- **Client desktop:** no background service. Client device keys stay inside the
  main application under OS-protected secure storage (owner decision).
- **Windows installer:** one MSIX for Master and Client, signed (test builds use a
  locally trusted self-signed certificate); installation requires Administrator
  rights on every Windows device because the package declares the Master service.
- **macOS:** requires an Apple-issued Developer ID signature with Team ID (frozen
  MAC-TRUST); unsigned builds cannot open protected storage.
- Android (in-app runtime with foreground service) and iOS (active-app hosting)
  are unchanged; all clients reach the Master over authenticated LAN RPC.

## Android original setup path

The default Android setup entry and employee entry remain distinct. Follow
`MainActivity` → `AndroidSetupConnection` → the nonexported
`AndroidLocalBranchService` → `AndroidLocalSetupOwner.controller` → common
`LocalSetupCoordinator`. The setup controller
connects an explicit first-account definition/review and password submission to
`AndroidLocalBranchRuntime.openOriginalMaster`, which registers the actual commerce
services through `AndroidSelfOwnedMasterService.fromOriginalContext`. The service uses the
actual original native custody, `RoomSelfOwnedInstallationVerifier` and
`RoomSelfOwnedBranchInitializer`; it is not an in-memory account seed.

The first account starts with no selected grants. Its complete policy and device
admission are reviewed before separate head/employee secrets are submitted. The
retained service keeps its real Room/native owner. Only a separate explicit original
client action acquires both protected journals and publishes the application branch
gateway. Successful installation is deliberately **not** a sign-in or
an operational-master readiness signal. A verified installation remains readable
when its desired master role is STOP.

If the same owner actually completed the original SQL/native settlement but its
company acknowledgement did not return, the explicit acknowledgement continuation
uses the native owner's retained completion capability. On a later owner, only a
complete original C1 scan and actual read close can rebind an already native-settled
completion. The distinct pending-original coordinator first durably fences the
attempt and requires complete positive original-C1 evidence; its result retains
STOP, never RUN. ACK remains explicit; Room admission requires another full scan
after ACK. Missing/empty/ambiguous/later histories are not safe absence. No SQL,
password, approval or original request is replayed. Explicit UI rescan waits for
actual old-owner close; unknown close blocks replacement ownership.
UI locking does not close this owner; service retirement denies work, waits for
admitted jobs/reads and closes the actual child owner—not the inert pre-transfer
context. Read-only receipt clients cannot independently close their host.

The original cohost now has distinct first-journal enrollment/existing-only reopening
and actual default branch gateway publication. [Android local client](ANDROID-LOCAL-CLIENT.md)
explains fixed receipt-derived identities, native file heads, foreground notice,
actual close, the completed-first/untouched-second enrollment continuation and
native-fenced exact prepared-write recovery (including a proved temporary prefix
or interrupted archive link). Missing write-ahead/successor, inconsistent/legacy
cuts and partial initial enrollment remain held. Room mode changes are atomically
visible to the cohost, not a plain cross-thread field. The same native root now supplies exact catalog-publication trust, and the
master-owned gate connects original payment-only recording; see
[NATIVE-LOCAL-CHECKOUT.md](NATIVE-LOCAL-CHECKOUT.md). Additional employee/device
journals, broader physical authorities, transport and general interrupted/restart
recovery remain required. Do not substitute demo data or
permissive fallbacks. See the handoff for source-bound build/regression evidence;
no native/device acceptance is claimed.

## Official library dependency policy

P2pKit library fixes are owned upstream, not maintained as POS patches. Use an
official version that satisfies employee-bound F13 disclosure and compatibility
requirements, then verify real admission/RPC/persistence integration. The
historical scoped fork and source-copy/receipt machinery are retired. This does
not remove F13 or Master-authoritative authorization requirements. Ordinary
compilation/rebuilds use standard Git and Gradle; historical evidence is retained
without imposing artificial single-use build restrictions.
