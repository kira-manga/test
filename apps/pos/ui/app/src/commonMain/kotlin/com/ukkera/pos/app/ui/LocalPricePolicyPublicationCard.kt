package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.UtcInstant
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Entry-owned unsigned input and original coordinates; private native reviews never survive renewal. */
internal class LocalPricePolicyEntryDrafts {
    val lifetime = LocalConfigurationEntryLifetime()
    val editor = mutableStateOf<PricePolicyEditor?>(null)
    val retainedActionId = mutableStateOf<DomainId?>(null)
    val approvalHeld = mutableStateOf(false)
    val notice = mutableStateOf<LocalText?>(null)
    val hasDraft: Boolean get() = editor.value != null
    fun retireAttachment() { lifetime.retire() }
    fun clear(denied: Boolean = false) {
        try { lifetime.retire(denied) }
        finally { editor.value = null; retainedActionId.value = null; approvalHeld.value = false; notice.value = null }
    }
}

/** Literal unsigned choices. Only the publication coordinate is generated, once per new draft. */
internal data class PricePolicyEditor(
    val publicationId: DomainId = DomainId.random(),
    val entryId: String = "",
    val revision: String = "",
    val skuId: String = "",
    val ruleId: String = "",
    val branchOverridesEnabled: Boolean = false,
    val referenceUnitId: String = "",
    val referenceScale: String = "",
    val referenceQuantity: String = "",
    val effectiveFrom: String = "",
    val effectiveUntil: String = "",
    val frozen: Boolean = false,
) {
    fun toDraft(): LocalPricePolicyPublicationDraft {
        require(revision.matches(Regex("0|[1-9][0-9]*")))
        require(referenceScale.matches(Regex("[0-6]")))
        return LocalPricePolicyPublicationDraft(publicationId, DomainId(entryId), Revision(revision.toLong()),
            DomainId(skuId), DomainId(ruleId), branchOverridesEnabled,
            Quantity.parse(DomainId(referenceUnitId), referenceQuantity, referenceScale.toInt()),
            UtcInstant.parseCanonical(effectiveFrom), effectiveUntil.takeIf { it.isNotEmpty() }?.let(UtcInstant::parseCanonical))
    }
}

@Composable
private fun PricePolicyEditorFields(input: PricePolicyEditor, enabled: Boolean, change: (PricePolicyEditor) -> Unit) {
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String) = language.text(LocalText(en, ar))
    PricePolicyField(input.entryId, label("Price-policy entry ID — explicit existing/new UUID", "معرف بند سياسة السعر — UUID صريح قائم أو جديد"), enabled) { change(input.copy(entryId = it)) }
    PricePolicyField(input.revision, label("Policy entry revision — exact decimal integer", "مراجعة بند السياسة — عدد عشري دقيق"), enabled) { change(input.copy(revision = it)) }
    PricePolicyField(input.skuId, label("Exact existing SKU ID", "معرف الصنف القائم الدقيق"), enabled) { change(input.copy(skuId = it)) }
    PricePolicyField(input.ruleId, label("Exact existing company price-rule ID", "معرف قاعدة سعر الشركة القائمة الدقيق"), enabled) { change(input.copy(ruleId = it)) }
    ConfirmRow(label("Policy permits branch overrides", "تسمح السياسة بأسعار الفرع"), input.branchOverridesEnabled, enabled) {
        change(input.copy(branchOverridesEnabled = it))
    }
    PricePolicyField(input.referenceUnitId, label("Authorization reference base-unit ID", "معرف الوحدة الأساسية للكمية المرجعية للصلاحية"), enabled) { change(input.copy(referenceUnitId = it)) }
    PricePolicyField(input.referenceScale, label("Exact SKU quantity precision (0–6)", "دقة كمية الصنف الدقيقة (٠–٦)"), enabled) { change(input.copy(referenceScale = it)) }
    PricePolicyField(input.referenceQuantity, label("Positive authorization reference quantity — no rounding", "كمية مرجعية موجبة للصلاحية — دون تقريب"), enabled) { change(input.copy(referenceQuantity = it)) }
    PricePolicyField(input.effectiveFrom, label("Policy effective from — canonical UTC ending in Z", "سريان السياسة من — UTC معياري ينتهي بـ Z"), enabled) { change(input.copy(effectiveFrom = it)) }
    PricePolicyField(input.effectiveUntil, label("Policy effective until — blank for no expiry", "سريان السياسة حتى — فارغ لعدم تحديد انتهاء"), enabled) { change(input.copy(effectiveUntil = it)) }
}

@Composable
private fun PricePolicyField(value: String, title: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { if (enabled && it.length <= 96) change(it) }, enabled = enabled,
        singleLine = true, label = { Text(title) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PricePolicyPreview(preview: LocalPricePolicyPublicationPreview) {
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String) = language.text(LocalText(en, ar))
    val definition = preview.definition
    Text(label("Exact retained price-policy review", "مراجعة سياسة السعر المحفوظة الدقيقة"), style = MaterialTheme.typography.titleSmall)
    Text(label("Company: ${preview.companyId.value}", "الشركة: ${preview.companyId.value}"))
    Text(label("Publication: ${definition.publicationId.value}", "المنشور: ${definition.publicationId.value}"))
    Text(label("Policy entry: ${definition.entryId.value}; revision: ${definition.revision.value}",
        "بند السياسة: ${definition.entryId.value}؛ المراجعة: ${definition.revision.value}"))
    Text(label("SKU: ${definition.skuId.value}", "الصنف: ${definition.skuId.value}"))
    Text(label("Company price rule: ${definition.ruleId.value}", "قاعدة سعر الشركة: ${definition.ruleId.value}"))
    Text(label("Branch overrides permitted: ", "أسعار الفرع مسموحة: ") + if (definition.branchOverridesEnabled) label("Yes", "نعم") else label("No", "لا"))
    val quantity = definition.authorizationReferenceQuantity
    Text(label("Authorization reference quantity: ${pricePolicyQuantityText(quantity)}; precision: ${quantity.scale}",
        "الكمية المرجعية للصلاحية: ${pricePolicyQuantityText(quantity)}؛ الدقة: ${quantity.scale}"))
    Text(label("Reference base unit: ${quantity.unitId.value}", "الوحدة الأساسية المرجعية: ${quantity.unitId.value}"))
    Text(label("Effective from: ${definition.effectiveFrom.canonicalString()}", "سارية من: ${definition.effectiveFrom.canonicalString()}"))
    Text(label("Effective until: ", "سارية حتى: ") + (definition.effectiveUntil?.canonicalString() ?: label("No expiry specified", "لم يُحدد انتهاء")))
}

private fun pricePolicyQuantityText(quantity: Quantity): String {
    val digits = quantity.scaled.toString().padStart(quantity.scale + 1, '0')
    return if (quantity.scale == 0) digits else digits.dropLast(quantity.scale) + "." + digits.takeLast(quantity.scale)
}

private fun com.ukkera.pos.security.CompanyBusinessPublicationPhase.authoringLabel(): LocalText = when (this) {
    com.ukkera.pos.security.CompanyBusinessPublicationPhase.PREPARED -> LocalText("Prepared", "اقتراح محفوظ")
    com.ukkera.pos.security.CompanyBusinessPublicationPhase.SIGNING_INTENT -> LocalText("Signing outcome uncertain", "نتيجة التوقيع غير مؤكدة")
    com.ukkera.pos.security.CompanyBusinessPublicationPhase.COMPLETED -> LocalText("Signing completed", "اكتمل التوقيع")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalPricePolicyPublicationCard(runtime: UkkeraRuntime, token: NavigationToken,
    drafts: LocalPricePolicyEntryDrafts, parentReady: Boolean, mayStartNew: Boolean,
    guardChanged: (draft: Boolean, working: Boolean) -> Unit, employeeAccessDenied: () -> Unit,
    receiptStaged: (NavigationToken) -> Unit) {
    val session = runtime.localSessionFor(token) ?: return
    val presentation by runtime.presentation.collectAsState()
    val attachment = presentation.localAttachment
    val identity = drafts.lifetime.identity
    val gateway = remember(session, attachment) { session.companyCatalog.pricePolicies }
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String): String = language.text(LocalText(en, ar))
    if (!gateway.configured) {
        Notice(label("Native company price-policy authoring is unavailable. No unsigned policy or employee-selected signing key is accepted.",
            "إنشاء سياسة أسعار الشركة الأصلية غير متاح. لا تُقبل سياسة غير موقعة أو مفتاح توقيع يختاره الموظف."), caution = true)
        return
    }
    val scope = rememberCoroutineScope()
    val latestToken by rememberUpdatedState(token)
    val latestParent by rememberUpdatedState(parentReady)
    val latestMayStart by rememberUpdatedState(mayStartNew)
    val latestGuard by rememberUpdatedState(guardChanged)
    val latestDenied by rememberUpdatedState(employeeAccessDenied)
    val latestStaged by rememberUpdatedState(receiptStaged)
    var alive by remember(session, token.entryId, attachment, identity) { mutableStateOf(true) }
    var generation by remember(session, token.entryId, attachment, identity) { mutableStateOf(0L) }
    var working by remember(session, token.entryId, attachment, identity) { mutableStateOf(false) }
    var work by remember(session, token.entryId, attachment, identity) { mutableStateOf<Job?>(null) }
    var status by remember(session, token.entryId, attachment, identity) { mutableStateOf<LocalPricePolicyPublicationStatus?>(null) }
    var statusRequested by remember(session, token.entryId, attachment, identity) { mutableStateOf(drafts.lifetime.accessDenied) }
    var review by remember(session, token.entryId, attachment, identity) { mutableStateOf<LocalPricePolicyPublicationReview?>(null) }
    var completed by remember(session, token.entryId, attachment, identity) { mutableStateOf<LocalPricePolicyPublicationCompleted?>(null) }
    var problem by remember(session, token.entryId, attachment, identity) {
        mutableStateOf<LocalServiceProblem?>(LocalServiceProblem.DENIED.takeIf { drafts.lifetime.accessDenied })
    }
    var headLogin by remember(session, token.entryId, attachment, identity) { mutableStateOf("") }
    var headSecret by remember(session, token.entryId, attachment, identity) { mutableStateOf("") }
    var exactApproved by remember(session, token.entryId, attachment, identity) { mutableStateOf(false) }
    var page by remember(session, token.entryId, attachment, identity) { mutableStateOf(0) }
    var refreshPending by remember(session, token.entryId, attachment, identity) { mutableStateOf(false) }
    var editor by drafts.editor
    var original by drafts.retainedActionId
    var approvalHeld by drafts.approvalHeld
    var notice by drafts.notice

    fun owns(): Boolean = alive && drafts.lifetime.isCurrent(identity) &&
        runtime.ownsLocalAttachment(session, latestToken, attachment)
    fun callbackCurrent(): Boolean = owns() && runtime.navigator.isCurrent(latestToken)
    fun ready(): Boolean = callbackCurrent() && !working && latestParent
    fun syncGuard() { if (owns()) latestGuard(drafts.hasDraft || review != null, working) }
    fun unavailable(value: LocalServiceProblem, native: SelfOwnedInstallationFailureV1? = null) {
        headSecret = ""; exactApproved = false
        if (value == LocalServiceProblem.DENIED && native != SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED) {
            latestDenied(); return
        }
        problem = value
        if (value == LocalServiceProblem.SESSION_ENDED) runtime.localSessionEnded(session)
    }
    fun adopt(result: LocalPricePolicyPublicationResult, expectedOriginal: DomainId? = null,
        expectedDraft: LocalPricePolicyPublicationDraft? = null) {
        val preview = when (result) {
            is LocalPricePolicyPublicationResult.Review -> result.review.preview
            is LocalPricePolicyPublicationResult.Completed -> result.publication.preview
            is LocalPricePolicyPublicationResult.Unavailable -> null
        }
        val actionId = when (result) {
            is LocalPricePolicyPublicationResult.Review -> result.review.action.originalActionId
            is LocalPricePolicyPublicationResult.Completed -> result.publication.originalActionId
            is LocalPricePolicyPublicationResult.Unavailable -> null
        }
        if (preview != null && (preview.companyId != session.display.companyId ||
                expectedDraft != null && preview.definition != expectedDraft ||
                expectedOriginal != null && actionId != expectedOriginal)) {
            unavailable(LocalServiceProblem.UNAVAILABLE); return
        }
        if (result is LocalPricePolicyPublicationResult.Review) {
            val proposal = result.review.action.proposal
            val definition = result.review.preview.definition
            if (proposal.kind != CompanyBusinessPublicationKind.PRICE_POLICY_V1 ||
                proposal.company.companyId != result.review.preview.companyId ||
                proposal.publicationId != definition.publicationId || proposal.entityId != definition.entryId) {
                unavailable(LocalServiceProblem.UNAVAILABLE); return
            }
        }
        when (result) {
            is LocalPricePolicyPublicationResult.Review -> {
                original = result.review.action.originalActionId
                review = result.review; completed = null; editor = null
                headLogin = result.review.action.headLogin; headSecret = ""; exactApproved = false
                // A successful native PREPARED review, not a failure hint, permits a new explicit approval.
                approvalHeld = false
            }
            is LocalPricePolicyPublicationResult.Completed -> {
                original = result.publication.originalActionId
                completed = result.publication; review = null; editor = null
                headLogin = ""; headSecret = ""; exactApproved = false; approvalHeld = true
                notice = LocalText("Company signing completed. Receiving this policy and activating it at the branch are separate explicit operations.",
                    "اكتمل توقيع الشركة. استلام هذه السياسة وتفعيلها في الفرع عمليتان منفصلتان صريحتان.")
            }
            is LocalPricePolicyPublicationResult.Unavailable -> unavailable(result.problem, result.nativeReason)
        }
    }
    fun runAction(secret: CharArray? = null, body: suspend () -> Unit): Boolean {
        if (!ready()) { secret?.fill('\u0000'); return false }
        val ticket = ++generation
        problem = null; working = true; syncGuard()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { body() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (owns() && ticket == generation) unavailable(LocalServiceProblem.UNAVAILABLE) }
            finally {
                secret?.fill('\u0000')
                if (owns() && ticket == generation) {
                    headSecret = ""
                    if (secret != null) headLogin = ""
                    working = false; work = null; syncGuard()
                    if (refreshPending) { refreshPending = false; latestStaged(runtime.navigator.token()) }
                }
            }
        }
        job.invokeOnCompletion { secret?.fill('\u0000') }
        work = job; job.start()
        return true
    }
    fun readStatus() {
        if (runAction {
            val result = gateway.status()
            currentCoroutineContext().ensureActive()
            if (owns()) when (result) {
                is LocalPricePolicyPublicationStatusResult.Available -> {
                    val references = result.status.references
                    if (references.size > 256 || references.any { it.kind != CompanyBusinessPublicationKind.PRICE_POLICY_V1 } ||
                        references.map { it.originalActionId }.distinct().size != references.size ||
                        references.map { it.publicationId }.distinct().size != references.size) {
                        status = null; unavailable(LocalServiceProblem.UNAVAILABLE)
                    } else { status = result.status; page = 0; drafts.lifetime.accessObserved() }
                }
                is LocalPricePolicyPublicationStatusResult.Unavailable -> {
                    status = null; unavailable(result.problem, result.nativeReason)
                }
            }
        }) statusRequested = true
    }
    fun retire() {
        alive = false; generation++
        val job = work; work = null
        headSecret = ""; headLogin = ""; exactApproved = false
        review = null; completed = null; status = null; refreshPending = false; working = false
        job?.cancel()
    }
    DisposableEffect(session, token.entryId, attachment, identity) {
        val release = drafts.lifetime.attach(identity, ::retire)
        syncGuard()
        onDispose {
            val wasCurrent = owns()
            release(); retire()
            if (wasCurrent) latestGuard(drafts.hasDraft, false)
        }
    }
    LaunchedEffect(session, token.entryId, attachment, identity, parentReady) {
        if (parentReady && !statusRequested && !working) readStatus()
    }

    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label("Company price-policy publication", "منشور سياسة أسعار الشركة"), style = MaterialTheme.typography.titleMedium)
            Notice(label("Proposing and signing a company price policy does not activate it. Receive the signed publication first, then explicitly activate it with current employee authority. Stock and payment stay separate.",
                "لا يفعّل اقتراح سياسة أسعار الشركة أو توقيعها السياسة. استلم المنشور الموقع أولًا، ثم فعّله صراحةً بصلاحية الموظف الحالية. يبقى المخزون والدفع منفصلين."), caution = true)
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            problem?.let { Notice(label("Operation unavailable: ${it.name}. Recover the original action; do not assume it was cancelled.",
                "العملية غير متاحة: ${it.name}. استعد الإجراء الأصلي؛ لا تفترض إلغاءه."), caution = true) }
            notice?.let { Notice(language.text(it), caution = true) }
            OutlinedButton(onClick = ::readStatus, enabled = ready(), modifier = Modifier.touchTarget()) {
                Text(label("Refresh current access / retained publications", "تحديث الصلاحيات والإصدارات المحفوظة"))
            }
            if (editor == null && review == null && completed == null) {
                Button(onClick = {
                    if (ready() && latestMayStart && status?.mayPrepare == true) {
                        editor = PricePolicyEditor(); original = null; approvalHeld = false; notice = null; syncGuard()
                    }
                }, enabled = ready() && mayStartNew && status?.mayPrepare == true, modifier = Modifier.touchTarget()) {
                    Text(label("Propose company price policy", "اقتراح سياسة أسعار الشركة"))
                }
            }
            editor?.let { input ->
                PricePolicyEditorFields(input, ready() && !input.frozen) { changed ->
                    if (ready() && editor?.frozen == false) { editor = changed; syncGuard() }
                }
                Text(label("Original publication ID: ${input.publicationId.value}", "معرف المنشور الأصلي: ${input.publicationId.value}"))
                Button(onClick = {
                    val draft = try { editor?.toDraft() } catch (_: IllegalArgumentException) { unavailable(LocalServiceProblem.INVALID_INPUT); null }
                        catch (_: ArithmeticException) { unavailable(LocalServiceProblem.INVALID_INPUT); null }
                    if (draft != null && ready() && latestMayStart && status?.mayPrepare == true) {
                        // Preserve the exact proposal whenever retention may have started, including cancellation.
                        editor = input.copy(frozen = true)
                        runAction {
                            val result = gateway.prepare(draft)
                            currentCoroutineContext().ensureActive()
                            if (owns()) adopt(result, expectedDraft = draft)
                        }
                    }
                }, enabled = ready() && mayStartNew && status?.mayPrepare == true, modifier = Modifier.touchTarget()) {
                    Text(label(if (input.frozen) "Retry the SAME exact proposal / recover review" else "Retain exact proposal and review",
                        if (input.frozen) "إعادة الاقتراح الدقيق نفسه أو استعادة المراجعة" else "حفظ الاقتراح الدقيق ومراجعته"))
                }
                if (input.frozen) Notice(label("Input is frozen after retention was requested. Read the original publication; no new identity or signature is generated on retry.",
                    "جُمدت المدخلات بعد طلب حفظها. اقرأ المنشور الأصلي؛ لا تُنشأ هوية أو توقيع جديد عند المحاولة."), caution = true)
                OutlinedButton(onClick = { if (ready()) { editor = null; syncGuard() } }, enabled = ready(), modifier = Modifier.touchTarget()) {
                    Text(label("Dismiss input; preserve any native retained proposal", "إغلاق المدخلات مع حفظ أي اقتراح أصلي محفوظ"))
                }
            }
            original?.let { id ->
                Text(label("Original native action: ${id.value}", "الإجراء الأصلي: ${id.value}"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runAction {
                        val result = gateway.review(id); currentCoroutineContext().ensureActive(); if (owns()) adopt(result, expectedOriginal = id)
                    } }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Re-read original review", "قراءة المراجعة الأصلية")) }
                    OutlinedButton(onClick = { runAction {
                        val result = gateway.completed(id); currentCoroutineContext().ensureActive(); if (owns()) adopt(result, expectedOriginal = id)
                    } }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Recover original signing output", "استعادة نتيجة التوقيع الأصلية")) }
                }
            }
            review?.let { exact ->
                PricePolicyPreview(exact.preview)
                Text(label("Company / head: ${exact.action.companyName} / ${exact.action.headName}",
                    "الشركة / الرئيس: ${exact.action.companyName} / ${exact.action.headName}"))
                Text(label("Original root fingerprint: ${exact.action.rootFingerprint}", "بصمة الجذر الأصلي: ${exact.action.rootFingerprint}"))
                Text(label("Exact content digest: ${exact.action.proposal.contentDigest.value}", "بصمة المحتوى الدقيق: ${exact.action.proposal.contentDigest.value}"))
                if (approvalHeld) Notice(label("An approval was attempted. Recover or re-read the SAME native action before any further signing; no failure proves noncompletion.",
                    "جرت محاولة الموافقة. استعد الإجراء الأصلي نفسه أو أعد قراءته قبل أي توقيع آخر؛ لا يثبت الفشل عدم اكتماله."), caution = true)
                OutlinedTextField(headLogin, { if (ready() && !approvalHeld) headLogin = it }, enabled = ready() && !approvalHeld,
                    singleLine = true, label = { Text(label("Original company-head login", "دخول رئيس الشركة الأصلي")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(headSecret, { if (ready() && !approvalHeld) headSecret = it }, enabled = ready() && !approvalHeld,
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text(label("Head password — transient", "كلمة مرور الرئيس — مؤقتة")) }, modifier = Modifier.fillMaxWidth())
                ConfirmRow(label("I reviewed these exact policy values; signing does not receive or activate this publication",
                    "راجعت هذه القيم الدقيقة للسياسة؛ لا يستلم التوقيع هذا المنشور أو يفعّله"), exactApproved, ready() && !approvalHeld) { exactApproved = it }
                Button(onClick = {
                    if (ready() && !approvalHeld && exactApproved && headLogin.isNotBlank() && headSecret.isNotEmpty()) {
                        val login = headLogin; val secret = headSecret.toCharArray(); headSecret = ""; exactApproved = false; approvalHeld = true
                        runAction(secret) {
                            val result = gateway.approve(exact, login, secret)
                            currentCoroutineContext().ensureActive()
                            if (owns()) adopt(result, expectedOriginal = exact.action.originalActionId, expectedDraft = exact.preview.definition)
                        }
                    }
                }, enabled = ready() && !approvalHeld && exactApproved && headLogin.isNotBlank() && headSecret.isNotEmpty(), modifier = Modifier.touchTarget()) {
                    Text(label("Authenticate head and sign EXACT reviewed price policy", "توثيق الرئيس وتوقيع سياسة السعر المراجعة الدقيقة"))
                }
            }
            completed?.let { output ->
                PricePolicyPreview(output.preview)
                Text(label("Original root fingerprint: ${output.rootFingerprint}", "بصمة الجذر الأصلي: ${output.rootFingerprint}"))
                Button(onClick = { runAction {
                    val result = gateway.stageReceipt(output.originalActionId)
                    currentCoroutineContext().ensureActive()
                    if (owns()) when (result) {
                        is LocalStageResult.Staged -> {
                            if (result.operation.action != LocalAction.PRICE_POLICY_RECEIVE ||
                                result.operation.operationId != OperationId(output.originalActionId.value)) {
                                unavailable(LocalServiceProblem.UNAVAILABLE); return@runAction
                            }
                            review = null; completed = null; notice = LocalText("Original receipt request saved. Explicitly review and submit it under saved requests. Receipt and branch activation remain separate.",
                                "حُفظ طلب الاستلام الأصلي. راجعه وأرسله صراحةً ضمن الطلبات المحفوظة. يبقى الاستلام والتفعيل في الفرع منفصلين.")
                            refreshPending = true
                        }
                        is LocalStageResult.Uncertain -> {
                            if (result.operationId != OperationId(output.originalActionId.value)) {
                                unavailable(LocalServiceProblem.UNAVAILABLE); return@runAction
                            }
                            review = null; completed = null; notice = LocalText("Staging is uncertain. Recover the original saved operation ${result.operationId.value}; never replace its CAS or identity.",
                                "حفظ الطلب غير مؤكد. استعد العملية الأصلية ${result.operationId.value}؛ لا تستبدل إصدارها المتوقع أو هويتها.")
                            refreshPending = true
                        }
                        is LocalStageResult.NotStaged -> unavailable(result.problem)
                    }
                } }, enabled = ready(), modifier = Modifier.touchTarget()) {
                    Text(label("Save separate policy receipt request", "حفظ طلب استلام السياسة المنفصل"))
                }
            }
            if (editor == null && review == null && completed == null) status?.references?.let { references ->
                references.drop(page * 8).take(8).forEach { ref ->
                    OutlinedButton(onClick = { runAction {
                        val result = gateway.review(ref.originalActionId); currentCoroutineContext().ensureActive(); if (owns()) adopt(result, expectedOriginal = ref.originalActionId)
                    } }, enabled = ready(), modifier = Modifier.touchTarget()) {
                        Text("${ref.publicationId.value} · ${language.text(ref.phase.authoringLabel())}")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page > 0) OutlinedButton(onClick = { if (ready()) page-- }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Previous", "السابق")) }
                    if ((page + 1) * 8 < references.size) OutlinedButton(onClick = { if (ready()) page++ }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Next", "التالي")) }
                }
            }
        }
    }
}
