# Setup acknowledgement supporting behavior

This incremental local capability adds distinct protected setup actions to the existing
master command/read kernel. It does not change installation, native custody, Android
schema/bytes or any prior command profile.

| Action | Permission action | Master identity |
|---|---|---|
| Protected saved-result read | `SETUP_RESULT_READ` | `local.setup.result.read` `v1` |
| Saved-result delivery ACK | `SETUP_RESULT_DELIVERY_ACKNOWLEDGE` | `local.setup.result.acknowledge` `v1` |
| Responsible business go-live | `SETUP_BUSINESS_GO_LIVE` | `local.setup.business.go-live` `v1` |
| Configuration settlement | `SETUP_CONFIGURATION_SETTLE` | `local.setup.configuration.record` `v1` |
| Catalog import settlement | `SETUP_CATALOG_IMPORT_SETTLE` | `local.setup.catalog-import.row` / `.complete` `v1` |
| Opening settlement | `SETUP_OPENING_SETTLE` | `local.setup.opening.stock-row` / `.float-row` / `.complete` `v1` |
| Detailed checkpoint read | `SETUP_CHECKPOINTS_READ` | `local.setup.checkpoints.read` `v1` |

The new `SecurityAction` values are appended after every existing value. Existing action
ordinals, roles, first-account defaults and grants are unchanged. None of the new actions is
added to a starter list or granted to the setup actor, first actor, manager, administrator or
central identity implicitly. Legitimate provisioning uses the existing signed employee-policy
authoring/activation path.

## Compatibility boundary

These setup-specific profiles are introduced by this increment. The main branch and the
preceding startup increment (`6fcd4e80`) contain no setup-result/checkpoint profile or retained
facts in those formats. Existing installation/history formats and earlier command identities
stay unchanged. Upgrading a pre-increment database leaves ACK and checkpoints absent, never
implicitly settled. Intermediate draft encodings have not been installed or executed natively
in this campaign; no production-data reset or accepting legacy-field default is introduced.
Required upgrade qualification remains unexecuted.

## Public wiring

`LocalSetupResultCodecV1` (`core/database`) exposes the additive request/result grammar:

- `acknowledge(operationId, installationId)` → `MutationRequest`
- `goLive(operationId, installationId)` → `MutationRequest`
- `read(installationId)` → `ReadRequest`
- `decodeOutcome(...)` and `decodeProjection(...)`

`LocalSetupAcknowledgementServices` registers the two commands and one read through the
normal production registry (`LocalBranchCommerceRegistry`). `LocalSetupResultHistoryProducer`
proves the retained result/effect pair after restart. Registration itself creates no grant,
session, readiness or native authority.

The requester supplies only the installation ID it believes it is addressing. The master
derives the original binding, scope, actor, permission, current policy/master state, operation
identity, semantic digest, recorded time and audit. A different or replacement installation
name is a no-effect hold; it is never adopted or merged.

## ACK and go-live

ACK commits one immutable acknowledgement aggregate keyed by the exact installation ID. A
same-operation retry returns the original retained result while the full saved-original binding still matches. If that binding changes, execute-replay holds; protected historical result recovery remains separate. A new operation ID cannot create a
second ACK or replace the original actor/audit; it is held without a new effect. The saved
setup result and product ACK/audit evidence are retained. Native delivery-envelope
retention/consumption remains undecided under OSR-I3/OSR-U1 and is not implemented or
asserted here.

Go-live is independent of ACK and of employee sign-in/RUN. It requires current
`SETUP_BUSINESS_GO_LIVE` authority and all observed prerequisites settled:

| Prerequisite | Production binding |
|---|---|
| `NATIVE_INSTALLATION_SETTLEMENT` | Actual saved original receipt, issuer acknowledgement, no pending attempt and completed first child |
| `REQUIRED_CONFIGURATION` | Original-bound configuration checkpoint with verified applied activity and explicit currency/timezone/cutoff/fiscal identity |
| `CATALOG_IMPORT_SETTLEMENT` | Original-bound manifest of unique retained catalog publications, or explicit no-import choice |
| `OPENING_SETTLEMENT` | Original-bound stock/float manifests checked against retained receipt lines, cash-opening results and source claims, or explicit zero choices |

Any missing, unknown, in-progress or unreadable prerequisite holds go-live with no committed
go-live fact. The protected read projection returns the exact `binding` string for every
unsettled prerequisite. Missing checkpoints use `unavailable:setup.configuration.checkpoint.missing`,
`unavailable:setup.catalog-import.checkpoint.missing` and `unavailable:setup.opening.checkpoint.missing`.
Unrelated activity/SKU/stock rows cannot settle setup without the checkpoint and exact retained
operation/result/effect references. Duplicate source identities and duplicate catalog operations
are refused. A zero opening or absent import must be recorded explicitly.
`OPERATIONAL`, `RUN`, a caller Boolean, supplied history/readiness or a mock service is never
substituted. A branch with a genuinely absent optional standby is not blocked by that absence.

Go-live records the currently authenticated local employee as the responsible actor and the
master-derived audit time. It does not delegate, substitute an approver, register centrally,
prove health, sign in an employee, promote training, post financial/stock effects or qualify a
native API.

## Read and isolation

The read is nonmutating and requires `SETUP_RESULT_READ` on the exact installation binding. It
returns only the saved original projection, its ACK/go-live facts and prerequisite
observations. It does not ACK, go live, install, import, repair or replace anything. Authority
loss returns no projection and no inference that the setup is absent.

Retained catalog/stock/float input references can be recovered through
`local.setup.checkpoint-rows.read/v1`, under `SETUP_CHECKPOINTS_READ`. Each page returns at most
64 references from the exact native original and validates the retained row producer. Loading
checkpoints restores these references into the manifest draft after restart. Pages do not prove
readiness: completion and go-live still verify the full current manifest and source effects.

The installed workflow checks checkpoint-settle actions with `ADMINISTER`, matching the
master handlers. Checkpoint requests carry the actual native connection's saved receipt
identity as request data; they do not require `SETUP_RESULT_READ`. The master independently
validates that binding and the current action grant. ACK and go-live remain accessible to
actors with their respective action grant even when saved-result details are not disclosed.

Committed row replies, including lookup/resume, restore exact references from the retained
actor journal. Rejected replies neither add manifest rows nor discard draft inputs. Explicit
checkpoint reload replaces draft references with retained rows under its separate read grant.
A recovered completion clears only drafts matching its retained input count/digest; later or
changed drafts are preserved. Each installed action/read is checked separately, so missing
read handlers do not disable available ACK/go-live commands. Retained row discovery also
cross-checks aggregate membership, refusing a row whose source claim is missing.

Detailed checkpoint reads require the separate `SETUP_CHECKPOINTS_READ` grant; saved-result
read authority alone discloses no fiscal configuration or opening-value details. Go-live and
checkpoint writes respect stock, cash-custody and authorization recovery holds. Facts retain
the original request, issued attempt/sequence and definition/genesis/receipt digests; a changed
original cannot reuse an earlier fact as current.

The checkpoint profile accepts only the live environment. Native training/live isolation and
other platform providers remain separate qualification work. These source bindings and passing
compilation do not establish positive native go-live or whole SAS case execution.
