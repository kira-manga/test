# ADR 0001 — isolated scalar/build foundation

Date: 23 October 2026. Status: **candidate, pending real build and peer review**.

- Keep the original plan, KMP source references and review package unchanged.
- All work is local. New app has no remote; all isolated source clones have disabled push URLs and the rejecting pre-push hook. No wrappers named commit/deploy/publish are run.
- Kotlin 2.4.21, AGP 9.3.1 and Gradle 9.7.0 are the initial documented compatibility candidates. 9.7.0 replaces the initially noted existing-ecosystem 9.6.1 candidate as the upper documented Kotlin-compatible Gradle; publication is not proof.
- Shared Android uses `com.android.kotlin.multiplatform.library`, not KMP + Android application in one module. The Android application launcher will be separate when added.
- JVM, Android, iOS arm64 and iOS simulator arm64 are declared. A Linux JVM test does not qualify another target, native linker or physical device. Apple execution requires its real runner.
- Start with the exact scalar core and serialization 1.12.0. Use portable bignum 0.3.10 BigInteger for exact intermediates, not JVM-only BigDecimal in common code. Independently test against JVM BigDecimal/BigInteger reference calculations. Qualify native compatibility before acceptance.
- Decimal input is bounded to 38 coefficient digits and a declared fractional precision (quantity at most six, unit-price at most eight). Generic scalar parsing supports up to 18 fractional digits for explicit internal contracts; caller domains must enforce their narrower limits. No silent stock rounding. Posted money remains signed 64-bit minor units encoded as canonical JSON strings.
- The rational helper implements exact elementary arithmetic and explicitly named operational HALF_UP posting, not the full tax/promotion/valuation engine. Largest remainder allocates a bounded signed amount using stable keys; it is not an entitlement or discount-cap guard.
- Full Room/P2pKit/Nav3/Koin/Coil graph, OS keys, durable transactions, actual backup/fencing and fiscal posting remain T01/T02/T05+ gates. Do not claim a scalar library is a POS.
