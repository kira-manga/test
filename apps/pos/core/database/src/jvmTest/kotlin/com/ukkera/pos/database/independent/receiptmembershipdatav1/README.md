# Receipt membership DATA supporting checks

Frozen expectations: `qa/pos/designs/receipt-membership-data-v1/cases.md`.
The seven methods partially bind RMD-D01–D04 to the production DATA tree with
controlled blocks. No native facade, session, seed or authority is constructed.

## Fixed input binding

Canonical UUID families use `N0000000-0000-4000-8000-OOOOOOOOOOOO`, where N is
the family below and O is the lowercase hexadecimal ordinal padded to 12 digits:

| Frozen label | Family / ordinal |
|---|---|
| S, Z | 1 / 1, 2 |
| E1–E65 | 2 / 1–65 |
| Uj | 3 / j |
| Qj | 4 / j |
| O | 5 / 1 |
| A | 6 / 1 |

Comparison payloads retain the specified identities, quantities, receipt zone,
observation and instants. Their SHA-256 values are inert locator fingerprints,
not canonical production EVENT images or retained command proofs. The full
expected locator set is never supplied to the tree as completeness authority.
Both target input orders and growth values 0, 65 and 1,000 remain unchanged.

Missing, changed, truncated and substituted block responses exercise failure of
the tree's actual reads. They do not qualify native file corruption, authenticated
source discovery, wholesale history deletion or trusted absence. Omitted initial
input with a contradictory head, changed original timestamps, financial effects,
current authorization, settlement, restart and native ownership need their real
integration bindings and remain **unverified**, not simulated passes.
Every fault assertion also requires the fixture to have served that exact fault;
failure while preparing substituted bytes cannot count as production rejection.

The 64-event test binds locator capacity and nonreplacement. It does not validate
the original sold quantity 65 versus entitlement 64, head/cumulative state or
actual command refusal. The test tree is DATA-only; only production custody and
the complete original scan can establish a usable native membership.

Execution results belong in the source-specific traceability record; this input
binding and the independent frozen expected outcomes must remain unchanged.
