package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Entry-owned unsigned input and original coordinates; private native reviews never survive renewal. */
internal class LocalReturnRightsEntryDrafts {
    val lifetime = LocalConfigurationEntryLifetime()
    val editor = mutableStateOf<ReturnRightsEditor?>(null)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalReturnRightsPublicationCard(runtime: UkkeraRuntime, token: NavigationToken,
    drafts: LocalReturnRightsEntryDrafts, parentReady: Boolean, mayStartNew: Boolean,
    guardChanged: (draft: Boolean, working: Boolean) -> Unit, employeeAccessDenied: () -> Unit,
    activationStaged: (NavigationToken) -> Unit) {
    val session = runtime.localSessionFor(token) ?: return
    val presentation by runtime.presentation.collectAsState()
    val attachment = presentation.localAttachment
    val identity = drafts.lifetime.identity
    val gateway = remember(session, attachment) { session.companyCatalog.returnRights }
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String): String = language.text(LocalText(en, ar))
    if (!gateway.configured) {
        Notice(label("Native return-rights authoring is unavailable. No unsigned policy or employee-selected signing key is accepted.",
            "إنشاء سياسة حقوق المرتجعات الأصلية غير متاح. لا تُقبل سياسة غير موقعة أو مفتاح توقيع يختاره الموظف."), caution = true)
        return
    }
    val scope = rememberCoroutineScope()
    val latestToken by rememberUpdatedState(token)
    val latestParent by rememberUpdatedState(parentReady)
    val latestMayStart by rememberUpdatedState(mayStartNew)
    val latestGuard by rememberUpdatedState(guardChanged)
    val latestDenied by rememberUpdatedState(employeeAccessDenied)
    val latestStaged by rememberUpdatedState(activationStaged)
    var alive by remember(session, token.entryId, attachment, identity) { mutableStateOf(true) }
    var generation by remember(session, token.entryId, attachment, identity) { mutableStateOf(0L) }
    var working by remember(session, token.entryId, attachment, identity) { mutableStateOf(false) }
    var work by remember(session, token.entryId, attachment, identity) { mutableStateOf<Job?>(null) }
    var status by remember(session, token.entryId, attachment, identity) { mutableStateOf<LocalReturnRightsPublicationStatus?>(null) }
    var statusRequested by remember(session, token.entryId, attachment, identity) { mutableStateOf(drafts.lifetime.accessDenied) }
    var review by remember(session, token.entryId, attachment, identity) { mutableStateOf<LocalReturnRightsPublicationReview?>(null) }
    var completed by remember(session, token.entryId, attachment, identity) { mutableStateOf<LocalReturnRightsPublicationCompleted?>(null) }
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
    fun adopt(result: LocalReturnRightsPublicationResult) {
        when (result) {
            is LocalReturnRightsPublicationResult.Review -> {
                original = result.review.action.originalActionId
                review = result.review; completed = null; editor = null
                headLogin = result.review.action.headLogin; headSecret = ""; exactApproved = false
                // A successful native PREPARED review, not a failure hint, permits a new explicit approval.
                approvalHeld = false
            }
            is LocalReturnRightsPublicationResult.Completed -> {
                original = result.publication.originalActionId
                completed = result.publication; review = null; editor = null
                headLogin = ""; headSecret = ""; exactApproved = false; approvalHeld = true
                notice = LocalText("Company signing completed. Employee branch staging and submission are separate; this is not return eligibility or refund authority.",
                    "اكتمل توقيع الشركة. حفظ طلب الموظف وإرساله في الفرع خطوتان منفصلتان؛ لا يثبت ذلك أهلية مرتجع أو صلاحية رد الأموال.")
            }
            is LocalReturnRightsPublicationResult.Unavailable -> unavailable(result.problem, result.nativeReason)
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
                is LocalReturnRightsPublicationStatusResult.Available -> {
                    status = result.status; page = 0; drafts.lifetime.accessObserved()
                }
                is LocalReturnRightsPublicationStatusResult.Unavailable -> {
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
            Text(label("Company return-rights documentary policy", "سياسة حقوق المرتجعات الوثائقية للشركة"), style = MaterialTheme.typography.titleMedium)
            Notice(label("Documentary rules only. No customer receipt, qualification, refund, stock or money effect is inferred. Continuing rights and original sale promises remain separate.",
                "قواعد وثائقية فقط. لا يُستنتج منها استلام العميل أو أهلية مرتجع أو رد أموال أو أثر على المخزون أو النقد. تبقى الحقوق المستمرة ووعود البيعة الأصلية منفصلة."), caution = true)
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
                        editor = ReturnRightsEditor(); original = null; approvalHeld = false; notice = null; syncGuard()
                    }
                }, enabled = ready() && mayStartNew && status?.mayPrepare == true, modifier = Modifier.touchTarget()) {
                    Text(label("Propose documentary policy — not sign", "اقتراح سياسة وثائقية — دون توقيع"))
                }
            }
            editor?.let { input ->
                ReturnRightsEditorFields(input, ready() && !input.frozen) { changed ->
                    if (ready() && editor?.frozen == false) { editor = changed; syncGuard() }
                }
                Text(label("Original publication ID: ${input.publicationId.value}", "معرف المنشور الأصلي: ${input.publicationId.value}"))
                Button(onClick = {
                    val draft = try { editor?.toDraft() } catch (_: IllegalArgumentException) { unavailable(LocalServiceProblem.INVALID_INPUT); null }
                    if (draft != null && ready() && latestMayStart && status?.mayPrepare == true) {
                        // Preserve the exact proposal whenever retention may have started, including cancellation.
                        editor = input.copy(frozen = true)
                        runAction {
                            val result = gateway.prepare(draft)
                            currentCoroutineContext().ensureActive()
                            if (owns()) adopt(result)
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
                        val result = gateway.review(id); currentCoroutineContext().ensureActive(); if (owns()) adopt(result)
                    } }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Re-read original review", "قراءة المراجعة الأصلية")) }
                    OutlinedButton(onClick = { runAction {
                        val result = gateway.completed(id); currentCoroutineContext().ensureActive(); if (owns()) adopt(result)
                    } }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Recover original signing output", "استعادة نتيجة التوقيع الأصلية")) }
                }
            }
            review?.let { exact ->
                ReturnRightsPreview(exact.preview)
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
                ConfirmRow(label("I reviewed these exact documentary values; this does not activate at the branch",
                    "راجعت هذه القيم الوثائقية الدقيقة؛ لا يُفعّل ذلك السياسة في الفرع"), exactApproved, ready() && !approvalHeld) { exactApproved = it }
                Button(onClick = {
                    if (ready() && !approvalHeld && exactApproved && headLogin.isNotBlank() && headSecret.isNotEmpty()) {
                        val login = headLogin; val secret = headSecret.toCharArray(); headSecret = ""; exactApproved = false; approvalHeld = true
                        runAction(secret) {
                            val result = gateway.approve(exact, login, secret)
                            currentCoroutineContext().ensureActive()
                            if (owns()) adopt(result)
                        }
                    }
                }, enabled = ready() && !approvalHeld && exactApproved && headLogin.isNotBlank() && headSecret.isNotEmpty(), modifier = Modifier.touchTarget()) {
                    Text(label("Authenticate head and sign EXACT reviewed policy", "توثيق الرئيس وتوقيع السياسة المراجعة الدقيقة"))
                }
            }
            completed?.let { output ->
                ReturnRightsPreview(output.preview)
                Text(label("Original root fingerprint: ${output.rootFingerprint}", "بصمة الجذر الأصلي: ${output.rootFingerprint}"))
                Button(onClick = { runAction {
                    val result = gateway.stageActivation(output.originalActionId)
                    currentCoroutineContext().ensureActive()
                    if (owns()) when (result) {
                        is LocalStageResult.Staged -> {
                            review = null; completed = null; notice = LocalText("Original activation saved. Explicitly review/submit it under saved requests; no activation was submitted here.",
                                "حُفظ طلب التفعيل الأصلي. راجعه وأرسله صراحةً ضمن الطلبات المحفوظة؛ لم يُرسل أي تفعيل هنا.")
                            refreshPending = true
                        }
                        is LocalStageResult.Uncertain -> {
                            review = null; completed = null; notice = LocalText("Staging is uncertain. Recover the original saved operation ${result.operationId.value}; never replace its CAS or identity.",
                                "حفظ الطلب غير مؤكد. استعد العملية الأصلية ${result.operationId.value}؛ لا تستبدل إصدارها المتوقع أو هويتها.")
                            refreshPending = true
                        }
                        is LocalStageResult.NotStaged -> unavailable(result.problem)
                    }
                } }, enabled = ready(), modifier = Modifier.touchTarget()) {
                    Text(label("Stage separate employee activation — not submit", "حفظ طلب تفعيل الموظف المنفصل — دون إرسال"))
                }
            }
            if (editor == null && review == null && completed == null) status?.references?.let { references ->
                references.drop(page * 8).take(8).forEach { ref ->
                    OutlinedButton(onClick = { runAction {
                        val result = gateway.review(ref.originalActionId); currentCoroutineContext().ensureActive(); if (owns()) adopt(result)
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

internal data class ReturnRightsRuleEditor(val category: String = "", val reason: LocalReturnRightsPublicationReason = LocalReturnRightsPublicationReason.CHANGE_OF_MIND,
    val treatment: LocalReturnRightsPublicationTreatment = LocalReturnRightsPublicationTreatment.EVIDENCE_OR_POLICY_REVIEW,
    val window: String = "", val resolution: String = "", val exception: LocalReturnRightsPublicationException? = null) {
    fun toRule(): LocalReturnRightsPublicationRule = LocalReturnRightsPublicationRule(category, reason, treatment,
        window.takeIf { it.isNotEmpty() }?.toIntStrict(), resolution.takeIf { it.isNotEmpty() }?.toIntStrict(), exception)
}
internal data class ReturnRightsEditor(val publicationId: DomainId = DomainId.random(), val policy: String = "", val revision: String = "",
    val source: String = "", val accessDate: String = "", val english: String = "", val arabic: String = "",
    val rules: List<ReturnRightsRuleEditor> = listOf(ReturnRightsRuleEditor()), val frozen: Boolean = false) {
    fun toDraft(): LocalReturnRightsPublicationDraft {
        require(revision.matches(Regex("0|[1-9][0-9]*")))
        return LocalReturnRightsPublicationDraft(publicationId, DomainId(policy), Revision(revision.toLong()), "EG", source,
            accessDate, LocalText(english, arabic), rules.map { it.toRule() })
    }
}
private fun String.toIntStrict(): Int { require(matches(Regex("[1-9][0-9]*"))); return toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReturnRightsEditorFields(input: ReturnRightsEditor, enabled: Boolean, change: (ReturnRightsEditor) -> Unit) {
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String) = language.text(LocalText(en, ar))
    fun rule(index: Int, value: ReturnRightsRuleEditor) { change(input.copy(rules = input.rules.mapIndexed { i, r -> if (i == index) value else r })) }
    PublicationField(input.policy, label("Policy ID — explicit existing/new UUID", "معرف السياسة — UUID صريح قائم أو جديد"), enabled) { change(input.copy(policy = it)) }
    PublicationField(input.revision, label("Company revision — explicit decimal integer", "مراجعة الشركة — عدد عشري صريح"), enabled) { change(input.copy(revision = it)) }
    PublicationField(input.source, label("Documentary HTTPS source — not fetched", "المصدر الوثائقي HTTPS — لا يُجلب"), enabled) { change(input.copy(source = it)) }
    PublicationField(input.accessDate, label("Source access date (YYYY-MM-DD), NOT customer receipt", "تاريخ الاطلاع (YYYY-MM-DD)، وليس استلام العميل"), enabled) { change(input.copy(accessDate = it)) }
    PublicationField(input.english, label("Continuing-rights notice — English", "إشعار الحقوق المستمرة — الإنجليزية"), enabled) { change(input.copy(english = it)) }
    PublicationField(input.arabic, label("Continuing-rights notice — Arabic", "إشعار الحقوق المستمرة — العربية"), enabled) { change(input.copy(arabic = it)) }
    input.rules.forEachIndexed { index, row ->
        Text(label("Rule ${index + 1}", "القاعدة ${index + 1}"), style = MaterialTheme.typography.titleSmall)
        PublicationField(row.category, label("Exact category code", "رمز الفئة الدقيق"), enabled) { rule(index, row.copy(category = it)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LocalReturnRightsPublicationReason.entries.forEach { reason ->
                FilterChip(row.reason == reason, onClick = { if (enabled) rule(index, row.copy(reason = reason)) }, enabled = enabled,
                    label = { Text(language.text(reason.authoringLabel())) }, modifier = Modifier.touchTarget())
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LocalReturnRightsPublicationTreatment.entries.forEach { treatment ->
                FilterChip(row.treatment == treatment, onClick = { if (enabled) rule(index, row.copy(treatment = treatment)) }, enabled = enabled,
                    label = { Text(language.text(treatment.authoringLabel())) }, modifier = Modifier.touchTarget())
            }
        }
        PublicationField(row.window, label("Receipt window days; blank = not specified", "مهلة الاستلام بالأيام؛ فارغ = غير محدد"), enabled) { rule(index, row.copy(window = it)) }
        PublicationField(row.resolution, label("Covered resolution days; blank = not specified", "مهلة المعالجة المشمولة؛ فارغ = غير محدد"), enabled) { rule(index, row.copy(resolution = it)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(row.exception == null, onClick = { if (enabled) rule(index, row.copy(exception = null)) }, enabled = enabled,
                label = { Text(label("No exception specified", "لم يُحدد استثناء")) }, modifier = Modifier.touchTarget())
            LocalReturnRightsPublicationException.entries.forEach { basis ->
                FilterChip(row.exception == basis, onClick = { if (enabled) rule(index, row.copy(exception = basis)) }, enabled = enabled,
                    label = { Text(language.text(basis.authoringLabel())) }, modifier = Modifier.touchTarget())
            }
        }
        if (input.rules.size > 1) OutlinedButton(onClick = { if (enabled) change(input.copy(rules = input.rules.filterIndexed { i, _ -> i != index })) },
            enabled = enabled, modifier = Modifier.touchTarget()) { Text(label("Remove rule", "حذف القاعدة")) }
    }
    OutlinedButton(onClick = { if (enabled && input.rules.size < 128) change(input.copy(rules = input.rules + ReturnRightsRuleEditor())) },
        enabled = enabled && input.rules.size < 128, modifier = Modifier.touchTarget()) { Text(label("Add category/reason rule", "إضافة قاعدة فئة وسبب")) }
}

@Composable
private fun PublicationField(value: String, title: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { if (enabled) change(it) }, enabled = enabled,
        singleLine = true, label = { Text(title) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ReturnRightsPreview(preview: LocalReturnRightsPublicationPreview) {
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String) = language.text(LocalText(en, ar))
    val definition = preview.definition
    Text(label("Exact retained documentary review", "مراجعة وثائقية محفوظة دقيقة"), style = MaterialTheme.typography.titleSmall)
    Text("${preview.companyId.value} / ${definition.policyId.value} / ${definition.companyRevision.value}")
    Text(definition.publicationId.value)
    Text("${definition.jurisdictionCode} / ${definition.legalSourceUri} / ${definition.legalSourceAccessDate}")
    Text(definition.continuingRights.english)
    Text(definition.continuingRights.arabic)
    definition.rules.forEach { row ->
        Text("${row.categoryCode} / ${language.text(row.reason.authoringLabel())} / ${language.text(row.treatment.authoringLabel())}")
        Text(label("Receipt days: ${row.receiptWindowDays ?: "Not specified"}; resolution days: ${row.coveredResolutionDays ?: "Not specified"}",
            "أيام الاستلام: ${row.receiptWindowDays ?: "غير محدد"}؛ أيام المعالجة: ${row.coveredResolutionDays ?: "غير محدد"}"))
        Text(label("Exception: ", "الاستثناء: ") + (row.exceptionBasis?.let { language.text(it.authoringLabel()) }
            ?: label("Not specified", "غير محدد")))
    }
}

private fun LocalReturnRightsPublicationReason.authoringLabel(): LocalText = when (this) {
    LocalReturnRightsPublicationReason.CHANGE_OF_MIND -> LocalText("Change of mind", "العدول عن الشراء")
    LocalReturnRightsPublicationReason.WRONG_SUPPLIED_ITEM -> LocalText("Wrong supplied item", "توريد صنف خاطئ")
    LocalReturnRightsPublicationReason.DEFECTIVE_OR_DAMAGED -> LocalText("Defective or damaged", "معيب أو تالف")
    LocalReturnRightsPublicationReason.MERCHANT_PREPARATION_ERROR -> LocalText("Merchant preparation error", "خطأ تجهيز من التاجر")
    LocalReturnRightsPublicationReason.QUALITY_COMPLAINT -> LocalText("Quality complaint", "شكوى جودة")
    LocalReturnRightsPublicationReason.FAILED_DELIVERY -> LocalText("Failed delivery", "فشل التسليم")
    LocalReturnRightsPublicationReason.UNPERFORMED_SERVICE -> LocalText("Unperformed service", "خدمة لم تُنفذ")
}
private fun LocalReturnRightsPublicationTreatment.authoringLabel(): LocalText = when (this) {
    LocalReturnRightsPublicationTreatment.RECEIPT_WINDOW -> LocalText("Receipt window", "مهلة من استلام العميل")
    LocalReturnRightsPublicationTreatment.CATEGORY_EXCEPTION_REVIEW -> LocalText("Category exception review", "مراجعة استثناء الفئة")
    LocalReturnRightsPublicationTreatment.EVIDENCE_OR_POLICY_REVIEW -> LocalText("Evidence or policy review", "مراجعة الأدلة أو السياسة")
}
private fun LocalReturnRightsPublicationException.authoringLabel(): LocalText = when (this) {
    LocalReturnRightsPublicationException.PERISHABLE_OR_PREPARED_FOOD -> LocalText("Perishable or prepared food", "طعام سريع التلف أو مُجهز")
    LocalReturnRightsPublicationException.CORRECTLY_SUPPLIED_CUSTOMER_SPECIFIED_GOODS -> LocalText("Correctly supplied customer-specified goods", "سلع حسب طلب العميل وردت بصورة صحيحة")
    LocalReturnRightsPublicationException.VERIFIED_OPENED_HYGIENE_SENSITIVE_PACKAGING -> LocalText("Verified opened hygiene-sensitive packaging", "عبوة حساسة صحيًا ثبت فتحها")
}
private fun com.ukkera.pos.security.CompanyBusinessPublicationPhase.authoringLabel(): LocalText = when (this) {
    com.ukkera.pos.security.CompanyBusinessPublicationPhase.PREPARED -> LocalText("Prepared", "اقتراح محفوظ")
    com.ukkera.pos.security.CompanyBusinessPublicationPhase.SIGNING_INTENT -> LocalText("Signing outcome uncertain", "نتيجة التوقيع غير مؤكدة")
    com.ukkera.pos.security.CompanyBusinessPublicationPhase.COMPLETED -> LocalText("Signing completed", "اكتمل التوقيع")
}
