package com.ukkera.pos.database.frozen.employeecreationrpcv1

import com.ukkera.pos.database.LocalAuthenticationCodec
import com.ukkera.pos.database.LocalEffectivePolicyCodec
import com.ukkera.pos.database.LocalMasterEmployeeCreationRpcProtocolV1 as P
import com.ukkera.pos.database.LocalMasterRpcEmployeeCreationCallV1
import com.ukkera.pos.database.LocalMasterRpcEmployeeCreationRequestV1
import com.ukkera.pos.database.LocalMasterRpcEmployeeCreationResponseV1
import com.ukkera.pos.database.LocalMasterRpcEmployeeCreationResultV1
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAuthConfiguration
import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalEmployeeCreationAuthenticationProfileV1
import com.ukkera.pos.security.LocalEmployeeCreationCancellationV1
import com.ukkera.pos.security.LocalEmployeeCreationConfigurationV1
import com.ukkera.pos.security.LocalEmployeeCreationContextResultV1
import com.ukkera.pos.security.LocalEmployeeCreationContextV1
import com.ukkera.pos.security.LocalEmployeeCreationDefinitionResultV1
import com.ukkera.pos.security.LocalEmployeeCreationDraftV1
import com.ukkera.pos.security.LocalEmployeeCreationExecutionV1
import com.ukkera.pos.security.LocalEmployeeCreationInputFailureV1
import com.ukkera.pos.security.LocalEmployeeCreationReceiptV1
import com.ukkera.pos.security.LocalEmployeeCreationReviewV1
import com.ukkera.pos.security.LocalEmployeeCreationServiceV1
import com.ukkera.pos.security.LocalEmployeeCreationStatusV1
import com.ukkera.pos.security.LocalEmployeeCreationTargetV1
import com.ukkera.pos.security.LocalEmployeeEnrollmentDefinition
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Frozen expectations for the remote employee-creation RPC (v1), authored on 13 November 2026
 * BEFORE the protocol, request custody, Master dispatch or Client decoding existed.
 * NOT independent (same author, owner instruction). Synthetic identifiers only.
 *
 * Fixed: one procedure `pos.employee-creation` v1, never re-invoked; the bearer and optional
 * password are owned by the request and wiped on destroy; the Master dispatch passes the
 * transport-authenticated credentials (never a body claim) to the real creation service and
 * binds the response to the verified requesting actor; the Client accepts a result only when it
 * names the exact queried target/original and actor, refusing any other binding.
 */
class EmployeeCreationRpcV1FrozenTest {
    private val scope = BranchScope(DomainId("11111111-1111-4111-8111-111111111111"),
        DomainId("22222222-2222-4222-8222-222222222222"), DomainId("33333333-3333-4333-8333-333333333333"))
    private val creator = DomainId("44444444-4444-4444-8444-444444444444")
    private val recipient = DomainId("55555555-5555-4555-8555-555555555555")
    private val device = DomainId("66666666-6666-4666-8666-666666666666")
    private val requestId = DomainId("77777777-7777-4777-8777-777777777777")
    private val connection = DomainId("88888888-8888-4888-8888-888888888888")
    private val tokens = LocalTokenPolicy(900_000, 28_800_000)
    private val admission = LocalDeviceAdmission(scope, device, Revision(2), true, "key-1", "fp-1", setOf("pos.counter"))
    private val json = Json

    private fun credentials() = LocalGatewayCredentials(connection, OpaqueCredential(ByteArray(52) { (it + 1).toByte() }))

    private fun definition() = LocalAccountProvisioning(recipient, LocalLoginIdentifier("cashier.b"),
        ActivatedLocalPolicy(scope, recipient, Revision(0), emptyList(), LocalSessionPolicy(), ThrottlePolicy()), tokens, setOf(device))

    private fun draft(withPassword: Boolean = true) = LocalEmployeeCreationDraftV1(requestId, definition(), withPassword)

    private fun original(withPassword: Boolean = true): LocalDelegatedEmployeeCreationV1 {
        val value = definition()
        val policy = LocalEffectivePolicyCodec.encodePolicy(value.policy)
        val configuration = LocalAuthenticationCodec.encodeConfiguration(
            LocalAuthConfiguration(scope, recipient, Revision(0), value.login, value.tokenPolicy))
        return LocalDelegatedEmployeeCreationV1.capture(creator,
            LocalEmployeeEnrollmentDefinition.capture(requestId, value, withPassword, policy, configuration, listOf(admission)))
    }

    private fun receipt(of: LocalDelegatedEmployeeCreationV1) =
        LocalEmployeeCreationReceiptV1(scope, of.requestId, of.operatorId, of.actorId, of.contentDigest)

    private fun context() = LocalEmployeeCreationContextV1(scope, creator, LocalEmployeeCreationTargetV1(requestId, recipient),
        Revision(4), LocalEmployeeCreationConfigurationV1.capture(listOf(LocalEmployeeCreationAuthenticationProfileV1(
            LocalSessionPolicy(), ThrottlePolicy(), tokens, null, null))), listOf(admission))

    private fun wire(request: LocalMasterRpcEmployeeCreationRequestV1): LocalMasterRpcEmployeeCreationRequestV1 =
        json.decodeFromString(P.PROCEDURE.request, json.encodeToString(P.PROCEDURE.request, request))

    private fun wire(response: LocalMasterRpcEmployeeCreationResponseV1): LocalMasterRpcEmployeeCreationResponseV1 =
        json.decodeFromString(P.PROCEDURE.response, json.encodeToString(P.PROCEDURE.response, response))

    /** Records exactly what the Master service received and replies with scripted results. */
    private inner class Service : LocalEmployeeCreationServiceV1 {
        val seen = mutableListOf<String>()
        var password: CharArray? = null
        var seenCredentials: LocalGatewayCredentials? = null
        var contextResult: LocalEmployeeCreationContextResultV1 = LocalEmployeeCreationContextResultV1.Available(context())
        var captureResult: LocalEmployeeCreationDefinitionResultV1 = LocalEmployeeCreationDefinitionResultV1.Captured(original())
        var executeResult: LocalEmployeeCreationExecutionV1 = LocalEmployeeCreationExecutionV1.Inserted(receipt(original()))
        override suspend fun employeeCreationContext(operator: LocalGatewayCredentials, target: LocalEmployeeCreationTargetV1) =
            contextResult.also { seenCredentials = operator; seen += "context:${target.requestId.value}:${target.actorId.value}" }
        override suspend fun captureEmployeeCreationDefinition(operator: LocalGatewayCredentials, draft: LocalEmployeeCreationDraftV1) =
            captureResult.also { seenCredentials = operator
                seen += "capture:${draft.requestId.value}:${draft.credentialWasProvided}:${draft.copyDefinition().login.value}" }
        override suspend fun executeEmployeeCreation(operator: LocalGatewayCredentials, original: LocalDelegatedEmployeeCreationV1,
            newPassword: CharArray?) = executeResult.also { seenCredentials = operator
                password = newPassword?.copyOf(); seen += "execute:${original.contentDigest.value}" }
        override suspend fun cancelEmployeeCreation(operator: LocalGatewayCredentials, original: LocalDelegatedEmployeeCreationV1) =
            LocalEmployeeCreationCancellationV1.Recorded(receipt(original)).also { seen += "cancel:${original.contentDigest.value}" }
        override suspend fun reviewEmployeeCreation(operator: LocalGatewayCredentials, original: LocalDelegatedEmployeeCreationV1) =
            LocalEmployeeCreationReviewV1.InputsChecked(original.requestId, original.operatorId, original.actorId, original.contentDigest)
                .also { seen += "review:${original.contentDigest.value}" }
        override suspend fun lookupEmployeeCreation(operator: LocalGatewayCredentials, original: LocalDelegatedEmployeeCreationV1) =
            LocalEmployeeCreationStatusV1.Applied(receipt(original)).also { seen += "lookup:${original.contentDigest.value}" }
    }

    private fun roundTrip(service: Service, request: LocalMasterRpcEmployeeCreationRequestV1,
        actor: DomainId = creator): LocalMasterRpcEmployeeCreationResponseV1 = runBlocking {
        val received = wire(request)
        val operator = credentials()
        try { wire(P.dispatch(service, operator, received, actor)) } finally { received.destroy() }
    }

    @Test
    fun procedureIdentityAndLimitsAreFixed() {
        assertEquals("pos.employee-creation", P.PROCEDURE.name)
        assertEquals(1, P.PROCEDURE.version)
        assertEquals(RpcRetrySafety.NeverReinvoke, P.PROCEDURE.retrySafety)
        assertEquals(1_048_576, P.PROCEDURE.requestLimitBytes)
        assertEquals(1_048_576, P.PROCEDURE.responseLimitBytes)
        assertEquals(64, P.PROCEDURE.errorLimitBytes)
    }

    @Test
    fun everyCallSurvivesTheWireWithItsExactFieldsAndBearer() {
        val o = original()
        val target = LocalEmployeeCreationTargetV1(requestId, recipient)
        val ctx = wire(LocalMasterRpcEmployeeCreationRequestV1.context(credentials(), target))
        assertEquals(LocalMasterRpcEmployeeCreationCallV1.Context(requestId, recipient), ctx.call)
        ctx.useBearer { assertContentEquals(ByteArray(52) { (it + 1).toByte() }, it) }
        val cap = assertIs<LocalMasterRpcEmployeeCreationCallV1.Capture>(
            wire(LocalMasterRpcEmployeeCreationRequestV1.capture(credentials(), draft())).call)
        assertTrue(cap.draft().matchesRequested(o))
        for ((request, kind) in listOf(
            LocalMasterRpcEmployeeCreationRequestV1.review(credentials(), o) to LocalMasterRpcEmployeeCreationCallV1.Review::class,
            LocalMasterRpcEmployeeCreationRequestV1.lookup(credentials(), o) to LocalMasterRpcEmployeeCreationCallV1.Lookup::class,
            LocalMasterRpcEmployeeCreationRequestV1.cancel(credentials(), o) to LocalMasterRpcEmployeeCreationCallV1.Cancel::class,
            LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), o, "s3cret-pass".toCharArray()) to
                LocalMasterRpcEmployeeCreationCallV1.Execute::class)) {
            val decoded = wire(request)
            assertEquals(kind, decoded.call::class)
            assertContentEquals(o.copyWireBytes(), decoded.call.original()!!.copyWireBytes())
        }
    }

    @Test
    fun passwordIsCarriedOnlyByExecuteAndWipedWithTheRequest() {
        val supplied = "s3cret-pass".toCharArray()
        val request = LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), original(), supplied)
        assertContentEquals("s3cret-pass".toCharArray(), supplied) // caller-owned input is never cleared
        val decoded = wire(request)
        decoded.usePassword { assertContentEquals("s3cret-pass".toCharArray(), it) }
        decoded.destroy()
        assertFails { decoded.usePassword { } }
        assertFails { LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), original(), "abc".toCharArray()) }
        assertFails { LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), original(withPassword = true), null) }
        assertFails { LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), original(withPassword = false), "abcd".toCharArray()) }
    }

    @Test
    fun masterDispatchUsesTransportCredentialsAndBindsTheVerifiedActor() {
        val service = Service()
        val o = original()
        val target = LocalEmployeeCreationTargetV1(requestId, recipient)

        val ctx = roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.context(credentials(), target))
        assertEquals(creator, ctx.requestingActorId)
        val available = assertIs<LocalEmployeeCreationContextResultV1.Available>(P.decodeContext(target, creator, ctx))
        assertEquals(setOf(device), available.context.admittedDeviceIds)
        assertEquals(Revision(4), available.context.policyRevision)
        assertEquals(connection, service.seenCredentials!!.connectionId)

        val captured = assertIs<LocalEmployeeCreationDefinitionResultV1.Captured>(P.decodeCapture(draft(), creator,
            roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.capture(credentials(), draft()))))
        assertContentEquals(o.copyWireBytes(), captured.original.copyWireBytes())

        assertIs<LocalEmployeeCreationReviewV1.InputsChecked>(P.decodeReview(o, creator,
            roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.review(credentials(), o))))
        assertIs<LocalEmployeeCreationStatusV1.Applied>(P.decodeLookup(o, creator,
            roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.lookup(credentials(), o))))
        assertIs<LocalEmployeeCreationExecutionV1.Inserted>(P.decodeExecution(o, creator,
            roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), o, "s3cret-pass".toCharArray()))))
        assertContentEquals("s3cret-pass".toCharArray(), service.password)
        assertIs<LocalEmployeeCreationCancellationV1.Recorded>(P.decodeCancellation(o, creator,
            roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.cancel(credentials(), o))))
        assertEquals(listOf("context:${requestId.value}:${recipient.value}", "capture:${requestId.value}:true:cashier.b",
            "review:${o.contentDigest.value}", "lookup:${o.contentDigest.value}", "execute:${o.contentDigest.value}",
            "cancel:${o.contentDigest.value}"), service.seen)
    }

    @Test
    fun refusalsAndNonReceiptOutcomesKeepTheirMeaning() {
        val service = Service()
        val o = original()
        service.executeResult = LocalEmployeeCreationExecutionV1.Refused(
            LocalEmployeeCreationReviewV1.Refused(LocalEmployeeCreationInputFailureV1.ADMISSIONS_CHANGED))
        val refused = assertIs<LocalEmployeeCreationExecutionV1.Refused>(P.decodeExecution(o, creator,
            roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), o, "s3cret-pass".toCharArray()))))
        assertEquals(LocalEmployeeCreationInputFailureV1.ADMISSIONS_CHANGED, refused.review.reason)
        for (value in listOf(LocalEmployeeCreationExecutionV1.Denied, LocalEmployeeCreationExecutionV1.Busy,
                LocalEmployeeCreationExecutionV1.Unavailable, LocalEmployeeCreationExecutionV1.StorageUncertain)) {
            service.executeResult = value
            assertSame(value, P.decodeExecution(o, creator,
                roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), o, "s3cret-pass".toCharArray()))))
        }
        for (status in listOf(LocalEmployeeCreationStatusV1.Applied(receipt(o)), LocalEmployeeCreationStatusV1.Cancelled(receipt(o)),
                LocalEmployeeCreationStatusV1.IdentityConflict)) {
            service.executeResult = LocalEmployeeCreationExecutionV1.Existing(status)
            val existing = assertIs<LocalEmployeeCreationExecutionV1.Existing>(P.decodeExecution(o, creator,
                roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), o, "s3cret-pass".toCharArray()))))
            assertEquals(status::class, existing.status::class)
        }
        service.contextResult = LocalEmployeeCreationContextResultV1.Denied
        val target = LocalEmployeeCreationTargetV1(requestId, recipient)
        assertSame(LocalEmployeeCreationContextResultV1.Denied, P.decodeContext(target, creator,
            roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.context(credentials(), target))))
    }

    @Test
    fun clientRefusesAnyResponseBoundToAnotherActorTargetOrOriginal() {
        val service = Service()
        val o = original()
        val other = original(withPassword = false)
        val target = LocalEmployeeCreationTargetV1(requestId, recipient)
        val intruder = DomainId("99999999-9999-4999-8999-999999999999")
        val ctx = roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.context(credentials(), target))
        assertFails { P.decodeContext(target, intruder, ctx) }
        assertFails { P.decodeContext(LocalEmployeeCreationTargetV1(intruder, recipient), creator, ctx) }
        val inserted = roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.execute(credentials(), o, "s3cret-pass".toCharArray()))
        assertFails { P.decodeExecution(other, creator, inserted) }
        assertFails { P.decodeLookup(other, creator, roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.lookup(credentials(), o))) }
        service.captureResult = LocalEmployeeCreationDefinitionResultV1.Captured(other)
        assertFails { P.decodeCapture(draft(), creator,
            roundTrip(service, LocalMasterRpcEmployeeCreationRequestV1.capture(credentials(), draft()))) }
        // A receipt-shaped result is not an answer to a context/review question.
        assertFails { P.decodeReview(o, creator, LocalMasterRpcEmployeeCreationResponseV1(creator,
            LocalMasterRpcEmployeeCreationResultV1.Receipt(LocalMasterRpcEmployeeCreationResultV1.ReceiptKind.INSERTED,
                scope, o.requestId, o.operatorId, o.actorId, o.contentDigest))) }
    }

    @Test
    fun malformedRequestsAreRefusedWithoutEchoingInput() {
        val encoded = json.encodeToString(P.PROCEDURE.request,
            LocalMasterRpcEmployeeCreationRequestV1.context(credentials(), LocalEmployeeCreationTargetV1(requestId, recipient)))
        val shortBearer = encoded.replaceFirst("[1,", "[")
        assertFails { json.decodeFromString(P.PROCEDURE.request, shortBearer) }
        assertFails { json.decodeFromString(P.PROCEDURE.request, encoded.replace("\"context\"", "\"other\"")) }
        assertFails { json.decodeFromString(P.PROCEDURE.request, "{}") }
        val failure = runCatching { json.decodeFromString(P.PROCEDURE.request, encoded.replace(recipient.value, "not-an-id")) }
            .exceptionOrNull()!!
        assertTrue("not-an-id" !in failure.message.orEmpty())
    }
}
