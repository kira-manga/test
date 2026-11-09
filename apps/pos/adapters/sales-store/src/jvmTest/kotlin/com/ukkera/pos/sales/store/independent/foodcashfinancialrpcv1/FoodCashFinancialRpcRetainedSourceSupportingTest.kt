package com.ukkera.pos.sales.store.independent.foodcashfinancialrpcv1

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Frozen FFRC v1.0.0 source-only refusal/declaration SUPPORT. Scalar identities,
 * committed flags, hashes and header bytes below are explicitly unqualified DATA.
 * No genuine retained Start/raw/Apply/stock stream, grant or native owner is minted;
 * no prepare/evaluate/dispatch is used to rebuild a retained result. */
class FoodCashFinancialRpcRetainedSourceSupportingTest {
    private val f get() = FoodCashFinancialRpcSupportingInputs
    private val profile = LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL

    @Test
    fun ffrc002ffrc011InvalidProfileBodyOrSemanticCoordinatesRefuseBeforeAnySourceRead() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val stock = assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(fixture.stockHandler)
        val payment = assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(fixture.paymentHandler)
        val inputs = listOf(Triple(fixture.stockHandler.key, stock, LocalFoodStockCodecV2.encode(f.begin)),
            Triple(fixture.stockHandler.key, stock, LocalFoodStockCodecV2.encode(f.finalize)),
            Triple(fixture.paymentHandler.key, payment, LocalFoodPaymentCodec.encode(f.apply)))
        for ((key, provider, body) in inputs) {
            val context = f.commandContext(key, body)
            val view = FoodCashFinancialRpcDenialDataView()
            for (other in LocalMasterRpcWorkflowProfile.entries.filterNot { it == profile })
                assertNull(provider.rpcRetainedResultRequirements(other, body, view, context))
            val mismatches = listOf(f.commandContext(CommandKey(key.name, key.version + 1), body),
                f.commandContext(key, body, actor = f.otherActor, digest = context.semanticDigest),
                f.commandContext(key, body, scope = f.scope.copy(branchId = f.id("R2"))),
                f.commandContext(key, body, scope = f.scope.copy(companyId = f.id("other-company"))),
                f.commandContext(key, body, scope = f.scope.copy(organizationId = f.id("other-organization"))),
                f.commandContext(key, body, digest = sha256("different-semantic-data".encodeToByteArray())))
            for (mismatch in mismatches)
                assertNull(provider.rpcRetainedResultRequirements(profile, body, view, mismatch))
            val changed = if (key == fixture.stockHandler.key) {
                LocalFoodStockCodecV2.encode(if (body == LocalFoodStockCodecV2.encode(f.begin)) f.finalize else f.begin)
            } else LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.ApplyOriginal(f.operation("other-recording")))
            assertNull(provider.rpcRetainedResultRequirements(profile, changed, view, context),
                "Shared key cannot change the complete canonical original body")
            assertNoSourceReads(view)
        }
        for (request in f.excludedPaymentBodies()) {
            val body = LocalFoodPaymentCodec.encode(request)
            val view = FoodCashFinancialRpcDenialDataView()
            assertNull(payment.rpcRetainedResultRequirements(profile, body, view, f.commandContext(fixture.paymentHandler.key, body)))
            assertNoSourceReads(view)
        }
        for (request in f.excludedStockBodies()) {
            val body = LocalFoodStockCodecV2.encode(request)
            val view = FoodCashFinancialRpcDenialDataView()
            assertNull(stock.rpcRetainedResultRequirements(profile, body, view, f.commandContext(fixture.stockHandler.key, body)))
            assertNoSourceReads(view)
        }
        assertEquals(0, fixture.source.calls)
        // Provider boundary only; no kernel original-identity or admitted RPC outcome is claimed.
    }

    @Test
    fun ffrc013AbsentRetainedPaymentCannotSupplyDisclosureRequirements() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val provider = assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(fixture.paymentHandler)
        val body = LocalFoodPaymentCodec.encode(f.apply)
        val view = FoodCashFinancialRpcDenialDataView(originals = mapOf(f.application to null))
        assertNull(provider.rpcRetainedResultRequirements(profile, body, view, f.commandContext(fixture.paymentHandler.key, body)))
        assertEquals(listOf(f.application), view.retainedReads)
        assertTrue(view.aggregateReads.isEmpty())
        assertTrue(view.claimReads.isEmpty())
        assertEquals(0, fixture.source.calls)
        // Null requirements are unavailable source support, not authoritative operation absence.
    }

    @Test
    fun ffrc013UnsupportedScalarRefusalIsNotAnOriginalFinancialSource() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val body = LocalFoodPaymentCodec.encode(f.apply)
        val context = f.commandContext(fixture.paymentHandler.key, body)
        val data = scalarRetainedData(context, body, code = "food.payment.not_available")
        val normalizedBefore = data.normalized.copyBytes()
        val resultBefore = data.result.copyBytes()
        val view = FoodCashFinancialRpcDenialDataView(originals = mapOf(f.application to data))
        val provider = assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(fixture.paymentHandler)
        assertFalse(data.committed)
        assertNull(provider.rpcRetainedResultRequirements(profile, body, view, context))
        assertEquals(listOf(f.application), view.retainedReads)
        assertTrue(view.aggregateReads.isEmpty())
        assertContentEquals(normalizedBefore, data.normalized.copyBytes())
        assertContentEquals(resultBefore, data.result.copyBytes())
        assertEquals(0, fixture.source.calls)
        // No supported native refusal carrier, terminal validation, journal acceptance or ACK.
    }

    @Test
    fun ffrc011ffrc012MismatchedScalarOriginalIdentityCannotAdoptPrivateRequirements() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val body = LocalFoodPaymentCodec.encode(f.apply)
        val context = f.commandContext(fixture.paymentHandler.key, body)
        val provider = assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(fixture.paymentHandler)
        val identities = listOf(f.commandContext(context.key, body, actor = f.otherActor, digest = context.semanticDigest),
            f.commandContext(context.key, body, operation = f.operation("different-application"), digest = context.semanticDigest),
            f.commandContext(CommandKey(context.key.name, 2), body, digest = context.semanticDigest),
            f.commandContext(context.key, body, scope = f.scope.copy(branchId = f.id("R2")), digest = context.semanticDigest),
            f.commandContext(context.key, body, scope = f.scope.copy(companyId = f.id("other-company")), digest = context.semanticDigest),
            f.commandContext(context.key, body, scope = f.scope.copy(organizationId = f.id("other-organization")), digest = context.semanticDigest),
            f.commandContext(context.key, body, digest = sha256("different-retained-semantic-data".encodeToByteArray())))
        val data = identities.map { scalarRetainedData(it, body, code = "committed") } +
            scalarRetainedData(context, LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.ApplyOriginal(
                f.operation("different-recording"))), code = "committed")
        for (original in data) {
            val view = FoodCashFinancialRpcDenialDataView(originals = mapOf(context.operationId to original))
            assertNull(provider.rpcRetainedResultRequirements(profile, body, view, context))
            assertEquals(listOf(context.operationId), view.retainedReads)
            assertTrue(view.aggregateReads.isEmpty())
            assertTrue(view.claimReads.isEmpty())
        }
        assertEquals(0, fixture.source.calls)
        // Scalar coordinate rejection only, not native order/session/attempt/lifetime substitutions.
    }

    @Test
    fun ffrc012ffrc013ByteEqualCommittedScalarDataCannotQualifyMissingOriginalCustody() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val body = LocalFoodPaymentCodec.encode(f.apply)
        val context = f.commandContext(fixture.paymentHandler.key, body)
        val data = scalarRetainedData(context, body, code = "committed")
        val view = FoodCashFinancialRpcDenialDataView(originals = mapOf(f.application to data, f.recording to null))
        val provider = assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(fixture.paymentHandler)
        val before = data.result.copyBytes()
        assertEquals(context.semanticDigest, data.semanticDigest)
        assertEquals(body, data.normalized)
        assertTrue(data.committed, "An inert declaration, not a genuine durable commit")
        val requirements = try { provider.rpcRetainedResultRequirements(profile, body, view, context) }
            catch (_: IllegalStateException) { null } // Missing original lineage is storage/source uncertainty.
        assertNull(requirements)
        assertEquals(listOf(f.application, f.recording), view.retainedReads)
        assertTrue(view.aggregateReads.isEmpty())
        assertTrue(view.claimReads.isEmpty())
        assertContentEquals(before, data.result.copyBytes())
        assertEquals(0, fixture.source.calls)
        // No synthetic raw, Start, EVENT, retained handle or native source repairs this DATA.
    }

    @Test
    fun ffrc006ffrc013StockFinancialLookupHasNoScalarOrContextOnlyPermissionFallback() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val sourceProvider = assertIs<LocalMasterRpcRetainedResultSourceAccessProvider>(fixture.stockHandler)
        val contextProvider = assertIs<LocalMasterRpcRetainedResultAccessProvider>(fixture.stockHandler)
        for (request in listOf(f.begin, f.finalize)) {
            val body = LocalFoodStockCodecV2.encode(request)
            val context = f.commandContext(fixture.stockHandler.key, body)
            val view = FoodCashFinancialRpcDenialDataView(originals = mapOf(context.operationId to
                scalarRetainedData(context, body, code = "food.payment.not_available")))
            assertFailsWith<IllegalStateException> {
                sourceProvider.rpcRetainedResultRequirements(profile, body, view, context)
            }
            assertFailsWith<IllegalStateException> { contextProvider.rpcRetainedResultRequirements(profile, body, context) }
            assertNoSourceReads(view)
        }
        assertEquals(0, fixture.source.calls)
        // Actual streamed BEFORE owner/quote is required; no positive/zero permission matrix.
    }

    @Test
    fun ffrc012ffrc013ffrc014MissingOrForeignApplyHeaderDeclaresCashReadWithoutAccounting() = runTest {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val body = LocalFoodPaymentCodec.encode(f.apply)
        val context = f.commandContext(fixture.paymentHandler.key, body, actor = f.otherActor)
        val rawKey = foodPaymentRawKey(f.recording)
        val rows = listOf(null, rawHeaderData(f.actor, f.actor), rawHeaderData(f.otherActor, f.actor),
            rawHeaderData(f.actor, f.otherActor))
        for (row in rows) {
            val view = FoodCashFinancialRpcDenialDataView(rows = mapOf(rawKey to row))
            val prepared = fixture.paymentHandler.prepare(body, view, context)
            val requirement = prepared.requirement
            assertEquals(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), requirement.permission)
            assertEquals(context.scope, requirement.scope)
            assertEquals(OwnerConstraint.OwnerPrivate(context.originalActorId), requirement.owner)
            assertEquals(LimitUse.NotApplicable, requirement.limitUse)
            val binding = assertNotNull(requirement.binding)
            assertEquals(DomainId(f.recording.value), binding.documentId)
            assertEquals(Revision(0), binding.revision)
            assertEquals(context.operationId, binding.operationId)
            assertEquals(context.semanticDigest, binding.payloadDigest)
            assertNull(prepared.admissionRestriction) // A declaration does not qualify its refusal carrier.
            assertFalse(prepared.allowsActionApproval)
            assertEquals(listOf(rawKey), view.aggregateReads)
            assertTrue(view.retainedReads.isEmpty())
            view.assertSuppliedDataUnchanged()

            if (row != null) {
                val readBody = LocalFoodPaymentReadCodec.encode(LocalFoodPaymentQuery.Original(f.recording))
                val readView = FoodCashFinancialRpcDenialDataView(rows = mapOf(rawKey to row))
                val readContext = f.readContext(fixture.paymentRead.key, readBody, actor = f.otherActor)
                val read = fixture.paymentRead.prepare(readBody, readView, readContext)
                assertEquals(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), read.requirements.single().permission)
                assertEquals(OwnerConstraint.OwnerPrivate(f.otherActor), read.requirements.single().owner)
                assertFailsWith<IllegalStateException> { read.project(emptyList(), f.at) }
                assertEquals(listOf(rawKey), readView.aggregateReads)
                assertTrue(readView.retainedReads.isEmpty())
                readView.assertSuppliedDataUnchanged()
            }
        }
        assertEquals(0, fixture.source.calls)
        // Prepare only inspects denial DATA; never evaluate, acquire, account, ACK or disclose.
        // Genuine pending/already-applied vs new-accounting access still needs full native sources.
    }

    private fun assertNoSourceReads(view: FoodCashFinancialRpcDenialDataView) {
        assertTrue(view.aggregateReads.isEmpty())
        assertTrue(view.claimReads.isEmpty())
        assertTrue(view.retainedReads.isEmpty())
        assertTrue(view.pageReads.isEmpty())
    }

    /** Matching typed DATA/hashes do not create a retained native producer or a durable result. */
    private fun scalarRetainedData(identity: CommandContext, body: StoredBytes, code: String): RetainedCommandEvidence {
        val result = LocalFoodPaymentCodec.result(if (code == "committed")
            LocalFoodPaymentResult.Applied(f.target, LocalFoodPaymentAction.CASH_PAID, identity.operationId, emptyList())
            else LocalFoodPaymentResult.Unavailable("food.payment.not_available"))
        return RetainedCommandEvidence(identity.scope, identity.operationId, identity.originalActorId, identity.key,
            f.id("unqualified-assignment-data"), Revision(1), JournalSequence(1), f.at,
            f.id("unqualified-authentication-data"), Revision(1), identity.semanticDigest, code, body, result,
            sha256(result.copyBytes()), sha256("unqualified-previous-journal-data".encodeToByteArray()),
            sha256("unqualified-journal-data".encodeToByteArray()), emptyList(), emptyList(), emptyList())
    }

    /** Denial-only header bytes; no slot, native reference, producer or accepting source exists. */
    private fun rawHeaderData(originalActor: DomainId, recorder: DomainId): StoredAggregate {
        val value = LocalFoodPaymentRaw(1, f.scope, f.target, originalActor, recorder,
            f.originalBody.copyBytes().decodeToString(), sha256(f.originalBody.copyBytes()),
            semanticDigest(f.scope, originalActor, LocalFoodPaymentProfile.COMMAND, f.originalBody),
            sha256("unqualified-slot-header-data".encodeToByteArray()), f.at)
        return foodFinancialRpcStoredData(foodPaymentRawKey(f.recording), Revision(1),
            LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.ORIGINAL), localSalesEncode(value,
                LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES))
    }
}
