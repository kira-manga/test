package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalRecord
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalRecordedDecision
import com.ukkera.pos.catalog.store.LocalActivateReturnRightsV1
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationCodecV1
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationProfileV1
import com.ukkera.pos.catalog.store.LocalReturnRightsPublicationCodec
import com.ukkera.pos.catalog.store.LocalReturnRightsReferenceV1
import com.ukkera.pos.catalog.store.LocalSignedReturnRightsPolicy
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.sha256
import kotlin.coroutines.cancellation.CancellationException

/** A canonical request for another company is not an admissible original client intent. */
internal fun validateReturnRightsIntent(entry: StoredClientIntentV1) {
    originalReturnRightsActivation(entry)
}

/** A current branch hint only. Saved CAS/content and native trust are never refreshed here. */
internal fun returnRightsContinuationHint(access: GatewayAccess, entry: StoredClientIntentV1): Boolean = try {
    if (entry.recorded != null || entry.acknowledged ||
        !access.installed.has(LocalReturnRightsActivationProfileV1.ACTIVATE) ||
        entry.owner.scope != access.view.scope || entry.owner.actorId != access.view.actorId ||
        entry.owner.deviceId != access.view.deviceId) false
    else {
        originalReturnRightsActivation(entry)
        access.allow(LocalAction.RETURN_RIGHTS_ACTIVATE, access.view.scope.branchId)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/** Generic recorded state survives an invalid documentary receipt; it grants no content read. */
internal fun returnRightsRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val decision = if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED
    var record: LocalRecord? = null
    val message = try {
        require(reply.operationId == entry.operationId && reply.originalActorId == entry.owner.actorId &&
            reply.physicalStart == null)
        val original = originalReturnRightsActivation(entry)
        val reference = original.reference()
        val result = LocalReturnRightsActivationCodecV1.decodeResult(reply.result)
        require(result.reference == reference && result.policyId == reference.policyId &&
            result.publicationId == reference.publicationId && reply.code == result.code)
        when (result.code) {
            LocalReturnRightsActivationProfileV1.ACTIVATED -> {
                require(reply.committed && original.request.expectedActiveRevision.value < Long.MAX_VALUE &&
                    result.activeRevision == original.request.expectedActiveRevision.next())
                // The selector contains no signed terms; opening it requires a fresh protected read.
                record = LocalRecord(LocalRecordReference(LocalRecordKind.RETURN_RIGHTS_PUBLICATION, reference.publicationId),
                    text("Retained return-rights documentary policy", "سياسة حقوق المرتجعات الوثائقية المحفوظة"),
                    text("Open with current policy-read permission", "افتح بصلاحية قراءة السياسة الحالية"))
                text("The master recorded this original documentary policy activation. It does not qualify a return, prove customer receipt or authorize a refund. No stock or money changed here.",
                    "سجل الجهاز الرئيسي تفعيل هذه السياسة الوثائقية الأصلي. لا يثبت أهلية مرتجع أو استلام العميل ولا يخول رد الأموال. لم يتغير المخزون أو النقد هنا.")
            }
            LocalReturnRightsActivationProfileV1.REVISION_CONFLICT,
            LocalReturnRightsActivationProfileV1.PUBLICATION_ALREADY_USED,
            LocalReturnRightsActivationProfileV1.POLICY_SEQUENCE_REQUIRED -> {
                require(!reply.committed && result.activeRevision == null)
                val reason = when (result.code) {
                    LocalReturnRightsActivationProfileV1.REVISION_CONFLICT ->
                        text("The original active revision conflicts with the master state.",
                            "إصدار التفعيل الأصلي يتعارض مع حالة الجهاز الرئيسي.")
                    LocalReturnRightsActivationProfileV1.PUBLICATION_ALREADY_USED ->
                        text("This publication identity has already been used.",
                            "سبق استخدام هوية هذا المنشور.")
                    else -> text("The required policy revision sequence was not met.",
                        "لم يتحقق تسلسل إصدارات السياسة المطلوب.")
                }
                text("The master durably refused this documentary policy activation. ${reason.english} It was not applied. Customer rights remain separate, and this refusal grants no refund authority; no stock or money changed here.",
                    "سجل الجهاز الرئيسي رفض تفعيل هذه السياسة الوثائقية بشكل دائم. ${reason.arabic} لم يُنفذ التفعيل. لا يعني ذلك رفض حقوق العميل ولا يخول رد الأموال؛ لم يتغير المخزون أو النقد هنا.")
            }
            // Reserved signature-refusal syntax supplies no authentic retained negative-trust proof.
            else -> unavailable()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        record = null
        text("The master recorded a ${if (reply.committed) "committed" else "rejected"} operation, but this client cannot validate its documentary activation receipt. No policy, return eligibility, customer-receipt proof, refund, stock or money authority is inferred from this receipt.",
            "سجل الجهاز الرئيسي عملية ${if (reply.committed) "مثبتة" else "مرفوضة"}، لكن لا يمكن لهذا العميل التحقق من سجل تفعيلها الوثائقي. لا تُستنتج من هذا السجل سياسة أو أهلية مرتجع أو إثبات استلام العميل أو صلاحية رد أموال أو تغيير مخزون أو نقد.")
    }
    return LocalOperationResult.Recorded(reply.operationId, decision, message, record, reply.replayed)
}

private data class OriginalReturnRightsActivation(
    val request: LocalActivateReturnRightsV1,
    val signed: LocalSignedReturnRightsPolicy,
) {
    fun reference(): LocalReturnRightsReferenceV1 {
        val definition = signed.definition
        val content = LocalReturnRightsPublicationCodec.canonicalPublication(definition)
        return try {
            LocalReturnRightsReferenceV1(definition.company, definition.publicationId, definition.policyId,
                definition.revision, sha256(content), signed.signingKeyId)
        } finally { content.fill(0) }
    }
}

/** Parse only the original canonical request; this validates identity, never its signature trust. */
private fun originalReturnRightsActivation(entry: StoredClientIntentV1): OriginalReturnRightsActivation {
    val saved = entry.request()
    require(entry.localAction == LocalAction.RETURN_RIGHTS_ACTIVATE &&
        saved.command == LocalReturnRightsActivationProfileV1.ACTIVATE && saved.actionApprovalId == null)
    val request = LocalReturnRightsActivationCodecV1.decodeActivate(saved.payload)
    val signed = LocalReturnRightsPublicationCodec.decodeSignedPublication(request.signedPublication)
    require(signed.definition.company == CompanyScope(entry.owner.scope.organizationId, entry.owner.scope.companyId) &&
        semanticDigest(entry.owner.scope, entry.owner.actorId, saved.command, saved.payload) == entry.semanticSha256)
    return OriginalReturnRightsActivation(request, signed)
}
