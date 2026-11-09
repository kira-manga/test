# Supplier invoice gateway support

**Execution: all 16 JVM methods passed on 5 November 2026**, with no failures,
errors or skips. **Profile S only.** Eleven conforming methods consume the
unchanged supplier-invoice-discrepancy-capture-v1 specification 1.0 and its BASE +
CONFORMING fixture. Five additional referenced-goods methods consume the separate
supplier-invoice-referenced-discrepancy-v1 specification 1.0 and its context-identity
clarification 1.1. Frozen artifact digests are checked before reading expectations.

The shared gateway, production command codec and client-entry codec are exercised
over controlled session, read, operation-result and journal replies. A DURABLE
journal reply here tests application handling of that outcome; it does not prove
native or database durability. Constructor identities and revisions are controlled
DTO coordinates, not authoritative runtime bindings.

| Frozen requirement | Supporting boundary wired |
| --- | --- |
| SIDC-08 | Explicit conforming v1/v2 entry; original PO units/conversion, exact 24 EA and EGP 240.00, supplier references and returned input identities; retained v1 semantics under the current v2 form; unsupported form versions rejected safely. |
| Common original-result oracle; SIDC-05/06 recovery boundary | Client result presentation waits for retention; failed retention prevents presentation/ACK; an uncertain response and entry-codec round trip preserve the original request, actor, result IDs and returned revisions without redispatch. All these probes use CONFORMING, not those cases' discrepant/mixed fixtures. |
| Common original-actor oracle | A refused current session or different current actor cannot erase or adopt the original client entry. Controlled replies do not qualify Master authorization or revocation races. |

## Additive referenced-goods boundary

The actual current v2 form has two distinct inputs. `ORDER_LINE:<id>` selects an
authoritative line in the selected PO read and supplies its immutable item/unit/
conversion snapshot. `DOCUMENTARY_GOODS` instead accepts explicit actual terms;
its `ALLOCATION_ID` is a supplier documentary claim, not a proven authoritative
link, receipt/billing allocation or approved substitution. The supporting fixture
retains the supplied original-line ID bytes separately from actual terms. Matching
those bytes to a controlled PO read does not establish genuine entity/scope binding.
V1's command/read history does not durably distinguish the documentary-reference
kind from an authoritative selection; that remains a public-contract gap.

| Frozen requirement | Additional supporting boundary wired |
| --- | --- |
| SIRD-01 | Actual B / 4 TRAY / 5 EA per TRAY / EGP 57.35 remains 20 EA / EGP 229.40; actual A / 3 CARTON / 10 EA per CARTON / EGP 91.10 remains 30 EA / EGP 273.30. Original reference ID bytes, entered invoice/line IDs, submitted PO revision and literal supplier/item references are preserved without using the PO conversion. |
| SIRD-02(c), numeric shape only | Actual 5/0 conversion is rejected before client staging/dispatch, without falling back to the original 12/1 ratio. |
| SIRD-03, client boundary only | Lost controlled reply and entry-codec round trip recover the original request/result/actor without redispatch. Changed documentary ID, quantity 5 or conversion 6/1 returns Uncertain and leaves the original intact. A refused session/different actor cannot perform a lookup or adopt that client original. |

The changed-ID probe uses another documentary DTO value, not an independently
resolved valid REF-B. Gateway Uncertain is a nonreplacement guard, not proof of
authoritative semantic-conflict handling. SIRD-02(a)/(b)'s authoritative-link
refusal oracle is not applied to literal claims. Invalid authoritative targets,
foreign scope, actual-item/dimensional qualification, current activated revocation
and separately granted recovery-reader access remain unwired/unverified. Current
typed invoice capture uses PURCHASING_APPROVE; these controlled grants do not prove
separate capture-only authority. Original PO read DTOs preserve 2 CARTON / 12 EA
per CARTON / 24 EA; persisted original amounts and the UNCHANGED_CUT are not read.

No absent-allocation or no-PO successful capture is represented. SIDC-01/02 and
SIDC-03's dimension/precision and foreign-reference checks remain separate gaps;
the additive numeric-shape probe does not execute or satisfy SIDC-03. Arbitrary
documentary allocation-reference text, absent references and no-PO capture still
need an additive command/read/history path. No missing allocation, receipt, approval or payment
identity is fabricated, and no required case is silently ignored.

All required I-profile production integration and D-profile platform/lifecycle
variants remain unverified. There is no protected Master/Room/admitted-RPC
integration, independent persisted invoice/ledger read, genuine authentication,
business atomicity/duplicate/revocation race, backup restore or native reopen.
A client-entry codec round trip does not qualify a process restart or migration.
No whole frozen case or original purchasing scenario is claimed passing.

From the POS Gradle root, the focused command is:

    ./gradlew :adapters:app-gateway:jvmTest --tests 'com.ukkera.pos.app.gateway.independent.supplierinvoicediscrepancyv1.*' --no-daemon --max-workers=1

Execution outcomes belong in separate evidence; the frozen design stays unchanged.
