package com.ukkera.pos.rpc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/** Owns actual decoded bearer requests, including SDK denial before a callback receives them. */
internal class LocalRpcBusinessRequestCustody(
    private val isRetired: () -> Boolean,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Request(val isCleanupComplete: () -> Boolean, val destroyIfUnclaimed: () -> Unit,
        val awaitCleanup: suspend () -> Unit, val created: TimeMark)
    private class Snapshot(val closed: Boolean = false, val requests: List<Request> = emptyList())
    private val atom = MutableStateFlow(Snapshot())

    fun <Q> serializer(delegate: KSerializer<Q>, isCleanupComplete: (Q) -> Boolean,
        destroyIfUnclaimed: (Q) -> Unit, destroy: (Q) -> Unit,
        awaitCleanup: suspend (Q) -> Unit): KSerializer<Q> = object : KSerializer<Q> {
        override val descriptor: SerialDescriptor get() = delegate.descriptor
        override fun serialize(encoder: Encoder, value: Q) { delegate.serialize(encoder, value) }
        override fun deserialize(decoder: Decoder): Q {
            val value = delegate.deserialize(decoder)
            val request = Request({ isCleanupComplete(value) }, { destroyIfUnclaimed(value) },
                { awaitCleanup(value) }, timeSource.markNow())
            repeat(32) {
                val before = atom.value
                if (before.closed || isRetired()) { destroy(value); throw unavailable() }
                val owned = before.requests.filterNot { it.isCleanupComplete() }
                if (owned.size >= 256) { destroy(value); throw unavailable() }
                if (atom.compareAndSet(before, Snapshot(false, owned + request))) {
                    if (atom.value.closed || isRetired()) { destroy(value); throw unavailable() }
                    return value
                }
            }
            destroy(value)
            throw unavailable()
        }
    }

    fun deny() {
        closeRetention()
    }

    private fun closeRetention(): Snapshot? {
        repeat(32) {
            val before = atom.value
            if (before.closed) return before
            val after = Snapshot(true, before.requests)
            if (atom.compareAndSet(before, after)) return after
        }
        // The parent ingress denies first. Failed bounded CAS cannot permit new input.
        return null
    }

    fun reap() {
        repeat(32) {
            val before = atom.value
            before.requests.forEach {
                if (!it.isCleanupComplete() && it.created.elapsedNow() >= 30.seconds) it.destroyIfUnclaimed()
            }
            val retained = before.requests.filterNot { it.isCleanupComplete() }
            if (retained.size == before.requests.size ||
                atom.compareAndSet(before, Snapshot(before.closed, retained))) return
        }
    }

    /** Parent denial and accepted callback drain precede exact retained cleanup. */
    suspend fun destroyAfterDrain() {
        val before = closeRetention()
            ?: throw IllegalStateException("Business request cleanup cut is unresolved")
        for (request in before.requests) {
            // Refused/pre-admission claim owners still perform their own cleanup. Never
            // force-clear a claimed or already-wiping input while its owner is active.
            request.destroyIfUnclaimed()
        }
        // Retire every unclaimed exact input before a held loan can delay the waits.
        for (request in before.requests) {
            request.awaitCleanup()
        }
        // No new retained input can cross the closed CAS cut; every exact input is clean.
        // Failure above leaves the incomplete census retained rather than reporting release.
        atom.value = Snapshot(closed = true)
    }

    private fun unavailable(): SerializationException = SerializationException("Business request custody is unavailable")
}
