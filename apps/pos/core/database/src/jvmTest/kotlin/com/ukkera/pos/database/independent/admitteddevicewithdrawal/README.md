# Additional-device withdrawal V2 supplementary JVM slices

**Status: 15 supplementary methods passed on 3 November 2026**, zero failures,
errors or skips. All whole frozen ADAH cases remain NOT RUN. Test selector:
`com.ukkera.pos.database.independent.admitteddevicewithdrawal.AdmittedDeviceWithdrawalV2SupplementaryTest`

These controlled tests wire factual subsets of the unchanged
`qa/pos/designs/admitted-device-authentication-history` v1 expectations and
ODA-028/029 from `qa/pos/designs/original-device-admission-v1/cases.md`.
They do not replace, execute or pass the frozen integration cases.

## Fixture and mapping

The logical F-ADAH-BASE relationships are two existing credential-present account
inputs A0/A1, A0's S0 on original device M, a distinct admitted target N, original
admission identity R and withdrawal identity W. Neither account has an N session.
The identity adapter and current-policy/status snapshot are explicitly substituted.
Password-record bytes, access hash, session/issuer/boot IDs, account revisions and
an explicit access duration are inert constructor scaffolding. No credential
verification, enrollment or real clock qualification occurs.

| Methods | Bounded observable mapping |
| --- | --- |
| `adah01_02_noTargetSessionsPreservesBothWholeProtectedAccountInputs` | ADAH-01/02 and ODA-028: whole account/credential/session inputs remain byte-identical when no target session exists. |
| `adah02_admissionReducerPreservesTheDeclaredBeforeAndTargetDescriptors` | ADAH-02: pure current-trust reduction retains the input and target descriptors; not historical admission preservation. |
| `adah01_02_captureKeepsDistinctTargetAndTheUnchangedTwoAccountDeclaration` | ADAH-01/02: retains supplied A0/A1 census, distinct M/N relationships and unchanged account commitments; predictions alone are not submitted images. |
| `adah04_*` | ADAH-04: retains original W, actor/session and issuer/access-window declaration; changed semantics cannot reuse the original supplied requirement. No retry/settlement schedule is exercised. |
| `adah03_*` | ADAH-03(a/b): supplied disabled/revoked operator facts cannot use the earlier supplied capability. No durable state change, policy-denial variant or same-writer ordering is established. |
| `adah05_*` | ADAH-05: foreign target/scope and substituted operator-device declarations/images are refused. These are controlled DATA checks, not native-source authority tests. |
| `adah06_*` | ADAH-06 and ODA-029: incomplete or mismatching submitted-image sequences cannot yield a complete declaration and are not repaired from predictions. Not complete-history or close qualification. |
| `declarationCompatibility_*` | Implementation-profile compatibility: exact image sequence, projection consistency and unique actor order. Not a new frozen numeric/security oracle. |
| `profileCompatibility_*` | Strict V2 DATA roundtrip and malformed/profile separation checks. V1 rejects V2 bytes; this is not complete V1 byte/predicate regression coverage. |

## Boundaries intentionally not claimed

- No Room database, genuine target admission/custody, native seal/final decision,
  chronological account/admission replay, actual commit/close/reopen or installed
  transport is exercised. Direct image submission is DATA, not protected sealing.
- A complete producer declaration is not proof of a genuine complete account
  census. Coordinated omission from both supplied projections requires the actual
  chronological consumer/census join; this harness does not supply that proof.
- No original R outcome lookup, durable W attribution, duplicate-request fencing,
  uncertain writer settlement, expiry extension prevention at native final time,
  target-device authentication, retained throttle history or business effects are
  verified by these slices.
- Withdrawal event encoding and revision increment are unspecified by the freeze.
  Existing supported APIs construct those profile inputs; tests do not promote
  their concrete encodings, image counts or capacity constants to frozen oracles.
- Missing/foreign history remains unqualified. Neither a decoded declaration nor
  copied fixture identities authorize current protected use or full-case acceptance.
