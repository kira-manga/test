package com.ukkera.pos.sales.store

import com.ukkera.pos.sales.CashAssignmentOpened
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CashSessions
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.InventoryCommitDependency
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesEffect
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CashNonSaleTranslationTest {
    @Test fun noVersionUniqueEffectOrCommandPartCanBeSilentlyDropped() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        val actual = fixture.transition(before, request)
        val movement = actual.effects.filterIsInstance<CashMovement>().single()
        fun checked(
            versions: List<SalesVersionCheck> = actual.versions,
            unique: List<SalesUniqueCheck> = actual.unique,
            effects: List<SalesEffect> = actual.effects,
            command: SalesCommand = actual.command,
            after: CashSession = actual.value,
        ): CashMovement? = validateNonSaleTransition(actual.command, before, request, command, after,
            versions, unique, effects, actual.inventoryDependencies)
        assertSame(movement, checked())
        assertSame(movement, checked(effects = actual.effects.reversed()))
        assertNull(checked(versions = emptyList()))
        assertNull(checked(versions = actual.versions + actual.versions))
        assertNull(checked(versions = listOf(actual.versions.single().copy(expected = Revision(1)))))
        assertNull(checked(versions = listOf(actual.versions.single().copy(aggregate = SalesAggregate.ORDER))))
        assertNull(checked(versions = listOf(actual.versions.single().copy(id = fixture.nextId()))))
        assertNull(checked(unique = emptyList()))
        assertNull(checked(unique = actual.unique + actual.unique))
        assertNull(checked(unique = listOf(actual.unique.single().copy(kind = SalesUniqueKind.SAFE_TRANSFER))))
        assertNull(checked(unique = listOf(actual.unique.single().copy(sourceId = fixture.nextId()))))
        assertNull(checked(effects = listOf(movement)))
        assertNull(checked(effects = actual.effects + movement))
        assertNull(checked(effects = listOf(movement, movement)))
        assertNull(checked(effects = listOf(CashSessionChanged(actual.value), CashSessionChanged(actual.value))))
        assertNull(checked(effects = listOf(CashSessionChanged(before), movement)))
        assertNull(checked(effects = listOf(CashSessionChanged(actual.value),
            CashAssignmentOpened(before.drawerId, before.custodianId, before.id))))
        assertNull(checked(command = actual.command.copy(operationId = fixture.nextOperation())))
        assertNull(checked(command = actual.command.copy(recordedAt = fixture.occurred.value)))
        assertNull(checked(after = before))
    }

    @Test fun aRealInventoryDependencyCannotBeIgnoredByThisNonSaleOnlyProfile() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        val actual = fixture.transition(before, request)
        val dependency: InventoryCommitDependency = fixture.pending(before).inventoryDependency
        assertNull(validateNonSaleTransition(actual.command, before, request, actual.command, actual.value,
            actual.versions, actual.unique, actual.effects, listOf(dependency)))
    }

    @Test fun allMovementFactsAreCheckedAgainstTheCanonicalCommandNotOnlyTheirPresence() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        val expected = fixture.transition(before, request)
        assertNotNull(validateNonSaleTransition(expected.command, before, request, expected.command, expected.value,
            expected.versions, expected.unique, expected.effects, expected.inventoryDependencies))
        // Deliberate adapter-assembly faults: real domain proposals using the same
        // amount-bound capability but different source/category/evidence inputs.
        // A proof is not magic validation of arguments; the adapter must compare them.
        val wrongInputs = listOf(
            request.copy(sourceId = fixture.nextId()),
            request.copy(kind = NonSaleCashKind.OPERATING_EXPENSE),
            request.copy(evidenceId = fixture.nextId()),
            request.copy(occurredAt = fixture.now),
            request.copy(reason = "Different actual evidence"),
        )
        for (wrong in wrongInputs) {
            val alternative = CashSessions.recordNonSale(expected.command, before, before.revision, wrong.sourceId,
                wrong.kind.domain, wrong.amount, CashierEvidence(wrong.evidenceId, before.custodianId, wrong.occurredAt.value), wrong.reason)
            assertNull(validateNonSaleTransition(expected.command, before, request, alternative.command, alternative.value,
                alternative.versions, alternative.unique, alternative.effects, alternative.inventoryDependencies))
        }
    }
}
