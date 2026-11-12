package com.ukkera.pos.security

/**
 * Separately composed exact-original employee-creation operations. A remote creation adapter
 * need not expose account directories, credential resets, device administration or a local
 * native peer. This type does not itself prove custody, an authenticated connection or authority.
 *
 * The actual service must authenticate the physical connection and current interactive creator
 * for every target-bound read, write and result disclosure. The creator/original association,
 * complete definition, native settlement and unresolved outcomes remain mandatory. Public
 * context/input objects are DATA only. A cast or copied catalogue never admits a writer.
 *
 * Inputs remain caller-owned: asynchronous providers copy credentials/passwords before enqueue
 * and clear owned copies on every abandoned delivery. Cancellation/lost delivery never implies
 * non-creation and never rearms a dispatched original. Missing implementations refuse.
 */
public interface LocalEmployeeCreationServiceV1 {
    /** Current target-scoped input DATA only. Returns no password, permit, safe absence or
     * first-dispatch handoff. Missing providers remain unavailable. */
    public suspend fun employeeCreationContext(operator: LocalGatewayCredentials,
        target: LocalEmployeeCreationTargetV1): LocalEmployeeCreationContextResultV1 = LocalEmployeeCreationContextResultV1.Unavailable
    /** Assemble the complete original with actual production codecs and retained admissions
     * under a confirmed no-write protected read. This does not prepare or submit it. */
    public suspend fun captureEmployeeCreationDefinition(operator: LocalGatewayCredentials,
        draft: LocalEmployeeCreationDraftV1): LocalEmployeeCreationDefinitionResultV1 = LocalEmployeeCreationDefinitionResultV1.Unavailable
    /** First submission of the complete retained original. Caller owns credentials/password;
     * an asynchronous adapter must copy them before handing them to its work queue. Providers
     * must reauthorize the original creator/target at the writer and confirm native settlement.
     * Reopening or looking up the original cannot reconstruct permission to submit it again.
     * Unimplemented or incomplete native/history composition stays unavailable. */
    public suspend fun executeEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1, newPassword: CharArray? = null): LocalEmployeeCreationExecutionV1 =
        LocalEmployeeCreationExecutionV1.Unavailable
    /** Competes with creation on the same immutable original key. A fresh tombstone requires
     * the real protected writer and native settlement. A prior Applied result wins unchanged;
     * no new password, account replacement, login or implicit retry is introduced. */
    public suspend fun cancelEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationCancellationV1 =
        LocalEmployeeCreationCancellationV1.Unavailable
    /** Protected, no-effect input review only. A checked result cannot dispatch or recover a
     * creation. Missing providers remain unavailable; native creation is a separate operation. */
    public suspend fun reviewEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationReviewV1 = LocalEmployeeCreationReviewV1.Unavailable
    /** Read the exact retained creation original under the original creator's current
     * target-bound ADMINISTER grant, including after re-login. A missing provider refuses.
     * Current account contents never substitute for an original result; this does not create
     * an account or resolve native close/commit uncertainty. */
    public suspend fun lookupEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationStatusV1 = LocalEmployeeCreationStatusV1.Unavailable
}
