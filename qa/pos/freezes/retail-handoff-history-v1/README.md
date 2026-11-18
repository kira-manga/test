# Frozen ordinary unpaid retail handoff history cases — v1

Independent requirements-only design for #25 / LF-ORDER-02 / OWN-04 and native
history/recovery #51. All **15 whole cases remain NOT_RUN**.

- [Cases and exact semantic oracles](cases-v1.md).
- [Synthetic baseline, stock/cash invariants and variants](fixtures-v1.json).

The scope is ordinary unpaid retail offer/cancel/recipient acceptance. It does
not qualify paid transfer, manager reassignment, FOOD or central services.

## Execution bindings still required

1. Actual handoff, original-result/history/recovery and current disclosure APIs,
   protected same-device and admitted local RPC adapters.
2. Public cancellation actor, expected-version and competing-transition behavior.
3. Current narrow recovery/disclosure scope after revocation and cache invalidation.
4. Genuine native authorization/business-source admission, safe negative fixtures
   and an observable integrity/recovery exception.
5. Supported durable restart/copy/compatible-reader path and coherent recovery cut.
6. Production fixtures, platform/driver, fault injection and independent committed
   observation surfaces.

Cases/fixtures are unchanged from the independent v1 specification. An observed
history/helper slice does not establish native admission, employee authorization,
SQLite durability, admitted RPC, process restart or complete-case acceptance.
Record actual execution separately; retain unbound/unrun variants explicitly.
