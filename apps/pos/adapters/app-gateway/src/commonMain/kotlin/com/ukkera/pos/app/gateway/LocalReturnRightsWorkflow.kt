package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*

internal val returnRightsRecordKinds: Set<LocalRecordKind> = setOf(
    LocalRecordKind.RETURN_RIGHTS_CURRENT, LocalRecordKind.RETURN_RIGHTS_PUBLICATION)

/** Navigation is only a hint; the protected Master read independently decides disclosure. */
internal fun returnRightsNavigation(access: GatewayAccess): List<LocalRecord> =
    if (access.installed.has(LocalReturnRightsActivationProfileV1.READ) &&
        access.allow(LocalAction.CATALOG_ACTIVATE, access.view.scope.branchId))
        listOf(LocalRecord(LocalRecordReference(LocalRecordKind.RETURN_RIGHTS_CURRENT, access.view.scope.companyId),
            text("Return-rights documentary policy", "سياسة حقوق المرتجعات الوثائقية"),
            text("Protected current-policy read — not return eligibility", "قراءة السياسة الحالية المحمية — ليست أهلية مرتجع")))
    else emptyList()

internal suspend fun returnRightsWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val reference = query.reference ?: invalid()
    if (query.workflow !in setOf(LocalWorkflow.CATALOG, LocalWorkflow.SETUP) || reference.kind !in returnRightsRecordKinds ||
        reference.parentId != null || reference.revision != null || query.search.isNotEmpty() || query.cursor != null ||
        query.pricePreview != null) invalid()
    val historical = reference.kind == LocalRecordKind.RETURN_RIGHTS_PUBLICATION
    if (!historical && reference.id != access.view.scope.companyId) invalid()
    access.checkCurrent()
    if (!access.installed.has(LocalReturnRightsActivationProfileV1.READ))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    access.requireAction(LocalAction.CATALOG_ACTIVATE, if (historical) reference.id else access.view.scope.branchId)
    val reply = access.read(if (historical) LocalReturnRightsActivationCodecV1.readPublication(reference.id)
        else LocalReturnRightsActivationCodecV1.readCurrent())
    val result = try { LocalReturnRightsActivationCodecV1.decodeRead(reply.payload) }
        catch (_: IllegalArgumentException) { unavailable() }
    val activation = result.activation
    val notice = text(
        "Documentary policy only. It does not qualify a sale, erase continuing rights, prove customer receipt or authorize a refund. No stock or money changes here.",
        "سياسة وثائقية فقط. لا تثبت أهلية بيع أو تلغي الحقوق المستمرة أو تثبت استلام العميل أو تخول رد الأموال. لا يتغير المخزون أو النقد هنا.")
    if (result.status != LocalReturnRightsReadStatusV1.PUBLISHED) {
        if (activation != null) unavailable()
        return LocalWorkflowPage(query.workflow, emptyList(), emptyList(), when (result.status) {
            LocalReturnRightsReadStatusV1.ABSENT -> text(
                "No retained return-rights policy was found. This is not a denial of customer rights.",
                "لم يُعثر على سياسة حقوق مرتجعات محفوظة. لا يعني ذلك رفض حقوق العميل.")
            LocalReturnRightsReadStatusV1.SOURCE_UNAVAILABLE -> text(
                "Return-rights documentary evidence is unavailable. Do not treat it as an absent policy or a qualified decision.",
                "الأدلة الوثائقية لحقوق المرتجعات غير متاحة. لا تعتبرها سياسة مفقودة أو قرار أهلية.")
            else -> unavailable()
        })
    }
    val original = activation ?: unavailable()
    if (original.scope != access.view.scope || historical && original.reference.publicationId != reference.id) unavailable()
    val definition = original.definition
    val attribution = listOf(
        recordValue(text("Policy ID", "معرف السياسة"), literal(definition.policyId.value)),
        recordValue(text("Publication ID", "معرف المنشور"), literal(definition.publicationId.value)),
        recordValue(text("Company policy revision", "مراجعة سياسة الشركة"), literal(definition.revision.value.toString())),
        recordValue(text("Jurisdiction", "الاختصاص"), literal(definition.jurisdictionCode)),
        recordValue(text("Documentary source — not fetched", "المصدر الوثائقي — لم يُجلب"), literal(definition.legalSourceUri)),
        recordValue(text("Source access date — not customer receipt date", "تاريخ الاطلاع على المصدر — ليس تاريخ استلام العميل"),
            literal(definition.legalSourceAccessDate)),
        recordValue(text("Local revision at original activation", "المراجعة المحلية وقت التفعيل الأصلي"), literal(original.activeRevision.value.toString())),
        recordValue(text("Original activation operation", "عملية التفعيل الأصلية"), literal(original.operationId.value)),
        recordValue(text("Original activation employee", "موظف التفعيل الأصلي"), literal(original.actorId.value)),
        recordValue(text("Permission policy revision at activation", "مراجعة سياسة الصلاحيات وقت التفعيل"),
            literal(original.permissionPolicyRevision.value.toString())),
        recordValue(text("Original activation time", "وقت التفعيل الأصلي"), literal(original.recordedAt.canonicalString())))
    val rules = definition.rules.map { rule -> LocalReturnRightsUiRule(rule.categoryCode, rule.reason.name, listOf(
        recordValue(text("Category", "الفئة"), literal(rule.categoryCode)),
        recordValue(text("Reason code", "رمز السبب"), literal(rule.reason.name)),
        recordValue(text("Documentary treatment code", "رمز المعالجة الوثائقية"), literal(rule.treatment.name)),
        recordValue(text("Ordinary receipt window (days)", "مهلة الاستلام العادية (أيام)"), documentaryPeriod(rule.receiptWindowDays)),
        recordValue(text("Covered resolution period (days)", "مدة المعالجة المشمولة (أيام)"), documentaryPeriod(rule.coveredResolutionDays)),
        recordValue(text("Specific exception basis code — evidence still required", "رمز أساس الاستثناء المحدد — لا تزال الأدلة مطلوبة"),
            rule.exceptionBasis?.let { literal(it.name) } ?: text("Not specified", "غير محدد")))) }
    return LocalWorkflowPage(query.workflow, emptyList(), emptyList(), notice,
        returnRights = LocalReturnRightsUiPage(reference, original.scope.companyId, original.scope.branchId, attribution,
            text(definition.continuingRightsEnglish, definition.continuingRightsArabic), rules))
}

private fun documentaryPeriod(days: Int?): LocalText = days?.let { literal(it.toString()) }
    ?: text("Not specified — not a denial of rights", "غير محدد — ليس رفضًا للحقوق")
