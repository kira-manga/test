package com.ukkera.pos.android.independent.sasacksetupv1

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureSetup
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
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import java.security.SecureRandom

/** Frozen synthetic participants. None of these values is authority or a fixed product ID. */
internal enum class SasActor(val loginLabel: String) {
    US("setup"),
    UA("ack"),
    UG("go-live"),
    UR("read"),
    UB("ack-b"),
    UH("go-live-h"),
    UX("manager"),
    UV("revoked"),
}

/**
 * Synthetic S1 setup/authority inputs. The actual company-head signatures, native installation
 * and current master remain authoritative; this object only requests accounts and policies.
 * Actor US is the native first actor. Other actor IDs are generated before setup so the first
 * account can hold genuinely scoped administration grants without gaining any new ACK/read/
 * go-live action.
 */
internal class SasFixturePlan(val protection: SelfOwnedKeyProtectionV1) {
    val companyName = "SAS synthetic company"
    val branchName = "SAS synthetic branch"
    val installerName = "SAS synthetic installer"
    val installerLogin = "sas-installer"
    val headName = "SAS synthetic named head"
    val headLogin = "sas-head"
    val authorityEpoch = Revision(1)

    val tokenPolicy = LocalTokenPolicy(accessLifetimeMillis = 30L * 60L * 1_000L,
        workLifetimeMillis = 8L * 60L * 60L * 1_000L)
    private val sessionPolicy = LocalSessionPolicy(maximumConcurrentSessions = 2)
    private val throttlePolicy = ThrottlePolicy(freeFailures = 3, initialDelayMillis = 1_000L,
        maximumDelayMillis = 900_000L, attemptTimeoutMillis = 120_000L)
    private val generatedActorIds: Map<SasActor, DomainId> =
        SasActor.entries.filter { it != SasActor.US }.associateWith { DomainId.random() }

    fun actorId(actor: SasActor, coordinates: SelfOwnedAllocationCoordinatesV1): DomainId =
        if (actor == SasActor.US) coordinates.firstActorId else generatedActorIds.getValue(actor)

    fun login(actor: SasActor): LocalLoginIdentifier = LocalLoginIdentifier("sas-${actor.loginLabel}")

    fun allActorIds(coordinates: SelfOwnedAllocationCoordinatesV1): Set<DomainId> =
        generatedActorIds.values.toSet() + coordinates.firstActorId

    fun firstAccount(coordinates: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        val scope = coordinates.scope
        val first = coordinates.firstActorId
        val grants = listOf(
            grant(scope, first, SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER,
                allActorIds(coordinates)),
            grant(scope, first, SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER,
                setOf(coordinates.deviceId)),
            grant(scope, first, SecurityAction.OPERATION_RESULT_RECOVERY),
        )
        return LocalAccountProvisioning(first, login(SasActor.US),
            ActivatedLocalPolicy(scope, first, Revision(0), grants, sessionPolicy, throttlePolicy,
                approvalPolicy = null),
            tokenPolicy, setOf(coordinates.deviceId))
    }

    /** P0 baseline for every non-first participant: no ACK/read/go-live grant at revision zero. */
    fun account(actor: SasActor, coordinates: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        check(actor != SasActor.US)
        val scope = coordinates.scope
        val actorId = actorId(actor, coordinates)
        val grants = if (actor == SasActor.UX) listOf(
            grant(scope, actorId, SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER,
                allActorIds(coordinates)),
        ) else emptyList()
        return LocalAccountProvisioning(actorId, login(actor),
            ActivatedLocalPolicy(scope, actorId, Revision(0), grants, sessionPolicy, throttlePolicy,
                approvalPolicy = null),
            tokenPolicy, setOf(coordinates.deviceId))
    }

    /**
     * Exact next activated policy for one participant. Existing unrelated grants are preserved:
     * UX retains the administrator grant, and no new action is added for any other actor unless
     * it appears in [actions].
     */
    fun policy(actor: SasActor, coordinates: SelfOwnedAllocationCoordinatesV1, revision: Revision,
        actions: Set<SecurityAction>): ActivatedLocalPolicy {
        val scope = coordinates.scope
        val actorId = actorId(actor, coordinates)
        val grants = mutableListOf<PermissionGrant>()
        if (actor == SasActor.UX) grants += grant(scope, actorId,
            SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER,
            allActorIds(coordinates))
        for (action in actions) grants += grant(scope, actorId, action)
        return ActivatedLocalPolicy(scope, actorId, revision, grants, sessionPolicy, throttlePolicy,
            approvalPolicy = null)
    }


    /** Preserves the original first-account grants and adds only explicitly listed baseline grants. */
    fun baselinePolicy(coordinates: SelfOwnedAllocationCoordinatesV1, revision: Revision,
        extra: List<PermissionGrant>): ActivatedLocalPolicy {
        val base = firstAccount(coordinates).policy
        return ActivatedLocalPolicy(base.scope, base.actorId, revision, base.grants + extra,
            base.sessionPolicy, base.throttlePolicy, base.approvalPolicy)
    }

    fun baselineGrant(coordinates: SelfOwnedAllocationCoordinatesV1, action: SecurityAction,
        verb: PermissionVerb = PermissionVerb.PERFORM, amount: Money? = null,
        targets: Set<DomainId>? = null): PermissionGrant {
        val actor = coordinates.firstActorId
        return PermissionGrant(Permission(action, verb), coordinates.scope, GrantEffect.ALLOW,
            PermissionLimits(maximumAmount = amount), OwnerAccess.OWN_ONLY, actor, targets, null)
    }

    private fun grant(scope: BranchScope, actor: DomainId, action: SecurityAction,
        verb: PermissionVerb = PermissionVerb.PERFORM, targets: Set<DomainId>? = null) =
        PermissionGrant(Permission(action, verb), scope, GrantEffect.ALLOW, PermissionLimits(),
            OwnerAccess.OWN_ONLY, actor, targets, null)

    fun founding(): LocalCompanyFoundingFactsV1 = LocalCompanyFoundingFactsV1(
        SelfOwnedCompanyFactsV1(companyName, installerName, installerLogin, protection),
        headName, headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
        LocalCompanyHeadPurposeV1.entries.toSet())

    val setup: NativeLocalFixtureSetup = NativeLocalFixtureSetup(
        companyName = companyName,
        branchName = branchName,
        installerLogin = installerLogin,
        headLogin = headLogin,
        initialAuthorityEpoch = authorityEpoch,
        founding = { selected ->
            check(selected == protection)
            founding()
        },
        account = { coordinates -> firstAccount(coordinates) },
    )
}

/** Owned synthetic credentials. Never serialized, printed or retained by this fixture. */
internal class SasSecrets {
    private val random = SecureRandom()
    val installer: CharArray = secret()
    val head: CharArray = secret()
    private val actors: Map<SasActor, CharArray> = SasActor.entries.associateWith { secret() }

    operator fun get(actor: SasActor): CharArray = actors.getValue(actor)

    fun clear() {
        installer.fill('\u0000')
        head.fill('\u0000')
        actors.values.forEach { it.fill('\u0000') }
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
internal suspend fun <T> withSasSecret(original: CharArray, action: suspend (CharArray) -> T): T {
    val copy = original.copyOf()
    return try { action(copy) } finally { copy.fill('\u0000') }
}
