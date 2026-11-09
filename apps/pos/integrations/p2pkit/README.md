# P2pKit integration

P2pKit is the upstream Kotlin Multiplatform local-network/RPC library:
https://github.com/p2pKit/P2pKit. It is not an Apple or Android platform SDK.

## Current policy

- Consume an appropriate **official upstream version** with normal Gradle/Maven
  dependency management. Do not maintain library fixes or a fork in this POS.
- Report library RPC/security defects upstream; POS owns its application logic,
  session/permission enforcement, configuration and native adapters.
- F13 requires current employee/session authorization for protected result, error,
  duplicate and status delivery. Device identity alone is insufficient. Do not
  bypass this gate while waiting for a suitable official release.
- Build and rebuild with normal tools. No fresh-source donor, custom copy pipeline,
  operation ID, source roster, observer or receipt is required. Preserve historical
  failures and protected copies without treating them as active prerequisites.

## Current dependency — 3 November 2026

POS selects matching official **0.8.0-rc11** Core/LAN/RPC artifacts. The release
includes immutable factory-owned `RpcHost.localIdentity` / `RpcClient.localIdentity`
and exact explicit `deviceName` support for closed upstream
[#540](https://github.com/p2pKit/P2pKit/issues/540).
[Current implementation and verification](../../../../qa/pos/traceability/rpc-rc11-identity-20261103/README.md)
separates shared DATA/native comparison and compatibility checks from unfinished
production host/client composition. Publication is no longer a blocker.

Actual factory composition still needs explicit approved LAN configuration,
integrity-protected durable local trust, retained native ownership, exact original
identity/name checks and current Master authorization. No default subnet,
accepting trust store or unconfirmed-close shortcut is substituted.

Original requirements selected `io.github.apdelrahman1911:p2p-core`,
`p2p-transport-lan` and `p2p-rpc` at `0.8.0-rc6`. Version suitability requires
source/compatibility review and relevant real integration tests, not just artifact
availability. The shared `adapters/local-rpc` module now declares matching official
`0.8.0-rc11` core/LAN/RPC dependencies; application entry points do not yet compose
this module into a qualified host/client runtime.
See [F13](../../../../docs/pos/plan-review/FINAL-PLAN.md#23-employee-bound-rpc-disclosure--f13)
and [upstream issue #528](https://github.com/p2pKit/P2pKit/issues/528)
and [Issue #48](https://github.com/ukkera/kmp-apps/issues/48).

## Earlier rc10 assessment — 2 November 2026

Official **0.8.0-rc10** supplies both immutable application sessions and the
exact callback-owned physical-session API requested by closed upstream
[#531](https://github.com/p2pKit/P2pKit/issues/531). Matching Maven artifacts and
JVM/Android/iOS target metadata are published. The shared binding now accepts
`RpcAuthenticationContext`, retains the exact SDK capability and delegates
protected POS admission to mandatory `LocalRpcPhysicalPeerProvenance`.
Retirement revokes its original application session and awaits exact physical
closure. See [the physical-session contract](../../../../qa/pos/contracts/LOCAL-RPC-PHYSICAL-SESSION.md)
and [source-specific verification and limits](../../../../qa/pos/traceability/rpc-physical-session-20261102/README.md).

Publication and the supported API resolve a dependency gap, not whole F13 or
POS acceptance. Genuine native admission, protected host/client registration,
current Master authorization and persistence/disclosure ordering remain required.
Common metadata compilation is not an Apple binary/framework/runtime result.

<details>
<summary>Earlier rc8 assessment — 30 October 2026</summary>

Official **0.8.0-rc8** was the earlier integration candidate. Its
[tagged source](https://github.com/p2pKit/P2pKit/tree/6c85fdc2977386e572830c243f9aa8bb17d84ec3)
and [supported application-session contract](https://github.com/p2pKit/P2pKit/blob/6c85fdc2977386e572830c243f9aa8bb17d84ec3/docs/rpc/application-session-authorization.md)
resolve the upstream immutable-scope mechanism/publication gap reported in
[#528](https://github.com/p2pKit/P2pKit/issues/528), now closed. Matching official
core/LAN/RPC root and JVM/Android/iOS module metadata are available. Publication
and source review are not POS dependency, native or RPC qualification.

`registerScoped` and `tryNotifyScoped` retain the original application session
and resource binding for authorization and queued/retained output. Fresh policy
checks and session revocation order SDK send-ticket start; already-committed output
may finish afterward. Legacy device-only registration remains insufficient.
Awaited revocation does not close the shared physical connection or establish
remote publication. Transport request retention is not durable business idempotency.

POS still needs genuine native peer/admission/key/history custody, immutable
original credential/session binding, exact current Master resource authorization,
SDK revocation/commit ordering and actual persistence/admitted-LAN tests. The
[identity boundary contract](../../../../qa/pos/contracts/LOCAL-RPC-IDENTITY-v1.md)
does not authorize sensitive serving by itself. The selected physical-context,
clock/generation and invoke-only/no-downgrade requirements remain unqualified;
rc8 preserves the existing wire and does not automatically prove those guarantees.
No POS-maintained SDK fork or automatic archived-patch integration is permitted.

</details>

## Shared adapter boundary

`LocalRpcIdentityBinding` retains one verified interactive login, a private
credential copy and the exact original SDK application session. It rechecks the
original principal and captured physical lifetime before returning identity or
scope metadata. Caller-owned credentials are not destroyed; explicit retirement
destroys the owned copy, revokes the original SDK application session and, for
a physical binding, awaits exact SDK physical closure. Source cancellation
propagates rather than becoming an ordinary refusal.

`LocalRpcPhysicalPeerProvenance` deliberately has no production default. Native
composition must join the exact SDK callback capability to protected POS device,
scope and key admission; a newest-connection lookup or SDK identity alone is
insufficient. Authentication, handler and outbound authorization preserve that
same original physical lifetime, refusing an equal-key/generation successor.
Descriptor-only `LocalRpcPeerProvenance` remains a legacy identity boundary and
cannot adopt a physical binding.

Failed/incomplete SDK closure is observable and cannot restore the login.
Retirement does not cancel host-owned business work, undo committed effects or
prove nondelivery. The SDK finite lifetime bounds scope activity; it neither
automatically erases the retained bearer nor qualifies native expiry. Composition
must retire the exact owner on logout, replacement and physical retirement;
revoking only its SDK application session does not release that bearer.

This adapter is not a permission engine or an installed RPC service. Current
Master resource checks, business commit/disclosure ordering, actual admission and
physical custody remain required. Controlled identity tests cannot qualify those
paths. See [current delivery status](../../../../docs/pos/CURRENT-STATUS.md).

The earlier rc6/rc7 source concern is historical: those inspected versions lack
the new official application-session mechanism. Its prior evidence does not
qualify rc10 or prove exploitation. Track current integration in #48/#49/#50 and
continue independent local development; central remains held.

## Historical work — not an active fork

The unchanged `patches/` files preserve prior research and legitimate proposed
fixes/tests. They are **retired historical references**, not installation recipes
and must not be automatically applied to POS dependencies. No new local library
patches will be maintained here. Their original Git history and existing
`qa/pos/traceability/sdk-*` records retain successes, failures and limits unchanged.
Generated compile recipes, bindings and full source rosters are removed from the
active tree; historical bytes remain retained outside the active workflow and in
Git history. No previous result qualifies the current official library or POS LAN.
