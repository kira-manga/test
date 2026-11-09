package com.ukkera.pos.app

import com.ukkera.pos.security.LocalAdministrationCommand
import com.ukkera.pos.security.LocalAdministrationQuery
import com.ukkera.pos.security.LocalAdministrationReadResult
import com.ukkera.pos.security.LocalAdministrationReceipt
import com.ukkera.pos.types.DomainId

enum class LocalAdministrationPhase { PREPARED, DISPATCHED, APPLIED, CANCELLED }

/** Original caller-owned request. A retained response is historical, not today's target state. */
data class LocalAdministrationPending(
    val command: LocalAdministrationCommand,
    val phase: LocalAdministrationPhase,
    val cancellationRequested: Boolean,
    val receipt: LocalAdministrationReceipt?,
) {
    val terminal: Boolean get() = phase == LocalAdministrationPhase.APPLIED || phase == LocalAdministrationPhase.CANCELLED
}

sealed interface LocalAdministrationResult {
    data class Known(val operation: LocalAdministrationPending) : LocalAdministrationResult
    /** No publication/retry/new ID is licensed by an unavailable observation. */
    data class Unresolved(val requestId: DomainId, val problem: LocalServiceProblem = LocalServiceProblem.UNAVAILABLE) : LocalAdministrationResult
    /** Only used when a new request was NOT durably staged/dispatched. */
    data class NotPrepared(val problem: LocalServiceProblem) : LocalAdministrationResult
}

sealed interface LocalAdministrationPendingResult {
    data class Available(val operation: LocalAdministrationPending?) : LocalAdministrationPendingResult
    data class Unavailable(val problem: LocalServiceProblem) : LocalAdministrationPendingResult
}

/** UI port; no passwords in commands, drafts, pending records, receipts or diagnostics. */
interface LocalAdministrationUiGateway {
    val configured: Boolean
    suspend fun read(query: LocalAdministrationQuery): LocalAdministrationReadResult
    suspend fun pending(): LocalAdministrationPendingResult
    /** Durably prepare the exact data before the user confirms execution. Never executes it. */
    suspend fun prepare(command: LocalAdministrationCommand): LocalAdministrationResult
    /** This UI port consumes/clears the supplied transient password array, including refusals. */
    suspend fun submit(requestId: DomainId, newPassword: CharArray? = null): LocalAdministrationResult
    suspend fun lookup(requestId: DomainId): LocalAdministrationResult
    /** Original-ID fencing cancellation, not deletion of an uncertain local request. */
    suspend fun cancel(requestId: DomainId): LocalAdministrationResult
    /** Explicit acknowledgement retires only a terminal client record; master audit is retained. */
    suspend fun acknowledge(requestId: DomainId): Boolean
}

object UnavailableLocalAdministration : LocalAdministrationUiGateway {
    override val configured: Boolean = false
    override suspend fun read(query: LocalAdministrationQuery): LocalAdministrationReadResult = LocalAdministrationReadResult.Unavailable
    override suspend fun pending(): LocalAdministrationPendingResult = LocalAdministrationPendingResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun prepare(command: LocalAdministrationCommand): LocalAdministrationResult = LocalAdministrationResult.NotPrepared(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun submit(requestId: DomainId, newPassword: CharArray?): LocalAdministrationResult {
        newPassword?.fill('\u0000')
        return LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.NOT_CONFIGURED)
    }
    override suspend fun lookup(requestId: DomainId): LocalAdministrationResult = LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun cancel(requestId: DomainId): LocalAdministrationResult = LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun acknowledge(requestId: DomainId): Boolean = false
}
