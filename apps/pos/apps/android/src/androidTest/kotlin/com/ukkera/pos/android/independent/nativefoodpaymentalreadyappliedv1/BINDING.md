# Native FOOD AlreadyApplied recovery binding

Owners: [#26](https://github.com/ukkera/kmp-apps/issues/26),
[#51](https://github.com/ukkera/kmp-apps/issues/51),
[#59](https://github.com/ukkera/kmp-apps/issues/59).
Deferred execution: [#63](https://github.com/ukkera/kmp-apps/issues/63).

**Runtime status: NOT RUN.** This binds selected FAA-002 paid-unsent and original
recovery assertions, not a complete FAA case or admitted remote-RPC qualification.
The unchanged `food-payment-already-applied-v1` assets retain frozen version 1.0.0.
Source review and Android native-harness compilation passed on 7 November 2026.
No matching APK pair was packaged or installed for this increment.

## Bound path

- Genuine native founding, branch installation, explicit acknowledgement and
  separately authenticated employees A/B; seven actual policy activations reach
  A's frozen policy revision 7. B has its own genuinely opened drawer.
- Actual signed catalog/activity/recipe publications, stock opening and protected
  FOOD/CASH commands establish the frozen bread/meat and EGP 300 baseline.
- Genuine native Start, instruction adoption and one cash capture/import precede
  financial accounting. No caller-created raw original or direct SQL seed is used.
- While that original is pending, durably Stage a separately pinned Apply R using
  the existing public financial workflow. Complete the first Apply A, then submit
  R once. Require the ordinary AlreadyApplied result, durable remembrance and ACK.
- Read the original money/ingredients, recover R without re-executing, deliberately
  create a separate later empty FOOD draft W, and require R before W. W is setup
  for the history-prefix regression, not a supposedly write-free observation.
- Confirm actual native close, reopen existing storage, authenticate afresh and
  recover only R's retained identity. Protected projections and a complete
  read-only native census compare original business state and no additional
  accounting, ingredient, kitchen or physical-recording effects.

The baseline is receive EGP 500/change 200, coverage 300, drawer A 1,300/B 700;
unsent bread on hand 10/reserved 2 and meat 1,500g/reserved 300g. It does not use the
older Café EGP 114 fixture. Census observations never grant production authority.

## Still unqualified

FAA-Q01–Q04 remain explicit gaps: complete public original projection,
independent ordinary-branch setup qualification, minimum/scoped-supervisor access,
and independent canonical-byte goldens. The pre-staged second Apply is observed
binding DATA, not an independent resolution of Q02. Native-generated UUIDs are
not forged into fixture labels; the literal-identity and cash-movement-ID mappings
remain unqualified. Issued-stock, authority-change, RPC/Receipt, loss/crash,
UI/peripheral and other-platform variants are not supplied by this method.

A passing method would establish only its executed assertions on the identified
source/device. It would not pass all FAA-001–012, FFRC or owner acceptance.

## Compile and run

Compile the existing standalone test module from `apps/pos`:

```sh
./gradlew :apps:android-runtime-tests:compileLocalTestingKotlin --console=plain
```

Use a matching signed application/test pair as described in
[`docs/pos/BUILDING.md`](../../../../../../../../../../../../../docs/pos/BUILDING.md).
Run only against a fresh disposable `com.ukkera.pos.local.testing` installation;
never reset real business data or replace an earlier uncertain fixture. Do not
launch the Activity/branch service concurrently with the isolated test owner.
Targets are Android API24 and API31+ with an explicitly supported key policy.
`OS_ISOLATED` below is an explicit policy selection, never a fallback from failed
hardware protection. Hardware/StrongBox variants need genuine corresponding keys.

```sh
adb -s "$TEST_DEVICE" shell am instrument -w -r \
  -e class com.ukkera.pos.android.independent.nativefoodpaymentalreadyappliedv1.NativeFoodAlreadyAppliedTest#faa002NativeOrdinaryAlreadyAppliedCashPaidUnsentAcrossOrderlyReopen \
  -e syntheticNativeFoodAlreadyAppliedFaaF02 true -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner
```

Record source/build, actual OS/key policy, assertion outcomes and any failure in
#63. Missing bindings or a discovered code defect also remain with #59 and the
implementation owner; they are not converted into deferred passing tests.
