package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.LocalAdministrationCommand
import com.ukkera.pos.app.LocalAdministrationPendingResult
import com.ukkera.pos.security.LocalAdministrationQuery
import com.ukkera.pos.security.LocalAdministrationReadResult
import com.ukkera.pos.app.LocalAdministrationResult
import com.ukkera.pos.app.LocalAdministrationUiGateway
import com.ukkera.pos.app.LocalEmployeeCreationUiGateway
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.types.DomainId

/**
 * A remote Client session that has the separately composed employee-creation lane but no
 * general account administration. Directory reads and generic administrative requests stay
 * NOT_CONFIGURED; nothing here grants authority. Every creation call is authorized by the Master.
 */
public class LocalCreationOnlyAdministrationGateway(
    override val employeeCreation: LocalEmployeeCreationUiGateway,
) : LocalAdministrationUiGateway {
    init { require(employeeCreation.configured) }
    override val configured: Boolean = true
    override suspend fun read(query: LocalAdministrationQuery): LocalAdministrationReadResult = LocalAdministrationReadResult.Unavailable
    override suspend fun pending(): LocalAdministrationPendingResult = LocalAdministrationPendingResult.Available(null)
    override suspend fun prepare(command: LocalAdministrationCommand): LocalAdministrationResult =
        LocalAdministrationResult.NotPrepared(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun submit(requestId: DomainId, newPassword: CharArray?): LocalAdministrationResult {
        newPassword?.fill('\u0000')
        return LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.NOT_CONFIGURED)
    }
    override suspend fun lookup(requestId: DomainId): LocalAdministrationResult =
        LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun cancel(requestId: DomainId): LocalAdministrationResult =
        LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun acknowledge(requestId: DomainId): Boolean = false
    override fun toString(): String = "LocalCreationOnlyAdministrationGateway([REDACTED])"
}
