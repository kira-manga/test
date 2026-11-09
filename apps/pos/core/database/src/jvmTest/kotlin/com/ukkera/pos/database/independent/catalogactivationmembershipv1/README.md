# Catalog original membership: unavailable proposal integration

Status: **NOT_RUN**. These JVM assertions bind a bounded non-device integration
slice to the existing frozen expectations; they do not complete any whole group.

The existing freezes are
[`catalog-original-publication-membership-v1`](../../../../../../../../../../../../../qa/pos/freezes/catalog-original-publication-membership-v1/README.md)
and
[`catalog-unit-authority-v3`](../../../../../../../../../../../../../qa/pos/freezes/catalog-unit-authority-v3/README.md).
The test resource `catalogactivationmembershipv1/catalog-unit-authority-v3.json`
is an unchanged copy of the latter's `fixtures.json`. Its independently fixed
legacy and explicit base-definition canonical bytes remain the input oracles.

| Frozen requirement | Bound assertion | Remaining scope |
| --- | --- | --- |
| CPM-P01/P05: unavailable membership is not known-empty or a durable refusal | The actual handler reads the actual detached transaction's unavailable native observation and exposes `SOURCE_UNAVAILABLE`, for both legacy unit-less and explicit-unit SKU content. | Complete empty/nonempty membership and genuine original refusal bundles. |
| CPM-P04/P06: portable/current data cannot supply historical custody | Matching persisted catalog copies, valid Ed25519 bytes and reopening the same file still supply no original native observation. | Original binding mismatches, legacy retained original recovery and protected result release. |
| CUA-03: preserve legacy omission and exact authenticated extension content | The actual codec retains the two frozen literal canonical publications through request normalization. | Broader codec variants and native completed content. |
| CUA-06: unavailable-history control | Explicit unit content stays admission-held when no native original observation is available. | Authentic full native and activated histories, failed native refresh, conflict admission and original recovery. |

Both methods run for `legacy` and `explicit-base`, giving four selected JVM
invocations. They use the production generated Room database, bundled file-backed
SQLite, DAO metadata/point reads, `TransactionView`, catalog codec, handler and
`JvmCompanyPublicationKeys`. A recording delegate forwards every read and records
native observation access. Disposable Ed25519 signatures establish cryptographic
input validity only. Persisted source specimens have no native original custody;
their foreign-key parent is deliberately unwritable and has invalid authority
metadata. No guard, access API or production schema is changed.

The assertions inspect `prepare()` and its admission restriction without calling
`evaluate()` or submitting a command. They compare exact selected SQL column values
and the real connection's change count before/after preparation. Unchanged
persistence proves conservation during these reads/preparations. Empty outcome,
journal, audit or outbox tables do not prove commit rollback, because no command
was submitted. Reopening a detached storage file is not a native Master restart.

`NativeCompanyBusinessPublicationSnapshot`, completed outputs, trust and native
owner construction are restricted to the security module's genuine native path.
This harness does not construct them or grant positive employee authorization.
Authorized Apply/durable refusal, exact completed membership, immutable native
conflicts, five literal nonbusiness refusal changes, original recovery, RPC and
native restart remain **UNBOUND/NOT_RUN** here. The persisted copies also do not
qualify a production price-history baseline or determine a later durable outcome.

The existing `:core:database` JVM test dependency on `:adapters:catalog-store`
provides all required production types. No new Gradle wiring is needed. Required
verification is source review, compilation and the focused selector:

```sh
./gradlew :core:database:jvmTest --tests 'com.ukkera.pos.database.independent.catalogactivationmembershipv1.*'
```

Record actual execution and the tested revision separately. No passing result is
claimed by this wiring record.

Actual outcome: the focused four invocations passed after a missing helper import
was corrected; see [source-specific results and limits](../../../../../../../../../../../../../qa/pos/traceability/catalog-original-membership-20261101/README.md).
