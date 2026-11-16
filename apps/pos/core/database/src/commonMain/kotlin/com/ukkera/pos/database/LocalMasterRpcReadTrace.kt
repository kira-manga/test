package com.ukkera.pos.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Fixed diagnostic categories; no request, peer, employee or stored identity is recorded. */
public enum class LocalMasterRpcReadTracePhase {
    AUTHENTICATION_QUEUE, AUTHENTICATE, AUTHORIZE_INVOKE, AUTHORIZE_EXECUTE, AUTHORIZE_SUCCESS, AUTHORIZE_CONTROL, HANDLER,
}

/** Opt-in observations only. Neither timing nor trace numbers participate in any decision. */
public class LocalMasterRpcReadTrace(
    private val phase: LocalMasterRpcReadTracePhase,
    initialStage: LocalMasterRpcReadStage,
) : ReadWriteProperty<Any?, LocalMasterRpcReadStage> {
    private val enabled = try { localMasterRpcReadTraceEnabled() } catch (_: Throwable) { false }
    private val start: TimeMark? = if (enabled) TimeSource.Monotonic.markNow() else null
    private val number = if (enabled) nextNumber() else 0L
    private var stage = initialStage
    private var previousMicros = 0L
    private var finished = false

    override public fun getValue(thisRef: Any?, property: KProperty<*>): LocalMasterRpcReadStage = stage

    override public fun setValue(thisRef: Any?, property: KProperty<*>, value: LocalMasterRpcReadStage) {
        record(false)
        stage = value
    }

    public fun finish() {
        if (finished) return
        record(true)
        finished = true
    }

    private fun record(final: Boolean) {
        if (start == null || finished) return
        try {
            val total = start.elapsedNow().inWholeMicroseconds
            reportLocalMasterRpcReadTiming(number, phase, stage, total - previousMicros, total, final)
            previousMicros = total
        } catch (_: Throwable) { /* Observation cannot replace the actual outcome. */ }
    }

    private companion object {
        val numbers = MutableStateFlow(0L)
        fun nextNumber(): Long {
            repeat(8) {
                val before = numbers.value
                val next = if (before == Long.MAX_VALUE) 1L else before + 1
                if (numbers.compareAndSet(before, next)) return next
            }
            return 0L // Diagnostic correlation only; contention cannot delay actual work.
        }
    }
}

internal expect fun localMasterRpcReadTraceEnabled(): Boolean
internal expect fun reportLocalMasterRpcReadTiming(number: Long, phase: LocalMasterRpcReadTracePhase,
    stage: LocalMasterRpcReadStage, elapsedMicros: Long, totalMicros: Long, final: Boolean)
