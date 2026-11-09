package com.ukkera.pos.app.setup.independent.employeepolicyauthoring

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import com.ukkera.pos.app.setup.LocalSetupController
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicies
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyStage
import com.ukkera.pos.app.setup.LocalSetupFirstAccountInput
import com.ukkera.pos.app.setup.LocalSetupNewCompanyInput
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupScreen
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.app.setup.LocalSetupWork
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalEmployeePolicyActivationDefinition
import com.ukkera.pos.security.LocalEmployeePolicyActivationPhase
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Frozen v2 rendered UI and controlled public-DATA observations. The controlled controller
 * is not a native owner, a Master, a credential verifier, persistence or a transport binding.
 */
class EmployeePolicyAuthoringRenderedTest {
    @get:Rule val ui = createComposeRule()
    private val fixtures = EmployeePolicyAuthoringFixtures
    private lateinit var controller: ControlledPolicyView
    private lateinit var labels: Labels

    @Test fun epaC01EnglishCompleteEditorAndReview() = completeEditorAndReview(AppLanguage.ENGLISH)
    @Test fun epaC01ArabicCompleteEditorAndReview() = completeEditorAndReview(AppLanguage.ARABIC)

    @Test fun epaC02AbsentEmployeeOrPredecessorCannotPrepare() {
        editor()
        replace(labels.predecessor, "7")
        ui.onAllNodes(hasText(labels.prepare)).assertCountEquals(0)
        replace(labels.predecessor, "")
        replace(labels.employee, fixtures.employee.value)
        ui.onAllNodes(hasText(labels.prepare)).assertCountEquals(0)
        assertNoMutation()
    }

    @Test fun epaC02AbsentSessionOrThrottleCannotPrepare() {
        editor()
        fillPolicy("replacement_policy")
        replace(labels.sessions, "")
        clickCheckedConsent(labels.prepareConsent)
        click(labels.prepare)
        assertNoMutation()
        replace(labels.sessions, "2")
        listOf(labels.free, labels.initial, labels.maximum, labels.timeout).forEach { replace(it, "") }
        clickCheckedConsent(labels.prepareConsent)
        click(labels.prepare)
        assertNoMutation()
    }

    @Test fun epaC03MinimumProfileThroughCompleteRenderedEditor() = exactEditorProfile("minimum_values")
    @Test fun epaC03MaximumProfileThroughCompleteRenderedEditor() = exactEditorProfile("maximum_values")

    @Test fun epaC03InvalidNumericInputsCannotPrepareOrClamp() {
        editor()
        fillPolicy("replacement_policy")
        val fields = mapOf(
            "sessionPolicy.maximumConcurrentSessions" to labels.sessions,
            "throttlePolicy.freeFailures" to labels.free,
            "throttlePolicy.initialDelayMillis" to labels.initial,
            "throttlePolicy.maximumDelayMillis" to labels.maximum,
            "throttlePolicy.attemptTimeoutMillis" to labels.timeout,
            "approvalPolicy.validForMillis" to labels.approvalMillis,
        )
        // The one-character free-failures editor cannot represent the frozen "-1" input.
        // That signed input is bound only to the public-value rejection test, not this renderer.
        val vectors = listOf("cap_zero", "cap_seventeen", "free_six", "initial_999", "initial_60001",
            "maximum_below_initial", "maximum_86400001", "timeout_999", "timeout_300001", "approval_zero", "approval_600001")
        vectors.forEach { vector ->
            val patch = fixtures.invalidNumeric(vector)
            val path = patch.getValue("path").jsonPrimitive.content
            val field = fields.getValue(path)
            replace(field, patch.getValue("value").jsonPrimitive.content)
            clickCheckedConsent(labels.prepareConsent)
            click(labels.prepare)
            assertNoMutation()
            val parts = path.split('.')
            replace(field, fixtures.input().getValue(parts[0]).jsonObject.getValue(parts[1]).jsonPrimitive.content)
        }
    }

    @Test fun epaC04ExhaustedPredecessorCannotCreateASuccessorEditor() {
        editor()
        replace(labels.employee, fixtures.employee.value)
        replace(labels.predecessor, "9223372036854775807")
        ui.onAllNodes(hasText(labels.prepare)).assertCountEquals(0)
        ui.onAllNodes(hasText(labels.successor)).assertCountEquals(0)
        assertNoMutation()
        // The UI has no editable successor field. Wrapped/overflow successor injection remains unbound.
    }

    @Test fun epaC05NegativeAmountCannotBeAddedAsAnAllowedGrant() {
        editor()
        identifyEmployee()
        choose(labels.action, "ORDER_CREATE", "DISCOUNT")
        replace(labels.amount, "-1")
        replace(labels.percentage, "1000")
        click(labels.addGrant)
        reviewValue(labels.grantCount, "0")
        assertNoMutation()
    }

    @Test fun epaC05OutOfRangePercentagesCannotBeAddedAsAllowedGrants() {
        editor()
        identifyEmployee()
        choose(labels.action, "ORDER_CREATE", "DISCOUNT")
        replace(labels.amount, "5000")
        listOf("-1", "10001").forEach {
            replace(labels.percentage, it)
            click(labels.addGrant)
            reviewValue(labels.grantCount, "0")
            assertNoMutation()
        }
    }

    @Test fun epaC06EmptyCompletePolicyDoesNotInheritGrantsOrApproval() = exactEditorProfile("empty_complete")
    @Test fun epaC06NullApprovalDoesNotInheritApproval() = exactEditorProfile("null_approval")
    @Test fun epaC06ZeroCeilingsStayVisibleZero() = exactEditorProfile("zero_ceilings")
    @Test fun epaC06ExplicitNoCeilingRetainsOnlyEGP() = exactEditorProfile("explicit_no_ceiling")

    @Test fun epaC06MissingMonetaryBoundIsRejectedByTheRenderedGrantEditor() {
        editor()
        identifyEmployee()
        choose(labels.action, "ORDER_CREATE", "CASH_OPEN")
        click(labels.addGrant)
        reviewValue(labels.grantCount, "0")
        assertNoMutation()
        // This is the editor's rejection slice. It is not a native conclusive-rejection result.
    }

    @Test fun epaC06ControlledUnresolvedMonetaryReviewNeverDisplaysInfinity() {
        review(name = "missing_monetary_limit")
        reviewValue(labels.action, "CASH_OPEN", grant = 2)
        reviewValue(labels.amountReview, labels.unspecified, grant = 2)
        reviewValue(labels.noCeilingReview, labels.notDeclared, grant = 2)
        assertNoMutation()
        // No monetary request or native activation is bound by this rendered DTO.
    }

    @Test fun epaC07PendingPreparationBlocksAnotherRenderedSubmission() {
        editor()
        controller.holdPreparation = true
        fillPolicy("replacement_policy")
        clickCheckedConsent(labels.prepareConsent)
        click(labels.prepare)
        assertEquals(1, controller.preparations.size)
        fixtures.assertPolicy(actual = controller.preparations.single().second)
        ui.onAllNodes(hasText(labels.prepare)).assertCountEquals(0)
        visible(labels.lookup).assertIsNotEnabled()
        ui.runOnIdle {
            controller.show(controller.state.value.copy(work = null))
        }
        click(labels.lookup)
        assertEquals(1, controller.preparations.size)
        assertEquals(0, controller.approvals)
        assertEquals(0, controller.activations)
        assertEquals(fixtures.originalRequest, controller.state.value.employeePolicy?.originalRequestId)
        assertEquals(null, controller.state.value.employeePolicy?.originalActionId)
        // The actual shared coordinator's one-owned-original/task bound remains unbound.
    }

    @Test fun epaC11InspectAndCancelCannotSignOrLeakTheTypedHeadSecret() {
        review()
        replace(labels.headSecret, fixtures.secret)
        click(labels.lookup)
        assertNoMutation()
        click(labels.back)
        assertEquals(1, controller.backCalls)
        assertEquals(1, controller.locks)
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        ui.onAllNodes(hasText(fixtures.secret)).assertCountEquals(0)
        assertNoMutation()
        // No caller CharArray is created by cancellation; that C20 obligation stays unbound here.
    }

    @Test fun epaC11ExplicitApprovalClearsCallerBufferAndDoesNotActivate() {
        review()
        val original = assertNotNull(controller.state.value.employeePolicy)
        replace(labels.headSecret, fixtures.secret)
        visible(labels.approve).assertIsNotEnabled()
        assertNoMutation()
        clickCheckedConsent(labels.approveConsent)
        click(labels.approve)
        assertEquals(1, controller.approvals)
        assertEquals(original.reviewId, controller.approvalReview)
        assertContentEquals(CharArray(16), assertNotNull(controller.transferredHeadBuffer))
        assertEquals(0, controller.activations)
        assertEquals(0, controller.preparations.size)
        assertEquals(LocalSetupEmployeePolicyStage.APPROVED, controller.state.value.employeePolicy?.stage)
        assertSame(original.requested, controller.state.value.employeePolicy?.requested)
        visible(labels.activate).assertIsNotEnabled()
        ui.onAllNodes(hasText(fixtures.secret)).assertCountEquals(0)
    }

    @Test fun epaC14UnresolvedApprovalAllowsOriginalInspectionWithoutSigningAgain() {
        review(stage = LocalSetupEmployeePolicyStage.APPROVAL_UNRESOLVED)
        click(labels.lookup)
        assertEquals(1, controller.lookups)
        assertEquals(fixtures.originalRequest, controller.state.value.employeePolicy?.originalRequestId)
        assertEquals(fixtures.originalAction, controller.state.value.employeePolicy?.originalActionId)
        ui.onAllNodes(hasText(labels.approve)).assertCountEquals(0)
        ui.onAllNodes(hasText(labels.activate)).assertCountEquals(0)
        assertNoMutation()
        // Native signing, lost response and approval-attempt identity are not simulated here.
    }

    @Test fun epaC15ActivationNeedsItsOwnConfirmationAndKeepsTheApprovedDefinition() {
        review(stage = LocalSetupEmployeePolicyStage.APPROVED)
        val original = assertNotNull(controller.state.value.employeePolicy)
        visible(labels.activate).assertIsNotEnabled()
        assertNoMutation()
        clickCheckedConsent(labels.activateConsent)
        click(labels.activate)
        assertEquals(1, controller.activations)
        assertEquals(original.reviewId, controller.activationReview)
        assertSame(original.requested, controller.activationDefinition)
        fixtures.assertPolicy(actual = assertNotNull(controller.activationDefinition).nextPolicy)
        assertEquals(0, controller.approvals)
        assertEquals(0, controller.preparations.size)
    }

    @Test fun epaC17UnresolvedActivationOnlyInspectsItsExistingOriginal() {
        review(stage = LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED)
        click(labels.lookup)
        click(labels.lookup)
        assertEquals(2, controller.lookups)
        assertEquals(fixtures.originalRequest, controller.state.value.employeePolicy?.originalRequestId)
        ui.onAllNodes(hasText(labels.activate)).assertCountEquals(0)
        assertNoMutation()
    }

    @Test fun epaC21LockHidesTheProtectedPolicyAndHeadEditor() {
        review()
        replace(labels.headSecret, fixtures.secret)
        click(labels.lock)
        ui.onAllNodes(hasText(fixtures.employee.value)).assertCountEquals(0)
        ui.onAllNodes(hasText(fixtures.originalRequest.value)).assertCountEquals(0)
        ui.onAllNodes(hasText(labels.headSecret)).assertCountEquals(0)
        ui.onAllNodes(hasText(fixtures.secret)).assertCountEquals(0)
        assertEquals(1, controller.locks)
        assertNoMutation()
        // Deliberately stale controlled DATA is still in the driver. The public screen suppresses it.
        assertNotNull(controller.state.value.employeePolicy)
    }

    @Test fun epaC23ReplacementOwnerViewDoesNotAdoptThePriorHeadInputOrPolicy() {
        review()
        replace(labels.headSecret, fixtures.secret)
        ui.runOnIdle {
            controller.show(LocalSetupView(phase = LocalSetupPhase.EMPLOYEE_POLICY, localClientConnected = true,
                employeePolicies = LocalSetupEmployeePolicies(newDefinitionAvailable = true)))
        }
        ui.onAllNodes(hasText(fixtures.employee.value)).assertCountEquals(0)
        ui.onAllNodes(hasText(fixtures.originalRequest.value)).assertCountEquals(0)
        ui.onAllNodes(hasText(fixtures.secret)).assertCountEquals(0)
        ui.onAllNodes(hasText(labels.approve)).assertCountEquals(0)
        assertNoMutation()
        // Controlled view replacement only; genuine native/session retirement is not bound.
    }

    private fun completeEditorAndReview(language: AppLanguage) {
        editor(language)
        fillPolicy("replacement_policy")
        val selectedReview = assertNotNull(controller.state.value.employeePolicy).reviewId
        clickCheckedConsent(labels.prepareConsent)
        click(labels.prepare)
        assertEquals(1, controller.preparations.size)
        assertEquals(selectedReview, controller.preparations.single().first)
        fixtures.assertPolicy(actual = controller.preparations.single().second)
        assertEquals(0, controller.approvals)
        assertEquals(0, controller.activations)
        assertRenderedReview("replacement_policy")
        // Frozen C01 requires readable EGP 50.00 and 10.00%, not merely raw storage units.
        reviewValue(labels.amountHumanReview, humanText { it == "EGP50.00" || it == "50.00EGP" }, grant = 2)
        reviewValue(labels.percentageHumanReview, humanText { it == "10.00%" }, grant = 2)
    }

    private fun exactEditorProfile(name: String) {
        editor()
        controller.replyProfile = name
        fillPolicy(name)
        val selectedReview = assertNotNull(controller.state.value.employeePolicy).reviewId
        clickCheckedConsent(labels.prepareConsent)
        click(labels.prepare)
        assertEquals(1, controller.preparations.size)
        assertEquals(selectedReview, controller.preparations.single().first)
        fixtures.assertPolicy(name, controller.preparations.single().second)
        assertRenderedReview(name)
        assertEquals(0, controller.approvals)
        assertEquals(0, controller.activations)
    }

    private fun identifyEmployee() {
        replace(labels.employee, fixtures.employee.value)
        replace(labels.predecessor, "7")
    }

    private fun fillPolicy(name: String) {
        identifyEmployee()
        val value = fixtures.input(name)
        var currentAction = "ORDER_CREATE"
        var currentVerb = "PERFORM"
        value.getValue("grants").jsonArray.forEach { item ->
            val grant = item.jsonObject
            val permission = grant.getValue("permission").jsonObject
            val action = permission.getValue("action").jsonPrimitive.content
            val verb = permission.getValue("verb").jsonPrimitive.content
            choose(labels.action, currentAction, action); currentAction = action
            choose(labels.verb, currentVerb, verb); currentVerb = verb
            val limits = grant.getValue("limits").jsonObject
            replace(labels.amount, limits.getValue("maximumAmount").takeUnless { it == JsonNull }
                ?.jsonObject?.getValue("minor")?.jsonPrimitive?.content.orEmpty())
            replace(labels.percentage, limits.getValue("maximumPercentageBasisPoints").takeUnless { it == JsonNull }
                ?.jsonPrimitive?.content.orEmpty())
            setConsent(labels.noCeiling, limits.getValue("noMonetaryCeiling") != JsonNull)
            replace(labels.targets, grant.getValue("targetIds").takeUnless { it == JsonNull }
                ?.jsonArray?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty())
            click(labels.addGrant)
        }
        replace(labels.sessions, value.getValue("sessionPolicy").jsonObject.getValue("maximumConcurrentSessions").jsonPrimitive.content)
        val throttle = value.getValue("throttlePolicy").jsonObject
        replace(labels.free, throttle.getValue("freeFailures").jsonPrimitive.content)
        replace(labels.initial, throttle.getValue("initialDelayMillis").jsonPrimitive.content)
        replace(labels.maximum, throttle.getValue("maximumDelayMillis").jsonPrimitive.content)
        replace(labels.timeout, throttle.getValue("attemptTimeoutMillis").jsonPrimitive.content)
        val approval = value.getValue("approvalPolicy")
        if (approval != JsonNull) {
            clickCheckedConsent(labels.approvalEnabled)
            replace(labels.approvalMillis, approval.jsonObject.getValue("validForMillis").jsonPrimitive.content)
            replace(labels.selfActions, approval.jsonObject.getValue("namedSelfApprovalActions").jsonArray
                .joinToString("\n") { it.jsonPrimitive.content })
        }
    }

    private fun assertRenderedReview(name: String) {
        val value = fixtures.input(name)
        reviewValue(labels.originalRequest, fixtures.originalRequest.value)
        reviewValue(labels.policyEmployee, fixtures.employee.value)
        reviewValue(labels.branchScope, fixtures.scope.branchId.value)
        reviewValue(labels.predecessorReview, "7")
        reviewValue(labels.successor, value.getValue("revision").jsonPrimitive.content)
        reviewValue(labels.grantCount, value.getValue("grants").jsonArray.size.toString())
        value.getValue("grants").jsonArray.forEachIndexed { index, item ->
            val grant = item.jsonObject
            val number = index + 1
            reviewValue(labels.action, grant.getValue("permission").jsonObject.getValue("action").jsonPrimitive.content, number)
            reviewValue(labels.verb, grant.getValue("permission").jsonObject.getValue("verb").jsonPrimitive.content, number)
            reviewValue(labels.effectReview, grant.getValue("effect").jsonPrimitive.content, number)
            reviewValue(labels.ownerAccessReview, grant.getValue("ownerAccess").jsonPrimitive.content, number)
            reviewValue(labels.subjectReview, grant.getValue("namedSubjectId").jsonPrimitive.content, number)
            reviewValue(labels.targetsReview, grant.getValue("targetIds").takeUnless { it == JsonNull }?.jsonArray
                ?.joinToString("\n") { it.jsonPrimitive.content } ?: labels.unspecifiedTargets, number)
            val limits = grant.getValue("limits").jsonObject
            val amount = limits.getValue("maximumAmount")
            reviewValue(labels.amountReview, if (amount == JsonNull) labels.unspecified
                else amount.jsonObject.getValue("minor").jsonPrimitive.content + " " +
                    amount.jsonObject.getValue("currency").jsonPrimitive.content, number)
            reviewValue(labels.percentageReview, limits.getValue("maximumPercentageBasisPoints").takeUnless { it == JsonNull }
                ?.jsonPrimitive?.content ?: labels.unspecifiedPercentage, number)
            val noCeiling = limits.getValue("noMonetaryCeiling")
            reviewValue(labels.noCeilingReview, if (noCeiling == JsonNull) labels.notDeclared else noCeiling.jsonPrimitive.content, number)
            reviewValue(labels.delegationReview, labels.nullDelegation, number)
        }
        val throttle = value.getValue("throttlePolicy").jsonObject
        reviewValue(labels.sessionsReview, value.getValue("sessionPolicy").jsonObject.getValue("maximumConcurrentSessions").jsonPrimitive.content)
        mapOf("freeFailures" to labels.freeReview, "initialDelayMillis" to labels.initialReview,
            "maximumDelayMillis" to labels.maximumReview, "attemptTimeoutMillis" to labels.timeoutReview).forEach { (field, label) ->
            reviewValue(label, throttle.getValue(field).jsonPrimitive.content)
        }
        val approval = value.getValue("approvalPolicy")
        if (approval == JsonNull) reviewValue(labels.approvalReview, labels.absentApproval)
        else {
            reviewValue(labels.approvalMillisReview, approval.jsonObject.getValue("validForMillis").jsonPrimitive.content)
            val actions = approval.jsonObject.getValue("namedSelfApprovalActions").jsonArray
            reviewValue(labels.selfActionsReview, if (actions.isEmpty()) labels.noSelfApproval
                else actions.joinToString("\n") { it.jsonPrimitive.content })
        }
        // This binds the displayed count and expected indexed fields, not a traversal/count of
        // all lazy grant rows. Rendered extra/stale-row and no-inheritance obligations stay unbound.
    }

    private fun editor(language: AppLanguage = AppLanguage.ENGLISH) {
        show(LocalSetupView(phase = LocalSetupPhase.EMPLOYEE_POLICY, localClientConnected = true,
            employeePolicy = fixtures.editor()), language)
    }

    private fun review(stage: LocalSetupEmployeePolicyStage = LocalSetupEmployeePolicyStage.REVIEW,
        name: String = "replacement_policy") {
        show(LocalSetupView(phase = LocalSetupPhase.EMPLOYEE_POLICY, localClientConnected = true,
            employeePolicy = fixtures.review(stage, name)), AppLanguage.ENGLISH)
    }

    private fun show(view: LocalSetupView, language: AppLanguage) {
        labels = Labels(language)
        controller = ControlledPolicyView(view)
        val selectedLanguage = mutableStateOf(language)
        ui.setContent {
            LocalSetupScreen(controller, selectedLanguage.value, { selectedLanguage.value = it },
                onBack = { controller.backCalls++ })
        }
    }

    private fun visible(text: String): SemanticsNodeInteraction = visible(hasText(text))
    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return ui.onAllNodes(matcher).onFirst().assertIsDisplayed()
    }

    private fun reviewValue(label: String, expected: String, grant: Int? = null): SemanticsNodeInteraction =
        reviewValue(label, hasText(expected), grant)

    private fun reviewValue(label: String, expected: SemanticsMatcher, grant: Int? = null): SemanticsNodeInteraction {
        val matcher = expected and SemanticsMatcher("value paired with '$label' in " + (grant?.let { "grant $it" } ?: "policy review")) { value ->
            val nodes = semanticsTree(value).toList()
            val headings = nodes.filter { labels.grantHeadingMatcher.matches(it) }
            val containers = headings.mapNotNull { it.layoutInfo.parentInfo?.parentInfo }
            val matchingLabels = nodes.filter { candidate ->
                hasText(label).matches(candidate) && if (grant == null) {
                    containers.none { containsLayout(it, candidate) }
                } else {
                    headings.any { heading ->
                        hasText(labels.grantHeading(grant)).matches(heading) &&
                            heading.layoutInfo.parentInfo?.parentInfo?.let { containsLayout(it, candidate) } == true
                    }
                }
            }
            // Scope labels occur in selected-original and retained-policy blocks; the last
            // non-grant occurrence is the policy block. Every scalar/count label is unique.
            val fieldLabels = if (grant == null) listOfNotNull(matchingLabels.lastOrNull()) else matchingLabels
            val parent = value.layoutInfo.parentInfo
            parent != null && fieldLabels.any { fieldLabel ->
                fieldLabel.id != value.id && fieldLabel.layoutInfo.parentInfo === parent
            }
        }
        ui.onNode(hasScrollToNodeAction(), useUnmergedTree = true).performScrollToNode(matcher)
        return ui.onNode(matcher, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun semanticsTree(node: SemanticsNode): Sequence<SemanticsNode> {
        var root = node
        while (true) root = root.parent ?: break
        return semanticsDescendants(root)
    }

    private fun semanticsDescendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(semanticsDescendants(it)) }
    }

    private fun containsLayout(container: LayoutInfo, node: SemanticsNode): Boolean {
        var layout: LayoutInfo? = node.layoutInfo
        while (layout != null) {
            if (layout === container) return true
            layout = layout.parentInfo
        }
        return false
    }
    private fun click(text: String) {
        visible(hasText(text) and hasClickAction()).performClick()
    }
    private fun replace(label: String, value: String) {
        visible(hasText(label) and hasSetTextAction()).performTextReplacement(value)
    }
    private fun choose(label: String, current: String, requested: String) {
        if (current == requested) return
        click("$label: $current")
        ui.onNode(hasText(requested) and hasClickAction()).performScrollTo().performClick()
    }
    private fun clickCheckedConsent(label: String) = setConsent(label, true)
    private fun setConsent(label: String, checked: Boolean) {
        val node = visible(hasText(label) and hasClickAction())
        val current = node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.ToggleableState) {
            error("Missing toggle state for frozen-case selector")
        }
        if ((current == androidx.compose.ui.state.ToggleableState.On) != checked) node.performClick()
    }
    private fun assertNoMutation() {
        assertEquals(0, controller.preparations.size)
        assertEquals(0, controller.approvals)
        assertEquals(0, controller.activations)
        assertEquals(0, controller.otherMutations)
    }
    private fun humanText(accepts: (String) -> Boolean): SemanticsMatcher = SemanticsMatcher("frozen readable numeric value") { node ->
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text ->
            val normalized = text.text.map { char ->
                when (char) {
                    in '٠'..'٩' -> '0' + (char - '٠')
                    '٫' -> '.'
                    '٪' -> '%'
                    else -> char
                }
            }.joinToString("").replace("ج.م", "EGP").filterNot {
                it.isWhitespace() || it in '\u2066'..'\u2069' || it == '\u200e' || it == '\u200f'
            }
            accepts(normalized)
        }
    }

    private class ControlledPolicyView(initial: LocalSetupView) : LocalSetupController {
        private val published = MutableStateFlow(initial)
        override val state = published.asStateFlow()
        val preparations = mutableListOf<Pair<DomainId, ActivatedLocalPolicy>>()
        var approvals = 0
        var activations = 0
        var lookups = 0
        var locks = 0
        var backCalls = 0
        var otherMutations = 0
        var holdPreparation = false
        var replyProfile = "replacement_policy"
        var approvalReview: DomainId? = null
        var activationReview: DomainId? = null
        var activationDefinition: LocalEmployeePolicyActivationDefinition? = null
        var transferredHeadBuffer: CharArray? = null
        fun show(view: LocalSetupView) { published.value = view }
        override fun prepareEmployeePolicy(reviewId: DomainId, nextPolicy: ActivatedLocalPolicy) {
            preparations += reviewId to nextPolicy
            val previous = assertNotNull(state.value.employeePolicy)
            if (holdPreparation) show(state.value.copy(work = LocalSetupWork.PREPARING_EMPLOYEE_POLICY,
                employeePolicy = previous.copy(stage = LocalSetupEmployeePolicyStage.PREPARATION_UNRESOLVED,
                    actorId = EmployeePolicyAuthoringFixtures.employee, submittedPolicy = EmployeePolicyAuthoringFixtures.policy())))
            else show(state.value.copy(employeePolicy = EmployeePolicyAuthoringFixtures.review(name = replyProfile)))
        }
        override fun approveEmployeePolicy(reviewId: DomainId, headSecret: CharArray) {
            assertContentEquals(EmployeePolicyAuthoringFixtures.secret.toCharArray(), headSecret)
            approvals++
            approvalReview = reviewId
            transferredHeadBuffer = headSecret // The UI must clear this same caller buffer on return.
            val selected = assertNotNull(state.value.employeePolicy)
            show(state.value.copy(employeePolicy = selected.copy(reviewId = DomainId.random(),
                stage = LocalSetupEmployeePolicyStage.APPROVED, nativePhase = LocalEmployeePolicyActivationPhase.APPROVED)))
        }
        override fun activateApprovedEmployeePolicy(reviewId: DomainId) {
            activations++
            activationReview = reviewId
            val selected = assertNotNull(state.value.employeePolicy)
            activationDefinition = selected.requested
            show(state.value.copy(employeePolicy = selected.copy(stage = LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED)))
        }
        override fun lookupEmployeePolicy(reviewId: DomainId) {
            assertEquals(state.value.employeePolicy?.reviewId, reviewId)
            lookups++
        }
        override fun lockPresentation() { locks++; show(state.value.copy(locked = true)) }
        override fun resumePresentation() = Unit
        override fun refreshOriginalStatus() = Unit
        private fun unexpected() { otherMutations++ }
        override fun chooseNewLocalCompany() = unexpected()
        override fun inspectOriginalCompany() = unexpected()
        override fun resumeOriginalCompany() = unexpected()
        override fun inspectOriginalInstallation() = unexpected()
        override fun prepareNewLocalCompany(input: LocalSetupNewCompanyInput) = unexpected()
        override fun completeNewLocalCompany(reviewId: DomainId, installerSecret: CharArray, headSecret: CharArray) {
            installerSecret.fill('\u0000'); headSecret.fill('\u0000'); unexpected()
        }
        override fun prepareOriginalBranch(branchName: String) = unexpected()
        override fun issueOriginalBranch(reviewId: DomainId, installerSecret: CharArray) {
            installerSecret.fill('\u0000'); unexpected()
        }
        override fun prepareOriginalNativeTarget() = unexpected()
        override fun openOriginalFirstAccount() = unexpected()
        override fun prepareFirstAccount(input: LocalSetupFirstAccountInput) = unexpected()
        override fun installFirstAccount(reviewId: DomainId, headSecret: CharArray, employeePassword: CharArray) {
            headSecret.fill('\u0000'); employeePassword.fill('\u0000'); unexpected()
        }
        override fun resumeOriginalInstallationAcknowledgement() = unexpected()
    }

    /** Exact text selectors are wiring, not translated wording oracles. */
    private class Labels(language: AppLanguage) {
        private val arabic = language == AppLanguage.ARABIC
        private fun text(en: String, ar: String) = if (arabic) ar else en
        val employee = text("Exact existing employee UUID", "معرّف UUID الدقيق للموظف الموجود")
        val originalRequest = text("Original request", "الطلب الأصلي")
        val policyEmployee = text("Policy employee ID", "معرّف موظف السياسة")
        val branchScope = text("Branch scope", "نطاق الفرع")
        val predecessor = text("Expected existing policy revision", "مراجعة السياسة الموجودة المتوقعة")
        val predecessorReview = text("Expected predecessor revision", "مراجعة السياسة السابقة المتوقعة")
        val successor = text("Requested replacement revision", "مراجعة السياسة البديلة المطلوبة")
        val action = text("Action code", "رمز الإجراء")
        val verb = text("Verb", "نوع الإذن")
        val amount = text("Maximum EGP amount in piastres (integer)", "أقصى مبلغ بالجنيه بوحدة القرش (عدد صحيح)")
        val percentage = text("Maximum percentage in basis points (0–10000)", "أقصى نسبة بنقاط الأساس (0–10000)")
        val targets = text("Exact target UUIDs, one per line; empty means no target restriction", "معرّفات الأهداف الدقيقة، واحد في كل سطر؛ فارغ يعني عدم تقييد الأهداف")
        val noCeiling = text("Explicitly no monetary ceiling, in EGP only (amount must be empty)", "عدم وجود حد نقدي صراحة بالجنيه فقط (يجب ترك المبلغ فارغًا)")
        val addGrant = text("Add this grant to the review", "إضافة هذه الصلاحية إلى المراجعة")
        val grantCount = text("Complete grant count", "عدد الصلاحيات الكامل")
        val sessions = text("Maximum concurrent sessions (1–16)", "أقصى جلسات متزامنة (1–16)")
        val free = text("Free failed attempts (0–5)", "المحاولات الفاشلة قبل التأخير (0–5)")
        val initial = text("Initial delay in ms (1000–60000)", "التأخير الأولي بالمللي ثانية (1000–60000)")
        val maximum = text("Maximum delay in ms (initial delay–86400000)", "أقصى تأخير بالمللي ثانية (التأخير الأولي–86400000)")
        val timeout = text("Attempt timeout in ms (1000–300000)", "مهلة المحاولة بالمللي ثانية (1000–300000)")
        val approvalEnabled = text("Include an explicit manager-approval policy", "تضمين سياسة صريحة لموافقة المدير")
        val approvalMillis = text("Approval lifetime in ms (1–600000)", "مدة الموافقة بالمللي ثانية (1–600000)")
        val selfActions = text("Exact self-approval action codes, one per line; empty means none", "رموز إجراءات الموافقة الذاتية الدقيقة، واحد في كل سطر؛ فارغ يعني لا شيء")
        val prepareConsent = text("Prepare exactly this complete replacement, including every grant and explicit empty selection", "تحضير هذه السياسة البديلة كاملةً بعينها، بجميع الصلاحيات والاختيارات الفارغة الصريحة")
        val prepare = text("Prepare and review this COMPLETE replacement", "تحضير هذه السياسة البديلة الكاملة ومراجعتها")
        val lookup = text("Inspect / look up only this original", "فحص / الاستعلام عن هذا الأصل فقط")
        val headSecret = text("Existing named company-head password", "كلمة مرور رئيس الشركة المسمّى الحالية")
        val approveConsent = text("I reviewed the exact employee, both revisions, every grant, limit, session, throttle and approval field; approve only this complete original without activation", "راجعت الموظف الدقيق والمراجعتين وجميع الصلاحيات والحدود وحقول الجلسة والتأخير والموافقة؛ اعتمد هذا الأصل الكامل فقط دون تفعيل")
        val approve = text("Approve this original policy only", "اعتماد هذه السياسة الأصلية فقط")
        val activateConsent = text("Activate the unchanged complete policy shown above for this exact employee and expected revision", "تفعيل السياسة الكاملة دون تغيير كما تظهر أعلاه لهذا الموظف الدقيق والمراجعة المتوقعة")
        val activate = text("Activate this approved original", "تفعيل هذا الأصل المعتمد")
        val back = text("Device choices", "خيارات الجهاز")
        val lock = text("Lock setup", "قفل الإعداد")
        val nullDelegation = text("No ceiling record supplied", "لم يُقدّم سجل حدود")
        val delegationReview = text("Delegation ceiling", "حدود التفويض")
        val effectReview = text("Effect", "الأثر")
        val ownerAccessReview = text("Owner access", "وصول المالك")
        val subjectReview = text("Named subject", "الشخص المسمّى")
        val targetsReview = text("Exact target IDs", "معرّفات الأهداف الدقيقة")
        val unspecifiedTargets = text("Unspecified", "غير محددة")
        val amountReview = text("Maximum amount (minor units)", "أقصى مبلغ بوحداته الصغرى")
        val amountHumanReview = text("Maximum amount", "أقصى مبلغ")
        val percentageReview = text("Maximum percentage (basis points)", "أقصى نسبة بنقاط الأساس")
        val percentageHumanReview = text("Maximum percentage", "أقصى نسبة")
        val unspecifiedPercentage = text("Unspecified", "غير محددة")
        val noCeilingReview = text("Explicit no monetary ceiling (currency)", "عدم وجود حد نقدي صريح مع العملة")
        val sessionsReview = text("Concurrent sessions", "الجلسات المتزامنة")
        val freeReview = text("Free authentication failures", "إخفاقات الدخول قبل التأخير")
        val initialReview = text("Initial delay (ms)", "التأخير الأولي بالمللي ثانية")
        val maximumReview = text("Maximum delay (ms)", "أقصى تأخير بالمللي ثانية")
        val timeoutReview = text("Attempt timeout (ms)", "مهلة المحاولة بالمللي ثانية")
        val approvalReview = text("Manager approval policy", "سياسة موافقة المدير")
        val approvalMillisReview = text("Approval validity (ms)", "صلاحية الموافقة بالمللي ثانية")
        val selfActionsReview = text("Named self-approval actions", "إجراءات الموافقة الذاتية المسمّاة")
        fun grantHeading(number: Int) = text("Grant", "الصلاحية") + " " + number
        val grantHeadingMatcher = SemanticsMatcher("numbered grant heading") { node ->
            node.config.contains(SemanticsProperties.Heading) && node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { it.text.matches(Regex(Regex.escape(text("Grant", "الصلاحية")) + " [0-9]+")) }
        }
        val unspecified = text("Unspecified", "غير محدد")
        val notDeclared = text("Not declared", "غير معلن")
        val absentApproval = text("Absent", "غير موجودة")
        val noSelfApproval = text("None", "لا شيء")
    }
}
