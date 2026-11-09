package com.ukkera.pos.app.gateway.independent.foodcheckrpcprofilev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Frozen FCRP-001/002/003/005/006/007/010 executable membership and negative
 * input SUPPORT slices only. Exact v1 ORDER and Draft bytes are frozen oracles;
 * own-list DATA and availability replies are controlled inputs, not real reads.
 * Add revision templates, v2/menu/preview wire bindings, Master authentication,
 * native sources, positive actual-revision qualification and RPC remain UNBOUND.
 */
class FoodCheckRpcProfileMembershipSupportingTest {
    private val frozen get() = FrozenFoodCheckRpcProfileInputs

    @Test
    fun fcrp001AndFcp3009RegisteredDescriptorsMatchTheExplicitVersionThreeExtension() {
        val procedures = listOf(LocalMasterFoodCheckRpcProtocolV1.READ, LocalMasterFoodCheckRpcProtocolV1.EXECUTE,
            LocalMasterFoodCheckRpcProtocolV1.LOOKUP)
        assertEquals(frozen.profile.getValue("procedures").jsonArray.map { entry ->
            entry.jsonObject.string("key") to entry.jsonObject.getValue("version").jsonPrimitive.int
        }, procedures.map { it.name to it.version })
        assertEquals("FOOD_CHECKS", LocalMasterRpcWorkflowProfile.FOOD_CHECKS.name)
        val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CHECKS)
        assertTrue(installed.isFoodChecksOnly)
        assertEquals(setOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD),
            LocalAction.entries.filter(installed::allows).toSet())
        val command = frozen.profile.getValue("admitted_command").jsonObject
        val expectedCommand = CommandKey(command.string("key"), command.getValue("version").jsonPrimitive.int)
        assertEquals(setOf(expectedCommand), foodCheckCommands)
        assertTrue(installed.has(expectedCommand))
        assertTrue(LocalMasterFoodCheckRpcKeysV1.accepts(expectedCommand))
        assertFalse(LocalMasterFoodCheckRpcKeysV1.accepts(CommandKey(expectedCommand.name, 1)))
        assertFalse(LocalMasterFoodCheckRpcKeysV1.accepts(LocalFoodProfileV1.COMMAND))
        val expectedReads = frozen.profile.getValue("reads").jsonArray.flatMap { entry ->
            val read = entry.jsonObject
            val versions = read["versions"]?.jsonArray?.map { it.jsonPrimitive.int }
                ?: listOf(read.getValue("version").jsonPrimitive.int)
            versions.map { ReadKey(read.string("key"), it) }
        }.toSet() + ReadKey("sales.food.check-kitchen.read", 3)
        // FOOD-CURRENT-V3-CASES-1 / FCP3-009 adds only this exact read key to the
        // preserved FCRP-001 set. This is the combined versioned assertion, not
        // a claim that the earlier exact-membership fixture includes version 3.
        assertEquals(expectedReads, foodCheckReads)
        expectedReads.forEach { assertTrue(installed.has(it)); assertTrue(LocalMasterFoodCheckRpcKeysV1.accepts(it)) }
        // Descriptor/key equality is not actual registration or canonical v2/menu/preview binding.
    }

    @Test
    fun fcrp002OnlyDocumentedV1OrderAndCanonicalProfileBodiesHaveExactByteOracles() {
        val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CHECKS)
        val bytes = frozen.bytes(frozen.v1OrderSelector)
        assertEquals(frozen.v1OrderSelector,
            LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(frozen.order)).copyBytes().decodeToString())
        installed.requireRead(ReadRequest(LocalFoodProfileV1.QUERY, bytes))
        installed.requireRead(ReadRequest(LocalFoodCheckKitchenCodecV1.QUERY, bytes))
        assertEquals("{}", LocalFoodQuotationProfileReadCodecV1.encode().copyBytes().decodeToString())
        installed.requireRead(LocalFoodQuotationProfileReadCodecV1.read())
        // The admitted /2 identity does not supply its otherwise UNBOUND body/result.
    }

    @Test
    fun fcrp002ExcludedSelectorsAndWrongReadVersionsNeverReachTheEndpoint() = runTest {
        withFoodCheckSupportFixture { f ->
            f.withReadAccess { access ->
                val excluded = listOf(
                    LocalFoodReadCodecV1.read(LocalFoodQueryV1.Handoff(frozen.order)),
                    LocalFoodReadCodecV1.read(LocalFoodQueryV1.Station(frozen.id("choice"))),
                    LocalFoodReadCodecV1.read(LocalFoodQueryV1.Task(frozen.id("choice"), frozen.portions.first())),
                    ReadRequest(LocalFoodCheckKitchenCodecV1.QUERY, LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Own())),
                    ReadRequest(ReadKey("sales.food.read", 2), frozen.bytes(frozen.v1OrderSelector)),
                    ReadRequest(ReadKey("sales.food.quotation.profile", 2), frozen.bytes("{}")),
                    ReadRequest(ReadKey("local.food.menu", 2), frozen.bytes("{}")),
                    ReadRequest(ReadKey("sales.food.write", 1), frozen.bytes("{}")),
                )
                for (request in excluded) assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                    assertFailsWith<GatewayFailure> { access.read(request) }.problem)
            }
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.journal.entries.isEmpty())
        }
    }

    @Test
    fun fcrp002MalformedNoncanonicalAndUnknownReadFieldsStopBeforeTheEndpoint() = runTest {
        withFoodCheckSupportFixture { f ->
            f.withReadAccess { access ->
                val order = frozen.v1OrderSelector
                val invalid = listOf(
                    ReadRequest(LocalFoodProfileV1.QUERY, frozen.bytes("{")),
                    ReadRequest(LocalFoodCheckKitchenCodecV1.QUERY, frozen.bytes(" $order")),
                    ReadRequest(LocalFoodCheckKitchenCodecV1.QUERY, frozen.bytes(order.dropLast(1) + ",\"profile\":\"FOOD_CHECKS\"}")),
                    ReadRequest(LocalFoodCheckKitchenCodecV1.QUERY, frozen.bytes(order.replace("\"limit\":8", "\"limit\":9"))),
                    ReadRequest(LocalFoodQuotationProfileReadCodecV1.QUERY, frozen.bytes("{ }")),
                    ReadRequest(LocalFoodQuotationProfileReadCodecV1.QUERY, frozen.bytes("{\"installed\":true}")),
                    ReadRequest(LocalFoodQuotationProfileReadCodecV1.QUERY, StoredBytes(byteArrayOf(0xff.toByte()))),
                )
                for (request in invalid) assertEquals(LocalServiceProblem.INVALID_INPUT,
                    assertFailsWith<GatewayFailure> { access.read(request) }.problem)
            }
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
        }
    }

    @Test
    fun fcrp003Draft2AndAdd3AreOnlyAdmittedIntentShapesNotPositiveSourceBindings() {
        val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CHECKS)
        installed.requireIntent(frozen.draftIntent())
        installed.requireIntent(frozen.unboundAddIntent())
        installed.requireIntent(frozen.unboundAddIntent(lines = 8))
        val invalid = listOf(frozen.draftIntent(1), frozen.draftIntent(3), frozen.unboundAddIntent(1),
            frozen.unboundAddIntent(2), frozen.unboundAddIntent(lines = 0), frozen.unboundAddIntent(lines = 9)) +
            listOf(LocalAction.FOOD_CREATE, LocalAction.FOOD_SEND, LocalAction.FOOD_STATION_START,
                LocalAction.FOOD_OFFER_HANDOFF, LocalAction.FOOD_EDIT_SERVICE_DETAILS, LocalAction.FOOD_BEGIN_COLLECTION,
                LocalAction.PAYMENT_ATTEST, LocalAction.FOOD_ASSIGN_TABLE).map {
                LocalCommandIntent(frozen.draftOperation, it, 1, null, emptyList())
            }
        for (intent in invalid) assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
            assertFailsWith<GatewayFailure> { installed.requireIntent(intent) }.problem)
        // The accepted Add UI shape has no bound revisions, menu or canonical executable request.
    }

    @Test
    fun fcrp003CanonicalDraftCannotUseWrongActionKeyApprovalOrCallerExtension() {
        val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CHECKS)
        val request = frozen.draftRequest()
        assertEquals(frozen.canonicalDraft, request.payload.copyBytes().decodeToString())
        installed.requireMutation(LocalAction.FOOD_CREATE_DRAFT, request)
        for (action in listOf(LocalAction.FOOD_ADD, LocalAction.FOOD_SEND, LocalAction.FOOD_STATION_READY,
            LocalAction.FOOD_BEGIN_COLLECTION, LocalAction.FOOD_ASSIGN_TABLE))
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertFailsWith<GatewayFailure> { installed.requireMutation(action, request) }.problem)
        val excluded = listOf(
            MutationRequest(request.operationId, CommandKey(request.command.name, 1), request.payload),
            MutationRequest(request.operationId, LocalFoodProfileV1.COMMAND, request.payload),
            MutationRequest(request.operationId, request.command, request.payload, frozen.id("sessionA")),
            frozen.draftRequest(legacy = true),
        )
        for (variant in excluded) assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
            assertFailsWith<GatewayFailure> { installed.requireMutation(LocalAction.FOOD_CREATE_DRAFT, variant) }.problem)
        for (field in listOf("\"profile\":\"FOOD_CHECKS\"", "\"capability\":true", "\"permission\":\"ALLOW\"")) {
            val payload = frozen.bytes(frozen.canonicalDraft.dropLast(1) + ",$field}")
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, assertFailsWith<GatewayFailure> {
                installed.requireMutation(LocalAction.FOOD_CREATE_DRAFT,
                    MutationRequest(request.operationId, request.command, payload))
            }.problem)
        }
    }

    @Test
    fun fcrp005DiscoveryAcceptsOnlyFrozenMandatoryBooleanShapesWithinTheByteCeiling() {
        assertTrue(LocalFoodQuotationProfileReadCodecV1.result(frozen.bytes("{\"installed\":true}")).installed)
        assertFalse(LocalFoodQuotationProfileReadCodecV1.result(frozen.bytes("{\"installed\":false}")).installed)
        val invalid = listOf("{}", "{\"installed\":\"true\"}", "{\"installed\":null}",
            "{\"installed\":true,\"profile\":\"FOOD_CHECKS\"}", " {\"installed\":true}")
        invalid.forEach { assertFails { LocalFoodQuotationProfileReadCodecV1.result(frozen.bytes(it)) } }
        for (length in listOf(2_048, 2_049)) {
            val body = "{\"installed\":true}"
            val bytes = frozen.bytes(body + " ".repeat(length - body.length))
            assertEquals(length, bytes.size)
            assertFails { LocalFoodQuotationProfileReadCodecV1.result(bytes) }
        }
        assertFails { LocalFoodQuotationProfileReadCodecV1.result(StoredBytes(byteArrayOf(0xff.toByte()))) }
        // No canonical positive response of length2048, native installation or grant is fabricated.
    }

    @Test
    fun fcrp006FalseDeniedMalformedOrUnboundFreshAddNeverStagesADowngrade() = runTest {
        val replies = listOf(
            ReadReply.Data(frozen.bytes("{\"installed\":false}"), com.ukkera.pos.types.Revision(1),
                com.ukkera.pos.types.UtcInstant.parseCanonical("1970-01-01T00:00:00Z")),
            ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED),
            ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN),
            ReadReply.Data(frozen.bytes("{}"), com.ukkera.pos.types.Revision(1),
                com.ukkera.pos.types.UtcInstant.parseCanonical("1970-01-01T00:00:00Z")),
            ReadReply.Data(frozen.bytes("{\"installed\":true}"), com.ukkera.pos.types.Revision(1),
                com.ukkera.pos.types.UtcInstant.parseCanonical("1970-01-01T00:00:00Z")),
        )
        for (reply in replies) withFoodCheckSupportFixture { f ->
            f.master.readReply = { request ->
                assertEquals(LocalFoodQuotationProfileReadCodecV1.QUERY, request.query)
                assertEquals("{}", request.payload.copyBytes().decodeToString())
                reply
            }
            assertIs<LocalStageResult.NotStaged>(f.gateway.stage(frozen.unboundAddIntent()))
            assertEquals(listOf(LocalFoodQuotationProfileReadCodecV1.QUERY), f.master.reads.map { it.query })
            assertTrue(f.journal.entries.isEmpty())
            assertEquals(0, f.journal.stageCalls)
            assertEquals(0, f.journal.dispatchCalls)
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
        }
        assertTrue(frozen.templateStatus.startsWith("NON_EXECUTABLE"))
        // Even installed:true cannot supply the missing independently bound revision/selection.
    }

    @Test
    fun fcrp006CreateAndOldAddPreviewsRefuseWithoutDispatchOrReplacementStage() = runTest {
        for (input in listOf(frozen.preview(LocalAction.FOOD_CREATE, 1), frozen.preview(version = 1),
            frozen.preview(version = 2), frozen.preview())) withFoodCheckSupportFixture { f ->
            assertIs<LocalFoodPricePreviewResult.Unavailable>(f.gateway.previewFood(input))
            assertTrue(f.journal.entries.isEmpty())
            assertEquals(0, f.journal.stageCalls)
            assertEquals(0, f.journal.dispatchCalls)
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            if (input.action != LocalAction.FOOD_ADD || input.formVersion != 3) assertTrue(f.master.reads.isEmpty())
            else assertEquals(listOf(LocalFoodQuotationProfileReadCodecV1.QUERY), f.master.reads.map { it.query })
        }
    }

    @Test
    fun fcrp007ConditionalDeliveryInputKeepsExactPublicDraftRepresentation() = runTest {
        withFoodCheckSupportFixture { f ->
            assertIs<LocalStageResult.Staged>(f.gateway.stage(frozen.draftIntent()))
            frozen.assertDraftOriginal(f.journal.entries.getValue(frozen.draftOperation))
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
        }
        withFoodCheckSupportFixture { f ->
            val intent = frozen.draftIntent(channel = "DELIVERY", includeDelivery = true)
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val raw = f.journal.entries.getValue(frozen.draftOperation).request().payload.copyBytes().decodeToString()
            val expected = frozen.canonicalDraft.replace("\"channel\":\"DINE_IN\"", "\"channel\":\"DELIVERY\"")
                .dropLast(2) + ",\"deliveryDetails\":${frozen.delivery}}}"
            assertEquals(expected, raw)
            assertEquals(frozen.delivery, frozen.wire.parseToJsonElement(raw).jsonObject.getValue("request").jsonObject
                .getValue("deliveryDetails").jsonObject)
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
        }
        for (intent in listOf(frozen.draftIntent(channel = "DELIVERY"), frozen.draftIntent(includeDelivery = true)))
            withFoodCheckSupportFixture { f ->
                assertIs<LocalStageResult.NotStaged>(f.gateway.stage(intent))
                assertTrue(f.journal.entries.isEmpty())
                assertEquals(0, f.journal.stageCalls)
                assertTrue(f.master.mutations.isEmpty())
            }
    }

    @Test
    fun fcrp001fcrp010RepeatedOwnListRenderingNeverDispatchesOrConstructsExcludedForms() = runTest {
        withFoodCheckSupportFixture { f ->
            repeat(2) {
                val page = assertIs<LocalReadResult.Available>(f.gateway.read(
                    LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.MyChecks()))).page
                assertEquals(listOf(LocalAction.FOOD_CREATE_DRAFT), page.forms.map { it.action })
                assertEquals(listOf(2), page.forms.map { it.version })
                assertTrue(page.records.isEmpty())
            }
            for (query in listOf(LocalWorkflowQuery(LocalWorkflow.KITCHEN), LocalWorkflowQuery(LocalWorkflow.CASH),
                LocalWorkflowQuery(LocalWorkflow.REPORTS), LocalWorkflowQuery(LocalWorkflow.INVENTORY),
                LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.IncomingKnownOrder(frozen.order))))
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                    assertIs<LocalReadResult.Unavailable>(f.gateway.read(query)).problem)
            assertEquals(2, f.master.reads.size)
            assertTrue(f.journal.entries.isEmpty())
            assertEquals(0, f.journal.stageCalls)
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            // Supplied empty owner-list DATA is not an actual empty branch/order census.
        }
    }
}
