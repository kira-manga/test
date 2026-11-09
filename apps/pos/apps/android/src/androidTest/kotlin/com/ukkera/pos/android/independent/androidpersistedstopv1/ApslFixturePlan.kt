package com.ukkera.pos.android.independent.androidpersistedstopv1

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureSetup
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
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.BusinessCalendar
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.CutoffResolution
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.IanaTimeZone
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import java.security.SecureRandom
import kotlinx.datetime.LocalTime

/**
 * One synthetic installation for the selected APSL v2 segment. The actual native founding,
 * head/installer ceremonies, installation and current Master authority remain authoritative.
 * Cash/drawer identifiers are inputs only; the real CASH_* commands and current policy decide
 * whether they become state. The drawer opening is 1,000.00 EGP only as a synthetic custody
 * source; the frozen ALCR-MCART/UNKNOWN arithmetic is not asserted here.
 */
internal class ApslFixturePlan(val protection: SelfOwnedKeyProtectionV1) {
    val companyName = "APSL synthetic company"
    val branchName = "APSL synthetic branch"
    val installerName = "APSL synthetic installer"
    val installerLogin = "apsl-installer"
    val headName = "APSL synthetic named head"
    val headLogin = "apsl-head"
    val employeeLogin = LocalLoginIdentifier("apsl-a1")
    val authorityEpoch = Revision(1)

    val drawerId = DomainId.random()
    val cashSessionId = DomainId.random()
    val openingSourceId = DomainId.random()
    val openingEvidenceId = DomainId.random()
    val closingEvidenceId = DomainId.random()
    val openingCash = Money(Currency.EGP, 100_000)
    val calendar = BusinessCalendar(IanaTimeZone.AFRICA_CAIRO, LocalTime(4, 0),
        CutoffResolution.SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP)

    private val tokenPolicy = LocalTokenPolicy(accessLifetimeMillis = 30L * 60L * 1_000L,
        workLifetimeMillis = 8L * 60L * 60L * 1_000L)
    private val sessionPolicy = LocalSessionPolicy(maximumConcurrentSessions = 1)
    private val throttlePolicy = ThrottlePolicy(freeFailures = 3, initialDelayMillis = 1_000L,
        maximumDelayMillis = 900_000L, attemptTimeoutMillis = 120_000L)

    fun firstAccount(coordinates: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning =
        firstAccount(coordinates.scope, coordinates.firstActorId, coordinates.deviceId)

    fun firstAccount(branch: LocalSetupBranchReview): LocalAccountProvisioning =
        firstAccount(BranchScope(branch.organizationId, branch.companyId, branch.branchId),
            branch.firstActorId, branch.deviceId)

    private fun firstAccount(scope: BranchScope, actor: DomainId, device: DomainId): LocalAccountProvisioning {
        val grants = listOf(
            grant(scope, actor, SecurityAction.CASH_CONFIGURE, targets = setOf(actor, drawerId)),
            grant(scope, actor, SecurityAction.CASH_OPEN, targets = setOf(drawerId), amount = openingCash),
            grant(scope, actor, SecurityAction.CASH_READ, targets = setOf(actor, drawerId, cashSessionId)),
            grant(scope, actor, SecurityAction.CASH_CLOSE, targets = setOf(cashSessionId)),
            grant(scope, actor, SecurityAction.OPERATION_RESULT_RECOVERY),
            // Explicit current lifecycle authority for the authenticated STOP/RESUME ingress.
            // This is a synthetic test policy grant, never an inferred setup/UI/cashier role.
            grant(scope, actor, SecurityAction.MASTER_LIFECYCLE, PermissionVerb.ADMINISTER),
        )
        return LocalAccountProvisioning(actor, employeeLogin,
            ActivatedLocalPolicy(scope, actor, Revision(0), grants, sessionPolicy, throttlePolicy,
                approvalPolicy = null), tokenPolicy, setOf(device))
    }

    private fun grant(scope: BranchScope, actor: DomainId, action: SecurityAction,
        verb: PermissionVerb = PermissionVerb.PERFORM, targets: Set<DomainId>? = null,
        amount: Money? = null) = PermissionGrant(Permission(action, verb), scope, GrantEffect.ALLOW,
            PermissionLimits(maximumAmount = amount), OwnerAccess.OWN_ONLY, actor, targets, null)

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

/** Owned synthetic credentials; never serialized, printed or retained in evidence. */
internal class ApslSecrets {
    private val random = SecureRandom()
    val installer: CharArray = secret()
    val head: CharArray = secret()
    val employee: CharArray = secret()

    fun clear() {
        installer.fill('\u0000')
        head.fill('\u0000')
        employee.fill('\u0000')
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
        } finally { bytes.fill(0) }
    }
}
