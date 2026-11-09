# Scalar foundations — bounded local implementation

This package implements part of **T04**, against BLUE §2 and TECH §7 in the
preserved [original requirements](../../../../docs/pos/original-handoff/) and the
[consolidated plan](../../../../docs/pos/plan-review/FINAL-PLAN.md). It is not full T04 acceptance,
legal/fiscal qualification, or a persistence implementation. Work stays local.

## Public contracts

All APIs are in `com.ukkera.pos.types` and available through `:core:types`.

| Type | Contract |
| --- | --- |
| `Money(currency, minor)` | EGP only, signed 64-bit minor units; checked addition/subtraction/negation. Minor-unit JSON is a canonical decimal string. |
| `ExactDecimal.parse(text, maxFractionDigits = 8)` | Exact bounded input; at most 38 coefficient digits and an explicitly chosen scale 0..18. Input may normalize trailing zeros/negative zero; `parseCanonical` and the serializer reject those aliases. No exponent/locale/float input. Domain wrappers enforce their narrower scales. |
| `ExactRational.ratio(numerator, denominator)` | Reduced arbitrary-precision intermediate with positive denominator, exact arithmetic/comparison/equality. Zero is `0/1`. Each **reduced** numerator/denominator is bounded to 1,024 decimal digits; exceeding it fails, never rounds. |
| `ExactRational.floorScaled(scale)` | Mathematical floor (including negative values), scale 0..18, checked signed 64-bit result. |
| `ExactRational.toScaledLongExact(scale)` | Scale 0..18; any fractional loss or signed 64-bit overflow fails. |
| `ExactRational.roundHalfUp(scale)` / `toMoney(currency)` | Explicit operational half-up posting; negative ties away from zero. This is not a legally approved fiscal rounding profile. |
| `Quantity(unitId, scaled, scale)` | Signed Long quantity; scale 0..6 is the unit/SKU precision snapshot. Wire: `{"unitId":"UUID","scaled":"1234","scale":3}` means exactly 1.234 units. |
| `Quantity.parse(unitId, text, scale)` | Rejects unsupported input precision and scaled Long overflow; no rounding. `UnsupportedPrecisionException` identifies precision errors. |
| Quantity operations | `+`/`-` require equal unit **and scale**, with overflow checks. `rescaleExact` is explicit. `compareTo` is exact across scales of the same unit; equality intentionally preserves the scale snapshot. |
| Quantity guards | `requirePositive()` is for command input; signed ledger deltas and zero balances remain representable. `requireWholeUnits()` is separately required for serial-tracked units. Owning commands must call the relevant guards. |
| `UnitPrice(currency, amount)` / `UnitCost(currency, amount)` | ExactDecimal amount, scale <=8; `parse(currency, text)` rejects excess input precision. Sign/eligibility belongs to the command, not an invented scalar policy. Aggregate postings still must fit Money. Repeating moving averages stay ExactRational rather than truncating each component to a UnitCost. |
| `PercentageRate(percent)` | Explicit nonnegative percent, scale <=8 and 38-digit coefficient bound; `parse("14").toFraction()` is 7/50. Supports >100%; each discount/tax/service feature must apply its explicit bound with `requireAtMost`. No statutory rate is selected. Wire `{"percent":"14"}`. |
| `UnitConversionRate.ratio(numerator, denominator)` | Positive Long numerator/denominator, reduced before storage; canonical `"12/5"` wire. The UOM owner binds source/target units; target Quantity precision must be checked exactly. |
| `DomainId` / `OperationId` | Existing canonical lowercase UUID strings retained. `random()` now generates CSPRNG-backed UUIDv4. No clock, counter, receipt number, or secret-token use. Existing canonical UUID versions remain parseable; new generated IDs are v4. |
| `Revision(value)` / `JournalSequence(value)` | Nonnegative Long, decimal-string scalar wire, checked `next()` and comparison. Ordering remains scoped by the owning branch/journal/authority contract; the scalar does not establish an epoch, writer fence or global order. |
| `UtcInstant(value)` | `kotlin.time.Instant`; serializer accepts its canonical UTC `Z` representation only, with 0/3/6/9 fractional digits. The generic scalar retains the full Instant range; narrower protocol bounds are explicit adapters. Not interchangeable with local date/time or sequence. |
| `IanaTimeZone(value)` | Must exist in the runtime IANA zone-ID set; fixed offset strings and unknown IDs are rejected. `AFRICA_CAIRO` is provided, never device-default time. |
| `BusinessDate(value)` | Operational LocalDate, canonical ISO string. Never infers the legal document date. |
| `BusinessCalendar(zone, cutoff, cutoffResolution)` | Cutoff and resolution are required, not assumed branch policy. `startOf(date)` / `businessDateAt(instant)` are pure; no wall-clock reads. |
| `Code` / `Barcode` | Preserve original string, normalized key and explicit normalization version. Only caller-selected `EXACT_V1` is implemented (`exact(text)`); no numeric conversion, trimming, Unicode/case folding or symbology guess. Both original/key are string-only, 1..256 UTF-16 code units. Inconsistent original/key/version fails. |

The canonical string serializers reject JSON numbers, null/array/object masquerading,
noncanonical numeric aliases, unsupported precision, and declared bounds. A generated
object serializer still reports its constructor's domain validation exception where
appropriate. This package does **not** claim to canonicalize arbitrary JSON envelopes,
unknown/duplicate object fields, or complete command semantic hashes.

## Exact allocation audit

Existing `allocateByLargestRemainder` uses portable BigInteger intermediates, including
`Long.MIN_VALUE`, and conserves the signed document amount. Weights are nonnegative
Longs, not eligibility limits/caps. Inputs are bounded to 10,000 recipients, unique
nonempty ASCII internal keys of at most 256 characters; ties are lexical ASCII, not
localized labels. Pricing/entitlement owners still enforce eligibility, caps and final
persisted-remainder consumption. Money currently has one admitted currency, so the
currency-mismatch guard cannot be exercised using a second supported currency.

## UUID source verification

Read-only upstream source verification used Kotlin **v2.4.21**:

- [`Uuid.kt`](https://github.com/JetBrains/kotlin/blob/v2.4.21/libraries/stdlib/src/kotlin/uuid/Uuid.kt):
  `Uuid.random()` delegates to `generateV4()`, whose documented implementations use
  JVM `java.security.SecureRandom`, Apple Native `arc4random_buf`, and the relevant
  platform secure APIs. UUIDv4 has only 122 random bits and is explicitly **not a
  secret/token API**. This package has no Wasm target (WASI has a documented caveat).
- The cached `kotlin-stdlib-2.4.21.jar` was inspected with `javap`:
  `Uuid$Companion.random()` invokes `generateV4()`;
  `UuidKt__UuidJVMKt.secureRandomBytes` invokes `SecureRandom.nextBytes`;
  `SecureRandomHolder` constructs `java.security.SecureRandom`.
- Structural/no-collision tests and JDK UUID version/variant checks are smoke tests,
  **not** entropy certification. Native executable/device qualification is outstanding.
- Domain ID generation does not persist operation IDs, enforce scoped uniqueness,
  authorize access, or prevent reuse after restore. Those remain durable-store duties.

## Business dates and DST

The only implemented, explicitly selected `CutoffResolution` is
`SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP`, matching the documented
[`LocalDateTime.toInstant` semantics in datetime v0.8.0](https://github.com/Kotlin/kotlinx-datetime/blob/v0.8.0/core/common/src/TimeZone.kt).
A missing local cutoff shifts forward by the gap duration; a repeated cutoff uses
the first occurrence. No alternative/reject-ambiguity policy is silently substituted.
Branch configuration activation must explicitly approve this supported choice.

Date classification compares **actual cutoff instants**. Comparing wall-clock time
alone would roll the business date backward in Cairo's repeated 23:00 hour. Calendar
date stepping, not subtraction of 24 hours, also handles Apia's skipped civil date.
The bounded search rejects unsupported discontinuities instead of guessing.

Master commit instant, optional client observation instant, journal order, business
date and fiscal date must be separate fields in the owning record. This utility
does not make the master wall clock trustworthy, protect persistent throttling, pick
a legal date, or validate statutory rules. Persist the relevant calendar/configuration
version; reconcile runtime tzdb differences before native/platform acceptance.

## Tests and evidence boundary

The historical scalar/wire verification record reports: **66 JVM tests, zero
failures/errors/skips**, including all five backend-wire oracle methods below.
The earlier expanded Android host batch passed **52 tests with zero failures,
errors or skips**. Current source contains 52 common and 14 JVM-specific methods;
counts alone are not execution evidence. Independent cross-review and refreshed
Apple Klib evidence are still pending; earlier Apple Klib compilation does not
establish native runtime correctness.

Relevant module commands (examples, not new execution results):

```sh
./gradlew :core:types:jvmTest :core:types:testAndroidHostTest
./gradlew :core:types:compileKotlinIosArm64 :core:types:compileKotlinIosSimulatorArm64
```

JVM tests use JDK BigInteger/BigDecimal for independent exact arithmetic and rounding
oracles; comparison, floor, scale conversion, quantity overflow, allocation and
signed boundaries are covered. The time oracle independently enumerates JDK cutoff
boundaries around Cairo, New York, Berlin, Lord Howe, Apia and Kathmandu transitions.
On JVM, datetime and the time oracle share the JDK tzdb: this verifies the mapping
algorithm, **not independent tzdb correctness** or Apple runtime behavior. Common
tests include explicit 2026 Cairo spring gap/autumn overlap and 23/25-hour days.

## Bounded evidence-v1 wire oracle

`IndependentBackendWireOracleTest` reads the backend-owned Node-produced
`backend/contracts/golden/evidence-v1.json`. Its test-only DTOs use the actual
Kotlin scalar serializers to reproduce fixed field-order JSON; independent JDK
SHA-256 and Ed25519 verify the primary payload and six signed time variants. The
frozen candidate primary content hash is
`sha256:070da7cf4fd7fed76953b38330bc689e3e75a1bc8aa4720f88a37488001e471c`.
The deterministic public fixture key is **not an enrolled device credential**.
This is a bounded fixed-schema oracle, not arbitrary JSON canonicalization,
production Kotlin gateway/semantic-hash code, or a signed-device integration test.

The candidate contract now agrees on domain UUID tenant IDs and quantity
`{"unitId":"UUID","scaled":"-1234567","scale":6}`. Precision metadata is a
small integer; money, scaled quantities and counters remain canonical strings.
Quantity scale rejects quoted numbers, exponent/decimal notation and negative
zero even under lenient JSON. Core revisions permit zero; evidence fields requiring
positive revisions/sequences apply that additional rule at their owning boundary.

An explicit **evidence-v1 timestamp adapter** admits only years **0000..9999**,
uppercase UTC `T`/`Z`, and the same 0/3/6/9 fractional groups as Kotlin Instant:
`.100Z`, `.123400Z` and `.123456700Z` are canonical; trimmed aliases are not.
The generic core scalar is deliberately not narrowed: Kotlin v2.4.21 supports
`-1000000000-01-01T00:00:00Z` through
`+1000000000-12-31T23:59:59.999999999Z`. The test-only range oracle distinguishes
these contracts. A real Kotlin master/gateway must enforce its supported protocol
range **before effects**, not rewrite an already committed/signed timestamp. That
production admission adapter is not supplied by these tests.

Domain UUIDs do not replace existing backend native string IDs. A still-required
protected mapping registry must preserve source/environment namespace and entity
kind, per-level bidirectional uniqueness, and authoritative Org/Company/Branch
parent links in one admission snapshot. Missing, disabled, ambiguous or reparented
mappings deny; identity tombstones prevent reuse/re-pointing historical evidence.
No cast, hash, default, auto-create, deployed registry or installed-index claim is
made by the scalar/golden code.

Remaining T04/integration gates include combined-tree peer acceptance and real
typed Kotlin/Go request/response adapters,
runtime/native Windows/macOS/Android/iPhone/iPad evidence, pinned/qualified
tzdb behavior, catalog normalization/namespace policies beyond explicitly chosen
EXACT_V1, complete scoped ID/storage/hash mappings, real persistence/overflow
rejection before writes, command-level sign/serial guards, and fiscal rounding/date
qualification. No push, PR, release, artifact upload or deployment is involved.
