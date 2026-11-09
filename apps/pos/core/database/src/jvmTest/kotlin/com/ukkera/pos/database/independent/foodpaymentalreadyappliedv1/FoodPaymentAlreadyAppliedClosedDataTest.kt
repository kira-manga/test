package com.ukkera.pos.database.independent.foodpaymentalreadyappliedv1

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.LocalFoodPaymentCodec
import com.ukkera.pos.sales.store.LocalFoodPaymentRequest
import com.ukkera.pos.sales.store.LocalFoodPaymentResult
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Supporting DATA slices of frozen food-payment-already-applied-v1 v1.0.0.
 *
 * The positive matcher/capsule checks exercise real production types, with no
 * issued authorization, native read, writer, settlement or current grant. The
 * application payload and history/clock/dependency fields are syntax fillers;
 * businessProvenance remains UNQUALIFIED. Encoders provide inputs only, never
 * canonical-byte or digest expectations (FAA-Q04). No genuine ordinary source,
 * original accounting, protected disclosure or whole FAA case is qualified.
 */
class FoodPaymentAlreadyAppliedClosedDataTest {
    private val f = AlreadyAppliedDatabaseData()
    private val profile = RuntimeHistoricalFoodPaymentRejectionProfile.ALREADY_APPLIED

    @Test
    fun faa001CashReadOriginalCandidateMatchesClosedDataOnly() {
        val value = f.authorization()
        value.validate() // Syntax validation does not issue a principal or grant.
        assertTrue(profile.matches(f.header(), value))
        assertEquals(f.actor, value.authorization.actorId)
        assertEquals(f.operation, value.requirement.operationId)
        assertEquals(f.scope, value.requirement.scope)
        assertEquals(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), value.requirement.value().permission)
        assertEquals(f.command, RuntimeHistoricalFoodPaymentRejectionProfile.fromCommand(f.command)?.command)
    }

    @Test
    fun faa003AttestOrOrderReadCannotReplaceCapturedCashReadData() {
        val value = f.authorization()
        assertTrue(profile.matches(f.header(), value))
        for (action in listOf(SecurityAction.COLLECTION_ATTEST, SecurityAction.ORDER_READ)) {
            val substituted = value.copy(requirement = value.requirement.copy(action = action))
            assertFalse(profile.matches(f.header(), substituted))
        }
        assertFalse(profile.matches(f.header(), value.copy(authorization = value.authorization.copy(
            principalKind = PrincipalKind.ADMITTED_DEVICE))))
        assertFalse(profile.matches(f.header(), value.copy(authorization = value.authorization.copy(
            authenticationPurpose = AuthenticationPurpose.SERVICE))))
        // Captured requirement only; not a current Master permission/revocation test.
    }

    @Test
    fun faa005OriginalCoordinateSubstitutionsCannotMatchClosedData() {
        val value = f.authorization()
        val replacements = listOf(
            value.copy(commandVersion = 2),
            value.copy(requirement = value.requirement.copy(operationId = f.wrongOperation)),
            value.copy(requirement = value.requirement.copy(payloadDigest = f.otherDigest)),
            value.copy(requirement = value.requirement.copy(scope = f.scope.copy(branchId = f.id("other_branch")))),
            value.copy(requirement = value.requirement.copy(scope = f.scope.copy(companyId = f.id("other_company")))),
            value.copy(requirement = value.requirement.copy(ownerId = f.id("actor_b"))),
        )
        for (candidate in replacements) assertFalse(profile.matches(f.header(), candidate))
        assertNull(RuntimeHistoricalFoodPaymentRejectionProfile.fromCommand(CommandKey(f.command.name, 2)))
        assertEquals(f.actor, value.authorization.actorId)
        assertEquals(f.operation, value.operationId)
        assertEquals(f.scope, value.authorization.scope)
    }

    @Test
    fun faa001EarlierAppliedCarrierCannotMatchOrdinaryRejectionData() {
        val value = f.authorization()
        val appliedHeader = f.header().copy(purpose = RuntimeWritePurposeV2.COMMAND_APPLY)
        assertFalse(profile.matches(appliedHeader, value))
        assertFalse(profile.matches(f.header(), value.copy(purposeTag = RuntimeWritePurposeV2.COMMAND_APPLY.tag)))
        val earlierCarrier = f.evidence(code = "committed")
        assertFailsWith<IllegalArgumentException> { f.sources(earlierCarrier) }
    }

    @Test
    fun faa001LiteralCapsuleRetainsIdentityWithoutBusinessQualification() {
        val evidence = f.evidence()
        val source = f.sources(evidence)
        assertSame(evidence, source.evidence)
        assertEquals(f.operation, source.evidence.operationId)
        assertEquals(f.actor, source.evidence.originalActorId)
        assertEquals(f.scope, source.evidence.scope)
        assertEquals(f.id("original_recording"), source.beforeApplication.row.key.id)
        assertEquals(RuntimeNativeLiteralBusinessProvenance.UNQUALIFIED, source.beforeApplication.businessProvenance)
        assertTrue(source.evidence.aggregateWrites.isEmpty())
        assertTrue(source.evidence.claimChanges.isEmpty())
        assertTrue(source.evidence.jobs.isEmpty())
        // This is positive capsule syntax only. The opaque BEFORE payload is not
        // an APPLIED application and cannot establish FAA-F01 or native custody.
    }

    @Test
    fun faa001faa010ClosedCapsuleRejectsAdditionalBusinessEffectsData() {
        val key = AggregateKey("cash.session", f.id("cash_session_a"))
        val payload = f.bytes("unqualified-extra-cash-write")
        val originals = listOf(
            f.evidence(writes = listOf(RetainedAggregateWrite(key, Revision(1), "cash.session/v1", payload,
                sha256(payload.copyBytes())))),
            f.evidence(claims = listOf(RetainedClaimChange(RetainedClaimAction.ACQUIRE,
                ClaimKey("sales.food.reservation", f.id("portion_1").value), f.id("order")))),
            f.evidence(jobs = listOf(RetainedJobIntent("sales.food.kitchen-task", f.bytes("unqualified-extra-task")))),
        )
        for (original in originals) assertFailsWith<IllegalArgumentException> { f.sources(original) }
        val refused = f.evidence(code = "food.payment.pending")
        assertFailsWith<IllegalArgumentException> { f.sources(refused) }
        // The common zero-extra-effect oracle is exercised at capsule admission;
        // no ledger, physical-capture, reservation or task count is observed here.
    }

    @Test
    fun faa005faa010MissingOrForeignBeforeApplicationCannotCreateClosedCapsule() {
        val original = f.evidence()
        val before = f.beforeApplication(original)
        assertFailsWith<IllegalArgumentException> { f.sources(original, before, emptyList()) }
        val foreignKey = RuntimeNativeLiteralAggregateSource(
            StoredAggregate(before.row.key.copy(id = f.id("wrong_identity")), before.row.revision,
                before.row.schema, before.row.payload, before.row.digest), before.original, before.writerHistorySequence)
        assertFailsWith<IllegalArgumentException> { f.sources(original, foreignKey) }
        val foreignWriter = RuntimeNativeLiteralAggregateSource(before.row, RetainedOriginalV2.Legacy(
            f.evidence(code = "committed", operation = f.wrongOperation, sequence = 1,
                scope = f.scope.copy(branchId = f.id("other_branch")))), before.writerHistorySequence)
        assertFailsWith<IllegalArgumentException> { f.sources(original, foreignWriter) }
        assertEquals(f.id("original_recording"), before.row.key.id)
        assertEquals(f.scope, original.scope)
    }

    @Test
    fun faa011CallerCollectionMutationDoesNotRewriteCapturedData() {
        val original = f.evidence()
        val before = f.beforeApplication(original)
        val aggregates = mutableListOf(before)
        val resources = f.resources(original).toMutableList()
        val source = f.sources(original, before, aggregates, resources)
        val bodyBefore = original.normalized.copyBytes()
        val resultBefore = original.result.copyBytes()
        aggregates.clear()
        resources.clear()
        assertSame(before, source.beforeAggregateSources.single())
        assertEquals(f.id("cash_session_a"), source.resources.single().resourceId)
        assertContentEquals(bodyBefore, source.evidence.normalized.copyBytes())
        assertContentEquals(resultBefore, source.evidence.result.copyBytes())
        // Collection ownership only, not coroutine/remote/native lifetime qualification.
    }

    @Test
    fun faa010UnconfiguredOriginalBeforeAccessorIsUnavailable() = runTest {
        val view = object : CommandReadView {
            override val scope = f.scope
            override suspend fun aggregate(key: AggregateKey): StoredAggregate? = error("Unexpected current-row fallback")
            override suspend fun claim(key: ClaimKey): DomainId? = error("Unexpected current-claim fallback")
            override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = error("Unexpected original fallback")
        }
        assertNull(view.foodPaymentAlreadyAppliedOriginalSources(f.operation))
        // Null source capability is not an authoritative absent operation or empty cut.
    }
}

private class AlreadyAppliedDatabaseData {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/food-payment-already-applied-v1") }
        .first { Files.isRegularFile(it.resolve("fixtures.json")) }
    private val root = Files.readAllBytes(directory.resolve("fixtures.json")).let { bytes ->
        check(sha256(bytes).value == "91ef569172f09e39906996c893bdc19a7e4eb178e07826e4443fbc87b92c9785")
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }.also { check(it.getValue("fixture_version").jsonPrimitive.content == "1.0.0") }
    private val ids = root.getValue("ids").jsonObject
    private val identity = root.getValue("FAA-F01").jsonObject.getValue("original_identity").jsonObject
    val scope = BranchScope(id("organization"), id("food_company"), id("branch"))
    val actor = id("actor_a")
    val operation = OperationId(id("operation").value)
    val wrongOperation = OperationId(id("wrong_identity").value)
    val command = CommandKey(identity.getValue("command_key").jsonPrimitive.content,
        identity.getValue("command_version").jsonPrimitive.int)
    val syntaxDigest = sha256("Unqualified FAA syntax filler".encodeToByteArray())
    val otherDigest = sha256("Different unqualified FAA syntax filler".encodeToByteArray())
    private val body = LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.ApplyOriginal(OperationId(id("original_recording").value)))
    private val at = UtcInstant.parseCanonical("2026-11-06T00:00:00Z") // Unasserted syntax time.
    fun id(label: String) = DomainId(ids.getValue(label).jsonPrimitive.content)
    fun bytes(value: String) = StoredBytes(value.encodeToByteArray())

    fun evidence(code: String = "food.payment.already_applied", operation: OperationId = this.operation,
        sequence: Long = 2, scope: BranchScope = this.scope,
        writes: List<RetainedAggregateWrite> = emptyList(), claims: List<RetainedClaimChange> = emptyList(),
        jobs: List<RetainedJobIntent> = emptyList()): RetainedCommandEvidence {
        val result = LocalFoodPaymentCodec.result(LocalFoodPaymentResult.AlreadyApplied(
            OperationId(id("original_recording").value), wrongOperation))
        return RetainedCommandEvidence(scope, operation, actor, command, id("wrong_identity"), Revision(1),
            JournalSequence(sequence), at, id("auth_session_a"), Revision(7), semanticDigest(scope, actor, command, body),
            code, body, result, sha256(result.copyBytes()), syntaxDigest, syntaxDigest, writes, claims, jobs)
    }

    private fun requirement(original: RetainedCommandEvidence) = AuthorizationRequirement(
        Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), original.scope,
        ActionBinding(id("original_recording"), Revision(1), original.semanticDigest, original.operationId,
            "food.payment.already_applied"), LimitUse.NotApplicable, OwnerConstraint.OwnerPrivate(actor))

    fun resources(original: RetainedCommandEvidence) = listOf(NativeHistoryOriginalResourceV2(
        3, NativeHistoryResourceKindV2.CASH_CUSTODY, id("cash_session_a"), true, original.semanticDigest))

    fun beforeApplication(original: RetainedCommandEvidence): RuntimeNativeLiteralAggregateSource {
        val payload = bytes("Unqualified BEFORE application DATA; no typed business body")
        val row = StoredAggregate(AggregateKey(RuntimeHistoricalFoodPaymentAlreadyAppliedSources.APPLICATION_KIND,
            id("original_recording")), Revision(2), RuntimeHistoricalFoodPaymentAlreadyAppliedSources.APPLICATION_SCHEMA,
            payload, sha256(payload.copyBytes()))
        return RuntimeNativeLiteralAggregateSource(row, RetainedOriginalV2.Legacy(evidence(code = "committed",
            operation = wrongOperation, sequence = 1, scope = original.scope)), 1)
    }

    fun sources(original: RetainedCommandEvidence, before: RuntimeNativeLiteralAggregateSource = beforeApplication(original),
        aggregates: List<RuntimeNativeLiteralAggregateSource> = listOf(before),
        resources: List<NativeHistoryOriginalResourceV2> = resources(original)) =
        RuntimeHistoricalFoodPaymentAlreadyAppliedSources(original, requirement(original), resources, before,
            aggregates, emptyList(), emptySet(), emptySet())

    fun authorization(): RuntimeCommandAuthorizationEvidenceV1 {
        val original = evidence()
        val access = RuntimeCommandAccessWindowV1(id("wrong_identity"), MonotonicMoment(id("wrong_identity"), 1),
            MonotonicMoment(id("wrong_identity"), 2), 1000, Revision(1), Revision(1))
        val snapshot = RuntimeCommandAuthorizationSnapshotV1(scope, actor, id("auth_session_a"), id("device_a"),
            SessionGeneration(1), PrincipalKind.LOCAL_EMPLOYEE, AuthenticationPurpose.INTERACTIVE,
            Revision(1), Revision(1), Revision(1), Revision(7), access,
            RuntimeCommandAuthorizationDependenciesV1(syntaxDigest, syntaxDigest, syntaxDigest, syntaxDigest))
        return RuntimeCommandAuthorizationEvidenceV1(purposeTag = RuntimeWritePurposeV2.COMMAND_REJECT.tag,
            operationId = operation, commandName = command.name, commandVersion = command.version,
            semanticDigest = original.semanticDigest, requirement = RuntimeCommandRequirementV1.capture(requirement(original)),
            authorization = snapshot, actionApprovalId = null, nativeRecordDigest = syntaxDigest,
            capturedChanges = RuntimeHistoricalFoodPaymentRejectionProfile.CAPTURED_CHANGES)
    }

    fun header() = RuntimeHistoryEventHeaderV2(scope = "${scope.organizationId.value}/${scope.companyId.value}/${scope.branchId.value}",
        storageId = id("wrong_identity"), bindingDigest = syntaxDigest, originalAllocationDigest = syntaxDigest,
        definitionDigest = syntaxDigest, installationAttemptId = id("wrong_identity"), installationAttemptSequence = 1,
        installationGenesisDigest = syntaxDigest, sequence = 2, parentKind = RuntimeHistoryParentKindV2.RECORDED_EVENT.tag,
        parentSequence = 1, parentDigest = syntaxDigest, transactionId = id("wrong_identity"), assignmentId = id("wrong_identity"),
        purpose = RuntimeWritePurposeV2.COMMAND_REJECT, commandEpoch = 1, commandSequence = 2, commandDigest = syntaxDigest,
        roleCommitment = syntaxDigest, recoveryCommitment = syntaxDigest,
        changeCount = RuntimeHistoricalFoodPaymentRejectionProfile.CAPTURED_CHANGES, chunkCount = 1, imageBytes = 0,
        intentBytes = 1, intentDigest = syntaxDigest, dependencyBytes = 0, dependencyDigest = syntaxDigest,
        factBytes = 0, factDigest = syntaxDigest)
}
