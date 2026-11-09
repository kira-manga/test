package com.ukkera.pos.app.gateway.independent.returnrightspresentationv1

import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bounded L1 slices of the frozen RRP cases, through the actual common endpoint gateway.
 * Returned page data and controlled scheduling do not pass installed-UI or runtime obligations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReturnRightsPresentationGatewayFrozenSlicesTest {
    private val inputs = FrozenReturnRightsPresentationInputs

    @Test fun rrp001_currentNavigationRequiresInstalledReadAndActualBranchTargetHint() = runTest {
        for (workflow in inputs.workflows) for (variant in inputs.array("navigationAvailability")) {
            val harness = ControlledReturnRightsPresentationHarness(installedRead = variant.flag("installedRead"),
                grants = if (variant.flag("correctTargetHintPermits"))
                    ControlledReturnRightsPresentationHarness.allowingHint(inputs.scope) else emptyList())
            try {
                val page = available(harness.gateway.read(LocalWorkflowQuery(workflow)))
                val links = page.records.filter { it.reference.kind == LocalRecordKind.RETURN_RIGHTS_CURRENT }
                assertEquals(if (variant.flag("expectedCurrentLink")) 1 else 0, links.size, "RRP-001/$workflow/$variant")
                links.singleOrNull()?.let { link ->
                    assertEquals(inputs.scope.companyId, link.reference.id)
                    assertNull(link.reference.parentId)
                    assertNull(link.reference.revision)
                }
                assertNull(page.returnRights)
                assertEquals(0, harness.master.protectedRequests.size)
                harness.assertNoMutationDispatch("RRP-001/$workflow")
            } finally { harness.close() }
        }
    }

    @Test fun rrp002_currentSelectionUsesExactCanonicalProtectedCurrentQuery() = runTest {
        for (workflow in inputs.workflows) withHarness { harness ->
            val query = inputs.selection(workflow, historical = false)
            harness.master.replyFixture("F-RRP-A")
            assertDocumentaryPage(available(harness.gateway.read(query)), query, "F-RRP-A")
            assertEquals(listOf(inputs.root.string("currentQuery")), harness.master.protectedRequests.map { it.payload.rrpText() })
            assertEquals("local.return-rights.read", harness.master.protectedRequests.single().query.name)
            assertEquals(1, harness.master.protectedRequests.single().query.version)
            harness.assertNoMutationDispatch("RRP-002/$workflow")
        }
    }

    @Test fun rrp003_historyCurrentHistoryRetainsEachOriginalActivation() = runTest {
        for (workflow in inputs.workflows) withHarness { harness ->
            for ((historical, fixture) in listOf(true to "F-RRP-A", false to "F-RRP-B", true to "F-RRP-A")) {
                val query = inputs.selection(workflow, historical)
                harness.master.replyFixture(fixture)
                assertDocumentaryPage(available(harness.gateway.read(query)), query, fixture)
            }
            assertEquals(listOf(inputs.root.string("historicalAQuery"), inputs.root.string("currentQuery"),
                inputs.root.string("historicalAQuery")), harness.master.protectedRequests.map { it.payload.rrpText() })
            harness.assertNoMutationDispatch("RRP-003/$workflow")
        }
    }

    @Test fun rrp004_everyFrozenLiteralAndRuleSurvivesEnglishArabicEnglishSelection() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (fixture in listOf("F-RRP-A", "F-RRP-B")) withHarness { harness ->
                val query = inputs.selection(workflow, historical, publicationId(fixture))
                harness.master.replyFixture(fixture)
                val page = available(harness.gateway.read(query))
                assertDocumentaryPage(page, query, fixture)
                val rights = assertNotNull(page.returnRights)
                val originalNotices = rights.continuingRights
                for (language in listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC, AppLanguage.ENGLISH)) {
                    val field = if (language == AppLanguage.ARABIC) "arabicNoticeLiteral" else "englishNoticeLiteral"
                    assertEquals(inputs.visible(fixture).string(field), language.text(rights.continuingRights), "RRP-004/$fixture/$language")
                    assertEquals(originalNotices, rights.continuingRights)
                }
                harness.assertNoMutationDispatch("RRP-004/$workflow/$historical/$fixture")
            }
    }

    @Test fun rrp005_absenceReturnsNoTermsIncludingAfterPublishedReply() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true)) withHarness { harness ->
            val previous = inputs.selection(workflow, historical)
            harness.master.replyFixture("F-RRP-A")
            assertDocumentaryPage(available(harness.gateway.read(previous)), previous, "F-RRP-A")
            val query = inputs.selection(workflow, historical, inputs.id("unretainedPublication"))
            harness.master.replyFixture("F-RRP-ABSENT")
            val page = available(harness.gateway.read(query))
            assertStatusPage(page)
            val notice = assertNotNull(page.notice)
            assertTrue(notice.english.contains("No retained", ignoreCase = true))
            assertTrue(notice.english.contains("not a denial", ignoreCase = true))
            harness.assertNoMutationDispatch("RRP-005/$workflow/$historical")
        }
    }

    @Test fun rrp006_sourceUnavailableIsDistinctFromAbsenceAndClearsDocumentaryOutput() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true)) withHarness { harness ->
            val query = inputs.selection(workflow, historical)
            harness.master.replyFixture("F-RRP-A")
            assertDocumentaryPage(available(harness.gateway.read(query)), query, "F-RRP-A")
            harness.master.replyFixture("F-RRP-SOURCE-UNAVAILABLE")
            val unavailable = available(harness.gateway.read(query))
            assertStatusPage(unavailable)
            harness.master.replyFixture("F-RRP-ABSENT")
            val absent = available(harness.gateway.read(
                inputs.selection(workflow, historical, inputs.id("unretainedPublication"))))
            assertStatusPage(absent)
            for (language in listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC))
                assertNotEquals(language.text(assertNotNull(absent.notice)), language.text(assertNotNull(unavailable.notice)))
            assertTrue(assertNotNull(unavailable.notice).english.contains("unavailable", ignoreCase = true))
            harness.assertNoMutationDispatch("RRP-006/$workflow/$historical")
        }
    }

    @Test fun rrp007_eachFrozenForeignScopeFailsForHistoricalAAndCurrentB() = runTest {
        for (workflow in inputs.workflows) for (variant in inputs.array("scopeMismatchVariants"))
            for (base in variant.getValue("bases").jsonArray) withHarness { harness ->
                val named = base.jsonPrimitive.content.split(' ')
                val historical = named[1] == "historical"
                harness.master.replyText(inputs.editedRead(variant, named[0]))
                assertProblem(harness.gateway.read(inputs.selection(workflow, historical)), variant.string("expectedProblem"))
                harness.assertNoMutationDispatch("RRP-007/$workflow/${variant.string("variantId")}/${base.jsonPrimitive.content}")
            }
    }

    @Test fun rrp008_referenceTupleDomainDigestAndRequestedHistoryRemainBound() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (variant in inputs.array("referenceMismatchVariants")) withHarness { harness ->
                harness.master.replyText(inputs.editedRead(variant))
                assertProblem(harness.gateway.read(inputs.selection(workflow, historical)), variant.string("expectedProblem"))
                harness.assertNoMutationDispatch("RRP-008/$workflow/$historical/${variant.string("variantId")}")
            }
        for (workflow in inputs.workflows) withHarness { harness ->
            val mismatch = inputs.root.getValue("historicalPublicationMismatch").jsonObject
            harness.master.replyFixture(mismatch.string("replyFixture"))
            assertProblem(harness.gateway.read(inputs.selection(workflow, true, DomainId(mismatch.string("requestedId")))),
                mismatch.string("expectedProblem"))
            harness.assertNoMutationDispatch("RRP-008/$workflow/historical-publication-mismatch")
        }
    }

    @Test fun rrp009_everyFrozenMalformedReplyFailsWithoutRepairForBothSelectors() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (variant in inputs.array("malformedResponseVariants")) withHarness { harness ->
                harness.master.replyText(inputs.editedRead(variant))
                assertProblem(harness.gateway.read(inputs.selection(workflow, historical)), variant.string("expectedProblem"))
                harness.assertNoMutationDispatch("RRP-009/$workflow/$historical/${variant.string("variantId")}")
            }
    }

    @Test fun rrp010_representableFrozenMixedSelectorsFailBeforeProtectedRead() = runTest {
        val unbound = setOf("SEL-01", "SEL-04", "SEL-05", "SEL-06", "SEL-11")
        for (variant in inputs.array("invalidSelectorVariants").filterNot { it.string("variantId") in unbound })
            for (base in variant.getValue("bases").jsonArray) {
                val workflows = if (variant.string("replaceSelectionField") == "workflow")
                    listOf(LocalWorkflow.PEOPLE) else inputs.workflows
                for (workflow in workflows) withHarness { harness ->
                    val query = inputs.boundInvalidSelection(variant, workflow, base.jsonPrimitive.content == "historical")
                    assertProblem(harness.gateway.read(query), variant.string("expectedProblem"))
                    assertEquals(0, harness.master.protectedRequests.size)
                    assertEquals(0, harness.master.requests.size)
                    harness.assertNoMutationDispatch("RRP-010/$workflow/$base/${variant.string("variantId")}")
                }
            }
    }

    @Test fun rrp011_cachedPermissiveHintCannotOverrideControlledPermissionRefusal() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true)) withHarness { harness ->
            val query = inputs.selection(workflow, historical)
            harness.master.replyFixture("F-RRP-A")
            assertDocumentaryPage(available(harness.gateway.read(query)), query, "F-RRP-A")
            harness.master.replyUnavailable(ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED))
            assertProblem(harness.gateway.read(query), "DENIED")
            assertEquals(2, harness.master.protectedRequests.size)
            harness.assertNoMutationDispatch("RRP-011/$workflow/$historical")
        }
    }

    @Test fun rrp012_missingReadSupportStaysUnimplementedForCurrentAndHistory() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true)) {
            val harness = ControlledReturnRightsPresentationHarness(installedRead = false)
            try {
                assertProblem(harness.gateway.read(inputs.selection(workflow, historical)), "NOT_IMPLEMENTED")
                assertEquals(0, harness.master.protectedRequests.size)
                assertNull(available(harness.gateway.read(LocalWorkflowQuery(workflow))).returnRights)
                harness.assertNoMutationDispatch("RRP-012/$workflow/$historical")
            } finally { harness.close() }
        }
    }

    @Test fun rrp013_eachFrozenLeaseEndOrReplacementBeforeReadDispatchEndsSession() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (variant in inputs.array("leaseVariants")) withHarness { harness ->
                val delivery = ControlledDelivery<com.ukkera.pos.security.LocalSessionViewResult>()
                harness.master.currentDelivery = delivery
                val pending = async { harness.gateway.read(inputs.selection(workflow, historical)) }
                runCurrent()
                assertTrue(delivery.waiting.isCompleted)
                assertEquals(0, harness.master.protectedRequests.size)
                harness.invalidate(variant.string("variantId"))
                delivery.deliver()
                runCurrent()
                assertProblem(pending.await(), variant.string("expectedProblem"))
                assertEquals(0, harness.master.protectedRequests.size)
                harness.assertNoMutationDispatch("RRP-013/$workflow/$historical/${variant.string("variantId")}")
            }
    }

    @Test fun rrp014_leaseChangeWhileReadWaitsOrAfterReplyDeliveryNeverReleasesPage() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (fixture in listOf("F-RRP-A", "F-RRP-B"))
                for (variant in inputs.array("leaseVariants")) for (afterReply in listOf(false, true)) withHarness { harness ->
                    val delivery = ControlledDelivery<ReadReply>()
                    harness.master.readDelivery = delivery
                    harness.master.replyFixture(fixture)
                    val pending = async { harness.gateway.read(inputs.selection(workflow, historical, publicationId(fixture))) }
                    runCurrent()
                    assertTrue(delivery.waiting.isCompleted)
                    assertEquals(1, harness.master.protectedRequests.size)
                    if (afterReply) delivery.deliver()
                    harness.invalidate(variant.string("variantId"))
                    if (!afterReply) delivery.deliver()
                    runCurrent()
                    assertProblem(pending.await(), variant.string("expectedProblem"))
                    harness.assertNoMutationDispatch("RRP-014/$workflow/$historical/$fixture/${variant.string("variantId")}/afterReply=$afterReply")
                }
    }

    @Test fun rrp015_eachFrozenCancellationBoundaryPropagatesCancellation() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (boundary in inputs.root.getValue("cancellationBoundaries").jsonArray) withHarness { harness ->
                val named = boundary.jsonPrimitive.content
                val beforeReadDelivery = ControlledDelivery<com.ukkera.pos.security.LocalSessionViewResult>()
                val delivery = ControlledDelivery<ReadReply>()
                if (named == "before_read") harness.master.currentDelivery = beforeReadDelivery
                else harness.master.readDelivery = delivery
                harness.master.replyFixture("F-RRP-A")
                var normalReadResult: LocalReadResult? = null
                var readCancellation: CancellationException? = null
                val pending = async {
                    try {
                        harness.gateway.read(inputs.selection(workflow, historical)).also { normalReadResult = it }
                    } catch (cancelled: CancellationException) {
                        readCancellation = cancelled
                        throw cancelled
                    }
                }
                runCurrent()
                if (named == "before_read") {
                    assertTrue(beforeReadDelivery.waiting.isCompleted)
                    assertEquals(0, harness.master.protectedRequests.size)
                } else {
                    assertTrue(delivery.waiting.isCompleted)
                    if (named == "reply_received_before_page_release") delivery.deliver()
                }
                pending.cancel()
                if (named == "before_read") beforeReadDelivery.deliver()
                if (named == "read_waiting") delivery.deliver()
                runCurrent()
                assertNull(normalReadResult, "RRP-015/$workflow/$historical/$named must not return a read result")
                assertNotNull(readCancellation, "RRP-015/$workflow/$historical/$named must propagate cancellation from gateway.read")
                assertFailsWith<CancellationException> { pending.await() }
                assertEquals(if (named == "before_read") 0 else 1, harness.master.protectedRequests.size)
                harness.assertNoMutationDispatch("RRP-015/$workflow/$historical/$named")
            }
    }

    @Test fun rrp016_replacementScopeUsesNewCompanyQueryAndFreshProtectedRefusal() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true)) {
            val previous = ControlledReturnRightsPresentationHarness()
            val replacement = ControlledReturnRightsPresentationHarness(inputs.replacementScope,
                DomainId(inputs.replacement.string("actorId")))
            try {
                val oldQuery = inputs.selection(workflow, historical)
                previous.master.replyFixture("F-RRP-A")
                assertDocumentaryPage(available(previous.gateway.read(oldQuery)), oldQuery, "F-RRP-A")
                previous.close()
                replacement.master.replyUnavailable(ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED))
                val freshQuery = inputs.selection(workflow, historical, selectedScope = inputs.replacementScope)
                assertProblem(replacement.gateway.read(freshQuery), "DENIED")
                assertEquals(listOf(inputs.query(historical)), replacement.master.protectedRequests.map { it.payload.rrpText() })
                if (!historical) assertEquals(inputs.replacementScope.companyId, assertNotNull(freshQuery.reference).id)
                previous.assertNoMutationDispatch("RRP-016/original/$workflow/$historical")
                replacement.assertNoMutationDispatch("RRP-016/replacement/$workflow/$historical")
            } finally { previous.close(); replacement.close() }
        }
    }

    @Test fun rrp017_documentaryReplyStatesExposeNoActionFormsOrMutationDispatch() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true)) withHarness { harness ->
            val query = inputs.selection(workflow, historical)
            for (fixture in listOf("F-RRP-A", "F-RRP-B", "F-RRP-ABSENT", "F-RRP-SOURCE-UNAVAILABLE")) {
                val selected = inputs.selection(workflow, historical,
                    when (fixture) {
                        "F-RRP-A", "F-RRP-B" -> publicationId(fixture)
                        "F-RRP-ABSENT" -> inputs.id("unretainedPublication")
                        else -> inputs.id("publicationA")
                    })
                harness.master.replyFixture(fixture)
                val page = available(harness.gateway.read(selected))
                assertTrue(page.forms.isEmpty())
                assertTrue(page.records.isEmpty())
                assertNull(page.priceControls)
                assertNull(page.nextCursor)
            }
            harness.master.replyUnavailable(ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED))
            assertProblem(harness.gateway.read(query), "DENIED")
            harness.assertNoMutationDispatch("RRP-017/$workflow/$historical")
            val dispatch = inputs.effects.getValue("expectedDispatchAndCreationTotals").jsonObject
            for (field in listOf("activationCommands", "saleMutations", "returnOrRefundMutations", "stockMutations",
                "entitlementMutations", "customerBalanceMutations"))
                assertEquals(dispatch.string(field).toInt(), harness.master.mutationDispatches, field)
            // Signing, qualification, evidence creation and committed business baselines require
            // their genuine boundaries; an unused fake counter must not supply those conclusions.
        }
    }

    @Test fun rrp018_maximumRevisionAndEveryNullableOrLongerPeriodRemainExact() = runTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (fixture in listOf("F-RRP-SCALAR", "F-RRP-A", "F-RRP-B")) withHarness { harness ->
                val query = inputs.selection(workflow, historical, publicationId(fixture))
                harness.master.replyFixture(fixture)
                assertDocumentaryPage(available(harness.gateway.read(query)), query, fixture)
                harness.assertNoMutationDispatch("RRP-018/$workflow/$historical/$fixture")
            }
    }

    @Test fun rrp019_boundTargetMatrixRespectsExactBranchPublicationAndTargetDenies() = runTest {
        val variants = inputs.array("targetPermissionVariants").filterNot { it.string("variantId") in setOf("TARGET-05", "TARGET-09") }
        for (workflow in inputs.workflows) for (variant in variants) {
            val grants = ControlledReturnRightsPresentationHarness.targetGrants(variant)
            val navigation = ControlledReturnRightsPresentationHarness(grants = grants)
            try {
                val links = available(navigation.gateway.read(LocalWorkflowQuery(workflow))).records
                    .filter { it.reference.kind == LocalRecordKind.RETURN_RIGHTS_CURRENT }
                assertEquals(if (variant.flag("expectedCurrentBranchHint")) 1 else 0, links.size)
                navigation.assertNoMutationDispatch("RRP-019/navigation/$workflow/${variant.string("variantId")}")
            } finally { navigation.close() }
            for ((historical, fixture, expectation) in listOf(
                Triple(false, "F-RRP-B", "expectedCurrentBranchHint"),
                Triple(true, "F-RRP-A", "expectedHistoricalAHint"),
                Triple(true, "F-RRP-B", "expectedHistoricalBHint")))
                for (masterRefuses in listOf(false, true)) {
                    val harness = ControlledReturnRightsPresentationHarness(grants = grants)
                    try {
                        val query = inputs.selection(workflow, historical, publicationId(fixture))
                        if (masterRefuses) harness.master.replyUnavailable(ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED))
                        else harness.master.replyFixture(fixture)
                        val result = harness.gateway.read(query)
                        val allowed = variant.flag(expectation)
                        if (allowed && !masterRefuses) assertDocumentaryPage(available(result), query, fixture)
                        else assertProblem(result, "DENIED")
                        assertEquals(if (allowed) 1 else 0, harness.master.protectedRequests.size)
                        harness.assertNoMutationDispatch("RRP-019/$workflow/${variant.string("variantId")}/$fixture/$historical/$masterRefuses")
                    } finally { harness.close() }
                }
        }
    }

    private suspend fun withHarness(block: suspend (ControlledReturnRightsPresentationHarness) -> Unit) {
        val harness = ControlledReturnRightsPresentationHarness()
        try { block(harness) } finally { harness.close() }
    }

    private fun publicationId(fixture: String): DomainId = DomainId(inputs.visible(fixture).string("publicationId"))
}

internal fun available(result: LocalReadResult): LocalWorkflowPage = assertNotNull((result as? LocalReadResult.Available)?.page,
    "The exact frozen controlled reply must produce its documentary page")

internal fun assertProblem(result: LocalReadResult, expected: String) {
    assertEquals(LocalServiceProblem.valueOf(expected), assertNotNull(result as? LocalReadResult.Unavailable).problem)
}

internal fun assertStatusPage(page: LocalWorkflowPage) {
    assertNull(page.returnRights)
    assertTrue(page.records.isEmpty())
    assertTrue(page.forms.isEmpty())
    assertNull(page.priceControls)
    assertNull(page.nextCursor)
    val notice = assertNotNull(page.notice)
    assertTrue(notice.english.isNotBlank())
    assertTrue(notice.arabic.isNotBlank())
}

internal fun assertDocumentaryPage(page: LocalWorkflowPage, query: LocalWorkflowQuery, fixture: String) {
    val inputs = FrozenReturnRightsPresentationInputs
    val expected = inputs.visible(fixture)
    val rights = assertNotNull(page.returnRights, fixture)
    assertEquals(query.workflow, page.workflow)
    assertEquals(query.reference, rights.selection)
    assertEquals(inputs.scope.companyId, rights.companyId)
    assertEquals(inputs.scope.branchId, rights.branchId)
    assertEquals(query.reference?.kind == LocalRecordKind.RETURN_RIGHTS_PUBLICATION, rights.historical)
    assertTrue(page.forms.isEmpty())
    assertTrue(page.records.isEmpty())
    assertNull(page.priceControls)
    assertNull(page.nextCursor)
    assertEquals(expected.string("englishNoticeLiteral"), rights.continuingRights.english)
    assertEquals(expected.string("arabicNoticeLiteral"), rights.continuingRights.arabic)
    for (field in listOf("jurisdictionCode", "legalSourceUri", "legalSourceAccessDate", "publicationId", "policyId",
        "companyRevision", "originalActiveRevision", "originalOperationId", "originalActorId",
        "originalPermissionPolicyRevision", "originalRecordedAt")) {
        val literal = expected.string(field)
        assertTrue(rights.attribution.any { it.value == LocalText(literal, literal) }, "$fixture/$field must be retained literally")
    }
    for ((field, semanticLabel) in listOf("companyRevision" to "company policy revision",
        "originalPermissionPolicyRevision" to "permission policy revision")) {
        val attribution = rights.attribution.single { it.label.english.contains(semanticLabel, ignoreCase = true) }
        val literal = expected.string(field)
        assertEquals(LocalText(literal, literal), attribution.value, "$fixture/$field must retain both locale values at its own label")
        assertTrue(attribution.label.arabic.isNotBlank())
    }
    for ((field, semanticLabel) in listOf("originalActiveRevision" to "local revision", "originalOperationId" to "operation",
        "originalActorId" to "employee", "originalRecordedAt" to "time")) {
        val attribution = rights.attribution.single { it.label.english.contains(semanticLabel, ignoreCase = true) }
        val literal = expected.string(field)
        assertEquals(LocalText(literal, literal), attribution.value)
        assertTrue(attribution.label.english.contains("original", ignoreCase = true), "$field belongs to the original activation")
        assertTrue(attribution.label.arabic.isNotBlank())
    }
    val rules = expected.getValue("rules").jsonArray.map { it.jsonObject }
    assertEquals(rules.map { it.string("categoryCode") to it.string("reason") },
        rights.rules.map { it.categoryCode to it.reasonCode })
    for ((row, rule) in rights.rules.zip(rules)) {
        for (field in listOf("categoryCode", "reason", "treatment")) {
            val literal = rule.string(field)
            assertTrue(row.values.any { it.value == LocalText(literal, literal) }, "$fixture/${row.categoryCode}/$field")
        }
        for ((field, label) in listOf("receiptWindowDays" to "receipt window", "coveredResolutionDays" to "resolution period",
            "exceptionBasis" to "exception basis")) {
            val value = row.values.single { it.label.english.contains(label, ignoreCase = true) }
            assertDocumentaryNullable(value, rule, field, "$fixture/${row.categoryCode}/${row.reasonCode}/$field")
        }
    }
}

private fun assertDocumentaryNullable(value: LocalRecordValue, rule: JsonObject, field: String, context: String) {
    val expected = rule.getValue(field)
    if (expected != JsonNull) assertEquals(LocalText(expected.jsonPrimitive.content, expected.jsonPrimitive.content), value.value, context)
    else {
        assertTrue(value.value.english.contains("not specified", ignoreCase = true) ||
            value.value.english.contains("unspecified", ignoreCase = true), context)
        assertTrue(value.value.arabic.contains("غير محدد"), context)
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC)) {
            val literal = language.text(value.value)
            assertNotEquals("0", literal, context)
            assertFalse(literal.equals("unlimited", true) || literal.equals("expired", true), context)
        }
    }
}
