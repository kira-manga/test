package com.ukkera.pos.app.gateway.supporting.resolver

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.test.*

/** SELF-AUTHORED, NOT independent. Separately frozen v1.0.11 support for original
 * blueprint5.3 and qa/pos/designs/lan-master-client-v1/cases.json LAN-A07/D04/D06.
 * Genuine completed cash-close/reopen + supplier-configuration ancestry precedes Start;
 * A UNKNOWN and distinct B known NOT_PAID release the reservation with no money debit.
 * Actual protected endpoint LAN retained authorization MUST also qualify the real native
 * BEFORE graph; wrong digest/foreign actor deny. This is bounded cohost native/software
 * source-port execution, NOT TCP/additional-device/native platform qualification. */
class SupplierCashAncestryNativeSupportingTest {
    private fun id() = DomainId.random()
    private fun op() = OperationId.random()
    private fun progress(reply: LocalRetailPhysicalReply): LocalRetailPhysicalReply.Progress =
        assertIs<LocalRetailPhysicalReply.Progress>(reply, "Actual production reply: $reply")
    private fun money(minor: Long) = Money(Currency.EGP, minor)
    private fun committed(reply: MutationReply): MutationReply.Recorded = assertIs<MutationReply.Recorded>(reply).also {
        assertTrue(it.committed, "${it.code}: ${it.result.copyBytes().decodeToString()}")
    }
    @Test fun closedCashAndSupplierAncestorsSupportNotPaidOriginalRecoveryExactlyOnce(): Unit = runBlocking {
        val f = FinancialResolverNativeFixture()
        var owner: BranchUiSession? = null; var supervisor: BranchUiSession? = null
        try {
            f.install(); f.enrollSupervisor()
            val drawer = id(); val cash = id(); val earlierCash = id(); val supplier = id()
            suspend fun cash(request: LocalCashRequestV1) = committed(f.execute(MutationRequest(op(), LocalSalesStoreProfile.CASH_COMMAND,
                LocalCashCommandCodec.encode(request))))
            cash(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic original supplier drawer", Currency.EGP))
            cash(LocalCashRequestV1.ConfigureCustodian(f.actor))
            cash(LocalCashRequestV1.Open(earlierCash, drawer, Revision(0), Revision(0), BusinessDate(LocalDate(2026, 11, 15)), id(),
                money(100_000), LocalCashEvidenceV1(id(), UtcInstant(Clock.System.now()))))
            cash(LocalCashRequestV1.BeginClosing(earlierCash, Revision(0), Revision(0)))
            val count = LocalCashEvidenceV1(id(), UtcInstant(Clock.System.now()))
            cash(LocalCashRequestV1.RecordClosingCount(earlierCash, Revision(1), null, money(100_000), count))
            cash(LocalCashRequestV1.SubmitCountedClose(earlierCash, Revision(2), count.evidenceId, money(100_000), money(100_000)))
            cash(LocalCashRequestV1.Open(cash, drawer, Revision(2), Revision(2), BusinessDate(LocalDate(2026, 11, 15)), id(),
                money(100_000), LocalCashEvidenceV1(id(), UtcInstant(Clock.System.now()))))
            committed(f.execute(MutationRequest(op(), LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(
                LocalConfigurationRequestV1.ConfigureSupplier(null, LocalSupplierV1(supplier, LocalNamesV1("Synthetic supplier", "مورد اختبار"), true))))))
            committed(f.execute(MutationRequest(op(), LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(
                LocalConfigurationRequestV1.ConfigureSupplier(Revision(1), LocalSupplierV1(supplier,
                    LocalNamesV1("Synthetic supplier updated", "مورد اختبار محدث"), true))))))
            suspend fun stage(session: BranchUiSession, request: LocalRetailPhysicalRequest): OperationId {
                val prepared = session.retailPhysical.prepare(request)
                val handle = assertIs<LocalRetailPhysicalReply.Prepared>(prepared, "$request: $prepared").value
                return assertIs<LocalRetailPhysicalReply.Staged>(session.retailPhysical.stage(handle)).value.operationId
            }
            owner = f.uiOwner()
            val start = stage(owner, LocalRetailPhysicalRequest.SupplierCash(supplier, null, money(3_000), "synthetic.supervisor.advance"))
            val lifetime = LocalRetailPhysicalLifetime()
            val instruction = assertIs<LocalRetailPhysicalReply.Instruction>(owner.retailPhysical.start(start, lifetime)).value
            assertEquals(money(3_000), instruction.display.amount)
            val recording = instruction.display.recordingOperationId
            val fixedResolution = instruction.display.resolutionOperationId
            assertIs<LocalRetailPhysicalCapture.CapturedInMemory>(instruction.capture(LocalRetailObservation.Unknown(
                LocalRetailEvidence(id(), UtcInstant(Clock.System.now())))))
            // Actual UI interruption after native input capture. The original native worker
            // retains its original owner; no second capture, Start or operation is substituted.
            owner.close(); owner = null; lifetime.deny()
            f.login(); owner = f.uiOwner()
            withTimeout(90_000L) {
                while (true) {
                    val read = owner.retailPhysical.recoverSupplierCash(recording)
                    if (read is LocalRetailPhysicalReply.Progress && read.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING) break
                    delay(100)
                }
            }
            val unknown = stage(owner, LocalRetailPhysicalRequest.SupplierCashApply(recording, LocalRetailObservationPhase.ORIGINAL))
            assertEquals(LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED,
                progress(owner.retailPhysical.continueSql(unknown)).value.state)
            assertEquals(money(100_000), f.expectedCash(cash))
            val deniedId = op()
            val denied = f.execute(LocalSupplierCashSupervisorCodec.command(deniedId, LocalSupplierCashSupervisorRequest.Review(recording)))
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(denied).reason)
            assertTrue(f.operationCounts(deniedId).values.all { it == 0L })
            val startIdentity = f.originalIdentity(start, LocalSupplierCashProfile.COMMAND)
            assertEquals(f.actor, startIdentity.originalActorId)
            assertEquals(LocalMasterRpcAccessResult.ALLOWED, f.authorizeLanOriginalAs(f.credentials, startIdentity))
            assertEquals(LocalMasterRpcAccessResult.DENIED, f.authorizeLanOriginalAs(f.credentials,
                startIdentity.copy(semanticSha256 = sha256("wrong.start.original".encodeToByteArray()).value)))
            owner.close(); owner = null
            supervisor = f.uiSupervisor()
            assertEquals(f.supervisorActor, supervisor.display.actorId)
            assertContains(supervisor.display.allowedAreas, com.ukkera.pos.app.navigation.AppArea.PURCHASING)
            assertNotEquals(f.actor, supervisor.display.actorId)
            assertIs<LocalRetailPhysicalReply.Unavailable>(supervisor.retailPhysical.prepare(
                LocalRetailPhysicalRequest.SupplierCashApply(recording, LocalRetailObservationPhase.ORIGINAL)))
            val reviewId = stage(supervisor, LocalRetailPhysicalRequest.SupplierSupervisorReview(recording))
            val review = progress(supervisor.retailPhysical.continueSql(reviewId)).value
            assertEquals(LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED, review.state)
            assertEquals(f.actor, review.originalActorId)
            assertEquals(recording, review.originalRecordingOperationId)
            val resolution = stage(supervisor, LocalRetailPhysicalRequest.SupplierSupervisorResolve(reviewId,
                LocalRetailObservation.NotPaid(LocalRetailEvidence(id(), UtcInstant(Clock.System.now())),
                    "synthetic.original.not_paid")))
            assertEquals(fixedResolution, resolution)
            assertEquals(LocalRetailDurableState.SQL_RESOLUTION_PENDING,
                progress(supervisor.retailPhysical.continueSql(resolution)).value.state)
            assertEquals(resolution, progress(supervisor.retailPhysical.recoverSupplierCash(resolution)).value.operationId)
            val freshId = stage(supervisor, LocalRetailPhysicalRequest.SupplierSupervisorReview(recording))
            val fresh = progress(supervisor.retailPhysical.continueSql(freshId)).value
            assertTrue(fresh.resolutionRecorded); assertEquals(f.actor, fresh.originalActorId)
            assertEquals(f.supervisorActor, fresh.recordingActorId)
            val application = stage(supervisor, LocalRetailPhysicalRequest.SupplierSupervisorApply(freshId, LocalRetailObservationPhase.RESOLUTION))
            val paid = progress(supervisor.retailPhysical.continueSql(application))
            assertEquals(LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY, paid.value.state)
            repeat(3) { assertEquals(paid.value, progress(supervisor.retailPhysical.continueSql(application)).value) }
            assertEquals(money(100_000), f.expectedCash(cash))
            for (operation in listOf(recording, resolution, application)) assertTrue(f.operationCounts(operation).values.all { it == 1L })
            // Fresh SQL-only application ID is also a real durable no-effect original.
            // SELF-AUTHORED support for qa/pos/designs/lan-master-client-v1/cases.json
            // LAN-A07/D04/D06 and blueprint5.3; no frozen/independent assertion is changed.
            val afterPaidReview = stage(supervisor, LocalRetailPhysicalRequest.SupplierSupervisorReview(recording))
            progress(supervisor.retailPhysical.continueSql(afterPaidReview))
            // Existing UI dedup keeps the paid original. A separate actual protected
            // Master request is required to exercise a NEW no-effect refusal original.
            val duplicateUi = assertIs<LocalRetailPhysicalReply.Staged>(supervisor.retailPhysical.prepare(
                LocalRetailPhysicalRequest.SupplierSupervisorApply(afterPaidReview, LocalRetailObservationPhase.RESOLUTION)))
            assertEquals(application, duplicateUi.value.operationId)
            val refusalCredentials = f.supervisorCredentials()
            assertEquals(LocalMasterRpcAccessResult.DENIED, f.authorizeLanOriginalAs(refusalCredentials, startIdentity))
            for (operation in listOf(reviewId, freshId, application)) {
                val identity = f.originalIdentity(operation, LocalSupplierCashSupervisorCodec.COMMAND)
                assertEquals(f.supervisorActor, identity.originalActorId)
                assertEquals(LocalMasterRpcAccessResult.ALLOWED, f.authorizeLanOriginalAs(refusalCredentials, identity))
                assertEquals(LocalMasterRpcAccessResult.DENIED, f.authorizeLanOriginalAs(refusalCredentials,
                    identity.copy(semanticSha256 = sha256("wrong.supervisor.original".encodeToByteArray()).value)))
            }
            val appliedIdentity = f.originalIdentity(application, LocalSupplierCashSupervisorCodec.COMMAND)
            val actualApplied = assertIs<LookupReply.Found>(f.lookupAs(refusalCredentials, appliedIdentity)).result
            assertEquals(LocalSupplierCashAction.NOT_PAID,
                assertIs<LocalSupplierCashResult.Applied>(LocalSupplierCashCodec.result(actualApplied.result)).action)
            val freshRequest = LocalSupplierCashSupervisorCodec.command(op(), LocalSupplierCashSupervisorRequest.Apply(resolution, LocalSupplierCashPhase.RESOLUTION))
            val refused = freshRequest.operationId
            val refusal = assertIs<MutationReply.Recorded>(f.executeAs(refusalCredentials, freshRequest))
            assertFalse(refusal.committed)
            assertEquals("purchasing.supplier_cash_already_applied", refusal.code)
            val refusedResult = assertIs<LocalSupplierCashResult.AlreadyApplied>(LocalSupplierCashCodec.result(refusal.result))
            assertEquals(application, refusedResult.applicationOperationId)
            assertEquals(LocalSupplierCashPhase.RESOLUTION, refusedResult.phase)
            repeat(3) {
                val replay = assertIs<MutationReply.Recorded>(f.executeAs(refusalCredentials, freshRequest))
                assertTrue(replay.replayed)
                assertEquals(StoredKernelResultV1.from(refusal), StoredKernelResultV1.from(replay))
            }
            val refusedIdentity = com.ukkera.pos.recovery.OperationIdentity(f.scope.branchId, refused,
                f.supervisorActor, 1, semanticDigest(f.scope, f.supervisorActor, freshRequest.command, freshRequest.payload).value)
            assertTrue(f.operationCounts(refused).values.all { it == 1L })
            assertEquals(LocalMasterRpcAccessResult.ALLOWED, f.authorizeLanOriginalAs(refusalCredentials, refusedIdentity))
            assertEquals(money(100_000), f.expectedCash(cash))
            assertEquals(LocalSessionEndResult.REVOKED, supervisor.logout())
            assertIs<LocalRetailPhysicalReply.Unavailable>(supervisor.retailPhysical.continueSql(resolution))
            supervisor = null
            f.reopen(); supervisor = f.uiSupervisor()
            assertEquals(LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY,
                progress(supervisor.retailPhysical.recoverSupplierCash(application)).value.state)
            assertEquals(money(100_000), f.expectedCash(cash))
            val coldCredentials = f.supervisorCredentials()
            val coldRefusal = assertIs<LookupReply.Found>(f.lookupAs(coldCredentials, refusedIdentity)).result
            assertEquals(LocalMasterRpcAccessResult.ALLOWED, f.authorizeLanOriginalAs(coldCredentials, refusedIdentity))
            assertTrue(coldRefusal.replayed)
            assertEquals(StoredKernelResultV1.from(refusal), StoredKernelResultV1.from(coldRefusal))
            assertTrue(f.operationCounts(refused).values.all { it == 1L })
        } finally { owner?.close(); supervisor?.close(); f.close() }
    }
}
