package com.ukkera.pos.android.independent.aosfsetupv2

import com.ukkera.pos.app.setup.LocalSetupBranchReview
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.security.SecureRandom

/**
 * One frozen AOSF installation family. A, C, D and U are separate installations, never clones.
 * C/D are the separate acknowledged-live/live-without-ACK distinction baselines.
 */
internal enum class AosfFixtureKind(val id: String) {
    A("AOSF-A"),
    C("AOSF-C"),
    D("AOSF-D"),
    U("AOSF-U"),
}

/**
 * Complete requested synthetic setup input for AOSF-A or AOSF-U. None of this is authority:
 * the actual company head/installer ceremonies and current master policy remain authoritative.
 * No literal OSR UUID is used; the production owner generates every installation ID.
 */
internal class AosfFixturePlan(
    val kind: AosfFixtureKind,
    val protection: SelfOwnedKeyProtectionV1,
) {
    val companyName = "AOSF synthetic ${kind.id} company"
    val branchName = "AOSF synthetic ${kind.id} branch"
    val installerName = "AOSF synthetic responsible installer"
    val installerLogin = "aosf-installer"
    val headName = "AOSF synthetic named head"
    val headLogin = "aosf-head"
    val authorityEpoch = Revision(1)
    val responsibleLogin = LocalLoginIdentifier("aosf-responsible-actor")
    val tokenPolicy = LocalTokenPolicy(accessLifetimeMillis = 30L * 60L * 1_000L,
        workLifetimeMillis = 8L * 60L * 60L * 1_000L)
    private val sessionPolicy = LocalSessionPolicy(maximumConcurrentSessions = 2)
    private val throttlePolicy = ThrottlePolicy(freeFailures = 3, initialDelayMillis = 1_000L,
        maximumDelayMillis = 900_000L, attemptTimeoutMillis = 120_000L)

    fun founding(): LocalCompanyFoundingFactsV1 = LocalCompanyFoundingFactsV1(
        SelfOwnedCompanyFactsV1(companyName, installerName, installerLogin, protection),
        headName, headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
        LocalCompanyHeadPurposeV1.entries.toSet())

    /** Requested responsible-setup role. It is approved only by the actual native installation. */
    fun firstAccount(branch: LocalSetupBranchReview): LocalAccountProvisioning {
        val scope = BranchScope(branch.organizationId, branch.companyId, branch.branchId)
        val actor = branch.firstActorId
        val grants = listOf(
            grant(scope, actor, SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER,
                setOf(actor)),
            grant(scope, actor, SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER,
                setOf(branch.deviceId)),
            grant(scope, actor, SecurityAction.OPERATION_RESULT_RECOVERY),
        )
        return LocalAccountProvisioning(actor, responsibleLogin,
            ActivatedLocalPolicy(scope, actor, Revision(0), grants, sessionPolicy, throttlePolicy,
                approvalPolicy = null),
            tokenPolicy, setOf(branch.deviceId))
    }

    /** Distinct AOSF-U logical-operation actor; eligible branch employee, not the setup actor. */
    fun distinctUncertainActor(branch: LocalSetupBranchReview): LocalAccountProvisioning {
        val scope = BranchScope(branch.organizationId, branch.companyId, branch.branchId)
        val suffix = DomainId.random().value.substring(0, 8)
        val actor = DomainId.random()
        check(actor != branch.firstActorId)
        val grants = listOf(grant(scope, actor, SecurityAction.OPERATION_RESULT_RECOVERY))
        return LocalAccountProvisioning(actor, LocalLoginIdentifier("aosf-uncertain-$suffix"),
            ActivatedLocalPolicy(scope, actor, Revision(0), grants, LocalSessionPolicy(1), throttlePolicy,
                approvalPolicy = null),
            tokenPolicy, setOf(branch.deviceId))
    }

    /**
     * Exact next activated policy for one already-provisioned synthetic actor. The existing
     * grants are preserved; only the explicitly requested setup-result actions are added.
     * Company-head approval and the actual sole-writer activation remain authoritative.
     */
    fun revisedPolicy(account: LocalAccountProvisioning, revision: Revision,
        actions: Set<SecurityAction>): ActivatedLocalPolicy {
        val base = account.policy
        return revisedPolicy(account, revision,
            actions.map { grant(base.scope, account.actorId, it) })
    }

    /** Exact next policy with caller-selected verbs/targets; existing unrelated grants stay. */
    fun revisedPolicy(account: LocalAccountProvisioning, revision: Revision,
        extra: List<PermissionGrant>): ActivatedLocalPolicy {
        val base = account.policy
        return ActivatedLocalPolicy(base.scope, account.actorId, revision, base.grants + extra,
            base.sessionPolicy, base.throttlePolicy, base.approvalPolicy)
    }

    /** One explicitly requested grant for this already-provisioned synthetic actor. */
    fun permissionGrant(account: LocalAccountProvisioning, action: SecurityAction,
        verb: PermissionVerb = PermissionVerb.PERFORM, targets: Set<DomainId>? = null): PermissionGrant =
        grant(account.policy.scope, account.actorId, action, verb, targets)

    private fun grant(scope: BranchScope, actor: DomainId, action: SecurityAction,
        verb: PermissionVerb = PermissionVerb.PERFORM, targets: Set<DomainId>? = null) =
        PermissionGrant(Permission(action, verb), scope, GrantEffect.ALLOW, PermissionLimits(),
            OwnerAccess.OWN_ONLY, actor, targets, null)

    companion object {
        const val setupDefinitionKey = "SETUP-SYNTH-ANDROID-001"
        const val setupDefinitionVersion = "1"
        const val currency = "EGP"
        const val businessTimezone = "Africa/Cairo"
    }
}

/** Owned synthetic credentials. Never serialized, printed or written to fixture evidence. */
internal class AosfSecrets {
    private val random = SecureRandom()
    val installer = secret()
    val head = secret()
    val responsible = secret()
    val uncertainActor = secret()

    fun clear() {
        installer.fill('\u0000')
        head.fill('\u0000')
        responsible.fill('\u0000')
        uncertainActor.fill('\u0000')
    }

    private fun secret(): CharArray {
        val bytes = ByteArray(24)
        val hex = "0123456789abcdef"
        return try {
            random.nextBytes(bytes)
            CharArray(bytes.size * 2) { index ->
                val value = bytes[index / 2].toInt() and 0xff
                hex[if (index % 2 == 0) value ushr 4 else value and 15]
            }
        } finally {
            bytes.fill(0)
        }
    }
}

/** One owned caller buffer for a production ceremony; cleared on every completion path. */
internal suspend fun <T> withAosfSecret(original: CharArray, action: suspend (CharArray) -> T): T {
    val copy = original.copyOf()
    return try { action(copy) } finally { copy.fill('\u0000') }
}
