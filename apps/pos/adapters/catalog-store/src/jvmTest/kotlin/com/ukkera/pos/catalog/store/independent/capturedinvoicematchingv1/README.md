# Captured invoice matching: supporting planner-unit checks

**Execution: seven JVM methods passed on 5 November 2026**, with no failures,
errors or skipped methods, on the matching source accompanying this record.
They are partial supporting checks, not a CCM case/profile PASS or an owner
acceptance result. The focused selection is
`:adapters:catalog-store:jvmTest --tests 'com.ukkera.pos.catalog.store.independent.capturedinvoicematchingv1.*'`.

The unchanged requirements oracles are:

- `qa/pos/designs/supplier-captured-claim-matching-v1/`: CCM **v1.0.1**, including
  `README.md`, `cases.md`, `fixtures.json` and `source-manifest.json`.
- `qa/pos/designs/supplier-captured-claim-matching-binding-v1/`: combined-action
  binding **v1.0.0**, including the same four filenames.

## Actual boundary exercised

`CapturedInvoiceMatchingPlannerSeams.kt` constructs explicitly synthetic,
immutable planner inputs. IDs, encoded row/producer metadata and source stamps
are inert test data, not actual provisioned records or verified original/receipt
producers. The inputs retain the frozen quantities, historical unit meanings,
prices and zero purchase-tax/discount basis used by the selected assertions.
They do not instantiate the complete frozen runtime fixtures or PDF originals.

The methods call the production `purchasingCapturedMatchSliceV2` and
`purchasingCapturedMatchEffectsV2` functions. The effects callback records their
actual typed proposed bodies and wraps them in synthetic row carriers; it never
accepts or persists a command. A second arrival partial uses the first proposal's
actual progress, payable and billing bodies as its next inert input.

## Method-to-oracle map

All amounts below are EGP minor integers.

| Method | Frozen assertions covered at this seam |
| --- | --- |
| `ccm01FullHistoricalCartonSliceRecognizes24000WithBillingOnlyProposal` | CCM-01/F-FULL and the binding's CCM-01/07 numeric portion: original2 cartons at historical12 each, base24, new allocation24/24000, remaining0. Quote and request slices agree. No current/future conversion publication is exercised. |
| `ccm02NoReceiptCannotProducePositiveMatchOrPayableProposal` | CCM-02/F-ARRIVAL stageA: raw claim24/24000 survives; a positive slice without a selected receipt refuses before any effects callback. |
| `ccm02TwoTwelveUnitSlicesKeepEach12000ResultAndOne24000Payable` | CCM-02 stagesB/C and incremental binding: two distinct selected receipt/source identities each allocate12/12000; payable progresses12000 then24000 on one invoice; the first result bytes/value remain12/12000. This is not durable recovery or duplicate-operation testing. |
| `ccm03Accepted22Recognizes22000WithoutRewritingRaw24Claim` | CCM-03/F-QUALITY's selected22-unit numeric slice: raw2 historical cartons/24 each/24000, new allocation22/22000, remaining2/2000. The seam uses an equivalent historical-carton PO24 and a selected BASE released receipt line22. It does not instantiate the other disposition sources or the excess-hold PO22 variant. |
| `ccm06Prior18LeavesExactly6For6000AndCannotBillRemaining4Again` | CCM-06/F-CAPACITY.claim10 and binding: inert prior18/18000 leaves6; request10 refuses, request6 yields6000 with new remainder4/4000, receipt billing reaches24/24000, and another4 refuses. Prior billing bodies remain18/18000. Prior-producer qualification and the competing6-unit race are not exercised. |
| `ccm04CompliantARecognizes10000ButB11000ClaimIsNotApprovedAt10000` | CCM-04/F-PRICE exact-only binding: compliantA10/10000 proposes recognition; B's5-unit claim11000 versus agreement10000 refuses, leaving B11000 unrecognized and raw total21000. No lesser-price substitution, future legal contested split or variance/settlement policy is selected. |
| `ccm08MonetaryRequirementCarriesFullRawClaimNotSelectedIncrement` | CCM-08/binding requirement construction only: the offered combined PERFORM requirement binds the invoice/revision and carries full raw24000,10000 or21000 rather than a selected12000,22000,6000 or10000 increment. No authority evaluator is invoked. |

Successful proposals assert the actual success code, slice/event/result amounts,
exactly six purchasing write keys, expected purchasing-only acquisition claims,
selected source snapshots, no jobs and no deadline. Refusal checks also inspect
the current slice contract's specific refusal code, preventing an unrelated guard
from satisfying the intended check.

## Explicitly uncovered required assertions

- **Ceiling enforcement:** finite23999 versus24000, ceilings based only on partial
  selected values, explicit company-head no-ceiling and missing/unresolved ceiling
  are not enforced by these tests. Neither grants nor accepting authorization
  snapshots are fabricated here. Current sessions, grants, revocation and all
  other configured authorization constraints remain untested.
- **Real source qualification:** completed primary original bytes/hash/length,
  exact supplier/reference/date association, current source heads, catalogue
  publication/conversion changes, receiving/inspection producers and historical
  producer validation are not proved by pre-hydrated synthetic inputs.
- **Physical and durable non-effects:** exact purchasing-only proposal keys exclude
  source, stock/value/cash/credit/settlement writes from this proposal. They do not
  independently observe balances or prove durable zero deltas, atomic commit,
  audit/journal/outbox, local duplicate guards, retry or restart/restore behavior.
- **Required owner fixture:** no current-schema JVM owner fixture is supplied.
  Legitimate production Master/receiving/claim producers, production SQLite/Room,
  independent fresh-connection reads, protected one-device access and admitted
  Client–Master RPC still require the frozen non-device L2/L3/L5 verification.
  CCM-11 application interaction and native/owner profiles are also not covered.

The frozen expected results are unchanged. Missing coverage is not waived, and
central implementation/verification is neither performed nor simulated here.
