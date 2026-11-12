# Unresolved source review — HOLD

Manual source review only. Findings were not reproduced by execution and no
compile/test/independent acceptance result exists. The owner requested pause
before these findings could be repaired. Do not apply or qualify this packet.

## Required cleanup/concurrency reconciliation

1. `LocalSdkLanOwner.closeActual` removes a normally completed cleanup entry before
   completing its deferred result. The bounded last-original registry remembers
   the SDK object, not its normal close result. Concurrent/late direct cleanup of
   the same actual session can therefore create another original close call.
   Exceptional native cleanup is retained, but a single original normal result
   across every cleanup observer is not established by this source.
2. `LocalSdkScopedPeer.close`, `LocalSdkScopedHost.seal` and
   `LocalSdkScopedClient.seal` do not yet retain one original FULL cleanup result.
   Repeated sealing/protocol cleanup can obscure an earlier error. Nested
   `finally` cleanup can replace the primary error instead of preserving both.
   Any repair must retain exact originals without introducing collector self-join
   or cross-collector waits; prelude loss collectors need a non-awaiting loser
   path when another cleanup already owns completion.
3. Scoped host/client `isCurrent()` currently relies on SDK/cut flags rather than
   additionally checking the local ACTIVE/SEALED StateFlow, despite changing local
   state before suspendable SDK sealing. Immediate denial and member publication
   races require review. Source/native/SDK fences are still independently required.
4. The owner awaits its incoming and registry-state watchers, but scoped watchers
   are also children of its scope and are not explicitly in that await census.
   Late cleanup additions/reobservations must be reconciled with full native
   close and original result retention before sole-owner release.
5. `LocalSdkLanOpening.actualKit` is a MutableStateFlow of a raw SDK kit. Although
   its CAS currently expects null, StateFlow equality must not be allowed to
   delegate any original SDK owner/session comparison to SDK equals/hashCode.
   Review/use a reference-identity wrapper rather than asserting full compliance.

## Thin adapter / error-custody review

6. Android physical acquisition obtains an actual native peer and then calls
   `actualCommonPeer()`. If currentness is lost between those steps, its returned
   peer should be explicitly retired before rethrowing. The SDK registry/watchers
   still own it, but the common driver's original cleanup record is not yet formed.
7. Preparation exchange cleanup in `finally` can replace a primary failure or
   CancellationException. Retain/aggregate the actual primary and cleanup results
   while preserving unknown durable intent and original-close denial. Review
   similar secret-copy cleanup `finally` paths for guaranteed destruction.
8. Public checked common/native capability accessors are currently needed across
   security/gateway modules. Reconcile explicit Kotlin API/visibility and prove
   every native authority boundary still validates the same final original
   identity/peer, not a caller tuple or arbitrary wrapper.
9. Reconcile nullable smart casts, Coroutine/StateFlow/SDK DSL signatures,
   serializer/Q digest format and all dependency API visibility by actual compile
   once authorized. Source inspection is not a compilation result. The full
   native target source implementation itself has unresolved registry/key APIs.

## Deliberately missing delivery

10. Original scoped source/host dispatch, per-target serving owner, Android original
    serving entry, original admission/master delivery and target exact challenge
    readback route remain NOT_IMPLEMENTED. Do not replace them with a generic
    callback, public protocol registration, guessed native cut or fake SDK.
11. No first-message HELLO custody seam exists in the consumed SDK. The explicit
    per-target intermediate mode must be wired before attachment, with exact
    source-backed authority and target-specific withdrawal/normal cleanup. No
    automatic demux/recovery or SDK production change was implemented.
12. UI attachment late-publication/retirement and cohost refresh behavior received
    manual staging changes before this continuation. Further concurrent review,
    frozen independent case execution and genuine Android service/LAN/native
    integration remain required. No acceptance or platform qualification follows.

No source was changed to repair these findings during the owner's pause wrap-up.
They are source blockers, not failed executed cases and not permission to resume.
