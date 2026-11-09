# Safe approval replacement — controller slices

Nine methods wire bounded presentation assertions from the frozen
[ASR v1 packet](../../../../../../../../../../../../../qa/pos/designs/approval-safe-replacement/README.md)
and its unchanged
[F1 dependency](../../../../../../../../../../../../../qa/pos/designs/arrival-approval-workflow/README.md).
Bounded method outcomes are recorded in the
[verification checkpoint](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/approval-safe-replacement-20261101/README.md).
All complete ASR cases and finite variants remain NOT_RUN; native/production
integration and installed-platform equivalents remain UNBOUND.

## Pinned expectations

Freeze ID: `approval-safe-replacement-v1`, frozen at `2026-11-01T21:44:45Z`.

| File | SHA-256 |
| --- | --- |
| ASR README | `c6a9ce9505f3450fb69ea37d25167b1c07a4156b9f50da9228cef3fe5e40c31f` |
| ASR cases | `3876decea5dec88fdb39525227fcdf009c156af91165ef0681c1974ba739a0e3` |
| ASR fixtures | `eb77a95066f8d78e357393d177b7770c36b13a4438451ff2c7e91209f668bf8e` |
| ASR FREEZE | `475f2f947f50a140b6c902f505e0b41b61cf68d5a4310c30807da54da7d2972e` |
| F1 README | `fc514415a97f0b4b6fc7186cb7054ff5b607b1ae0b506790f8cef2c6c3ff0d1f` |
| F1 cases | `4d069aacc3965cb10932b215855479e816118dc81aa71c148ba110836933ebf1` |
| F1 fixtures | `945f03bff1472c0d49390dd1d97dbb5f0a3c43b13b085f098249fef45cddf0f0` |
| F1 FREEZE | `74a49495b41b17f4f5135ed27e37e7acfa4c9f1cc045ff32ebe9d482ef9bc129` |

No frozen input, case, outcome or retained F1 original is modified.

## Bounded mapping

| Method prefix | Frozen assertion projection | Binding limit |
| --- | --- | --- |
| `asr01And02And13Retired` | ASR-01/02 explicit-action separation; ASR-13 modal reopen; retired display does not automatically begin/issue/submit | All three Record route display samples, not genuine expiry/revocation/retirement |
| `asr01And08And13Explicit` | ASR-01 replacement awaiting separate submit; ASR-08/13 own-authentication and no-auto-submit assertion | Three Record routes: explicit begin, separate preview/confirmation/password handoff, then issued DTO display; no actual authentication/issue |
| `asr07UncertainRetirement` | ASR-07 unknown portion of RETIRED_REPLY_LOST/UNPROVED_ABSENCE; no repeat mutation/new review | Real lookup-required UI flag and recovery of supplied current DTOs; permanent-fence/non-commit proof not supplied |
| `asr05And08And11Unknown` | ASR-05/08 unknown issuance and ASR-11 ISSUANCE_UNKNOWN; latest ID/archive retained, lookup does not submit | Initial/current-replacement DTO observations; no genuine issuance recovery |
| `asr13CancelBefore` | ASR-13 CANCEL_BEFORE_RETIRE and CANCEL_REVIEW/AUTH_CANCEL | Controller close/reopen retains pending display and archives, not durable storage/process restart |
| `asr13Unconfirmed` | ASR-13 AUTH_CANCEL, AUTH_FAILURE/AUTH_DENIED display, TOUCH_KEY_SCANNER repeated-delivery assertion | Own-login/password input handoff and actual queued/borrowed-secret cleanup; synthetic refusal, no authentication or actual scanner/key/touch driver |
| `asr08And11And13Late` | ASR-08/11 out-of-order historical reply and ASR-13 STALE_CALLBACK | Noncancellable retire/begin/issue/lookup deliveries fenced after lifecycle loss, deactivate and attachment replacement; not device logout/process restart |
| `asr04And10And14Mismatched` | ASR-04 actor binding, ASR-10 OPERATION/REQUESTER/ACTION and ASR-14 incomplete binding | Bad public returned identity/action cannot redirect saved original or open credentials; not Master proof checking |
| `asr16CurrentRetirement` | ASR-16 SIXTEEN_RETIRE_CURRENT/SIXTEEN_BEGIN_REFUSED and modal reopen retention | Sixteen previous DTO rows plus separate current; controller displays gateway capacity refusal, not an independent authority/capacity gate |

These mappings name frozen assertion sources, not complete variant execution.
The 12 genuine action/route targets, actual English LTR/Arabic RTL rendering,
own distinct-A/NamedSelf authentication, R-token-for-A denial, current REQUEST/
APPROVE/native admission, genuine historical receipt origin, process kill/reopen,
native storage, all five one-device roles and 25 ordered Master/Client pairs,
RPC and physical peripheral inputs remain UNBOUND. Complete ASR-15 cuts,
custody/document/retention joins and financial invariants are not proved by DTOs.

## Presentation boundary

The actual shared runtime/controller, navigation tokens, attachment/lifecycle
checks, confirmation handling and secret-copy cleanup are invoked. Public review,
issued/retired/history and clock values are controlled **display data**. They grant
no authority, create no real approvals or business results, and do not implement
a fake durable Master/journal. No restricted native approval proof objects are
constructed. The controlled port never returns a successful business result.

The controller delegates an explicit capacity request to the gateway and retains
its refusal; these methods do not assert a UI-only security substitute. They
also do not resolve a native unknown outcome from absence or an old AP0 tombstone.
No bounded slice may promote a whole ASR/F1/original scenario to PASS.

## Focused selector

From the POS build:

```sh
./gradlew :ui:app:jvmTest --tests 'com.ukkera.pos.app.independent.approvalsafereplacement.ApprovalSafeReplacementControllerSlicesTest'
```

The earlier `arrivalapprovalclient` controller suite remains a separate unchanged
regression selector.
