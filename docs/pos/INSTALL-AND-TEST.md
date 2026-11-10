# Local installation and testing — 10 November 2026

These are synthetic local qualification builds. Epic 3 development and owner
acceptance remain incomplete. Android has native setup/service integration;
desktop and iOS currently launch an **unconfigured bootstrap**. Installing them
does not provide a working branch Master, employee login, checkout or paired
Client. Do not use production data or real payments.

## Download packages with their verification limits

Sign in to GitHub with access to `kira-manga/test`. Download the named Actions
artifact, extract its outer ZIP, and use the package below. The auxiliary repo
is temporarily **public** during the authorized native Actions campaign because
private-runner minutes are exhausted. It will be restored private when the
campaign finishes. No credentials, signing material or private data may be
uploaded. Artifacts have a 14-day retention period. Keep the package with its
manifest, source revision and checksum files. Package signing remains a separate
requirement.

| Platform | Download | Package and tested source |
| --- | --- | --- |
| Android, latest installable diagnostic | [API 35 startup artifact, run 42791988202](https://github.com/kira-manga/test/actions/runs/42791988202/artifacts/13751684962) | `testing-inputs/ukkera-pos-testing.apk`, clean source `631df201`; install only the application APK. Includes native RPC LAN permission, physical-session cleanup and bounded history-read guards; bounded activity/PID startup passed on API24/35/37 x86_64. No permission-revocation, full setup/ACK or LAN qualification. |
| Android, API 24+ | [API 37 artifact, run 42687218267](https://github.com/kira-manga/test/actions/runs/42687218267/artifacts/13704653553) | `ukkera-pos-testing.apk`, source `afc7f369`; install the application APK, not the instrumentation APK |
| Windows x64 | [MSI artifact, run 42790747818](https://github.com/kira-manga/test/actions/runs/42790747818/artifacts/13751569890) | `UkkeraPOS-0.1.0.msi`, source `631df201`; package/content/architecture and packaged startup passed |
| macOS Apple Silicon | [ARM64 DMG, run 42790747818](https://github.com/kira-manga/test/actions/runs/42790747818/artifacts/13751588657) | `UkkeraPOS-1.0.0.dmg`, source `631df201`; package/content/architecture and packaged startup passed |
| macOS Intel | [x64 DMG, run 42790747818](https://github.com/kira-manga/test/actions/runs/42790747818/artifacts/13751911072) | `UkkeraPOS-1.0.0.dmg`, source `631df201`; package/content/architecture and packaged startup passed |
| iPhone/iPad Simulator on an Apple Silicon Mac | [iOS artifact, run 42790741202](https://github.com/kira-manga/test/actions/runs/42790741202/artifacts/13753475302) | `UkkeraPos-simulator.app.zip`, source `631df201`; link and exact-PID startup on iPhone 17 Pro passed, iOS 26.5 / Xcode 26.6. 464 native simulator methods passed with zero failures/errors/skips; exact-package iPad startup passed in run 42793999046 |
| Physical iPhone/iPad | Same iOS artifact | Release `UkkeraPos-unsigned-device.xcarchive.zip` is built but **cannot be installed until signed and provisioned** |
| Alternative unsigned iOS Debug archive | [Debug artifact, run 42688767829](https://github.com/kira-manga/test/actions/runs/42688767829/artifacts/13706303743) | Device-only Debug archive, source `e8cedcf5`; no simulator/test rerun in this job; also not installable until signed |

The current Android, desktop and iOS packages above share source `631df201`.
The explicitly labelled historical alternatives have different revisions. The 464-method Kotlin simulator regression also executed at this source. Earlier
`0ea09405` packages and iPad observations remain in the dated refresh evidence. See [latest Android startup verification](../../qa/pos/traceability/android-bounded-startup-20261110/README.md) and [exact verification](../../qa/pos/traceability/epic3-native-qualification-20261109/README.md)
for the boundaries and preserved failed runs. The [10 November refresh](../../qa/pos/traceability/native-build-refresh-20261110/README.md) records the newer desktop/iOS source, packages and actual checks.

## Verify package SHA-256

On macOS/Linux use `shasum -a 256 <package>`. On Windows use
`Get-FileHash -Algorithm SHA256 <package>` in PowerShell.

| Package | SHA-256 |
| --- | --- |
| Older Android startup APK (`afc7f369`) | `773aa6b0ee98475dfb8d32b579a1e2d3f352879f19f23cdd84142e40558891d7` |
| Latest Android diagnostic APK (`631df201`) | `29b84f45479fb0c61310c0664c8fb1847866b94a5d8f9be718195b3c5c554d70` |
| Refreshed Windows MSI | `3aeae7a962e5c6b4adbba1522d4dc6d65c0d3987c200273da275716a565e7ab6` |
| macOS ARM64 DMG | `8529dac2d2c23d5ff1988e9bc1722867d786c90bde5de89856a7d368faf605a2` |
| macOS x64 DMG | `0ee1fd281d13cc6ac7c9d06a2893d86bf1efda17921a097e7731d10d8f9ff8d3` |
| iOS Simulator app ZIP | `04cd50b2514e759dc84e921d5ff0cbb31e3174b128512515469652dedf6c4752` |
| iOS unsigned Release archive ZIP | `6cac0075b3f34a28d177c88705ca0e62bc6038142cdf8c09e9637458e20207be` |
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

**Android verification limits:** the latest `631df201` application includes native
RPC LAN permission, history-IV, Room coordination, TEMP-column, DAO-scope,
existing-only key-lookup, physical-session cleanup and bounded history-cell
batching changes. Its clean packaging/fatal lint and activity/original-PID startup
passed on API24/35/37, with zero instrumentation methods. The original ACK run
`42791908821` still used the historical single-chunk independent helper and ended
with OS-recorded signal 9 during the closed native cut: one method started, none
completed. No batch call was observed in that failed execution. The batched
executor in `42794005660` then reached traversal end and actual close, but its
application-census assertion failed because the helper mixed native/history
markers into its application-only set. The `4f40e3ff` executor corrects marker
classification, retains the existing assertion/fingerprints and additionally
checks the complete native census. Its signed build passed; the native successor
passed one API35 x86_64 installation-only native method in run `42795403725`,
including repeated confirmed-ACK observations, complete before/after native cuts
and zero state delta. The distinct operational close/reopen segment failed
before ACK assertions; its production registration-order correction is being
verified. A separate cold-original method terminated during history re-observation
without completing.
This does not establish whole ACK/setup/business/LAN acceptance.
[Exact source-bound results and limits](../../qa/pos/traceability/android-history-cell-batching-20261110/README.md).

Earlier native I/O and key slices remain bound to their recorded source revisions:
N18/N19/N20 I/O on API24/35/37 and two key slices per API; additional key selectors
at `ecc42c63` also ran on API24 x86 and the official API35 16 KiB profile. The key
runs observed **SOFTWARE / OS_ISOLATED**, not hardware/StrongBox or an independent
pre/post-restart identity comparison. Those executions are not repeated or claimed
for this latest APK. [Earlier key results](../../qa/pos/traceability/android-original-key-slices-20261109/README.md).
Full setup/repeat ACK, cold recovery, login, LAN and business acceptance remain
open. Preserve an unresolved original; do not reset it or create a replacement
merely to continue testing.

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
The refreshed `7f5320aa` artifact also passed [the exact-package iPad check](https://github.com/kira-manga/test/actions/runs/42764846715), with original PID 5245 visible after five seconds. The predecessor `53f496da` ZIP also
passed [run 42783159441](https://github.com/kira-manga/test/actions/runs/42783159441),
with original PID 3723 visible after five seconds and the unconfigured chooser in
the retained screenshot. The current `631df201` ZIP passed [run 42786909482](https://github.com/kira-manga/test/actions/runs/42786909482), retaining original PID 3173 after five seconds on iPad Pro 13-inch (M5), iOS 26.5. The screenshot shows the unconfigured workspace chooser. Interactions, rotation, accessibility
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
   at `631df201` selects this entitlement file; the older Debug archive
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
