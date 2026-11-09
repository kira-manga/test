package com.ukkera.pos.database

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.CompanyBusinessPublicationReference
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.types.DomainId

public data class LocalSerialPrivatePublicationReference(
    public val originRequestId: DomainId,
    public val publication: CompanyBusinessPublicationReference,
)

/** Genuine original-master companion. Each request authenticates the actual bearer/peer
 * and current protected requester; possession of an action ID never grants private access. */
public interface LocalMasterSerialPublications {
    public suspend fun retainedSerialPublications(credentials: LocalGatewayCredentials):
        LocalCompanyAuthorityResult<List<LocalSerialPrivatePublicationReference>>
    public suspend fun prepareSerialAuthority(credentials: LocalGatewayCredentials, requestId: DomainId):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>
    public suspend fun prepareSerialPublication(credentials: LocalGatewayCredentials, requestId: DomainId,
        proposal: CompanyBusinessPublicationProposal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>
    public suspend fun reviewSerialPublication(credentials: LocalGatewayCredentials, requestId: DomainId,
        originRequestId: DomainId, originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>
    public suspend fun recoverSerialPublication(credentials: LocalGatewayCredentials, requestId: DomainId,
        originRequestId: DomainId, originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput>
}
