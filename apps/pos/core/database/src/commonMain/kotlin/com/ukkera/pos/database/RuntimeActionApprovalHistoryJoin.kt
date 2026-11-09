package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Secret-free facts at one actual native event cut. Construction requires the genuine
 * chronological C1 ACCOUNT seed or an independently joined, already active enrollment.
 * This is not a principal/session store and never creates an AuthorizedAction. */
internal class RuntimeActionApprovalHistoryActorFacts private constructor(
    private val session: SelfOwnedNativeHistoryReadSession,
    private val header: RuntimeHistoryEventHeaderV2,
    private val account: LocalAccountSessionState,
    private val credentialRevision: Revision,
    private val credentialPresent: Boolean,
    private val policy: ActivatedLocalPolicy,
    private val admission: LocalDeviceAdmission,
    val dependencies: RuntimeThrottleDependenciesV1,
    private val issuances: Map<DomainId, LocalAccessIssuance>,
) {
    fun requestor(selected: SelfOwnedNativeHistoryReadSession, cut: RuntimeHistoryEventHeaderV2,
        supplied: RuntimeCommandAuthorizationSnapshotV1, requirement: AuthorizationRequirement,
        observed: LocalAuthenticationObservation): Boolean {
        if (!interactiveAccess(selected, cut, supplied, observed)) return false
        return AuthorizationEvaluator.permitsHistoricalInteractiveLocalRequirement(policy, account.actorId, account.scope,
            requirement.copy(permission = requirement.permission.copy(verb = PermissionVerb.REQUEST)))
    }

    /** Private serial requests keep their exact preparation verb, or the original actor's
     * narrower recovery query. Neither approval REQUEST nor a supervisor route qualifies. */
    fun serialPublicationRequest(selected: SelfOwnedNativeHistoryReadSession, cut: RuntimeHistoryEventHeaderV2,
        evidence: RuntimeSerialPublicationRequestEvidenceV1, originalActor: DomainId,
        observed: LocalAuthenticationObservation): Boolean {
        evidence.validate()
        if (evidence.authorization.actorId != originalActor ||
            !interactiveAccess(selected, cut, evidence.authorization, observed)) return false
        val request = evidence.requestValue()
        val requirement = runtimeSerialPublicationRequirement(request, originalActor)
        historyShapeV2(evidence.requirement.value() == requirement && request.scope == account.scope)
        return when (request.operation) {
            NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE,
            NativeSerialPrivatePublicationOperationV1.PREPARE ->
                AuthorizationEvaluator.permitsHistoricalInteractiveLocalRequirement(policy, account.actorId, account.scope, requirement)
            NativeSerialPrivatePublicationOperationV1.REVIEW,
            NativeSerialPrivatePublicationOperationV1.RECOVER,
            NativeSerialPrivatePublicationOperationV1.STATUS ->
                AuthorizationEvaluator.permitsHistoricalOriginalActorResultRecovery(policy, account.actorId, account.scope,
                    OperationId(checkNotNull(request.originRequestId).value), originalActor)
        }
    }

    private fun interactiveAccess(selected: SelfOwnedNativeHistoryReadSession, cut: RuntimeHistoryEventHeaderV2,
        supplied: RuntimeCommandAuthorizationSnapshotV1, observed: LocalAuthenticationObservation): Boolean {
        historyShapeV2(selected === session && cut == header)
        if (supplied.scope != account.scope || supplied.actorId != account.actorId || supplied.deviceId != admission.deviceId ||
            supplied.principalKind != PrincipalKind.LOCAL_EMPLOYEE || supplied.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return false
        val access = issuances[supplied.sessionId] ?: return false
        val identity = VerifiedLocalIdentity(account.actorId, account.scope, admission.deviceId, supplied.sessionId,
            supplied.generation, supplied.admissionRevision, AuthenticationPurpose.INTERACTIVE)
        historyShapeV2(supplied.accountRevision == account.revision && supplied.credentialRevision == credentialRevision &&
            supplied.policyRevision == policy.revision && supplied.admissionRevision == admission.revision &&
            supplied.dependencies == RuntimeCommandAuthorizationDependenciesV1(dependencies.account, dependencies.policy,
                dependencies.admission, digest(ProtectedLocalSecurityCodec.encodeIssuance(access))) &&
            supplied.access.issuerContextId == access.issuerContextId && supplied.access.issuedAt == access.issuedAt &&
            supplied.access.expiresAtElapsedMillis == access.expiresAtElapsedMillis &&
            supplied.access.credentialRevisionAtIssue == access.credentialRevisionAtIssue &&
            supplied.access.admissionRevisionAtIssue == access.admissionRevisionAtIssue)
        historyShapeV2(LocalAccessEligibility.matchesCurrentFacts(identity, access, account, credentialRevision,
            credentialPresent, admission, observed.issuerContextId, observed.moment))
        return true
    }

    fun approver(selected: SelfOwnedNativeHistoryReadSession, cut: RuntimeHistoryEventHeaderV2,
        supplied: RuntimeApprovalApproverSnapshotV1, approval: ProtectedLocalActionApproval): Boolean {
        historyShapeV2(selected === session && cut == header)
        if (supplied.scope != account.scope || supplied.actorId != account.actorId || supplied.deviceId != admission.deviceId) return false
        historyShapeV2(account.enabled && credentialPresent && admission.admitted && account.revision.value < Long.MAX_VALUE &&
            account.generation.value < Long.MAX_VALUE && supplied.accountRevision == account.revision &&
            supplied.generation == account.generation && supplied.credentialRevision == credentialRevision &&
            supplied.policyRevision == policy.revision && supplied.admissionRevision == admission.revision &&
            supplied.dependencies == RuntimeApprovalApproverDependenciesV1(dependencies.account, dependencies.policy, dependencies.admission))
        val record = approval.record
        val configured = policy.approvalPolicy ?: return false
        if (configured.validForMillis != record.validForMillis || record.selfApprovalPolicyRevision != policy.revision ||
            record.approverPolicyRevision != policy.revision) return false
        if (record.requestorId == record.approverId && !configured.forActor(account.scope, account.actorId, policy.revision)
                .permits(account.scope, account.actorId, record.requirement.permission.action)) return false
        return AuthorizationEvaluator.permitsHistoricalPrivilegedLocalApproval(policy, account.actorId, account.scope,
            record.requirement.copy(permission = record.requirement.permission.copy(verb = PermissionVerb.APPROVE)))
    }

    companion object {
        fun founding(selected: SelfOwnedNativeHistoryReadSession, original: OriginalNativeHistorySnapshot,
            seedDigest: PayloadDigest, header: RuntimeHistoryEventHeaderV2, account: LocalAccountSessionState,
            credentialRevision: Revision, credentialPresent: Boolean, policy: ActivatedLocalPolicy,
            admission: LocalDeviceAdmission, dependencies: RuntimeThrottleDependenciesV1,
            issuances: Map<DomainId, LocalAccessIssuance>): RuntimeActionApprovalHistoryActorFacts {
            val coordinates = original.record.allocation.coordinates
            historyShapeV2(selected.original === original && seedDigest == checkNotNull(original.record.receipt).core.seeds.account &&
                account.actorId == coordinates.firstActorId && account.scope == coordinates.scope && admission.deviceId == coordinates.deviceId)
            requireCut(selected, header, 1L)
            return RuntimeActionApprovalHistoryActorFacts(selected, header, account, credentialRevision, credentialPresent,
                policy, admission, dependencies, issuances.toMap())
        }
        fun enrolled(selected: SelfOwnedNativeHistoryReadSession, source: NativeEmployeeEnrollmentHistorySource,
            header: RuntimeHistoryEventHeaderV2, account: LocalAccountSessionState, credentialRevision: Revision,
            credentialPresent: Boolean, policy: ActivatedLocalPolicy, admission: LocalDeviceAdmission,
            dependencies: RuntimeThrottleDependenciesV1, issuances: Map<DomainId, LocalAccessIssuance>): RuntimeActionApprovalHistoryActorFacts {
            val coordinates = selected.original.record.allocation.coordinates
            historyShapeV2(selected.employeeEnrollments.any { it === source } && account.actorId == source.requested.request.targetId &&
                account.actorId != coordinates.firstActorId && account.scope == coordinates.scope && admission.deviceId == coordinates.deviceId &&
                source.child.commandEpoch == header.commandEpoch && source.child.installationGenesisDigest == header.installationGenesisDigest)
            requireCut(selected, header, source.child.historySequence)
            return RuntimeActionApprovalHistoryActorFacts(selected, header, account, credentialRevision, credentialPresent,
                policy, admission, dependencies, issuances.toMap())
        }
        private fun requireCut(selected: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2, origin: Long) {
            val native = selected.original.record
            historyShapeV2(header.sequence > origin && header.assignmentId == native.allocation.coordinates.assignmentId &&
                header.installationGenesisDigest == checkNotNull(native.firstChild).installationGenesisDigest &&
                header.commandEpoch == checkNotNull(native.definition).definition.authorityEpoch.value)
        }
        private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
    }
}

/** Registered DATA from exact earlier issuance, both original humans, actual MATCH and
 * native final decisions. Complete protected lineage/current/relation and business joins
 * are still mandatory. Older direct history predicates are deliberately not relaxed. */
internal class RuntimeActionApprovalHistoryJoin(private val session: SelfOwnedNativeHistoryReadSession) {
    private val coordinates = session.original.record.allocation.coordinates
    private val scope = coordinates.scope
    private class Event(val header: RuntimeHistoryEventHeaderV2, val digest: PayloadDigest,
        val issue: RuntimeActionApprovalIssuanceEvidenceV1?, val command: RuntimeApprovedCommandEvidenceV1?,
        val retirement: RuntimeActionApprovalRetirementEvidenceV1?,
        val versions: List<RuntimeHistoryRowVersionRowV2>) {
        val images = mutableListOf<Image>()
        fun clear() { versions.forEach { it.key.fill(0) }; images.forEach { it.bytes.fill(0) }; images.clear() }
    }
    private class Image(val event: Event, val version: RuntimeHistoryRowVersionRowV2,
        val binding: ProtectedLocalBinding, val image: RuntimeApprovalImageV1, val bytes: ByteArray) {
        var joined = false
    }
    private class Issue(val event: Event, val value: ProtectedLocalActionApproval) {
        var command: RuntimeActionApprovalHistoryAuthorization? = null
        var retirement: Event? = null
    }
    private val events = linkedMapOf<Long, Event>()
    private val issues = linkedMapOf<DomainId, Issue>()
    private val commands = linkedMapOf<Long, RuntimeActionApprovalHistoryAuthorization>()
    private val operations = linkedSetOf<com.ukkera.pos.types.OperationId>()
    private val latest = linkedMapOf<ProtectedLocalRecordKey, Image>()
    private val currentJoined = linkedSetOf<ProtectedLocalRecordKey>()
    private val relationsJoined = linkedSetOf<DomainId>()
    private var retainedBytes = 0L
    private var retainedVersions = 0
    private var enabled = true
    val issuanceEvents: Long get() = issues.size.toLong()
    val retirementEvents: Long get() = events.values.count { it.retirement != null }.toLong()
    val commandEvents: Long get() = commands.size.toLong()
    val originalCommandEvents: Long get() = commands.values.count { it.requesterIsOriginal }.toLong()
    val employeeCommandEvents: Long get() = commandEvents - originalCommandEvents
    val commandMutations: Long get() = commands.values.sumOf { it.evidence.capturedChanges.toLong() }
    val originalCommandMutations: Long get() = commands.values.filter { it.requesterIsOriginal }.sumOf { it.evidence.capturedChanges.toLong() }
    val employeeCommandMutations: Long get() = commandMutations - originalCommandMutations
    val issuanceProtectedMutationCount: Long get() = issuanceEvents * 2L
    val nonbusinessProtectedMutationCount: Long get() = issuanceProtectedMutationCount + retirementEvents
    val protectedMutationCount: Long get() = events.values.sumOf { it.images.size.toLong() }
    var imageCount: Long = 0L
        private set
    val tipCount: Long get() = latest.size.toLong()
    fun count(table: RuntimeHistoryTableV2): Long = latest.keys.count { key -> key.table == table.protectedTable }.toLong()

    fun issuance(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest, evidence: RuntimeActionApprovalIssuanceEvidenceV1,
        versions: List<RuntimeHistoryRowVersionRowV2>, finalDecision: NativeHistoryProtectedFinalDecision,
        original: RuntimeOriginalAuthenticationReplay?, employees: RuntimeEmployeeEnrollmentHistoryJoin): Boolean {
        historyShapeV2(enabled)
        evidence.validate()
        historyCapacityV2(issues.size < RuntimeBusinessHistoryJoin.MAX_COMMANDS)
        historyShapeV2(header.purpose == RuntimeWritePurposeV2.APPROVAL_ISSUE && header.changeCount == 4 &&
            evidence.requestor.scope == scope && evidence.approvalId !in issues && header.sequence !in events)
        val requester = actor(evidence.requestor.actorId, header, original, employees) ?: return false
        val manager = actor(evidence.approver.actorId, header, original, employees) ?: return false
        val value = evidence.value()
        val previous = issues.values.filter { it.value.record.requirement.binding?.operationId == value.record.requirement.binding?.operationId }
        // Fresh IDs cannot bypass an unresolved/live predecessor, or change original business
        // content/policy/session attribution. The actual chronological native chain is authority.
        historyShapeV2(previous.all { prior -> prior.retirement != null && prior.command == null &&
            prior.event.header.sequence < checkNotNull(prior.retirement).header.sequence &&
            checkNotNull(prior.retirement).header.sequence < header.sequence && sameActionApprovalBusiness(prior.value, value) &&
            checkNotNull(prior.event.issue).commandName == evidence.commandName &&
            checkNotNull(prior.event.issue).commandVersion == evidence.commandVersion })
        if (previous.isEmpty()) historyShapeV2(!value.hasReplacementOrigin)
        else if (value.hasReplacementOrigin) {
            val root = previous.first(); val predecessor = previous.last()
            historyShapeV2(!root.value.hasReplacementOrigin && value.replacementOrigin == actionApprovalReplacementOrigin(
                root.value, predecessor.value, checkNotNull(checkNotNull(predecessor.retirement).retirement).event()))
        } else historyShapeV2(previous.none { it.value.hasReplacementOrigin }) // Never strip a v3 chain into v2.
        requireFinal(header, digest, evidence.protectedFinalDeclaration(), finalDecision)
        if (!requester.requestor(session, header, evidence.requestor, value.record.requirement,
                LocalAuthenticationObservation(evidence.requestor.access.issuerContextId, evidence.requestor.access.observedAt)) ||
            !requester.requestor(session, header, evidence.requestor, value.record.requirement, finalDecision.observed) ||
            !manager.approver(session, header, evidence.approver, value)) return false
        historyShapeV2(manager.dependencies == evidence.throttle.dependencies)
        requireCompleteVersions(header, versions)
        for ((ordinal, state) in evidence.throttle.changed().withIndex()) {
            val version = versions[ordinal]; val key = runtimeThrottleKey(state.key)
            try { historyShapeV2(version.tableTag == RuntimeHistoryTableV2.THROTTLE.tag && version.key.contentEquals(key) &&
                version.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag && version.version == state.revision.value + 1L &&
                version.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag) } finally { key.fill(0) }
        }
        evidence.images.forEachIndexed { index, image -> requireImageVersion(versions[index + 2], image, null) }
        // Advance only after both original human comparisons and the exact complete census.
        if (evidence.approver.actorId == coordinates.firstActorId) checkNotNull(original).throttle(evidence.throttle)
        else historyShapeV2(employees.approvalThrottle(evidence.throttle, header))
        val event = registerEvent(header, digest, evidence, null, versions)
        val entry = Issue(event, value)
        issues[evidence.approvalId] = entry
        registerImage(event, 2, evidence.images[0], LocalActionApprovalCodec.encode(value))
        registerImage(event, 3, evidence.images[1], LocalAuthenticationCodec.encodeEvent(runtimeApprovalIssueEvent(value)))
        return true
    }

    fun retirement(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest, evidence: RuntimeActionApprovalRetirementEvidenceV1,
        versions: List<RuntimeHistoryRowVersionRowV2>, finalDecision: NativeHistoryProtectedFinalDecision,
        original: RuntimeOriginalAuthenticationReplay?, employees: RuntimeEmployeeEnrollmentHistoryJoin): Boolean {
        historyShapeV2(enabled)
        evidence.validate()
        val issue = issues[evidence.approvalId] ?: return false
        val issuance = checkNotNull(issue.event.issue)
        historyShapeV2(header.purpose == RuntimeWritePurposeV2.APPROVAL_RETIRE && header.changeCount == 1 &&
            issue.event.header.sequence < header.sequence && issue.command == null && issue.retirement == null && header.sequence !in events &&
            evidence.requestor.scope == scope && evidence.approval == issuance.approval &&
            evidence.commandName == issuance.commandName && evidence.commandVersion == issuance.commandVersion &&
            evidence.beforeHistorySequence == header.parentSequence && evidence.beforeHistoryDigest == header.parentDigest &&
            evidence.beforeCommandEpoch.value == header.commandEpoch && evidence.beforeCommandSequence.value == header.commandSequence &&
            evidence.beforeCommandDigest == header.commandDigest && issue.value.record.revision == Revision(0) &&
            issue.value.record.consumedByOperationId == null && checkNotNull(issue.value.record.requirement.binding?.operationId) !in operations)
        val requester = actor(evidence.requestor.actorId, header, original, employees) ?: return false
        requireFinal(header, digest, evidence.protectedFinalDeclaration(), finalDecision)
        if (!requester.requestor(session, header, evidence.requestor, issue.value.record.requirement,
                LocalAuthenticationObservation(evidence.requestor.access.issuerContextId, evidence.requestor.access.observedAt)) ||
            !requester.requestor(session, header, evidence.requestor, issue.value.record.requirement, finalDecision.observed)) return false
        requireCompleteVersions(header, versions)
        requireImageVersion(versions.single(), evidence.images.single(), null)
        val event = registerEvent(header, digest, null, null, versions, evidence)
        registerImage(event, 0, evidence.images.single(), LocalAuthenticationCodec.encodeEvent(evidence.event()))
        issue.retirement = event
        return true
    }

    fun command(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest, wrappedEvidence: RuntimeApprovedCommandEvidenceV1,
        versions: List<RuntimeHistoryRowVersionRowV2>, finalDecision: NativeHistoryProtectedFinalDecision,
        original: RuntimeOriginalAuthenticationReplay?, employees: RuntimeEmployeeEnrollmentHistoryJoin): RuntimeActionApprovalHistoryAuthorization? {
        historyShapeV2(enabled)
        wrappedEvidence.validate()
        val evidence = wrappedEvidence.command(); val use = wrappedEvidence.approvalUse
        val issue = issues[use.approvalId] ?: return null
        val issuance = checkNotNull(issue.event.issue)
        historyShapeV2(issue.event.header.sequence < header.sequence && issue.command == null && issue.retirement == null && header.sequence !in events &&
            evidence.operationId !in operations && evidence.authorization.scope == scope && header.purpose.tag == evidence.purposeTag &&
            use.issuedApproval == issuance.approval && use.approver == issuance.approver &&
            evidence.commandName == issuance.commandName && evidence.commandVersion == issuance.commandVersion)
        val requester = actor(evidence.authorization.actorId, header, original, employees) ?: return null
        val manager = actor(use.approver.actorId, header, original, employees) ?: return null
        requireFinal(header, digest, use.protectedFinalDeclaration(evidence), finalDecision)
        if (!requester.requestor(session, header, evidence.authorization, issue.value.record.requirement,
                LocalAuthenticationObservation(evidence.authorization.access.issuerContextId, evidence.authorization.access.observedAt)) ||
            !requester.requestor(session, header, evidence.authorization, issue.value.record.requirement, use.observed.value()) ||
            !requester.requestor(session, header, evidence.authorization, issue.value.record.requirement, finalDecision.observed) ||
            !manager.approver(session, header, use.approver, issue.value)) return null
        requireCompleteVersions(header, versions)
        historyShapeV2(header.changeCount == evidence.capturedChanges)
        val protected = versions.filter { RuntimeHistoryTableV2.fromTag(it.tableTag).protectedTable != null }
        historyShapeV2(protected.size == use.images.size)
        use.images.forEachIndexed { index, image -> requireImageVersion(protected[index], image,
            if (image.table == RuntimeHistoryTableV2.APPROVAL) issue.event.versions[2] else null) }
        val event = registerEvent(header, digest, null, wrappedEvidence, versions)
        if (use.kind == RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED) {
            registerImage(event, protected[0].ordinal, use.images[0], LocalActionApprovalCodec.encode(checkNotNull(use.consumed(scope))))
            registerImage(event, protected[1].ordinal, use.images[1], LocalAuthenticationCodec.encodeEvent(checkNotNull(use.event(scope))))
        }
        val proof = RuntimeActionApprovalHistoryAuthorization.capture(this, header, digest, evidence, wrappedEvidence,
            evidence.authorization.actorId == coordinates.firstActorId, issue.value, finalDecision)
        historyCapacityV2(commands.size < RuntimeBusinessHistoryJoin.MAX_COMMANDS)
        commands[header.sequence] = proof; operations += evidence.operationId; issue.command = proof
        return proof
    }

    private fun actor(id: DomainId, header: RuntimeHistoryEventHeaderV2, original: RuntimeOriginalAuthenticationReplay?,
        employees: RuntimeEmployeeEnrollmentHistoryJoin): RuntimeActionApprovalHistoryActorFacts? =
        if (id == coordinates.firstActorId) original?.approvalActor(session, header) else employees.approvalActor(id, header)

    private fun requireFinal(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        declaration: NativeHistoryProtectedFinalDeclaration, decision: NativeHistoryProtectedFinalDecision) {
        historyShapeV2(session.original.record.protectedFinalDecisions.any { it === decision } && decision.scope == scope &&
            decision.originalAllocationDigest == session.original.record.allocation.digest && decision.declaration == declaration &&
            decision.child == NativeHistoryRecordedHeadV2(header.sequence, digest, header.installationGenesisDigest, header.commandEpoch,
                header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment))
    }
    private fun requireCompleteVersions(header: RuntimeHistoryEventHeaderV2, versions: List<RuntimeHistoryRowVersionRowV2>) {
        historyShapeV2(versions.size == header.changeCount && versions.withIndex().all { (index, value) ->
            value.sequence == header.sequence && value.ordinal == index })
        historyShapeV2(versions.map { it.tableTag.toString() + ":" + sha256(it.key).value }.distinct().size == versions.size)
    }
    private fun requireImageVersion(version: RuntimeHistoryRowVersionRowV2, image: RuntimeApprovalImageV1,
        predecessor: RuntimeHistoryRowVersionRowV2?) {
        val key = image.key(scope)
        try {
            historyShapeV2(version.tableTag == image.tableTag && version.key.contentEquals(key) && version.mutationKind == image.mutationTag &&
                version.version == image.revision.value + 1L && version.afterRowId != null && version.afterDigest != null &&
                version.afterKind == (if (image.table.immutable) RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag else RuntimeHistoryAfterKindV2.IMAGE.tag))
            if (image.mutation == RuntimeHistoryMutationKindV2.INSERT) historyShapeV2(predecessor == null &&
                version.previousSequence == null && version.previousOrdinal == null && version.previousVersion == null &&
                version.beforeRowId == null && version.beforeDigest == null)
            else {
                val previous = checkNotNull(predecessor)
                historyShapeV2(image.table == RuntimeHistoryTableV2.APPROVAL && version.previousSequence == previous.sequence &&
                    version.previousOrdinal == previous.ordinal && version.previousVersion == previous.version &&
                    version.beforeRowId == previous.afterRowId && version.beforeDigest == previous.afterDigest)
            }
        } finally { key.fill(0) }
    }
    private fun registerEvent(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        issue: RuntimeActionApprovalIssuanceEvidenceV1?, command: RuntimeApprovedCommandEvidenceV1?,
        versions: List<RuntimeHistoryRowVersionRowV2>, retirement: RuntimeActionApprovalRetirementEvidenceV1? = null): Event {
        historyCapacityV2(events.size < RuntimeBusinessHistoryJoin.MAX_COMMANDS * 2 &&
            versions.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS - retainedVersions)
        historyShapeV2(listOfNotNull(issue, command, retirement).size == 1)
        val canonical = if (issue != null) RuntimeActionApprovalEvidenceCodec.encode(issue)
            else if (retirement != null) RuntimeActionApprovalRetirementEvidenceCodec.encode(retirement)
            else RuntimeActionApprovalEvidenceCodec.encode(checkNotNull(command))
        try { charge(canonical.size.toLong()) } finally { canonical.fill(0) }
        val copies = versions.map { version ->
            val wire = RuntimeHistoryCodecV2.encodeMutation(version)
            try { charge(wire.size.toLong() + version.key.size) } finally { wire.fill(0) }
            version.copy(key = version.key.copyOf())
        }
        val event = Event(header, digest, issue, command, retirement, copies)
        events[header.sequence] = event; retainedVersions += copies.size
        return event
    }
    private fun registerImage(event: Event, ordinal: Int, image: RuntimeApprovalImageV1, bytes: ByteArray) {
        try {
            charge(bytes.size.toLong())
            historyShapeV2(sha256(bytes) == image.plaintextDigest)
            val key = if (image.table == RuntimeHistoryTableV2.APPROVAL) ProtectedLocalRecordKey.ActionApproval(scope, image.recordId)
                else ProtectedLocalRecordKey.AuthEvent(scope, image.recordId)
            val selected = Image(event, event.versions[ordinal], ProtectedLocalBinding(key, image.revision), image, bytes)
            if (image.mutation == RuntimeHistoryMutationKindV2.INSERT) historyShapeV2(key !in latest)
            else historyShapeV2(key in latest && latest[key]?.binding?.revision == Revision(0))
            event.images += selected; latest[key] = selected
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    fun issuanceEvidence(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        version: RuntimeHistoryRowVersionRowV2): RuntimeActionApprovalIssuanceEvidenceV1? = event(header, digest, version)?.issue
    fun commandEvidence(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        version: RuntimeHistoryRowVersionRowV2): RuntimeApprovedCommandEvidenceV1? = event(header, digest, version)?.command
    fun retirementEvidence(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        version: RuntimeHistoryRowVersionRowV2): RuntimeActionApprovalRetirementEvidenceV1? = event(header, digest, version)?.retirement
    private fun event(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest, version: RuntimeHistoryRowVersionRowV2): Event? {
        historyShapeV2(enabled)
        val event = events[header.sequence] ?: return null
        historyShapeV2(event.header == header && event.digest == digest)
        requireSameVersion(event.versions.getOrNull(version.ordinal) ?: malformed(), version)
        return event
    }
    fun lineageImage(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest, version: RuntimeHistoryRowVersionRowV2,
        binding: ProtectedLocalBinding, bytes: ByteArray): Boolean {
        val event = event(header, digest, version) ?: return false
        val expected = event.images.singleOrNull { it.version.ordinal == version.ordinal } ?: return false
        historyShapeV2(!expected.joined && expected.binding == binding && expected.image.matches(binding) &&
            bytes.contentEquals(expected.bytes) && sha256(bytes) == expected.image.plaintextDigest)
        expected.joined = true; imageCount++
        return true
    }
    fun current(binding: ProtectedLocalBinding, bytes: ByteArray): Boolean {
        historyShapeV2(enabled)
        val expected = latest[binding.key] ?: return false
        historyShapeV2(expected.binding == binding && bytes.contentEquals(expected.bytes) && currentJoined.add(binding.key))
        return true
    }
    fun relation(value: ProtectedLocalActionApproval): Boolean {
        historyShapeV2(enabled)
        val issue = issues[value.record.approvalId] ?: return false
        val expected = latest[ProtectedLocalRecordKey.ActionApproval(scope, value.record.approvalId)] ?: malformed()
        val bytes = LocalActionApprovalCodec.encode(value)
        try { historyShapeV2(bytes.contentEquals(expected.bytes) && relationsJoined.add(value.record.approvalId)) }
        finally { bytes.fill(0) }
        historyShapeV2((value.record.consumedByOperationId != null) ==
            (issue.command?.wrappedEvidence?.approvalUse?.kind == RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED))
        if (issue.retirement != null) historyShapeV2(issue.command == null && value.record.revision == Revision(0) &&
            value.record.consumedByOperationId == null)
        return true
    }
    /** Complete immutable operation census proves absence AT retirement, not forever.
     * A later separately approved replacement may legitimately commit the same original
     * operation; an original Apply OR Reject at/before retirement is never safe absence.
     * This original actor/business profile admits same-epoch progression only. Recovery-
     * epoch transitions still require their separate original/resource authority joins. */
    fun retirementOperation(approvalId: DomainId, row: OperationRow) {
        historyShapeV2(enabled && row.scope == scope.storageKey())
        val issue = issues[approvalId] ?: return
        val retired = issue.retirement ?: return
        if (row.operationId != issue.value.record.requirement.binding?.operationId?.value) return
        historyShapeV2(row.epoch == retired.header.commandEpoch && row.sequence > retired.header.commandSequence)
    }
    internal fun requireProof(proof: RuntimeActionApprovalHistoryAuthorization, selected: SelfOwnedNativeHistoryReadSession,
        header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        historyShapeV2(enabled && selected === session && commands[header.sequence] === proof && proof.header == header &&
            proof.eventDigest == digest && proof.evidence === evidence)
    }
    internal fun requireProofImages(proof: RuntimeActionApprovalHistoryAuthorization) {
        historyShapeV2(enabled && commands[proof.header.sequence] === proof &&
            checkNotNull(events[proof.header.sequence]).images.all { it.joined })
        val selected = checkNotNull(issues[proof.wrappedEvidence.approvalUse.approvalId])
        val ancestors = issues.values.filter { it.event.header.sequence <= selected.event.header.sequence &&
            it.value.record.requirement.binding?.operationId == selected.value.record.requirement.binding?.operationId }
        historyShapeV2(ancestors.isNotEmpty() && ancestors.last() === selected && ancestors.all { it.event.images.all { image -> image.joined } } &&
            ancestors.dropLast(1).all { checkNotNull(it.retirement).images.all { image -> image.joined } })
    }
    internal fun requireProofVersions(proof: RuntimeActionApprovalHistoryAuthorization, versions: List<RuntimeHistoryRowVersionRowV2>) {
        historyShapeV2(enabled && commands[proof.header.sequence] === proof)
        val event = checkNotNull(events[proof.header.sequence])
        historyShapeV2(versions.size == event.versions.size)
        event.versions.zip(versions).forEach { (a, b) -> requireSameVersion(a, b) }
    }
    internal fun requireProtectedVersion(proof: RuntimeActionApprovalHistoryAuthorization, version: RuntimeHistoryRowVersionRowV2) {
        requireProofImages(proof)
        val event = checkNotNull(events[proof.header.sequence])
        val image = event.images.singleOrNull { it.version.ordinal == version.ordinal } ?: malformed()
        requireSameVersion(image.version, version)
    }
    fun requireClosure() {
        historyShapeV2(enabled && imageCount == protectedMutationCount && events.values.all { it.images.all { image -> image.joined } } &&
            currentJoined == latest.keys && relationsJoined == issues.keys && commands.values.all { it.joined })
    }
    fun clear() {
        enabled = false; events.values.forEach { it.clear() }; events.clear(); issues.clear(); commands.clear()
        operations.clear(); latest.clear(); currentJoined.clear(); relationsJoined.clear()
    }
    private fun charge(bytes: Long) { retainedBytes = historyAddV2(retainedBytes, bytes, RuntimeBusinessHistoryJoin.MAX_RETAINED_BYTES) }
    private fun requireSameVersion(a: RuntimeHistoryRowVersionRowV2, b: RuntimeHistoryRowVersionRowV2) {
        val left = RuntimeHistoryCodecV2.encodeMutation(a); val right = RuntimeHistoryCodecV2.encodeMutation(b)
        try { historyShapeV2(left.contentEquals(right)) } finally { left.fill(0); right.fill(0) }
    }
    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}

internal class RuntimeActionApprovalHistoryAuthorization private constructor(
    private val owner: RuntimeActionApprovalHistoryJoin,
    val header: RuntimeHistoryEventHeaderV2,
    val eventDigest: PayloadDigest,
    val evidence: RuntimeCommandAuthorizationEvidenceV1,
    val wrappedEvidence: RuntimeApprovedCommandEvidenceV1,
    val requesterIsOriginal: Boolean,
    private val issued: ProtectedLocalActionApproval,
    private val finalDecision: NativeHistoryProtectedFinalDecision,
) {
    var joined = false
        private set
    val requiresPreservedBusinessAttribution: Boolean get() = issued.hasReplacementOrigin
    val businessAuthenticationId: DomainId get() { requireImagesJoined(); return issued.requestorSessionId }
    val businessGeneration: SessionGeneration get() { requireImagesJoined(); return issued.requestorGeneration }
    fun requireCaptured(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        digest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) = owner.requireProof(this, session, header, digest, evidence)
    fun requireIntent(intent: NativeHistoryOriginalIntentV2) {
        val bytes = SelfOwnedNativeHistoryWireV2.encodeIntent(intent)
        try { historyShapeV2(sha256(bytes) == finalDecision.originalIntentDigest) } finally { bytes.fill(0) }
    }
    fun requireVersions(versions: List<RuntimeHistoryRowVersionRowV2>) = owner.requireProofVersions(this, versions)
    fun requireProtectedVersion(version: RuntimeHistoryRowVersionRowV2) = owner.requireProtectedVersion(this, version)
    fun requireImagesJoined() = owner.requireProofImages(this)
    fun originalApproval(): RuntimeHistoricalActionApproval {
        requireImagesJoined()
        return RuntimeHistoricalActionApproval(issued, CommandKey(evidence.commandName, evidence.commandVersion), evidence.operationId, evidence.semanticDigest)
    }
    fun markJoined(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        digest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        requireCaptured(session, header, digest, evidence); requireImagesJoined(); historyShapeV2(!joined); joined = true
    }
    companion object {
        fun capture(owner: RuntimeActionApprovalHistoryJoin, header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
            evidence: RuntimeCommandAuthorizationEvidenceV1, wrapped: RuntimeApprovedCommandEvidenceV1,
            original: Boolean, issued: ProtectedLocalActionApproval, finalDecision: NativeHistoryProtectedFinalDecision): RuntimeActionApprovalHistoryAuthorization =
            RuntimeActionApprovalHistoryAuthorization(owner, header, digest, evidence, wrapped, original, issued, finalDecision)
    }
}
