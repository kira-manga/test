# iPhone/iPad native host and packaging handoff

**Status: source/package preparation only.** The shared `:ui:app` module declares
`iosArm64` and `iosSimulatorArm64`, producing static framework `UkkeraPos`. The
SwiftUI app retains one `IosAppHost` per scene and embeds
`ComposeUIViewController`; it adds no UIKit or SwiftUI navigation history. The
Swift launcher module is `UkkeraPosLauncher`, distinct from its imported Kotlin
`UkkeraPos` framework. This native host is not a complete iOS Master or Client.

The Xcode project has a **shared** `UkkeraPos` scheme so `xcodebuild` works on a
fresh checkout without opening Xcode first. The framework build phase invokes
`xcode-build-framework.sh`, which resolves JDK 21 (Xcode does not inherit a login
shell), keeps the POS Gradle wrapper, and applies the shared resource limits. The
phase task is exactly:

```text
:ui:app:embedAndSignAppleFrameworkForXcode
```

The underlying direct framework tasks are
`:ui:app:linkDebugFrameworkIosSimulatorArm64` and
`:ui:app:linkReleaseFrameworkIosArm64`. `adapters:local-master-host` is JVM-only
and has no iOS compilation task; do not add it to an Apple target list.

## Toolchain: validate what is installed

Do not assume Xcode **26.4** exists. Use the actual selected developer directory
and record it. The manual CI workflow uses GitHub's `macos-26` arm64 runner and
prints `xcode-select -p`, `xcodebuild -version`, the installed SDKs and the
simulator runtime. It accepts an optional `xcode_developer_dir` input and fails
below Xcode 16. `macos-26` also needs the arm64 runner because the POS build does
not declare legacy `iosX64`.

Local prerequisites:

- macOS on Apple Silicon for the declared simulator architecture.
- Xcode 16 or newer with an iOS SDK; JDK 21 (Temurin or equivalent).
- Android SDK configured through `ANDROID_HOME`/`ANDROID_SDK_ROOT` because the
  independent POS Gradle settings also configure Android modules.
- Repository root available, because the scripts and workflows live outside
  `apps/pos`.

## Local build and package

From the repository root:

```sh
scripts/pos/ios/build-ios-artifacts.sh \
  --pos-root apps/pos \
  --out build/pos-artifacts/ios
```

The script:

1. validates the selected Xcode, iPhone SDKs, arm64 host and JDK 21;
2. creates a disposable iPhone simulator;
3. builds the simulator app through Xcode, which runs the Gradle framework
   phase, with `CODE_SIGNING_ALLOWED=NO`;
4. boots the simulator, installs and launches `com.ukkera.pos.local`, waits,
   checks the process, captures a screenshot and app log, then shuts down and
   deletes the disposable simulator;
5. archives the **unsigned** device app with
   `CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO`;
6. verifies the bundle IDs, absence of `embedded.mobileprovision`, signing state,
   architecture and archive contents;
7. writes diagnostics, `README-ARTIFACTS.txt`, `SHA256SUMS` and `manifest.json`.

Useful variants:

```sh
# No simulator boot; still builds and packages both products.
SIMULATOR_SMOKE=0 scripts/pos/ios/build-ios-artifacts.sh

# Also run focused Kotlin/Native simulator tests (optional and slower).
RUN_IOS_KOTLIN_TESTS=1 scripts/pos/ios/build-ios-artifacts.sh

# Record the original source revision when the auxiliary snapshot has no history.
SOURCE_REVISION=0c335645 scripts/pos/ios/build-ios-artifacts.sh
```

Manual equivalent for the simulator app (after selecting Xcode):

```sh
cd apps/pos
./gradlew :ui:app:linkDebugFrameworkIosSimulatorArm64 --console=plain
cd apps/ios
xcodebuild \
  -project UkkeraPos.xcodeproj \
  -scheme UkkeraPos \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/ukkera-pos-ios-derived \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  ARCHS=arm64 \
  build
```

## Artifacts

| File | Meaning |
|---|---|
| `UkkeraPos-simulator.app.zip` | Unsigned iOS Simulator application. Install only in a Simulator. |
| `UkkeraPos-unsigned-device.xcarchive.zip` | Device archive built with signing disabled. **Not signed, has no provisioning profile and is not installable on a physical iPhone or iPad.** |
| `diagnostics/` | Xcode/Gradle logs, Xcode build settings, SDK/version output, simulator launch log, screenshot, signing state, archive contents. |
| `SOURCE-REVISION.txt`, `SHA256SUMS`, `manifest.json` | Original/auxiliary source revisions, per-file SHA256 and toolchain metadata. |
| `README-ARTIFACTS.txt` | Warning and artifact description inside the uploaded payload. |

The payload intentionally excludes credentials, provisioning profiles, signing
keys, native databases and Gradle caches. Logs and copied diagnostics are
sanitized for local home paths. The simulator app is unsigned but installable in
a Simulator; the device archive is **not** an installable app.

## Install and smoke-test a simulator package

```sh
unzip UkkeraPos-simulator.app.zip
xcrun simctl create "UkkeraPOS check" "<iPhone device type>" "<iOS runtime identifier>"
xcrun simctl boot "<UDID>"
xcrun simctl bootstatus "<UDID>" -b
xcrun simctl install "<UDID>" UkkeraPos.app
xcrun simctl launch "<UDID>" com.ukkera.pos.local
xcrun simctl io "<UDID>" screenshot simulator.png
xcrun simctl terminate "<UDID>" com.ukkera.pos.local
xcrun simctl shutdown "<UDID>"
xcrun simctl delete "<UDID>"
```

The default launcher must show its explicitly unconfigured bootstrap state. It
constructs `AppDependencies(platform = PlatformFamily.IOS)`; the branch, central
and local-enrollment gateways remain `Unavailable*`, `centralServicesEnabled` is
false, and there is no iOS Master host/LAN gateway in this build. The smoke check
therefore proves process startup and a rendered scene only, not authentication,
company/branch setup, Master authority, RPC or business workflows. Do not disable
production identity/security checks to make startup pass.

## Physical device remains separate

The unsigned archive has no team, certificate, provisioning profile or embedded
profile. To test on a real iPhone/iPad, open the project on the owner's Mac,
select the owner's signing team and qualified profile, rebuild from source, and
record that signed artifact separately. Never present the unsigned archive as
installable, signed or owner-accepted.

Current gaps remain: no iOS Master host, LAN gateway, keychain or peripheral
adapter; no claimed minimum iOS version (15.0 is a candidate); real
app-switcher/VoiceOver/background/lock/termination behavior, 60/120 Hz,
multitasking, cryptography and hardware qualification are unexecuted. Physical
iPhone and iPad LTR/RTL gates remain. Compiling and launching the unconfigured
shell is not operational Master completion.

## Manual CI (auxiliary repository only)

`.github/workflows/pos-ios-native.yml` is `workflow_dispatch` only and refuses to
run outside `kira-manga/test`. Root runs it after snapshotting the source; the
child author does not push, dispatch or upload. The workflow produces the same
artifact directory and verifies Xcode itself rather than pinning a guessed
version. See [the packaging handoff](../../../../docs/pos/NATIVE-PACKAGING-HANDOFF.md)
for the root dispatch commands and all platform artifacts.
