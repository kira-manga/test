package com.ukkera.pos.security.frozen.companyheadv2

import com.ukkera.pos.security.AndroidOwnedInstallationWork
import com.ukkera.pos.security.AndroidOwnedResult
import com.ukkera.pos.security.AndroidOwnedTask
import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedDesktopCompanyHeadV2
import com.ukkera.pos.security.SelfOwnedDesktopHeadCeremonyV2
import com.ukkera.pos.security.SelfOwnedDesktopProvisionerV2
import com.ukkera.pos.security.SelfOwnedFailureV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.desktopseamv2.DesktopSeamV2Double
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Frozen expectations for the Windows/macOS named-head ceremonies (WP-C4), authored on
 * 13 November 2026 BEFORE [SelfOwnedDesktopCompanyHeadV2] existed, from the Android V1 head
 * authentication semantics (durable penalty before derivation, full cooldown re-armed on a later
 * boot, purpose withdrawal, approval bound to boot/revisions). Driven over the jvmTest-only
 * [DesktopSeamV2Double] with every stage inside an admitted owned-work run. Synthetic DATA only;
 * software keys prove no native custody. NOT independent (same author, owner instruction).
 */
class DesktopCompanyHeadV2FrozenTest {
    private val facts = SelfOwnedCompanyFactsV1("Synthetic Company", "Synthetic Person", "installer.one",
        SelfOwnedKeyProtectionV1.OS_ISOLATED)
    private val founding = LocalCompanyFoundingFactsV1(facts, "Synthetic Head", "head.one",
        LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, LocalCompanyHeadPurposeV1.entries.toSet())
    private val headSecret = "synthetic-head-pass-2026"
    private val action = DomainId("0f1e2d3c-4b5a-4968-8776-655443322110")
    private val digest = PayloadDigest("a".repeat(64))
    private val far = Long.MAX_VALUE / 4

    private fun founded(): DesktopSeamV2Double {
        val seam = DesktopSeamV2Double()
        val work = seam.openWork()
        try {
            val p = SelfOwnedDesktopProvisionerV2(seam.environment, seam.store, seam.keys, work)
            p.completeNewCompanyAndHead(p.prepareNewCompanyAndHead(founding), "synthetic-installer-pass".toCharArray(),
                headSecret.toCharArray())
        } finally { seam.closeWork(work) }
        return seam
    }

    /** One owned work lifetime and one head owner; every [stage] is a separately admitted run. */
    private class Session(val seam: DesktopSeamV2Double) {
        val work: AndroidOwnedInstallationWork = seam.openWork()
        val head: SelfOwnedDesktopCompanyHeadV2 = try {
            SelfOwnedDesktopCompanyHeadV2(seam.environment, seam.store, seam.keys, work)
        } catch (failure: Throwable) { seam.closeWork(work); throw failure }
        fun <T> stage(block: (SelfOwnedDesktopCompanyHeadV2) -> T): AndroidOwnedResult<T> = runBlocking {
            work.run {
                object : AndroidOwnedTask<T> {
                    override fun execute(): T = block(head)
                    override fun discard(value: T) = Unit
                    override fun clearInputs() = Unit
                }
            }
        }
        fun close() { head.close(); seam.closeWork(work) }
    }

    private fun <T> session(seam: DesktopSeamV2Double, block: (Session) -> T): T {
        val session = Session(seam)
        try { return block(session) } finally { session.close() }
    }

    private fun <T> value(result: AndroidOwnedResult<T>): T = assertIs<AndroidOwnedResult.Value<T>>(result).value
    private fun reason(result: AndroidOwnedResult<*>): SelfOwnedInstallationFailureV1 =
        assertIs<AndroidOwnedResult.Unavailable>(result).reason

    private fun auth(s: Session, ceremony: SelfOwnedDesktopHeadCeremonyV2, secret: String = headSecret, login: String = "head.one") =
        s.stage { it.authenticate(ceremony, action, digest, login, secret.toCharArray(), far) }

    @Test
    fun everyCeremonyAuthenticatesTheNamedHeadAndStaysCurrentInTheSameBoot() {
        val seam = founded()
        session(seam) { s ->
            SelfOwnedDesktopHeadCeremonyV2.entries.forEach { ceremony ->
                val approval = value(auth(s, ceremony))
                value(s.stage { h -> h.requireStillCurrent(approval, h.readState()!!, ceremony, action, digest) })
                // Bound to the ceremony, action and digest.
                val other = SelfOwnedDesktopHeadCeremonyV2.entries.first { it != ceremony }
                assertEquals(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED,
                    reason(s.stage { h -> h.requireStillCurrent(approval, h.readState()!!, other, action, digest) }))
                assertEquals(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED, reason(s.stage { h ->
                    h.requireStillCurrent(approval, h.readState()!!, ceremony, action, PayloadDigest("b".repeat(64))) }))
            }
            val head = value(s.stage { it.readState()!! }).head
            assertEquals(0, head.failedAttempts); assertEquals(null, head.reservationId)
        }
    }

    @Test
    fun aWrongSecretRetainsTheDurablePenaltyAndThrottlesUntilTheCooldownElapses() {
        val seam = founded()
        session(seam) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED,
                reason(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT, secret = "wrong-synthetic-pass-1")))
            val penalized = value(s.stage { it.readState()!! }).head
            assertEquals(1, penalized.failedAttempts); assertEquals(null, penalized.reservationId)
            assertTrue(penalized.cooldownMillis > 0L)
            assertEquals(SelfOwnedInstallationFailureV1.THROTTLED, reason(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT)))
            // A wrong login with the right secret is also refused.
            seam.elapsed.addAndGet(penalized.cooldownMillis)
            assertEquals(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED,
                reason(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT, login = "head.two")))
            val second = value(s.stage { it.readState()!! }).head
            assertEquals(2, second.failedAttempts)
            seam.elapsed.addAndGet(second.cooldownMillis)
            value(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT))
            assertEquals(0, value(s.stage { it.readState()!! }).head.failedAttempts)
        }
    }

    @Test
    fun aLaterBootReArmsTheFullCooldownAndInvalidatesEarlierApprovals() {
        val seam = founded()
        val approval = session(seam) { s ->
            reason(auth(s, SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH, secret = "wrong-synthetic-pass-1"))
            seam.elapsed.addAndGet(value(s.stage { it.readState()!! }).head.cooldownMillis)
            val approval = value(auth(s, SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH))
            seam.nextBoot()
            assertEquals(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED, reason(s.stage { h ->
                h.requireStillCurrent(approval, h.readState()!!, SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH, action, digest) }))
            approval
        }
        assertEquals("SelfOwnedDesktopHeadApprovalV2([REDACTED])", approval.toString())
        session(seam) { s ->
            // Penalty after the success was cleared; a new failure at boot 1 then a reboot re-arms it fully.
            reason(auth(s, SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH, secret = "wrong-synthetic-pass-2"))
        }
        seam.nextBoot()
        session(seam) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.THROTTLED, reason(auth(s, SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH)))
            val head = value(s.stage { it.readState()!! }).head
            assertEquals(2, head.throttleBoot); assertEquals(1, head.failedAttempts)
            assertEquals(seam.elapsed.get() + head.cooldownMillis, head.nextAllowedElapsedMillis)
            seam.elapsed.addAndGet(head.cooldownMillis)
            value(auth(s, SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH))
        }
    }

    @Test
    fun withdrawalIsDurableRevokesApprovalsAndRefusesNewCeremonies() {
        val seam = founded()
        session(seam) { s ->
            val policy = value(auth(s, SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH))
            val enrollment = value(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT))
            value(s.stage { it.withdrawHeadIssuance(LocalCompanyHeadPurposeV1.POLICY_PUBLISH) })
            assertEquals(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN, reason(s.stage { h ->
                h.requireStillCurrent(policy, h.readState()!!, SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH, action, digest) }))
            // A purpose withdrawal leaves the separate ceremonies current.
            value(s.stage { h -> h.requireStillCurrent(enrollment, h.readState()!!,
                SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT, action, digest) })
        }
        // Durable: a new owner (no in-memory gate) still refuses the withdrawn purpose.
        session(seam) { s ->
            val head = value(s.stage { it.readState()!! }).head
            assertFalse(head.policyEnabled); assertEquals(1L, head.policyRevision); assertTrue(head.enabled)
            assertEquals(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN, reason(auth(s, SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH)))
            val enrollment = value(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT))
            value(s.stage { it.withdrawHeadIssuance(null) })
            assertEquals(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN, reason(s.stage { h ->
                h.requireStillCurrent(enrollment, h.readState()!!, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT, action, digest) }))
        }
        session(seam) { s ->
            val head = value(s.stage { it.readState()!! }).head
            assertFalse(head.enabled); assertEquals(1L, head.accountRevision)
            SelfOwnedDesktopHeadCeremonyV2.entries.forEach { ceremony ->
                assertEquals(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN, reason(auth(s, ceremony)))
            }
        }
    }

    @Test
    fun tamperedContainerAndRolledBackBootChainFailClosed() {
        val seam = founded()
        val bytes = seam.envelopeBytes!!
        seam.envelopeBytes = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        session(seam) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT)))
        }
        seam.envelopeBytes = bytes
        seam.nextBoot()
        session(seam) { s -> value(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_POLICY_ACTIVATION)) }
        // The head throttle boot is 1; a consistent but rolled-back chain (boot 0) is uncertain.
        val live = seam.chain.toList()
        seam.presentedChain = { live.dropLast(1) }; seam.presentedCurrent = { live[live.size - 2] }
        session(seam) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT)))
        }
        // A truncated chain that no longer reaches the presented current ledger is uncertain too.
        seam.presentedChain = { live.drop(1) }; seam.presentedCurrent = null
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, assertFailsWith<SelfOwnedFailureV1> { Session(seam) }.reason)
        seam.presentedChain = null
        session(seam) { s -> value(auth(s, SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT)) }
    }
}
