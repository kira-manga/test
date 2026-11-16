package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

internal class SelfOwnedCompanyKeyRef(val keyId: DomainId)

/** Founding facts of the retained company bootstrap (V1 or V2). */
internal class SelfOwnedCompanyFoundingView(
    val rootId: DomainId,
    val actionId: DomainId,
    val organizationId: DomainId,
    val companyId: DomainId,
    val issuerId: DomainId,
    val facts: SelfOwnedCompanyFactsV1,
    private val keyIds: Map<SelfOwnedNativeKeyRoleV1, DomainId>,
) {
    fun key(role: SelfOwnedNativeKeyRoleV1): SelfOwnedCompanyKeyRef =
        SelfOwnedCompanyKeyRef(keyIds[role] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN))
    override fun toString(): String = "SelfOwnedCompanyFoundingView([REDACTED])"
}

internal class SelfOwnedCompanyBootstrapView(
    val original: SelfOwnedCompanyFoundingView,
    val headAuthorityId: DomainId,
    val headEnrollmentId: DomainId,
    val headName: String,
    val headLogin: String,
    private val ids: List<DomainId>,
) {
    fun allIds(): List<DomainId> = ids
    override fun toString(): String = "SelfOwnedCompanyBootstrapView([REDACTED])"

    companion object {
        fun of(value: LocalCompanyAuthorityBootstrapV1): SelfOwnedCompanyBootstrapView = SelfOwnedCompanyBootstrapView(
            SelfOwnedCompanyFoundingView(value.original.rootId, value.original.actionId, value.original.organizationId,
                value.original.companyId, value.original.issuerId, value.original.facts, value.original.keys.associate { it.role to it.keyId }),
            value.headAuthorityId, value.headEnrollmentId, value.headName, value.headLogin, value.allIds())
        fun of(value: LocalCompanyAuthorityBootstrapV2): SelfOwnedCompanyBootstrapView = SelfOwnedCompanyBootstrapView(
            SelfOwnedCompanyFoundingView(value.original.rootId, value.original.actionId, value.original.organizationId,
                value.original.companyId, value.original.issuerId, value.original.facts, value.original.keys.associate { it.role to it.keyId }),
            value.headAuthorityId, value.headEnrollmentId, value.headName, value.headLogin, value.allIds())
    }
}
