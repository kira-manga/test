package com.ukkera.pos.security.independent.qualifiedarrivalmatchingv1

import com.ukkera.pos.security.*
import com.ukkera.pos.security.PurchasingArrivalRuleConstraintResult.MISMATCH
import com.ukkera.pos.security.PurchasingArrivalRuleConstraintResult.NO_MISMATCH
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotSame

/** Wires frozen QAM outcomes only; these shared slices do not execute a protected arrival. */
class QualifiedArrivalMatchingV1FrozenUnitSlicesTest {
    private fun compare(label: String, input: QamInvocation,
        expected: PurchasingArrivalRuleConstraintResult = NO_MISMATCH) {
        val before = input.observation()
        assertEquals(expected, input.compare(), label)
        assertEquals(before, input.observation(), "$label: input/proof provisioning changed")
    }

    private fun changedProduct(input: QamInvocation, index: Int,
        change: (PurchasingSkuReferenceV1) -> PurchasingSkuReferenceV1): QamInvocation =
        input.copy(products = input.products.mapIndexed { currentIndex, product -> if (currentIndex == index) change(product) else product })

    @Test
    fun qam01ExplicitRuleMustExistAndMatchTheProvidedCompleteBody() = runTest {
        val input = QamInputs.baseline()
        compare("QAM-01 explicitly chosen Direct beside other eligible rules", input)
        val selection = assertNotNull(input.selection)
        val rule = assertNotNull(input.rule)
        val variants = listOf(
            "missing selection" to input.copy(selection = null),
            "missing selected rule ID" to input.copy(selection = selection.copy(ruleId = null)),
            "unknown selected rule ID" to input.copy(selection = selection.copy(ruleId = QamInputs.unknownRuleId)),
            "provided body has another selected ID" to input.copy(rule = QamInputs.rule(QamRoute.SEPARATE)),
            "explicit choice differs from provided body" to input.copy(selection = selection.copy(ruleId = QamInputs.otherRuleId)),
            "missing complete body" to input.copy(rule = null),
            "choice absent despite other eligible rules" to input.copy(policy = QamInputs.policyLike(input.policy,
                rules = input.policy.rules.filter { it.ruleId != rule.ruleId })),
            "empty publication rules" to input.copy(policy = QamInputs.policyLike(input.policy, rules = emptyList())),
            "body evidence differs from original selected body" to input.copy(rule = QamInputs.ruleLike(rule,
                evidence = QamInputs.evidence(QamRoute.SEPARATE))),
        )
        variants.forEach { (label, variant) -> compare("QAM-01 $label", variant, MISMATCH) }
        compare("QAM-01 independently materialized equal complete body", input.copy(rule = QamInputs.rule(QamRoute.DIRECT)))
    }

    @Test
    fun qam01PolicyPublicationBranchAndBusinessActionAreExact() = runTest {
        val input = QamInputs.baseline()
        val selection = assertNotNull(input.selection)
        listOf(
            "policy ID" to input.copy(policy = QamInputs.policyLike(input.policy, policyId = QamInputs.id(130))),
            "publication ID" to input.copy(policy = QamInputs.policyLike(input.policy, publicationId = QamInputs.id(131))),
            "publication revision" to input.copy(policy = QamInputs.policyLike(input.policy, revision = Revision(7))),
            "policy branch" to input.copy(policy = QamInputs.policyLike(input.policy, branch = QamInputs.otherBranchScope)),
            "expected branch" to input.copy(selection = selection.copy(scope = QamInputs.otherBranchScope)),
            "another cost-free action" to input.copy(action = PurchasingRuleActionV1.ARRIVAL_RECORD),
            "supplier invoice action" to input.copy(action = PurchasingRuleActionV1.INVOICE_RECORD),
        ).forEach { (label, variant) -> compare("QAM-01 different $label", variant, MISMATCH) }
    }

    @Test
    fun qam01SupplierIdAndRevisionMustMatchTheOneChosenRule() = runTest {
        val input = QamInputs.baseline()
        compare("QAM-01 supplier ID", input.copy(supplier = QamInputs.supplier(QamInputs.otherSupplierId)), MISMATCH)
        compare("QAM-01 supplier revision", input.copy(supplier = QamInputs.supplier(revision = Revision(2))), MISMATCH)
        val rule = assertNotNull(input.rule)
        compare("QAM-01 eligible other rule cannot replace excluded supplier",
            input.replacingRule(QamInputs.ruleLike(rule, suppliers = listOf(QamInputs.supplier(QamInputs.otherSupplierId)))), MISMATCH)
    }

    @Test
    fun qam01BothProductsAndEverySuppliedProductCatalogCoordinateMustMatch() = runTest {
        val input = QamInputs.baseline()
        for (index in input.products.indices) {
            val changes: List<Pair<String, (PurchasingSkuReferenceV1) -> PurchasingSkuReferenceV1>> = listOf(
                "excluded product ID" to { QamInputs.productLike(it, productId = QamInputs.excludedProductId) },
                "product revision" to { QamInputs.productLike(it, productRevision = Revision(2)) },
                "SKU ID" to { QamInputs.productLike(it, skuId = QamInputs.otherSkuId) },
                "SKU revision" to { QamInputs.productLike(it, skuRevision = Revision(2)) },
                "catalog release ID" to { QamInputs.productLike(it, catalog = QamInputs.catalog(QamInputs.otherCatalogReleaseId)) },
                "catalog release revision" to { QamInputs.productLike(it, catalog = QamInputs.catalog(revision = Revision(6))) },
            )
            changes.forEach { (label, change) -> compare("QAM-01 product $index $label", changedProduct(input, index, change), MISMATCH) }
            val rule = assertNotNull(input.rule)
            compare("QAM-01 selected rule excludes product $index despite eligible neighbor", input.replacingRule(
                QamInputs.ruleLike(rule, products = PurchasingProductSelectionV1(input.products.filterIndexed { i, _ -> i != index }, emptyList()))), MISMATCH)
        }
        compare("QAM-01 required catalog absent", input.copy(expectedCatalog = null), MISMATCH)
        compare("QAM-01 required catalog release ID", input.copy(expectedCatalog = QamInputs.catalog(QamInputs.otherCatalogReleaseId)), MISMATCH)
        compare("QAM-01 required catalog release revision", input.copy(expectedCatalog = QamInputs.catalog(revision = Revision(6))), MISMATCH)
    }

    @Test
    fun qam01CategoryMembershipCannotBeInferredFromProducts() = runTest {
        val input = QamInputs.baseline()
        val categoryOnly = PurchasingProductSelectionV1(emptyList(), listOf(
            PurchasingCategoryReferenceV1(QamInputs.categoryId, Revision(1), QamInputs.catalog())))
        compare("QAM-01 category-only rule has no independently supplied membership",
            input.replacingRule(QamInputs.ruleLike(assertNotNull(input.rule), products = categoryOnly)), MISMATCH)
    }

    @Test
    fun qam01SupplierProductAndEvidencePermissionsCannotBeMergedAcrossRules() = runTest {
        val input = QamInputs.baseline()
        val rule = assertNotNull(input.rule)
        val supplierOnly = QamInputs.ruleLike(rule,
            products = PurchasingProductSelectionV1(listOf(input.products[0]), emptyList()))
        val productOnly = QamInputs.rule(QamRoute.DIRECT, QamInputs.otherRuleId,
            suppliers = listOf(QamInputs.supplier(QamInputs.otherSupplierId)))
        val policy = QamInputs.policyLike(input.policy, rules = listOf(supplierOnly, productOnly))
        compare("QAM-01 supplier rule cannot borrow second product", input.copy(policy = policy, rule = supplierOnly), MISMATCH)
        compare("QAM-01 combined body is not either original rule", input.copy(policy = policy, rule = rule), MISMATCH)
    }

    @Test
    fun qam02DirectNullProvenanceSurvivesSeparateProofProvisioning() = runTest {
        val direct = QamInputs.baseline()
        val before = QamProofObservation(direct.authorization)
        compare("QAM-02 Direct before approval provisioning", direct)
        assertNull(direct.authorization.approvalId)
        assertNull(direct.authorization.approverId)
        assertNull(direct.authorization.approverPolicyRevision)
        assertEquals(0, direct.provision.counts.issues)
        assertEquals(0, direct.provision.counts.consumptions)
        val separate = QamInputs.baseline(QamRoute.SEPARATE)
        compare("QAM-02 Separate after Direct", separate)
        val second = QamProofs.provision(QamRoute.SEPARATE, separate.operation.requirement(), approvalId = QamInputs.otherApprovalId)
        compare("QAM-02 second actual Separate proof", separate.copy(authorization = second.authorization, provision = second))
        compare("QAM-02 Direct after two approval outputs", direct)
        assertEquals(before, QamProofObservation(direct.authorization))
        assertEquals(QamInputs.publicPermission, direct.authorization.requirement.permission)
        assertEquals(QamInputs.requester, direct.authorization.actorId)
        assertEquals(QamInputs.requesterPolicyRevision, direct.authorization.policyRevision)
    }

    @Test
    fun qam02SeparateUsesTheActualRequesterApproverAndEmployeePolicyRevisions() = runTest {
        val input = QamInputs.baseline(QamRoute.SEPARATE)
        compare("QAM-02 actual Separate R/A", input)
        assertEquals(QamInputs.requester, input.authorization.actorId)
        assertEquals(QamInputs.approver, input.authorization.approverId)
        assertEquals(QamInputs.requesterPolicyRevision, input.authorization.policyRevision)
        assertEquals(QamInputs.approverPolicyRevision, input.authorization.approverPolicyRevision)
        assertEquals(QamInputs.separateApprovalId, input.authorization.approvalId)
        assertEquals(1, input.provision.counts.issues)
        assertEquals(1, input.provision.counts.consumptions)
        assertEquals(QamInputs.operationId, assertNotNull(input.provision.consumed).consumedByOperationId)
        assertNotSame(input.requirement, input.authorization.requirement)
        assertEquals(input.operation.requirement(), input.authorization.requirement)
    }

    @Test
    fun qam02NamedSelfAdmitsBothExplicitEqualityAndDistinctAuthorizedActors() = runTest {
        val input = QamInputs.baseline(QamRoute.NAMED_SELF)
        compare("QAM-02 named actual R/R", input)
        assertEquals(QamInputs.requester, input.authorization.actorId)
        assertEquals(QamInputs.requester, input.authorization.approverId)
        assertEquals(QamInputs.requesterPolicyRevision, input.authorization.approverPolicyRevision)
        assertEquals(QamInputs.selfApprovalId, input.authorization.approvalId)
        val distinct = QamProofs.provision(QamRoute.SEPARATE, input.operation.requirement())
        compare("QAM-02 NamedSelf actual distinct R/A", input.copy(authorization = distinct.authorization, provision = distinct))
        assertEquals(QamInputs.requester, distinct.authorization.actorId)
        assertEquals(QamInputs.approver, distinct.authorization.approverId)
    }

    @Test
    fun qam02DirectRejectsActualApprovalCoordinatesAndSeparateRejectsTheirAbsenceOrEquality() = runTest {
        val direct = QamInputs.baseline()
        val approvedDirectInput = QamProofs.provision(QamRoute.SEPARATE, direct.operation.requirement())
        compare("QAM-02 Direct rejects all present public approval coordinates",
            direct.copy(authorization = approvedDirectInput.authorization, provision = approvedDirectInput), MISMATCH)
        val separate = QamInputs.baseline(QamRoute.SEPARATE)
        val directSeparateInput = QamProofs.provision(QamRoute.DIRECT, separate.operation.requirement())
        compare("QAM-02 Separate rejects absent public approval tuple",
            separate.copy(authorization = directSeparateInput.authorization, provision = directSeparateInput), MISMATCH)
        val selfSeparateInput = QamProofs.provision(QamRoute.NAMED_SELF, separate.operation.requirement())
        compare("QAM-02 Separate rejects actual R/R despite public approval output",
            separate.copy(authorization = selfSeparateInput.authorization, provision = selfSeparateInput), MISMATCH)
    }

    @Test
    fun qam02EqualityExceptionMustNameTheActualAccountButDoesNotExcludeDistinctActors() = runTest {
        val input = QamInputs.baseline(QamRoute.NAMED_SELF)
        val otherNamed = PurchasingApprovalRouteV1.NamedSelf(PurchasingActorSelectionV1.CurrentBranchGrantees,
            PurchasingActorSelectionV1.CurrentBranchGrantees, listOf(QamInputs.otherRequester))
        val wrongException = input.replacingRule(QamInputs.ruleLike(assertNotNull(input.rule), approval = otherNamed))
        compare("QAM-02 another account cannot allow actual R/R", wrongException, MISMATCH)
        val noException = input.replacingRule(QamInputs.ruleLike(assertNotNull(input.rule), approval =
            PurchasingApprovalRouteV1.Separate(PurchasingActorSelectionV1.CurrentBranchGrantees,
                PurchasingActorSelectionV1.CurrentBranchGrantees)))
        compare("QAM-02 no named equality exception", noException, MISMATCH)
        val distinct = QamProofs.provision(QamRoute.SEPARATE, input.operation.requirement())
        compare("QAM-02 distinct R/A does not require equality-list membership",
            wrongException.copy(authorization = distinct.authorization, provision = distinct))
    }

    @Test
    fun qam02BodyActorSelectionsNeverSubstituteForActualProofIdentities() = runTest {
        val separate = QamInputs.baseline(QamRoute.SEPARATE)
        val namedBoth = PurchasingActorSelectionV1.NamedEmployees(listOf(QamInputs.requester, QamInputs.approver))
        val bothSelections = PurchasingApprovalRouteV1.Separate(namedBoth, namedBoth)
        compare("QAM-02 actual R/A under explicit selections", separate.replacingRule(
            QamInputs.ruleLike(assertNotNull(separate.rule), approval = bothSelections)))
        val self = QamProofs.provision(QamRoute.NAMED_SELF, separate.operation.requirement())
        compare("QAM-02 body names do not turn actual R/R into distinct actors", separate.replacingRule(
            QamInputs.ruleLike(assertNotNull(separate.rule), approval = bothSelections)).copy(
            authorization = self.authorization, provision = self), MISMATCH)
        assertEquals(QamInputs.requester, separate.authorization.actorId)
        assertEquals(QamInputs.approver, separate.authorization.approverId)
        assertEquals(QamInputs.requester, self.authorization.approverId)
    }

    @Test
    fun qam02AllModesRequireTheExactOriginalRequesterAndExpectedRequesterPolicyRevision() = runTest {
        for (route in QamRoute.entries) {
            val input = QamInputs.baseline(route)
            val otherActor = QamProofs.provision(route, input.operation.requirement(), requesterId = QamInputs.otherRequester)
            compare("QAM-02 $route proof actor differs from original requester",
                input.copy(authorization = otherActor.authorization, provision = otherActor), MISMATCH)
            compare("QAM-02 $route expected original identity cannot be chosen by proof",
                input.copy(originalRequesterId = QamInputs.otherRequester), MISMATCH)
            val oldPolicy = QamProofs.provision(route, input.operation.requirement(), requesterRevision = Revision(10))
            compare("QAM-02 $route actual requester employee-policy revision",
                input.copy(authorization = oldPolicy.authorization, provision = oldPolicy), MISMATCH)
            compare("QAM-02 $route independently newer employee-policy revision",
                input.copy(expectedRequesterPolicyRevision = Revision(12)), MISMATCH)
        }
    }

    @Test
    fun qam02NonPerformOutputsAreRejectedInEveryMode() = runTest {
        for (route in QamRoute.entries) {
            val input = QamInputs.baseline(route)
            for (verb in listOf(PermissionVerb.REQUEST, PermissionVerb.APPROVE)) {
                val requirement = input.operation.requirement().copy(permission = QamInputs.publicPermission.copy(verb = verb))
                val (_, actualOutput) = QamProofs.publicAuthorization(requirement)
                compare("QAM-02 $route actual public $verb output", input.copy(authorization = actualOutput), MISMATCH)
            }
        }
    }

    @Test
    fun qam02EverySuppliedAuthorizationRequirementCoordinateIsComparedExactly() = runTest {
        for (route in QamRoute.entries) {
            val input = QamInputs.baseline(route)
            val expected = input.operation.requirement()
            val binding = assertNotNull(expected.binding)
            val changedSemantic = input.operation.copy(retention = PurchasingArrivalRetentionSelection(QamInputs.otherRetentionClassId, Revision(2))).requirement()
            val variants = listOf(
                "target ID" to expected.copy(binding = binding.copy(documentId = QamInputs.otherTargetId)),
                "target revision" to expected.copy(binding = binding.copy(revision = Revision(4))),
                "operation ID" to expected.copy(binding = binding.copy(operationId = QamInputs.otherOperationId)),
                "missing operation ID" to expected.copy(binding = binding.copy(operationId = null)),
                "semantic digest" to changedSemantic,
                "reason code" to expected.copy(binding = binding.copy(reasonCode = "purchasing.arrival.changed")),
                "branch scope" to expected.copy(scope = QamInputs.otherBranchScope),
                "company in requirement scope" to expected.copy(scope = QamInputs.otherCompanyScope),
                "security action" to expected.copy(permission = Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM)),
                "limit-use coordinate" to expected.copy(limitUse = LimitUse.Monetary(Money(Currency.EGP, 1))),
                "owner coordinate" to expected.copy(owner = OwnerConstraint.OwnerPrivate(QamInputs.otherRequester)),
            )
            variants.forEach { (label, requirement) ->
                // Approvals require an operation ID; the genuine direct output covers its absence for every selected route.
                val proofRoute = if (requirement.binding?.operationId == null) QamRoute.DIRECT else route
                val actual = QamProofs.provision(proofRoute, requirement)
                compare("QAM-02 $route different $label", input.copy(authorization = actual.authorization, provision = actual), MISMATCH)
            }
        }
    }

    @Test
    fun qam03OptionalZeroOneAndTwoExactOriginalsAgree() = runTest {
        for (count in 0..2) {
            val input = QamInputs.baseline(selectedDocuments = QamInputs.documents().take(count),
                originalDocuments = QamInputs.documents().take(count))
            if (count > 0) assertNotSame(input.selectedDocuments[0], input.originalDocuments[0])
            compare("QAM-03 optional exact $count originals", input)
        }
    }

    @Test
    fun qam03RequiredOneAndTwoAgreeAndZeroRefuses() = runTest {
        for (route in listOf(QamRoute.SEPARATE, QamRoute.NAMED_SELF)) {
            for (count in 0..2) {
                val input = QamInputs.baseline(route, QamInputs.documents().take(count), QamInputs.documents().take(count))
                compare("QAM-03 $route required $count originals", input, if (count == 0) MISMATCH else NO_MISMATCH)
            }
        }
    }

    @Test
    fun qam03AnyRuleRefusesThreeOriginalsAndRepeatedOriginalIdentity() = runTest {
        for (route in QamRoute.entries) {
            val selected = QamInputs.documents() + QamInputs.documents()[1].copy(documentId = QamInputs.overflowId)
            val resolved = QamInputs.documents() + QamInputs.documents()[1].copy(documentId = QamInputs.overflowId)
            compare("QAM-03 $route three originals", QamInputs.baseline(route, selected, resolved), MISMATCH)
            val repeatedSelected = listOf(QamInputs.documents()[0], QamInputs.documents()[0])
            val repeatedResolved = listOf(QamInputs.documents()[0], QamInputs.documents()[0])
            compare("QAM-03 $route repeated original ID", QamInputs.baseline(route, repeatedSelected, repeatedResolved), MISMATCH)
        }
    }

    @Test
    fun qam03PresentOptionalAndRequiredOriginalsKeepAllSelectorConstraints() = runTest {
        val variants: List<Pair<String, (PurchasingArrivalDocumentReference) -> PurchasingArrivalDocumentReference>> = listOf(
            "forbidden PNG media" to { it.copy(media = PurchasingEvidenceMediaV1.PNG) },
            "supplier invoice purpose" to { it.copy(purpose = PurchasingEvidencePurposeV1.SUPPLIER_INVOICE) },
            "different supplier" to { it.copy(supplierId = QamInputs.otherSupplierId) },
            "different branch" to { it.copy(scope = QamInputs.otherBranchScope) },
            "missing positive storage revision" to { it.copy(storageRevision = Revision(0)) },
            "missing positive byte length" to { it.copy(byteLength = 0) },
        )
        for (route in QamRoute.entries) {
            for (index in 0..1) {
                variants.forEach { (label, change) ->
                    val selected = QamInputs.documents().mapIndexed { i, doc -> if (i == index) change(doc) else doc }
                    val resolved = QamInputs.documents().mapIndexed { i, doc -> if (i == index) change(doc) else doc }
                    compare("QAM-03 $route original $index $label", QamInputs.baseline(route, selected, resolved), MISMATCH)
                }
            }
        }
    }

    @Test
    fun qam03ResolvedOriginalsMustExactlyMatchTheIndependentOrderedSelections() = runTest {
        val changes: List<Pair<String, (PurchasingArrivalDocumentReference) -> PurchasingArrivalDocumentReference>> = listOf(
            "original ID" to { it.copy(documentId = QamInputs.changedOriginalId) },
            "storage revision" to { it.copy(storageRevision = Revision(2)) },
            "byte length" to { it.copy(byteLength = it.byteLength + 1) },
            "digest" to { it.copy(exactBytesDigest = PayloadDigest("0".repeat(64))) },
            "media" to { it.copy(media = PurchasingEvidenceMediaV1.PNG) },
            "purpose" to { it.copy(purpose = PurchasingEvidencePurposeV1.SUPPLIER_INVOICE) },
            "supplier" to { it.copy(supplierId = QamInputs.otherSupplierId) },
            "branch" to { it.copy(scope = QamInputs.otherBranchScope) },
        )
        for (route in QamRoute.entries) {
            val input = QamInputs.baseline(route)
            for (index in input.originalDocuments.indices) {
                changes.forEach { (label, change) -> compare("QAM-03 $route resolved original $index different $label",
                    input.copy(originalDocuments = input.originalDocuments.mapIndexed { i, doc -> if (i == index) change(doc) else doc }), MISMATCH) }
            }
            compare("QAM-03 $route resolved order reversed", input.copy(originalDocuments = QamInputs.documents().reversed()), MISMATCH)
            compare("QAM-03 $route resolved original missing", input.copy(originalDocuments = QamInputs.documents().take(1)), MISMATCH)
            compare("QAM-03 $route selection count differs from resolution", input.copy(selectedDocuments = QamInputs.documents().take(1)), MISMATCH)
        }
    }

    @Test
    fun qam04ExplicitRetentionClassAndRevisionNeverFallBack() = runTest {
        for (route in QamRoute.entries) {
            val input = QamInputs.baseline(route)
            compare("QAM-04 $route exact class revision 2", input)
            compare("QAM-04 $route selected class/revision omitted", input.copy(retention = null), MISMATCH)
            compare("QAM-04 $route class ID changed", input.copy(retention =
                PurchasingArrivalRetentionSelection(QamInputs.otherRetentionClassId, Revision(2))), MISMATCH)
            compare("QAM-04 $route class revision changed", input.copy(retention =
                PurchasingArrivalRetentionSelection(QamInputs.retentionClassId, Revision(1))), MISMATCH)
            val otherRule = QamInputs.rule(QamRoute.DIRECT, QamInputs.otherRuleId,
                evidence = QamInputs.evidence(QamRoute.DIRECT, QamInputs.otherRetentionClassId, Revision(1)))
            val policy = QamInputs.policyLike(input.policy,
                rules = input.policy.rules.map { if (it.ruleId == otherRule.ruleId) otherRule else it },
                retentionClasses = listOf(QamInputs.retentionClass(), QamInputs.retentionClass(QamInputs.otherRetentionClassId, Revision(1))))
            compare("QAM-04 $route other rule class present still cannot substitute", input.copy(policy = policy,
                retention = PurchasingArrivalRetentionSelection(QamInputs.otherRetentionClassId, Revision(1))), MISMATCH)
        }
    }

    @Test
    fun qam05OldSuppliedRevisionsAgainstNewExplicitExpectedCoordinatesRefuse() = runTest {
        val input = QamInputs.baseline()
        val selection = assertNotNull(input.selection)
        compare("QAM-05 old supplied publication", input.copy(policy = QamInputs.policyLike(input.policy, revision = Revision(7))), MISMATCH)
        compare("QAM-05 newer explicitly expected publication", input.copy(selection = selection.copy(publicationRevision = Revision(9))), MISMATCH)
        for (index in input.products.indices) {
            compare("QAM-05 old product $index", changedProduct(input, index) { QamInputs.productLike(it, productRevision = Revision(2)) }, MISMATCH)
            compare("QAM-05 old catalog $index", changedProduct(input, index) { QamInputs.productLike(it, catalog = QamInputs.catalog(revision = Revision(6))) }, MISMATCH)
        }
        compare("QAM-05 newer explicitly expected catalog", input.copy(expectedCatalog = QamInputs.catalog(revision = Revision(8))), MISMATCH)
    }

    @Test
    fun qam05AnotherOperationTargetRevisionOrSemanticBodyCannotBorrowTheProof() = runTest {
        for (route in QamRoute.entries) {
            val input = QamInputs.baseline(route)
            val semanticVariants = listOf(
                "operation ID" to input.operation.copy(operationId = QamInputs.otherOperationId),
                "target revision" to input.operation.copy(targetRevision = Revision(4)),
                "supplier semantics" to input.operation.copy(supplier = QamInputs.supplier(QamInputs.otherSupplierId)),
                "product semantics" to input.operation.copy(products = input.products.mapIndexed { i, product ->
                    if (i == 1) QamInputs.productLike(product, productRevision = Revision(2)) else product }),
                "original-selection order semantics" to input.operation.copy(documents = QamInputs.documents().reversed()),
                "retention semantics" to input.operation.copy(retention = PurchasingArrivalRetentionSelection(QamInputs.otherRetentionClassId, Revision(2))),
            )
            semanticVariants.forEach { (label, operation) ->
                val actual = QamProofs.provision(route, operation.requirement())
                compare("QAM-05 $route different $label proof", input.copy(authorization = actual.authorization, provision = actual), MISMATCH)
            }
        }
    }

    @Test
    fun qam05IdenticalSnapshotsGiveIdenticalResultsAndLeaveInputsAndProofCallsUnchanged() = runTest {
        for (route in QamRoute.entries) {
            val input = QamInputs.baseline(route)
            val before = input.observation()
            repeat(3) { compare("QAM-05 $route identical agreement $it", input) }
            val rejected = input.copy(selection = assertNotNull(input.selection).copy(ruleId = QamInputs.unknownRuleId))
            repeat(3) { compare("QAM-05 $route identical refusal $it", rejected, MISMATCH) }
            assertEquals(before, input.observation())
        }
    }

    @Test
    fun qam05AgreementDoesNotRefreshRevokedActorSessionDeviceOrGrantFacts() = runTest {
        for (route in QamRoute.entries) {
            val input = QamInputs.baseline(route)
            val provision = input.provision
            val currentRequirement = provision.requesterAuthorization.requirement
            val denials = listOf(
                QamProofs.evaluateCurrent(provision.requesterPrincipal, currentRequirement, provision.requesterRevision, accountEnabled = false),
                QamProofs.evaluateCurrent(provision.requesterPrincipal, currentRequirement, provision.requesterRevision, sessionActive = false),
                QamProofs.evaluateCurrent(provision.requesterPrincipal, currentRequirement, provision.requesterRevision, deviceAdmitted = false),
                QamProofs.evaluateCurrent(provision.requesterPrincipal, currentRequirement, provision.requesterRevision, permissionPresent = false),
            )
            denials.forEach { assertIs<AuthorizationDecision.Denied>(it) }
            compare("QAM-05 $route historical supplied proof agrees without current authority", input)
        }
    }

    @Test
    fun qam05AgreementDoesNotRefreshExpiredConsumedOrRevokedApproverApprovalFacts() = runTest {
        for (route in listOf(QamRoute.SEPARATE, QamRoute.NAMED_SELF)) {
            val input = QamInputs.baseline(route)
            val provision = input.provision
            val issued = assertNotNull(provision.issued)
            val consumed = assertNotNull(provision.consumed)
            val currentApprover = assertNotNull(provision.currentApprover)
            val issuedBefore = issued.copy()
            val consumedBefore = consumed.copy()
            assertIs<ApprovalConsumeDecision.Denied>(ActionApprovals.consume(issued, provision.callsRequirement,
                provision.requesterAuthorization, currentApprover.copy(enabled = false), provision.selfPolicy, provision.consumedAt))
            assertIs<ApprovalConsumeDecision.Denied>(ActionApprovals.consume(consumed, provision.callsRequirement,
                provision.requesterAuthorization, currentApprover, provision.selfPolicy, provision.consumedAt))
            val definitelyLate = MonotonicMoment(QamInputs.boot, provision.issuedAt.elapsedMillis + provision.validForMillis + 1)
            assertIs<ApprovalConsumeDecision.Denied>(ActionApprovals.consume(issued, provision.callsRequirement,
                provision.requesterAuthorization, currentApprover, provision.selfPolicy, definitelyLate))
            compare("QAM-05 $route supplied proof agreement cannot override current approval refusals", input)
            assertEquals(issuedBefore, issued)
            assertEquals(consumedBefore, consumed)
        }
    }
}
