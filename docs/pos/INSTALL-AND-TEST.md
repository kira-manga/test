# Local installation and testing — 11 November 2026

These are synthetic local qualification builds. Epic 3 development and owner
acceptance remain incomplete. Android has native setup/service integration;
desktop and iOS currently launch an **unconfigured bootstrap**. Installing them
does not provide a working branch Master, employee login, checkout or paired
Client. Do not use production data or real payments.

## Download packages with their verification limits

Sign in to GitHub with access to `kira-manga/test`. Download the named Actions
artifact, extract its outer ZIP, and use the package below. The auxiliary repo
remains **public** for the authorized native Actions campaign because private
runner minutes are exhausted. The owner's latest instruction is to leave its
visibility unchanged. No credentials, signing material or private data may be
uploaded. Artifacts have a 14-day retention period. Keep the package with its
manifest, source revision and checksum files. Package signing remains a separate
requirement.

| Platform | Download | Package and tested source |
| --- | --- | --- |
| Android, latest collected closed-ancestor inspection build | [Exact signed pair, run42976531281](https://github.com/kira-manga/test/actions/runs/42976531281/artifacts/13836173015) | `testing-inputs/ukkera-pos-testing.apk`, clean application source `2f960d6b` (inventory implementation `e3203739`); install only the application APK. Signed packaging/fatal lint and1,835 JVM methods passed (two namespace skips); Apple passed562 stock simulator methods. Exact retained executor3213c7a0 keeps source51 provenance. Android recorded original capture/import and financial application, then signal9 while finalization was pending. The native owner logged a guard-refusal exclusion request at the shared deadline. Finalization, final close and cold reopens remain unknown; zero completed methods and no observed cleanup. Synthetic diagnostics only; preserve unresolved finalization and all earlier unknown originals. Does not contain later setup-closure UI retirement changes. |
| Android, preceding financial-node stock proof build | [Exact signed pair, run42969676284](https://github.com/kira-manga/test/actions/runs/42969676284/artifacts/13832724738) | `testing-inputs/ukkera-pos-testing.apk`, clean application source `6593e72f` (same production tree as `b5e52af5`); install only the application APK. Signed packaging/fatal lint and1,835 JVM methods passed (two namespace skips). Exact retained executor keeps source51 provenance. Native capture/import, same-ID result recovery and financial application were recorded, then signal9 while finalization was pending. Finalization, final close and cold reopens remain unknown; zero completed methods. Synthetic diagnostics only; preserve unresolved finalization and all earlier unknown originals. |
| Windows x64, updated presentation cleanup | [MSI artifact, run 42875573982](https://github.com/kira-manga/test/actions/runs/42875573982/artifacts/13791457653) | `UkkeraPOS-0.1.0.msi`, source `b28638df`; package/content/architecture and packaged startup passed, including failed-old-binding lock correction. All15 manifest/16 checksum entries verified. Unsigned, unconfigured bootstrap; no native cleanup-failure injection or complete branch workflow qualification. |
| macOS ARM64, current presentation cleanup build | [ARM artifact, run 42875573982](https://github.com/kira-manga/test/actions/runs/42875573982/artifacts/13792366363) | `UkkeraPOS-1.0.0.dmg`, source `b28638df`; mount/content/architecture and packaged startup passed, including the failed-old-binding lock correction. All18 manifest/19 checksum entries verified. Unsigned, unnotarized, unconfigured bootstrap. |
| macOS Intel, current presentation cleanup build | [Intel artifact, run 42875573982](https://github.com/kira-manga/test/actions/runs/42875573982/artifacts/13792331313) | `UkkeraPOS-1.0.0.dmg`, source `b28638df`; mount/content/architecture and packaged startup passed. All18 manifest/19 checksum entries verified. Unsigned, unnotarized, unconfigured bootstrap. |
| iOS Simulator, current foreground/shutdown cleanup build | [Apple artifact, run42867498835](https://github.com/kira-manga/test/actions/runs/42867498835/artifacts/13791088633) | `UkkeraPos-simulator.app.zip`, source `8a7f7888`; framework/Xcode linking and exact-PID iPhone17 Pro startup passed. The exact ZIP also passed iPad Pro13-inch Simulator original-PID startup in [42876800066](https://github.com/kira-manga/test/actions/runs/42876800066). Includes both cleanup corrections. No Kotlin test rerun or native failure injection in this job. |
| Physical iPhone/iPad, current cleanup archive | Same current Apple artifact | Debug `UkkeraPos-unsigned-device.xcarchive.zip`, source `8a7f7888`; native archive passed but requires authorized signing/provisioning before installation. |

<details>
<summary>Retained earlier Android diagnostic packages</summary>

| Platform | Download | Package and tested source |
| --- | --- | --- |
| Android, prior native-payment proof build | [Exact signed pair, run42962802720](https://github.com/kira-manga/test/actions/runs/42962802720/artifacts/13830611560) | `testing-inputs/ukkera-pos-testing.apk`, clean application source `99e8ba9e`; install only the application APK. Signed packaging/fatal lint and1,652 JVM methods passed (two namespace skips). Exact retained executor keeps source51 provenance. Native capture/import, same-ID result recovery and financial application were recorded, then signal9 while finalization was pending. Finalization, final close and cold reopens remain unknown; zero completed methods. Synthetic diagnostics only; preserve unresolved finalization and all earlier unknown originals. |
| Android, prior closed-ancestry build | [Exact signed pair, run42954657696](https://github.com/kira-manga/test/actions/runs/42954657696/artifacts/13827078255) | `testing-inputs/ukkera-pos-testing.apk`, clean source `355ea18a`; install only the application APK. Signed packaging/fatal lint and1,835 JVM methods passed (two namespace skips). Native capture/import and financial application were recorded, then signal9 after finalization dispatch. Finalization, final close and cold reopens remain unknown; zero completed methods. Synthetic diagnostics only; preserve the unresolved finalization and all earlier unknown originals. |
| Android, prior retained-inspection diagnostic | [Exact signed pair, run42951088557](https://github.com/kira-manga/test/actions/runs/42951088557/artifacts/13826380146) | `testing-inputs/ukkera-pos-testing.apk`, clean source `ec18aa3d`; install only the application APK. Signed packaging/fatal lint and1,835 JVM methods passed (two namespace skips). Native capture/import and financial application were recorded, then signal9 after finalization dispatch. Finalization, final close and cold reopens remain unknown; zero completed methods. Synthetic diagnostics only; preserve the unresolved finalization and all earlier unknown originals. |
| Android, prior canonical-ASCII build | [Exact signed pair, run42945521251](https://github.com/kira-manga/test/actions/runs/42945521251/artifacts/13823516639) | `testing-inputs/ukkera-pos-testing.apk`, clean source `1bc40eba`; install only the application APK. Signed packaging/fatal lint and1,835 JVM methods passed (two namespace skips). The native run observed durable successful capture/import and financial application `RECORDED`, then exited with signal9 after finalization dispatch. Finalization, final close and cold reopens are unobserved; zero methods completed. Synthetic diagnostics only; preserve the unresolved finalization and all earlier unknown originals. |
| Android, prior grouped-source build | [Exact signed pair, run42935013259](https://github.com/kira-manga/test/actions/runs/42935013259/artifacts/13818472127) | `testing-inputs/ukkera-pos-testing.apk`, source `dbd89440`; install only the application APK. Signed packaging/fatal lint, 1,652 existing JVM methods (two namespace skips) and 562 Apple stock simulator methods passed. Android failed one method: Start returned `STORAGE_UNCERTAIN`, instruction authorization was not reached, and final native close was unconfirmed. Preserve the unresolved original; full checkout/recovery remain unverified. |
| Android, prior instruction-proof build | [Exact signed pair, run42931770736](https://github.com/kira-manga/test/actions/runs/42931770736/artifacts/13816931217) | `testing-inputs/ukkera-pos-testing.apk`, clean source `9087e117`; install only the application APK. Fatal lint/signed pair and1,652 existing JVM methods passed (two explicit namespace skips);562 existing Apple stock simulator methods passed. Android recorded Start, native instruction preparation and retained Start read returned, then signal9 during domain authorization preparation. One method started, zero completed; instruction adoption, financial outcome and final cleanup remain unobserved. Preserve the unresolved original. |
| Android, prior selected-write batch / instruction diagnostic | [Exact signed pair, run42927423739](https://github.com/kira-manga/test/actions/runs/42927423739/artifacts/13814623650) | `testing-inputs/ukkera-pos-testing.apk`, clean source `088ba505`; install only the application APK. Fatal lint/signed pair and1,835 existing JVM methods passed (two explicit namespace skips);562 Apple stock simulator methods passed. Android recorded Start, native instruction preparation and retained Start read returned, then signal9 during domain authorization preparation. Zero methods completed; instruction adoption, financial outcome and final cleanup remain unobserved. Preserve the unresolved original. |
| Android, prior physical Start diagnostic | [Exact signed pair, run 42921427334](https://github.com/kira-manga/test/actions/runs/42921427334/artifacts/13812197159) | `testing-inputs/ukkera-pos-testing.apk`, clean source `bc1407ae`; install only the application APK. Signed packaging/fatal lint and 1,067 existing database methods passed (two explicit namespace skips). Start returned RECORDED after native acquisition, SQL execution, recheck, persistence and release returned. Signal9 followed before instruction adoption, financial outcome or final cleanup; zero methods completed. Full checkout and recovery remain unverified. |
| Android, prior normalized collection-core diagnostic | [Exact signed pair, run 42915474141](https://github.com/kira-manga/test/actions/runs/42915474141/artifacts/13809242529) | `testing-inputs/ukkera-pos-testing.apk`, clean source `80d30efa`; install only the application APK. Signed packaging/fatal lint, 1,652 existing JVM methods and 562 Apple stock simulator methods passed. Android Start returned `STORAGE_UNCERTAIN`; one method failed, native cleanup unconfirmed. Full checkout and recovery remain unverified. |
| Android, prior stock direct-source build | [Stock source artifact, run42894356180](https://github.com/kira-manga/test/actions/runs/42894356180/artifacts/13798789846) | `testing-inputs/ukkera-pos-testing.apk`, clean source `0833f397`; install only the application APK. Fatal lint/signed pair,1,835 existing JVM methods (two explicit namespace skips) and562 Apple Simulator methods passed. Android stock and complete handler preparation returned, then signal9 with no native acquisition, Start reply or confirmed cleanup; zero financial methods completed. Preserve unresolved original. |

</details>

<details>
<summary>Historical packages and their source-specific verification</summary>

| Platform | Download | Package and tested source |
| --- | --- | --- |
| Android, previous collection/order source build | [Commerce source artifact, run42885266594](https://github.com/kira-manga/test/actions/runs/42885266594/artifacts/13795269599) | `testing-inputs/ukkera-pos-testing.apk`, clean source `4b460ec8`; install only the application APK. Fatal lint/signed pair passed, with1,652 existing JVM methods and562 Apple Simulator methods passing (two explicit JVM namespace skips). Android collection preparation returned, stock preparation entered, then signal9 with no Start reply or confirmed cleanup; zero financial methods completed. Preserve unresolved original. |
| Android, previous payment member stream build | [Payment stream artifact, run 42875564777](https://github.com/kira-manga/test/actions/runs/42875564777/artifacts/13791353153) | `testing-inputs/ukkera-pos-testing.apk`, clean source `b28638df`; install only the application APK. Fatal lint, signed pair and1,835 JVM methods passed (one skipped). Native authentication returned and Start preparation entered, then signal9 with no reply or cleanup; zero financial methods completed. Preserve unresolved original. |
| Android, previous stock composition build | [Stock revision artifact, run 42854976285](https://github.com/kira-manga/test/actions/runs/42854976285/artifacts/13782961958) | `testing-inputs/ukkera-pos-testing.apk`, clean source `9bf03ee1`; install only the application APK. Signed packaging/fatal lint and 581 existing catalogue/inventory methods passed. Native API35 x86_64 reached recorded opening stock and collection begin, then signal9 before Start was observed; zero methods completed. Full checkout and cold recovery remain unverified. |
| Android, current authenticated STOP/close build | [Lifecycle artifact, run 42816765743](https://github.com/kira-manga/test/actions/runs/42816765743/artifacts/13764751908) | `testing-inputs/ukkera-pos-testing.apk`, clean source `f01bbb41`; install only the application APK. Signed package/fatal lint, 18 focused lifecycle JVM methods, full database regression and one API35 x86_64 native STOP/close supporting method passed without AOT. Full lifecycle/business/LAN/owner qualification remains open. |
| Android, actual-owner refresh/lifecycle diagnostic | [Lifecycle artifact, run 42807559079](https://github.com/kira-manga/test/actions/runs/42807559079/artifacts/13761390342) | `testing-inputs/ukkera-pos-testing.apk`, clean source `4c045a36`; install only the application APK. Signed package/fatal lint and full database regression passed. Three native lifecycle methods failed at authenticated STOP; the background method passed current-Master refresh and existing-only RUN reopen assertions before STOP failed. |
| Android, lifecycle closure-accounting build | [STOP/resume artifact, run 42834923867](https://github.com/kira-manga/test/actions/runs/42834923867/artifacts/13773606677) | `testing-inputs/ukkera-pos-testing.apk`, clean source `a476d536`; install only the application APK. Clean signed packaging/fatal lint and one unchanged API35 x86_64 supporting STOP/resume method passed. Whole lifecycle/financial/LAN/platform acceptance remains open. |
| Android, latest native-intake diagnostic | [Operational ACK artifact, run 42800059765](https://github.com/kira-manga/test/actions/runs/42800059765/artifacts/13755756444) | `testing-inputs/ukkera-pos-testing.apk`, clean source `c93b8671`; install only the application APK. Full database regression, fatal lint/package and one unchanged API35 x86_64 operational ACK close/reopen method passed without AOT. Whole setup/business/LAN/owner qualification remains open. |
| Android, prior cross-API startup diagnostic | [API 35 startup artifact, run 42791988202](https://github.com/kira-manga/test/actions/runs/42791988202/artifacts/13751684962) | `testing-inputs/ukkera-pos-testing.apk`, clean source `631df201`; install only the application APK. Includes native RPC LAN permission, physical-session cleanup and bounded history-read guards; bounded activity/PID startup passed on API24/35/37 x86_64. No permission-revocation, full setup/ACK or LAN qualification. |
| Android, API 24+ | [API 37 artifact, run 42687218267](https://github.com/kira-manga/test/actions/runs/42687218267/artifacts/13704653553) | `ukkera-pos-testing.apk`, source `afc7f369`; install the application APK, not the instrumentation APK |
| Windows x64, historical bootstrap | [MSI artifact, run 42790747818](https://github.com/kira-manga/test/actions/runs/42790747818/artifacts/13751569890) | `UkkeraPOS-0.1.0.msi`, source `631df201`; package/content/architecture and packaged startup passed |
| macOS Apple Silicon | [ARM64 DMG, run 42790747818](https://github.com/kira-manga/test/actions/runs/42790747818/artifacts/13751588657) | `UkkeraPOS-1.0.0.dmg`, source `631df201`; package/content/architecture and packaged startup passed |
| macOS Intel | [x64 DMG, run 42790747818](https://github.com/kira-manga/test/actions/runs/42790747818/artifacts/13751911072) | `UkkeraPOS-1.0.0.dmg`, source `631df201`; package/content/architecture and packaged startup passed |
| iPhone/iPad Simulator on an Apple Silicon Mac | [iOS artifact, run 42790741202](https://github.com/kira-manga/test/actions/runs/42790741202/artifacts/13753475302) | `UkkeraPos-simulator.app.zip`, source `631df201`; link and exact-PID startup on iPhone 17 Pro passed, iOS 26.5 / Xcode 26.6. 464 native simulator methods passed with zero failures/errors/skips; exact-package iPad startup passed in run 42793999046 |
| Physical iPhone/iPad | Same iOS artifact | Release `UkkeraPos-unsigned-device.xcarchive.zip` is built but **cannot be installed until signed and provisioned** |
| Alternative unsigned iOS Debug archive | [Debug artifact, run 42688767829](https://github.com/kira-manga/test/actions/runs/42688767829/artifacts/13706303743) | Device-only Debug archive, source `e8cedcf5`; no simulator/test rerun in this job; also not installable until signed |

</details>

The prior cross-API Android, desktop and iOS refresh shares source `631df201`.
The newer Android lifecycle closure-accounting build is `a476d536`; each row above names
its own exact source and evidence.
The explicitly labelled historical alternatives have different revisions. The 464-method Kotlin simulator regression also executed at this source. Earlier
`0ea09405` packages and iPad observations remain in the dated refresh evidence. See [latest Android startup verification](../../qa/pos/traceability/android-bounded-startup-20261110/README.md) and [exact verification](../../qa/pos/traceability/epic3-native-qualification-20261109/README.md)
for the boundaries and preserved failed runs. The [10 November refresh](../../qa/pos/traceability/native-build-refresh-20261110/README.md) records the newer desktop/iOS source, packages and actual checks.

The collected `355ea18a` application passed 1,835 existing JVM methods, two
namespace skips, Android/both iOS inventory compilation and signed pair/fatal lint.
[Android42954657696](https://github.com/kira-manga/test/actions/runs/42954657696)
failed after recorded capture/application, with finalization and native close/
cold reopens unobserved. [Apple42954664502](https://github.com/kira-manga/test/actions/runs/42954664502)
passed562 stock simulator methods. One diagnostic sample at pending finalization
uses this same APK pair; it is not a newer application or a passing workflow.
[Exact collected outcomes](../../qa/pos/traceability/stock-closed-ancestry-20261111/README.md).

## Verify package SHA-256

On macOS/Linux use `shasum -a 256 <package>`. On Windows use
`Get-FileHash -Algorithm SHA256 <package>` in PowerShell.

| Package | SHA-256 |
| --- | --- |
| Closed-ancestor inspection Android APK (`2f960d6b`) | `f41301888c7717a23221dc31ed05540566db6cdf78e9f0a499d9cbe572847946` |
| Preceding financial-node stock Android APK (`6593e72f`) | `6f9401ac562f99765b24de7597df05e0edacbd5c94e6a40aa387dde98afd1b8b` |
| Prior native-payment Android APK (`99e8ba9e`) | `9aa9dcde1b1971a62974b520879e87237be726e0f92db20d3b1ca256457c9ae9` |
| Prior closed-ancestry Android APK (`355ea18a`) | `84a8d07fefe98ccf308c9018ad6e61451ee787803dd0a1363c560abd0910c58f` |
| Prior retained-inspection Android APK (`ec18aa3d`) | `f95f7def4060c48528d4ab485f2db925738aa0fb1dc279d5684f494dc150d6df` |
| Prior canonical-ASCII Android APK (`1bc40eba`) | `b82fb982105703d2910073070ff664e5a00d12fd3b52021b5f4b9f8e312fba8b` |
| Prior reservation-producer proof Android APK (`a76724d4`) | `6336564932d24ef5ee48202a8ec4d7e9929d2c711577a310678d61c4ba894eb4` |
| Prior grouped-source Android APK (`dbd89440`) | `5a10cd2e8baf48843f5c7009a5d557992f1e05013705219fcc3389613d6b5943` |
| Prior instruction-proof Android APK (`9087e117`) | `a5021d71abb8f6739b734846917e4ba8020a3e4e178e66478e4f444c95a581e5` |
| Prior selected-write batch Android APK (`088ba505`) | `2219b41bc189bb6bb11ab2b9fec562e8244ca9a4c5edb782c32a2d50a06e87ca` |
| Prior physical Start diagnostic Android APK (`bc1407ae`) | `7a750a0489485e88f669a8fe7b5fa92d1d9e24782ca91fab914743d99abc5d86` |
| Prior stock direct-source Android APK (`0833f397`) | `31f04129cf856ee261c9ef059d60d438c74c995d04124086f5865824f8a3f360` |
| Current macOS ARM64 DMG (`b28638df`) | `d6afdb08684905211cbf6f40b8490a57ca17ec6516df3a2d3847afcad732cb92` |
| Current macOS Intel DMG (`b28638df`) | `34ec21f2f83ba1053381a1002d80cd7e6301fbbb735527f306e6e09cc29bea89` |
| Current foreground/shutdown iOS Simulator ZIP (`8a7f7888`) | `81fe8cdc1d4c6e11e45f0fc63be608482134522aa1232b2e34ca699594d57788` |
| Current foreground/shutdown unsigned Debug ZIP (`8a7f7888`) | `b92ce3cf99a7b8612d0bb5fe1e8a1f506f395b4dfb40b63a60606410a04ee728` |
| Current Windows MSI (`b28638df`) | `813d73ddad0ca46e8d6b05d9969de693a3d82fcd4358190f0788cb320c738f88` |

<details>
<summary>Historical package checksums</summary>

| Package | SHA-256 |
| --- | --- |
| Prior shutdown cleanup iOS Simulator ZIP (`72af8eb1`) | `a810068ac07fca859597efee8ece7e025b763f2b396fec727c52e9bc7f009590` |
| Shutdown cleanup unsigned Release ZIP (`72af8eb1`) | `c53266ef39738086dc39ce05d5d3f56bef9fa406f89b60f7258f89837a5de8ad` |
| Previous collection/order source Android APK (`4b460ec8`) | `4dbf1e923338e30043ae4162bfcd7e5729004fe858dfad07bf66e0556f08ba02` |
| Previous payment stream Android APK (`b28638df`) | `9e57496b0514a10bc47449ce6b41caae0a91235543e5e8804a2a766af33e0350` |
| Previous stock composition Android APK (`9bf03ee1`) | `e5131da7ad3a7d5ff5caad9565254a5e3800a80dc60f814a3c6262a748de10f2` |
| Lifecycle closure-accounting Android APK (`a476d536`) | `07854fcdc6010bacffc7fe041774d141c73de74256f4386990f304a8bcc25f04` |
| Older Android startup APK (`afc7f369`) | `773aa6b0ee98475dfb8d32b579a1e2d3f352879f19f23cdd84142e40558891d7` |
| Historical Android STOP/close APK (`f01bbb41`) | `4bd95a718ecf21f580fe5ba5b8295a21e7247aeefd5e249e26a8edccfe470bb6` |
| Prior actual-owner Android APK (`4c045a36`) | `a4d4ebe3980cfc975f4002941d004f433d76186cc01ff45a7de7903ae8e392b6` |
| Prior Android native-intake APK (`c93b8671`) | `f0a98c3f00441f145199b0710534a01bcd95adb90ce7ce65c9dce6deff9e94f8` |
| Prior cross-API Android APK (`631df201`) | `29b84f45479fb0c61310c0664c8fb1847866b94a5d8f9be718195b3c5c554d70` |
| Historical Windows MSI (`631df201`) | `3aeae7a962e5c6b4adbba1522d4dc6d65c0d3987c200273da275716a565e7ab6` |
| macOS ARM64 DMG | `8529dac2d2c23d5ff1988e9bc1722867d786c90bde5de89856a7d368faf605a2` |
| macOS x64 DMG | `0ee1fd281d13cc6ac7c9d06a2893d86bf1efda17921a097e7731d10d8f9ff8d3` |
| iOS Simulator app ZIP | `04cd50b2514e759dc84e921d5ff0cbb31e3174b128512515469652dedf6c4752` |
| iOS unsigned Release archive ZIP | `6cac0075b3f34a28d177c88705ca0e62bc6038142cdf8c09e9637458e20207be` |
| iOS unsigned Debug archive ZIP | `51fae2442b336fcf40359845291f185b642994c1b462e70e5e776906811c0619` |

</details>

## Android

Latest Android application SHA-256: `f41301888c7717a23221dc31ed05540566db6cdf78e9f0a499d9cbe572847946` (34,251,438 bytes). The runtime-test APK is an executor for disposable automated runs and is not needed for owner installation.

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

**Latest selected-write batch / instruction diagnostic:** `088ba505` is retained
in artifact13814623650 above. Install only the application APK. The executor
and original unsplit shared 30,000-ms budget are unchanged. Recorded Start,
native instruction preparation and retained Start read returned; the process
ended with signal9 after entering domain authorization preparation. Zero methods
completed. No instruction adoption, financial outcome or final cleanup was
observed. Preserve this and all earlier unresolved originals; do not infer
rollback or create a replacement. [Exact pair and observed phases](../../qa/pos/traceability/payment-original-write-batch-20261110/android-native-results.json).

**Prior physical Start diagnostic:** `bc1407ae` remains available in the table above. Start returned
`RECORDED`, then the process ended with signal9 before instruction adoption,
financial outcome or final cleanup. Zero methods completed. Preserve this and
all earlier unknown originals. A recorded Start does not establish checkout or
safe recovery. [Exact pair and observed boundaries](../../qa/pos/traceability/physical-start-boundaries-20261110/android-native-results.json).

**Prior normalized collection-core build:** `80d30efa` fixes the legacy allocation
getter reached by normalized collection outcome verification. App SHA-256:
`c5a1ff04cd8c4543d323c7b1b5b7826b85fe2d0aeeedf96d7c42fac4ba7d2ab2`.
The exact signed app/executor pair is retained in artifact13809242529 above.
Install only `testing-inputs/ukkera-pos-testing.apk`. Android API35/x86_64/AOT
returned from native acquisition and the Start commit call, then Start returned
`Deferred(STORAGE_UNCERTAIN)`. One supporting method completed with one failure;
final cleanup could not confirm native closure. No full checkout pass is claimed.
Preserve the original saved request and unresolved evidence. Do not create a
replacement or infer rollback from the deferred reply. Existing executor and
original unsplit shared 30,000-ms budget remain unchanged.
[Exact source, pair and outcome](../../qa/pos/traceability/normalized-collection-core-20261110/android-native-results.json).

**Previous stock direct-source build:** `0833f397` returned stock preparation
and the complete handler preparation before the process ended with signal9.
No native acquisition, Start reply or confirmed cleanup was observed; zero
financial methods completed. This is diagnostic evidence, not successful checkout
or proof of timeout/rollback. Preserve the original saved request; do not create a
replacement. The original shared 30,000-ms budget and frozen executor remain
unchanged. The ordinary retained-payment mismatch source implementation still needs focused
independent qualification. Consult the exact source and evidence below for this
package’s implementation boundary.
[Exact source, pair and outcome](../../qa/pos/traceability/stock-direct-source-batch-20261110/android-native-results.json).

**Previous stock composition build:** `9bf03ee1` corrects the installed stock
authority instance and preserves valid zero-based signed domain revisions. The
unchanged native selector reached recorded opening stock and collection begin,
then terminated with signal9 before an original Start was observed. No complete
financial method or confirmed final cleanup was recorded. Use the build for
synthetic setup and observed workflow checks; preserve any unresolved original
and record the exact boundary. [Source, pair and limits](../../qa/pos/traceability/android-stock-domain-revision-20261110/README.md).

**Current Android lifecycle evidence:** `f01bbb41` passed one native STOP/close
supporting method on API35 x86_64 without AOT: authenticated committed STOP,
confirmed coordinator closure/cached retirement and durable STOP blocking
automatic reopen. This does not instrument physical native close counts or
qualify the full lifecycle. Background/reopen `42817587823` also passed one
supporting method. STOP/RESUME `42817587411` and AOT cold publication diagnostic
`42816992704` each started one method but completed none (OS signal 9); neither
is a pass. [Exact inputs, outcomes and limits](../../qa/pos/traceability/android-lifecycle-supporting-20261110/README.md).

**Prior cross-API verification limits:** the `631df201` application includes native
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
before ACK assertions; correction `c93b8671` passed the unchanged operational
segment without AOT in run `42800059765`, including two confirmed owner closes,
existing-only reopen and zero ACK delta. Full database regression and clean
signed packaging passed. A separate AOT cold-original attempt reached fresh
employee authentication, then ended with signal 9 before a genuine SKU output
or financial command was observed. No cold-original method completed.
[Native-intake result and limits](../../qa/pos/traceability/android-native-intake-registration-20261110/README.md).
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
the retained screenshot. The prior `631df201` ZIP passed [run 42793999046](https://github.com/kira-manga/test/actions/runs/42793999046), retaining original PID 3541 after five seconds. The current `8a7f7888` ZIP from packaging run42867498835/artifact13791088633 passed [the exact-package iPad check42876800066](https://github.com/kira-manga/test/actions/runs/42876800066): original PID5290 remained visible after five seconds on iPad Pro13-inch (M5), iOS26.5. [Source-bound package and startup evidence](../../qa/pos/traceability/ios-presentation-peer-cleanup-20261110/ipad-native-results.json). This proves bounded startup only; native identity, custody, setup and complete workflow remain unverified. Interactions, rotation, accessibility
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
   profile’s App ID prefix plus bundle ID. The current unsigned Debug archive
   at `8a7f7888` selects this entitlement file; the historical Debug archive
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
