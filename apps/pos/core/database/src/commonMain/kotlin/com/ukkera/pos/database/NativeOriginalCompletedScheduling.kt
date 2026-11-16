package com.ukkera.pos.database

import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.flow.MutableStateFlow

/** Bounded scheduling DATA for one endpoint lifetime. Only its actual original Work's
 * known return records completion, after native recheck and confirmed lease release.
 * This supplies no operation result, source proof, grant or financial-hold release.
 * A new endpoint starts empty and therefore revalidates the original catalogue. */
internal class NativeOriginalCompletedScheduling(private val maximum: Int = 1_024) {
    init { require(maximum in 1..1_024) }
    private val completed = MutableStateFlow<Set<DomainId>>(emptySet())

    fun contains(locator: NativeOriginalIntakeLocator): Boolean = locator.clientAllocationId in completed.value

    fun record(locator: NativeOriginalIntakeLocator, result: NativeOriginalWorkerResult) {
        if (result != NativeOriginalWorkerResult.SETTLED) return
        while (true) {
            val current = completed.value
            if (locator.clientAllocationId in current) return
            val retained = if (current.size < maximum) current else current.drop(1).toSet()
            if (completed.compareAndSet(current, retained + locator.clientAllocationId)) return
        }
    }
}
