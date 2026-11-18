package com.ukkera.pos.app

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.types.DomainId

/** Display DATA only. It carries neither a native owner nor permission to sign or activate. */
data class LocalCompanyPublicationReviewData(
    val originalActionId: DomainId,
    val proposal: CompanyBusinessPublicationProposal,
    val signingKeyId: DomainId,
    val rootFingerprint: String,
    val companyName: String,
    val headName: String,
    val headLogin: String,
) {
    companion object {
        fun of(action: CompanyBusinessPublicationAction): LocalCompanyPublicationReviewData =
            LocalCompanyPublicationReviewData(action.originalActionId, action.proposal, action.signingKeyId,
                action.rootFingerprint, action.companyName, action.headName, action.headLogin)
    }
}
