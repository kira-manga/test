# Opening supplier obligations

Owning work: [#18](https://github.com/ukkera/kmp-apps/issues/18),
[#51](https://github.com/ukkera/kmp-apps/issues/51). This is separate migrated
supplier debt, as required by the original Blueprint §4.8 and company policies
§7.5. It must never fabricate purchases, goods receipts, sales, stock or cash.

## Shared application contract

- Command: `purchasing.opening-obligation`, version 1; read:
  `purchasing.opening-obligations`, version 1. The common commerce registry
  installs command, read and original-history producer together.
- Request: obligation/supplier IDs, observed supplier storage revision, evidence
  ID, original source reference, mandatory original source identity/text,
  documentary opening date, positive integer-minor EGP amount, explicit payment
  terms and reason. Explicit empty terms mean the source specifies no terms.
- The Master uses current amount-bound `PURCHASING_APPROVE/PERFORM` authority
  against the new obligation at revision zero. No inline action approval is
  accepted. Device admission, company administration and Client hints are not
  employee authority.
- One immutable original stores the exact document and original supplier
  configuration, actor, operation, time and policy. Evidence metadata derives
  byte size/hash, scope/ownership, capture, purpose, retention and access from
  that original. Matching retries retain their original operation; duplicate
  obligation/evidence/exact branch-local source identity/normalized supplier
  reference guards are transactional.
- Reads require current `PURCHASING_READ/PERFORM` **and**
  `INVENTORY_COST_READ/PERFORM`. Direct-ID, optional supplier filter and bounded
  branch pagination expose immutable originals, not a made-up invoice payable.
  A filtered cursor is the last scanned key, not necessarily the last displayed
  document. Complete oversized pages report capacity rather than truncating.
- Shared Purchasing → Opening obligations connects entry, durable intent
  staging, separate submission, original recovery/acknowledgement and authorized
  document readback. No-cost mode does not read debt or infer a zero balance.
  The UI requests one complete documentary original per page and preserves
  ordered text parts without altering source bytes or Unicode.

## Deliberate limits and remaining work

This intake supports exact valid UTF-8 `text/plain` originals of 1–4,096 bytes,
not PDF/image upload or the general attachment workflow. Capture status is not a
claim that asynchronous upload completed. Operator-supplied text capture preserves
its exact content; it does not verify supplier signatures or the truth of claims. Alias-aware/company-wide disconnected
source conflicts remain separate work. Zero/negative opening credits are not
implemented by this positive-debt operation.

Opening settlement/advance/credit allocations, payable/report aggregation,
resumable import/full opening setup, training/live transition and full coherent
backup/restore remain required. Invoice/advance payment must not substitute for
settling an opening document. Unsupported native histories remain held rather
than being accepted as authority.

[Frozen independent cases](../../qa/pos/freezes/opening-supplier-obligation/README.md)
and [actual results/limits](../../qa/pos/traceability/opening-supplier-obligation-20261030/README.md)
distinguish source/controlled checks from production native persistence, current
Master authorization, admitted RPC and whole acceptance. Development remains
incomplete; manual owner acceptance is separate and central remains held.
