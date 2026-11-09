package com.ukkera.pos.app.gateway.independent.routerestorationv1

import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledPolicyInput
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.SessionGatewayFixture
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LRR-002-P Current-cut client slice from local-route-restoration-v1, frozen version 1.0.
 * Real EndpointSession.mayRestore and JVM encrypted journals are used with fixed frozen inputs.
 * The route check awaits its credential worker while the controlled Current reply changes
 * from P40 to P41. These replies do not certify provisioning, device admission or activation.
 * No successful protected read type, financial result or accepting master is synthesized.
 *
 * Master/presentation ordering, real protected-read barriers/late or duplicate delivery,
 * private payload/draft/frame absence, fresh read markers, durable route save/reopen and all
 * five actual business-effect counters remain unbound. All whole cases and platform/RPC
 * variants remain NOT_RUN. An empty intent journal is only a client-journal observation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteRestorationGatewayFrozenSlicesTest {
    @Test
    fun lrr002P_currentWithdrawalRejectsPendingOrderWhileClientSessionRemainsCurrent() = runTest {
        val frozen = FrozenRouteRestorationGatewayInputs.load()
        val withdrawn = frozen.assertion("LRR-002", "LRR-002-P", 0)
        val validSession = frozen.assertion("LRR-002", "LRR-002-P", 1)
        val noEffects = frozen.assertion("LRR-002", "LRR-002-P", 4)
        val identity = LocalClientIdentity(BranchScope(
            frozen.identity("organization_a"), frozen.identity("company_a"), frozen.identity("branch_a"),
        ), frozen.identity("employee_a"), frozen.identity("device_a"))
        val fixture = SessionGatewayFixture.create(
            StandardTestDispatcher(testScheduler), initialPolicySample = frozen.policySample("P40"),
            identitySample = identity, initialSessionSample = frozen.identity("session_a"),
        )
        try {
            val session = fixture.signIn()
            val initialCurrent = assertIs<LocalSessionCheckResult.Current>(session.current())
            check(AppArea.ORDERS in initialCurrent.display.allowedAreas && AppArea.STOCK in initialCurrent.display.allowedAreas) {
                "The frozen P40 sample must initially include order and quantity presentation hints."
            }
            val originalJournal = fixture.observeJournals()
            val signInsBefore = fixture.client.signInCalls.get()
            val refreshesBefore = fixture.client.refreshCalls.get()
            val currentCallsBefore = fixture.client.currentCalls.get()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                session.mayRestore(AppRoute.Order(frozen.identity("order_a")))
            }
            check(!pending.isCompleted && fixture.client.currentCalls.get() == currentCallsBefore) {
                "The unit schedule requires the begun order check to await its queued Current sample."
            }
            fixture.client.replaceCurrentPolicyReplySample(frozen.policySample("P41"))
            assertFalse(pending.await(), withdrawn.message("client Boolean slice: pending old order route cannot restore after known withdrawal"))

            val current = assertIs<LocalSessionCheckResult.Current>(session.current(),
                validSession.message("client Current sample remains available after permission withdrawal"))
            assertEquals(initialCurrent.display.actorId, current.display.actorId,
                validSession.message("client employee sample remains employee_a"))
            assertEquals(initialCurrent.display.companyId, current.display.companyId,
                validSession.message("client company sample remains company_a"))
            assertEquals(initialCurrent.display.branchId, current.display.branchId,
                validSession.message("client branch sample remains branch_a"))
            assertFalse(AppArea.ORDERS in current.display.allowedAreas,
                withdrawn.message("withdrawn order navigation hint is absent from the controlled Current projection"))
            assertTrue(AppArea.STOCK in current.display.allowedAreas,
                validSession.message("the current permitted quantity navigation hint remains"))
            assertTrue(session.isCurrent(), validSession.message("the actual client capability is not retired as a session failure"))
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value,
                validSession.message("known permission withdrawal causes no terminal client lifecycle"))
            assertEquals(signInsBefore, fixture.client.signInCalls.get(), validSession.message("restoration starts no extra sign-in"))
            assertEquals(refreshesBefore, fixture.client.refreshCalls.get(), validSession.message("the checkpoint starts no session renewal"))
            assertEquals(frozen.identity("session_a"), fixture.client.currentSessionSample,
                validSession.message("the controlled current session locator remains session_a"))

            // Initiating-call and journal observations do not establish master business effects.
            assertTrue(fixture.client.protectedCalls.none { it.kind != "READ" },
                noEffects.message("bounded client slice: no non-read protected call is initiated"))
            val after = fixture.observeJournals()
            assertEquals(originalJournal.sessions, after.sessions,
                validSession.message("secret-free production journal identity/phase observations are preserved"))
            assertEquals(originalJournal.pendingOperationIds, after.pendingOperationIds,
                noEffects.message("bounded journal slice: restoration adds no operation intent"))
            fixture.retireAndReopenJournals()
            val reopened = fixture.observeJournals()
            assertEquals(after.sessions, reopened.sessions,
                validSession.message("reopened encrypted journal preserves retained facts; those facts do not grant a session"))
            assertEquals(after.pendingOperationIds, reopened.pendingOperationIds,
                noEffects.message("independent encrypted reader observes no added operation intent"))
        } finally { fixture.close() }
    }
}

/** Reads exact frozen bytes and selects their inputs/outcomes; no replacement oracle is supplied. */
private class FrozenRouteRestorationGatewayInputs private constructor(
    private val cases: JsonObject,
    private val fixtures: JsonObject,
) {
    fun identity(alias: String): DomainId = DomainId(fixtures.getValue("identities").jsonObject.getValue(alias).jsonPrimitive.content)

    // The aliases map only to controlled Current reply data; no authorization engine is added.
    fun policySample(alias: String): ControlledPolicyInput {
        val input = fixtures.getValue("activated_policy_states").jsonObject.getValue(alias).jsonObject
        val permissions = input.getValue("employee_a_grants").jsonArray.map {
            Permission(when (it.jsonPrimitive.content) {
                "quantity read" -> SecurityAction.INVENTORY_QUANTITY_READ
                "own order read" -> SecurityAction.ORDER_READ
                "own order edit" -> SecurityAction.ORDER_EDIT
                "cost read" -> SecurityAction.INVENTORY_COST_READ
                else -> error("Unbound frozen grant alias.")
            }, PermissionVerb.PERFORM)
        }
        return ControlledPolicyInput(Revision(input.getValue("revision").jsonPrimitive.content.toLong()), permissions)
    }

    fun assertion(caseId: String, variantId: String, zeroBasedIndex: Int): FrozenGatewayRouteAssertion {
        val selected = cases.getValue("cases").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == caseId
        }.jsonObject
        check(selected.getValue("completion_variants").jsonArray.any {
            it.jsonObject.getValue("id").jsonPrimitive.content == variantId
        }) { "Unbound frozen completion variant." }
        return FrozenGatewayRouteAssertion("$variantId exact_assertions[$zeroBasedIndex]",
            selected.getValue("exact_assertions").jsonArray[zeroBasedIndex].jsonPrimitive.content)
    }

    companion object {
        private const val DIRECTORY = "qa/pos/designs/local-route-restoration-v1"
        private val hashes = mapOf(
            "README.md" to "5f44c2729ecd0e92c841d54917607ea1a56a9a733171e30e907d181af2926dd1",
            "cases.json" to "477231865abb8312ca259099ed1bd2298a7171ea2567f3edf52205822672ae4e",
            "fixtures.json" to "8b37e783ec4426248fe7b08dbea0d7b1f7894a6399a5ad14382617d3a2bf34c2",
        )

        fun load(): FrozenRouteRestorationGatewayInputs {
            val directory = repositoryRoot().resolve(DIRECTORY)
            val sealedFiles = hashes.mapValues { (name, expected) ->
                Files.readAllBytes(directory.resolve(name)).also { bytes ->
                    val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                    check(actual == expected) { "Frozen route-restoration file hash mismatch: $name" }
                }
            }
            val cases = Json.parseToJsonElement(sealedFiles.getValue("cases.json").decodeToString()).jsonObject
            val fixtures = Json.parseToJsonElement(sealedFiles.getValue("fixtures.json").decodeToString()).jsonObject
            check(cases.getValue("design_id").jsonPrimitive.content == "local-route-restoration-v1")
            check(cases.getValue("frozen_version").jsonPrimitive.content == "1.0")
            check(fixtures.getValue("frozen_version").jsonPrimitive.content == "1.0")
            return FrozenRouteRestorationGatewayInputs(cases, fixtures)
        }

        private fun repositoryRoot(): Path =
            generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()) { it.parent }
                .firstOrNull { Files.isDirectory(it.resolve(DIRECTORY)) }
                ?: error("Frozen route-restoration supplement is unavailable.")
    }
}

private data class FrozenGatewayRouteAssertion(val id: String, val expected: String) {
    fun message(slice: String): String = "$id: $slice. Frozen expectation: $expected"
}
