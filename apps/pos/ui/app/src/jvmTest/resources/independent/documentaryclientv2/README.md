# Documentary intake v2 shared client slices

**Execution: NOT_RUN.** These 21 tests wire supporting shared draft/input and
controller-host slices, not complete DI2 cases or native/non-device production
acceptance. No compilation or application test result is claimed here.

## Frozen inputs and bindings

`fixtures-v2.json` is a byte-identical copy of
`qa/pos/freezes/purchasing-documentary-intake-v2/fixtures-v2.json`. Expectations
remain in that freeze's `cases-v2.md` and `proposed-contract-v2.md`, with the public
`qa/pos/contracts/purchasing-documentary-intake.md` contract. The source freeze and
its earlier versions are unchanged. Copy equality was checked with `cmp`; that is
fixture-copy verification, not application execution.

`FrozenDocumentaryClientV2Inputs` expands only the published base64 or
base-plus-padding byte recipes and fixed operation rule (refreshed rejected
completion step 901). Its byte expansion checks the frozen length and SHA-256
using the JDK, not application digest code. Frozen `B(d,f)` requires the file's
actual media suffix: the metadata filename stem is paired with `.pdf`, `.png` or
`.jpg`. Explicit filename-boundary/control fixtures remain exact. Reference,
date, purpose, correction reason and other supplied text are not normalized.
Expected capture values are assembled from the fixture separately from the draft
validator under test.

Presentation-only company, branch, STAFF-A, STAFF-X and supplier IDs are bound,
respectively, to `20000000-0000-4000-8000-000000000001` through
`20000000-0000-4000-8000-000000000005`; presentation revisions are `S=1`, `R=1`.
These are not genuine identity/session/device/supplier/grant provisioning.

The host fixture uses the actual shared runtime, entry ViewModel holder and
controller with scripted file-selection/session/gateway inputs. Delayed callbacks
can be delivered after the old presentation owner ends. Scripted Saved/Captured
results are inputs to the host, never evidence of private durability or branch
capture. Assertions that no unrelated mutation calls occur do not substitute for
fresh authoritative `Z` ledger observations.

## Supporting slice map

Every row is one test method; all rows are **NOT_RUN**. Frozen references identify
the supported portion only, not case closure.

### `DocumentaryClientV2FrozenDraftSlicesTest` — 9 tests

| Frozen reference | Shared input assertion |
| --- | --- |
| DI2-001 | Exact original metadata/reference/date passes through the draft. |
| DI2-002 | All seven purposes and declared PDF/PNG/JPEG media remain exact inputs. |
| DI2-011, DI2-015 | Child and correction retain distinct frozen parent/source links and reason. |
| DI2-010 | Mutually present parent/correction links cannot form a public capture. |
| DI2-013 | Missing child attachment revision is not silently defaulted. |
| DI2-016 | Frozen leading-space/case/reference and supplied/absent-date variants remain exact; duplicate grouping is not exercised. |
| DI2-027 | ASCII reference/filename 192/193 and correction-reason 256/257 boundaries. |
| DI2-027 | Frozen control text is rejected, not cleaned or truncated. |
| DI2-004 | Primary/child maximum and over-limit lengths, plus empty primary input. |

### `DocumentaryClientV2FrozenControllerSlicesTest` — 12 tests

| Frozen reference | Shared host assertion |
| --- | --- |
| DI2-014, DI2-021, DI2-N04 | Pre-submit child Back can be cancelled or confirmed without save/transfer. |
| DI2-021, DI2-N02 | Scripted chooser cancellation/denial/access change cannot become a URI-only save. |
| DI2-021, DI2-024 | Private-save uncertainty retains exact input for explicit retry, freezes edits/import and does not auto-transfer. |
| DI2-015 | Correction save forwards exact new identity/source/reason/bytes without dispatching transfer. |
| DI2-027 | Entered frozen control text remains visible as invalid input and is not saved. |
| DI2-013 | Wrong `R+1` input observation and refused completion identity remain unchanged; reviewed lower `R` is a separate explicit retry request, repeated exactly after uncertainty. Only a later explicit Transfer presents the scripted step-901 outcome. |
| DI2-013, DI2-021 | Unknown transfer cannot open a semantic completion replacement. |
| DI2-021, DI2-N04 | Nonactive shared session lifecycle clears private draft and chosen source. |
| DI2-021, DI2-N04 | Late A import callback cannot populate or adopt A input in X presentation. |
| DI2-021, DI2-024, DI2-N04 | Unknown save blocks Back; its late reply cannot populate X presentation. |
| DI2-021, DI2-024, DI2-N04 | Late A transfer outcome cannot relabel its identity or populate X presentation. |
| DI2-021, DI2-N04 | Attachment replacement drops private input and requires fresh attachment review. |

## Remaining verification

- Native gestures/dialogs/camera, RTL/focus/rendered frames, actual protected
  output/cache, OS lifecycle and supported backup/history are not exercised.
- Production authentication/current grants, durable Master/client journals, actual
  saved step bodies/native origin/RPC, refusal precedence and fresh authoritative
  `Z` ledgers remain outside these slices. In-memory source destruction observes
  shared input ownership, not native storage/cache disposal or durable retention.
- Raw codec malformed hex/digest/enum/ID/count/selector and oversized-input checks
  are not these UI slices. The frozen date corpus here supplies valid and absent
  dates; no new malformed-calendar corpus or oracle was invented.
- Full transfer, download/reconstruction, paging, custody persistence and completed
  original facts require their own appropriate production-boundary evidence.
  Central implementation and verification remain held.

Unrun selectors, relative to the POS build:

```text
:ui:app:jvmTest
--tests com.ukkera.pos.app.independent.documentaryclientv2.DocumentaryClientV2FrozenDraftSlicesTest
--tests com.ukkera.pos.app.independent.documentaryclientv2.DocumentaryClientV2FrozenControllerSlicesTest
```
