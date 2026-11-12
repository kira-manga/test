# Android compatibility

The application declares Android API 24 as its minimum. Native compatibility
work is tracked in [#55](https://github.com/ukkera/kmp-apps/issues/55); development
verification and physical/owner acceptance remain separate. See the
[owner requirement](../../qa/pos/requirements/OWNER-ANDROID-COMPATIBILITY.md) and
[frozen compatibility cases](../../qa/pos/designs/android-api24-compatibility/README.md).

## Native security boundary

Shared `AndroidNativeKeyPolicy` selects guarantees; Android adapters acquire and
inspect actual AndroidKeyStore keys. The existing application identity, UID,
unlocked-user, nondebuggable, original-key possession, authoritative permission
and retained-history checks remain mandatory.

| Selected protection | Required observation |
| --- | --- |
| `OS_ISOLATED` | Genuine nonexportable AndroidKeyStore key; its OS-protected software implementation is explicitly permitted. |
| `SECURE_HARDWARE` | Actual secure-hardware evidence. A legacy hardware boolean may establish hardware without identifying TEE or StrongBox; software is refused. |
| `STRONGBOX` | Exact StrongBox evidence at creation and reopen. This profile requires API 31+ public `KeyInfo.securityLevel` and actual device support; no weaker fallback. |

API 24–30 hardware observations are recorded as **hardware kind unspecified**,
never invented TEE/StrongBox. Newer APIs are guarded: API 28 key-generation options
and package signer/version metadata, API 31 exact key-security/usage information,
API 26 notification channels and API 34 foreground service types. Existing enum
wire values remain unchanged; the generic hardware value is appended.

On API 24/25, PBKDF2-HMAC-SHA256 uses the official BouncyCastle UTF-8/SHA256
implementation through a private JCA factory, without constructing the full
provider or registering its unrelated algorithms. Parameters, credential formats,
key cleanup and the API26+ platform path are unchanged. No global provider is
installed or replaced; password strength and native deadlines are not reduced.

## Offline Ed25519 compatibility

The Android offline-installation verifier uses the already-declared official
Bouncy Castle RFC8032 primitive directly instead of requiring a platform JCA
Ed25519 registration. The exact 32-byte public key, 64-byte signature and
nonempty message of at most 65,536 bytes remain mandatory. Full public-key
validation rejects malformed and low-order keys; verification uses the exact
message, without a provider replacement or accepting fallback.

The six signed document roles and fresh native-proof callers retain their shared
framing and authoritative checks. This public verification adapter neither
acquires key custody nor fixes self-owned P-256 founding or its unresolved native
failure. See the [contract](../../qa/pos/contracts/ANDROID-OFFLINE-SIGNATURE-VERIFICATION.md).
Installed API24/API31+ and whole native workflows remain separate verification.
Source/harness review and focused Android compilation passed; **19 Android-host
methods and 14 compatible JVM methods passed**, with no failures/errors/skips.
The host test observes real JVM provider absence/restoration, not an installed
Android OS. [Results and limits](../../qa/pos/traceability/android-offline-signature-20261103/README.md).

## EC public-point compatibility

API24 providers need not expose `AlgorithmParameters("EC")`. The Android P-256
adapter uses the exact standard curve specification from its existing constants,
while retaining point bounds, curve membership, returned-key checks and unchanged
signature/wire formats. No key replacement or provider fallback is introduced.
Six independent host tests pass against the actual Android adapter; native runtime
qualification remains separately reported in the [execution record](../../qa/pos/traceability/android-api24-compatibility-20261031/README.md).

## Protected storage

`adapters/android-native-files` supplies the real native facilities missing from
the older public Android API: atomic close-on-exec/no-follow opens, actual access
flags and streaming directory enumeration. It uses public
`ParcelFileDescriptor` ownership and standard JNI/NDK, not hidden reflection.
Platform owners retain their existing ownership, identity, locking and bounded
namespace checks. An uncertain close consumes the close obligation and retains
the owner fence; it is never retried against a potentially reused handle.

API 26+ uses the public storage-volume UUID API. API 24/25 establishes the default
internal data volume through actual canonical ancestry and filesystem evidence,
preserving the documented default UUID. Private adopted-storage paths whose
stable volume identity cannot be established are explicitly refused on these
versions; selecting internal storage is the supported alternative.

Core-library desugaring uses the official Android dependency to support existing
`java.time` paths on API 24. Compatibility does not change database schemas,
original operation identities, encryption/AAD or the persisted spool profile ID.

## Verification boundaries

A compiled APK is not proof of enrollment, authentication, persistence or an
installed POS journey. The source-bound execution record must distinguish:

- Shared policy/contract unit assertions.
- Real native key and protected-file integration assertions.
- Native original setup, authentication, durable business services and reopen.
- Actual APK startup and process restart.
- Real admitted LAN RPC and complete business/failure-recovery journeys.
- Physical hardware, OS upgrades, peripherals and owner acceptance.

Only executed assertions may be marked passed. Software-emulator key observations
do not qualify physical hardware, actual StrongBox, in-place OS upgrades or full
product acceptance. Central work remains held.

## Password adapter check — 2 November 2026

Source review accepted the direct official SPI construction and API24-compatible
JCA constructors. The three production callers are unchanged. Android production
compilation and all **8 existing Android-host password regression methods passed**,
with zero failures, errors or skips:

```sh
cd apps/pos
./gradlew :core:security:testAndroidHostTest \
  --tests 'com.ukkera.pos.security.LocalPasswordHasherPortableRegressionTest' \
  :core:security:compileAndroidMain --console=plain
```

These tests exercise the Android password adapter with synthetic pepper inputs,
including the existing independent PBKDF2 known answer, exact Unicode/NUL/space
handling, wrong-secret rejection, bounds, unavailable protection and concurrency.
They do not qualify AndroidKeyStore, founding, actual OS versions or native
restart. No measured speedup or company-founding repair is established; the
previous unknown founding outcome remains preserved in #55/#63.

### Actual API24 runtime check

At `f04129f`, both normal signed `localTesting` APK builds passed, and both
packages passed APK signature verification with valid v2 signatures. An API24
software emulator completed boot; both packages installed in a separate Android
guest profile, preserving earlier uncertain application state. `MainActivity`
launched and resumed successfully. This establishes installation/basic startup,
not successful setup, authentication or business workflows.

The unchanged `NativeRetailBaselineTest#nativeBaselineMatchesFrozenRetailFixture`
failed by instrumentation-process crash. Native registry replacement returned,
but final authenticated persisted readback did not return before a self-directed
SIG9 exactly 30 logged seconds after founding entry. Native deadline exhaustion
is the leading explanation, not a captured exact exclusion reason. The durable
company outcome remains unknown; no downstream retail command passed. Preserve
the uncertain state rather than resetting it or rerunning the same founding
operation. Follow-up runtime/restart verification remains in
[#63](https://github.com/ukkera/kmp-apps/issues/63), and the unresolved founding
behavior remains development work in [#55](https://github.com/ukkera/kmp-apps/issues/55).

### Targeted founding readback diagnostics — 2 November 2026

The final authenticated company-retention readback now emits two fixed, best-effort
phase markers after filesystem/application continuity and after native key checks.
Only retention readback enables them. Source review accepted the change, and
`:core:security:compileAndroidMain` passed. Readback, key guarantees, password
parameters, formats, cleanup and deadlines are unchanged. No runtime test was
rerun: these markers have not been observed and do not establish a repaired
founding flow, an exact exclusion cause or the durable company outcome.

### Bootstrap continuity deduplication — 2 November 2026

The native key owner's two bootstrap helpers no longer perform a full registry
continuity scan immediately before a getter that performs the same fresh scan.
Both retain application observation and active-work checks before and after the
getter; no captured state is returned after a failed final check. The getters,
all other guards, alias/namespace checks, native key protection and possession
proofs, authenticated persisted readback, formats and deadlines are unchanged.
No cache or unchecked getter was introduced. Source review accepted the exact
two-helper diff; `:core:security:compileAndroidMain` and both `localTesting`
APK assembly tasks passed. Both APKs passed v2 verification with the existing
matching testing certificate; the application remains nondebuggable. No native
runtime result or measured speedup is established for this correction.

The packaging status text now accurately states the declared API24 minimum and
selected key-protection requirement, without claiming complete compatibility.
Python syntax validation passed; that text-only change did not execute packaging.
Earlier protected artifacts and their metadata remain unchanged.

### Bounded founding stages — 2 November 2026

The unchanged baseline at `3011e8d` again failed by instrumentation-process crash.
Both credential derivations, registry replacement and the two readback markers
returned; complete authenticated readback did not. Self-directed SIG9 occurred
approximately 30.017 seconds after founding entry. This supports, but does not
prove the exact trigger of, native deadline exhaustion. The durable outcome
remains unknown; no downstream retail pass. Earlier marker/build observations
above describe their own revisions, not a successful later baseline.

Founding now prepares disposable native-peppered credentials in a read/derive-only
operation, then ordinarily admits final signing/persistence/authenticated readback.
The original action, boot and 300-second expiry are preserved; each native
operation retains its existing 30-second watchdog, including nested-work rules.
One-use private ownership, independent execution copies and full final current
identity/key/issuance checks preserve cancellation and unknown-outcome rules.
No password-work-factor, key-policy, common contract, wire/storage-format or
schema change. The four-file source review accepted the corrected implementation.

Corrected Android security compilation and both signed APK assemblies passed;
v2 signer continuity, nondebuggable application and minSdk24 were checked. Final
reviewed host execution passed **16 methods (8 transfer +8 password regressions)**
with zero failures/errors/skips. Seven complete host cases pass; NCT-002 retains
an unobserved transient-copy assertion. At `3e44434`, API24 boot and both
installations passed, but the unchanged baseline crashed without a completed JUnit
result. Founding authenticated retention returned; the later installer-password
task sent itself SIG9 after 29.998 seconds. The exact exclusion trigger and full
setup/allocation/business outcomes remain unknown and preserved. See
[the source-specific record](../../qa/pos/traceability/android-founding-stages-20261102/README.md).
No founding repair, complete Android compatibility or owner-ready claim.
