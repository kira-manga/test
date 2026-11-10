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
| Android, latest setup diagnostic | [API 35 diagnostic, run 42737203959](https://github.com/kira-manga/test/actions/runs/42737203959/artifacts/13727232956) | `testing-inputs/ukkera-pos-testing.apk`, clean source `fac0cf70`; only install the application APK. Native fixture observed Installed and original setup readback, then process died during the next closed native cut; zero completed methods. This is an installable diagnostic, not a qualified setup/ACK workflow. |
| Android, API 24+ | [API 37 artifact, run 42687218267](https://github.com/kira-manga/test/actions/runs/42687218267/artifacts/13704653553) | `ukkera-pos-testing.apk`, source `afc7f369`; install the application APK, not the instrumentation APK |
| Windows x64 | [MSI artifact, run 42689314277](https://github.com/kira-manga/test/actions/runs/42689314277/artifacts/13707405198) | `UkkeraPOS-0.1.0.msi`, source `35441670`; includes the Windows namespace serialization and DPAPI envelope fixes |
| macOS Apple Silicon | [ARM64 DMG, run 42679207040](https://github.com/kira-manga/test/actions/runs/42679207040/artifacts/13701379955) | `UkkeraPOS-1.0.0.dmg`, source `f71044b8` |
| macOS Intel | [x64 DMG, run 42679207040](https://github.com/kira-manga/test/actions/runs/42679207040/artifacts/13703125856) | `UkkeraPOS-1.0.0.dmg`, source `f71044b8` |
| iPhone/iPad Simulator on an Apple Silicon Mac | [iOS artifact, run 42680343828](https://github.com/kira-manga/test/actions/runs/42680343828/artifacts/13706096880) | `UkkeraPos-simulator.app.zip`, source `038c115e`; startup tested on iPhone 17 Pro and iPad Pro 13-inch (M5) Simulators, iOS 26.5, Xcode 26.6 |
| Physical iPhone/iPad | Same iOS artifact | Release `UkkeraPos-unsigned-device.xcarchive.zip` is built but **cannot be installed until signed and provisioned** |
| Alternative unsigned iOS Debug archive | [Debug artifact, run 42688767829](https://github.com/kira-manga/test/actions/runs/42688767829/artifacts/13706303743) | Device-only Debug archive, source `e8cedcf5`; no simulator/test rerun in this job; also not installable until signed |

The package revisions differ because later changes include Android setup/native
corrections, Windows code, test fixtures, executor scripts and evidence. Do not describe them all as one tested
source revision. See [exact verification](../../qa/pos/traceability/epic3-native-qualification-20261109/README.md)
for the boundaries and preserved failed runs.

## Verify package SHA-256

On macOS/Linux use `shasum -a 256 <package>`. On Windows use
`Get-FileHash -Algorithm SHA256 <package>` in PowerShell.

| Package | SHA-256 |
| --- | --- |
| Older Android startup APK (`afc7f369`) | `773aa6b0ee98475dfb8d32b579a1e2d3f352879f19f23cdd84142e40558891d7` |
| Latest Android diagnostic APK (`fac0cf70`) | `fb5d0029672476cf3b2472c86a63c3941bd3fded3ca2d80c5c2beaf8d9b98f9b` |
| Refreshed Windows MSI | `f1e1793cd716194346e925e94a6274645a072204a08e564b21f461a78f24fe47` |
| macOS ARM64 DMG | `009d9041a8f552b2e385085e3cf87280adb3d29803c17fc67029b0f623293b75` |
| macOS x64 DMG | `63594d0d77e2f2308a2ded21e34a002ba853ce04aca26e570e7f5f9648122d13` |
| iOS Simulator app ZIP | `512c42221b66fdbe9e005ad5aed0784a576a6add9a7d0abf828dfe30285a9504` |
| iOS unsigned Release archive ZIP | `22302c427c1d61dd7843b23bd97464066d89cdafab4faef56c81228acae392e8` |
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
`fac0cf70` diagnostic includes the IV, Room sidecar, TEMP-column and DAO-scope
corrections. Its unchanged native fixture obtained `Installed`, reread the exact
original setup and confirmed actual owner cleanup; the next independent closed
native cut ended with OS signal 9 before any test method completed. Earlier
native attempts also refused final currentness. These failures remain retained
in the [source-bound evidence](../../qa/pos/traceability/android-original-ack-acquisition-20261109/README.md).
This artifact does not establish full setup, repeat ACK, cold recovery, login or
business workflow acceptance. Preserve an unresolved original and record its
state; do not reset it or create a replacement merely to continue testing.

Automated evidence covers N18/N19/N20 native file I/O on API 24/35/37, two native
key slices per API, and API 37 application startup. The key runs observed
**SOFTWARE / OS_ISOLATED** evidence; hardware/StrongBox and an independently
captured pre/post-restart identity comparison are not established.

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
five seconds and the entry screen rendered. Interactions, rotation, accessibility
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
   profile’s App ID prefix plus bundle ID. The downloadable unsigned archives
   predate this explicit entitlement configuration; rebuild from the current PR81
   source for the signed test. No Team ID/prefix or wildcard group is guessed.
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
