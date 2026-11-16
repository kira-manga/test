package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Pure structural cross-check; native callers still establish original custody and authentication. */
internal fun requireOriginalCompanyPolicyV1(policy: PurchasingPolicyPublicationV1, delegation: PurchasingAuthorityDelegationV1,
    state: LocalProvisionerRegistryStateV1, original: LocalCompanyAuthorityBootstrapV1, rootFingerprint: String) {
    val branch = requireNotNull(state.allocations.singleOrNull()).body.coordinates.scope
    val rootKey = original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId
    val headKey = original.key(LocalCompanyHeadKeyRoleV1.POLICY)
    requireOriginalCompanyPolicy(policy, delegation, branch, rootKey, original.headAuthorityId, original.headName, headKey.keyId,
        rootFingerprint) { point -> headKey.proof.same(point) }
}

/**
 * Desktop (Windows/macOS) V2 company container counterpart of [requireOriginalCompanyPolicyV1]: the
 * same structural rules over the V2 registry/bootstrap. A V2 head key intent proves its key by a
 * digest, so the delegation subject point is compared with [policyPoint], the actual POLICY head
 * key point read from custody by the caller.
 */
internal fun requireOriginalCompanyPolicyV2(policy: PurchasingPolicyPublicationV1, delegation: PurchasingAuthorityDelegationV1,
    state: LocalProvisionerRegistryStateV2, original: LocalCompanyAuthorityBootstrapV2, rootFingerprint: String,
    policyPoint: ByteArray) {
    val branch = requireNotNull(state.allocations.singleOrNull()).body.coordinates.scope
    val rootKey = original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId
    val headKey = original.key(LocalCompanyHeadKeyRoleV1.POLICY)
    requireOriginalCompanyPolicy(policy, delegation, branch, rootKey, original.headAuthorityId, original.headName, headKey.keyId,
        rootFingerprint) { point -> point.contentEquals(policyPoint) }
}

private fun requireOriginalCompanyPolicy(policy: PurchasingPolicyPublicationV1, delegation: PurchasingAuthorityDelegationV1,
    branch: BranchScope, rootKey: DomainId, headAuthorityId: DomainId, headName: String, headKeyId: DomainId,
    rootFingerprint: String, isHeadPoint: (ByteArray) -> Boolean) {
    require(policy.branch == branch && delegation.company == branch.company() && policy.root.keyId == rootKey &&
        delegation.root.keyId == rootKey && policy.root.fingerprint.value == rootFingerprint && delegation.root.fingerprint.value == rootFingerprint)
    require((delegation.issuer as? PurchasingAuthorityIssuerV1.Root)?.keyId == rootKey)
    require(delegation.subject.authorityId == headAuthorityId && delegation.subject.name == headName &&
        delegation.subject.role == PurchasingAuthorityRoleV1.COMPANY_HEAD && delegation.subject.keyId == headKeyId)
    val point = delegation.subject.publicPoint.copyBytes()
    try { require(isHeadPoint(point)) } finally { point.fill(0) }
    val delegationBytes = LocalCompanyPolicyWireV1.encodeDelegationBody(delegation)
    try {
        require(policy.author.authorityId == headAuthorityId && policy.author.accountRevision == delegation.subject.accountRevision &&
            policy.author.keyId == headKeyId && policy.author.delegationId == delegation.delegationId &&
            policy.author.delegationPublication.publicationId == delegation.publicationId &&
            policy.author.delegationPublication.revision == delegation.revision &&
            policy.author.delegationPublication.bodyDigest.value == sha256(delegationBytes).value)
    } finally { delegationBytes.fill(0) }
    require(policy.validity.validFrom == delegation.validity.validFrom && policy.validity.validUntil == delegation.validity.validUntil)
    for (grant in delegation.grants) {
        require(grant is PurchasingAuthorityGrantV1.PolicyPublish && grant.branchIds == listOf(branch.branchId))
        require(grant.selection.products.categories.isEmpty() && grant.selection.products.skus.isNotEmpty())
    }
    for (rule in policy.rules) {
        require(rule.selection.products.categories.isEmpty() && rule.selection.products.skus.isNotEmpty())
        val grant = requireNotNull(delegation.grants.singleOrNull { it.grantId == rule.delegationGrantId }
            as? PurchasingAuthorityGrantV1.PolicyPublish)
        require(grant.action == rule.action)
        val replacement = PurchasingPolicyRuleV1(rule.ruleId, rule.delegationGrantId, rule.action, grant.selection, grant.limits,
            rule.evidence, rule.approval, rule.acquisition)
        fun oneRule(value: PurchasingPolicyRuleV1): ByteArray = LocalCompanyPolicyWireV1.encodePolicyBody(PurchasingPolicyPublicationV1(
            policy.branch, policy.root, policy.policyId, policy.publicationId, policy.revision, policy.predecessor, policy.author,
            policy.validity, policy.retentionClasses, listOf(value)))
        val actual = oneRule(rule); val expected = oneRule(replacement)
        try { require(actual.contentEquals(expected)) } finally { actual.fill(0); expected.fill(0) }
    }
}
