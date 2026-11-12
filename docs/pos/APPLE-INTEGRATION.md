# Apple platform integration

Current checkpoint, **9 November 2026**: official P2pKit **0.8.0-rc12** is now
consumed. Real Swift/Xcode simulator linking, **464 native simulator methods**,
iPhone/iPad exact-PID startup checks and unsigned Release/Debug device archives
have passed at their recorded revisions. See [current results and limits](../../qa/pos/traceability/epic3-native-qualification-20261109/README.md)
and [installation instructions](INSTALL-AND-TEST.md). The approved additive P1
identity path remains separate from historical pending factories. Full Apple
custody/history/setup/Master/Client composition is still incomplete; the older
rc11 and unexecuted-tooling statements below describe the prior checkpoint.


**Integration is incomplete, not an owner-ready build.** Track iPhone/iPad in
[#57](https://github.com/ukkera/kmp-apps/issues/57), macOS in
[#56](https://github.com/ukkera/kmp-apps/issues/56), and the local prototype in
[#43](https://github.com/ukkera/kmp-apps/issues/43). Central services remain held.

## Start here

1. **Establish one iPhone/iPad composition framework.** The Xcode project currently
   builds the framework from `:ui:app` and Swift constructs the unconfigured
   `IosAppHost()`. The real gateway module depends on `:ui:app`; adding the reverse
   dependency would create a cycle. Use a thin application composition target
   depending on both modules, rather than copying gateways into UI or linking two
   independently owned copies of the Kotlin runtime/model graph. Coordinate the
   framework/build-phase change before replacing the existing entry point. Update
   the Gradle build task, both Xcode framework search paths and the Swift-visible
   facade/exports together; update imports/linker names if the framework basename
   changes. macOS currently uses Compose Desktop/JVM, not this native framework.
2. **Connect native custody and persistence.** Supply actual protected keys,
   pepper, private credential/intent journals and the native database owner. Room
   and its processor already declare both Apple targets; this is not evidence of
   an operational Apple database or writer. Do not expose internal master
   construction or install an accepting history/provider verifier to unblock UI.
   The current original target/namespace/boot contracts also retain Android and
   POSIX-specific facts. Implement their compatible platform identity prerequisite
   in #56/#57 before native composition; do not fabricate Android signer/ID/UID
   facts or compress a boot-session UUID into an Android boot counter.
3. **Compose a real local client.** Inject `AppDependencies` into `IosAppHost` or
   `runUkkeraDesktop`; retain the client/native owner outside presentation state.
   Enrollment, authentication and each protected operation must use the actual
   master. Defaults deliberately do not authenticate.
4. **Add native lifecycle and admitted communication.** Test local-network
   permission denial/retry, peer loss and confirmed shutdown. Set Bonjour/service
   declarations only from the selected real transport. On iOS, backgrounding or
   termination cannot promise continuous master hosting. UI logout must not
   silently stop a separately running master.

The Windows portion of #56 still requires Windows-specific verification; a macOS
or JVM result does not establish Windows behavior.

## Shared implementation and integration boundaries

All paths below are relative to `apps/pos`.

| Boundary | Reuse | Still required |
|---|---|---|
| Setup | `adapters/app-gateway/.../setup/LocalSetupCoordinator`, `LocalSetupNativeServices`, `LocalSetupMaster`, `LocalSetupClient`, `LocalSetupExecution` and common launch/intention codecs | Actual platform acquisition, private storage and confirmed release; a RUN hint is not writer admission. |
| Original-history startup | `core/database/commonMain/.../RuntimeHistoryStartup` shares allocation, complete scan/close and hold/ACK/admission decisions. `RuntimeHistoryRoomStartup.openOwnedRuntimeHistoryRoom` also shares lazy owned Room open, durability/schema validation, native-finish ordering, original admission and owner registration/failure cleanup; Android delegates to it. | Actual native custody/history controllers and bound Room driver remain platform prerequisites. The shared sequence cannot create authority or replace a native adapter. Earlier scan extraction [scope/results](../../qa/pos/traceability/shared-history-startup-20261029/README.md) remain revision-specific. Native startup and Apple execution are not established. |
| Employee authentication | `core/security` protected password/session rules and `core/database` local authentication services | Native protected custody, installed original authority, actual master composition and runtime verification. |
| Apple crypto | `core/security/src/iosMain` has password PBKDF2, SHA-256, entropy and constant-time access-digest comparison | Company-publication Ed25519 verification and offline-installation verification/challenge/hasher composition currently return false/null. Those implementations, Keychain/secure ownership and actual Apple execution remain required. Password checks do not qualify these paths. |
| Client presentation | `BranchUiSession`, `LocalSessionAttachmentKey`, `LocalSessionCheckResult`, `UkkeraRuntime`, shared no-operational-access status | An existing valid attachment may have zero operational areas. Use the shared session-only shell, not invented logout or fallback grants; restore routes only against one current attachment. Native/master/RPC acceptance remains required. |
| Application gateway | `LocalEndpointBranchGateway`, workflow/admin/catalog gateways and shared setup | Genuine connection, original identity and durable journals. Native connection replacement requires confirmed old close, not just a new UI generation. |
| Official RPC identity | `adapters/local-rpc/commonMain/.../LocalRpcIdentityBinding` consumes official **0.8.0-rc11**, with callback-bound physical provenance, confirmed-close APIs and immutable factory `localIdentity`/exact `deviceName`. Shared native transport-fact comparison is available in `LocalDevicePairingSdkIdentityV2`. [Current source/review/results](../../qa/pos/traceability/rpc-rc11-identity-20261103/README.md). | #531/#540 source and publication prerequisites are resolved, not full POS qualification. Compose approved OrganizationLan, genuine protected durable RpcTrustStore and retained platform RPC owner scope; compare actual factory readback with the original native association and separately retained name before networking. Current Master resource/admission, exact physical lifetime and private-disclosure enforcement remain required. |
| Master/client port | `core/database/.../LocalMasterClient` and `adapters/app-gateway/.../LocalMasterClientConnection` | Genuine enrolled peer/device keys and admitted same-device/LAN transport. Health flags and supplied IDs are not authority. |
| Fulfillment handover | Existing common owner-food `MyChecks`/`Check`/`Collection`, explicit Serve/PaidPickup and durable command journal; [integrated source/review](../../qa/pos/traceability/fulfillment-handover-20261029/README.md) | Reuse this shared implementation, not native handover rules. Successful gateway eligibility, actual native Master/Room/admitted-RPC and restart workflows remain unverified. Generic delivery/COD/remittance/incidents remain incomplete. |
| Business workflows | Shared catalog, stock, sales, payments, food, purchasing, reports and recovery modules | Per-feature native history/authority joins and automated production integration; none is accepted solely because its code exists. |
| Backup | Portable `LocalBackupPolicy`, formats and `LocalBackupNativePort` in `adapters/recovery-store`; Android and both Apple targets are now declared | Android shared-policy compilation passed; Apple compilation is unverified. Supply real native ports; full history/key/anchor recovery and current-schema restore remain required (#54). The JVM host is not an iOS adapter. |

The shared owned-open helper must run inside the same retained
`SelfOwnedNativeHistoryCustody.runRoomWork` that acquired the native driver.
Platform code supplies the lazy Room builder and actual native open-completion
operation for that exact driver. Keep builder creation inside the shared failure
boundary; do not move acquisition outside the retained invocation, add an
accepting finish callback, bypass schema guards or repeat a registered Room close.
Android remains the only implemented native consumer. Desktop/Apple acquisition
and genuine current-schema integration remain #56/#57/#59.
Source comparison found no blocking difference in the extracted admission,
lazy-open, cancellation or single-close sequence. Affected database
`compileKotlinJvm` and `compileAndroidMain` passed on 4 November 2026 in isolated
runs; the earlier interrupted combined run is not a pass. No new native startup,
Apple compilation or behavioral acceptance is established by this extraction.

Public constructors and interfaces are composition boundaries, not permission to
manufacture trust. Every protected operation still uses current master-side
employee, device, session and policy state. Preserve original pending IDs and
unknown money/stock outcomes across refresh, logout and restart.


The latest shared session/status increment passed 71 gateway/runtime/rendered/UI
methods and Android/Desktop compilation; three separate authentication-profile
methods also passed. [Exact source and verification boundaries](../../qa/pos/traceability/session-no-access-20261029/README.md).
These are reusable shared inputs, not Apple execution or a completed local client.
The later recovery/profile checkpoint also passed the same 29 protected-storage
regressions on JVM and Android host. It exposes shared backup target declarations,
not Apple storage or restore execution. [Exact scope](../../qa/pos/traceability/shared-foundation-20261029/README.md).

## Dependency gates

- **#48 → #49:** POS consumes official `0.8.0-rc11`. Closed upstream #531/#540
  API and publication prerequisites are resolved. The release carries immutable
  factory-owned `RpcHost.localIdentity` / `RpcClient.localIdentity`, exact
  `deviceName` and confirmed physical-close APIs. [Current verification and
  limits](../../qa/pos/traceability/rpc-rc11-identity-20261103/README.md).
  Actual POS factory configuration, durable protected trust, retained ownership,
  admitted scoped serving and lifecycle/RPC qualification remain unfinished.
  The earlier 3 November 07:47 UTC rc11 HTTP404 observation is historical;
  publication alone does not establish POS authority or qualification.
- **#50:** same-connection refresh and native connection replacement are distinct.
  Full policy-change continuity and frozen session/RPC acceptance remain required.
- **#51 → #59:** complete native history/restart joins and genuine production test
  fixtures remain unfinished.
- **#56/#57 native identity:** `SelfOwnedTargetDescriptorV1` carries Android app
  identity; native namespace/database identity carries POSIX facts and original
  installation attempts retain an OS boot count. Truthful platform variants must
  reach original preparation, custody, history and SDK association, not just UI
  labels. Preserve Android V1 bytes and history formats 2–6. Requested hardware
  policies cannot downgrade; permitted OS-isolated software custody must still
  use genuine protected native facilities, not adjacent file keys.
- **Upstream P2pKit #543:** official rc11 transport Receipt precedes application
  durable acceptance. [The upstream issue](https://github.com/p2pKit/P2pKit/issues/543)
  remains open as observed on 5 November 2026. Hold affected qualification, not
  independent same-device or native work. Do not maintain a POS library fork or
  claim the order is fixed by source compilation.
- **#54:** current-schema restoration is not yet available as a verified workflow.
- **#58:** the protected-profile expectation mismatch has a reviewed,
  requirements-backed passing correction. The separate raw-throttle/installed-
  verifier fixture remains incompatible; do not restore unsafe APIs.
- **JVM original setup:** the legacy opener deliberately rejects current owned
  Room history. Current JVM native original-context/history/Room-driver acquisition
  and composition are missing, not fixed by changing a schema constant. Preserve
  that guard while implementing genuine ownership (#56/#59). The common startup
  selection can now be reused; it does not supply those native capabilities.

Avoid incompatible changes to `commonMain`, RPC types, credential/journal formats,
Room schema 7 or dependency pins while native composition is being connected.
Keep native additions in their platform source sets; reusable decisions stay in
shared code. Preserve all frozen requirements, tests and protected checkpoints.

## Focused checks available on macOS

Use the repository's pinned Gradle/dependency versions and JDK 21. These are
**suggested checks, not claimed execution results**:

```sh
cd apps/pos

# Shared business regressions before native integration.
./gradlew :core:types:jvmTest :core:recovery:jvmTest :domain:pricing:jvmTest

# Apple compilation, including the actual gateway and Room-generated code.
./gradlew :core:database:compileKotlinIosSimulatorArm64 \
  :adapters:app-gateway:compileKotlinIosSimulatorArm64 \
  :adapters:recovery-store:compileKotlinIosSimulatorArm64 \
  :adapters:local-rpc:compileKotlinIosSimulatorArm64

# Independently record simulator results for the selected shared modules.
./gradlew :core:types:iosSimulatorArm64Test :core:security:iosSimulatorArm64Test \
  :domain:pricing:iosSimulatorArm64Test

# macOS source checks; launcher defaults remain unconfigured.
./gradlew :apps:desktop:compileKotlin \
  :adapters:app-gateway:compileKotlinJvm :adapters:local-master-host:compileKotlin

# Android source integration when the Android SDK is configured.
./gradlew :apps:android:compileLocalTestingKotlin
```

Framework linking and the Swift application build are separate from klib
compilation. Current framework tasks are `:ui:app:linkDebugFrameworkIosSimulatorArm64`
and `:ui:app:linkDebugFrameworkIosArm64`; update them when the composition module
changes. Use Xcode's existing shared `UkkeraPos` scheme for the application build.
The project records Xcode creation metadata of 26.4, not a qualified toolchain pin.
iOS 15 remains a candidate minimum, not a qualified support claim. Device signing
requires actual signing credentials, never committed to the repository. Simulator
and physical-device results are different evidence. Desktop packaging
(`:apps:desktop:packageDmg`) likewise needs a separate macOS check after composition.

Run independent frozen cases against real services and persistence as the native
bindings become available. UI or controlled-client unit slices do not pass whole
RPC/business scenarios. Record the source revision, selected tests, result and
limits once; do not reuse a result across incompatible source/dependency changes.

## Completion and owner testing

The quickest useful completion path is native composition → setup/login → real
catalog/inventory read → durable business commands → restart/recovery. Include
sales/drawers, returns, purchasing and restaurant/café workflows as their genuine
dependencies become available; a checkout-only application is not the full scope.

Development closure requires integrated code, review and meaningful passing unit
and production integration checks. Real-world device/peripheral/owner execution
stays in [#61](https://github.com/ukkera/kmp-apps/issues/61) and the
[manual catalog](manual-testing/README.md). Missing automated integration cannot
be moved into that manual lane.
