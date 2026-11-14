package com.ukkera.pos.security.frozen.companycustodyv2

import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AndroidLocalCompanyIssuanceGate
import com.ukkera.pos.security.AndroidOwnedInstallationWork
import com.ukkera.pos.security.AndroidOwnedResult
import com.ukkera.pos.security.AndroidOwnedTask
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAuthConfiguration
import com.ukkera.pos.security.LocalAuthenticationObservation
import com.ukkera.pos.security.LocalBranchInstallationWireV2
import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalEmployeeEnrollmentDefinition
import com.ukkera.pos.security.LocalEmployeeEnrollmentPhase
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.NativeEmployeeEnrollmentApprovalV1
import com.ukkera.pos.security.NativeEmployeeEnrollmentHeadV1
import com.ukkera.pos.security.NativeEmployeeEnrollmentRecordV1
import com.ukkera.pos.security.NativeEmployeeEnrollmentStateV1
import com.ukkera.pos.security.NativeEmployeeEnrollmentTargetV1
import com.ukkera.pos.security.NativeEmployeeEnrollmentWireV1
import com.ukkera.pos.security.NativeHistoryRecordedHeadV2
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedCompanyLedgerKindV2
import com.ukkera.pos.security.SelfOwnedDesktopCompanyCustodyV2
import com.ukkera.pos.security.SelfOwnedDesktopProvisionerV2
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedNativeCompanyLedgerWireV2
import com.ukkera.pos.security.SelfOwnedNativeKeyRoleV1
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.desktopseamv2.DesktopSeamV2Double
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen expectations for the Windows/macOS company workflow custody (V2 ledgers, root
 * endorsements and the employee-enrollment head ceremony), written 13 November 2026 from the
 * Android V1 registry ledger and key-owner semantics, before the custody was first executed. Each
 * stage runs inside an admitted owned-work run of [DesktopSeamV2Double]. Synthetic DATA only;
 * software keys prove no native custody. NOT independent (same author, owner instruction).
 */
class DesktopCompanyCustodyV2FrozenTest {
    private val facts = SelfOwnedCompanyFactsV1("Synthetic Company", "Synthetic Person", "installer.one",
        SelfOwnedKeyProtectionV1.OS_ISOLATED)
    private val founding = LocalCompanyFoundingFactsV1(facts, "Synthetic Head", "head.one",
        LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, LocalCompanyHeadPurposeV1.entries.toSet())
    private val headSecret = "synthetic-head-pass-2026"
    private var counter = 0x100
    private fun id(): DomainId = DomainId("00000000-0000-4000-8000-%012x".format(counter++))
    private fun digest(c: Char) = PayloadDigest(c.toString().repeat(64))

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

    private class Session(val seam: DesktopSeamV2Double) {
        val work: AndroidOwnedInstallationWork = seam.openWork()
        val custody = try { SelfOwnedDesktopCompanyCustodyV2(seam.environment, seam.store, seam.keys, work, AndroidLocalCompanyIssuanceGate()) }
            catch (failure: Throwable) { seam.closeWork(work); throw failure }
        fun <T> stage(block: (SelfOwnedDesktopCompanyCustodyV2) -> T): AndroidOwnedResult<T> = runBlocking {
            work.run {
                object : AndroidOwnedTask<T> {
                    override fun execute(): T = block(custody)
                    override fun discard(value: T) = Unit
                    override fun clearInputs() = Unit
                }
            }
        }
        fun close() { seam.closeWork(work) }
    }

    private fun <T> session(seam: DesktopSeamV2Double, block: (Session) -> T): T {
        val session = Session(seam)
        try { return block(session) } finally { session.close() }
    }

    private fun <T> value(result: AndroidOwnedResult<T>): T = assertIs<AndroidOwnedResult.Value<T>>(result).value
    private fun reason(result: AndroidOwnedResult<*>): SelfOwnedInstallationFailureV1 =
        assertIs<AndroidOwnedResult.Unavailable>(result).reason

    /** Synthetic PREPARED enrollment record targeting the actual founded company (or [fingerprint]). */
    private fun prepared(custody: SelfOwnedDesktopCompanyCustodyV2, bootCount: Int, fingerprint: String? = null): NativeEmployeeEnrollmentRecordV1 {
        val original = checkNotNull(custody.companyOriginal())
        val state = checkNotNull(custody.companyState())
        val scope = BranchScope(original.original.organizationId, original.original.companyId, id())
        val device = id(); val firstActor = id(); val actor = id()
        val coordinates = SelfOwnedAllocationCoordinatesV1(id(), scope, id(), id(), device, id(), id(), id(), id(), firstActor)
        val policy = ActivatedLocalPolicy(scope, actor, Revision(0), emptyList(), LocalSessionPolicy(), ThrottlePolicy())
        val login = LocalLoginIdentifier("employee.one")
        val tokens = LocalTokenPolicy(60_000L, 120_000L)
        val definition = LocalEmployeeEnrollmentDefinition.capture(
            requestId = id(),
            definition = LocalAccountProvisioning(actor, login, policy, tokens, setOf(device)),
            credentialWasProvided = true,
            canonicalPolicy = LocalBranchInstallationWireV2.encodePolicy(policy),
            canonicalConfiguration = LocalBranchInstallationWireV2.encodeConfiguration(
                LocalAuthConfiguration(scope, actor, Revision(0), login, tokens)),
            retainedAdmissions = listOf(LocalDeviceAdmission(scope, device, Revision(0), true, "synthetic-device", null, emptySet())),
        )
        val root = state.original.root.copyWireBytes()
        val rootDigest = try { sha256(root) } finally { root.fill(0) }
        val parent = NativeHistoryRecordedHeadV2(1L, digest('1'), digest('2'), 0L, 1L, digest('3'), digest('4'), digest('5'))
        val target = NativeEmployeeEnrollmentTargetV1(coordinates, digest('6'), digest('7'), id(), digest('8'), digest('9'), Revision(0),
            parent, digest('a'), original.original.rootId, original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId, rootDigest,
            original.original.actionId, original.headAuthorityId, original.headEnrollmentId, fingerprint ?: custody.rootFingerprint())
        val observation = LocalAuthenticationObservation(id(), MonotonicMoment(id(), 1L))
        val head = NativeEmployeeEnrollmentHeadV1(facts.companyName, state.head.name, state.head.login, state.head.accountRevision,
            state.head.credentialRevision, state.original.revision, bootCount, observation, Long.MAX_VALUE / 4)
        return NativeEmployeeEnrollmentRecordV1(id(), id(), id(), id(), target, SelfOwnedOctetsV1(definition.copyWireBytes()), head,
            LocalEmployeeEnrollmentPhase.PREPARED)
    }

    @Test
    fun enrollmentLedgerRoundTripsAsAClosedV2FrameAndIsAbsentBeforeRetention() {
        val seam = founded()
        session(seam) { s ->
            assertNull(value(s.stage { it.readEmployeeEnrollments() }))
            val record = value(s.stage { prepared(it, seam.environment.bootCount) })
            val state = NativeEmployeeEnrollmentStateV1(0L, listOf(record))
            assertEquals(state, value(s.stage { it.retainEmployeeEnrollments(state) }))
            assertEquals(state, value(s.stage { it.readEmployeeEnrollments() }))
            val stored = checkNotNull(seam.ledgerBytes[SelfOwnedCompanyLedgerKindV2.EMPLOYEE_ENROLLMENTS])
            assertIs<com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2.Data<*>>(SelfOwnedNativeCompanyLedgerWireV2.decodeEnvelope(stored))
            // No other ledger was written and nothing is stored in the Android V1 grammar.
            assertEquals(setOf(SelfOwnedCompanyLedgerKindV2.EMPLOYEE_ENROLLMENTS), seam.ledgerBytes.keys)
        }
    }

    @Test
    fun tamperedOrSwappedLedgerBytesAreStateUncertain() {
        val seam = founded()
        // An uncertain refusal retires its owned work (by design), so each check gets its own run.
        val record = session(seam) { s ->
            value(s.stage { prepared(it, seam.environment.bootCount) }).also { record ->
                value(s.stage { it.retainEmployeeEnrollments(NativeEmployeeEnrollmentStateV1(0L, listOf(record))) })
            }
        }
        val genuine = checkNotNull(seam.ledgerBytes[SelfOwnedCompanyLedgerKindV2.EMPLOYEE_ENROLLMENTS]).copyOf()
        // A ledger of one kind presented as another kind (AAD/domain bound) is refused.
        seam.ledgerBytes[SelfOwnedCompanyLedgerKindV2.EMPLOYEE_POLICY_ACTIVATIONS] = genuine.copyOf()
        session(seam) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason(s.stage { it.readEmployeePolicyActivations() }))
        }
        seam.ledgerBytes.remove(SelfOwnedCompanyLedgerKindV2.EMPLOYEE_POLICY_ACTIVATIONS)
        // Any flipped byte (frame, ciphertext or signature) is refused.
        for (index in listOf(genuine.size / 2, genuine.size - 1)) {
            val tampered = genuine.copyOf(); tampered[index] = (tampered[index].toInt() xor 1).toByte()
            seam.ledgerBytes[SelfOwnedCompanyLedgerKindV2.EMPLOYEE_ENROLLMENTS] = tampered
            session(seam) { s ->
                assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason(s.stage { it.readEmployeeEnrollments() }))
            }
        }
        seam.ledgerBytes[SelfOwnedCompanyLedgerKindV2.EMPLOYEE_ENROLLMENTS] = genuine
        session(seam) { s -> assertEquals(record, value(s.stage { it.readEmployeeEnrollments() })!!.records.single()) }
    }

    @Test
    fun aRecordTargetingAnotherRootIsRefusedBeforeAnyWrite() {
        val seam = founded()
        session(seam) { s ->
            val record = value(s.stage { prepared(it, seam.environment.bootCount, fingerprint = "f".repeat(64)) })
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
                reason(s.stage { it.retainEmployeeEnrollments(NativeEmployeeEnrollmentStateV1(0L, listOf(record))) }))
            assertTrue(seam.ledgerBytes.isEmpty())
        }
    }

    @Test
    fun theRootSignsOnlySharedEndorsementMessages() {
        val seam = founded()
        val outsider = "not-an-endorsement".encodeToByteArray()
        val failure = runCatching { seam.keys.signRootEndorsement(outsider) }.exceptionOrNull()
        assertTrue(failure != null)
    }

    @Test
    fun theNamedHeadApprovesAndTheRootEndorsementVerifiesWithTheActualRootKey() {
        val seam = founded()
        session(seam) { s ->
            var record = value(s.stage { prepared(it, seam.environment.bootCount) })
            var state = NativeEmployeeEnrollmentStateV1(0L, listOf(record))
            value(s.stage { it.retainEmployeeEnrollments(state) })
            val action = NativeEmployeeEnrollmentWireV1.actionMessage(record)
            val actionDigest = try { sha256(action) } finally { action.fill(0) }
            // A wrong secret is refused and never yields an approval.
            assertEquals(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED, reason(s.stage {
                it.authenticateEmployeeEnrollment(record.originalActionId, actionDigest, "head.one", "wrong-synthetic-pass".toCharArray(),
                    record.preparedHead.deadlineElapsedMillis) }))
            seam.elapsed.addAndGet(value(s.stage { it.companyState()!!.head.cooldownMillis }))
            val approval = value(s.stage {
                it.authenticateEmployeeEnrollment(record.originalActionId, actionDigest, "head.one", headSecret.toCharArray(),
                    record.preparedHead.deadlineElapsedMillis) })
            // The prepared head revisions must match the approval; re-prepare against the current account.
            record = record.copy(phase = LocalEmployeeEnrollmentPhase.SIGNING_INTENT, approvedHead = NativeEmployeeEnrollmentApprovalV1(
                approval.accountRevision, approval.credentialRevision, approval.retainedRevision, approval.boot, approval.expires))
            state = state.next(record)
            value(s.stage { it.retainEmployeeEnrollments(state) })
            val signature = value(s.stage { it.signEmployeeEnrollmentEndorsement(record, approval) })
            assertEquals(64, signature.size)
            val approved = record.copy(phase = LocalEmployeeEnrollmentPhase.APPROVED, rootEndorsement = SelfOwnedOctetsV1(signature))
            value(s.stage { it.retainEmployeeEnrollments(state.next(approved)) })
            value(s.stage { it.verifyEmployeeEnrollment(approved) })
            // A forged endorsement (not the actual root key's signature) is refused.
            val forged = signature.copyOf(); forged[5] = (forged[5].toInt() xor 1).toByte()
            assertContentEquals(signature, value(s.stage { it.readEmployeeEnrollments() })!!.records.single().rootEndorsement.copyBytes())
            assertEquals(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN, reason(s.stage {
                it.verifyEmployeeEnrollment(approved.copy(rootEndorsement = SelfOwnedOctetsV1(forged))) }))
        }
    }
}
