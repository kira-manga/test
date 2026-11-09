package com.ukkera.pos.android.independent.aosfsetupv2

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.nativefixture.requireNativeFixturePolicy
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.database.setup.LocalSetupAcknowledgementRecordV1
import com.ukkera.pos.database.setup.LocalSetupResultCodecV1
import com.ukkera.pos.database.setup.LocalSetupResultProfileV1
import com.ukkera.pos.database.setup.LocalSetupResultProjectionV1
import com.ukkera.pos.database.setup.LocalSetupGoLiveRecordV1
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Frozen E3-003 Android original-setup fixture (AOSF v2). One selected case runs on a fresh
 * isolated nondebuggable localTesting installation. The actual AndroidLocalBranchService setup
 * path settles the original; AOSF-001/002/003/004/005/006 then drive the real current-action
 * granted protected setup read and, where frozen, the delivery ACK. AOSF-003 selects A/B, C or D
 * separately; AOSF-004 selects B (A) or C. The C/D distinction baselines are established through
 * the installed original-bound configuration/catalog/opening checkpoint commands and the real
 * responsible-actor go-live command. No readiness flag, mock provider or substitute result is
 * accepted. Every case stops with its honest unbound remainder; no whole frozen case is PASS.
 */
@RunWith(AndroidJUnit4::class)
class AosfOriginalSetupFixtureTest {
    @Test
    fun selectedFrozenCase(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val case = checkNotNull(arguments.getString("aosfCase")) {
            "Select one frozen aosfCase=AOSF-001..AOSF-006 explicitly"
        }
        val fixture = when (val value = arguments.getString("aosfFixture")) {
            AosfFixtureKind.A.id -> AosfFixtureKind.A
            AosfFixtureKind.C.id -> AosfFixtureKind.C
            AosfFixtureKind.D.id -> AosfFixtureKind.D
            AosfFixtureKind.U.id -> AosfFixtureKind.U
            else -> error("Select aosfFixture=AOSF-A, AOSF-C, AOSF-D or AOSF-U explicitly; got $value")
        }
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly"
        })
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing") {
            "AOSF requires the isolated com.ukkera.pos.local.testing application"
        }
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "The genuine native owner refuses a debuggable application"
        }
        val plan = AosfFixturePlan(fixture, protection)
        val evidence = AosfEvidence.claim(application, case, fixture, protection.name)
        val secrets = AosfSecrets()
        val driver = AosfSetupServiceDriver(application)
        var owner: AosfNativeOwner? = null
        var sessions: AosfSessionLifetime? = null
        var originalFailure: Throwable? = null
        try {
            when (case) {
                "AOSF-001" -> {
                    requireFixture(fixture, AosfFixtureKind.A, case)
                    val settled = prepareSettlement(application, driver, plan, secrets, evidence)
                    runAosf001(application, settled, plan, secrets.responsible, secrets.head,
                        evidence) { owner = it }
                    unboundAosf001(evidence)
                }
                "AOSF-002" -> {
                    requireFixture(fixture, AosfFixtureKind.A, case)
                    val settled = prepareSettlement(application, driver, plan, secrets, evidence)
                    runAosf002(application, settled, plan, secrets.responsible, secrets.head,
                        evidence) { owner = it }
                    unboundAosf002(evidence)
                }
                "AOSF-003" -> {
                    val settled = prepareSettlement(application, driver, plan, secrets, evidence)
                    when (fixture) {
                        AosfFixtureKind.A -> {
                            runAosf003(application, settled, plan, secrets, evidence) { owner = it }
                            unboundAosf003(evidence, "AOSF-A")
                        }
                        AosfFixtureKind.C -> {
                            runAosf003C(application, settled, plan, secrets, evidence) { owner = it }
                            unboundAosf003(evidence, "AOSF-C")
                        }
                        AosfFixtureKind.D -> {
                            runAosf003D(application, settled, plan, secrets, evidence) { owner = it }
                            unboundAosf003(evidence, "AOSF-D")
                        }
                        else -> error("AOSF-003 requires aosfFixture=AOSF-A, AOSF-C or AOSF-D")
                    }
                }
                "AOSF-004" -> {
                    val settled = prepareSettlement(application, driver, plan, secrets, evidence)
                    when (fixture) {
                        AosfFixtureKind.A -> {
                            runAosf004(application, settled, plan, secrets, evidence) { owner = it }
                            unboundAosf004(evidence, "AOSF-B")
                        }
                        AosfFixtureKind.C -> {
                            runAosf004C(application, settled, plan, secrets, evidence) { owner = it }
                            unboundAosf004(evidence, "AOSF-C")
                        }
                        else -> error("AOSF-004 requires aosfFixture=AOSF-A or AOSF-C")
                    }
                }
                "AOSF-005" -> {
                    requireFixture(fixture, AosfFixtureKind.A, case)
                    val settled = prepareSettlement(application, driver, plan, secrets, evidence)
                    runAosf005(application, settled, secrets, plan, evidence) { actualOwner, actualSessions ->
                        owner = actualOwner
                        sessions = actualSessions
                    }
                    unboundAosf005(evidence)
                }
                "AOSF-006" -> {
                    requireFixture(fixture, AosfFixtureKind.U, case)
                    val settled = prepareSettlement(application, driver, plan, secrets, evidence)
                    runAosf006(application, settled, plan, secrets, evidence) { owner = it }
                    unboundAosf006(evidence)
                }
                else -> error("Unknown frozen AOSF case $case")
            }
            evidence.status("INDEPENDENT_BINDINGS_EXECUTED_UNBOUND_REMAINDER_RETAINED")
            assumeTrue("Frozen AOSF $case retains required E3-005/E3-004 obligations; see unbound entries", false)
        } catch (assumption: AssumptionViolatedException) {
            throw assumption
        } catch (failure: Throwable) {
            originalFailure = failure
            evidence.fail(failure)
            throw failure
        } finally {
            try {
                cleanupAosf(
                    { sessions?.destroyAll() },
                    { owner?.let { check(it.closeOnlyWhenConfirmed()) { "Original owner close is unconfirmed" } } },
                    { driver.retire(evidence) },
                    { driver.close() },
                    { secrets.clear() },
                )
            } catch (cleanupFailure: Throwable) {
                originalFailure?.let { cleanupFailure.addSuppressed(it) }
                evidence.fail(cleanupFailure)
                throw cleanupFailure
            }
        }
    }

    private suspend fun prepareSettlement(application: Application, driver: AosfSetupServiceDriver,
        plan: AosfFixturePlan, secrets: AosfSecrets, evidence: AosfEvidence): AosfSetupOutcome.Installed {
        val outcome = driveAosfSetup(driver, plan, secrets.installer, secrets.head, secrets.responsible, evidence)
        driver.retire(evidence)
        driver.close()
        return when (outcome) {
            is AosfSetupOutcome.Installed -> outcome
            is AosfSetupOutcome.NativeAcknowledgementPending -> {
                evidence.status("BLOCKED_NATIVE_INSTALLATION_ACK_PENDING_E3_004")
                assumeTrue("Native installation acknowledgement pending; E3-004 owns explicit continuation", false)
                throw IllegalStateException("E3-004 native ACK continuation is required")
            }
        }
    }

    private suspend fun runAosf001(application: Application, settled: AosfSetupOutcome.Installed,
        plan: AosfFixturePlan, responsibleSecret: CharArray, headSecret: CharArray,
        evidence: AosfEvidence, retainOwner: (AosfNativeOwner) -> Unit) {
        val initial = readAosfNativeCut(application)
        initial.requireInitialSetupOnly()
        requireBranchCut(initial, evidence)
        evidence.bind("INITIAL_SETUP_CUT", initial.cutDigest)
        evidence.bind("INITIAL_BUSINESS_ROWS_ZERO", true)
        val savedReceipt = readAosfSavedReceipt(application)
        requireSavedReceipt(settled, savedReceipt)
        evidence.event("SAVED_ORIGINAL_RECEIPT_READBACK", JSONObject()
            .put("receiptDigest", savedReceipt.digest.value)
            .put("definitionDigest", savedReceipt.core.definitionDigest.value)
            .put("originalRequestId", savedReceipt.core.originalRequestId.value))

        val owner = AosfNativeOwner(application).also { retainOwner(it) }
        owner.openExistingOnly()
        val setup = owner.setup ?: owner.readOriginalSetup()
        val anchor = AosfAnchor.bind(settled, setup, checkNotNull(owner.original))
        requireFixedAnchor(anchor)
        evidence.bind("SETTLEMENT_ANCHOR", anchor.retained())
        evidence.event("ORIGINAL_SETUP_READBACK_MATCHED", JSONObject()
            .put("originalRequestId", anchor.setupRequestId)
            .put("receiptDigest", anchor.receiptDigest)
            .put("definitionDigest", anchor.definitionDigest)
            .put("readerIdentityUsed", false))
        owner.requireNoSecondWriter()
        evidence.event("ONE_ACTIVE_WRITER_OBSERVED")

        val sessions = AosfSessionLifetime(owner, evidence)
        try {
            val s1 = sessions.signIn(settled.account.login, responsibleSecret)
            val current = sessions.requireCurrent(s1, "AOSF-001 setup actor")
            check(current.actorId.value == anchor.actorId && current.scope.branchId.value == anchor.branchId &&
                current.deviceId.value == anchor.deviceId && current.login.value == anchor.actorLogin)
            requireNativeFixturePolicy(settled.account.policy, current.policy)
            check(AosfAnchor.policyFingerprint(current.policy) == anchor.roleFingerprint)
            evidence.event("SETUP_ACTOR_EXPLICIT_LOGIN", JSONObject()
                .put("actorId", current.actorId.value).put("sessionId", current.sessionId.value)
                .put("generation", current.generation.value).put("policyRevision", current.policy.revision.value))
            requireAosfReadDenied(probeAosfProtectedSetup(owner, sessions.credentials(s1), anchor),
                "AOSF-001 administrator before explicit read grant")
            evidence.event("ADMINISTRATIVE_ROLE_NOT_IMPLICIT_SETUP_READ", JSONObject()
                .put("actorId", current.actorId.value)
                .put("existingAdministrationGrantUsed", false))

            // The setup administrator grant is not an implicit setup-result read grant. Add only
            // the explicit action through the actual named-head policy ceremony, then read.
            val readerPolicy = activateAosfSetupPolicy(owner, plan, settled.account, Revision(1),
                setOf(SecurityAction.SETUP_RESULT_READ), headSecret, evidence)
            val reader = sessions.signIn(settled.account.login, responsibleSecret)
            requireAosfSetupActor(sessions, reader, anchor, readerPolicy, "AOSF-001 protected reader", evidence)
            val firstRead = readAosfProtectedSetup(owner, sessions, reader, anchor, readerPolicy.actorId,
                readerPolicy.revision, acknowledgement = null, label = "AOSF-001 read 1", evidence = evidence)
            val secondRead = readAosfProtectedSetup(owner, sessions, reader, anchor, readerPolicy.actorId,
                readerPolicy.revision, acknowledgement = null, label = "AOSF-001 read 2", evidence = evidence)
            check(firstRead == secondRead) { "Repeated protected setup reads changed the disclosed facts" }
            evidence.event("SETUP_RESULT_ACK_ABSENT_GO_LIVE_FALSE", JSONObject()
                .put("acknowledgement", "ABSENT").put("goLive", "ABSENT")
                .put("deliveryAckPerformed", false))
        } finally {
            sessions.destroyAll()
        }

        val readback = owner.readOriginalSetup()
        requireSameSetupReadback(anchor, readback)
        evidence.event("REPEATED_ORIGINAL_READBACK_NONMUTATING")
        owner.closeConfirmed()
        evidence.event("RUNTIME_OWNER_CONFIRMED_CLOSE", JSONObject()
            .put("closeConfirmedByProductionOwner", true).put("intentionalStopRequested", false))

        val final = readAosfNativeCut(application)
        final.requireSameSetupIdentity(initial)
        final.requireNoBusinessOrPending()
        check(final.count(com.ukkera.pos.security.NativeHistoryTableV2.BRANCH) == 1L &&
            final.count(com.ukkera.pos.security.NativeHistoryTableV2.ACCOUNT) == 1L &&
            final.count(com.ukkera.pos.security.NativeHistoryTableV2.POLICY) == 1L &&
            final.count(com.ukkera.pos.security.NativeHistoryTableV2.ADMISSION) == 1L)
        check(final.branchEpoch == 1L && final.branchWritable == 1L)
        evidence.bind("FINAL_SETUP_CUT", final.cutDigest)
    }

    private suspend fun runAosf002(application: Application, settled: AosfSetupOutcome.Installed,
        plan: AosfFixturePlan, responsibleSecret: CharArray, headSecret: CharArray,
        evidence: AosfEvidence, retainOwner: (AosfNativeOwner) -> Unit) {
        val initial = readAosfNativeCut(application)
        initial.requireInitialSetupOnly()
        requireBranchCut(initial, evidence)

        val owner = AosfNativeOwner(application).also { retainOwner(it) }
        owner.openExistingOnly()
        val first = owner.setup ?: owner.readOriginalSetup()
        val anchor = AosfAnchor.bind(settled, first, checkNotNull(owner.original))
        requireFixedAnchor(anchor)
        evidence.bind("SETTLEMENT_ANCHOR", anchor.retained())

        val sessions = AosfSessionLifetime(owner, evidence)
        val readerPolicy = activateAosfSetupPolicy(owner, plan, settled.account, Revision(1),
            setOf(SecurityAction.SETUP_RESULT_READ), headSecret, evidence)
        var reader = sessions.signIn(settled.account.login, responsibleSecret)
        requireAosfSetupActor(sessions, reader, anchor, readerPolicy, "AOSF-002 protected reader", evidence)
        owner.closeConfirmed()

        // The pre-read cut deliberately starts after the explicit policy grant and login, so the
        // remaining episode is read-only. A pre-close session may or may not be reusable after
        // reopen; both outcomes are recorded instead of being assumed.
        val before = readAosfNativeCut(application)
        before.requireNoBusinessOrPending()
        requireSetupOnlyRoots(before)
        evidence.bind("BEFORE_READ_CUT", before.cutDigest)

        owner.openExistingOnly()
        check(AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original)) == anchor)
        var resumed = sessions.current(reader) is LocalSessionViewResult.Current
        if (!resumed) {
            evidence.event("PRE_READ_SESSION_REJECTED_AFTER_REOPEN",
                JSONObject().put("explicitFreshLogin", true))
            reader = sessions.signIn(settled.account.login, responsibleSecret)
            requireAosfSetupActor(sessions, reader, anchor, readerPolicy,
                "AOSF-002 reader after reopen", evidence)
        } else {
            evidence.event("PRE_READ_SESSION_ACCEPTED_AFTER_REOPEN")
        }
        val firstRead = readAosfProtectedSetup(owner, sessions, reader, anchor, readerPolicy.actorId,
            readerPolicy.revision, acknowledgement = null, label = "AOSF-002 read 1", evidence = evidence)
        val withheld = readAosfProtectedSetup(owner, sessions, reader, anchor, readerPolicy.actorId,
            readerPolicy.revision, acknowledgement = null, label = "AOSF-002 lost-reply read", evidence = evidence)
        consumeWithheldProjection(withheld)
        evidence.event("ORIGINAL_SETUP_LOST_REPLY_WITHHELD")
        check(firstRead == withheld) { "The lost-reply read changed the disclosed original facts" }
        sessions.destroyAll()
        owner.closeConfirmed()

        val savedReceipt = readAosfSavedReceipt(application)
        requireSavedReceipt(settled, savedReceipt)
        evidence.event("SAVED_ORIGINAL_RECEIPT_READBACK", JSONObject()
            .put("receiptDigest", savedReceipt.digest.value)
            .put("definitionDigest", savedReceipt.core.definitionDigest.value)
            .put("originalRequestId", savedReceipt.core.originalRequestId.value))

        val after = readAosfNativeCut(application)
        if (resumed) {
            before.requireSameReadCut(after)
            evidence.event("REPEATED_PROTECTED_READBACK_ZERO_DELTA")
        } else {
            // A fresh login is an explicit action, so exact cut equality cannot isolate only the
            // read. Retain that limitation instead of presenting the session delta as a read.
            after.requireSameSetupAndPolicy(before)
            after.requireNoStockOrBusinessMovement(before)
            evidence.unbound("AOSF-002-READ-CUT",
                "The pre-close session was rejected after reopen, so an explicit fresh login occurred between the cuts; protected-read projection equality is asserted, but exact zero-delta native cut cannot be attributed to the read alone")
        }
        evidence.bind("AFTER_READ_CUT", after.cutDigest)
    }

    private suspend fun runAosf003(application: Application, settled: AosfSetupOutcome.Installed,
        plan: AosfFixturePlan, secrets: AosfSecrets, evidence: AosfEvidence,
        retainOwner: (AosfNativeOwner) -> Unit) {
        val initial = readAosfNativeCut(application)
        initial.requireInitialSetupOnly()
        requireBranchCut(initial, evidence)

        val owner = AosfNativeOwner(application).also { retainOwner(it) }
        owner.openExistingOnly()
        val anchor = AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original))
        requireFixedAnchor(anchor)
        evidence.bind("SETTLEMENT_ANCHOR", anchor.retained())

        val sessions = AosfSessionLifetime(owner, evidence)
        val policy = activateAosfSetupPolicy(owner, plan, settled.account, Revision(1),
            setOf(SecurityAction.SETUP_RESULT_READ, SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE),
            secrets.head, evidence)
        var reader = sessions.signIn(settled.account.login, secrets.responsible)
        requireAosfSetupActor(sessions, reader, anchor, policy, "AOSF-003 setup actor", evidence)
        owner.closeConfirmed()

        val beforeAck = readAosfNativeCut(application)
        beforeAck.requireNoBusinessOrPending()
        requireSetupOnlyRoots(beforeAck)
        evidence.bind("BEFORE_ACK_CUT", beforeAck.cutDigest)

        owner.openExistingOnly()
        check(AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original)) == anchor)
        var resumed = sessions.current(reader) is LocalSessionViewResult.Current
        if (!resumed) {
            evidence.event("ACK_SESSION_REJECTED_AFTER_REOPEN", JSONObject().put("explicitFreshLogin", true))
            reader = sessions.signIn(settled.account.login, secrets.responsible)
            requireAosfSetupActor(sessions, reader, anchor, policy, "AOSF-003 ACK actor after reopen", evidence)
        }
        val preAck = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement = null, label = "AOSF-003 settlement before ACK", evidence = evidence)
        check(preAck.acknowledgement == null && preAck.goLive == null) {
            "Settlement was not observable before the explicit delivery ACK"
        }
        evidence.event("SETTLEMENT_OBSERVED_BEFORE_ACK", JSONObject()
            .put("acknowledgement", "ABSENT").put("goLive", "ABSENT"))
        val acknowledgement = acknowledgeAosfDelivery(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, evidence)
        val firstRead = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement, "AOSF-B read 1", evidence)
        val secondRead = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement, "AOSF-B read 2", evidence)
        check(firstRead == secondRead) { "Repeated AOSF-B reads changed the committed ACK facts" }
        sessions.destroyAll()
        owner.closeConfirmed()

        val afterAck = readAosfNativeCut(application)
        if (resumed) afterAck.requireSameSetupAndSecurity(beforeAck)
        else afterAck.requireSameSetupAndPolicy(beforeAck)
        afterAck.requireNoStockOrBusinessMovement(beforeAck)
        check(afterAck.count(com.ukkera.pos.security.NativeHistoryTableV2.OPERATION) ==
            beforeAck.count(com.ukkera.pos.security.NativeHistoryTableV2.OPERATION) + 1L) {
            "The delivery ACK did not create exactly one operation result"
        }
        check(afterAck.count(com.ukkera.pos.security.NativeHistoryTableV2.AGGREGATE) ==
            beforeAck.count(com.ukkera.pos.security.NativeHistoryTableV2.AGGREGATE) + 1L) {
            "The delivery ACK did not create exactly one setup-result aggregate"
        }
        evidence.event("AOSF_B_AFTER_EXPLICIT_ACK", JSONObject()
            .put("acknowledgementOperationId", acknowledgement.operationId.value)
            .put("acknowledgementActorId", acknowledgement.actorId.value)
            .put("goLive", "ABSENT")
            .put("bookkeeping", afterAck.requireDeliveryAckBookkeeping(beforeAck)))
        evidence.bind("AOSF_B_ACKNOWLEDGEMENT", JSONObject()
            .put("operationId", acknowledgement.operationId.value)
            .put("actorId", acknowledgement.actorId.value)
            .put("policyRevision", acknowledgement.policyRevision.value)
            .put("masterAuthorityEpoch", acknowledgement.masterAuthorityEpoch.value)
            .put("acknowledgedAt", acknowledgement.acknowledgedAt.toString()))
        evidence.bind("AFTER_ACK_CUT", afterAck.cutDigest)

        // A separate reopen reads AOSF-B again; the pre-reopen cut isolates this read episode.
        owner.openExistingOnly()
        check(AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original)) == anchor)
        resumed = sessions.current(reader) is LocalSessionViewResult.Current
        if (!resumed) {
            evidence.event("AOSF_B_READ_SESSION_REJECTED_AFTER_REOPEN",
                JSONObject().put("explicitFreshLogin", true))
            reader = sessions.signIn(settled.account.login, secrets.responsible)
            requireAosfSetupActor(sessions, reader, anchor, policy, "AOSF-B reader after reopen", evidence)
        } else {
            evidence.event("AOSF_B_READ_SESSION_ACCEPTED_AFTER_REOPEN")
        }
        val afterReopenRead = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement, "AOSF-B read after reopen", evidence)
        check(afterReopenRead == firstRead) { "AOSF-B changed across confirmed close/reopen" }
        sessions.destroyAll()
        owner.closeConfirmed()

        val afterBRead = readAosfNativeCut(application)
        if (resumed) {
            afterAck.requireSameReadCut(afterBRead)
            evidence.event("AOSF_B_REOPENED_READ_ZERO_DELTA")
        } else {
            afterBRead.requireSameSetupAndPolicy(afterAck)
            afterBRead.requireNoStockOrBusinessMovement(afterAck)
            evidence.unbound("AOSF-003-READ-CUT",
                "The post-ACK session was rejected after reopen, so an explicit fresh login occurred between the cuts; AOSF-B projection equality is asserted, but exact zero-delta read cut is not attributed to the read alone")
        }
        evidence.bind("AFTER_B_READ_CUT", afterBRead.cutDigest)
    }

    /**
     * Frozen AOSF-003 on the separate AOSF-C installation: acknowledged and live by the original
     * responsible actor. The checkpoint chain is established by production commands, then the
     * explicit ACK and go-live are committed. The case observes the two facts and their durable
     * independent cut; the A/B run separately proves ACK does not imply go-live.
     */
    private suspend fun runAosf003C(application: Application, settled: AosfSetupOutcome.Installed,
        plan: AosfFixturePlan, secrets: AosfSecrets, evidence: AosfEvidence,
        retainOwner: (AosfNativeOwner) -> Unit) {
        val initial = readAosfNativeCut(application)
        initial.requireInitialSetupOnly()
        requireBranchCut(initial, evidence)

        val owner = AosfNativeOwner(application).also { retainOwner(it) }
        owner.openExistingOnly()
        val anchor = AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original))
        requireFixedAnchor(anchor)
        evidence.bind("SETTLEMENT_ANCHOR", anchor.retained())

        val sessions = AosfSessionLifetime(owner, evidence)
        val policy = activateAosfLivePolicy(owner, plan, settled, acknowledge = true, goLive = true,
            headSecret = secrets.head, evidence = evidence)
        val reader = sessions.signIn(settled.account.login, secrets.responsible)
        requireAosfSetupActor(sessions, reader, anchor, policy, "AOSF-003-C setup actor", evidence)
        val checkpoint = prepareAosfCheckpointBaseline(owner, plan, settled, anchor, sessions, reader,
            policy, secrets.head, evidence)
        evidence.bind("AOSF_C_CHECKPOINT_BASE", checkpoint.facts())

        val acknowledgement = acknowledgeAosfDeliveryRecorded(owner, sessions, reader, anchor,
            policy.actorId, policy.revision, evidence)
        val goLive = goLiveAosfBusiness(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, evidence)
        val first = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement.record, "AOSF-C read 1", evidence, goLive.record)
        val second = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement.record, "AOSF-C read 2", evidence, goLive.record)
        check(first == second) { "Repeated AOSF-C reads changed the acknowledged go-live facts" }
        replayAosfSetupAction(owner, sessions, reader, LocalSetupResultCodecV1.acknowledge(
            acknowledgement.record.operationId, anchor.installation), acknowledgement.recorded,
            "AOSF-003-C ACK", evidence)
        replayAosfSetupAction(owner, sessions, reader, LocalSetupResultCodecV1.goLive(
            goLive.record.operationId, anchor.installation), goLive.recorded,
            "AOSF-003-C go-live", evidence)
        owner.closeConfirmed()

        val savedReceipt = readAosfSavedReceipt(application)
        requireSavedReceipt(settled, savedReceipt, anchor.attemptSequence)
        val baselineCut = readAosfNativeCut(application)
        baselineCut.requireSameSetupIdentity(initial)
        check(!baselineCut.pending) { "The AOSF-C baseline retains an unresolved operation" }
        baselineCut.requireZeroBusinessLedger()
        check(baselineCut.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size == 1 &&
            baselineCut.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size == 1) {
            "The AOSF-C baseline does not hold exactly one ACK and one go-live fact"
        }
        check(baselineCut.operations.count {
            it.operationId == acknowledgement.record.operationId.value &&
                it.actorId == anchor.actorId &&
                it.command == LocalSetupResultProfileV1.ACKNOWLEDGE.name
        } == 1 && baselineCut.operations.count {
            it.operationId == goLive.record.operationId.value && it.actorId == anchor.actorId &&
                it.command == LocalSetupResultProfileV1.GO_LIVE.name
        } == 1) { "The AOSF-C durable operation/actor identity is absent" }
        evidence.bind("AOSF_C_ACKNOWLEDGEMENT", acknowledgementFacts(acknowledgement.record))
        evidence.bind("AOSF_C_GO_LIVE", goLiveFacts(goLive.record))
        evidence.bind("AOSF_C_CUT", baselineCut.cutDigest)
        evidence.event("AOSF_C_ACKNOWLEDGED_LIVE", JSONObject()
            .put("acknowledgementOperationId", acknowledgement.record.operationId.value)
            .put("goLiveOperationId", goLive.record.operationId.value)
            .put("actorId", anchor.actorId)
            .put("businessLedgerZero", true)
            .put("goLivePrerequisitesAllSettled", goLive.record.prerequisites.all { it.settled }))

        // Independent read episode after a real confirmed close/existing-only reopen.
        owner.openExistingOnly()
        check(AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original)) == anchor)
        var resumed = sessions.current(reader) is LocalSessionViewResult.Current
        if (!resumed) {
            evidence.event("AOSF_C_READ_SESSION_REJECTED_AFTER_REOPEN",
                JSONObject().put("explicitFreshLogin", true))
            val fresh = sessions.signIn(settled.account.login, secrets.responsible)
            requireAosfSetupActor(sessions, fresh, anchor, policy,
                "AOSF-C reader after reopen", evidence)
            val reopened = readAosfProtectedSetup(owner, sessions, fresh, anchor, policy.actorId,
                policy.revision, acknowledgement.record, "AOSF-C reopened read 1", evidence,
                goLive.record)
            val reopenedRepeated = readAosfProtectedSetup(owner, sessions, fresh, anchor,
                policy.actorId, policy.revision, acknowledgement.record,
                "AOSF-C reopened read 2", evidence, goLive.record)
            check(reopened == first && reopenedRepeated == first) {
                "The AOSF-C acknowledged live facts changed across confirmed close/reopen"
            }
        } else {
            val reopened = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
                policy.revision, acknowledgement.record, "AOSF-C reopened read 1", evidence,
                goLive.record)
            val reopenedRepeated = readAosfProtectedSetup(owner, sessions, reader, anchor,
                policy.actorId, policy.revision, acknowledgement.record,
                "AOSF-C reopened read 2", evidence, goLive.record)
            check(reopened == first && reopenedRepeated == first) {
                "The AOSF-C acknowledged live facts changed across confirmed close/reopen"
            }
        }
        sessions.destroyAll()
        owner.closeConfirmed()

        val afterRead = readAosfNativeCut(application)
        if (resumed) {
            baselineCut.requireSameReadCut(afterRead)
            evidence.event("AOSF_C_REOPENED_READ_ZERO_DELTA")
        } else {
            afterRead.requireNoSetupFactMovement(baselineCut)
            afterRead.requireSameSetupAndPolicy(baselineCut)
            evidence.unbound("AOSF-003-C-READ-CUT",
                "The AOSF-C session was rejected after reopen, so an explicit fresh login occurred between the cuts; ACK/go-live/checkpoint/business facts are unchanged, but exact record-state zero delta is not attributed to the read alone")
        }
        afterRead.requireZeroBusinessLedger()
        evidence.bind("AOSF_C_AFTER_READ_CUT", afterRead.cutDigest)
    }

    /**
     * Frozen AOSF-003 on the separate AOSF-D installation: live by the original responsible actor
     * with delivery ACK absent. The ACK is then committed and the same go-live actor/audit fact
     * must remain unchanged.
     */
    private suspend fun runAosf003D(application: Application, settled: AosfSetupOutcome.Installed,
        plan: AosfFixturePlan, secrets: AosfSecrets, evidence: AosfEvidence,
        retainOwner: (AosfNativeOwner) -> Unit) {
        val initial = readAosfNativeCut(application)
        initial.requireInitialSetupOnly()
        requireBranchCut(initial, evidence)

        val owner = AosfNativeOwner(application).also { retainOwner(it) }
        owner.openExistingOnly()
        val anchor = AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original))
        requireFixedAnchor(anchor)
        evidence.bind("SETTLEMENT_ANCHOR", anchor.retained())

        val sessions = AosfSessionLifetime(owner, evidence)
        val policy = activateAosfLivePolicy(owner, plan, settled, acknowledge = true, goLive = true,
            headSecret = secrets.head, evidence = evidence)
        var reader = sessions.signIn(settled.account.login, secrets.responsible)
        requireAosfSetupActor(sessions, reader, anchor, policy, "AOSF-003-D setup actor", evidence)
        val checkpoint = prepareAosfCheckpointBaseline(owner, plan, settled, anchor, sessions, reader,
            policy, secrets.head, evidence)
        evidence.bind("AOSF_D_CHECKPOINT_BASE", checkpoint.facts())

        val goLive = goLiveAosfBusiness(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, evidence)
        val liveWithoutAck = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement = null, label = "AOSF-D live without ACK",
            evidence = evidence, goLive = goLive.record)
        evidence.event("AOSF_D_LIVE_WITHOUT_ACK", JSONObject()
            .put("acknowledgement", "ABSENT")
            .put("goLiveOperationId", goLive.record.operationId.value)
            .put("actorId", goLive.record.actorId.value)
            .put("auditOccurredAt", goLive.record.occurredAt.toString()))
        owner.closeConfirmed()

        val savedReceipt = readAosfSavedReceipt(application)
        requireSavedReceipt(settled, savedReceipt, anchor.attemptSequence)
        val beforeAck = readAosfNativeCut(application)
        beforeAck.requireSameSetupIdentity(initial)
        check(!beforeAck.pending) { "The AOSF-D baseline retains an unresolved operation" }
        beforeAck.requireZeroBusinessLedger()
        check(beforeAck.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size == 1 &&
            beforeAck.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).isEmpty()) {
            "The AOSF-D live-without-ACK baseline is not exactly one go-live and no ACK"
        }
        evidence.bind("AOSF_D_LIVE_WITHOUT_ACK_CUT", beforeAck.cutDigest)

        owner.openExistingOnly()
        check(AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original)) == anchor)
        val resumed = sessions.current(reader) is LocalSessionViewResult.Current
        if (!resumed) {
            evidence.event("AOSF_D_ACK_SESSION_REJECTED_AFTER_REOPEN",
                JSONObject().put("explicitFreshLogin", true))
            reader = sessions.signIn(settled.account.login, secrets.responsible)
            requireAosfSetupActor(sessions, reader, anchor, policy,
                "AOSF-D ACK actor after reopen", evidence)
        }
        replayAosfSetupAction(owner, sessions, reader, LocalSetupResultCodecV1.goLive(
            goLive.record.operationId, anchor.installation), goLive.recorded,
            "AOSF-003-D go-live after reopen", evidence)
        val acknowledgement = acknowledgeAosfDeliveryRecorded(owner, sessions, reader, anchor,
            policy.actorId, policy.revision, evidence)
        val afterAckRead = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement.record, "AOSF-D ACK read 1", evidence, goLive.record)
        val afterAckReadRepeated = readAosfProtectedSetup(owner, sessions, reader, anchor,
            policy.actorId, policy.revision, acknowledgement.record, "AOSF-D ACK read 2",
            evidence, goLive.record)
        check(afterAckRead == afterAckReadRepeated && afterAckRead.goLive == liveWithoutAck.goLive) {
            "The explicit ACK changed the AOSF-D go-live actor/audit fact"
        }
        replayAosfSetupAction(owner, sessions, reader, LocalSetupResultCodecV1.acknowledge(
            acknowledgement.record.operationId, anchor.installation), acknowledgement.recorded,
            "AOSF-003-D ACK", evidence)
        sessions.destroyAll()
        owner.closeConfirmed()

        val afterAck = readAosfNativeCut(application)
        afterAck.requireOneAcknowledgement(beforeAck, DomainId(anchor.actorId),
            acknowledgement.record.operationId)
        afterAck.requireZeroBusinessLedger()
        check(afterAck.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size == 1) {
            "The explicit ACK removed or duplicated the AOSF-D go-live"
        }
        evidence.bind("AOSF_D_ACKNOWLEDGEMENT", acknowledgementFacts(acknowledgement.record))
        evidence.bind("AOSF_D_GO_LIVE", goLiveFacts(goLive.record))
        evidence.bind("AOSF_D_AFTER_ACK_CUT", afterAck.cutDigest)
    }

    private suspend fun activateAosfLivePolicy(
        owner: AosfNativeOwner,
        plan: AosfFixturePlan,
        settled: AosfSetupOutcome.Installed,
        acknowledge: Boolean,
        goLive: Boolean,
        headSecret: CharArray,
        evidence: AosfEvidence,
    ): com.ukkera.pos.security.ActivatedLocalPolicy {
        val actions = mutableSetOf(
            SecurityAction.SETUP_CONFIGURATION_SETTLE,
            SecurityAction.SETUP_CATALOG_IMPORT_SETTLE,
            SecurityAction.SETUP_OPENING_SETTLE,
            SecurityAction.SETUP_CHECKPOINTS_READ,
            SecurityAction.BRANCH_ACTIVITY_ACTIVATE,
            SecurityAction.SETUP_RESULT_READ,
        )
        if (acknowledge) actions += SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE
        if (goLive) actions += SecurityAction.SETUP_BUSINESS_GO_LIVE
        return activateAosfCheckpointPolicy(owner, plan, settled.account, Revision(1), actions,
            headSecret, evidence)
    }

    private suspend fun runAosf004(application: Application, settled: AosfSetupOutcome.Installed,
        plan: AosfFixturePlan, secrets: AosfSecrets, evidence: AosfEvidence,
        retainOwner: (AosfNativeOwner) -> Unit) {
        // This fresh AOSF-A installation is advanced to frozen AOSF-B by one explicit real ACK.
        val owner = AosfNativeOwner(application).also { retainOwner(it) }
        owner.openExistingOnly()
        val anchor = AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original))
        requireFixedAnchor(anchor)
        evidence.bind("SETTLEMENT_ANCHOR", anchor.retained())
        owner.requireNoSecondWriter()
        evidence.event("ONE_ACTIVE_WRITER_OBSERVED")

        val sessions = AosfSessionLifetime(owner, evidence)
        val policy = activateAosfSetupPolicy(owner, plan, settled.account, Revision(1),
            setOf(SecurityAction.SETUP_RESULT_READ, SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE),
            secrets.head, evidence)
        var reader = sessions.signIn(settled.account.login, secrets.responsible)
        requireAosfSetupActor(sessions, reader, anchor, policy, "AOSF-004 setup actor", evidence)
        val acknowledgement = acknowledgeAosfDelivery(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, evidence)
        val ackRead = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement, "AOSF-B pre-close read", evidence)
        check(ackRead.goLive == null)
        evidence.event("AOSF_B_PRODUCED_IN_THIS_FRESH_INSTALLATION", JSONObject()
            .put("acknowledgementOperationId", acknowledgement.operationId.value)
            .put("goLive", "ABSENT"))
        sessions.destroyAll()
        owner.closeConfirmed()
        evidence.event("CONFIRMED_NATIVE_CLOSE", JSONObject()
            .put("closeConfirmedByProductionOwner", true).put("intentionalStopRequested", false))

        val before = readAosfNativeCut(application)
        requireSetupOnlyRoots(before)
        evidence.bind("AOSF_B_CUT", before.cutDigest)

        owner.openExistingOnly()
        val reopened = owner.setup ?: owner.readOriginalSetup()
        check(AosfAnchor.bind(settled, reopened, checkNotNull(owner.original)) == anchor)
        evidence.event("RUNTIME_OWNER_REACQUIRED", JSONObject()
            .put("serviceIncarnationId", owner.original?.serviceIncarnationId?.value)
            .put("sameAnchor", true))
        var resumed = sessions.current(reader) is LocalSessionViewResult.Current
        if (!resumed) {
            evidence.event("POST_REOPEN_SESSION_REJECTED", JSONObject().put("explicitFreshLogin", true))
            reader = sessions.signIn(settled.account.login, secrets.responsible)
            requireAosfSetupActor(sessions, reader, anchor, policy, "AOSF-004 reader after reopen", evidence)
        } else {
            evidence.event("POST_REOPEN_SESSION_ACCEPTED")
        }
        val postReopen = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement, "AOSF-B read after reopen", evidence)
        check(postReopen == ackRead) { "AOSF-B protected read changed across confirmed close/reopen" }
        requireSameSetupReadback(anchor, owner.readOriginalSetup())
        evidence.event("POST_REOPEN_ORIGINAL_READBACK_MATCHED")
        sessions.destroyAll()
        owner.closeConfirmed()

        val after = readAosfNativeCut(application)
        check(before.count(com.ukkera.pos.security.NativeHistoryTableV2.OPERATION) ==
            after.count(com.ukkera.pos.security.NativeHistoryTableV2.OPERATION) &&
            before.count(com.ukkera.pos.security.NativeHistoryTableV2.AGGREGATE) ==
            after.count(com.ukkera.pos.security.NativeHistoryTableV2.AGGREGATE))
        if (resumed) {
            before.requireSameReadCut(after)
            evidence.event("AOSF_B_REOPEN_READ_ZERO_DELTA")
        } else {
            after.requireSameSetupAndPolicy(before)
            after.requireNoStockOrBusinessMovement(before)
            evidence.unbound("AOSF-004-READ-CUT",
                "The AOSF-B session was rejected after reopen, so an explicit fresh login occurred between the cuts; the protected projection/ACK identity is asserted, but exact zero-delta read cut is not attributed to the read alone")
        }
        evidence.bind("POST_REOPEN_CUT", after.cutDigest)
    }

    /**
     * Frozen AOSF-004 on the separate AOSF-C installation. Confirmed native-owner close and
     * existing-only offline reopen preserve the acknowledged live original; the original current
     * employee then explicitly logs in again and performs its own protected current-authority read.
     */
    private suspend fun runAosf004C(application: Application, settled: AosfSetupOutcome.Installed,
        plan: AosfFixturePlan, secrets: AosfSecrets, evidence: AosfEvidence,
        retainOwner: (AosfNativeOwner) -> Unit) {
        val initial = readAosfNativeCut(application)
        initial.requireInitialSetupOnly()
        requireBranchCut(initial, evidence)

        val owner = AosfNativeOwner(application).also { retainOwner(it) }
        owner.openExistingOnly()
        owner.requireNoSecondWriter()
        evidence.event("ONE_ACTIVE_WRITER_OBSERVED")
        val anchor = AosfAnchor.bind(settled, owner.setup ?: owner.readOriginalSetup(),
            checkNotNull(owner.original))
        requireFixedAnchor(anchor)
        evidence.bind("SETTLEMENT_ANCHOR", anchor.retained())

        val sessions = AosfSessionLifetime(owner, evidence)
        val policy = activateAosfLivePolicy(owner, plan, settled, acknowledge = true, goLive = true,
            headSecret = secrets.head, evidence = evidence)
        val reader = sessions.signIn(settled.account.login, secrets.responsible)
        requireAosfSetupActor(sessions, reader, anchor, policy, "AOSF-004-C setup actor", evidence)
        val checkpoint = prepareAosfCheckpointBaseline(owner, plan, settled, anchor, sessions, reader,
            policy, secrets.head, evidence)
        evidence.bind("AOSF_C_CHECKPOINT_BASE", checkpoint.facts())
        val acknowledgement = acknowledgeAosfDeliveryRecorded(owner, sessions, reader, anchor,
            policy.actorId, policy.revision, evidence)
        val goLive = goLiveAosfBusiness(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, evidence)
        evidence.bind("AOSF_C_ACKNOWLEDGEMENT", acknowledgementFacts(acknowledgement.record))
        evidence.bind("AOSF_C_GO_LIVE", goLiveFacts(goLive.record))
        val preClose = readAosfProtectedSetup(owner, sessions, reader, anchor, policy.actorId,
            policy.revision, acknowledgement.record, "AOSF-004-C pre-close", evidence, goLive.record)
        sessions.destroyAll()
        owner.closeConfirmed()
        evidence.event("CONFIRMED_NATIVE_CLOSE", JSONObject()
            .put("closeConfirmedByProductionOwner", true).put("intentionalStopRequested", false))

        val savedReceipt = readAosfSavedReceipt(application)
        requireSavedReceipt(settled, savedReceipt, anchor.attemptSequence)
        val before = readAosfNativeCut(application)
        before.requireSameSetupIdentity(initial)
        check(!before.pending) { "The AOSF-C reopen baseline retains an unresolved operation" }
        before.requireZeroBusinessLedger()
        check(before.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size == 1 &&
            before.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size == 1) {
            "The AOSF-C baseline does not hold exactly one ACK and one go-live before reopen"
        }
        evidence.bind("AOSF_C_CUT", before.cutDigest)

        owner.openExistingOnly()
        val reopened = owner.setup ?: owner.readOriginalSetup()
        check(AosfAnchor.bind(settled, reopened, checkNotNull(owner.original)) == anchor) {
            "Existing-only reopen changed the AOSF-C original settlement anchor"
        }
        evidence.event("RUNTIME_OWNER_REACQUIRED", JSONObject()
            .put("serviceIncarnationId", owner.original?.serviceIncarnationId?.value)
            .put("sameAnchor", true))
        val postReopen = sessions.signIn(settled.account.login, secrets.responsible)
        requireAosfSetupActor(sessions, postReopen, anchor, policy,
            "AOSF-004-C post-reopen current employee", evidence)
        val afterReopen = readAosfProtectedSetup(owner, sessions, postReopen, anchor,
            policy.actorId, policy.revision, acknowledgement.record,
            "AOSF-004-C protected read after reopen", evidence, goLive.record)
        check(afterReopen == preClose) {
            "The AOSF-C acknowledged live facts changed across confirmed close/reopen"
        }
        check(afterReopen.goLive == goLive.record &&
            afterReopen.acknowledgement == acknowledgement.record) {
            "The AOSF-C ACK/go-live actor or audit identity changed across reopen"
        }
        requireSameSetupReadback(anchor, owner.readOriginalSetup())
        evidence.event("POST_REOPEN_ORIGINAL_READBACK_MATCHED")
        sessions.destroyAll()
        owner.closeConfirmed()

        val after = readAosfNativeCut(application)
        // The frozen AOSF-004 allowed action delta includes SESSION_ESTABLISHED_AFTER_REOPEN, so
        // the cut excludes session/auth tables and compares all setup/business/authority/ACK facts.
        after.requireNoSetupFactMovement(before)
        after.requireSameSetupAndPolicy(before)
        after.requireZeroBusinessLedger()
        check(after.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size == 1 &&
            after.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size == 1) {
            "The reopened AOSF-C installation lost or duplicated its ACK/go-live facts"
        }
        evidence.bind("AOSF_C_POST_REOPEN_CUT", after.cutDigest)
    }

    private suspend fun runAosf005(application: Application, settled: AosfSetupOutcome.Installed,
        secrets: AosfSecrets, plan: AosfFixturePlan, evidence: AosfEvidence,
        retain: (AosfNativeOwner, AosfSessionLifetime) -> Unit): AosfSessionLifetime {
        val owner = AosfNativeOwner(application)
        val sessions = AosfSessionLifetime(owner, evidence)
        retain(owner, sessions)
        owner.openExistingOnly()
        val setup = owner.setup ?: owner.readOriginalSetup()
        val anchor = AosfAnchor.bind(settled, setup, checkNotNull(owner.original))
        requireFixedAnchor(anchor)
        evidence.bind("SETTLEMENT_ANCHOR", anchor.retained())

        val policy = activateAosfSetupPolicy(owner, plan, settled.account, Revision(1),
            setOf(SecurityAction.SETUP_RESULT_READ), secrets.head, evidence)
        val s1 = sessions.signIn(settled.account.login, secrets.responsible)
        val preClose = requireAosfSetupActor(sessions, s1, anchor, policy, "AOSF-005 S1", evidence)
        check(preClose.login.value == anchor.actorLogin)
        val preCloseRead = readAosfProtectedSetup(owner, sessions, s1, anchor, policy.actorId,
            policy.revision, acknowledgement = null, label = "AOSF-005 pre-close", evidence = evidence)
        check(preCloseRead.acknowledgement == null && preCloseRead.goLive == null)
        evidence.event("PRE_CLOSE_PROTECTED_READ_SUCCEEDED", JSONObject()
            .put("sessionId", preClose.sessionId.value).put("policyRevision", preClose.policy.revision.value))
        evidence.event("PRE_CLOSE_SESSION_LIVE", JSONObject().put("explicitlyRetiredBeforeClose", false))

        owner.closeConfirmed()
        evidence.event("CONFIRMED_NATIVE_CLOSE_WITH_PRECLOSE_SESSION_NOT_RETIRED", JSONObject()
            .put("closeConfirmedByProductionOwner", true).put("intentionalStopRequested", false))
        owner.openExistingOnly()
        val reopenedSetup = owner.setup ?: owner.readOriginalSetup()
        check(AosfAnchor.bind(settled, reopenedSetup, checkNotNull(owner.original)) == anchor) {
            "Existing-only reopen changed the original settlement anchor"
        }
        evidence.event("RUNTIME_OWNER_REACQUIRED", JSONObject()
            .put("serviceIncarnationId", owner.original?.serviceIncarnationId?.value)
            .put("sameAnchor", true))

        val probe = sessions.current(s1)
        when (probe) {
            is LocalSessionViewResult.Current -> {
                evidence.event("PRECLOSE_SESSION_AFTER_REOPEN", JSONObject().put("observed", "ACCEPTED"))
                val acceptedRead = readAosfProtectedSetup(owner, sessions, s1, anchor, policy.actorId,
                    policy.revision, acknowledgement = null, label = "AOSF-005 S1 after reopen", evidence = evidence)
                check(acceptedRead == preCloseRead) {
                    "The pre-close session disclosed different original facts after reopen"
                }
                evidence.event("PRECLOSE_PROTECTED_READ_AFTER_REOPEN", JSONObject().put("observed", "ACCEPTED"))
                val refreshed = sessions.refresh(s1)
                val active = when (refreshed) {
                    is LocalAuthenticationResult.Authenticated -> {
                        evidence.event("PRECLOSE_SESSION_REFRESH_AFTER_REOPEN", JSONObject()
                            .put("observed", "ACCEPTED"))
                        refreshed.tokens
                    }
                    is LocalAuthenticationResult.Denied -> {
                        evidence.event("PRECLOSE_SESSION_REFRESH_AFTER_REOPEN", JSONObject()
                            .put("observed", "DENIED"))
                        s1
                    }
                    else -> error("Pre-close session refresh result was unavailable: $refreshed")
                }
                sessions.retire(active, "AOSF-005-S1-OR-ROTATED")
            }
            is LocalSessionViewResult.Denied -> {
                evidence.event("PRECLOSE_SESSION_AFTER_REOPEN", JSONObject()
                    .put("observed", "REJECTED").put("attributedToNativeClose", false))
                requireAosfReadDenied(probeAosfProtectedSetup(owner, sessions.credentials(s1), anchor),
                    "AOSF-005 rejected S1")
                evidence.event("PRECLOSE_PROTECTED_READ_AFTER_REOPEN", JSONObject().put("observed", "REJECTED"))
                val s2 = sessions.signIn(settled.account.login, secrets.responsible)
                requireAosfSetupActor(sessions, s2, anchor, policy, "AOSF-005 S2", evidence)
                evidence.event("POST_CLOSE_EXPLICIT_RETIREMENT_FRESH_SESSION",
                    JSONObject().put("sessionId", s2.view.sessionId.value))
                sessions.retire(s2, "AOSF-005-S2")
            }
            else -> error("Pre-close session post-reopen probe was unavailable: $probe")
        }
        // A pre-close generation must not become usable through a later refresh or protected read.
        check(sessions.current(s1) is LocalSessionViewResult.Denied)
        check(sessions.refresh(s1) is LocalAuthenticationResult.Denied)
        requireAosfReadDenied(probeAosfProtectedSetup(owner, sessions.credentials(s1), anchor),
            "AOSF-005 retired pre-close")
        evidence.event("STALE_PRECLOSE_GENERATION_REJECTED")

        val s3 = sessions.signIn(settled.account.login, secrets.responsible)
        val fresh = requireAosfSetupActor(sessions, s3, anchor, policy, "AOSF-005 S3", evidence)
        check(fresh.login.value == anchor.actorLogin)
        val freshRead = readAosfProtectedSetup(owner, sessions, s3, anchor, policy.actorId,
            policy.revision, acknowledgement = null, label = "AOSF-005 S3", evidence = evidence)
        check(freshRead == preCloseRead) { "The fresh current employee read changed the original facts" }
        evidence.event("FRESH_EXPLICIT_LOGIN_AFTER_REOPEN", JSONObject()
            .put("actorId", fresh.actorId.value).put("sessionId", fresh.sessionId.value)
            .put("generation", fresh.generation.value).put("policyRevision", fresh.policy.revision.value))

        check(sessions.current(sessions.tampered(s3)) is LocalSessionViewResult.Denied)
        check(sessions.current(sessions.foreignConnection(s3)) is LocalSessionViewResult.Denied)
        requireAosfReadDenied(probeAosfProtectedSetup(owner, sessions.tampered(s3), anchor),
            "AOSF-005 tampered S3")
        requireAosfReadDenied(probeAosfProtectedSetup(owner, sessions.foreignConnection(s3), anchor),
            "AOSF-005 foreign S3")
        evidence.event("STALE_FOREIGN_GENERATION_REJECTED")
        when (val rotated = sessions.refresh(s3)) {
            is LocalAuthenticationResult.Authenticated -> {
                check(sessions.current(s3) is LocalSessionViewResult.Denied)
                check(sessions.refresh(s3) is LocalAuthenticationResult.Denied)
                requireAosfReadDenied(probeAosfProtectedSetup(owner, sessions.credentials(s3), anchor),
                    "AOSF-005 stale S3")
                sessions.requireCurrent(rotated.tokens, "AOSF-005 S3 rotated")
                sessions.retire(rotated.tokens, "AOSF-005-S3-ROTATED")
                evidence.event("STALE_S3_GENERATION_REJECTED")
            }
            is LocalAuthenticationResult.Denied ->
                error("Fresh S3 refresh was denied; generation rotation could not be observed")
            else -> error("Fresh S3 refresh was unavailable: $rotated")
        }
        // The original S3 access is stale after rotation and must stay rejected.
        check(sessions.current(s3) is LocalSessionViewResult.Denied)
        check(sessions.refresh(s3) is LocalAuthenticationResult.Denied)
        requireAosfReadDenied(probeAosfProtectedSetup(owner, sessions.credentials(s3), anchor),
            "AOSF-005 rotated S3")
        sessions.destroyAll()
        owner.closeConfirmed()
        val final = readAosfNativeCut(application)
        final.requireNoBusinessOrPending()
        check(final.count(com.ukkera.pos.security.NativeHistoryTableV2.BRANCH) == 1L &&
            final.count(com.ukkera.pos.security.NativeHistoryTableV2.ACCOUNT) == 1L &&
            final.count(com.ukkera.pos.security.NativeHistoryTableV2.POLICY) == 1L &&
            final.branchEpoch == 1L && final.branchWritable == 1L)
        evidence.event("AOSF005_LIFECYCLE_INDEPENDENT_SEGMENTS_COMPLETE")
        return sessions
    }

    private suspend fun runAosf006(application: Application, settled: AosfSetupOutcome.Installed,
        plan: AosfFixturePlan, secrets: AosfSecrets, evidence: AosfEvidence,
        retainOwner: (AosfNativeOwner) -> Unit) {
        val owner = AosfNativeOwner(application).also { retainOwner(it) }
        owner.openExistingOnly()
        val setup = owner.setup ?: owner.readOriginalSetup()
        val anchor = AosfAnchor.bind(settled, setup, checkNotNull(owner.original))
        requireFixedAnchor(anchor)
        evidence.bind("SETTLEMENT_ANCHOR", anchor.retained())

        val requested = plan.distinctUncertainActor(settled.branch)
        check(requested.actorId.value != anchor.actorId)
        val requestId = DomainId.random()
        check(requestId.value != anchor.setupRequestId)
        val action = (checkNotNull(owner.original).prepareEmployeeEnrollment(requestId, requested,
            credentialWasProvided = true) as? LocalCompanyAuthorityResult.Value)?.value
            ?: error("The distinct AOSF-U actor definition was not prepared")
        val connection = withAosfSecret(secrets.head) { head ->
            (checkNotNull(owner.original).approveEmployeeEnrollment(action, plan.headLogin, head)
                as? LocalCompanyAuthorityResult.Value)?.value
        } ?: error("The distinct AOSF-U actor was not approved")
        check(connection.belongsTo(owner.original!!) && connection.actorId == requested.actorId)
        val definition = checkNotNull(connection.originalDefinition(requestId, requested.login))
        check(definition.actorId == requested.actorId && definition.login == requested.login &&
            definition.admittedDeviceIds == requested.admittedDeviceIds &&
            definition.tokenPolicy == requested.tokenPolicy)
        requireNativeFixturePolicy(requested.policy, definition.policy)
        val applied = withAosfSecret(secrets.uncertainActor) { password ->
            connection.provisionAccount(requestId, requested, password)
        } as? LocalCredentialAdministrationResult.Applied
            ?: error("The distinct AOSF-U actor was not provisioned")
        val lookup = (connection.lookupAccountProvisioning(requestId, requested, credentialWasProvided = true)
            as? LocalProvisioningStatus.Applied)
            ?: error("The distinct AOSF-U actor provisioning was not independently reobserved")
        check(lookup.eventId == applied.eventId)
        evidence.bind("UNCERTAIN_ACTOR", JSONObject().put("actorId", requested.actorId.value)
            .put("login", requested.login.value).put("requestId", requestId.value)
            .put("actionId", connection.originalActionId.value)
            .put("provisioningEventId", applied.eventId.value)
            .put("roleFingerprint", AosfAnchor.policyFingerprint(requested.policy))
            .put("scope", requested.policy.scope.branchId.value)
            .put("admittedDeviceId", requested.admittedDeviceIds.single().value))
        check(requested.policy.scope.organizationId.value == anchor.organizationId &&
            requested.policy.scope.companyId.value == anchor.companyId &&
            requested.policy.scope.branchId.value == anchor.branchId &&
            requested.admittedDeviceIds.single().value == anchor.deviceId)

        val operation = OperationId.random()
        val ownerAcquisition = checkNotNull(owner.original).serviceIncarnationId.value
        check(operation.value != anchor.setupRequestId && operation.value != anchor.genesisDigest &&
            operation.value != ownerAcquisition && operation.value != requestId.value &&
            operation.value != connection.originalActionId.value)
        evidence.retainUncertainOriginal(operation.value, requested.actorId.value,
            AosfAnchor.policyFingerprint(requested.policy), anchor.branchId, anchor.installationId, ownerAcquisition)
        val retainedBefore = evidence.uncertainOriginal()

        // The distinct uncertain actor needs an explicit current read action; neither its
        // recovery grant nor any setup-administrator role is treated as an implicit read grant.
        val readerPolicy = activateAosfSetupPolicy(owner, plan, requested, Revision(1),
            setOf(SecurityAction.SETUP_RESULT_READ), secrets.head, evidence)
        val sessions = AosfSessionLifetime(owner, evidence)
        var reader = sessions.signIn(requested.login, secrets.uncertainActor)
        requireAosfSetupActor(sessions, reader, anchor, readerPolicy, "AOSF-006 uncertain actor", evidence)
        owner.closeConfirmed()

        val before = readAosfNativeCut(application)
        before.requireNoBusinessOrPending()
        check(before.count(com.ukkera.pos.security.NativeHistoryTableV2.BRANCH) == 1L &&
            before.count(com.ukkera.pos.security.NativeHistoryTableV2.ACCOUNT) == 2L &&
            before.count(com.ukkera.pos.security.NativeHistoryTableV2.POLICY) == 2L &&
            before.count(com.ukkera.pos.security.NativeHistoryTableV2.ADMISSION) == 1L)
        val savedReceipt = readAosfSavedReceipt(application)
        requireSavedReceipt(settled, savedReceipt)
        evidence.event("SAVED_ORIGINAL_RECEIPT_READBACK", JSONObject()
            .put("receiptDigest", savedReceipt.digest.value)
            .put("definitionDigest", savedReceipt.core.definitionDigest.value)
            .put("originalRequestId", savedReceipt.core.originalRequestId.value))
        owner.openExistingOnly()
        val first = owner.setup ?: owner.readOriginalSetup()
        check(AosfAnchor.bind(settled, first, checkNotNull(owner.original)) == anchor)
        var resumed = sessions.current(reader) is LocalSessionViewResult.Current
        if (!resumed) {
            evidence.event("UNCERTAIN_ACTOR_SESSION_REJECTED_AFTER_REOPEN",
                JSONObject().put("explicitFreshLogin", true))
            reader = sessions.signIn(requested.login, secrets.uncertainActor)
            requireAosfSetupActor(sessions, reader, anchor, readerPolicy,
                "AOSF-006 uncertain actor after reopen", evidence)
        } else {
            evidence.event("UNCERTAIN_ACTOR_SESSION_ACCEPTED_AFTER_REOPEN")
        }
        val ackState = readAosfProtectedSetup(owner, sessions, reader, anchor, readerPolicy.actorId,
            readerPolicy.revision, acknowledgement = null, label = "AOSF-006 ACK-state read 1", evidence = evidence)
        val repeated = readAosfProtectedSetup(owner, sessions, reader, anchor, readerPolicy.actorId,
            readerPolicy.revision, acknowledgement = null, label = "AOSF-006 ACK-state read 2", evidence = evidence)
        check(ackState == repeated) { "Repeated AOSF-006 ACK-state reads changed the disclosed facts" }
        evidence.event("AOSF006_PROTECTED_ACK_STATE_READ", JSONObject()
            .put("committedAckFact", "NONE_DISCLOSED")
            .put("acknowledgementState", "UNKNOWN_UNRESOLVED")
            .put("uncertainLogicalOperation", "UNKNOWN_UNRESOLVED")
            .put("converted", false).put("autoResumed", false).put("autoRetried", false))
        consumeWithheldReply(owner.readOriginalSetup())
        sessions.destroyAll()
        owner.closeConfirmed()
        val after = readAosfNativeCut(application)
        if (resumed) {
            before.requireSameReadCut(after)
            evidence.event("AOSF006_READ_CUT_ZERO_DELTA")
        } else {
            after.requireSameSetupAndPolicy(before)
            after.requireNoStockOrBusinessMovement(before)
            evidence.unbound("AOSF-006-READ-CUT",
                "The uncertain-actor session was rejected after reopen, so an explicit fresh login occurred between the cuts; protected ACK-state equality is asserted, but exact zero-delta read cut is not attributed to the read alone")
        }
        check(evidence.uncertainOriginal().toString() == retainedBefore.toString()) {
            "The fixture-driven uncertain original was rebound after readback"
        }
        evidence.bind("UNCERTAIN_ORIGINAL", retainedBefore)
        evidence.bind("PRE_READ_CUT", before.cutDigest)
        evidence.bind("POST_READ_CUT", after.cutDigest)
        evidence.event("UNCERTAIN_ORIGINAL_REMAINED_UNRESOLVED",
            JSONObject().put("autoResolved", false).put("autoRetried", false)
                .put("newIdentityMinted", false).put("operationTableRows", after.count(
                    com.ukkera.pos.security.NativeHistoryTableV2.OPERATION)))
        check(after.count(com.ukkera.pos.security.NativeHistoryTableV2.OPERATION) == 0L)
        check(after.count(com.ukkera.pos.security.NativeHistoryTableV2.BRANCH) == 1L &&
            after.branchEpoch == 1L && after.branchWritable == 1L)
    }

    private fun unboundAosf001(evidence: AosfEvidence) {
        evidence.unbound("AOSF-001-PRE_SETTLEMENT_SESSION",
            "The first account credential is created by installation settlement, so the explicit responsible-actor session is observed immediately after settlement; the anchor itself contains no session")
        evidence.unbound("AOSF-001-SETUP-DEFINITION-KEY-VERSION",
            "Production LocalBranchInstallationDefinitionV2 has no separate setup-definition key/version field; the requested SETUP-SYNTH-ANDROID-001/v1 fact is retained only as fixture input")
        evidence.unbound("AOSF-001-CURRENCY-TIMEZONE",
            "Production setup does not expose an EGP/Africa-Cairo configuration observation in the selected definition path")
        evidence.unbound("AOSF-UB3", "The actual AndroidKeyStore protection kind is recorded from material facts; no TEE/StrongBox claim is made")
    }

    private fun unboundAosf002(evidence: AosfEvidence) {
        evidence.unbound("AOSF-002-SETUP-DEFINITION-KEY-VERSION",
            "No separate setup-definition key/version field exists in the selected production definition")
        evidence.unbound("OSR-U1", "Exact native readback availability/status after installation remains undecided")
    }

    private fun unboundAosf003(evidence: AosfEvidence, fixture: String) {
        if (fixture != "AOSF-C") evidence.unbound("AOSF-003-AOSF-C",
            "This fresh $fixture installation did not run the separate AOSF-C acknowledged-live baseline; AOSF-C requires its own fresh AOSF-C installation")
        if (fixture != "AOSF-D") evidence.unbound("AOSF-003-AOSF-D",
            "This fresh $fixture installation did not run the separate AOSF-D live-without-ACK baseline; AOSF-D requires its own fresh AOSF-D installation")
        evidence.unbound("AOSF-UB5",
            "Exact delivery-ACK/envelope representation and native envelope lifetime remain undecided")
        evidence.unbound("OSR-U1",
            "Exact native saved-result readback status/shape and delivery-envelope lifetime remain undecided")
        evidence.unbound("AOSF-003-SETUP-DEFINITION-KEY-VERSION",
            "The selected production setup definition path does not expose a separate setup-definition key/version; SETUP-SYNTH-ANDROID-001/v1 remains requested fixture input")
    }

    private fun unboundAosf004(evidence: AosfEvidence, fixture: String) {
        if (fixture != "AOSF-C") evidence.unbound("AOSF-004-AOSF-C",
            "This fresh $fixture installation did not run the separate AOSF-C acknowledged-live reopen baseline; AOSF-C requires its own fresh AOSF-C installation")
        evidence.unbound("AOSF-UB5",
            "Exact delivery-ACK/envelope representation and native envelope lifetime remain undecided")
        evidence.unbound("OSR-U4",
            "Exact closed native readback status and native-close-to-intentional-stop mapping remain undecided; the owner close itself was confirmed")
        evidence.unbound("AOSF-UB4",
            "The exact public current-authority observation surface is bound for the successful post-reopen employee read, but the disabled/revoked post-reopen negative variant is not exercised in this installation")
        evidence.unbound("AOSF-004-SETUP-DEFINITION-KEY-VERSION",
            "The selected production setup definition path does not expose a separate setup-definition key/version; SETUP-SYNTH-ANDROID-001/v1 remains requested fixture input")
    }

    private fun unboundAosf005(evidence: AosfEvidence) {
        evidence.unbound("AOSF-005-CURRENCY-TIMEZONE",
            "No setup currency/timezone configuration observation is exposed by the production definition path")
        evidence.unbound("AOSF-005-NATIVE-ENVELOPE",
            "Delivery-envelope retention/consumption is not asserted by the protected setup read")
    }

    private fun unboundAosf006(evidence: AosfEvidence) {
        evidence.unbound("AOSF-006-DELIVERY-ACK-STATE",
            "The protected setup read discloses no committed delivery ACK; the separate uncertain logical operation remains UNKNOWN_UNRESOLVED and is never converted to ACK/settlement success or failure")
        evidence.unbound("AOSF-006-CURRENCY-TIMEZONE",
            "No setup currency/timezone configuration observation is exposed by the production definition path")
        evidence.unbound("AOSF-UB5",
            "Exact uncertain ACK/logical-operation representation and native envelope lifetime remain undecided")
    }

    private fun requireFixture(actual: AosfFixtureKind, expected: AosfFixtureKind, case: String) {
        check(actual == expected) { "$case requires the separate ${expected.id} installation, not ${actual.id}" }
    }

    private fun requireBranchCut(cut: AosfNativeCut, evidence: AosfEvidence) {
        check(cut.branchEpoch == 1L && cut.branchWritable == 1L) {
            "The current-schema branch authority is not epoch 1 / one active writer"
        }
        check(cut.branchAssignment != null && cut.branchDevice != null && cut.branchScope != null)
        evidence.bind("BRANCH_CUT", JSONObject().put("epoch", cut.branchEpoch)
            .put("writable", cut.branchWritable).put("assignmentId", cut.branchAssignment)
            .put("deviceId", cut.branchDevice).put("scope", cut.branchScope))
    }

    private fun requireFixedAnchor(anchor: AosfAnchor) {
        check(anchor.authorityEpoch == 1L)
        check(anchor.protection in SelfOwnedKeyProtectionV1.entries.map { it.name })
        check(anchor.storageKeySecurity.isNotEmpty())
        check(anchor.setupRequestId.length == 36 && anchor.issuedAttemptId.length == 36 &&
            anchor.attemptSequence in 1 until Long.MAX_VALUE &&
            anchor.definitionDigest.length == 64 &&
            anchor.receiptDigest.length == 64 && anchor.genesisDigest.length == 64 &&
            anchor.roleFingerprint.length == 64)
        check(anchor.actorId != anchor.installationId && anchor.actorId != anchor.deviceId &&
            anchor.actorId != anchor.assignmentId)
    }

    private fun requireSetupOnlyRoots(cut: AosfNativeCut, accounts: Long = 1L, policies: Long = 1L) {
        check(cut.count(com.ukkera.pos.security.NativeHistoryTableV2.BRANCH) == 1L &&
            cut.count(com.ukkera.pos.security.NativeHistoryTableV2.ACCOUNT) == accounts &&
            cut.count(com.ukkera.pos.security.NativeHistoryTableV2.POLICY) == policies &&
            cut.count(com.ukkera.pos.security.NativeHistoryTableV2.ADMISSION) == 1L) {
            "Unexpected setup root/account/policy/admission cardinality"
        }
        check(cut.branchEpoch == 1L && cut.branchWritable == 1L)
    }

    private fun requireSameSetupReadback(anchor: AosfAnchor, readback: LocalOriginalInstallationSetup) {
        check(readback.receipt?.digest?.value == anchor.receiptDigest &&
            readback.definition?.definitionDigest?.value == anchor.definitionDigest &&
            readback.receipt?.core?.originalRequestId?.value == anchor.setupRequestId &&
            readback.company.organizationId.value == anchor.organizationId &&
            readback.company.companyId.value == anchor.companyId &&
            readback.company.rootFingerprint == anchor.rootFingerprint)
    }

    private fun acknowledgementFacts(value: LocalSetupAcknowledgementRecordV1): JSONObject = JSONObject()
        .put("organizationId", value.organizationId.value).put("companyId", value.companyId.value)
        .put("branchId", value.branchId.value).put("installationId", value.installationId.value)
        .put("originalRequestId", value.originalRequestId.value)
        .put("issuedAttemptId", value.issuedAttemptId.value)
        .put("attemptSequence", value.attemptSequence)
        .put("definitionDigest", value.definitionDigest.value)
        .put("genesisDigest", value.genesisDigest.value)
        .put("receiptDigest", value.receiptDigest.value)
        .put("actorId", value.actorId.value).put("operationId", value.operationId.value)
        .put("policyRevision", value.policyRevision.value)
        .put("masterAuthorityEpoch", value.masterAuthorityEpoch.value)
        .put("acknowledgedAt", value.acknowledgedAt.toString())

    private fun goLiveFacts(value: LocalSetupGoLiveRecordV1): JSONObject = JSONObject()
        .put("organizationId", value.organizationId.value).put("companyId", value.companyId.value)
        .put("branchId", value.branchId.value).put("installationId", value.installationId.value)
        .put("originalRequestId", value.originalRequestId.value)
        .put("issuedAttemptId", value.issuedAttemptId.value)
        .put("attemptSequence", value.attemptSequence)
        .put("definitionDigest", value.definitionDigest.value)
        .put("genesisDigest", value.genesisDigest.value)
        .put("receiptDigest", value.receiptDigest.value)
        .put("actorId", value.actorId.value).put("operationId", value.operationId.value)
        .put("policyRevision", value.policyRevision.value)
        .put("masterAuthorityEpoch", value.masterAuthorityEpoch.value)
        .put("occurredAt", value.occurredAt.toString())
        .put("prerequisites", value.prerequisites.joinToString(",") {
            "${it.code.name}=${if (it.settled) "SETTLED" else "UNAVAILABLE"}:${it.binding}"
        })

    private fun requireSavedReceipt(settled: AosfSetupOutcome.Installed,
        saved: com.ukkera.pos.security.LocalBranchInstallationReceiptV2,
        expectedAttemptSequence: Long? = null) {
        check(saved.digest.value == settled.receipt.receiptDigest &&
            saved.genesis.value == settled.receipt.genesisDigest &&
            saved.core.originalRequestId.value == settled.receipt.originalRequestId.value &&
            saved.core.definitionDigest.value == settled.receipt.definitionDigest &&
            saved.core.issuedAttemptId.value == settled.receipt.issuedAttemptId.value &&
            saved.core.attemptSequence in 1 until Long.MAX_VALUE &&
            (expectedAttemptSequence == null || saved.core.attemptSequence == expectedAttemptSequence) &&
            saved.core.allocation.coordinates.scope.branchId.value == settled.receipt.branchId.value)
    }

    private fun consumeWithheldReply(@Suppress("UNUSED_PARAMETER") reply: LocalOriginalInstallationSetup) {
        // The durable read completed; this observing caller intentionally receives no fields.
    }

    private fun consumeWithheldProjection(@Suppress("UNUSED_PARAMETER") projection: LocalSetupResultProjectionV1) {
        // The durable protected read completed; this observing caller intentionally receives no fields.
    }
}
