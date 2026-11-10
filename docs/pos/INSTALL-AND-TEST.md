# Local installation and testing — 10 November 2026

These are synthetic local qualification builds. Epic 3 development and owner
acceptance remain incomplete. Android has native setup/service integration;
desktop and iOS currently launch an **unconfigured bootstrap**. Installing them
does not provide a working branch Master, employee login, checkout or paired
Client. Do not use production data or real payments.

## Download packages with their verification limits

Sign in to GitHub with access to `kira-manga/test`. Download the named Actions
artifact, extract its outer ZIP, and use the package below. The auxiliary repo
was restored **private** on 9 November 2026; artifacts have a 14-day retention
period. Access was independently checked after the visibility change. Keep the package with its
manifest, source revision and checksum files. Repository privacy does not replace
package signing.

| Platform | Download | Package and tested source |
| --- | --- | --- |
| Android, latest installable diagnostic | [API 35 startup artifact, run 42759031363](https://github.com/kira-manga/test/actions/runs/42759031363/artifacts/13737201155) | `testing-inputs/ukkera-pos-testing.apk`, clean source `1490aa61`; install only the application APK. Includes native RPC LAN permission guards; bounded activity/PID startup passed on API24/35/37 x86_64. No permission-revocation, full setup/ACK or LAN qualification. |
| Android, API 24+ | [API 37 artifact, run 42687218267](https://github.com/kira-manga/test/actions/runs/42687218267/artifacts/13704653553) | `ukkera-pos-testing.apk`, source `afc7f369`; install the application APK, not the instrumentation APK |
| Windows x64 | [MSI artifact, run 42756458982](https://github.com/kira-manga/test/actions/runs/42756458982/artifacts/13736605242) | `UkkeraPOS-0.1.0.msi`, source `5b6cefe7`; package/content/architecture and packaged startup passed |
| macOS Apple Silicon | [ARM64 DMG, run 42756458982](https://github.com/kira-manga/test/actions/runs/42756458982/artifacts/13735647679) | `UkkeraPOS-1.0.0.dmg`, source `5b6cefe7`; package/content/architecture and packaged startup passed |
| macOS Intel | [x64 DMG, run 42756458982](https://github.com/kira-manga/test/actions/runs/42756458982/artifacts/13735608503) | `UkkeraPOS-1.0.0.dmg`, source `5b6cefe7`; package/content/architecture and packaged startup passed |
| iPhone/iPad Simulator on an Apple Silicon Mac | [iOS artifact, run 42762460399](https://github.com/kira-manga/test/actions/runs/42762460399/artifacts/13738363918) | `UkkeraPos-simulator.app.zip`, source `7f5320aa`; 464 simulator methods and exact-PID startup on iPhone 17 Pro passed, iOS 26.5 / Xcode 26.6; exact-PID startup also passed on iPad Pro 13-inch (M5) using this same package |
| Physical iPhone/iPad | Same iOS artifact | Release `UkkeraPos-unsigned-device.xcarchive.zip` is built but **cannot be installed until signed and provisioned** |
| Alternative unsigned iOS Debug archive | [Debug artifact, run 42688767829](https://github.com/kira-manga/test/actions/runs/42688767829/artifacts/13706303743) | Device-only Debug archive, source `e8cedcf5`; no simulator/test rerun in this job; also not installable until signed |

The package revisions differ because later changes include Android setup/native
corrections, Windows code, test fixtures, executor scripts and evidence. Do not describe them all as one tested
source revision. See [latest Android startup verification](../../qa/pos/traceability/android-bounded-startup-20261110/README.md) and [exact verification](../../qa/pos/traceability/epic3-native-qualification-20261109/README.md)
for the boundaries and preserved failed runs. The [10 November refresh](../../qa/pos/traceability/native-build-refresh-20261110/README.md) records the newer desktop/iOS source, packages and actual checks.

## Verify package SHA-256

On macOS/Linux use `shasum -a 256 <package>`. On Windows use
`Get-FileHash -Algorithm SHA256 <package>` in PowerShell.

| Package | SHA-256 |
| --- | --- |
| Older Android startup APK (`afc7f369`) | `773aa6b0ee98475dfb8d32b579a1e2d3f352879f19f23cdd84142e40558891d7` |
| Latest Android diagnostic APK (`1490aa61`) | `ee24da8d1f0279d052ef79993559fe3330bce0050a4b283c350171c2a9a5aa16` |
| Refreshed Windows MSI | `d818ee50c1deee7174009cac810d0897b8b5e87f6e9cec2d102e2641eaa0a0af` |
| macOS ARM64 DMG | `51c607aa60e4d64d1031f70f0d62d20ae440d8104a2da8e5240038422f9c9563` |
| macOS x64 DMG | `b7d3673fdb32dca28b80ff2fd19ebbb29d35bd501a6814500dc0164e561d5aba` |
| iOS Simulator app ZIP | `5e7da9af4511b3280f2e2f2fe532ab829a754d93ff84005ac65300faef4350d7` |
| iOS unsigned Release archive ZIP | `93013adccdfc303ecbabbd6157e7d4bd5d8487a00211f15b96e328a0473057dd` |
| iOS unsigned Debug archive ZIP | `51fae2442b336fcf40359845291f185b642994c1b462e70e5e776906811c0619` |

## Android

1. Use a disposable test device/profile on API 24 or later. Preserve existing test
   data unless an independently agreed clean-install case requires clearing it.
2. Install `ukkera-pos-testing.apk` through the Android package installer, granting
   install permission only to the file manager used, or use ADB:

   ```sh
   adb install -r ukkera-pos-testing.apk
   adb shell monkey -p com.ukkera.pos.local.testing -c android.intent.category.LAUNCHER 1
   ```

3. Launch **Ukkera POS**. The package ID is `com.ukkera.pos.local.testing` and the
   application is nondebuggable. Its existing testing signer is retained. If an
   update reports an incompatible signature, preserve the installed application
   and record the error; do not uninstall it to make the update appear successful.
4. On API 37, the app requests local-network access before a new LAN host/connect.
   Check denial and grant separately. Denial must not create an operational LAN
   endpoint. Grant alone must not authenticate an employee or admit a device.
5. Use synthetic setup values only. Record the exact step/result rather than
   assuming the whole setup→ACK→login→business workflow is qualified.

**Android verification limits:** the older startup-qualified APK predates the
AndroidKeyStore history-IV correction and failed original setup. The latest
`1490aa61` diagnostic includes native RPC LAN permission guards and the earlier
IV, Room sidecar, TEMP-column, DAO-scope
and existing-only key-lookup corrections. Its unchanged native fixture obtained `Installed`, reread the exact
original setup and confirmed actual owner cleanup; the next independent closed
native cut ended with OS signal 9 before any test method completed. Earlier
native attempts also refused final currentness. The identical pair on API24 x86
started one method and crashed during setup without a completed outcome or explicit
exclusion cause. These failures remain retained
in the [source-bound evidence](../../qa/pos/traceability/android-original-ack-acquisition-20261109/README.md).
The earlier `ecc42c63` package's native I/O, key, x86 and 16 KiB results do not
qualify those paths in the newest APK. This artifact does not establish full setup,
repeat ACK, cold recovery, login or
business workflow acceptance. Preserve an unresolved original and record its
state; do not reset it or create a replacement merely to continue testing.

Automated evidence covers N18/N19/N20 native file I/O on API 24/35/37, two native
key slices per API, and API 37 application startup. The key runs observed
**SOFTWARE / OS_ISOLATED** evidence; hardware/StrongBox and an independently
captured pre/post-restart identity comparison are not established.
The latest APK additionally passed both unchanged key selectors on API24 x86
and the official API35 16 KiB profile, with observed process absence before the
existing-only second invocation. [Exact source-bound key results and limits](../../qa/pos/traceability/android-original-key-slices-20261109/README.md#additional-runtime-profiles--10-november-2026).


## Windows

1. Use Windows x64 and open `UkkeraPOS-0.1.0.msi`, or run:

   ```powershell
   msiexec.exe /i .\UkkeraPOS-0.1.0.msi
   ```

2. Complete the per-user installer, then open **UkkeraPOS** from the Start menu.
   The install directory can vary; use the installed shortcut rather than an
   assumed `%LOCALAPPDATA%` path.
3. The installer is unsigned. If Windows blocks it, record the message and use
   an authorized local test installation procedure or obtain a signed package.
   Do not disable machine-wide protection.
4. Check the window and unconfigured bootstrap, resize it, close it, then reopen.
   Closing the default UI is not evidence of an operational Master shutdown.
5. Uninstall through Settings → Apps when the test is finished.

Hosted package verification extracted the MSI, inspected the executable and ran
its packaged `--smoke-exit` path. That is bounded startup evidence, not installed
full setup or service qualification. Separately, all ten native SDK-store methods
passed against real HKCU/DPAPI under a genuine nonadministrator account in
[run 42710676090](https://github.com/kira-manga/test/actions/runs/42710676090).
The OS-known LocalAppData owner and production DACL checks remained enforced;
no owner normalization was used. This qualifies the bounded SDK-store methods
on that profile, not installed full setup, native STORAGE/PEPPER keys,
cross-process contention or owner acceptance.

## macOS

1. Select the DMG matching the Mac's processor. Open it and copy `UkkeraPOS.app`
   into Applications (or a disposable user applications folder).
2. Open the copied application. These builds are unsigned and not notarized.
   Gatekeeper can refuse them. Use the Mac's authorized per-app local testing
   procedure or obtain a signed/notarized build; do not disable Gatekeeper globally.
3. Check the unconfigured bootstrap, resize, close and reopen it. The package
   does not establish an installed native Master/Client.
4. Eject the DMG. Remove only this test application when finished.

Both DMGs passed mount/content/architecture checks and packaged startup smoke on
matching hosted Mac architectures. Physical Mac behavior and protected-key
custody remain separate acceptance work.

## iOS Simulator

Install Xcode on an Apple Silicon Mac. Unzip `UkkeraPos-simulator.app.zip`, choose
an installed iPhone or iPad Simulator in Xcode, boot it, then run:

```sh
xcrun simctl list devices booted
xcrun simctl install booted UkkeraPos.app
xcrun simctl launch booted com.ukkera.pos.local
```

Use an explicit simulator UDID instead of `booted` if more than one is running.
This app ZIP targets the ARM64 iOS Simulator; it is not an iPhone/iPad IPA and
cannot run on physical hardware. The hosted launch used iOS 26.5. Other runtime
versions remain unverified. A separate exact-artifact startup check passed on an
iPad Pro 13-inch (M5) Simulator with iOS 26.5; the original launch PID survived
five seconds and the entry screen rendered **on predecessor source `038c115e`**.
The refreshed `7f5320aa` artifact also passed [the exact-package iPad check](https://github.com/kira-manga/test/actions/runs/42764846715), with original PID 5245 visible after five seconds. Interactions, rotation, accessibility
and full iPad presentation remain unverified.

Check the visible unconfigured bootstrap, rotate the simulator, background and
foreground the app, close and relaunch it. The security policy deliberately
refuses native installed-identity acceptance on a simulator. Do not work around
that refusal or interpret it as an unexpected setup failure.

## Physical iPhone/iPad — final signing step

The unsigned archives contain no provisioning profile. A team/account authorized
for the unchanged bundle ID `com.ukkera.pos.local`, an Apple development signing
identity and a matching device provisioning profile are needed. A paid developer
membership/device registration may be needed for the chosen distribution route.
No signing material is included in the repository or artifacts.

With authorized signing access on a Mac:

1. Open `apps/pos/apps/ios/UkkeraPos.xcodeproj` at the chosen tested source revision.
   Use JDK 21; the checked-in Xcode framework script builds the shared code. The
   current project references a single exact Keychain group using the authorized
   profile’s App ID prefix plus bundle ID. The refreshed unsigned Release archive
   at `7f5320aa` already selects this entitlement file; the older Debug archive
   predates that configuration. Neither unsigned archive carries usable signed,
   provisioned entitlements. Rebuild the selected source with authorized signing
   for a physical-device test. No Team ID/prefix or wildcard group is guessed.
2. Select scheme **UkkeraPos**, select the connected iPhone/iPad, and select the
   authorized Team under Signing & Capabilities. Retain the bundle ID. Let Xcode
   create/select the matching development profile through the owner's account.
3. Build and Run with signing enabled. Keep the existing code-signature,
   current-app, exact-group Keychain and separate custody checks. Do not add
   wildcard/shared identity grants or disable native verification to make setup run.
4. Record the signed build identity and device/OS. A successful signed bootstrap
   still does not complete native marker creation, key custody, history ownership
   or Master/Client composition; those production dependencies remain open.

The simulator app, Swift linking and unsigned device archives are already built.
The owner must provide signing access for a physical installation; a signing
certificate alone does not resolve the remaining native production composition.

## Synthetic screen and basket rehearsal

Where the entry screen offers **Open training workspace**, it opens the bounded
in-memory sample workspace without a production sign-in. Try product/category
search, add and remove sample basket items, and clear the basket. Switch between
English and Arabic, then leave and reopen training; record the actual result.
**Explore company training** similarly opens a synthetic company view. These
buttons do not create a company, employee, admission or production session.

The sample products span grocery, bakery, café, kitchen and clothing. Basket
amounts are illustrative sample totals; this rehearsal does not reserve stock,
print a fiscal receipt, take payment or retain a durable business result. No live
backend is required. Hosted startup checks did not click through this rehearsal,
so these are hands-on test instructions rather than a passing interaction claim.

## Manual result checklist

Record PASS, FAIL or BLOCKED for each attempted item with package hash, device/OS,
steps, actual result, timestamp and screenshot/log. Do not include passwords,
private keys, financial data or private native-store files.

- [ ] Correct package hash, processor/OS compatibility and source recorded.
- [ ] Installation/update succeeds without destroying previous test data.
- [ ] Normal launch shows a window/screen and remains alive for at least 30 seconds.
- [ ] Window resizing/rotation, Arabic/English presentation and available navigation
      behave correctly; record unavailable controls rather than inventing a flow.
- [ ] Background/foreground and close/relaunch return to an honest current state.
- [ ] Unconfigured desktop/iOS services do not falsely announce login, admission,
      a running Master, a payment or a committed business result.
- [ ] Android API 37 LAN denial/grant behaves as described above, if available.
- [ ] Any synthetic setup/ACK/login/recovery flow actually attempted is recorded
      separately, including its exact final step or blocker.
- [ ] Any unexpected crash or authority disclosure is reported against the owning
      development Issue as well as campaign #63.

Paired-device workflows, employee revocation versus commit, native original
close/reopen, interrupted physical operations, peripherals and power-loss tests
remain required in the broader [#61 owner campaign](https://github.com/ukkera/kmp-apps/issues/61).
This checklist does not replace their independent frozen cases.
