package com.ukkera.pos.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.Copy
import com.ukkera.pos.app.ui.FoodCopy
import com.ukkera.pos.app.ui.LocalWorkflowScreen
import com.ukkera.pos.app.ui.foodCopyText
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Three frozen original-ID recovery variants through the actual runtime, entry store/controller
 * and full shared screen. Read-family variants are not included: their v2 schedules have an
 * unresolved nonmutating pending-delivery binding. No ignored/placeholder passing method exists.
 * Continuous consumer states and closed outputs are observed; rendered semantics are checkpoints
 * only. Full transient rendered-frame, master/native/persistence/RPC and whole-case proof remain
 * blocked/unverified even if these three supported method-level observations pass.
 */
class FulfillmentHandoverRecoveryFrozenV2Test {
    @get:Rule val compose = createComposeRule()
    private var renderedEntry by mutableStateOf<HandoverFrozenV2World.Entry?>(null)
    private val renderedCheckpoints = mutableListOf<HandoverRenderedCheckpoint>()

    @Test fun fulHpr010_accessDeniedUnknown_boundedConsumerAndCheckpoints() = recovery("RECOVER-ACCESS_DENIED_UNKNOWN")
    @Test fun fulHpr010_originalApplied_boundedConsumerAndCheckpoints() = recovery("RECOVER-ORIGINAL_APPLIED")
    @Test fun fulHpr010_originalRejected_boundedConsumerAndCheckpoints() = recovery("RECOVER-ORIGINAL_REJECTED")

    private fun recovery(id: String) = case("FUL-HPR-010-V2-S01/$id") { world ->
        val variant = FrozenHandoverV2Inputs.recoveries.single { it.id == id }
        // Independently clarified PRE-WINDOW setup, not a new frozen ordinal or authority grant.
        val (entry, setupRead) = enter(world, "A_INITIAL", "MY_DEFAULT", "AUTHORIZED_EMPTY_A")
        captured(setupRead, FrozenHandoverV2Inputs.queries.getValue("MY_DEFAULT"), "$id admissible setup request")
        release(setupRead, entry.session.integrity)
        assertSelectedSource(checkpoint(world, entry, "PRE-WINDOW-only").state, "MY_DEFAULT", "AUTHORIZED_EMPTY_A", "$id exact setup source")
        val setupOutputs = world.outputs.samples
        assertEquals(HandoverIntentCounts(0, 0, 0, 0), world.outputs.classifiedCounts(setupOutputs), "admissible setup has no hidden business/stop intents")
        assertTrue(setupOutputs.none { it.method in setOf("gateway.stage", "gateway.submit", "gateway.acknowledgeRecorded") }, "setup does not stage/submit/ACK original work")
        val pending = ui {
            world.beginWindow() // BEFORE dispatch/consumption of ordinal-1 delivery, including its first reaction
            val plan = entry.session.gateway.pending.expect(null, FrozenHandoverV2Inputs.pendingOriginal, HandoverReplyDelivery.HELD_IGNORING_CANCELLATION)
            entry.controller.refreshPending(world.runtime.navigator.token())
            plan
        }
        captured(pending, null, "$id ordinal-1 original pending request")
        release(pending, entry.session.integrity)
        val original = checkpoint(world, entry, "pending-original-inside-window")
        assertEquals(FrozenHandoverV2Inputs.pendingOriginal.operations, original.state.pending, "FUL-HPR-010-V2-S01-A01/$id exact original ID/action/unresolved projection")
        val l1 = ui {
            world.at(2)
            val plan = entry.session.gateway.lookups.expect(FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A,
                FrozenHandoverV2Inputs.operations.getValue("UNKNOWN"), HandoverReplyDelivery.HELD_IGNORING_CANCELLATION)
            entry.controller.lookup(FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A, world.runtime.navigator.token())
            plan
        }
        captured(l1, FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A, "$id exact L1 original lookup")
        release(l1, entry.session.integrity)
        ui { world.at(3) }
        val unknown = checkpoint(world, entry, "L1-unknown-recovery-required")
        assertEquals(FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A, unknown.state.operationId)
        assertEquals(FrozenHandoverV2Inputs.operations.getValue("UNKNOWN"), unknown.state.operation, "FUL-HPR-010-V2-S01-A01/$id")
        assertTrue(unknown.state.recoveryRequired)
        requireRenderedText(unknown, binding(Copy.OPERATION_UNKNOWN), "unknown result, not recorded or new action")
        requireRenderedText(unknown, FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A.value, "rendered original operation ID")
        val l2 = ui {
            world.at(4)
            val plan = entry.session.gateway.lookups.expect(FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A,
                FrozenHandoverV2Inputs.operations.getValue(variant.finalResponse), HandoverReplyDelivery.HELD_IGNORING_CANCELLATION)
            entry.controller.lookup(FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A, world.runtime.navigator.token())
            plan
        }
        captured(l2, FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A, "$id exact L2 original lookup")
        release(l2, entry.session.integrity)
        ui { world.at(5) }
        val final = checkpoint(world, entry, "L2-original-result-processed")
        assertEquals(FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A, final.state.operationId)
        assertEquals(FrozenHandoverV2Inputs.operations.getValue(variant.finalResponse), final.state.operation, "FUL-HPR-010-V2-S01-A02/$id exact final result/replayed/order/portion metadata")
        requireRenderedText(final, FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A.value, "rendered original result identity")
        if (variant.finalResponse == "ACCESS_DENIED_UNKNOWN") {
            assertTrue(final.state.recoveryRequired)
            requireRenderedText(final, binding(Copy.OPERATION_UNKNOWN), "denial stays unknown")
            requireRenderedText(final, binding(Copy.LOCAL_DENIED), "current access failure surfaced")
        } else {
            val result = FrozenHandoverV2Inputs.operations.getValue(variant.finalResponse) as LocalOperationResult.Recorded
            requireRenderedText(final, binding(when (result.decision) {
                LocalRecordedDecision.APPLIED -> Copy.RECORDED_APPLIED
                LocalRecordedDecision.REJECTED -> Copy.RECORDED_REJECTED
                else -> throw HandoverWiringUnavailable("unassigned recorded decision binding")
            }), "original recorded decision classification")
            val replayBinding = binding(Copy.RECORDED_REPLAY)
            if (result.replayed) requireRenderedText(final, replayBinding, "original replay classification")
            else assertFalse(final.nodes.any { it.contains(replayBinding) }, "original rejection is not presented as replay at checkpoint")
            requireRenderedText(final, result.message.english, "original recorded result message")
            requireRenderedText(final, assertNotNull(result.food?.orderId).value, "original recovered order metadata")
            requireRenderedText(final, assertNotNull(result.food).portions.single().portionId.value, "original recovered portion metadata")
        }
        assertTrue(world.outputs.samples.filter { it.inFrozenWindow && it.method == "gateway.lookup" }.all {
            it.operationId == FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A
        }, "FUL-HPR-010-V2-S01-A01/$id every actual lookup uses the original ID; no fixed lookup count")
        assertTrue(world.workspaceSamples.filter { it.inFrozenWindow }.all {
            it.operationId == null || it.operationId == FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A
        }, "FUL-HPR-010-V2-S01-A01/$id no new independent operation presentation")
        zeroIntents(world, "FUL-HPR-010-V2-S01-A03/$id")
    }

    private fun case(id: String, body: (HandoverFrozenV2World) -> Unit) {
        val world = ui { HandoverFrozenV2World() }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1100.dp, 2400.dp)) {
                    renderedEntry?.let { entry -> LocalWorkflowScreen(world.runtime, entry.route, entry.model, world.runtime.navigator.token()) }
                }
            }
        }
        var failure: Throwable? = null
        try { body(world) } catch (problem: Throwable) { failure = problem }
        try {
            ui { if (world.windowOpen) world.endWindow() }
            runBlocking { world.closeAndValidate {
                ui { world.runtime.close(); renderedEntry = null }
                compose.waitForIdle()
            } }
        } catch (problem: Throwable) { failure?.addSuppressed(problem) ?: run { failure = problem } }
        failure?.let { throw it }
        // A method-level XML success is deliberately scoped by the external assertion map.
        check(renderedCheckpoints.isNotEmpty()) { "BLOCKED_WIRING: $id lacks actual rendered checkpoints" }
    }

    private fun enter(world: HandoverFrozenV2World, context: String, query: String, response: String):
        Pair<HandoverFrozenV2World.Entry, HandoverReplyPlan<LocalWorkflowQuery, LocalReadResult>> = ui {
        val session = world.session(context)
        runBlocking { world.signIn(session) }
        val entry = world.acquireEntry(session)
        val plan = armRead(session, query, response)
        entry.controller.activate(LocalWorkflow.FULFILLMENT, food = FrozenHandoverV2Inputs.queries.getValue(query).food)
        renderedEntry = entry
        entry to plan
    }

    private fun armRead(session: HandoverFrozenV2World.Session, query: String, response: String): HandoverReplyPlan<LocalWorkflowQuery, LocalReadResult> {
        session.gateway.pending.expect(null, LocalPendingResult.Available(emptyList(), null), HandoverReplyDelivery.IMMEDIATE)
        return session.gateway.reads.expect(FrozenHandoverV2Inputs.queries.getValue(query), FrozenHandoverV2Inputs.reads.getValue(response),
            HandoverReplyDelivery.HELD_IGNORING_CANCELLATION)
    }

    private fun <I, O> captured(plan: HandoverReplyPlan<I, O>, input: I, label: String) {
        compose.waitForIdle()
        if (!plan.captured.isCompleted) throw HandoverWiringUnavailable("$label did not reach its actual consumer boundary")
        assertEquals(input, runBlocking { plan.captured.await() }, label)
    }

    private fun <I, O> release(plan: HandoverReplyPlan<I, O>, ledger: HandoverHarnessIntegrity, joinCancelledConsumer: Boolean = false) {
        val consumer = ui { ledger.safeHeldConsumer(plan.planId) }
            ?: throw HandoverWiringUnavailable("scheduled plan ${plan.planId} has no safely captured actual consumer")
        ui { plan.release() }
        if (joinCancelledConsumer) {
            if (!consumer.isCancelled) throw HandoverWiringUnavailable("obsolete RA consumer was not actually retired")
            runBlocking { consumer.join() }
            ui { ledger.markJoined(consumer) }
        }
        compose.waitForIdle()
        if (plan.planId in ui { ledger.observation().activePlanIds })
            throw HandoverWiringUnavailable("scheduled plan ${plan.planId} delivery did not finish before checkpoint")
        if (consumer.isCompleted) ui { ledger.markJoined(consumer) }
    }

    private fun checkpoint(world: HandoverFrozenV2World, entry: HandoverFrozenV2World.Entry, label: String): HandoverRenderedCheckpoint {
        compose.waitForIdle()
        val state = compose.runOnIdle { world.checkpoint(entry, label) }
        // Fetch through the real Compose test API outside the UI callback (which must not wait
        // for itself). Copy the returned tree on the UI loop; no reflection/test-created food UI.
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val nodes = ui { semanticSamples(root) }
        return HandoverRenderedCheckpoint(label, state, nodes).also(renderedCheckpoints::add)
    }

    private fun semanticSamples(root: SemanticsNode): List<HandoverRenderedNode> = buildList {
        fun visit(node: SemanticsNode) {
            val config = node.config
            add(HandoverRenderedNode((config.getOrNull(SemanticsProperties.Text) ?: emptyList()).map { it.text },
                config.getOrNull(SemanticsProperties.EditableText)?.text,
                config.getOrNull(SemanticsProperties.ContentDescription)?.toList() ?: emptyList(),
                config.getOrNull(SemanticsProperties.StateDescription), SemanticsActions.OnClick in config,
                SemanticsProperties.Disabled in config))
            node.children.forEach(::visit)
        }
        visit(root)
    }

    private fun assertSelectedSource(state: HandoverWorkspaceSample, query: String, response: String, message: String) {
        assertSelectedQuery(state, query, message)
        val expectedPage = available(response).page
        assertEquals(expectedPage, state.page, message) // all frozen body/metadata/nulls, not only fixture inspection
        assertNull(state.readProblem, message)
        assertNull(state.foodUnavailable, message)
    }

    private fun assertSelectedQuery(state: HandoverWorkspaceSample, query: String, message: String) {
        val expectedQuery = FrozenHandoverV2Inputs.queries.getValue(query)
        assertEquals(expectedQuery.workflow, state.workflow, message)
        assertEquals(expectedQuery.food, state.foodQuery, message)
        assertEquals(expectedQuery.search, state.search, message)
        assertEquals(expectedQuery.reference, state.reference, message)
    }

    private fun zeroIntents(world: HandoverFrozenV2World, message: String) {
        assertEquals(HandoverIntentCounts(0, 0, 0, 0), world.outputs.countsInWindow(), message)
    }

    private fun requireRenderedText(checkpoint: HandoverRenderedCheckpoint, text: String, meaning: String) {
        if (checkpoint.nodes.none { it.contains(text) }) throw HandoverWiringUnavailable("${checkpoint.label}: missing rendered $meaning binding")
    }

    private fun available(name: String) = FrozenHandoverV2Inputs.reads.getValue(name) as LocalReadResult.Available
    private fun binding(key: Copy): String = runBlocking { getString(key.en) } // existing localized presentation bindings, not a new business oracle
    private fun <T> ui(block: () -> T): T = compose.runOnUiThread(block)
}

private data class HandoverRenderedNode(val text: List<String>, val editableText: String?, val contentDescriptions: List<String>,
    val stateDescription: String?, val hasClick: Boolean, val disabled: Boolean) {
    fun contains(value: String): Boolean = text.any { it.contains(value) } || editableText?.contains(value) == true ||
        contentDescriptions.any { it.contains(value) } || stateDescription?.contains(value) == true
}

private data class HandoverRenderedCheckpoint(val label: String, val state: HandoverWorkspaceSample, val nodes: List<HandoverRenderedNode>)
