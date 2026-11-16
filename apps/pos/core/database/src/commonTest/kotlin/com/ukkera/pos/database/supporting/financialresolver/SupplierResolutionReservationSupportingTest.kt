package com.ukkera.pos.database.supporting.financialresolver

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlin.test.*

/** SELF-AUTHORED, NOT independent. Blueprint5.3/frozen LAN-A07/D04/D06 supporting
 * negative shape controls only. This predicate grants no permission/native custody;
 * the separate genuine native supplier scenario must prove the actual positive path. */
class SupplierResolutionReservationSupportingTest {
    private fun id() = DomainId.random()
    private val scope = BranchScope(id(), id(), id())
    private val original = id(); private val resolver = id()
    private val command = CommandKey("purchasing.supplier-cash", 1)
    private val binding = OperationReservationBinding.frame("supplier-resolution-supporting", listOf("fixed-original"))
    private val reservation = FutureOperationReservation(OperationId.random(), original, command, binding)
    private fun requirement(action: SecurityAction, verb: PermissionVerb = PermissionVerb.PERFORM,
        owner: OwnerConstraint = OwnerConstraint.OwnerPrivate(original)) =
        AuthorizationRequirement(Permission(action, verb), scope, owner = owner)

    @Test fun supplierAndExistingResolveShapesRetainOriginalPrivateOwner() {
        for (action in listOf(SecurityAction.COLLECTION_RESOLVE, SecurityAction.REFUND_RESOLVE, SecurityAction.SUPPLIER_CASH_RESOLVE))
            assertTrue(reservation.matchesSupervisedResolutionRequirement(requirement(action)))
    }
    @Test fun differentVerbOrOwnerCannotQualifySupplierResolution() {
        assertFalse(reservation.matchesSupervisedResolutionRequirement(requirement(SecurityAction.SUPPLIER_CASH_RESOLVE, PermissionVerb.ADMINISTER)))
        assertFalse(reservation.matchesSupervisedResolutionRequirement(requirement(SecurityAction.SUPPLIER_CASH_RESOLVE,
            owner = OwnerConstraint.OwnerPrivate(resolver))))
        assertFalse(reservation.matchesSupervisedResolutionRequirement(requirement(SecurityAction.SUPPLIER_CASH_RESOLVE,
            owner = OwnerConstraint.Unrestricted)))
    }
    @Test fun ordinaryCashReadMoveAndSupplierSettlementRemainExcluded() {
        for (action in listOf(SecurityAction.CASH_READ, SecurityAction.CASH_MOVE, SecurityAction.SUPPLIER_CASH_SETTLE))
            assertFalse(reservation.matchesSupervisedResolutionRequirement(requirement(action)))
    }
    @Test fun otherActorStillNeedsExactCommandTargetAndRetainResolution() {
        assertTrue(reservation.matchesRecorderRoute(resolver, command, binding, PhysicalRecordingAction.RETAIN_RESOLUTION))
        for (action in listOf(PhysicalRecordingAction.START_PAYMENT, PhysicalRecordingAction.START_REFUND, PhysicalRecordingAction.RETAIN_ORIGINAL))
            assertFalse(reservation.matchesRecorderRoute(resolver, command, binding, action))
        assertFalse(reservation.matchesRecorderRoute(resolver, CommandKey(command.name, 2), binding, PhysicalRecordingAction.RETAIN_RESOLUTION))
        assertFalse(reservation.matchesRecorderRoute(resolver, command,
            OperationReservationBinding.frame("supplier-resolution-supporting", listOf("other-original")), PhysicalRecordingAction.RETAIN_RESOLUTION))
        assertFalse(reservation.matchesRecorderRoute(resolver, command, null, PhysicalRecordingAction.RETAIN_RESOLUTION))
    }
}
