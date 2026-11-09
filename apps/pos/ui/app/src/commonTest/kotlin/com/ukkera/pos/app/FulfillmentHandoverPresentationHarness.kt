package com.ukkera.pos.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Response delivery controls only. The frozen binding must supply every response value. */
internal enum class HandoverReplyDelivery { IMMEDIATE, HELD_IGNORING_CANCELLATION }

internal enum class HandoverIntegrityFailureKind {
    MISSING_REPLY_PLAN, REUSED_REPLY_PLAN, UNSUPPORTED_CALL, CALL_AFTER_TEARDOWN, ACTIVE_REPLY_AT_TEARDOWN,
    AMBIGUOUS_JOB_OWNERSHIP, ACTIVE_CONSUMER_AT_TEARDOWN,
}

internal data class HandoverIntegrityFailure(
    val ordinal: Int,
    val kind: HandoverIntegrityFailureKind,
    val method: String,
    val planId: Long?,
)

internal data class HandoverIntegrityObservation(
    val planAttempts: Map<Long, Int>,
    val activePlanIds: Set<Long>,
    val failures: List<HandoverIntegrityFailure>,
    val teardownChecked: Boolean,
    val retainedHeldConsumerCount: Int,
    val joinedHeldConsumerCount: Int,
)

/**
 * Append-only test bookkeeping, independent of consumer exceptions and response futures.
 * This belongs to one case's single serialized consumer event loop; it has no reset/clear operation.
 */
internal class HandoverHarnessIntegrity {
    private val retainedFailures = mutableListOf<HandoverIntegrityFailure>()
    private val planAttempts = mutableMapOf<Long, Int>()
    private val activePlans = mutableSetOf<Long>()
    private val releases = mutableListOf<() -> Unit>()
    private data class HeldConsumer(val job: Job, val method: String, val planId: Long, val ownedAtCapture: Boolean)
    private val heldConsumers = mutableListOf<HeldConsumer>()
    private val protectedJobs = mutableListOf<Job>()
    private val joinedJobs = mutableSetOf<Job>()
    private var controllerRoot: Job? = null
    private var nextPlanId = 0L
    private var teardownChecked = false

    fun allocatePlan(method: String, release: () -> Unit): Long {
        if (teardownChecked) fail(HandoverIntegrityFailureKind.CALL_AFTER_TEARDOWN, "plan.$method", null)
        nextPlanId++
        planAttempts[nextPlanId] = 0
        releases += release
        return nextPlanId
    }

    fun beginDelivery(method: String, planId: Long) {
        val attempt = (planAttempts[planId] ?: 0) + 1
        planAttempts[planId] = attempt
        if (attempt != 1) fail(HandoverIntegrityFailureKind.REUSED_REPLY_PLAN, method, planId)
        if (teardownChecked) fail(HandoverIntegrityFailureKind.CALL_AFTER_TEARDOWN, method, planId)
        activePlans += planId
    }

    fun endDelivery(planId: Long) { activePlans -= planId }

    /** Save callers before entering NonCancellable; never infer the runtime's private parent. */
    fun protectCaller(job: Job?) {
        if (job != null && protectedJobs.none { it === job }) protectedJobs += job
    }

    fun registerControllerRoot(job: Job) {
        if (controllerRoot != null && controllerRoot !== job) unsupportedCall("harness.reused-controller-owner")
        controllerRoot = job
    }

    /** Public child traversal only. An unreadable relationship is not permission to own a job. */
    private fun containsJob(root: Job, target: Job): Boolean? = try {
        val pending = ArrayDeque<Job>()
        val visited = mutableSetOf<Job>()
        pending.addLast(root)
        var found = false
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next === target) { found = true; break }
            if (visited.add(next)) next.children.forEach(pending::addLast)
        }
        found
    } catch (_: Throwable) { null }

    private fun excludesProtectedJobs(job: Job): Boolean = job !== NonCancellable &&
        protectedJobs.all { containsJob(job, it) == false }

    /** Capture the actual held consumer BEFORE withContext(NonCancellable) substitutes its Job. */
    fun retainHeldConsumer(method: String, planId: Long, job: Job?) {
        if (job == null) {
            fail(HandoverIntegrityFailureKind.AMBIGUOUS_JOB_OWNERSHIP, method, planId)
        }
        val inControllerTree = controllerRoot?.let { containsJob(it, job) } == true
        // These two controlled ports are consumed by the actual runtime's detached cleanup work.
        // Inline/test callers and their ancestors are still excluded explicitly, at both boundaries.
        val runtimeHeldPort = method == "session.refresh" || method == "session.logout"
        val owned = excludesProtectedJobs(job) && (inControllerTree || runtimeHeldPort)
        // An unowned inline/ambiguous caller must never enter either held wait. The failure
        // remains retained even when production translates the throw to an unavailable result.
        if (!owned) fail(HandoverIntegrityFailureKind.AMBIGUOUS_JOB_OWNERSHIP, method, planId)
        heldConsumers += HeldConsumer(job, method, planId, owned)
    }

    /** Include the controller root (and thus its observers), plus retained runtime consumers. */
    fun safeOwnedJobs(): List<Job> {
        val candidates = buildList {
            controllerRoot?.let { add(it) }
            heldConsumers.filter { it.ownedAtCapture }.forEach { add(it.job) }
        }.distinct()
        return candidates.filter { candidate ->
            val safe = excludesProtectedJobs(candidate)
            if (!safe) record(HandoverIntegrityFailureKind.AMBIGUOUS_JOB_OWNERSHIP, "harness.teardown-owner", null)
            safe
        }
    }

    fun markJoined(job: Job) { joinedJobs += job }

    /** Exact scheduled-completion coordination; never exposes a caller, ancestor or open root. */
    fun safeHeldConsumer(planId: Long): Job? {
        val candidates = heldConsumers.filter { it.planId == planId }
        if (candidates.size != 1) {
            record(HandoverIntegrityFailureKind.AMBIGUOUS_JOB_OWNERSHIP, "harness.scheduled-completion-owner", planId)
            return null
        }
        val captured = candidates.single()
        if (!captured.ownedAtCapture || captured.job === controllerRoot || !excludesProtectedJobs(captured.job)) {
            record(HandoverIntegrityFailureKind.AMBIGUOUS_JOB_OWNERSHIP, "harness.scheduled-completion-owner", planId)
            return null
        }
        return captured.job
    }

    fun missingPlan(method: String): Nothing = fail(HandoverIntegrityFailureKind.MISSING_REPLY_PLAN, method, null)
    fun unsupportedCall(method: String): Nothing = fail(HandoverIntegrityFailureKind.UNSUPPORTED_CALL, method, null)

    private fun record(kind: HandoverIntegrityFailureKind, method: String, planId: Long?) {
        retainedFailures += HandoverIntegrityFailure(retainedFailures.size + 1, kind, method, planId)
    }

    private fun fail(kind: HandoverIntegrityFailureKind, method: String, planId: Long?): Nothing {
        record(kind, method, planId)
        throw AssertionError("Handover harness integrity violation: $kind at $method (plan $planId).")
    }

    /** Release before joining work, including replies suspended in NonCancellable. */
    fun releaseAllHeldReplies() { releases.forEach { it() } }

    fun observation(): HandoverIntegrityObservation = HandoverIntegrityObservation(
        planAttempts.toMap(), activePlans.toSet(), retainedFailures.toList(), teardownChecked,
        heldConsumers.size, heldConsumers.count { it.job in joinedJobs })

    /** Must run from test teardown, outside every production consumer's exception boundary. */
    fun validateAfterTeardown() {
        teardownChecked = true
        activePlans.forEach { record(HandoverIntegrityFailureKind.ACTIVE_REPLY_AT_TEARDOWN, "reply.delivery", it) }
        heldConsumers.filter { !it.job.isCompleted || it.job !in joinedJobs }.forEach {
            record(HandoverIntegrityFailureKind.ACTIVE_CONSUMER_AT_TEARDOWN, it.method, it.planId)
        }
        controllerRoot?.takeIf { !it.isCompleted || it !in joinedJobs }?.let {
            record(HandoverIntegrityFailureKind.ACTIVE_CONSUMER_AT_TEARDOWN, "harness.controller-and-observer", null)
        }
        if (retainedFailures.isNotEmpty()) throw AssertionError(retainedFailures.joinToString(
            prefix = "Retained handover harness violations: ", separator = "; ") {
            "${it.ordinal}:${it.kind}:${it.method}:plan=${it.planId}"
        })
    }
}

internal class HandoverReplyPlan<I, O>(
    private val integrity: HandoverHarnessIntegrity,
    private val method: String,
    private val reply: O,
    private val delivery: HandoverReplyDelivery,
) {
    private val capturedInput = CompletableDeferred<I>()
    val captured: Deferred<I> get() = capturedInput
    private val released = CompletableDeferred<Unit>()
    val planId: Long = integrity.allocatePlan(method) { released.complete(Unit) }

    fun release() { released.complete(Unit) }

    suspend fun deliver(input: I): O {
        integrity.beginDelivery(method, planId)
        try {
            if (delivery != HandoverReplyDelivery.IMMEDIATE)
                integrity.retainHeldConsumer(method, planId, currentCoroutineContext()[Job])
            capturedInput.complete(input)
            when (delivery) {
                HandoverReplyDelivery.IMMEDIATE -> Unit
                HandoverReplyDelivery.HELD_IGNORING_CANCELLATION -> withContext(NonCancellable) { released.await() }
            }
            return reply
        } finally { integrity.endDelivery(planId) }
    }
}

/** Missing/reused plans remain in the shared ledger even if a consumer swallows Throwable. */
internal class HandoverReplyQueue<I, O>(private val method: String, private val integrity: HandoverHarnessIntegrity) {
    private val plans = ArrayDeque<HandoverReplyPlan<I, O>>()
    private val inputs = mutableListOf<I>()
    val calls: List<I> get() = inputs.toList()

    fun plan(reply: O, delivery: HandoverReplyDelivery): HandoverReplyPlan<I, O> =
        HandoverReplyPlan<I, O>(integrity, method, reply, delivery).also(plans::addLast)

    suspend fun next(input: I): O {
        inputs += input
        val plan = plans.removeFirstOrNull() ?: integrity.missingPlan(method)
        return plan.deliver(input)
    }
}
