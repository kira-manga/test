# Captured matching display: SCD supporting harness

**Execution: all 11 JVM methods passed on 5 November 2026**, with no failures,
errors or skips. These supporting methods assert the
unchanged **SCD v1.0.0** expectations from
`qa/pos/designs/supplier-captured-match-display-v1/`. They do not change the prior
23 CCM gateway methods, original CCM freezes or combined-action binding.

`SupplierCapturedMatchDisplayGatewayFixture.kt` supplies synthetic readonly wire
replies to the real shared gateway, codecs, projection validators and restricted
`INVOICE_MATCHING` profile. Positive revision values and catalog/session carriers
are controlled, not observed authoritative heads. The reused journal is
memory-only; no accepting matching command or source quote is supplied.

| Method in `SupplierCapturedMatchDisplaySupportingTest` | Frozen contribution |
| --- | --- |
| `scd01AllGoodsStayMatchedWithPositiveUnresolvedCharge` | SCD-01: goods 5/5/0; raw 620, recognized 570, unmatched 50; freight retained; matched status and false eligibility. |
| `scd02PresentZeroChargeSurvivesAuthorizedCostProjection` | SCD-02: goods 5/5/0; raw/recognized 570, unmatched 0; zero-value handling charge retained and false eligibility. |
| `scd03ZeroPricedRemainderStaysPartialAndVisible` | SCD-03: priced 5/5/0 and free 3/1/2; observed same-unit totals 8/6/2; raw/recognized 570, unmatched 0; partial status and false eligibility. |
| `scd01I1ToI3ContradictoryGoodsStatusMoneyAndEligibilityAreUnavailable` | SCD-01-I1/I2/I3: independent wrong status, erased unmatched claim and true eligibility. |
| `scd02I1MoneyEqualityCannotMakeAnUnresolvedZeroChargeEligible` | SCD-02-I1: true eligibility despite a present unresolved zero charge. |
| `scd03I1AndI3ZeroUnmatchedMoneyCannotErasePartialGoodsOrGrantEligibility` | SCD-03-I1/I3: independent full status and true eligibility despite unmatched free goods. |
| `scd04CostRedactionPreservesEachGoodsIdentityQuantityAndStatus` | SCD-04: authorized amounts/charges versus pre-encoding omission; exact operational records remain equal for all three fixtures. |
| `scd04I1RawClaimLeakIsUnavailableForAllFixtures` | SCD-04-I1: exact raw-claim monetary leak, independently for F1/F2/F3. |
| `scd04I2PositiveAndZeroChargeLeaksAreUnavailable` | SCD-04-I2: independent positive/zero charge leaks for F1/F2. |
| `scd04I3RedactionCannotRelabelGoodsCompletion` | SCD-04-I3: independent redacted-status contradiction for F1/F2/F3. |
| `scd04MatchingProfileCarriesRedactedInvoiceAndControlledQuoteDenial` | SCD-04 supporting profile boundary: typed redacted invoice read, exact source query and propagation of a scripted authorization-denied reply; not enforcement of grants. |

All amounts above are EGP. Priced goods retain net 500/tax 70; all credits, payments
and reserved amounts are zero and remaining due is 570. The free line's amounts
are exactly zero. Redacted financial/charge data are absent before encoding; the
existing nullable wire fields must contain null, never monetary objects, charge
collections or zero substitutes. Both decoded payload and mapped records are
checked. No hidden-widget redaction or new positive settlement policy is used.

## Unbound at this layer

- **SCD-01-I4:** the recognition DTO has a projected matched quantity but no
  separately trusted allocation source quantity. Changing that projected quantity
  would not reproduce the frozen source 4/projection 5 contradiction.
- **SCD-02-I2:** raw capture and authorized projection share one charge collection.
  Removing a zero charge preserves monetary arithmetic; this gateway has no
  independent original-source collection for the required comparison. The valid
  F2 assertion checks normal mapping retention, not rejection of this variant.
- **SCD-03-I2:** unmatched quantity is derived by the production mapper as raw
  minus matched. The valid F3 assertion observes 2; there is no independently
  injectable projected unmatched 0 field while retaining raw 3/matched 1.
- **SCD-05:** actual Master services, qualified original/PO/valued receipt sources,
  observed heads, full-claim ceilings, SQLite/history owners, independent reads,
  atomic once-only records, reply loss/restart and protected local/admitted RPC
  remain required and **NOT RUN**. Independent source comparisons for the three
  variants above must be bound there without changing their frozen semantics.

The current Master three-grant source gate, cost revocation with a stale Client,
clearing a previously visible sensitive view, original PNG/metadata preservation
and independent stock/valuation/cash non-effects are not established by controlled
callbacks. Native layouts/lifecycle/platform roles and owner acceptance remain
separate **NOT RUN** lanes. No complete SCD case or production readiness is claimed.
