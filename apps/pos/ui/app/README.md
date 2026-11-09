# Native application shell — bounded foundation package

**LOCAL ONLY. Not a live POS, fiscal implementation, or completed T10/T17/T28.**
This package uses the original TECH §§4–6/10, POL §§7–8, QA §§11–12 and
`../../../ukkera-pos-plan-review/FINAL-PLAN.md` §§3/12. Shared root logs contain
actual combined verification; source presence below is not a green build claim.

## Platform composition root / public integration

```kotlin
val runtime = UkkeraRuntime(
    AppDependencies(
        platform = PlatformCapabilities(PlatformFamily.DESKTOP),
        branchGateway = reviewedBranchGateway, // optional; default always rejects
        centralGateway = reviewedCentralGateway, // separate human trust
    )
)
// Retain runtime once per process/scene owner; do not recreate inside recomposition.
UkkeraApp(runtime, onExitRequest = platformExit)
// Explicit shutdown only:
runtime.close()
```

`BranchUiGateway` and `CentralUiGateway` return different non-serializable
capability session interfaces after actual authentication. `SessionDisplayContext`
contains bounded IDs/display names/allowed area projection, **not an authority
principal or credential**. Adapters implement immediate local `close()`
invalidation, resource reauthorization for `mayRestore`, cancellation-safe
capability handoff and independent authoritative policy enforcement. The UI
clears supplied credential arrays; it never persists passwords or routes/tokens.
Entry-launched sign-in also wipes the buffer if disposal cancels it before its
coroutine body starts; this is logical cleanup, not a claim to erase immutable
platform text-input strings from managed memory.

Adapters must call `runtime.signOut()` immediately on active local revocation or
context loss. Connection cleanup may fail: the private UI still disappears and
a sanitized attention notice is shown. Stale **first** sign-in completions and
retained/retry completions cannot adopt a session after its captured generation,
entry or root changes; any unclaimed capability is closed. This presentation
fence does **not** repair retained/in-flight/queued RPC confidentiality, authorize
responses, or make P2pKit suitable for sensitive data. No P2pKit is bound here.

No production catalog/quote/payment/stock/cash mutation port is exposed yet.
Next integration must use the domain owners' reviewed services and durable
gateway result, then route outcome recovery by original operation identity.
An irreversible command never belongs to an entry VM; logout must not erase or
relabel `PendingOperationOwner` or the protected journal. No UI callback returns
an invented successful charge, receipt, stock reservation or go-live.

## Navigation / ownership

- One event-loop-confined `AppNavigator` owns all typed `AppRoute : NavKey`
  history. Bootstrap, branch sign-in/workspace and company sign-in/workspace
  are separate security roots. The generic Nav3 entry envelope adds only a
  stable local entry identity; it is not a second controller.
- Explicit exhaustive route registry, independent area stacks, single-top
  duplicates, 12-entry stack limits, pop-to-root reselection, bounded direct
  dispatch and identity-captured callbacks. Guard/draft policy changes advance
  the same callback revision. Old animated entries retain their
  **own** ID, not the incoming entry's callback authority.
- `BackCoordinator` policy is shared by toolbar, public Nav3 system/predictive
  and iOS Back, desktop Escape, forward/tab navigation and window close.
  Guarded scenes expose an empty public `Scene.previousEntries`; resetting the
  display on eligibility/security changes removes previous visual state.
  Library-owned gestures are not replaced with private native workarounds.
- The explicit optional checkpoint contains only route history and bounded
  scope IDs. Platform launchers currently cold-start at bootstrap rather than
  persisting checkpoints. Restoration is quarantined, structurally bounded and
  fully reauthorized before installing any history. Different actor/company,
  deleted/denied entity, errors, cancellation and delayed validation fail closed.
  Existing or newly created guards/drafts also reject route installation; an
  entry-local discard never acknowledges a retained workspace basket.
  The authenticated root's `LaunchedEffect` owns suspended restore reads, rather
  than the sign-in entry disposed on success. Root removal cancels that effect;
  session identity, policy revision and coroutine cancellation fence installation.
  Quotes/setup edits are not restored without their application state.
- Process-local **isolated** Koin application (no global singleton); a new DSL
  module graph per runtime avoids cross-container scoped-instance collisions.
  Each root has a Koin scope and explicit ViewModelStore. Nav3's pinned saveable
  and ViewModel entry decorators retain tabs and release popped entries.
  `koinViewModel` resolves each entry through its owner and a child Koin scope;
  `onCleared` clears private state/closes that scope. Root reset clears all stores
  before disposing session state. No branch service is created or stopped here.
- Koin compiler-plugin compatibility/graph verification remains a root
  foundation gate. Runtime lifetime tests do not claim compiler checks passed.

## Training / localization / accessibility

Explicit training uses a bounded **in-memory** dataset with no network, storage,
payment, printing or outbox dependency. It is neither an authenticated production
session nor a live fixture import. Search (Arabic/English/leading-zero barcode),
quantity controls, cart, exact EGP arithmetic preview, sample order states,
practice drawer counting, stock, report descriptions, eight-step setup rehearsal
and native company administration areas are present. Products cover grocery,
bakery, café/restaurant and fixed clothing variants, not complete activity flows.
The zero-tax illustration is **not** `OperationalPricingEngine` or an
authoritative `PricingSnapshot`. Live domain/gateway integration is still needed.
Training private state clears on exit; it cannot synchronize or produce receipts.
Closed training objects cannot alter a replacement workspace's draft protection.

Static copy uses explicitly paired Arabic/English **Compose Resources**, with
app-local language selection (no global OS locale changes/internal resource API).
Arabic integer cardinal selection is explicit and tested so an English OS does
not choose the wrong Arabic plural. Amounts avoid floating point, use bidi
isolation, and parser/search accept Arabic/Persian digits without losing leading
zeros. Language changes preserve the current root, basket and stable entry.
Stock notices, unavailable features and example states use text, not color alone.
No raster images are needed; the small vector icon set is code-native. Coil cache
qualification is pending rather than claimed from an unused default image loader.

Common action minima are 48dp. Layout breakpoints account for font scale, lists
are bounded/lazy, totals have an always reachable dedicated phone route, and
search has Ctrl/Cmd+K plus a repeated-Enter latch. English/Arabic semantics,
hardware scanners, focus retention, 200% text rendering, contrast, TalkBack,
VoiceOver/Narrator, actual iOS gestures and measured scrolling still require
integration/hardware evidence. Pure policy or formatting tests are not AT tests.

## Verification entry points

- `:ui:app:jvmTest`: navigation/ownership/restore/stale first-response/session
  teardown, isolated Koin contexts, training arithmetic/search, RTL/digits and
  text-size breakpoint tests. JVM-only Compose JUnit regressions also exercise
  the real cached Nav3 entry's Add-to-Review callback and rapid sign-in followed
  by root-owned deferred restoration. On this Linux worker the lead runs this
  suite with `xvfb-run -a`; these checks are not native gesture or AT qualification.
- `python3 ui/app/tools/check-resource-parity.py`: bilingual names/placeholders
  and generated binding parity, plus Android/iOS XML syntax checks.
- `:apps:desktop:run --args=--smoke-exit`: native local-window launch only.
- `:apps:android:assembleDebug`: dedicated Android application compilation.
- Apple/Windows/macOS host instructions and honest missing gates live under
  `apps/{ios,desktop,android}/README.md`.

The lead batches Gradle; do not launch competing builds. Independent review and
actual combined results are required before marking this package accepted.
