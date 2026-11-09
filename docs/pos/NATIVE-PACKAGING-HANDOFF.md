# Native packaging and handoff — macOS, Windows, iPhone/iPad

**Prepared from parent `37f8285b` for root integration.** This document is a
runbook and a record of prepared source/config, not a claim that the native
builds already ran. No remote repository, Actions run, upload, push or PR was
performed by the packaging child.

The source of truth for a particular run is the auxiliary snapshot revision (or
the `source_revision` dispatch input) recorded in each `manifest.json`. A green
packaging job proves compilation/linking/packaging and, for iOS, a bounded
simulator launch check. It does not establish an operational Master, business
setup, authentication, RPC, real devices/peripherals, signing/notarization or
owner acceptance.

## 0. External preflight context

Root reported these source-level checks at snapshot `0c335645` while this
packaging handoff was being prepared (this child did not rerun them): Apple
`:core:security` iOS simulator tests **336 passed, 0 failed**; `app-gateway` and
`database` Apple compilation; UI framework link; Windows `core/security` and
desktop compilation. They establish Kotlin/Native compilation and framework
linkage, **not** the Xcode application link, simulator launch, DMG/MSI package,
installed behavior or owner acceptance covered by the jobs below.

## 1. What is ready

| Area | Files | Purpose |
|---|---|---|
| iOS Xcode host | `apps/pos/apps/ios/` | Shared `UkkeraPos` scheme, Swift imports, framework build-phase script, honest install/limitations README. |
| Desktop packaging | `apps/pos/apps/desktop/build.gradle.kts`, `README.md` | Stable macOS bundle ID, stable MSI upgrade UUID, per-user MSI, DMG/MSI verification and local instructions. |
| iOS build/package | `scripts/pos/ios/` | Disposable simulator selection, Xcode simulator build, launch smoke, unsigned device archive, diagnostics, manifest. |
| Desktop build/package | `scripts/pos/desktop/` | Compose `packageDmg`/`packageMsi`, DMG mount check, MSI administrative extract, optional `--smoke-exit`, diagnostics, manifest. |
| Artifact hygiene | `scripts/pos/artifact_manifest.py`, `scripts/pos/sanitize_diagnostics.py` | SHA256 manifest and rejection of credentials, signing material and native databases before upload. |
| Manual CI | `.github/workflows/pos-ios-native.yml`, `.github/workflows/pos-desktop-native.yml` | `workflow_dispatch` only, restricted to `kira-manga/test`, official macOS/Windows runners, per-job artifacts. |
| Config validation | `scripts/pos/validate-config.sh` | Lightweight shell/Python/YAML/XML/Xcode-project/package-DSL checks. |

Do not edit security/common identity code, `IosAppHost`, or desktop `Main.kt`
business dependencies as part of packaging. Those remain with their owners.

## 2. Exact task and command paths

### iOS

```text
:ui:app:embedAndSignAppleFrameworkForXcode   # Xcode build phase; framework link/embed
:ui:app:linkDebugFrameworkIosSimulatorArm64  # direct simulator framework link
:ui:app:linkReleaseFrameworkIosArm64         # direct device framework link
```

The Xcode project invokes `apps/pos/apps/ios/xcode-build-framework.sh`, which
runs the first task with JDK 21 and the standard Gradle limits. `xcodebuild`
then compiles Swift and links `-framework UkkeraPos`.

```sh
# Full local iOS package + simulator smoke + unsigned device archive.
scripts/pos/ios/build-ios-artifacts.sh --pos-root apps/pos

# Optional focused Kotlin/Native simulator tests (no JVM-only module is added).
RUN_IOS_KOTLIN_TESTS=1 scripts/pos/ios/build-ios-artifacts.sh
```

`adapters:local-master-host` is JVM-only. It has no
`compileKotlinIosSimulatorArm64` task and must not be used in an Apple
compilation list.

### Desktop

```text
:apps:desktop:packageDmg              # macOS DMG, run on macOS
:apps:desktop:packageMsi              # Windows MSI, run on Windows
:apps:desktop:createDistributable     # app image used by packaging
```

```sh
# macOS
scripts/pos/desktop/package-desktop.sh --format dmg

# Windows Git Bash
scripts/pos/desktop/package-desktop.sh --format msi

# Add the bounded launcher smoke only where a desktop session is available.
DESKTOP_SMOKE=1 scripts/pos/desktop/package-desktop.sh --format dmg
DESKTOP_SMOKE=1 scripts/pos/desktop/package-desktop.sh --format msi
```

On Windows, invoke Gradle with `bash ./gradlew` or quote every `-P`/`-D`
argument. Unquoted PowerShell splitting can turn
`-Pkotlin.compiler.execution.strategy=in-process` into a bogus task name.

## 3. Root-owned manual CI dispatch

The workflows are manual only and contain a hard guard:

```text
GITHUB_REPOSITORY == kira-manga/test
```

They do not run on `push`, `pull_request`, `schedule` or the original
`ukkera/kmp-apps` repository. Snapshot the **repository root** (including
`apps/pos`, `scripts/pos` and `.github/workflows`), not only `apps/pos`, or the
locator step will fail with a clear message.

After the auxiliary snapshot is in place:

```sh
# iOS simulator app, smoke, unsigned device archive, manifest.
gh workflow run pos-ios-native.yml --repo kira-manga/test --ref <snapshot-branch> \
  -f source_revision=0c335645 \
  -f simulator_smoke=true \
  -f run_ios_kotlin_tests=false \
  -f xcode_developer_dir=

# macOS arm64+x64 DMGs and Windows MSI, each with diagnostics/manifests.
gh workflow run pos-desktop-native.yml --repo kira-manga/test --ref <snapshot-branch> \
  -f source_revision=0c335645 \
  -f desktop_smoke=false

# Inspect/download only after the run completes.
gh run list --repo kira-manga/test --workflow pos-ios-native.yml
gh run download <run-id> --repo kira-manga/test --dir build/aux-artifacts
```

Artifact names contain the run id and attempt:

- `pos-ios-native-<run>-<attempt>`
- `pos-macos-dmg-arm64-<run>-<attempt>`
- `pos-macos-dmg-x64-<run>-<attempt>`
- `pos-windows-msi-<run>-<attempt>`

Every payload normally contains `README-ARTIFACTS.txt`,
`SOURCE-REVISION.txt`, `manifest.json` (source and toolchain metadata) and
`SHA256SUMS`. The aggregate per-run SHA is the packaged
file SHA in the manifests; there is no invented release signature.

### Runner/toolchain policy

- iOS uses `macos-26` (arm64). POS declares only `iosSimulatorArm64`, so an Intel
  macOS runner cannot build the simulator app without adding a legacy `iosX64`
  target. The script fails clearly instead of pretending.
- Desktop uses `macos-26` for arm64 DMG, `macos-26-intel` for x64 DMG and
  `windows-2025` for MSI. These are GitHub-hosted official images.
- Xcode is **validated at runtime** (`xcode-select -p`, `xcodebuild -version`,
  `-showsdks`, SDK versions). The project no longer requires an assumed Xcode
  26.4. The optional `xcode_developer_dir` input selects another installed
  Xcode. The actual version is stored in logs and `manifest.json`.
- JDK 21 is installed with `actions/setup-java`; `jpackage` is checked before
  packaging.
- Android SDK is required for POS Gradle configuration but Android artifacts are
  not built. The runner images already provide `ANDROID_HOME`. If a workflow is
  adapted later to install it with `setup-android`, set
  `packages: platform-tools`; the action's old default `tools` package is
  retired and fails before compilation.
- Workflows use `shell: bash`, and scripts use `set -euo pipefail` around every
  `tee`d Gradle/Xcode log. A failed build cannot look successful.

## 4. Artifact contents and honest status

### iOS

```text
UkkeraPos-simulator.app.zip              unsigned, Simulator-only
UkkeraPos-unsigned-device.xcarchive.zip  unsigned, no profile, NOT installable
diagnostics/                             Xcode/Gradle logs, SDK/toolchain, smoke screenshot/log
SHA256SUMS, manifest.json                file hashes + source/toolchain metadata
README-ARTIFACTS.txt                     warning and contents
```

The device archive is built with:

```text
CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
CODE_SIGN_IDENTITY= CODE_SIGN_ENTITLEMENTS= DEVELOPMENT_TEAM=
PROVISIONING_PROFILE_SPECIFIER=
```

The script also asserts that `embedded.mobileprovision` is absent. Therefore the
archive **must never be presented as installable, signed or owner-ready**.
Physical-device testing requires the owner's signing team/profile and a separate
signed build. The simulator app is not signed either, but simulator installation
does not require a profile. The simulator zip is staged before the smoke check;
if the process check fails, the script still attempts the device archive and
diagnostics, then fails the job with the captured launch evidence.

The simulator smoke test:

1. creates a disposable iPhone simulator;
2. builds the Xcode app (which runs the Gradle framework phase);
3. installs and launches `com.ukkera.pos.local`;
4. requires the exact launch PID to remain visible after a 5-second startup interval, polling for up to 30 seconds;
5. captures a screenshot and app log;
6. terminates, shuts down and deletes the simulator.

It does not authenticate, enter company/branch setup, start a Master, exercise
RPC, or bypass any identity check. The default iOS composition is
`AppDependencies(platform = PlatformFamily.IOS)`: branch/central/enrollment
gateways stay `Unavailable*`, central services are disabled, and there is no iOS
Master host/LAN gateway. The desktop `Main.kt` likewise passes the default
`Unavailable*` composition. The app setup dependency gap is intentional and must
remain explicit; a package smoke must not be described as working setup or
checkout. If the default launcher crashes, that is a real defect to diagnose, not
permission to disable security.

### Desktop

```text
UkkeraPOS-0.1.0.dmg or UkkeraPOS-0.1.0.msi   unsigned local test package
diagnostics/                                  Gradle/package/signing/version logs
SHA256SUMS, manifest.json                     file hashes + source/toolchain metadata
README-ARTIFACTS.txt                          warning and contents
```

The DMG is verified by `hdiutil verify`, mounted read-only, bundle ID checked,
executable architecture recorded, and `codesign`/`spctl` state recorded (expected
to be unsigned/not accepted). The MSI is parsed with `msiexec /a` (or 7-Zip
fallback), the extracted `UkkeraPOS.exe` is checked, and Authenticode state is
recorded (expected `NotSigned`). Optional `--smoke-exit` runs the packaged
launcher for at most 60 seconds.

## 5. Install and test handoff

### iOS Simulator

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

Record the simulator runtime/device type, Xcode version, screenshot and app log.
Expected: visibly unconfigured bootstrap; no fake login or business data.

### macOS DMG

```sh
hdiutil attach UkkeraPOS-0.1.0.dmg -nobrowse -readonly
# Copy UkkeraPOS.app out of the mounted volume for a local check.
# Gatekeeper warning is expected because the package is unsigned.
/Applications/UkkeraPOS.app/Contents/MacOS/UkkeraPOS --smoke-exit
```

Do not globally disable Gatekeeper. For owner use, produce a signed/notarized
build with the owner's identity instead.

### Windows MSI

```bat
msiexec /i UkkeraPOS-0.1.0.msi /qn
"%LOCALAPPDATA%\UkkeraPOS\UkkeraPOS.exe" --smoke-exit
```

The MSI is per-user and unsigned; SmartScreen may warn. Uninstall through
Settings → Apps. The package is not a service installer and does not create a
branch master.

## 6. Verification before relying on a run

Run the local config checks first:

```sh
scripts/pos/validate-config.sh
```

It checks shell/Python syntax, `Info.plist`/scheme XML, the Xcode native target ↔
shared scheme binding, DMG/MSI package DSL fields, YAML parsing, manual-only
triggers, the auxiliary-repository guard, and artifact-hygiene rejection rules.
It does not compile Kotlin or run Xcode.

For each actual CI run, retain:

- the workflow run URL and `manifest.json` (`source_revision`,
  `aux_snapshot_revision`, run id/attempt);
- Xcode version/build and SDK versions from `diagnostics/`;
- the simulator smoke result, screenshot and app log;
- `codesign`/`spctl`/Authenticode state (unsigned expected);
- any failed Gradle/Xcode/jpackage/WiX error text;
- SHA256 for the packaged DMG/MSI/`.app.zip`/`.xcarchive.zip`.

A successful package is not a passing owner test. The iOS smoke and desktop
`--smoke-exit` only prove bounded startup/shutdown of the unconfigured shell.

## 7. Known risks and blockers to report, not hide

1. **No native run by this author.** The Linux authoring host cannot compile,
   link, launch or sign Apple targets or build Windows MSI/macOS DMG. All results
   must come from the macOS/Windows runners or the owner's hosts.
2. **Xcode/Kotlin compatibility.** The runner may install Xcode 26.6, while
   Kotlin 2.4.21/Compose 1.12.2 may have a toolchain-specific issue. The script
   records and reports the actual Xcode; do not silently pin or fake 26.4.
3. **Static framework/link flags.** The Xcode project links `UkkeraPos` through
   `OTHER_LDFLAGS` and the standard `xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`
   path. If an iOS SDK or Compose/CryptoKit dependency adds a missing system
   framework, the real `xcodebuild` link error is the required evidence.
4. **Simulator launch.** Default `IosAppHost()` composes the unconfigured shared
   runtime and platform file adapters. A launch crash is an implementation defect;
   do not disable production identity/security checks to pass smoke.
5. **jpackage/WiX.** MSI creation depends on WiX 3.x on `windows-2025` and JDK 21
   `jpackage`. DMG creation depends on macOS `jpackage`/`hdiutil`. The package
   scripts record the real failure; no installer identity is invented.
6. **Unsigned artifacts.** Gatekeeper/SmartScreen warnings are expected. The iOS
   device archive is categorically not installable; only a separately signed
   build can go to a physical device.
7. **Missing native scope.** No iOS master host, LAN gateway, keychain or
   peripheral adapter; Windows/macOS key/history owners and current-schema
   Master/Client composition remain separate implementation work. iOS 15 is a
   candidate minimum, not a qualified support claim.
8. **No automatic workload.** The workflows are restricted to manual dispatch in
   `kira-manga/test`. Do not enable `push`/`pull_request` on the original
   repository to obtain a run.

## 8. Integration checklist for root

- [ ] Snapshot repository root at the intended source revision.
- [ ] Confirm `.github/workflows/pos-ios-native.yml` and
      `pos-desktop-native.yml` are present and `workflow_dispatch` only.
- [ ] Dispatch iOS and desktop workflows in `kira-manga/test` with the original
      source SHA.
- [ ] Download artifacts; verify `SHA256SUMS`/`manifest.json`, Xcode version and
      package names.
- [ ] Preserve failures and exact logs; do not convert an unsigned archive into a
      physical-device claim.
- [ ] Hand the simulator app, DMGs/MSI and diagnostics to the owner test campaign
      separately; record actual launch/install outcomes and remaining scope.
