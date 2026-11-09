package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision

/** Concrete supplier insert-only materializer beside the SAME actual native gate/history owner. */
public class LocalSupplierCashNativeOriginalImport : NativeOriginalImportInterpreter {
    override val domain: NativeOriginalDomain = NativeOriginalDomain.SUPPLIER_CASH_V1
    override fun project(native: NativeOriginalRetained): NativeOriginalProjection = LocalSupplierCashFacts.nativeProjection(native)
    override suspend fun prepare(view: CommandReadView, context: NativeOriginalImportContext): NativeOriginalImportPlan? {
        val native = context.native; val projection = project(native)
        if (view.scope != projection.scope) return null
        val requirement = LocalSupplierCashPhysicalProjection.parse(native.canonicalRequirement).requirement
        val slot = LocalSupplierCashFacts.slot(view, projection.recordingOperationId) ?: return null
        if (slot.value.requirement().canonicalBytes != requirement.canonicalBytes) return null
        requireSameSupplierOriginal(slot.start, context.historicalStart)
        requirePurchasingProducer(slot.start)
        PurchasingReader(view).supplierAttempt(slot.value.target.settlementId) ?: return null
        val certificate = LocalSupplierCashFacts.requireStartCertificate(native.certificate, context.historicalStart, requirement)
        val pair = LocalSupplierCashJournal.sourcePair(requirement, native.sourceRows)
        check(slot.value.masterSource == LocalSupplierCashSourceReference.from(pair.first) && slot.value.clientSource == LocalSupplierCashSourceReference.from(pair.second))
        for (source in native.sourceRows) {
            val row = view.aggregate(source.key) ?: return null
            check(row.revision == Revision(1) && row.schema == source.schema && row.payload == source.payload && row.digest == sha256(source.payload.copyBytes()))
            LocalSupplierCashFacts.requireRetainedRow(slot.start, row)
            check(certificate.sourceStamps.single { it.key == row.key }.let { it.schema == row.schema && it.generation == row.revision && it.payloadDigest == row.digest })
        }
        val operation = projection.recordingOperationId
        // The core recognizes complete existing rows FIRST. Never replace an advanced application.
        if (view.aggregate(LocalSupplierCashJournal.rawKey(operation)) != null || view.aggregate(LocalSupplierCashJournal.applicationKey(operation)) != null) return null
        val raw = LocalSupplierCashFacts.raw(slot.value, LocalSupplierCashPhase.ORIGINAL, requirement.originalActorId,
            native.originalBody, slot.row.digest, context.recordedAt)
        return NativeOriginalImportPlan(domain, operation, requirement.originalActorId, requirement.command, native.originalBody,
            raw.semanticDigest, projection.reservedBinding, LocalSupplierCashFacts.rawWrite(raw),
            LocalSupplierCashFacts.applicationWrite(LocalSupplierCashFacts.initial(raw), null),
            LocalSupplierCashCodec.result(LocalSupplierCashResult.RawRetained(slot.value.target)))
    }
    override suspend fun verifyExisting(view: CommandReadView, context: NativeOriginalImportContext, original: RetainedCommandEvidence): Boolean {
        val projection = project(context.native)
        if (view.scope != projection.scope || original.scope != view.scope || original.command != domain.command ||
            original.operationId != projection.recordingOperationId || original.originalActorId != projection.originalActorId || original.normalized != context.native.originalBody) return false
        val current = view.retainedCommand(original.operationId) ?: return false
        requireSameSupplierOriginal(current, original)
        val fact = supplierCashFact(view, original.operationId, LocalSupplierCashPhase.ORIGINAL) ?: return false
        if (fact.slot.value.requirement().canonicalBytes != context.native.canonicalRequirement || fact.value.body() != context.native.originalBody) return false
        requireSameSupplierOriginal(fact.slot.start, context.historicalStart)
        LocalSupplierCashFacts.requireStartCertificate(context.native.certificate, context.historicalStart, fact.slot.value.requirement())
        LocalSupplierCashJournal.sourcePair(fact.slot.value.requirement(), context.native.sourceRows)
        check(LocalSupplierCashFacts.requireRaw(original, context.historicalStart, LocalSupplierCashPhase.ORIGINAL) == fact.value)
        original.nativeOriginalProvenance?.let {
            LocalSupplierCashFacts.requireNativeOriginalEvidence(original, context.historicalStart, fact.slot.value)
            check(it.nativeOriginal.certificateDigest == sha256(context.native.certificate.copyBytes()))
        }
        // Complete retained finance/application lineage was read; no money/native replay or grant.
        return true
    }
}

internal fun requireSameSupplierOriginal(actual: RetainedCommandEvidence, expected: RetainedCommandEvidence) {
    check(actual.scope == expected.scope && actual.operationId == expected.operationId && actual.originalActorId == expected.originalActorId &&
        actual.command == expected.command && actual.assignmentId == expected.assignmentId && actual.authorityEpoch == expected.authorityEpoch &&
        actual.sequence == expected.sequence && actual.recordedAt == expected.recordedAt && actual.semanticDigest == expected.semanticDigest &&
        actual.committed == expected.committed && actual.code == expected.code && actual.normalized == expected.normalized && actual.result == expected.result && actual.resultHash == expected.resultHash &&
        actual.previousJournalHash == expected.previousJournalHash && actual.journalHash == expected.journalHash && actual.nativeOriginalProvenance == expected.nativeOriginalProvenance &&
        actual.aggregateWrites.size == expected.aggregateWrites.size && actual.aggregateWrites.zip(expected.aggregateWrites).all { (a, b) ->
            a.key == b.key && a.storageGeneration == b.storageGeneration && a.schema == b.schema && a.payload == b.payload && a.payloadDigest == b.payloadDigest } &&
        actual.claimChanges.size == expected.claimChanges.size && actual.claimChanges.zip(expected.claimChanges).all { (a, b) ->
            a.key == b.key && a.ownerId == b.ownerId && a.action == b.action } && actual.jobs.size == expected.jobs.size &&
        actual.jobs.zip(expected.jobs).all { (a, b) -> a.kind == b.kind && a.body == b.body })
    if (actual.nativeOriginalProvenance == null) check(actual.authenticationId == expected.authenticationId && actual.policyRevision == expected.policyRevision)
}
