# T08 — local authentication implementation

Status: production-source implementation in progress; **not independently accepted and not an owner-ready POS release**. LOCAL ONLY. Central/backend work remains on hold pending owner testing and explicit approval.

Implementer: `/root/independent_review` (the name is historical; this agent is **not** the independent reviewer of this implementation). Lead owns the grouped runtime endpoint, shared database opener/schema registration and combined build/evidence. Independent designers/executor own acceptance cases and results. This implementer has not authored, edited or executed the new acceptance cases, or inspected independent QA drafts.

Requirements: `/root/projects/ukkera/ukkera-pos-agent-handoff-2026-10-21/POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md` §§5–8 and the original T08 package; `/root/projects/ukkera/ukkera-pos-plan-review/FINAL-PLAN.md`. Those paths are workspace references, not shipped application assets.

## Trusted composition, not automatic setup

`LocalMasterSecurityDependencies` requires explicit protected-storage and password/pepper providers, cryptographic entropy, a qualified current service/monotonic environment, authenticated physical-peer evidence, and bounded work capacity. `LocalMasterSecurity` is constructed by the trusted opener over **one exact** `MasterRoomDatabase`/`RoomLocalSecurityStore`/`RoomCurrentLocalAuthorizationSource` pair:

- `principalFactory` verifies existing interactive access credentials.
- `authentication` supplies login, refresh, current-session projection and logout.
- `credentials` supplies controlled provisioning and credential/session administration.
- `authorization` must be passed to the command/read kernel over that same database. A second source or pool is not an equivalent composition.
- `transactionApprovals` binds protected single-use action approvals to that same kernel writer; no separately opened approval database/source is permitted.

The low-level services retain F35's unborrowed, non-inherited, non-reentered Room-entry precondition. Changing a dispatcher or `Job` alone does not remove an inherited Room connection. The lead-owned typed `LocalMasterEndpoint` supplies fresh service request lineage; it is not an arbitrary caller callback/DAO ingress.

No default administrator, hardcoded password, generated substitute storage key, permissive peer source or accepting provisioning authority is installed. `UnavailableLocalProvisioningAuthority` denies provisioning. A synthetic authority or key provider may support an explicitly isolated qualification/training environment but cannot establish company approval or production OS-key qualification.

`LocalAuthenticatedPeer` is adapter-supplied evidence of an authenticated **physical** lifetime, not a request-body device UUID. When an admission requires both key identifier and fingerprint, both must agree. Device capabilities do not confer employee permissions. Connection identifiers originate in the transport/protected same-device adapter. User, device, central administrator and master-service identities remain distinct.

## Account provisioning and activation

`LocalAccountProvisioning` contains the stable branch-local actor ID, exact local login identifier, initial activated policy, explicit token durations and the approved admitted-device set. Scope and actor must agree, policy revision starts at zero, and uniqueness is enforced by protected account/configuration identities and the unique branch/login index.

Login identifiers are case-sensitive, with no trimming or Unicode normalization: 1–128 UTF-16 code units, at most 256 strict UTF-8 bytes, no control characters or leading/trailing whitespace. `localLoginKey` is a domain-separated index hash, **not** a secrecy guarantee for guessable identifiers.

`provisionAccount(requestId, definition, password)` asks the installed `LocalProvisioningAuthority` to approve a canonical digest of the complete definition and whether a credential is supplied. Its current ceremony must remain valid through the protected writer cut. A non-null credential uses a branch/account/revision-bound verifier. Null deliberately creates an account with **no usable credential**; a qualified local administrator may later establish it with `resetCredential`.

Account, activated policy, login/token configuration, account/device throttle buckets and immutable provisioning event commit together. No usable account is synthesized from directory presence. A token policy whose work deadline cannot be represented from the current monotonic moment is refused, not stored as an immediately unusable configuration.

The initial `admitDevice` ceremony binds the complete exact public descriptors, capabilities and revision, not merely a logical device ID. It creates the revision-zero admission and each existing account's new-device throttle bucket atomically. Explicit same-ID **re-admission** requires a retained revoked admission and exact next revision; an already-admitted identity cannot be overwritten. Re-admission authenticates/preserves every existing account/device throttle bucket, refuses missing buckets, and revokes the old device's sessions atomically. Provisioning a new account initializes buckets for all retained device identities, including revoked ones. Device enrollment still needs real pairing/new-key proof and retirement of replaced physical peer lifetimes by the trusted adapter; the DB revision alone does not terminate a transport.

`activatePolicy` requires the full digest-bound trusted ceremony and exact previous revision plus one. It neither merges grants nor supplies default permissions. Lowering the session cap below the current active count revokes the active set atomically. `reactivateAccount` is a separate explicit ceremony binding expected account, credential, policy and configuration revisions; only a disabled nonterminal account can be reactivated. Credentials, grants, throttle and history remain, account revision/generation advance, and old sessions are not resurrected.

### Unknown provisioning outcomes

The client retains the **original** request ID, original definition and ceremony data, never a password. `lookupAccountProvisioning(..., credentialWasProvided)`, `lookupDeviceAdmission(...)`, `lookupPolicyActivation(...)` and `lookupAccountReactivation(...)` reauthorize that same digest-bound request and read its immutable protected event.

- `Applied` means the matching durable event was observed through a normally returned writer loan.
- `NotRecorded` means authenticated absence at that writer cut; it does **not** prove an earlier still-running request cannot commit.
- `Denied`, `Unavailable`, `StorageUncertain` and `Busy` are not success.

Do not mint a new request ID or infer safe retry from an unknown result. UI adapters must not expose arbitrary editable lookup IDs. Authority lookup is read-only: durable consumption occurs in the event transaction, not by consuming approval before a potentially failing SQL operation.

## Password login and persistent throttling

`signIn(connectionId, login, password, explicitDeviceMove)` snapshots caller-owned input only after bounded admission. Supported password input is 4–1024 UTF-16 code units with well-formed surrogate sequences. Passwords are never logged, synchronized or stored in auth audit. Owned copies are wiped on exit; complete runtime/provider memory erasure is not promised.

The production sequence is:

1. Capture the authenticated peer, resolve the protected login and capture account/credential/policy/admission/configuration dependencies.
2. Commit a paired **account plus account/device** throttle reservation.
3. Perform expensive password verification outside the Room writer.
4. Revalidate the captured dependencies and permit, then persist MATCH completion, session transition, immutable access/refresh issuance and auth audit in the same transaction.
5. Recheck current peer/time before leaving the outer writer. Publish owned secrets only after its COMMIT and outer cleanup return normally.

A mismatch completes both throttle buckets durably. Hasher/provider unavailability is not counted as a wrong password; uncertain storage never becomes an empty bucket or successful login. Missing buckets are not repaired at login. In-progress attempts, restart/monotonic discontinuity and timeout follow the persisted throttle policy rather than wall-clock reset.

JVM/Android adapters use JCA PBKDF2-HMAC-SHA256 plus a separately protected HMAC-SHA256 pepper; the Apple adapter uses public CommonCrypto PBKDF2 with the same UTF-8 and post-HMAC binding. The floor is 600,000 iterations, with a bounded supported work range and two derivations per hasher instance. Per-credential salts are at least 128 random bits. The floor is not evidence of device calibration or OS-keystore qualification. Android provider availability at the selected OS minima and actual Apple compilation/linking/runtime remain separate gates.

Service saturation is `SERVICE_BUSY` / typed `Busy`, not wrong credentials or proof of damaged storage. Capacities bound admitted work, not a hung provider's duration. Runtime deadlines and OS process supervision remain necessary.

### Availability is not storage poison

Auth/current/logout/administration/status distinguish ordinary `Unavailable` from `StorageUncertain`. Peer disconnect, expiry or a missing password/key provider do not by themselves retire a healthy Room master. No bearer/receipt is published when final peer/time checks fail. Unknown issuance/revocation outcomes still require the original recovery workflow; absence of storage poison is not an applied-result claim.

Expected in-transaction refusals/provider errors use explicit Room `rollback(result)` and cross the outer cleanup as data. Only after normally returned rollback/loan cleanup can they become benign unavailability. Actual SQL, COMMIT, rollback or outer loan failures remain sanitized storage uncertainty. A throttle-adapter latch preserves this distinction even though the historical pure throttle helper catches ordinary exceptions. No raw exception/cause, SQL, credential or protected row enters a public error. Cancellation/deadline/poisoned-close handling remains the supervised runtime's responsibility.

## Access, refresh and local expiry

D055's interactive access profile is preserved exactly:

| Value | Encoding | Stored secret material |
| --- | --- | --- |
| Access | ASCII `ULA1`, 16 UUID bytes in network order, 32 random secret bytes: **52 bytes total** | Existing domain-separated SHA-256 hash |
| Refresh | ASCII `ULR1`, 16 UUID bytes in network order, 32 independently generated secret bytes: **52 bytes total** | `ukkera.local-refresh-token/sha256/v1` domain-separated SHA-256 hash |

Neither accepts the other profile. There is no JSON/text bearer fallback and no actor/grant claim trusted from the token. `LocalSessionTokens` owns redacted `OpaqueCredential` instances; the client must protect or destroy those secrets. `toString` is not a transport serializer.

`LocalTokenPolicy` supplies positive explicit access/work durations, with work at least as long as access. There is no built-in cloud lease or cloud-dependent renewal. Access is capped by the remaining work deadline. A refresh atomically revokes its old session and appends a new session/access/refresh pair while retaining the **original** work origin and deadline. Concurrent/stale refresh cannot create a second session from the same old credential after the first rotation commits. Clients serialize refresh. If its reply is lost irrecoverably, use explicit **local** password login/device move, not replay as a new login or cloud authentication.

Issuances bind branch, actor, device, session/generation, credential/admission revisions, boot and service incarnation. Refresh additionally binds configuration revision and original work-session identity. Current policy is always loaded at the master; the returned `LocalSessionView` is only a display/capability hint. Commands and protected reads reauthorize their own current policy and resource/field visibility.

`current` requires a live access credential and returns the stable actor, branch/device/session/generation, login, policy and monotonic expiry moments. `logout` accepts the original access **or** refresh secret, even when expired, **only to revoke that original identity**; it still requires a currently admitted physical peer. It cannot refresh, issue authority or act on another session. Revoked archived identities remain resolvable for logout. Client lock is synchronous presentation teardown and must not be presented as successful master revocation.

After a boot/service change, local password re-login is required; no cloud connection is involved. The shared current-environment source permanently fails closed on lost continuity, actual incarnation/boot change or elapsed regression. Currentness checks are observations, not simultaneous database/network disclosure fences at delivery.

## Sessions, local administration and history

Default policy is one interactive session per branch account, with explicit device move; activated policy can allow up to 16. Device move revokes the prior active set atomically and preserves historical identity. Access expiry alone does not retire a still-renewable work session; fresh password login may retire sessions already unusable from work expiry, service/boot change or credential/admission revocation.

`resetCredential`, `revokeSession` and `disableAccount` first authenticate the operator, then authorize current `LOCAL_ACCOUNT_ADMINISTRATION/ADMINISTER` against the target/revision in the actual writer. Credential reset advances its own credential revision and revokes all sessions atomically. Self-reset/disable/revoke is an intentional authorized effect, not grounds to resurrect the operator afterward. Missing/stale authority denies. Disable has no implicit re-enable inverse.

The account aggregate retains at most 256 session records. Before a new session would overflow it, exact revoked records are inserted into the immutable session archive and removed from the aggregate in that same transaction. Live sessions, original identifiers, credentials, issuance and history are not discarded or rewritten. Reusing an archived session ID is refused. Counter exhaustion cannot recycle a generation or resurrect authority.

Provisioning currently has technical bounds of 4,096 retained accounts and 256 **total retained** device admissions, including revoked devices. These are explicit implementation limits, not a reduction of the original business requirements. Unsupported state refuses rather than silently truncating rows/history. Archive/audit growth needs operational storage sizing; no retention deletion is implemented here.

An absent numeric permission ceiling remains fail-closed. Explicit `NoMonetaryCeiling(currency)` is separately represented and currency-bound; it cannot coexist with a finite ceiling. Percentage and delegation ceilings remain independently required/enforced. `Long.MAX_VALUE` is not an infinity sentinel.

`LocalEffectivePolicyCodec` leaves canonical v1 policy bytes and meaning unchanged for unextended policies. Its explicit v2 envelope contains a canonical v1 base plus ordered grant-index-bound no-ceiling extensions and optional `LocalApprovalPolicy`. Strict canonical re-encoding detects missing/reordered/duplicate/unsupported fields. Missing approval policy enables nothing; lifetime is explicit (1–600,000 milliseconds) and named self-approval exceptions bind scope, actor and action through activated policy. The historical v1 encoder refuses an extended object rather than silently dropping authority fields.

## Additive schemas v5 and v6

Historical schemas 1–4, protected codecs and guard bodies remain unchanged. Four protected tables are added:

- `protected_local_auth_configuration`: branch/actor identity and unique branch/login index.
- `protected_local_refresh_issuance`: immutable revision-zero refresh issuance.
- `protected_local_auth_event`: immutable revision-zero local lifecycle/provisioning audit.
- `protected_local_session_archive`: immutable revision-zero revoked history.

New envelopes use the existing qualified AES-256-GCM protected-storage port and table/key/revision binding. Plain secrets do not enter the rows. Immutable tables reject update/delete, alternate replacement and hidden-rowid collisions. Configuration preserves identity/login index and requires exact revision advancement; there is no generic public upsert.

Direct migration callbacks cover 1→5, 2→5, 3→5 and 4→5 without assuming intermediate Room version restamping. They refuse all newly reserved schema names before inherited DDL; 4→5 checks the persistent TRUNCATE/FULL profile, exact v4 identity and every historical guard before creating v5. Open callbacks verify guard definitions, not just their names. No destructive fallback or live migration has been executed by this implementer.

Exported schema: `core/database/schemas/com.ukkera.pos.database.MasterRoomDatabase/5.json`, 16 tables; Room identity `917ea675dbaed518193bc43cfcd19ffc`; JSON SHA-256 `5c7beab64256609b3597d3abcd5da258c43cfaca5755814fef483352521df4ed`.

The approved additive v6 source adds one `protected_local_action_approval` table and direct 1→6 through 5→6 migration routes; it does not rewrite historical migration bodies. The new row permits only initial revision zero and exact once-only 0→1 consumption; replacement, hidden-rowid identity collision and deletion are guarded. V6's genuine generated Room identity is pending the lead-coordinated KSP compile and must not be guessed. No live migration was executed.

## Exact-action manager approval — source in progress

`LocalActionApprovalService` defines typed preview, issue and original-ID/digest status lookup. `LocalApprovalCommandPreparation` is the trusted kernel seam: normalization, semantic digest, operation binding and current business/recovery/writer checks come from the real typed handler, not a caller-supplied authorization requirement. At this documentation cut, the service implementation/composition remains **in progress**, not runnable acceptance evidence.

The protected codec/store and `TransactionApprovalSource` consumption path are authored. A protected approval binds the exact action/verb, branch, document/revision, semantic digest, operation, reason, money/base, owner constraint, requester/approver policy revisions, original requester session/device/credential/admission, approver credential/admission, service incarnation, monotonic issue/expiry and original preview digest. The trusted policy must explicitly permit any self-approval. An approval DTO is not an RPC authority.

The kernel binding reauthorizes current requester REQUEST, checks the approver's current dependencies and derives only the exact PERFORM capability for that live view. It adds the approver authorization resource to recovery admission. Consumption CAS and protected audit belong in the **same successful original command effects/result transaction**, after business/resource/CAS/future-ID validation. Reject, defer and replay never consume; any later failure rolls consumption back. Final requester/approver dependencies are reread and expiry uses the kernel's shared final monotonic observation. This is not proof that all approval workflows or all of T08 have passed independent execution.

## Staged recovery quarantine and F36

`quarantineStagedLocalSecurity` is a JVM **child-helper** implementation for an exact existing private staged v6 file. It is currently explicitly **unavailable before opening any file** pending a genuine generated source-controlled v6 identity pin. It never validates v5 and then implicitly migrates through the v6 builder. V5 archive validation/originals are preserved separately, but v5 restore remains unavailable pending a separately qualified staged compatibility path; do not silently upgrade it. The helper does not establish the stopped/exclusive lease, recovery delegation, target-key access or absence of another physical writer. Never map it directly to a parent-process recovery port or invoke it on a live database.

Before changes it verifies source scope/authority and performs a bounded metadata-first authentication census of **all ten protected tables**, including inactive/history and action-approval rows. It revokes active sessions, disables admitted devices, and adds exactly one protected event bound to backup, incident, source authority, target and approval ID. Accounts, credentials, policies, throttle/configuration and immutable issuance/archive/approval bytes remain; approvals are neither consumed nor rewritten by quarantine. Their requester/session/admission/incarnation bindings make pre-quarantine approval authority unusable. It authenticates a second census and the no-active/no-admitted state before commit. There is no `branch.writable` change, install, activation, new device key or rekey. Recovery additionally requires independently trusted nonempty canonical DDL pins; the Room identity alone does not pin trigger bodies.

A receipt is created internally but emitted only after COMMIT, outer cleanup and actual synchronous `Room.close()` return. A poisoned outer recycle can make close spin before cancellation: `withTimeout` is **not** evidence that close succeeded. On failure/cancellation, core requests external retirement and does not close a possibly poisoned pool in `finally`. The parent supervisor must bound, terminate if necessary and **reap** the child on every path before treating staged custody as released. The retirement callback is not reap proof.

Recovery preserves staged files on any quarantine exception/cancellation; no unconditional deletion follows an unconfirmed helper death. Shared maintenance custody and private-stage cleanup remain supervised adapter responsibilities. A successful quarantine does not prove target activation, writer fencing, historical completeness or receipt/pending-operation reconciliation.

## Evidence and remaining gates

The lead executed compile-only `:core:security:compileKotlinJvm :core:database:compileKotlinJvm`, exit 0 / `BUILD SUCCESSFUL`, 13 tasks. Log: `.workspace/evidence/local-delivery-20261024/compile/01-auth-database.log`. That cut predates later busy-status, lookup, staged-quarantine, Unicode and native-provider changes. It is not verification of those edits or acceptance evidence.

The lead also reported the combined auth/database/UI JVM compile succeeded, 21 tasks (15 executed, 6 up-to-date); log `.workspace/evidence/local-delivery-20261024/compile/02-auth-ui.log`. That cut predates re-admission, policy v2/activation/reactivation, v6 approvals and the availability split. These newest changes remain uncompiled at this documentation cut.

The next coordinated compile is being recorded by the lead; no new compilation or runtime result is claimed here until its exact source cut is reported. No auth acceptance execution is claimed. Required outstanding evidence includes real grouped runtime/service/persistence and local-communication workflows, independent failure-boundary execution, native builds and physical platforms, protected-key/pepper/peer/clock/supervisor qualification, paired re-enrollment/recovery, current policy/approval workflows, and UI user-switch/unknown-outcome handling. Hardware/provider gates stay **UNVERIFIED**. Full local scope, owner hands-on testing and later central approval remain separate milestones.
