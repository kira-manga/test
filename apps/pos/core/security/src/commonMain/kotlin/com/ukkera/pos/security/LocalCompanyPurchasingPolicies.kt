package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.flow.MutableStateFlow

/** Original producer phase data. Prepared and signing-intent records are not positive trust. */
public enum class LocalPurchasingPublicationPhase { PREPARED, SIGNING_INTENT, ISSUED }

public class LocalPurchasingPublicationReference internal constructor(
    public val originalActionId: DomainId,
    public val policyId: DomainId,
    public val publicationId: DomainId,
    public val revision: Revision,
    public val bodyDigest: PayloadDigest,
    public val phase: LocalPurchasingPublicationPhase,
) {
    override fun toString(): String = "LocalPurchasingPublicationReference([REDACTED])"
}

/**
 * Closed exact-publication trust from the actual original native company owner. The owner
 * verifies its original root, complete durable ledger, enrolled head, root-signed delegation,
 * immutable predecessor sequence and both P-256 signatures before retaining an envelope.
 * Requests cannot select a root/verifier, install entries or renew the native lifetime.
 *
 * This compares issued canonical envelopes only. It grants neither branch activation nor
 * qualified supplier/product/catalog selectors. Retained older publications remain available
 * for history; pending publications are not trusted. Native denial retires the handle forever.
 * Native ledger reads and refreshes never run inside a borrowed business transaction.
 */
public class NativePurchasingPolicyTrust internal constructor(
    public val scope: BranchScope,
    public val rootFingerprint: String,
) {
    private class State(val retired: Boolean, val entries: List<VerifiedNativePurchasingPolicy>)
    private val state = MutableStateFlow(State(false, emptyList()))

    public fun verify(scope: BranchScope, canonicalEnvelope: ByteArray): Boolean {
        if (scope != this.scope || canonicalEnvelope.size !in 1..LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES) return false
        val selected = state.value
        if (selected.retired) return false
        val digest = sha256(canonicalEnvelope)
        val entry = selected.entries.singleOrNull { it.envelopeDigest == digest } ?: return false
        return entry.envelope.same(canonicalEnvelope) && !state.value.retired
    }

    internal fun retainVerified(entries: List<VerifiedNativePurchasingPolicy>) {
        check(entries.size <= LocalCompanyAuthorityStateV1.MAX_POLICY_RECORDS &&
            entries.map { it.publicationId }.distinct().size == entries.size)
        while (true) {
            val previous = state.value
            check(!previous.retired)
            check(previous.entries.all { old -> entries.any { it == old } })
            if (state.compareAndSet(previous, State(false, entries.toList()))) return
        }
    }

    internal fun retireNoIo() {
        while (true) {
            val previous = state.value
            if (previous.retired || state.compareAndSet(previous, State(true, emptyList()))) return
        }
    }

    override fun toString(): String = "NativePurchasingPolicyTrust([REDACTED])"
}

internal data class VerifiedNativePurchasingPolicy(
    val publicationId: DomainId,
    val envelopeDigest: PayloadDigest,
    val envelope: SelfOwnedOctetsV1,
)

/**
 * Same-owner facade over the existing A0a producer. No shadow issuer, employee permission,
 * default policy or request-supplied trust is introduced. Issuance is separate from branch
 * activation; recovery returns an original result without signing or qualifying selectors.
 */
public class LocalCompanyPurchasingPolicies internal constructor(
    private val controller: SelfOwnedPurchasingPoliciesOwner,
    public val trust: NativePurchasingPolicyTrust,
) {
    public suspend fun prepare(proposal: LocalCompanyPolicyProposal): LocalCompanyAuthorityResult<LocalCompanyPolicyAction> =
        controller.preparePurchasingPolicy(proposal)
    /** Exact original action and independently authenticated head credentials are required. */
    public suspend fun approve(action: LocalCompanyPolicyAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> = controller.approvePurchasingPolicy(action, headLogin, headSecret)
    public suspend fun recover(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> =
        controller.recoverPurchasingPolicy(originalActionId)
    /** Refreshes the same closed trust from the authenticated original ledger, never repairs it. */
    public suspend fun retained(): LocalCompanyAuthorityResult<List<LocalPurchasingPublicationReference>> =
        controller.retainedPurchasingPolicies()
    override fun toString(): String = "LocalCompanyPurchasingPolicies([REDACTED])"
}

internal interface SelfOwnedPurchasingPoliciesOwner {
    suspend fun readOriginalPurchasingPolicies(): LocalCompanyAuthorityResult<LocalCompanyPurchasingPolicies>
    suspend fun preparePurchasingPolicy(proposal: LocalCompanyPolicyProposal): LocalCompanyAuthorityResult<LocalCompanyPolicyAction>
    suspend fun approvePurchasingPolicy(action: LocalCompanyPolicyAction, login: String, secret: CharArray):
        LocalCompanyAuthorityResult<LocalCompanyPolicyPublication>
    suspend fun recoverPurchasingPolicy(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication>
    suspend fun retainedPurchasingPolicies(): LocalCompanyAuthorityResult<List<LocalPurchasingPublicationReference>>
}

/** Missing native support stays unavailable; no different root or ledger is opened as fallback. */
public suspend fun SelfOwnedLocalSecurityContext.readOriginalPurchasingPolicies(): LocalCompanyAuthorityResult<LocalCompanyPurchasingPolicies> =
    (controller as? SelfOwnedPurchasingPoliciesOwner)?.readOriginalPurchasingPolicies()
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

public suspend fun SelfOwnedNativeHistoryCustody.readOriginalPurchasingPolicies(): LocalCompanyAuthorityResult<LocalCompanyPurchasingPolicies> =
    (controller as? SelfOwnedPurchasingPoliciesOwner)?.readOriginalPurchasingPolicies()
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
