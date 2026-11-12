package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId

/** Closed historical origin used by the per-actor reducer and its registered proofs.
 * The existing variant retains the actual native-selected company-head enrollment object;
 * its session check is the same reference membership check as before this extraction.
 * A delegated origin retains a private completed initial-event join from the actual native
 * traversal. The two origins share replay fields, never a fabricated enrollment request or
 * company-head approval. Initial definitions/images are DATA. No parsed object, source ID,
 * Boolean, current row or callback can replace original native selection.
 * No origin alone admits history: seed/event/lineage/current/relationships and actual read
 * close still belong to the full scanner and its owning original-native coordinator. */
internal sealed class RuntimeEmployeeHistoryOrigin private constructor() {
    abstract val scope: BranchScope
    abstract val actorId: DomainId
    abstract val definition: LocalAccountProvisioning
    abstract val admissions: List<LocalDeviceAdmission>
    abstract val originalCohostDeviceId: DomainId
    abstract val child: NativeHistoryRecordedHeadV2
    abstract val frameId: DomainId
    abstract val eventAt: MonotonicMoment
    abstract val seedImages: List<LocalEmployeeEnrollmentProtectedImage>
    abstract val admissionDigests: List<Pair<DomainId, PayloadDigest>>
    abstract val eventPurpose: RuntimeWritePurposeV2

    /** Pure comparison with the actual native source or completed event selection, never
     * a supplied acceptance flag. Sealed construction covers the two distinct real origins. */
    fun matchesNativeSelection(session: SelfOwnedNativeHistoryReadSession): Boolean = when (this) {
        is CompanyHeadEnrollment -> session.employeeEnrollments.any { it === nativeSource }
        is DelegatedCreation -> completed.matchesNativeSelection(session)
    }

    /** Only the company-head prepass/capsule lane consumes this source. Reusable
     * chronological/current/lineage reducers retain the origin instead. */
    fun companyHeadEnrollment(): NativeEmployeeEnrollmentHistorySource = when (this) {
        is CompanyHeadEnrollment -> nativeSource
        is DelegatedCreation -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    }

    /** Fresh replay scopes retain the same actual native source, or independently join
     * the same actual session/final/header/event. Actor IDs and decoded definitions alone
     * never establish origin identity. */
    fun sameNativeSelection(other: RuntimeEmployeeHistoryOrigin): Boolean = when (this) {
        is CompanyHeadEnrollment -> other is CompanyHeadEnrollment && nativeSource === other.nativeSource
        is DelegatedCreation -> other is DelegatedCreation && completed.sameNativeSelection(other.completed)
    }

    /** The creation lane retains its complete original facts/dependencies/intent; it
     * cannot be reinterpreted as an enrollment or ordinary authentication capsule. */
    fun requireCreationCapsule(session: SelfOwnedNativeHistoryReadSession,
        header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        capsule: RuntimeHistoryCapsule, intent: NativeHistoryOriginalIntentV2) {
        when (this) {
            is CompanyHeadEnrollment -> Unit
            is DelegatedCreation -> completed.requireCapsule(session, header, eventDigest, capsule, intent)
        }
    }

    fun clear() { when (this) {
        is CompanyHeadEnrollment -> Unit
        is DelegatedCreation -> completed.clear()
    } }

    private class CompanyHeadEnrollment(val nativeSource: NativeEmployeeEnrollmentHistorySource) : RuntimeEmployeeHistoryOrigin() {
        override val scope get() = nativeSource.requested.request.scope
        override val actorId get() = nativeSource.requested.request.targetId
        override val definition get() = nativeSource.requested.definition
        override val admissions get() = nativeSource.requested.admissions
        override val originalCohostDeviceId get() = nativeSource.originalCohostDeviceId
        override val child get() = nativeSource.child
        override val frameId get() = nativeSource.frameId
        override val eventAt get() = nativeSource.evidence.eventAt
        override val seedImages get() = nativeSource.evidence.images
        override val admissionDigests get() = nativeSource.evidence.admissionDigests
        override val eventPurpose get() = RuntimeWritePurposeV2.PROVISIONING
    }

    private class DelegatedCreation(val completed: RuntimeDelegatedEmployeeCreationSeedJoin.Completed) : RuntimeEmployeeHistoryOrigin() {
        override val scope get() = completed.original.scope
        override val actorId get() = completed.original.actorId
        override val definition get() = completed.requested.definition
        override val admissions get() = completed.requested.admissions
        override val originalCohostDeviceId get() = completed.originalCohostDeviceId
        override val child get() = completed.final.child
        override val frameId get() = completed.header.transactionId
        override val eventAt get() = completed.eventAt
        override val seedImages get() = completed.seedImages
        override val admissionDigests get() = completed.admissionDigests
        override val eventPurpose get() = RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION
    }

    override fun toString(): String = "RuntimeEmployeeHistoryOrigin([REDACTED])"

    companion object {
        fun delegatedCreation(completed: RuntimeDelegatedEmployeeCreationSeedJoin.Completed): RuntimeEmployeeHistoryOrigin = DelegatedCreation(completed)
        fun nativeEnrollment(source: NativeEmployeeEnrollmentHistorySource): RuntimeEmployeeHistoryOrigin = CompanyHeadEnrollment(source)
    }
}
