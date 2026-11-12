# Android local testing

**6 November 2026: refreshed app/test APKs are packaged and signature-verified
with production source `28dee76e` and the reviewed COS-001 original-cost test binding.
Installed behavior remains unverified; this is not yet an
owner-ready or accepted prototype.**

[Tracker #2](https://github.com/ukkera/kmp-apps/issues/2) ·
[Delivery #43](https://github.com/ukkera/kmp-apps/issues/43) ·
[Device campaign #63](https://github.com/ukkera/kmp-apps/issues/63) ·
[Owner acceptance #61](https://github.com/ukkera/kmp-apps/issues/61).
Central implementation and verification remain held.

## Candidate and verification

The current app/test pair includes the shared cold-history, supplier-original and
permission-denial corrections through
[production `28dee76e`](https://github.com/ukkera/kmp-apps/commit/28dee76ec7f3ef30bfa6d43e550157809c2780b2),
plus the reviewed original-cost assertions in the Android COS-001 fixture.
Fatal lint passed; both APKs passed v2 signature and 16-KiB ZIP-alignment checks
with the same retained signer. Both manifests declare minSdk24/targetSdk37.
The app is nondebuggable; the separate instrumentation APK is debuggable and
targets the app's isolated package. [Artifact identifiers and exact results](../../qa/pos/traceability/cold-history-20261106/retail-native-original.md#original-cost-binding-and-refreshed-android-pair--6-november).

Earlier DEX/lint-preparation failures remain in the historical evidence; no
security or lint check was disabled to get this result. Packaging does not
establish startup, secure enrollment, a business transaction or restart recovery.
The [previous `75308e99` pair](../../qa/pos/traceability/finite-purchase-history-20261105/README.md#refreshed-android-packaging)
and its source-specific results remain preserved. The refreshed packaging does
not rerun or broaden earlier unit-test results.

Run ordinary Gradle from `apps/pos`, with JDK 21 and the configured Android SDK.
Supply the retained private PKCS12 key through `UKKERA_POS_TEST_KEYSTORE` and
`UKKERA_POS_TEST_KEYSTORE_PASSWORD`; do not commit either value. Do not replace
an existing signing key to repair an installation error.

```sh
# These standard tasks can run separately and reuse Gradle's valid outputs.
./gradlew :apps:android:lintVitalLocalTesting --console=plain
./gradlew :apps:android:packageLocalTesting --console=plain
./gradlew :apps:android-runtime-tests:packageLocalTesting --console=plain
```

Standard outputs, relative to `apps/pos`:

- App: `apps/android/build/outputs/apk/localTesting/android-localTesting.apk`.
- Tests: `apps/android-runtime-tests/build/outputs/apk/localTesting/android-runtime-tests-localTesting.apk`.

The app is the isolated, **nondebuggable** `com.ukkera.pos.local.testing`; tests
use `com.ukkera.pos.local.testing.test`. Both must have the same private signer.
Use Android build-tools `apksigner verify --print-certs` and the generated
`output-metadata.json` files to confirm the actual artifacts. Native checks are
not disabled for testing. Signing inputs and binaries stay out of the repository.
See [BUILDING.md](BUILDING.md) for ordinary build and signing details.

## Install and inspect on a disposable target

Use Android **API24 / Android 7.0 or newer**, only synthetic data, and an
explicitly selected device. Some preserved fixtures have a narrower API31+
precondition; do not reinterpret their results as API24 coverage. Test API24 and
at least one API31+ target for the compatibility campaign.

```sh
adb devices -l
# Set TEST_DEVICE to the selected disposable target's listed serial.
adb -s "$TEST_DEVICE" install -r \
  apps/android/build/outputs/apk/localTesting/android-localTesting.apk
adb -s "$TEST_DEVICE" install -r \
  apps/android-runtime-tests/build/outputs/apk/localTesting/android-runtime-tests-localTesting.apk
adb -s "$TEST_DEVICE" shell am start -W -n \
  com.ukkera.pos.local.testing/com.ukkera.pos.android.MainActivity
```

An update must preserve the expected signer and original installation. Do not
clear app data, uninstall, generate another identity or reset unresolved work to
manufacture a passing result. Use a separate disposable installation for fixtures
that require fresh setup. Never use real business data or an operational branch.

There are **no built-in test credentials or prequalified demonstration database**.
For interactive testing, the real setup UI separates new company creation,
original branch installation, first employee definition, explicit completion ACK
and employee sign-in. Choose synthetic credentials privately. Resume/inspect an
existing original rather than choosing a new company after an uncertain result.
These are implemented entry points, not proof that their full sequence has passed.

Select the key policy explicitly: `OS_ISOLATED` is a supported policy, never an
automatic fallback from `SECURE_HARDWARE` or `STRONGBOX`. Hardware policies require
actual qualifying capabilities. Follow [ANDROID-COMPATIBILITY.md](ANDROID-COMPATIBILITY.md);
do not inject accepting authority, fake enrollment or seeded protected rows.

## Run one selected native fixture

Do not run fixtures concurrently with the Activity/branch service or reuse
uncertain original fixture state. Each linked record defines its exact class,
opt-in argument, synthetic setup and expected outcomes. The existing runner is:

```sh
adb -s "$TEST_DEVICE" shell am instrument -w -r \
  -e class com.ukkera.pos.android.independent.retailhandoffhistoryv1.NativeRetailBaselineTest \
  -e syntheticRetail true -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner
```

| Fixture | Scope / remaining limits |
|---|---|
| [API24 native compatibility](../../qa/pos/traceability/android-api24-compatibility-20261031/README.md) | Native descriptors, directory enumeration and nonexportable key observations. Historical bounded passes are not full setup/business acceptance. |
| [Retail baseline](../../qa/pos/traceability/native-retail-baseline-host-20261030/README.md) | Original setup, authentication and stock/cash/order preparation; whole workflow and LAN are not established. |
| [Cash original COS-001](../../qa/pos/traceability/cold-history-20261106/retail-native-original.md#original-cost-binding-and-refreshed-android-pair--6-november) | Actual setup, capture, separate Apply/Finalize, protected original cost and two owner-reopen bindings are compiled. UNRUN; payment receipt, presentation and broader variants remain incomplete. |
| [Original supplier workflow](../../qa/pos/traceability/supplier-original-lookup-20261105/README.md) | Supplier create/read/update, original lookup and native close/reopen bindings; whole-case limitations remain explicit. |
| [Invoice capture SIDC-02(b)](../../qa/pos/traceability/supplier-invoice-capture-rpc-20261105/README.md#native-sidc-02b-fixture--source-reviewed-not-run) | Genuine native baseline, shared capture and original recovery; not installed UI, LAN RPC or a complete SIDC/ICR pass. |
| [Historical recipient](../../qa/pos/traceability/native-historical-recipient-prefix-20261030/README.md) | Preserved API31+ complete-registry-history bindings; missing whole-case observations remain open. |
| [Opening refusal](../../qa/pos/traceability/native-opening-supplier-refusal-20261030/README.md) / [positive posting](../../qa/pos/traceability/native-opening-supplier-positive-20261030/README.md) | Original result and supplier obligations have distinct frozen fixtures; no new execution is claimed. |

Fixture credentials are ephemeral and destroyed; they do not become reusable
owner accounts. Record actual revision, OS, key policy, case, result and relevant
failure evidence in #63. Unexecuted tests remain pending, not passed.

## Known blockers and historical evidence

- No current complete setup → login → protected business operation → original
  recovery → restart result is established. Required genuine persistence/current
  authority/RPC integration remains #59; missing native implementation remains #55.
- The recorded API24 run reached founding readback but later terminated during
  original-allocation installer authentication. Its cause and a current-source
  runtime repair remain unproved. [Actual recorded result](../../qa/pos/traceability/android-founding-stages-20261102/README.md#corrected-source-api24-execution--2-november-2026).
- Earlier API31 software runtime attempts became unstable before installation;
  the later attempt completed permission initialization but suffered watchdog and
  network-stack failures. [Preserved diagnostics and bounded results](../../qa/pos/traceability/android-api24-compatibility-20261031/README.md).
  No connected target was available for this checkpoint; no emulator was rebuilt.
- Official P2pKit **0.8.0-rc11** is consumed. #531/#540 are no longer the active
  publication prerequisites. Application acceptance before transport Receipt is
  tracked in [upstream #543](https://github.com/p2pKit/P2pKit/issues/543); hold only
  affected behavior, without a POS fork or security bypass.

All four activities and five native platforms remain in scope. The
[previous guide](https://github.com/ukkera/kmp-apps/blob/6ae307265c2af9ead780e5b9f44cb9bf40a82161/docs/pos/ANDROID-TESTING.md)
and linked records preserve earlier packaging, runtime attempts and failures.
They do not verify a newer artifact. Packaging and supporting tests are not
owner acceptance or production readiness.
