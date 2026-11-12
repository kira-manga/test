# Building and testing the local POS

Run from `apps/pos`, using its own Gradle wrapper, JDK 21 and a configured Android
SDK (`ANDROID_HOME` or `ANDROID_SDK_ROOT`). The separate Queue build is not POS.

```sh
./gradlew :core:security:jvmTest --console=plain
./gradlew :adapters:local-rpc:jvmTest :adapters:local-rpc:compileAndroidMain --console=plain
./gradlew :ui:app:jvmTest --console=plain
./gradlew :apps:android:assembleLocalTesting --console=plain
./gradlew :apps:desktop:compileKotlin --console=plain
```

Choose relevant tasks, not a full build after every change. Add `--offline` only
when dependencies are cached. If a task fails, inspect the real error, fix it,
clean disposable output when necessary, and rerun. Reuse normal Gradle caches.
Do not clean historical evidence, private signing keys or protected sources.

Record the Git revision (and uncommitted changes), command/configuration, exit
result and relevant test reports. Gradle's normal reports live in the module's
`build/test-results` and `build/reports`. Preserve unique failure diagnostics;
no source-copy/hash/seal/receipt framework is required. Up-to-date or cached
results are reusable only for compatible inputs, not new code they did not test.

## Android packaging

`python3 tools/package_local_android.py` runs ordinary Gradle assembly and signs
the isolated, non-debuggable testing package with retained private testing keys.
First use requires explicit `--initialize-signing-key`; never replace an existing
or partial signing directory. Output is reusable normal build output:
`apps/android/build/outputs/local-testing-signed/ukkera-pos-testing.apk`. Signature
and package checks remain mandatory. Packaging is not device, business or owner
acceptance. See [ANDROID-TESTING.md](ANDROID-TESTING.md) for actual limitations.

### Native instrumentation baseline

The standard `com.android.test` module, `:apps:android-runtime-tests`, uses
`AndroidJUnitRunner` and the unchanged retail-handoff v1 baseline against actual native setup, authentication and stock/cash/order services.
It is not an owner demo, complete handoff suite or separate-device RPC test.
The test APK is debuggable to package the complete official desugaring runtime;
the target application remains nondebuggable. Native test sources and frozen
assets are reused in place without changing their assertions.

For a signed application/test pair, supply `UKKERA_POS_TEST_KEYSTORE` and
`UKKERA_POS_TEST_KEYSTORE_PASSWORD` through your local environment, using the
retained private PKCS12 testing key. Do not commit these values. Without these
optional variables, ordinary compilation and the existing packager are unchanged.

```sh
./gradlew :apps:android:assembleLocalTesting :apps:android-runtime-tests:assembleLocalTesting --console=plain
```

Check both `output-metadata.json` files and verify the APK certificates match with
Android build-tools `apksigner verify --print-certs`. Use a fresh disposable
`com.ukkera.pos.local.testing` installation on an eligible API24+ target. Do not
launch the Activity/branch service concurrently or touch real business data.

```sh
adb -s "$TEST_DEVICE" install apps/android/build/outputs/apk/localTesting/android-localTesting.apk
adb -s "$TEST_DEVICE" install apps/android-runtime-tests/build/outputs/apk/localTesting/android-runtime-tests-localTesting.apk
adb -s "$TEST_DEVICE" shell am instrument -w -r \
  -e class com.ukkera.pos.android.independent.retailhandoffhistoryv1.NativeRetailBaselineTest \
  -e syntheticRetail true -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner
```

`OS_ISOLATED` is an explicitly selected supported lane, not fallback from failed
hardware protection; `SECURE_HARDWARE`/`STRONGBOX` need actual native capabilities.
Exact StrongBox evidence still requires API31+; see [Android compatibility](ANDROID-COMPATIBILITY.md).
All native security checks remain enabled. A retained test intent prevents another
invocation from replacing uncertain setup/requests; inspect it before an explicit
synthetic-data reset. It stores identity/status only, not replay-ready payloads or
a production recovery journal. Ephemeral test credentials are destroyed.

[Source, frozen bindings, results and remaining gaps](../../qa/pos/traceability/native-retail-baseline-host-20261030/README.md)
distinguish compilation/signing from actual native execution. All 15 whole history
cases require their separate original assertions and failure/platform variants.

### Native floor/table directory supplement

The same app/test build pair contains the separately frozen
[`native-food-floor-directory-v1`](../../qa/pos/freezes/native-food-floor-directory-v1/FREEZE.md)
supplement. Select **one** case per fresh disposable installation; each invocation
performs genuine original setup and all 28 BASE configuration commands. Do not
reuse an earlier branch, clear uncertain work automatically or run the Activity/
branch service concurrently. An existing retained fixture intent refuses replacement.

```sh
adb -s "$TEST_DEVICE" shell am instrument -w -r \
  -e class com.ukkera.pos.android.independent.nativefoodfloordirectoryv1.NativeFoodFloorDirectoryTest#nativeFrozenDirectoryAssertions \
  -e syntheticNativeFoodFloorDirectory true -e nativeFoodFloorCase NFFD-003 \
  -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner
```

NFFD-001 covers original setup/BASE; NFFD-002 adds 11 protected reads; NFFD-003
adds distinct-input CAS/stale refusals; NFFD-004 adds fixed-parent refusal;
NFFD-005 adds original A lookup/retry after confirmed close/reopen and fresh login.
These are named supporting assertions, not complete whole-case acceptance:
raw history/audit/outbox, business sentinels, admitted RPC, faults/races/restore,
installed UI and other-platform obligations remain separate. All runtime checks
remain NOT_RUN until actually executed on an eligible native target. The separate
historical-recipient host retains its original API31 qualification precondition;
do not reinterpret that frozen host as API24 evidence.

## Native desktop and iPhone/iPad packaging handoff

The prepared native packaging path is [NATIVE-PACKAGING-HANDOFF.md](NATIVE-PACKAGING-HANDOFF.md).
It contains the exact Gradle/Xcode task paths, manual auxiliary-repository
workflows, artifact SHA256 manifests and the install/test instructions. The
unsigned iOS device archive is not installable; only a separately signed build
can be used on physical hardware. Simulator launch and desktop `--smoke-exit`
are bounded packaging checks, not operational Master or owner acceptance.

## Acceptance and dependencies

Frozen independent expectations remain authoritative. Test executors may wire
production services and isolate synthetic data, but must not weaken assertions.
For the existing frozen partial host suites, use
`python3 tools/execute_independent_auth_v1.py --compile` / `--execute`, or
`python3 tools/execute_independent_commerce_v1.py compile` /
`execute --binding-version v1` (or `v2`). They prepare ordinary reusable classpaths
and isolate synthetic execution; they do not represent complete original cases
or real LAN qualification. Preserve meaningful failure diagnostics before
reusing disposable fixtures.

Those two execution helpers require a Linux host with network-namespace and
process-group support; the commerce helper also uses Linux `fcntl`/resource APIs.
This restriction belongs to those isolated test runners, not to the POS platforms
or the ordinary Gradle commands above.

Actual persistence/RPC behavior is required for development closure; device/owner
checks are separate in #61. Apple linking/native execution requires appropriate
platform tools and remains unverified until executed.

[P2pKit](../../apps/pos/integrations/p2pkit/README.md) must be a suitable official
release. F13 is an upstream dependency concern, not permission to bypass security.
Historical SDK tooling/fork patches are not current build instructions. Central
implementation **and verification remain held** pending separate authorization.

## Cleanup verification — 30 October 2026

- Plain `:core:security:jvmTest --offline`: 142 tests, zero failures/errors/skips.
- `python3 apps/pos/tools/tests/test_android_packaging.py` from repository root:
  14 methods, 45 parameter rows passed; external commands are mocked. Original
  C05/C07 failures were fixed in implementation without changing expectations.
- Auth/commerce CLI plans and ordinary Gradle preparation dry-run succeeded; no
  JUnit task executes as a preparation dependency. This is graph evidence only.
- Real Android assembly/signing/installation, auth/commerce acceptance, P2pKit
  admitted RPC and Apple/device checks were not executed by this cleanup.

These results cover the cleanup's scope, not full POS development completion.
