package com.ukkera.pos.security

/**
 * Explicit durable-consumer boundary for a creation transport. Ordinary creation methods do
 * not supply a consumer: a remote provider must withhold its terminal SDK Receipt when no
 * real consumer has accepted the exact result. Returning DATA alone is not durable acceptance.
 * A local/cohost service need not implement this transport capability.
 *
 * Before offering Applied, Cancelled or IdentityConflict, the actual transport must validate
 * the selected Host, physical/call/procedure association and complete original, and observe
 * current authority for disclosure. It must offer the same terminal status as its returned
 * result, await the consumer, then recheck its exact original connection before Receipt.
 * Consumer failure, cancellation or currentness loss withholds Receipt and propagates failure;
 * it never implies rollback, clears the original, retries submission or renews native work.
 * Nonterminal observations cannot be passed to the terminal consumer.
 *
 * The consumer validates and durably retains that exact original/result in the genuine client
 * journal before returning. This promise grants no employee, device, native or Master authority.
 * Credentials/passwords retain the caller/owned-copy rules of [LocalEmployeeCreationServiceV1].
 * Missing implementations still refuse through the inherited ordinary methods; implementing
 * this interface does not install a provider, procedure, journal or native history capability.
 */
public interface LocalEmployeeCreationResultAcceptanceV1 : LocalEmployeeCreationServiceV1 {
    public suspend fun executeEmployeeCreationAccepting(
        operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1,
        newPassword: CharArray? = null,
        accept: suspend (LocalEmployeeCreationStatusV1) -> Unit,
    ): LocalEmployeeCreationExecutionV1

    public suspend fun cancelEmployeeCreationAccepting(
        operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1,
        accept: suspend (LocalEmployeeCreationStatusV1) -> Unit,
    ): LocalEmployeeCreationCancellationV1

    public suspend fun lookupEmployeeCreationAccepting(
        operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1,
        accept: suspend (LocalEmployeeCreationStatusV1) -> Unit,
    ): LocalEmployeeCreationStatusV1
}
