@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.foodtableassignment

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** FTA-v1 supporting shared-code checks, not native original/history or full-case runs. */
class FoodTableAssignmentFrozenSalesSlicesTest {
    private val f = FtaSalesInputs

    @Test
    fun fta008020NormalizePreservesExplicitFirstVersusReplacementAndAllSelectedCoordinates() {
        val handler = ftaAssignmentHandler()
        for (request in listOf(f.first, f.move, f.move.copy(expectedAssignmentRevision = null),
            f.move.copy(expectedOrderRevision = Revision(11)), f.move.copy(expectedAssignmentRevision = Revision(2)))) {
            val normalized = handler.normalize(LocalFoodTableAssignmentCodecV1.encode(request))
            assertEquals(request, LocalFoodTableAssignmentCodecV1.decode(normalized))
        }
        val read = LocalFoodTableAssignmentQueryV1(f.order)
        val normalized = ftaAssignmentReadHandler().normalize(LocalFoodTableAssignmentReadCodecV1.encode(read))
        assertEquals(LocalFoodTableAssignmentReadCodecV1.encode(read), normalized)
        // Equality here checks retention of supplied input, not independently frozen canonical bytes.
        assertTrue(handler.claimNamespaces.isEmpty())
        assertTrue(handler.writableKinds.none { it in LocalFoodProfileV1.kinds })
    }

    @Test
    fun fta008LegacyLabelPureSalesProposalDataCreatesOnlyFirstBindingAndPreservesLiteralOrderInput() {
        val request = f.first
        val cut = f.legacyCut()
        val beforeOrder = foodStoredEnvelopeBytes(cut.order, cut.orderSource.schema)
        val beforeIndex = foodStoredEnvelopeBytes(cut.ownerIndex, cut.ownerIndexSource.schema)
        val identity = FoodTableAssignmentIdentityV1(f.scope, f.actor, f.operation(8),
            semanticDigest(f.scope, f.actor, LocalFoodTableAssignmentProfileV1.COMMAND,
                LocalFoodTableAssignmentCodecV1.encode(request)))
        val production = foodTableAssignmentProduction(identity, request, cut, null, f.directory)
        assertEquals(Permission(SecurityAction.ORDER_EDIT, PermissionVerb.PERFORM), production.requirement.permission)
        assertEquals(OwnerConstraint.OwnerPrivate(f.actor), production.requirement.owner)
        val outcome = assertIs<ProposedOutcome.Apply>(production.propose(f.at,
            FoodTableAssignmentAuthorizationV1(f.actor, f.session, SessionGeneration(1), Revision(1))))
        val result = LocalFoodTableAssignmentCodecV1.decodeResult(outcome.result)
        assertEquals(f.order, result.orderId)
        assertEquals(Revision(12), result.orderRevision)
        assertEquals(LocalFoodTableBindingV1(Revision(1), Revision(12), f.floorMain, Revision(3), f.tableA, Revision(7)),
            result.assignment)
        assertNull(outcome.changes.single { it.key.kind == LocalFoodTableAssignmentProfileV1.BINDING }.expectedRevision)
        assertTrue(outcome.changes.all { it.key.kind in setOf(LocalFoodTableAssignmentProfileV1.BINDING,
            LocalFoodTableAssignmentProfileV1.VERSION) })
        assertTrue(outcome.changes.none { it.key.kind in LocalFoodProfileV1.kinds })
        assertTrue(outcome.claims.isEmpty())
        assertTrue(outcome.jobs.isEmpty())
        assertEquals(beforeOrder, foodStoredEnvelopeBytes(cut.order, cut.orderSource.schema))
        assertEquals(beforeIndex, foodStoredEnvelopeBytes(cut.ownerIndex, cut.ownerIndexSource.schema))
        assertEquals(f.legacyReference, cut.value.service.reference)
        assertEquals(f.legacySeatNote, cut.value.service.seatNote)
        assertEquals(f.creator, cut.value.creatorId)
        assertEquals(f.actor, cut.value.ownerId)
        // This is F-LEGACY-LABEL / pure sales proposal DATA. There is no database commit,
        // genuine protected absence, accepting native original, stock/cash cut or move seed.
    }

    @Test
    fun fta009MissingOrderActualHandlerRefusesWithoutAnAssociationProposal(): Unit = runTest {
        val directory = FtaSalesDirectory()
        val handler = ftaAssignmentHandler(directory)
        val request = f.first.copy(orderId = f.missingOrder)
        val view = FtaSalesView()
        val normalized = handler.normalize(LocalFoodTableAssignmentCodecV1.encode(request))
        val prepared = handler.prepare(normalized, view, f.context(request, 9))
        assertEquals(Permission(SecurityAction.ORDER_EDIT, PermissionVerb.PERFORM), prepared.requirement.permission)
        assertEquals(OwnerConstraint.OwnerPrivate(f.actor), prepared.requirement.owner)
        assertEquals(f.missingOrder, prepared.requirement.binding?.documentId)
        assertEquals(Revision(12), prepared.requirement.binding?.revision)
        assertFalse(prepared.allowsActionApproval)
        val outcome = assertIs<ProposedOutcome.Reject>(prepared.evaluate(f.authorization(prepared.requirement), f.at))
        val result = LocalFoodTableAssignmentCodecV1.decodeResult(outcome.result)
        assertNull(result.orderId)
        assertNull(result.orderRevision)
        assertNull(result.assignment)
        assertEquals(1, directory.currentReads)
        assertTrue(view.censusReads > 0)
        assertEquals(0, view.retainedReads)
        // Reject has no business-change fields. The controlled absent cut is not native absence.
    }

    @Test
    fun fta009024MissingOrderActualReadRefusesRatherThanPublishingAnUnassignedObservation(): Unit = runTest {
        val directory = FtaSalesDirectory()
        val handler = ftaAssignmentReadHandler(directory)
        val query = LocalFoodTableAssignmentQueryV1(f.missingOrder)
        val view = FtaSalesView()
        val prepared = handler.prepare(handler.normalize(LocalFoodTableAssignmentReadCodecV1.encode(query)), view,
            f.readContext(f.missingOrder))
        val requirement = prepared.requirements.single()
        assertEquals(Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM), requirement.permission)
        assertEquals(OwnerConstraint.OwnerPrivate(f.actor), requirement.owner)
        val authorization = f.authorization(requirement)
        assertFailsWith<MasterReadInvalidSelection> { prepared.project(listOf(authorization), f.at) }
        assertEquals(0, directory.currentReads)
        assertEquals(0, view.retainedReads)
    }

    @Test
    fun fta014OtherOwnerActualReadRefusesEvenBroadSyntheticOwnerAccessWithoutAcquiringFullFoodHistory(): Unit = runTest {
        val directory = FtaSalesDirectory()
        val handler = ftaAssignmentReadHandler(directory)
        val view = FtaSalesView(f.legacyHeader())
        val query = LocalFoodTableAssignmentQueryV1(f.order)
        val prepared = handler.prepare(handler.normalize(LocalFoodTableAssignmentReadCodecV1.encode(query)), view,
            f.readContext(f.order, f.otherActor))
        val requirement = prepared.requirements.single()
        assertEquals(Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM), requirement.permission)
        assertEquals(OwnerConstraint.OwnerPrivate(f.actor), requirement.owner)
        val broad = f.authorization(requirement, f.otherActor, OwnerAccess.ANY_OWNER)
        assertFailsWith<MasterReadAuthorizationDenied> { prepared.project(listOf(broad), f.at) }
        assertEquals(0, directory.currentReads)
        assertEquals(0, view.retainedReads)
        // Only the representable F-LEGACY-LABEL header is supplied. No F-ASSIGNED binding,
        // real broad grant, native actor/session authority or source qualification is asserted.
    }
}
