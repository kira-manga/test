package com.ukkera.pos.android.independent.nativefixture

import com.ukkera.pos.security.*

/** Existing native fixture assertions/secret lifetime, shared without changing their outcomes. */
internal suspend fun <T> nativeFixtureSecret(original: CharArray, action: suspend (CharArray) -> T): T {
    val copy = original.copyOf()
    return try { action(copy) } finally { copy.fill('\u0000') }
}

internal fun <T> nativeFixtureValue(result: LocalCompanyAuthorityResult<T>): T =
    (result as? LocalCompanyAuthorityResult.Value)?.value
        ?: error("The genuine original native authority is unavailable")

internal fun requireSameNativeFixtureCoordinates(a: SelfOwnedAllocationCoordinatesV1, b: SelfOwnedAllocationCoordinatesV1) {
    check(a.scope == b.scope && a.deviceId == b.deviceId && a.allocationId == b.allocationId &&
        a.installationId == b.installationId && a.assignmentId == b.assignmentId &&
        a.storageInstanceId == b.storageInstanceId && a.storageKeyId == b.storageKeyId &&
        a.pepperKeyId == b.pepperKeyId && a.targetSigningKeyId == b.targetSigningKeyId &&
        a.firstActorId == b.firstActorId) { "The reopened native original changed" }
}

internal fun requireNativeFixturePolicy(expected: ActivatedLocalPolicy, actual: ActivatedLocalPolicy) {
    check(actual.scope == expected.scope && actual.actorId == expected.actorId && actual.revision == expected.revision &&
        actual.sessionPolicy == expected.sessionPolicy && actual.throttlePolicy == expected.throttlePolicy &&
        actual.approvalPolicy == expected.approvalPolicy)
    fun grantKey(grant: PermissionGrant): List<Any?> = listOf(grant.permission, grant.scope, grant.effect,
        grant.limits, grant.ownerAccess, grant.namedSubjectId, grant.targetIds, grant.delegationCeiling)
    check(actual.grants.map(::grantKey).toSet() == expected.grants.map(::grantKey).toSet() &&
        actual.grants.size == expected.grants.size) { "Actual activated fixture grants differ" }
}
