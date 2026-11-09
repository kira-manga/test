# Safe approval replacement — client journal/gateway slices

Ten methods wire bounded client assertions from the frozen
[ASR v1 packet](../../../../../../../../../../../../../../qa/pos/designs/approval-safe-replacement/README.md)
and its unchanged
[F1 dependency](../../../../../../../../../../../../../../qa/pos/designs/arrival-approval-workflow/README.md).
Bounded method outcomes are recorded in the
[verification checkpoint](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/approval-safe-replacement-20261101/README.md).
All 16 complete ASR cases and their 123 finite variants remain NOT_RUN; whole-case
native/production integration remains UNBOUND.

## Pinned expectations

| Packet/file | SHA-256 |
| --- | --- |
| ASR README | `c6a9ce9505f3450fb69ea37d25167b1c07a4156b9f50da9228cef3fe5e40c31f` |
| ASR cases | `3876decea5dec88fdb39525227fcdf009c156af91165ef0681c1974ba739a0e3` |
| ASR fixtures | `eb77a95066f8d78e357393d177b7770c36b13a4438451ff2c7e91209f668bf8e` |
| ASR FREEZE | `475f2f947f50a140b6c902f505e0b41b61cf68d5a4310c30807da54da7d2972e` |
| F1 README | `fc514415a97f0b4b6fc7186cb7054ff5b607b1ae0b506790f8cef2c6c3ff0d1f` |
| F1 cases | `4d069aacc3965cb10932b215855479e816118dc81aa71c148ba110836933ebf1` |
| F1 fixtures | `945f03bff1472c0d49390dd1d97dbb5f0a3c43b13b085f098249fef45cddf0f0` |
| F1 FREEZE | `74a49495b41b17f4f5135ed27e37e7acfa4c9f1cc045ff32ebe9d482ef9bc129` |

Freeze ID: `approval-safe-replacement-v1`, frozen at `2026-11-01T21:44:45Z`.
No frozen files, expected outcomes or original F1 fixtures are changed.

## Bounded mapping

| Method prefix | Frozen assertion/variant projection | Binding limit |
| --- | --- | --- |
| `asr12EncryptedReopen` | ASR-12 INITIAL_ISSUANCE_UNKNOWN, AP0_ISSUED, RETIREMENT_UNKNOWN, RETIRED_BEFORE_BEGIN, REVIEW_OPEN, I1_UNKNOWN, AP1_ISSUED, BUSINESS_UNKNOWN, BUSINESS_COMMITTED; original/every receipt retained | All 12 action/route command-data targets; actual encrypted close/openExisting, not process kill or Master restart |
| `asr03And07Retirement` | ASR-03 PERMISSION_DENIED/NOT_RECORDED/MASTER_UNAVAILABLE; ASR-07 UNPROVED_ABSENCE and unknown portion of RETIRED_REPLY_LOST; original preserved, no retransmission/new review/ID | Actual durable pre-handoff and gateway serialization; public negative responses only; successful retirement/recovery unbound |
| `asr05And08Unknown` | ASR-05 ABSENCE_WITH_LATE_HANDLER and ASR-08 UNPROVED_ABSENCE; one saved current issuance ID, retained old archive | Negative issuance handoff plus actual encrypted reopen; real issuance/authentication not supplied |
| `asr11PreviewAndIssue` | ASR-11 latest-selection assertion: old AP0 cannot substitute for latest AP1 | Denied preview/unknown issuance observe forwarding of last archived ID over two client data archives; no Master qualification of that ID |
| `asr04CurrentRequester` | ASR-04 OTHER_ACTOR, WRONG_COMPANY, WRONG_BRANCH, APPROVER_ONLY, PERFORM_ONLY, REQUEST_DENIED, WRONG_DEVICE; unavailable/denied current session data | Current-view/credential/hint refusal before handoff; REQUEST-only may hand off, but does not prove native retirement or authority |
| `asr16SixteenPrevious` | ASR-16 FIFTEEN_RETIRE_CURRENT, FIFTEEN_BEGIN, SIXTEEN_RETIRE_CURRENT, SIXTEEN_BEGIN_REFUSED, FULL_UNKNOWN_CURRENT; ASR-12 CAPACITY_CURRENT_RETIRED | Actual journal transitions/reopen and capacity refusal; chain/terminal retirement rows are client data, not genuine 17-approval history |
| `asr10And11And14Truncation` | ASR-10 immutable preview/original; ASR-11 current selection; ASR-14 CLIENT_CEREMONIES/LATEST_SELECTION | Reject proposed archive removal/rewrites and old-ID selection before writing; not actual corrupt-file/native-history recovery |
| `asr06And07And11And14Found` | ASR-06 original-result precedence; ASR-07 BUSINESS_PRECEDENCE; ASR-11 COMMITTED; ASR-14 BUSINESS_RESULT precedence over broken approval lookup | Synthetic Found/Recorded response data wins in actual submit/lookup/pending and is retained as client result data; no genuine business commit |
| `asr06DispatchedUnknown` | ASR-06 held business/PHYSICAL_DEPARTURE original projection | Three Dispatch routes; durable client dispatched checkpoint and frozen 3-unit command/observation retained; no actual departure/cut proof |
| `asr12FailedOrUnknown` | ASR-12 IDENTITY_SAVE_UNCERTAIN; no outbound mutation before durable identity/input | Controlled pre-write failure or unknown response after a real durable client write; no native IO-fault qualification |

Variant names above identify the assertion sources, **not complete variant runs**.
Retirement receipt/issuance receipt/archive-review write-uncertainty variants,
genuine positive recovery, own approver authentication, actual expiry/revocation,
current Master REQUEST/APPROVE checks, supported historical codecs and atomic
consume-versus-retire schedules remain UNBOUND. This binding does not invent a
retry-release transition after an unknown retirement: negative/nonterminal
observations remain held and may lack a liveness resolution.

## Data and persistence boundary

The existing synthetic JVM custody helper uses actual AES-GCM, private files,
atomic replacement and fsync. Its JCA key/custody observations are controlled
inputs, not platform secure-storage qualification. Reopen closes and opens the
same encrypted evidence; it does not kill a process or restart a native Master.

Original quantities, action/route coordinates and document manifests project the
unchanged F1 inputs. Client approval/retirement/clock metadata and public current
views are inert carriers. No restricted Master approval preview, issuance receipt
or retirement receipt is constructed. Found business data includes an explicitly
synthetic `MutationReply.Recorded` carrier; it is never inserted into Master or
business tables and does not demonstrate an authentic committed cut.

The tests assert unchanged original command bytes/identity and retained client
metadata. They do **not** prove native original document bytes/retention joins,
permanent fencing/conclusive non-commit, custody/money vectors, current admission,
genuine Room/history transactions, physical instructions, process crashes, RPC or
installed Windows/macOS/Android/iPhone/iPad roles. ASR-09 and the full ASR-15
eight-issue/four-retire/four-consume journeys remain UNBOUND. A passing bounded
slice must never be reported as a complete ASR/F1/original-scenario PASS.

## Focused selector

From the POS build:

```sh
./gradlew :adapters:app-gateway:jvmTest --tests 'com.ukkera.pos.app.gateway.independent.approvalsafereplacement.ApprovalSafeReplacementClientJournalSlicesTest'
```

The earlier `arrivalapprovalclient` suite is a separate regression selector; its
controlled endpoint only receives fail-closed retirement and optional-predecessor
API compatibility, with its original expectations unchanged.
