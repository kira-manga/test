package com.ukkera.pos.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.TimeSource

/** Fixed source phases only. Opt-in diagnostics never carry an application identity or data. */
public enum class LocalMasterRpcAuthenticationTracePhase { AUTHENTICATE, AUTHORIZE, HANDLER, ATTACH_RESULT, CURRENT }

/** Entered-stage observations locate an unfinished wait as well as completed callbacks. */
public class LocalMasterRpcAuthenticationTrace(
    private val phase: LocalMasterRpcAuthenticationTracePhase,
    initialStage: LocalMasterRpcAuthenticationStage,
) : ReadWriteProperty<Any?, LocalMasterRpcAuthenticationStage> {
    private val start = if (try { localMasterRpcReadTraceEnabled() } catch (_: Throwable) { false })
        TimeSource.Monotonic.markNow() else null
    private val number = if (start != null) nextNumber() else 0L
    private var stage = initialStage
    private var finished = false
    init { record(false) }

    override public fun getValue(thisRef: Any?, property: KProperty<*>): LocalMasterRpcAuthenticationStage = stage
    override public fun setValue(thisRef: Any?, property: KProperty<*>, value: LocalMasterRpcAuthenticationStage) {
        stage = value
        record(false)
    }
    public fun finish() {
        if (finished) return
        record(true)
        finished = true
    }
    private fun record(final: Boolean) {
        if (start == null || finished) return
        try { reportLocalMasterRpcAuthenticationTiming(number, phase, stage, start.elapsedNow().inWholeMicroseconds, final) }
        catch (_: Throwable) { /* Observations cannot change actual authority, lifetime or outcome. */ }
    }
    private companion object {
        val numbers = MutableStateFlow(0L)
        fun nextNumber(): Long {
            repeat(8) {
                val before = numbers.value
                val next = if (before == Long.MAX_VALUE) 1L else before + 1
                if (numbers.compareAndSet(before, next)) return next
            }
            return 0L
        }
    }
}

internal expect fun reportLocalMasterRpcAuthenticationTiming(number: Long, phase: LocalMasterRpcAuthenticationTracePhase,
    stage: LocalMasterRpcAuthenticationStage, totalMicros: Long, final: Boolean)
