# Documentary client v2 — bounded wiring

The assertions come from the unchanged
`qa/pos/freezes/purchasing-documentary-intake-v2/cases-v2.md` and
`fixtures-v2.json`, bound to the public documentary contract v1. This is
source-aware harness wiring, not a new independent design or a case result.

The original 32 documentary methods passed at `18745e2`. The later additive six
PCR1 methods and three unchanged completion-retry regressions passed on
1 November 2026, zero failures/errors/skips. Every complete DI2/PCR1 case and
variant remains **NOT_RUN**. These results establish only the named bounded
assertion slices, not complete-case, native-platform or product acceptance.

Selector: `com.ukkera.pos.app.gateway.independent.documentaryclientv2.*`

## Assertion slices

| Test class | Methods | Frozen references and bounded assertions |
| --- | ---: | --- |
| `DocumentaryClientFrozenSourceSlicesTest` | 5 | DI2-001/002/003/004/006/007/008/027: exact byte recipes, fixed original step IDs, slot lengths/digests/hex, source-copy isolation, primary/supplement bounds and frozen ASCII/control/hex inputs. |
| `DocumentaryClientFrozenEncryptedCustodySlicesTest` | 8 | DI2-001/003/005/006/007/008/018/021; client-custody support for DI2-025: real encrypted source/intent retention and reopen, immutable bodies, original owner/scope/device, and fail-closed source/archive omissions without repair. |
| `DocumentaryClientFrozenTransferSlicesTest` | 9 | DI2-001/004/007/008/018/020/021; receiver support for DI2-025/026: sequential/concurrent continuation, each lost step reply, cancelled waits, account switch, recovery versus fresh work, retained media refusal, and rejected malformed step/completion/result envelopes. |
| `DocumentaryClientFrozenCompletionRetrySlicesTest` | 3 | DI2-008/013: deliberately submitted R+1 with actual reviewed R, exact retained stale refusal, new fixed completion step 901, encrypted append/reopen, gateway immediate-child save replay, separate dispatch, and refusal/prefix/collision gates. |
| `DocumentaryClientGeneratedRetryFrozenSlicesTest` | 6 | Separate PCR1-001–005 bounded client slices: generated durable child, before-append and lost-append-reply cuts, archive failure, encrypted reopen, explicit transfer/recovery, unknown predecessor and local grant/owner/scope hints. Not complete PCR1 cases or authoritative revocation. |
| `DocumentaryClientFrozenDownloadSlicesTest` | 7 | DI2-001/011/017/019/021/026: exact PDF-MULTI and PNG-A reconstruction, bounded export-slot replies, corrupt/short/reordered/duplicate/missing slots, false full digest, insufficient dedicated grants, chooser/lifetime/account/read-denial schedules, and controlled write/close failures without a saved-original claim. |

There are **38 test methods** in six test classes. The first 32 preserve the
DI2 bindings; the six additive methods use the separate PCR1 freeze. Iterated controlled specimens are not a count of passing frozen variants.
The source tests do not execute every media/purpose or refusal-precedence subrun.

## Inputs and controls

- The resource at `src/jvmTest/resources/independent/documentaryclientv2/fixtures-v2.json`
  is byte-identical to the frozen fixture. Its SHA-256 is
  `63e19ecb70a771a855200e1e6057438ee15cd8402459fe406ed84ffa8eecbc7d`.
  That copy comparison is a static input check, not application execution.
- `FrozenDocumentaryClientInputsV2` expands the frozen base64/padding recipes,
  uses independent JCA SHA-256 and retains fixed document/step IDs. Supplier,
  scope/owner/device, session, time and R=1 are explicit synthetic U bindings;
  they are not genuinely provisioned authority or native history. Default purpose
  remains `SUPPLIER_INVOICE`; the DI2-011 child explicitly uses
  `SUPPLIER_DELIVERY`. The frozen B(d,f) action requires the actual media suffix:
  PDF uses `.pdf`, PNG uses `.png`, and `.jpg` is the controlled JPEG suffix
  binding, not evidence for an independently bound native filename variant.
- `SyntheticDocumentaryEncryptedJournal` uses the production
  `JvmEncryptedClientIntentJournal`, actual private POSIX files, AES-GCM, atomic
  replacement and durable file operations. A fresh JCA key and current custody
  provider are controlled. Same-directory/key/identity reopen uses a new context;
  this is not process-kill, hardware-key, protected native-owner or rollback
  qualification. Corruption and cleanup affect only each newly allocated fixture.
- `ControlledDocumentaryClientPort` and `ControlledDocumentaryReadReplies` supply
  protocol envelopes, current-view hints, lookup/recovery controls and protected
  read/denial specimens. They are **not** a Master, grant installer, history owner,
  SQLite store, network or RPC fixture. Their result census is only a census of
  controlled replies. Synthetic history-shaped read fields do not qualify history.
- The download file destination is an explicitly controlled **in-memory output
  seam**, never a native chooser, saved file or genuine native close result.
  These slices validate the shared receiver's response to that seam only.

### Fixed completion retry boundary

The DI2-013 slice preserves the original refused body at R+1 and appends the
reviewed R request under the frozen step 901 through the encrypted journal. It
then exercises the gateway's **already-retained immediate-child save replay** and
separate transfer. It does **not** execute the fresh gateway
`latestCompleteOperationId == refusedCompleteOperationId` save branch, which
assigns a new identity rather than accepting the fixed fixture input. That branch
is tested separately by the additive PCR1 client slices below; actual
authoritative parent CAS behavior remains unverified here; no random
identity or production test hook substitutes for the fixed case.

The restriction is explicit in frozen `cases-v2.md`, Fixture actions and universal
assertions: all new document/step identities use the fixture rule. Its note after
DI2-027 applies that same rule to altered rejected CAS bodies, and
`operationIdentityRule.steps.newRefreshedRejectedComplete` is **901**. The existing
distinct-operation and preserved-refused-body obligations do not provide an
exception allowing a generated identity to replace that fixed input. Treating a
random assigned identity as a new passing v2 binding would weaken the input rule.

### Separate generated-completion retry binding

The independently frozen
`qa/pos/freezes/purchasing-documentary-client-retry-v1/{README.md,cases.md,fixtures.json}`
defines generated canonical identity as observable durable client output, not a
replacement for DI2 step 901. The six additive methods retain the refused R+1
body/result and save a distinct immediate child under reviewed actual R before
archive or explicit transfer. Successful IO uses the real encrypted journal and
same-key/identity reopen.

The fault wrapper records append/delegated-durable/acknowledgment calls. Tests
assert the named cut was reached: refusal before append writes nothing; reply
loss follows an actual durable append; failed predecessor archive follows that
append. These are controlled response cuts, **not** disk-full, process-kill or
physical-write-failure verification. Explicit transfer/lost reply recovers the
same generated identity without another dispatch. Unknown predecessor, absent
local grants and foreign owner/scope support PCR1-004/005 only; they do not prove
Master revocation, stale delayed callbacks, native history or complete recovery.

All complete PCR1 cases remain NOT_RUN. The six bounded methods passed in the
[approval/retry traceability record](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/approval-documentary-retry-20261101/README.md);
no generated result is relabeled DI2 fixed-901 acceptance.

## Unverified scope and implementation limits

These files do not establish any complete case's fresh Z stock/money/ledger
assertions, genuine Master result/audit/journal/authority origin, SQLite durability,
local authentication/current-policy serialization, protected same-device adapter,
real local P2pKit RPC, native source/output/cache/backup behavior, process fault
barriers or supported old-reader/checkpoint restoration. In particular the
DI2-025 omissions are supporting **client-custody** slices, not the full native
history/restoration case or reader qualification.

The client currently retains at most **four documentary sources** in one journal
and has a **16-attempt completion ceiling**, with no source-purge authority. Those
are implementation limitations, **not additional frozen passing oracles**. The
four-source limit is unrelated to the public four-completed-children limit. These
slices do not truncate the twelve-primary page campaign (DI2-026), seven-duplicate
campaign (DI2-016), child-capacity races (DI2-012), or any expected result to fit the
journal ceiling. Whole campaigns and unresolved dependent bindings remain unverified.

All applicable Windows/macOS/Android/iPhone/iPad native, emulator/simulator,
physical-device and owner variants require their own actual evidence. No central
implementation or verification is performed by this wiring.
