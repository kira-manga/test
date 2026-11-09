package com.ukkera.pos.android.independent.nativesupplierinvoicecapturev1

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.android.independent.nativefixture.requireSameNativeFixtureCoordinates
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.PurchaseInvoiceCaptureV2
import com.ukkera.pos.inventory.PurchaseOrderV1
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** Supplementary finite observations from the actually closed original native database.
 * This is NOT a history-admission verifier: subsequent normal production opens must still
 * admit the complete original history. No SQL, database fixture, proof injection, protected
 * plaintext retention, census shortcut to writer admission or guessed revision is used. */
internal class NativeInvoiceCaptureCensus private constructor(
    private val rows: Map<NativeHistoryTableV2, List<CaptureCensusRow>>,
    private val counts: Map<NativeHistoryTableV2, Long>,
    val epoch: Long,
    val branchRevision: Long,
) : AutoCloseable {
    private val branch: CaptureCensusRow get() = rows.getValue(NativeHistoryTableV2.BRANCH).single()
    private fun table(value: NativeHistoryTableV2): List<CaptureCensusRow> = rows.getValue(value)
    private fun aggregate(kind: String, id: DomainId): CaptureCensusRow = table(NativeHistoryTableV2.AGGREGATE)
        .single { it.text("kind") == kind && it.text("id") == id.value }

    /** Full business BASE, not the supplier-only empty-business fixture. Native originals
     * must equal the separately authenticated setup operations actually returned by the Master.
     * The complete physical cut additionally proves no invoice/financial/approval documents. */
    fun requireBase(base: NativeInvoiceCaptureBase) {
        val originals = base.operations
        check(originals.isNotEmpty() && originals.map { it.request.operationId }.distinct().size == originals.size)
        val ids = originals.map { it.request.operationId.value }.toSet()
        check(table(NativeHistoryTableV2.OPERATION).map { it.text("operationId") }.toSet() == ids &&
            table(NativeHistoryTableV2.OPERATION).size == ids.size)
        check(table(NativeHistoryTableV2.JOURNAL).size == ids.size)
        check(table(NativeHistoryTableV2.OUTBOX).all { it.text("operationId") in ids } &&
            table(NativeHistoryTableV2.JOURNAL_FRAGMENT).all { it.text("operationId") in ids })
        originals.forEach { observed ->
            check(observed.session.actorId == base.fixtureActorId && observed.session.scope == base.coordinates.scope)
            requireObservedOriginal(observed.request, observed.identity, observed.session, observed.result)
        }
        check(table(NativeHistoryTableV2.AUDIT).filter { it.text("kind").startsWith("command.") }
            .all { it.text("operationId") in ids })
        requireJournalHead()
        requireNoFinancialDocuments()
        check(table(NativeHistoryTableV2.AGGREGATE).none { it.text("kind") == LocalPurchasingProfile.INVOICE })
        check(counts.getValue(NativeHistoryTableV2.APPROVAL) == 0L)

        for ((kind, expected) in listOf(
            LocalConfigurationProfile.SUPPLIER to setOf(base.supplierId.value),
            LocalConfigurationProfile.WAREHOUSE to setOf(base.warehouseId.value),
            LocalConfigurationProfile.SKU to setOf(base.a.definition.skuId.value, base.b.definition.skuId.value, base.c.definition.skuId.value),
            LocalConfigurationProfile.PUBLICATION to base.publications.map { it.publicationId.value }.toSet(),
            LocalPurchasingProfile.ORDER to setOf(base.po.id.value),
        )) check(table(NativeHistoryTableV2.AGGREGATE).filter { it.text("kind") == kind }
            .map { it.text("id") }.toSet() == expected)
        for (sku in listOf(base.a, base.b, base.c)) {
            val native = aggregate(LocalConfigurationProfile.SKU, sku.definition.skuId)
            check(native.integer("revision") == sku.item.storageRevision.value)
            val record = JSONObject(native.textBytes("payload"))
            check(record.getString("revision") == sku.item.revision.value.toString())
            val actual = captureCensusJson.decodeFromString(LocalPublishedSkuV1.serializer(),
                record.getJSONObject("body").getJSONObject("value").getJSONObject("definition").toString())
            check(actual == sku.definition)
            val publication = aggregate(LocalConfigurationProfile.PUBLICATION, actual.publicationId)
            check(publication.text("payloadHash") == sha256(publication.bytes("payload")).value)
        }
        val supplier = aggregate(LocalConfigurationProfile.SUPPLIER, base.supplierId)
        check(supplier.integer("revision") == base.supplierStorageRevision.value)
        val nativeSupplier = captureCensusJson.decodeFromString(LocalSupplierV1.serializer(),
            JSONObject(supplier.textBytes("payload")).getJSONObject("body").getJSONObject("value").toString())
        check(nativeSupplier == (base.snapshot.supplier.detail as LocalConfigurationDetailV1.Supplier).value)
        requireOrder(base)
        val receipts = table(NativeHistoryTableV2.AGGREGATE).filter { it.text("kind") == LocalStockStoreProfile.RECEIPT_DOCUMENT }
        check(receipts.size == 1 && receipts.single().text("id") == base.openingReceiptId.value)
        // Stock record body is a JSON string, not the purchasing tagged-object format.
        val opening = JSONObject(JSONObject(receipts.single().textBytes("payload")).getString("body"))
        check(opening.getString("basisKind") == "OPENING" && opening.isNull("supplierId") &&
            opening.getString("basisId") != base.po.id.value)
        check(table(NativeHistoryTableV2.AGGREGATE).none {
            it.text("kind") == LocalStockStoreProfile.ALLOWANCE && it.text("id") == base.originalOrder.lines.single().allocationId.value
        }) { "The tested PO has received custody before capture" }
    }

    /** Exactly one documentary capture, preserving every prior aggregate/claim/stock/value/
     * price/cash/PO row, result, journal, fragment and upload. Read/authentication histories
     * may grow through the genuine protected APIs; they are not business mutations. */
    fun requireCaptureDelta(before: NativeInvoiceCaptureCensus, base: NativeInvoiceCaptureBase,
        original: NativeInvoiceCaptureOriginal) {
        before.requireBase(base)
        val request = checkNotNull(original.request)
        val result = checkNotNull(original.result)
        val identity = checkNotNull(original.identity)
        val session = checkNotNull(original.session)
        val invoice = original.expected.invoice
        val returned = LocalPurchasingCodec.captureResult(result.result)
        check(result.committed && result.code == "committed" && returned.code == "purchasing.invoice_captured" &&
            returned.id == invoice.id && returned.revision != null)
        check(epoch == before.epoch && branchRevision > before.branchRevision)
        requireUnchangedBranchIdentity(before)
        for (table in retainedTables - setOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.AUDIT))
            requirePreserved(before, table)
        fun delta(table: NativeHistoryTableV2): List<CaptureCensusRow> = added(before, table)
        check(delta(NativeHistoryTableV2.OPERATION).single().text("operationId") == original.operationId.value)
        check(delta(NativeHistoryTableV2.JOURNAL).single().text("operationId") == original.operationId.value)
        check(delta(NativeHistoryTableV2.OUTBOX).single().text("operationId") == original.operationId.value)
        check(delta(NativeHistoryTableV2.AGGREGATE).single().let {
            it.text("kind") == LocalPurchasingProfile.INVOICE && it.text("id") == invoice.id.value
        })
        val claims = delta(NativeHistoryTableV2.CLAIM)
        val expectedClaims = captureClaimKeys(base.coordinates.scope, invoice)
        check(claims.size == 2 && claims.map { it.text("namespace") to it.text("key") }.toSet() == expectedClaims &&
            claims.all { it.text("ownerId") == invoice.id.value })
        for (table in stockTables + NativeHistoryTableV2.JOURNAL_FRAGMENT) check(delta(table).isEmpty())
        requirePreserved(before, NativeHistoryTableV2.AUDIT)
        val commandAudits = delta(NativeHistoryTableV2.AUDIT).filter { it.text("kind").startsWith("command.") }
        check(commandAudits.size == 1 && commandAudits.single().text("operationId") == original.operationId.value &&
            commandAudits.single().text("kind") == "command.committed")
        requireNonCommandAudits(delta(NativeHistoryTableV2.AUDIT).filterNot { it in commandAudits })
        requireNoFinancialDocuments()
        check(counts.getValue(NativeHistoryTableV2.APPROVAL) == 0L)
        val journal = requireObservedOriginal(request, identity, session, result)
        check(journal.text("previousHash") == before.branch.text("journalHash") &&
            branch.integer("sequence") == result.sequence.value && branch.text("journalHash") == journal.text("hash"))
        requireCapturedRecord(before, base, original, returned.revision!!.value)
        captureWithFrames(journal.bytes("envelope")) { fields ->
            check(fields.size == 20 && fields[0].text() == "ukkera.journal/v1")
            val effects = fields[18].frames().map { it.frames() }
            // The v1 effect section contains one aggregate AND two claim.acquire records.
            check(effects.size == 3 && effects.count { it.size == 5 } == 1 && effects.count { it.size == 4 } == 2)
            val write = effects.single { it.size == 5 }
            val row = aggregate(LocalPurchasingProfile.INVOICE, invoice.id)
            check(write[0].text() == LocalPurchasingProfile.INVOICE && write[1].text() == invoice.id.value &&
                write[2].text() == returned.revision!!.value.toString() && write[3].text() == row.text("schema") &&
                write[4].equalsBytes(row.bytes("payload")))
            val framedClaims = effects.filter { it.size == 4 }
            check(framedClaims.all { it[0].text() == "claim.acquire" && it[3].text() == invoice.id.value } &&
                framedClaims.map { it[1].text() to it[2].text() }.toSet() == expectedClaims)
            check(fields[19].frames().isEmpty()) { "Capture created an unrelated job" }
        }
        requireJournalHead()
    }

    /** Fresh explicit login and original lookup are not a new capture or a re-posting. */
    fun requireSameBusiness(after: NativeInvoiceCaptureCensus) {
        check(epoch == after.epoch && branch.fingerprint() == after.branch.fingerprint())
        for (table in retainedTables - setOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.AUDIT))
            check(index(table).mapValues { it.value.fingerprint() } == after.index(table).mapValues { it.value.fingerprint() }) {
                "Original native business evidence changed after reopen"
            }
        after.requirePreserved(this, NativeHistoryTableV2.AUDIT)
        after.requireNonCommandAudits(after.added(this, NativeHistoryTableV2.AUDIT))
        check(counts.getValue(NativeHistoryTableV2.APPROVAL) == 0L && after.counts.getValue(NativeHistoryTableV2.APPROVAL) == 0L)
        after.requireNoFinancialDocuments()
        after.requireJournalHead()
    }

    private fun requireUnchangedBranchIdentity(before: NativeInvoiceCaptureCensus) {
        check(listOf("scope", "assignmentId", "deviceId", "writable", "recoveryJson", "epoch")
            .all { branch.cellFingerprint(it) == before.branch.cellFingerprint(it) })
    }

    private fun requireNoFinancialDocuments() {
        val forbidden = setOf(LocalPurchasingProfile.PROGRESS, LocalPurchasingProfile.MATCH,
            LocalPurchasingProfile.RECEIPT_BILLED, LocalPurchasingProfile.PAYABLE,
            LocalPurchasingProfile.CREDIT, LocalPurchasingProfile.INVOICE_CREDITS) + LocalSupplierSettlementProfile.kinds
        check(table(NativeHistoryTableV2.AGGREGATE).none { it.text("kind") in forbidden }) {
            "The native cut contains a matching, credit, advance or settlement document"
        }
    }

    private fun requireOrder(base: NativeInvoiceCaptureBase) {
        val row = aggregate(LocalPurchasingProfile.ORDER, base.po.id)
        check(row.integer("revision") == base.po.revision.value)
        val record = JSONObject(row.textBytes("payload"))
        val body = record.getJSONObject("body")
        check(body.getString("type") == "ORDER" && record.getString("revision") == base.po.revision.value.toString())
        check(captureCensusJson.decodeFromString(PurchaseOrderV1.serializer(), body.getJSONObject("value").toString()) == base.originalOrder)
        val sources = body.getJSONArray("sources").objects()
        check(sources.size == 3 && sources.map { it.getString("kind") to it.getString("id") }.toSet() == setOf(
            LocalConfigurationProfile.SUPPLIER to base.supplierId.value,
            LocalConfigurationProfile.WAREHOUSE to base.warehouseId.value,
            LocalConfigurationProfile.SKU to base.a.definition.skuId.value))
        sources.forEach(::requireSource)
        val allocation = base.originalOrder.lines.single().allocationId
        val billed = JSONObject(aggregate(LocalPurchasingProfile.ORDER_BILLED, allocation).textBytes("payload")).getJSONObject("body")
        check(billed.getString("type") == "ORDER_BILLED" && billed.getString("orderId") == base.po.id.value &&
            billed.getString("allocationId") == allocation.value && billed.getJSONArray("matchIds").length() == 0)
        check(captureCensusJson.decodeFromString(Quantity.serializer(), billed.getJSONObject("quantity").toString()) ==
            Quantity(base.eaUnitId, 0, 0))
        for (field in listOf("net", "tax")) check(captureCensusJson.decodeFromString(Money.serializer(),
            billed.getJSONObject(field).toString()) == Money(Currency.EGP, 0))
        val matches = JSONObject(aggregate(LocalPurchasingProfile.ORDER_MATCHES, base.po.id).textBytes("payload")).getJSONObject("body")
        check(matches.getString("type") == "ORDER_MATCHES" && matches.getString("orderId") == base.po.id.value &&
            matches.getJSONArray("matchIds").length() == 0)
    }

    private fun requireCapturedRecord(before: NativeInvoiceCaptureCensus, base: NativeInvoiceCaptureBase,
        original: NativeInvoiceCaptureOriginal, revision: Long) {
        val expected = original.expected.invoice
        val result = checkNotNull(original.result)
        val session = checkNotNull(original.session)
        val identity = checkNotNull(original.identity)
        val row = aggregate(LocalPurchasingProfile.INVOICE, expected.id)
        check(row.integer("revision") == revision && row.text("schema") == LocalPurchasingProfile.schema(LocalPurchasingProfile.INVOICE) &&
            row.text("payloadHash") == sha256(row.bytes("payload")).value)
        val record = JSONObject(row.textBytes("payload"))
        val scope = record.getJSONObject("scope")
        check(scope.getString("organizationId") == base.coordinates.scope.organizationId.value &&
            scope.getString("companyId") == base.coordinates.scope.companyId.value &&
            scope.getString("branchId") == base.coordinates.scope.branchId.value &&
            record.getString("kind") == LocalPurchasingProfile.INVOICE && record.getString("id") == expected.id.value &&
            record.getString("revision") == revision.toString() && record.getString("recordedAt") == result.recordedAt.canonicalString() &&
            record.getString("policyRevision") == session.policy.revision.value.toString())
        val producer = record.getJSONObject("producer")
        check(producer.getString("operationId") == original.operationId.value && producer.getString("actorId") == session.actorId.value &&
            producer.getString("command") == LocalPurchasingProfile.CAPTURE_INVOICE.name && producer.getString("digest") == identity.semanticSha256)
        val body = record.getJSONObject("body")
        check(body.getString("type") == "INVOICE_CAPTURE_V2" && body.isNull("orderSource") && body.isNull("orderOperationId") &&
            body.getString("normalizedReference") == normalizedPurchaseReference(expected.reference))
        val persisted = captureCensusJson.decodeFromString(PurchaseInvoiceCaptureV2.serializer(), body.getJSONObject("value").toString())
        check(persisted == expected)
        check(body.getJSONArray("discrepancies").length() == 1 && body.getJSONArray("discrepancies").getString(0) == "ORDER_LINE_REQUIRED")
        val supplier = body.getJSONObject("supplierSource")
        before.requireSource(supplier)
        check(supplier.getString("kind") == LocalConfigurationProfile.SUPPLIER && supplier.getString("id") == base.supplierId.value &&
            supplier.getString("revision") == original.expected.expectedSupplierRevision.value.toString())
        val sources = body.getJSONArray("catalogSources").objects()
        check(sources.size == 2 && sources.map { it.getJSONObject("skuSource").getString("id") }.toSet() ==
            setOf(base.b.definition.skuId.value, base.c.definition.skuId.value))
        for (source in sources) {
            val skuSource = source.getJSONObject("skuSource")
            val publicationSource = source.getJSONObject("publicationSource")
            before.requireSource(skuSource); before.requireSource(publicationSource)
            val sku = listOf(base.b, base.c).single { it.definition.skuId.value == skuSource.getString("id") }
            check(skuSource.getString("kind") == LocalConfigurationProfile.SKU &&
                publicationSource.getString("kind") == LocalConfigurationProfile.PUBLICATION &&
                publicationSource.getString("id") == sku.definition.publicationId.value)
            val units = source.getJSONArray("unitSources").objects()
            val goods = expected.lines.single { it.goods.skuId == sku.definition.skuId }.goods
            check(units.size == 2 && units.map { it.getString("unitId") }.toSet() ==
                setOf(goods.units.purchaseUnitId.value, goods.units.baseUnitId.value))
            for (unit in units) {
                val stamp = unit.getJSONObject("publicationSource")
                before.requireSource(stamp)
                check(stamp.getString("kind") == LocalConfigurationProfile.PUBLICATION)
                val carrier = listOf(base.a, base.b, base.c).single { it.definition.publicationId.value == stamp.getString("id") }
                val actual = checkNotNull(carrier.definition.unitDefinitions).single { it.id.value == unit.getString("unitId") }
                check(actual == checkNotNull(sku.detail.unitDefinitions).single { it.id == actual.id })
            }
        }
    }

    private fun requireSource(source: JSONObject) {
        val row = aggregate(source.getString("kind"), DomainId(source.getString("id")))
        check(source.getString("revision") == row.integer("revision").toString() &&
            source.getString("digest") == row.text("payloadHash") && row.text("payloadHash") == sha256(row.bytes("payload")).value)
    }

    private fun requireObservedOriginal(request: MutationRequest, identity: OperationIdentity,
        session: LocalSessionView, result: MutationReply.Recorded): CaptureCensusRow {
        check(result.operationId == request.operationId && result.originalActorId == session.actorId && result.physicalStart == null &&
            identity.operationId == request.operationId && identity.branchId == session.scope.branchId &&
            identity.originalActorId == session.actorId && identity.semanticDigestVersion == 1 &&
            identity.semanticSha256 == semanticDigest(session.scope, session.actorId, request.command, request.payload).value)
        val operation = table(NativeHistoryTableV2.OPERATION).single { it.text("operationId") == request.operationId.value }
        check(operation.text("scope") == captureScope(session.scope) && operation.text("actorId") == session.actorId.value &&
            operation.text("command") == request.command.name && operation.integer("commandVersion") == request.command.version.toLong() &&
            operation.text("digest") == identity.semanticSha256 && operation.text("outcome") == result.code &&
            operation.integer("sequence") == result.sequence.value && operation.integer("epoch") == result.authorityEpoch.value &&
            operation.text("authenticationId") == session.sessionId.value && operation.integer("policyRevision") == session.policy.revision.value &&
            operation.text("committedAt") == result.recordedAt.canonicalString() && operation.equalsStored("result", result.result) &&
            operation.text("resultHash") == sha256(operation.bytes("result")).value)
        val journal = table(NativeHistoryTableV2.JOURNAL).single { it.text("operationId") == request.operationId.value }
        check(journal.text("scope") == operation.text("scope") && journal.integer("sequence") == result.sequence.value &&
            journal.integer("epoch") == result.authorityEpoch.value && journal.integer("epoch") == epoch &&
            journal.text("assignmentId") == branch.text("assignmentId") && journal.text("hash") == sha256(journal.bytes("envelope")).value)
        val audits = table(NativeHistoryTableV2.AUDIT).filter { it.text("operationId") == request.operationId.value &&
            it.text("kind") == "command.${result.code}" }
        check(audits.size == 1 && audits.single().let {
            it.text("scope") == operation.text("scope") && it.text("actorId") == session.actorId.value &&
                it.text("originalActorId") == session.actorId.value && it.text("digest") == identity.semanticSha256 &&
                it.integer("policyRevision") == session.policy.revision.value && it.text("recordedAt") == result.recordedAt.canonicalString()
        })
        val obligations = table(NativeHistoryTableV2.OUTBOX).filter { it.text("operationId") == request.operationId.value }.sortedBy { it.integer("ordinal") }
        check(obligations.isNotEmpty() && obligations.map { it.integer("ordinal") } == obligations.indices.map(Int::toLong))
        obligations.forEach { check(it.text("scope") == operation.text("scope") && it.text("bodyHash") == sha256(it.bytes("body")).value) }
        val upload = obligations.first()
        check(upload.text("kind") == "journal.upload")
        captureWithFrames(upload.bytes("body")) { values -> check(values.map { it.text() } == listOf(
            "ukkera.journal-upload/v1", session.scope.branchId.value, result.authorityEpoch.value.toString(),
            result.sequence.value.toString(), journal.text("hash"))) }
        val tag = CaptureBinaryReader(journal.bytes("envelope")).text(48)
        when (tag) {
            "ukkera.journal/v1" -> captureWithFrames(journal.bytes("envelope")) { fields ->
                check(fields.size == 20)
                val metadata = listOf(tag, operation.text("scope"), journal.text("assignmentId"), result.authorityEpoch.value.toString(),
                    result.sequence.value.toString(), request.operationId.value, session.actorId.value, request.command.name,
                    request.command.version.toString(), session.sessionId.value, session.policy.revision.value.toString(), identity.semanticSha256,
                    result.code, result.recordedAt.canonicalString(), operation.text("resultHash"), journal.text("previousHash"))
                check(fields.take(16).map { it.text() } == metadata && fields[16].equalsStored(request.payload) && fields[17].equalsStored(result.result))
                val jobs = fields[19].frames().map { it.frames() }
                check(jobs.size == obligations.size - 1)
                jobs.forEachIndexed { i, values -> check(values.size == 2 && values[0].text() == obligations[i + 1].text("kind") &&
                    values[1].equalsBytes(obligations[i + 1].bytes("body"))) }
                check(table(NativeHistoryTableV2.JOURNAL_FRAGMENT).none { it.text("operationId") == request.operationId.value })
            }
            "ukkera.journal/root/v2" -> requireStreamedOriginal(journal, operation, request, session, obligations)
            else -> error("Unsupported original native journal format; no empty history is substituted")
        }
        return journal
    }

    /** Literal bounded root/fragment linkage for existing setup stock originals. The normal
     * production scanner still owns complete typed effects/source/lineage admission. */
    private fun requireStreamedOriginal(journal: CaptureCensusRow, operation: CaptureCensusRow,
        request: MutationRequest, session: LocalSessionView, outbox: List<CaptureCensusRow>) {
        val reader = CaptureBinaryReader(journal.bytes("envelope"))
        check(reader.text(48) == "ukkera.journal/root/v2" && reader.text(36) == session.scope.organizationId.value &&
            reader.text(36) == session.scope.companyId.value && reader.text(36) == session.scope.branchId.value &&
            reader.text(36) == request.operationId.value && reader.text(96) == request.command.name &&
            reader.int() == request.command.version && reader.text(36) == session.actorId.value && reader.text(64) == operation.text("digest") &&
            reader.text(36) == journal.text("assignmentId") && reader.long() == journal.integer("epoch") &&
            reader.long() == journal.integer("sequence") && reader.text(30) == operation.text("committedAt") &&
            reader.text(36) == session.sessionId.value && reader.long() == session.policy.revision.value &&
            reader.text(64) == operation.text("resultHash") && reader.text(64) == journal.text("previousHash"))
        val fragmentCount = reader.long()
        val streamBytes = reader.long()
        val commitment = reader.text(64)
        val sectionCounts = LongArray(8)
        val sectionBytes = LongArray(8)
        repeat(8) { sectionCounts[it] = reader.long(); sectionBytes[it] = reader.long() }
        reader.end()
        check(fragmentCount in 1..MAX_ROWS.toLong() && streamBytes in 1..MAX_RETAINED_BYTES &&
            sectionCounts[0] == 1L && sectionCounts[1] == 1L && sectionCounts.all { it >= 0 } && sectionBytes.all { it >= 0 })
        val fragments = table(NativeHistoryTableV2.JOURNAL_FRAGMENT).filter { it.text("operationId") == request.operationId.value }
            .sortedBy { it.integer("ordinal") }
        check(fragments.size.toLong() == fragmentCount && fragments.sumOf { it.integer("byteCount") } == streamBytes)
        val stream = ByteArray(streamBytes.toInt())
        try {
            var offset = 0
            var chain = captureTextHash("ukkera.journal/fragment-chain/v2")
            fragments.forEachIndexed { ordinal, fragment ->
                val bytes = fragment.bytes("payload")
                check(fragment.integer("ordinal") == ordinal.toLong() && bytes.size.toLong() == fragment.integer("byteCount") &&
                    bytes.size in 1..49_152 && (ordinal == fragments.lastIndex || bytes.size == 49_152) &&
                    fragment.text("payloadHash") == sha256(bytes).value)
                bytes.copyInto(stream, offset); offset += bytes.size
                chain = captureBinaryHash { data ->
                    data.captureText("ukkera.journal/fragment-link/v2"); data.captureText(chain)
                    data.writeLong(ordinal.toLong()); data.writeInt(bytes.size); data.captureText(fragment.text("payloadHash"))
                }
            }
            check(offset == stream.size && captureBinaryHash { data ->
                data.captureText("ukkera.journal/fragment-finish/v2"); data.captureText(chain)
                data.writeLong(fragmentCount); data.writeLong(streamBytes)
            } == commitment)
            val observedCounts = LongArray(8)
            val observedBytes = LongArray(8)
            var prior = 1
            for (frame in CaptureSlice(stream).frames()) {
                val fields = CaptureBinaryReader(frame)
                check(fields.text(48) == "ukkera.journal/frame/v2")
                val section = fields.int()
                check(section in prior..8); prior = section
                observedCounts[section - 1]++
                observedBytes[section - 1] += frame.size + 4L
                when (section) {
                    1 -> { check(fields.blob(65_536).equalsStored(request.payload)); fields.end() }
                    2 -> { check(fields.blob(65_536).equalsBytes(operation.bytes("result"))); fields.end() }
                    5 -> {
                        val ordinal = fields.long()
                        check(ordinal in 1L until outbox.size.toLong() && fields.text(96) == outbox[ordinal.toInt()].text("kind") &&
                            fields.blob(65_536).equalsBytes(outbox[ordinal.toInt()].bytes("body")))
                        fields.end()
                    }
                    else -> Unit // These are NOT admitted/reconstructed source or effect DTOs here.
                }
            }
            check(observedCounts.contentEquals(sectionCounts) && observedBytes.contentEquals(sectionBytes) &&
                sectionCounts[4] == (outbox.size - 1).toLong())
        } finally { stream.fill(0) }
    }

    private fun requireJournalHead() {
        val journals = table(NativeHistoryTableV2.JOURNAL).sortedBy { it.integer("sequence") }
        check(journals.isNotEmpty() && journals.all { it.integer("epoch") == epoch })
        check(journals.zipWithNext().all { (a, b) -> b.integer("sequence") > a.integer("sequence") && b.text("previousHash") == a.text("hash") })
        check(branch.integer("sequence") == journals.last().integer("sequence") && branch.text("journalHash") == journals.last().text("hash"))
    }

    private fun requireNonCommandAudits(values: List<CaptureCensusRow>) {
        check(values.all { audit -> audit.text("kind") == "result.supervisor-read" &&
            table(NativeHistoryTableV2.OPERATION).any { it.text("operationId") == audit.text("operationId") &&
                it.text("actorId") == audit.text("originalActorId") } }) { "Recovery introduced an unrelated durable audit" }
    }

    private fun index(table: NativeHistoryTableV2): Map<String, CaptureCensusRow> = table(table).associateBy { it.key(table) }
        .also { check(it.size == table(table).size) }
    private fun requirePreserved(before: NativeInvoiceCaptureCensus, table: NativeHistoryTableV2) {
        val after = index(table)
        check(before.index(table).all { (key, value) -> after[key]?.fingerprint() == value.fingerprint() }) {
            "A baseline native row was deleted or rewritten"
        }
    }
    private fun added(before: NativeInvoiceCaptureCensus, table: NativeHistoryTableV2): List<CaptureCensusRow> {
        val prior = before.index(table)
        return index(table).filterKeys { it !in prior }.values.toList()
    }

    override fun close() { rows.values.flatten().forEach(CaptureCensusRow::clear) }

    companion object {
        private const val MAX_ROWS = 4_096
        private const val MAX_RETAINED_BYTES = 32L * 1024 * 1024
        private val stockTables = setOf(NativeHistoryTableV2.STOCK_POSITION, NativeHistoryTableV2.STOCK_ALLOCATION,
            NativeHistoryTableV2.STOCK_RESERVATION, NativeHistoryTableV2.STOCK_VALUE)
        private val retainedTables = setOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.OPERATION,
            NativeHistoryTableV2.AGGREGATE, NativeHistoryTableV2.CLAIM, NativeHistoryTableV2.JOURNAL,
            NativeHistoryTableV2.AUDIT, NativeHistoryTableV2.OUTBOX, NativeHistoryTableV2.JOURNAL_FRAGMENT) + stockTables

        suspend fun read(custody: SelfOwnedNativeHistoryCustody, coordinates: SelfOwnedAllocationCoordinatesV1,
            label: String, evidence: NativeLocalFixtureEvidence): NativeInvoiceCaptureCensus = custody.runRoomWork(block = {
            val read = custody.openOriginalReadOnly()
            val rows = retainedTables.associateWith { mutableListOf<CaptureCensusRow>() }
            val pending = mutableListOf<ByteArray>()
            var closeStarted = false
            var primary: Throwable? = null
            var delivered = false
            try {
                requireSameNativeFixtureCoordinates(coordinates, read.original.record.allocation.coordinates)
                val counts = NativeHistoryTableV2.entries.associateWith { 0L }.toMutableMap()
                val complete = mutableSetOf<NativeHistoryTableV2>()
                var retainedBytes = 0L
                while (read.readSchemaPage() != null) { /* Every schema page is consumed, not accepted as a verifier. */ }
                while (true) {
                    val metadata = read.nextRowMetadata() ?: break
                    val table = metadata.table
                    val census = metadata.pass == NativeHistoryReadPassV2.CENSUS
                    if (census && metadata.kind == NativeHistoryRowKindV2.PASS_END) check(complete.add(checkNotNull(table)))
                    val keep = census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL && table in retainedTables
                    val cells = linkedMapOf<String, CaptureCensusCell>()
                    for ((index, cell) in metadata.cells.withIndex()) {
                        check(cell.byteCount in 0..NativeHistoryPhysicalReadProfileV2.MAX_CELL_BYTES)
                        if (keep) {
                            check(cell.byteCount <= MAX_RETAINED_BYTES - retainedBytes) { "The bounded native business cut is oversized" }
                            retainedBytes += cell.byteCount
                        }
                        val kept = if (keep) ByteArray(cell.byteCount.toInt()).also(pending::add) else null
                        var offset = 0L
                        do {
                            val chunk = read.readCellChunk()
                            try {
                                val bytes = chunk.copyBytes()
                                try {
                                    check(chunk.column == index && chunk.offset == offset && chunk.totalBytes == cell.byteCount &&
                                        chunk.integerValue == cell.integerValue && offset + bytes.size <= cell.byteCount &&
                                        bytes.size <= NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES)
                                    kept?.let { bytes.copyInto(it, offset.toInt()) }
                                    offset += bytes.size
                                    check(chunk.last == (offset == cell.byteCount))
                                    if (chunk.last) break
                                    check(bytes.isNotEmpty())
                                } finally { bytes.fill(0) }
                            } finally { chunk.clear() }
                        } while (true)
                        check(offset == cell.byteCount)
                        if (keep) {
                            check(cells.put(cell.name, CaptureCensusCell(cell.type, cell.integerValue, checkNotNull(kept))) == null)
                        }
                    }
                    if (census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL) {
                        val actual = checkNotNull(table)
                        counts[actual] = counts.getValue(actual) + 1
                        check(counts.getValue(actual) <= MAX_ROWS) { "The bounded native census is oversized; no rows are truncated" }
                        if (keep) {
                            rows.getValue(actual) += CaptureCensusRow(cells)
                            // Transfer byte ownership only once the complete row is retained.
                            // A later cell failure must still wipe every unfinished row cell.
                            cells.values.forEach { pending.remove(it.bytes) }
                        }
                    }
                }
                check(complete.containsAll(NativeHistoryPhysicalReadProfileV2.applicationTables))
                closeStarted = true
                val closed = withContext(NonCancellable) { read.closeOriginalRead() }
                val after = custody.reobserveAfterActualClose(closed)
                check(after.lifetimeId == read.original.lifetimeId && after.recordDigest == read.original.recordDigest &&
                    after.record.database == read.original.record.database)
                custody.completeOriginalReadDelivery(after)
                val branch = rows.getValue(NativeHistoryTableV2.BRANCH).single()
                check(branch.text("scope") == captureScope(coordinates.scope) && branch.text("assignmentId") == coordinates.assignmentId.value &&
                    branch.text("deviceId") == coordinates.deviceId.value)
                rows.values.flatten().forEach { check(it.text("scope") == branch.text("scope")) }
                val result = NativeInvoiceCaptureCensus(rows.mapValues { it.value.toList() }, counts.toMap(),
                    branch.integer("epoch"), branch.integer("revision"))
                evidence.event(label, JSONObject().put("nativeReadActualClose", true)
                    .put("completeApplicationTableCensus", true).put("retainedBusinessBytes", retainedBytes)
                    .put("authorityEpoch", result.epoch).put("branchRevision", result.branchRevision)
                    .put("physicalCounts", JSONObject().also { json -> counts.filterKeys { it.application }.forEach { (table, count) -> json.put(table.name, count) } })
                    .put("businessFingerprints", JSONObject().also { json -> retainedTables.forEach { table ->
                        json.put(table.name, JSONArray(rows.getValue(table).map { it.fingerprint() }.sorted()))
                    } })
                    .put("fullProductionHistoryAdmission", "NOT_CLAIMED_BY_CENSUS"))
                delivered = true
                result
            } catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                pending.forEach { it.fill(0) }
                if (!delivered) rows.values.flatten().forEach(CaptureCensusRow::clear)
                if (!closeStarted) try { withContext(NonCancellable) { read.closeOriginalRead() } }
                catch (cleanup: Throwable) {
                    val failure = primary
                    if (failure == null) throw cleanup
                    if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }, discard = { census ->
            try { custody.denyDataUse() } finally { census.close() }
        })
    }
}

private class CaptureCensusCell(val type: NativeHistorySqliteTypeV2, val integer: Long?, val bytes: ByteArray) {
    fun fingerprint(): String = "${type.name}:${integer ?: "-"}:${sha256(bytes).value}"
}
private class CaptureCensusRow(private val cells: Map<String, CaptureCensusCell>) {
    fun text(name: String): String = cells.getValue(name).let { check(it.type == NativeHistorySqliteTypeV2.TEXT); it.bytes.decodeToString(throwOnInvalidSequence = true) }
    fun integer(name: String): Long = cells.getValue(name).let { check(it.type == NativeHistorySqliteTypeV2.INTEGER); checkNotNull(it.integer) }
    fun bytes(name: String): ByteArray = cells.getValue(name).bytes
    fun textBytes(name: String): String = bytes(name).decodeToString(throwOnInvalidSequence = true)
    fun cellFingerprint(name: String): String = cells.getValue(name).fingerprint()
    fun key(table: NativeHistoryTableV2): String = table.keyColumns.joinToString("|") { "$it:${cellFingerprint(it)}" }
    fun fingerprint(): String = captureTextHash(cells.entries.joinToString("|") { (name, value) -> "$name:${value.fingerprint()}" })
    fun equalsStored(name: String, value: StoredBytes): Boolean = CaptureSlice(bytes(name)).equalsStored(value)
    fun clear() { cells.values.forEach { it.bytes.fill(0) } }
}

/** Zero-copy views of the literal three-level v1 framing and bounded v2 fragment stream. */
private class CaptureSlice(val bytes: ByteArray, val start: Int = 0, val end: Int = bytes.size) {
    val size: Int get() = end - start
    fun text(): String = bytes.decodeToString(start, end, throwOnInvalidSequence = true)
    fun equalsBytes(other: ByteArray): Boolean = size == other.size && other.indices.all { bytes[start + it] == other[it] }
    fun equalsStored(other: StoredBytes): Boolean {
        val copy = other.copyBytes()
        return try { equalsBytes(copy) } finally { copy.fill(0) }
    }
    fun frames(): List<CaptureSlice> {
        check(size <= 32 * 1024 * 1024)
        val result = mutableListOf<CaptureSlice>()
        var at = start
        while (at < end) {
            check(result.size < 16_384 && end - at >= 4)
            var length = 0L
            repeat(4) { length = (length shl 8) or (bytes[at++].toLong() and 255L) }
            check(length <= end - at)
            result += CaptureSlice(bytes, at, at + length.toInt())
            at += length.toInt()
        }
        check(at == end)
        return result
    }
}
private inline fun captureWithFrames(bytes: ByteArray, block: (List<CaptureSlice>) -> Unit) = block(CaptureSlice(bytes).frames())
private class CaptureBinaryReader(private val source: CaptureSlice) {
    constructor(bytes: ByteArray) : this(CaptureSlice(bytes))
    private var at = source.start
    fun int(): Int { check(source.end - at >= 4); var value = 0; repeat(4) { value = (value shl 8) or (source.bytes[at++].toInt() and 255) }; return value }
    fun long(): Long { check(source.end - at >= 8); var value = 0L; repeat(8) { value = (value shl 8) or (source.bytes[at++].toLong() and 255L) }; return value }
    fun blob(maximum: Int): CaptureSlice { val size = int(); check(size in 0..maximum && size <= source.end - at); return CaptureSlice(source.bytes, at, at + size).also { at += size } }
    fun text(maximum: Int): String = blob(maximum).text()
    fun end() { check(at == source.end) }
}
private val captureCensusJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun captureScope(scope: BranchScope): String = listOf(scope.organizationId.value, scope.companyId.value, scope.branchId.value).joinToString("/")
private fun captureTextHash(value: String): String { val bytes = value.encodeToByteArray(throwOnInvalidSequence = true); return try { sha256(bytes).value } finally { bytes.fill(0) } }
private fun captureBinaryHash(write: (DataOutputStream) -> Unit): String {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use(write)
    val bytes = output.toByteArray()
    return try { sha256(bytes).value } finally { bytes.fill(0) }
}
private fun DataOutputStream.captureText(value: String) {
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { writeInt(bytes.size); write(bytes) } finally { bytes.fill(0) }
}
private fun captureClaimKeys(scope: BranchScope, invoice: PurchaseInvoiceCaptureV2): Set<Pair<String, String>> = setOf(
    LocalPurchasingProfile.DOCUMENT_CLAIM to "${LocalPurchasingProfile.INVOICE}/${invoice.id.value}",
    LocalPurchasingProfile.REFERENCE_CLAIM to "invoice/${invoice.supplierId.value}/" + captureTextHash(
        "purchasing-reference/v1/${scope.companyId.value}/${invoice.supplierId.value}/${normalizedPurchaseReference(invoice.reference)}"),
)
