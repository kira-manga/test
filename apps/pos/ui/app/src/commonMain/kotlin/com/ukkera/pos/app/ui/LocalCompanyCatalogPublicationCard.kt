package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.catalog.CatalogAttribute
import com.ukkera.pos.catalog.CatalogAttributeValue
import com.ukkera.pos.catalog.VariantAxis
import com.ukkera.pos.catalog.VariantValue
import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.security.CompanyBusinessPublicationPhase
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.text.CharacterCodingException

/** Same-entry data only. Lazy-list disposal or adapter renewal must not lose original IDs. */
internal class LocalCompanyCatalogEntryDrafts {
    val lifetime = LocalConfigurationEntryLifetime()
    val retainedActionId = mutableStateOf<DomainId?>(null)
    val editor = mutableStateOf<CompanySkuEditor?>(null)
    val notice = mutableStateOf<LocalText?>(null)
    val hasDraft: Boolean get() = editor.value != null

    fun retireAttachment() {
        lifetime.retire()
        editor.value = editor.value?.let { if (it.frozen) it else it.copy(taxConfirmed = false) }
    }

    fun clear(denied: Boolean = false) {
        try { lifetime.retire(denied) }
        finally { editor.value = null; retainedActionId.value = null; notice.value = null }
    }
}

/** Active-entry-only native company form; never used by the training or central navigation root. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalCompanyCatalogPublicationCard(
    runtime: UkkeraRuntime,
    token: NavigationToken,
    drafts: LocalCompanyCatalogEntryDrafts,
    parentReady: Boolean,
    mayStartNew: Boolean,
    guardChanged: (draft: Boolean, working: Boolean) -> Unit,
    employeeAccessDenied: () -> Unit,
    activationStaged: (NavigationToken) -> Unit,
) {
    val session = runtime.localSessionFor(token) ?: return
    val runtimePresentation by runtime.presentation.collectAsState()
    val attachmentKey = runtimePresentation.localAttachment
    val presentationIdentity = drafts.lifetime.identity
    val gateway = remember(session, attachmentKey) { session.companyCatalog }
    val language = LocalAppLanguage.current
    if (!gateway.configured) {
        Notice(language.text(LocalText("Native local company publication is unavailable. No unsigned catalog or employee-chosen signing key is accepted.",
            "إصدار الشركة المحلية الأصلي غير متاح. لا يُقبل كتالوج غير موقع أو مفتاح توقيع يختاره الموظف.")), caution = true)
        return
    }
    val scope = rememberCoroutineScope()
    val latestToken by rememberUpdatedState(token)
    val latestParentReady by rememberUpdatedState(parentReady)
    val latestGuard by rememberUpdatedState(guardChanged)
    val latestStaged by rememberUpdatedState(activationStaged)
    val latestEmployeeAccessDenied by rememberUpdatedState(employeeAccessDenied)
    var alive by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(true) }
    var generation by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(0L) }
    var working by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(false) }
    var work by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<Job?>(null) }
    var retainedActionId by drafts.retainedActionId
    var pendingRefreshRequested by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(false) }
    // A failed initial read stays visible until explicit refresh. The sibling card's
    // busy/ready transitions must not create an automatic retry loop.
    var initialStatusRequested by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(drafts.lifetime.accessDenied) }
    var status by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<LocalCompanyCatalogStatus?>(null) }
    var editor by drafts.editor
    var review by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<LocalCompanyCatalogReview?>(null) }
    var completed by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<LocalCompanyCatalogCompleted?>(null) }
    var problem by remember(session, token.entryId, attachmentKey, presentationIdentity) {
        mutableStateOf<LocalServiceProblem?>(LocalServiceProblem.DENIED.takeIf { drafts.lifetime.accessDenied })
    }
    var nativeProblem by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<SelfOwnedInstallationFailureV1?>(null) }
    var notice by drafts.notice
    var page by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(0) }
    // NEVER saveable, journaled, logged or submitted as a branch employee password.
    var headLogin by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf("") }
    var headSecret by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf("") }
    var approvedExact by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(false) }

    fun owns(): Boolean = alive && drafts.lifetime.isCurrent(presentationIdentity) &&
        runtime.ownsLocalAttachment(session, latestToken, attachmentKey)
    fun callbackCurrent(): Boolean = owns() && runtime.navigator.isCurrent(latestToken)
    fun syncGuard() { if (owns()) latestGuard(editor != null || review != null, working) }
    fun unavailable(value: LocalServiceProblem, native: SelfOwnedInstallationFailureV1? = null) {
        if (value == LocalServiceProblem.DENIED) {
            headLogin = ""; headSecret = ""; approvedExact = false
            // Head authentication is a separate ceremony, not employee-session retirement.
            // Keep its exact original request/body; a refusal says nothing about earlier retention.
            if (native != SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED) {
                latestEmployeeAccessDenied()
                return
            }
        }
        problem = value; nativeProblem = native
        if (value == LocalServiceProblem.SESSION_ENDED) runtime.localSessionEnded(session)
    }
    fun adopt(result: LocalCompanyCatalogReviewResult) {
        when (result) {
            is LocalCompanyCatalogReviewResult.Review -> {
                retainedActionId = result.review.action.originalActionId
                review = result.review; completed = null; editor = null
                headLogin = result.review.action.headLogin; headSecret = ""; approvedExact = false
                notice = null
            }
            is LocalCompanyCatalogReviewResult.Completed -> {
                retainedActionId = result.publication.originalActionId
                completed = result.publication; review = null; editor = null; headSecret = ""; approvedExact = false
                notice = LocalText("The original company signing completed. Branch activation is separate; no stock or sale has been created by signing.",
                    "اكتمل توقيع الشركة الأصلي. تفعيل الفرع خطوة منفصلة؛ التوقيع لا ينشئ مخزونًا أو بيعًا.")
            }
            is LocalCompanyCatalogReviewResult.Unavailable -> unavailable(result.problem, result.nativeReason)
        }
    }
    fun runAction(requireParentReady: Boolean = true, secret: CharArray? = null, body: suspend () -> Unit): Boolean {
        if (!callbackCurrent() || working || requireParentReady && !latestParentReady) { secret?.fill('\u0000'); return false }
        working = true; problem = null; nativeProblem = null
        val ticket = ++generation
        syncGuard()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { if (owns() && generation == ticket) body(); currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (owns() && generation == ticket) unavailable(LocalServiceProblem.UNAVAILABLE) }
            finally { if (owns() && generation == ticket) {
                working = false; syncGuard()
                if (pendingRefreshRequested) { pendingRefreshRequested = false; latestStaged(runtime.navigator.token()) }
            } }
        }
        // Also clear a transferred password when cancellation wins before the body starts.
        job.invokeOnCompletion { secret?.fill('\u0000') }
        work = job
        job.start()
        return true
    }
    fun readStatus() {
        val started = runAction {
            val result = gateway.status()
            currentCoroutineContext().ensureActive()
            if (owns()) when (result) {
                is LocalCompanyCatalogStatusResult.Available -> {
                    drafts.lifetime.accessObserved(); status = result.status; page = 0
                }
                is LocalCompanyCatalogStatusResult.Unavailable -> { status = null; unavailable(result.problem, result.nativeReason) }
            }
        }
        if (started) initialStatusRequested = true
    }

    fun retirePresentation() {
        alive = false; generation++
        val retiringWork = work; work = null
        headSecret = ""; headLogin = ""; approvedExact = false
        review = null; completed = null; status = null; pendingRefreshRequested = false
        working = false
        retiringWork?.cancel()
    }
    DisposableEffect(session, token.entryId, attachmentKey, presentationIdentity) {
        alive = true
        val release = drafts.lifetime.attach(presentationIdentity, ::retirePresentation)
        syncGuard()
        onDispose {
            val wasCurrent = owns()
            release()
            retirePresentation()
            // Only the entry owner clears drafts. This card may merely have scrolled offscreen.
            if (wasCurrent) latestGuard(drafts.hasDraft, false)
        }
    }
    LaunchedEffect(session, token.entryId, attachmentKey, presentationIdentity, parentReady) {
        if (parentReady && !initialStatusRequested && !working) readStatus()
    }
    val ready = parentReady && !working && owns()
    fun label(english: String, arabic: String): String = language.text(LocalText(english, arabic))
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(label("Native local company catalog publication", "إصدار كتالوج الشركة المحلية الأصلي"))
            Notice(label("For this originally founded local company only, not central registration or an imported company. Propose a new stocked SKU with explicit tracking and quantity precision, review exact content, authenticate the separate named company head, then activate under the branch employee's current CATALOG_ACTIVATE / ADMINISTER permission.",
                "للشركة المحلية المؤسسة أصلًا فقط، وليس تسجيلًا مركزيًا أو شركة مستوردة. اقترح صنفًا مخزونًا جديدًا مع تحديد التتبع ودقة الكمية، وراجع المحتوى الدقيق، ثم تحقق من رئيس الشركة المنفصل المحدد، ثم فعِّل الصنف بصلاحية موظف الفرع الحالية CATALOG_ACTIVATE / ADMINISTER."))
            if (working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(label("Waiting for the original native/journal operation. Lock remains available; leaving never erases retained evidence.",
                    "بانتظار العملية الأصلية في الحفظ الأصلي أو السجل. يظل القفل متاحًا؛ المغادرة لا تمحو الأدلة المحفوظة."),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            problem?.let { Notice(localProblem(it), caution = true) }
            nativeProblem?.let {
                Text(label("Native result: ${it.name}. Read the original retained action before any continuation.",
                    "النتيجة الأصلية: ${it.name}. اقرأ الإجراء الأصلي المحفوظ قبل أي متابعة."), style = MaterialTheme.typography.bodySmall)
            }
            notice?.let { Notice(language.text(it), caution = true) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { readStatus() }, enabled = !working && parentReady, modifier = Modifier.touchTarget()) {
                    Text(label("Read retained publications", "قراءة الإصدارات المحفوظة"))
                }
                Button(onClick = {
                    if (callbackCurrent() && ready && mayStartNew && status?.mayPrepareNewItem == true) {
                        editor = CompanySkuEditor(); review = null; completed = null; headSecret = ""; approvedExact = false
                        retainedActionId = null
                        notice = null; problem = null; nativeProblem = null; syncGuard()
                    }
                }, enabled = ready && mayStartNew && status?.mayPrepareNewItem == true && editor == null && review == null,
                    modifier = Modifier.touchTarget()) { Text(label("Propose new stocked SKU", "اقتراح صنف مخزون جديد")) }
            }

            editor?.let { draft ->
                CompanySkuEditorForm(draft, ready && !draft.frozen, edit = { changed ->
                    if (callbackCurrent() && ready && !draft.frozen) { editor = changed; syncGuard() }
                })
                IdentifierText(label("Retained publication identity for this draft", "معرف الإصدار المحفوظ لهذه المسودة"), draft.publicationId.value)
                Button(onClick = {
                    if (!callbackCurrent() || working || !latestParentReady) return@Button
                    val exactDraft = try {
                        editor?.toDraft()
                    }
                    catch (_: IllegalArgumentException) { unavailable(LocalServiceProblem.INVALID_INPUT); null }
                    catch (_: CharacterCodingException) { unavailable(LocalServiceProblem.INVALID_INPUT); null }
                    if (exactDraft == null) return@Button
                    val template = draft.template
                    // Retention may complete even if its reply is lost. Keep the exact unit,
                    // conversion and commercial content until its original has been read.
                    editor = draft.copy(frozen = true)
                    runAction {
                        val result = when (template) {
                            CompanySkuTemplate.RETAIL -> gateway.prepare(exactDraft)
                            CompanySkuTemplate.INGREDIENT -> gateway.prepareIngredient(LocalCompanyIngredientSkuDraft(exactDraft))
                            CompanySkuTemplate.FOOD_OUTPUT -> gateway.prepareFoodOutput(LocalCompanyFoodOutputSkuDraft(exactDraft))
                        }
                        currentCoroutineContext().ensureActive()
                        if (owns()) adopt(result)
                    }
                }, enabled = ready && draft.complete && mayStartNew, modifier = Modifier.touchTarget()) {
                    Text(label("Retain exact proposal and review — NOT sign", "حفظ الاقتراح الدقيق ومراجعته — دون توقيع"))
                }
                if (draft.frozen) Notice(label("This proposal is frozen after retention was requested. Retry these exact bytes or read its original retained publication; no revision/signature is reconstructed.",
                    "جُمِّد هذا الاقتراح بعد طلب حفظه. أعد محاولة هذه البيانات الدقيقة أو اقرأ إصداره الأصلي المحفوظ؛ لا تُعاد صياغة مراجعة أو توقيع."), caution = true)
                OutlinedButton(onClick = {
                    if (callbackCurrent() && ready) {
                        editor = null; syncGuard()
                        notice = LocalText("Editor dismissed. Any native retained proposal remains under its original ID; read retained publications to continue it.",
                            "أُغلقت المسودة. يظل أي اقتراح محفوظ أصليًا بمعرفه الأصلي؛ اقرأ الإصدارات المحفوظة لمتابعته.")
                    }
                }, enabled = ready && !draft.frozen, modifier = Modifier.touchTarget()) { Text(label("Dismiss editor; preserve any retained proposal", "إغلاق المسودة مع حفظ أي اقتراح محفوظ")) }
            }

            if (review == null && completed == null) retainedActionId?.let { original ->
                IdentifierText(label("Original publication retained across session refresh", "الإصدار الأصلي المحفوظ بعد تجديد الجلسة"), original.value)
                OutlinedButton(onClick = {
                    runAction {
                        val result = gateway.review(original)
                        currentCoroutineContext().ensureActive()
                        if (owns()) adopt(result)
                    }
                }, enabled = ready, modifier = Modifier.touchTarget()) {
                    Text(label("Read SAME original publication before continuing", "قراءة الإصدار الأصلي نفسه قبل المتابعة"))
                }
            }

            review?.let { exact ->
                SectionHeading(label("Exact company-head approval review", "مراجعة موافقة رئيس الشركة الدقيقة"))
                CompanySkuPreview(exact.preview)
                IdentifierText(label("Original native action", "الإجراء الأصلي"), exact.action.originalActionId.value)
                IdentifierText(label("Endorsed signing key identity", "معرف مفتاح التوقيع المجاز"), exact.action.signingKeyId.value)
                IdentifierText(label("Company / organization", "الشركة أو المؤسسة"),
                    "${exact.action.proposal.company.companyId.value} / ${exact.action.proposal.company.organizationId.value}")
                IdentifierText(label("Root fingerprint", "بصمة جذر الثقة"), exact.action.rootFingerprint)
                IdentifierText(label("Content SHA-256", "بصمة المحتوى SHA-256"), exact.action.proposal.contentDigest.value)
                Text(label("Company: ${exact.action.companyName}. Separate head: ${exact.action.headName} (${exact.action.headLogin}).",
                    "الشركة: ${exact.action.companyName}. الرئيس المنفصل: ${exact.action.headName} (${exact.action.headLogin})."))
                Notice(label("This fresh ceremony endorses only the displayed exact content and one ephemeral Ed25519 key through the original native P-256 root. Purchasing-policy or installation approval is NOT catalog-signing authority. No employee login or grant replaces the head's password.",
                    "يجيز هذا الإجراء الجديد المحتوى الدقيق المعروض ومفتاح Ed25519 مؤقتًا واحدًا عبر جذر P-256 الأصلي. موافقة سياسة المشتريات أو التثبيت ليست صلاحية توقيع كتالوج. لا يحل دخول الموظف أو منحه محل كلمة مرور الرئيس."), caution = true)
                OutlinedTextField(headLogin, { if (callbackCurrent() && ready && it.length <= 128) headLogin = it },
                    label = { Text(label("Separate company-head login", "اسم دخول رئيس الشركة المنفصل")) }, singleLine = true,
                    enabled = ready, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(headSecret, { if (callbackCurrent() && ready && it.length <= 256) headSecret = it },
                    label = { Text(label("Company-head password (never employee password)", "كلمة مرور رئيس الشركة — ليست كلمة مرور الموظف")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = ready, modifier = Modifier.fillMaxWidth())
                CompanyCheck(label("I approve this exact item, price, tax snapshot, identities and native-root endorsement.",
                    "أوافق على هذا الصنف وسعره ولقطته الضريبية ومعرفاته وإجازة الجذر الأصلي بدقة."), approvedExact, ready) { approvedExact = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val password = headSecret.toCharArray(); headSecret = ""; approvedExact = false
                        if (!runAction(secret = password) {
                            try {
                                val result = gateway.approve(exact, headLogin, password)
                                currentCoroutineContext().ensureActive()
                                if (owns()) when (result) {
                                    is LocalCompanyCatalogApprovalResult.Completed -> adopt(LocalCompanyCatalogReviewResult.Completed(result.publication))
                                    is LocalCompanyCatalogApprovalResult.Unavailable -> unavailable(result.problem, result.nativeReason)
                                }
                            } finally { password.fill('\u0000') }
                        }) password.fill('\u0000')
                    }, enabled = ready && approvedExact && headSecret.length in 4..256 && headLogin.isNotBlank(), modifier = Modifier.touchTarget()) {
                        Text(label("Authenticate head and sign exact publication", "التحقق من الرئيس وتوقيع الإصدار الدقيق"))
                    }
                    OutlinedButton(onClick = {
                        headSecret = ""; approvedExact = false
                        runAction {
                            val result = gateway.review(exact.action.originalActionId)
                            currentCoroutineContext().ensureActive()
                            if (owns()) adopt(result)
                        }
                    }, enabled = ready, modifier = Modifier.touchTarget()) { Text(label("Refresh exact retained review", "تحديث مراجعة المحتوى المحفوظ الدقيق")) }
                }
            }

            completed?.let { signed ->
                SectionHeading(label("Company signing completed — activation separate", "اكتمل توقيع الشركة — التفعيل منفصل"))
                CompanySkuPreview(signed.preview)
                IdentifierText(label("Original signing / branch activation identity", "معرف التوقيع الأصلي وتفعيل الفرع"), signed.originalActionId.value)
                Button(onClick = {
                    runAction {
                        val result = gateway.stageActivation(signed.originalActionId)
                        currentCoroutineContext().ensureActive()
                        if (owns()) when (result) {
                            is LocalStageResult.Staged -> {
                                notice = LocalText("The exact branch activation request is retained (not newly signed). Review/submit or read its original result in saved operations below; only a durable APPLIED result confirms activation. Then configure stock separately.",
                                    "حُفظ طلب تفعيل الفرع الدقيق دون توقيع جديد. راجع الطلب وأرسله أو اقرأ نتيجته الأصلية في العمليات المحفوظة أدناه؛ تؤكد النتيجة الدائمة المُطبقة وحدها التفعيل. ثم اضبط المخزون منفصلًا.")
                                pendingRefreshRequested = true
                            }
                            is LocalStageResult.NotStaged -> unavailable(result.problem)
                            is LocalStageResult.Uncertain -> {
                                unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
                                notice = LocalText("Activation retention is uncertain. Keep original identity ${result.operationId.value}; read saved operations. Do not create a replacement operation or SKU.",
                                    "حفظ التفعيل غير مؤكد. احتفظ بالمعرف الأصلي ${result.operationId.value} واقرأ العمليات المحفوظة. لا تنشئ عملية أو صنفًا بديلًا.")
                                pendingRefreshRequested = true
                            }
                        }
                    }
                }, enabled = ready, modifier = Modifier.touchTarget()) { Text(label("Stage branch activation — no signing or stock", "حفظ تفعيل الفرع — دون توقيع أو مخزون")) }
            }

            status?.let { retained ->
                Text(label("Native retained publications: ${retained.references.size}. Master observation: ${retained.masterObservedAt.canonicalString()}.",
                    "الإصدارات المحفوظة أصلًا: ${retained.references.size}. رصد الجهاز الرئيسي: ${retained.masterObservedAt.canonicalString()}."),
                    style = MaterialTheme.typography.bodySmall)
                retained.references.drop(page * 8).take(8).forEach { reference ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        IdentifierText(label("Publication · ${reference.phase.name}", "الإصدار · ${reference.phase.name}"), reference.publicationId.value)
                        if (reference.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT) Notice(label(
                            "HELD signing intent: a key/signature may already have been used. No key regeneration, signing retry, replacement or automatic repair is permitted.",
                            "نية توقيع معلقة: قد استُخدم المفتاح أو التوقيع بالفعل. لا يُسمح بإعادة توليد المفتاح أو تكرار التوقيع أو الاستبدال أو الإصلاح التلقائي."), caution = true)
                        else OutlinedButton(onClick = {
                            runAction {
                                val result = gateway.review(reference.originalActionId)
                                currentCoroutineContext().ensureActive()
                                if (owns()) adopt(result)
                            }
                        }, enabled = ready && (editor == null || editor?.frozen == true && editor?.publicationId == reference.publicationId), modifier = Modifier.touchTarget()) {
                            Text(label("Read exact original action / completed output", "قراءة الإجراء الأصلي الدقيق أو ناتجه المكتمل"))
                        }
                    }
                }
                if (retained.references.size > 8) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (callbackCurrent()) page-- }, enabled = ready && page > 0, modifier = Modifier.touchTarget()) { Text(label("Previous retained page", "صفحة محفوظة سابقة")) }
                    OutlinedButton(onClick = { if (callbackCurrent()) page++ }, enabled = ready && (page + 1) * 8 < retained.references.size,
                        modifier = Modifier.touchTarget()) { Text(label("Next retained page", "صفحة محفوظة تالية")) }
                }
            }
        }
    }
}

internal enum class CompanySkuTemplate { RETAIL, INGREDIENT, FOOD_OUTPUT }

internal data class CompanyVariantAxisEditor(
    val identity: String = DomainId.random().value, val code: String = "", val values: String = "", val selected: String = "",
) {
    val complete: Boolean get() = identity.length == 36 && code.isNotBlank() &&
        values.lines().filter { it.isNotEmpty() }.size in 1..LocalCompanyProductDraftProfile.MAX_AXIS_VALUES &&
        selected.isNotEmpty() && selected in values.lines()
    fun axis(): VariantAxis = VariantAxis(DomainId(identity), Code.exact(code), values.lines().filter { it.isNotEmpty() }.map { Code.exact(it) })
    fun selection(): VariantValue = VariantValue(DomainId(identity), Code.exact(selected))
}

internal enum class CompanyAttributeType { TEXT, DECIMAL, FLAG }
internal data class CompanyProductAttributeEditor(val key: String = "", val type: CompanyAttributeType = CompanyAttributeType.TEXT, val value: String = "") {
    val complete: Boolean get() = key.isNotBlank() && when (type) {
        CompanyAttributeType.TEXT, CompanyAttributeType.DECIMAL -> value.isNotBlank()
        CompanyAttributeType.FLAG -> value == "true" || value == "false"
    }
    fun attribute(): CatalogAttribute = CatalogAttribute(Code.exact(key), when (type) {
        CompanyAttributeType.TEXT -> CatalogAttributeValue.Text(Code.exact(value))
        CompanyAttributeType.DECIMAL -> CatalogAttributeValue.Decimal(ExactDecimal.parse(value))
        CompanyAttributeType.FLAG -> CatalogAttributeValue.Flag(when (value) { "true" -> true; "false" -> false; else -> error("Invalid flag") })
    })
}

internal data class CompanySkuEditor(
    val publicationId: DomainId = DomainId.random(), val skuId: DomainId = DomainId.random(),
    val productId: DomainId = DomainId.random(), val baseUnitId: DomainId = DomainId.random(),
    val priceRuleId: DomainId = DomainId.random(), val taxPolicyId: DomainId = DomainId.random(),
    val englishName: String = "", val arabicName: String = "", val code: String = "", val barcodes: String = "",
    val price: String = "", val taxCategory: String = "", val taxPercent: String = "",
    val inclusion: LocalCompanyPriceTaxInclusion? = null, val canPurchase: Boolean = false, val taxConfirmed: Boolean = false,
    val quantityScale: Int = 0, val tracking: LocalCompanyStockTracking = LocalCompanyStockTracking.QUANTITY,
    val requiresExpiry: Boolean = false, val template: CompanySkuTemplate = CompanySkuTemplate.RETAIL, val frozen: Boolean = false,
    val returnPromiseEnabled: Boolean = false, val returnPolicyId: String = "", val returnPolicyRevision: String = "",
    val returnCategory: String = "", val returnEnglishTerms: String = "", val returnArabicTerms: String = "",
    val changeOfMindReceiptWindowDays: String = "", val defectiveReceiptWindowDays: String = "",
    val defectiveResolutionDays: String = "",
    val baseUnitIdentity: String = baseUnitId.value, val baseUnitCode: String = "",
    val unitDimension: UnitDimension? = null, val baseMaximumScale: Int? = null,
    val packEnabled: Boolean = false, val packUnitIdentity: String = DomainId.random().value,
    val packUnitCode: String = "", val packMaximumScale: Int? = null,
    val conversionIdentity: String = DomainId.random().value,
    val conversionNumerator: String = "", val conversionDenominator: String = "",
    val productIdentity: String = productId.value, val defineProduct: Boolean = false,
    val productEnglishName: String = "", val productArabicName: String = "", val productCategories: String = "",
    val variantAxes: List<CompanyVariantAxisEditor> = emptyList(), val productAttributes: List<CompanyProductAttributeEditor> = emptyList(),
    val declareAllowedVariantCombinations: Boolean = false,
    val allowedVariantCombinations: List<List<VariantValue>> = emptyList(),
) {
    val allowedVariantCombinationsComplete: Boolean get() {
        if (!defineProduct || !declareAllowedVariantCombinations) return true
        if (allowedVariantCombinations.size !in 1..LocalCompanyProductDraftProfile.MAX_ALLOWED_VARIANT_COMBINATIONS ||
            variantAxes.map { it.identity }.distinct().size != variantAxes.size) return false
        if (allowedVariantCombinations.map { row -> row.sortedBy { it.axisId.value } }.distinct().size != allowedVariantCombinations.size)
            return false
        if (allowedVariantCombinations.any { row ->
            row.size != variantAxes.size || row.map { it.axisId }.distinct().size != row.size ||
                variantAxes.any { axis -> row.singleOrNull { it.axisId.value == axis.identity }?.value?.original !in axis.values.lines() }
        }) return false
        return allowedVariantCombinations.any { row -> variantAxes.all { axis ->
            row.singleOrNull { it.axisId.value == axis.identity }?.value?.original == axis.selected
        } }
    }

    fun selectedVariantCombination(): List<VariantValue>? {
        if (variantAxes.size > 16 || variantAxes.any { !it.complete }) return null
        return try {
            variantAxes.map { it.selection() }.takeIf { row -> row.map { it.axisId }.distinct().size == row.size }
        } catch (_: IllegalArgumentException) { null }
    }

    val returnPromisePeriodsValid: Boolean get() =
        validOptionalReturnPromiseDays(changeOfMindReceiptWindowDays, 14) &&
            validOptionalReturnPromiseDays(defectiveReceiptWindowDays, 30) &&
            validOptionalReturnPromiseDays(defectiveResolutionDays, 1, 7) &&
            (defectiveReceiptWindowDays.isEmpty() == defectiveResolutionDays.isEmpty())

    val complete: Boolean get() = (englishName.isNotBlank() || arabicName.isNotBlank()) && code.isNotBlank() && price.isNotBlank() &&
        taxCategory.isNotBlank() && taxPercent.isNotBlank() && inclusion != null && taxConfirmed &&
        productIdentity.length == 36 && variantAxes.size <= 16 && variantAxes.all { it.complete } &&
        (!defineProduct || productCategories.lines().filter { it.isNotEmpty() }.size <= 32 &&
            productAttributes.size <= 32 && productAttributes.all { it.complete }) &&
        allowedVariantCombinationsComplete &&
        quantityScale in 0..6 && (tracking != LocalCompanyStockTracking.SERIAL || quantityScale == 0) &&
        baseUnitIdentity.length == 36 && baseUnitCode.isNotEmpty() && unitDimension != null &&
        baseMaximumScale?.let { it in 0..6 && quantityScale <= it } == true &&
        (tracking != LocalCompanyStockTracking.SERIAL || unitDimension == UnitDimension.COUNT) &&
        (!packEnabled || packUnitIdentity.length == 36 && conversionIdentity.length == 36 && packUnitCode.isNotEmpty() &&
            packMaximumScale?.let { it in 0..6 } == true &&
            conversionNumerator.toLongOrNull()?.let { it > 0 } == true && conversionDenominator.toLongOrNull()?.let { it > 0 } == true) &&
        (!requiresExpiry || tracking == LocalCompanyStockTracking.LOT) &&
        (!returnPromiseEnabled || template == CompanySkuTemplate.RETAIL && returnPolicyId.length == 36 &&
            returnPolicyRevision.toLongOrNull()?.let { it >= 0 } == true && returnCategory.isNotBlank() &&
            (returnEnglishTerms.isNotBlank() || returnArabicTerms.isNotBlank()) && returnPromisePeriodsValid) && when (template) {
            CompanySkuTemplate.RETAIL -> true
            CompanySkuTemplate.INGREDIENT -> canPurchase && tracking in setOf(LocalCompanyStockTracking.QUANTITY, LocalCompanyStockTracking.LOT)
            CompanySkuTemplate.FOOD_OUTPUT -> !canPurchase && tracking == LocalCompanyStockTracking.QUANTITY && !requiresExpiry
        }
    fun toDraft(): LocalCompanySkuDraft? {
        if (!complete) return null
        val selectedProductId = DomainId(productIdentity)
        val product = if (!defineProduct) null else LocalCompanyProductDraft(
            if (productEnglishName.isEmpty() && productArabicName.isEmpty()) englishName else productEnglishName,
            if (productEnglishName.isEmpty() && productArabicName.isEmpty()) arabicName else productArabicName,
            productCategories.lines().filter { it.isNotEmpty() }.map(::DomainId), variantAxes.map { it.axis() }, productAttributes.map { it.attribute() },
            allowedVariantCombinations = if (declareAllowedVariantCombinations) allowedVariantCombinations.map { it.toList() } else null)
        val base = CatalogUnit(DomainId(baseUnitIdentity), Code.exact(baseUnitCode), requireNotNull(unitDimension), requireNotNull(baseMaximumScale))
        val pack = if (!packEnabled) null else CatalogUnit(DomainId(packUnitIdentity), Code.exact(packUnitCode), base.dimension, requireNotNull(packMaximumScale))
        val conversions = if (pack == null) emptyList() else listOf(ProductUnitConversion(DomainId(conversionIdentity), Revision(0),
            selectedProductId, pack.id, base.id, UnitConversionRate.ratio(requireNotNull(conversionNumerator.toLongOrNull()),
                requireNotNull(conversionDenominator.toLongOrNull()))))
        return LocalCompanySkuDraft(publicationId, skuId, selectedProductId, base.id,
        priceRuleId, taxPolicyId, englishName, arabicName, code, barcodes.lines().filter { it.isNotEmpty() },
        price, taxCategory, taxPercent, requireNotNull(inclusion), canPurchase, quantityScale, tracking, requiresExpiry,
        if (!returnPromiseEnabled) null else LocalCompanyReturnPromiseDraft(DomainId(returnPolicyId),
            Revision(requireNotNull(returnPolicyRevision.toLongOrNull())), returnCategory, returnEnglishTerms, returnArabicTerms,
            changeOfMindReceiptWindowDays = optionalReturnPromiseDays(changeOfMindReceiptWindowDays),
            defectiveReceiptWindowDays = optionalReturnPromiseDays(defectiveReceiptWindowDays),
            defectiveResolutionDays = optionalReturnPromiseDays(defectiveResolutionDays)),
        listOfNotNull(base, pack), conversions, product, variantAxes.map { it.selection() }).also { it.validatePublicationInput() }
    }
}

/** Empty is unspecified; signs, spaces, leading zeroes and overflow never become a day value. */
private fun optionalReturnPromiseDays(content: String): Int? {
    if (content.isEmpty()) return null
    require(content.all { it in '0'..'9' }) { "Invalid commercial period" }
    val days = content.toIntOrNull()
    require(days != null && days.toString() == content) { "Invalid commercial period" }
    return days
}

private fun validOptionalReturnPromiseDays(content: String, minimum: Int, maximum: Int = Int.MAX_VALUE): Boolean =
    try { optionalReturnPromiseDays(content)?.let { it in minimum..maximum } ?: true }
    catch (_: IllegalArgumentException) { false }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanySkuEditorForm(value: CompanySkuEditor, enabled: Boolean, edit: (CompanySkuEditor) -> Unit) {
    val language = LocalAppLanguage.current
    fun label(english: String, arabic: String): String = language.text(LocalText(english, arabic))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompanySkuTemplate.entries.forEach { template ->
            FilterChip(value.template == template, { edit(value.copy(template = template, canPurchase = template == CompanySkuTemplate.INGREDIENT,
                tracking = if (template == CompanySkuTemplate.FOOD_OUTPUT || template == CompanySkuTemplate.INGREDIENT && value.tracking == LocalCompanyStockTracking.SERIAL)
                    LocalCompanyStockTracking.QUANTITY else value.tracking, requiresExpiry = value.requiresExpiry && template != CompanySkuTemplate.FOOD_OUTPUT)) },
                enabled = enabled && (!value.returnPromiseEnabled || template == CompanySkuTemplate.RETAIL), label = { Text(when (template) {
                    CompanySkuTemplate.RETAIL -> label("Retail stocked SKU", "صنف مخزون للتجزئة")
                    CompanySkuTemplate.INGREDIENT -> label("Raw ingredient", "مكوّن خام")
                    CompanySkuTemplate.FOOD_OUTPUT -> label("Made-to-order output", "منتج طعام حسب الطلب")
                }) }, modifier = Modifier.touchTarget())
        }
    }
    if (value.template == CompanySkuTemplate.RETAIL) Text(label("Template: new sellable stocked SKU. Choose exact unit definitions and optional same-dimension conversion. Stock starts unchanged; counted opening/receiving is separate.",
        "القالب: صنف مخزون جديد قابل للبيع. اختر تعريفات الوحدة الدقيقة وتحويلًا اختياريًا ضمن البُعد نفسه. لا يتغير المخزون؛ الجرد الافتتاحي أو الاستلام منفصل."))
    if (value.template != CompanySkuTemplate.RETAIL) Notice(label(if (value.template == CompanySkuTemplate.INGREDIENT)
        "Separate ingredient template: purchasable raw quantity/lot stock, not directly sellable, signed SKU revision 0. Recipe quantities use this exact base unit; receiving/opening remain separate."
    else "Separate food-output template: active, sellable MADE_TO_ORDER with QUANTITY tracking, no purchase/expiry. This NEW output is genuinely signed at SKU revision 1; no old revision-0 signature is rewritten. Recipe ingredients issue only on kitchen send.",
        if (value.template == CompanySkuTemplate.INGREDIENT)
            "قالب مكوّن منفصل: مخزون خام قابل للشراء وتتبع الكمية أو الدفعة، وليس للبيع المباشر، بمراجعة صنف موقعة 0. تستخدم الوصفة الوحدة الأساسية الدقيقة؛ الاستلام والجرد الافتتاحي منفصلان."
        else "قالب منتج طعام منفصل: نشط وقابل للبيع حسب الطلب MADE_TO_ORDER مع تتبع QUANTITY، ودون شراء أو صلاحية. يُوقَّع هذا المنتج الجديد فعلًا بمراجعة صنف 1؛ لا يُعدل توقيع قديم بمراجعة 0. تُصرف المكونات عند إرسال المطبخ فقط."), caution = true)
    CompanyField(label("English name", "الاسم بالإنجليزية"), value.englishName, 160, enabled) { edit(value.copy(englishName = it)) }
    CompanyField(label("Arabic name", "الاسم بالعربية"), value.arabicName, 160, enabled) { edit(value.copy(arabicName = it)) }
    Text(label("Shared product definition and selected variant", "تعريف المنتج المشترك والمتغير المختار"), style = MaterialTheme.typography.titleSmall)
    CompanyField(label("Exact product identity (reuse for sibling SKUs)", "معرف المنتج الدقيق — أعد استخدامه للأصناف التابعة"),
        value.productIdentity, 36, enabled) { edit(value.copy(productIdentity = it)) }
    CompanyCheck(label("Enter or edit product metadata (otherwise reuse it, or create a new simple product from SKU names)",
        "أدخل بيانات المنتج أو عدلها (وإلا أعد استخدامها، أو أنشئ منتجًا بسيطًا جديدًا بأسماء الصنف)"),
        value.defineProduct, enabled) { edit(value.copy(defineProduct = it)) }
    Notice(label("Product and SKU revisions are independent. The original native company history assigns a new product revision only for an explicit metadata change; signing and branch activation remain separate. A legacy product without version evidence requires a controlled migration, not a guessed revision.",
        "مراجعتا المنتج والصنف مستقلتان. يحدد سجل الشركة الأصلي مراجعة منتج جديدة عند تغيير بياناته صراحةً فقط؛ ويظل التوقيع منفصلًا عن تفعيل الفرع. يتطلب المنتج القديم بلا دليل مراجعة انتقالًا مضبوطًا، لا مراجعة مخمّنة."), caution = true)
    if (value.defineProduct) {
        CompanyField(label("Product English name (blank names use this new SKU's names)", "اسم المنتج بالإنجليزية — يستخدم الاسمين الحاليين إن تُرك الاسمان فارغين"),
            value.productEnglishName, 160, enabled) { edit(value.copy(productEnglishName = it)) }
        CompanyField(label("Product Arabic name", "اسم المنتج بالعربية"), value.productArabicName, 160, enabled) { edit(value.copy(productArabicName = it)) }
        OutlinedTextField(value.productCategories, { if (it.length <= 1183) edit(value.copy(productCategories = it)) },
            label = { Text(label("Category identities, one per line (metadata, not purchasing authority)", "معرفات الفئات، واحد لكل سطر — بيانات وليست صلاحية شراء")) },
            enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
    }
    value.variantAxes.forEachIndexed { index, axis ->
        Text(label("Variant axis ${index + 1} — declare values and select exactly one", "محور المتغير ${index + 1} — عرّف القيم واختر واحدة فقط"))
        fun update(changed: CompanyVariantAxisEditor) = edit(value.copy(variantAxes = value.variantAxes.mapIndexed { i, old -> if (i == index) changed else old }))
        CompanyField(label("Exact axis identity", "معرف المحور الدقيق"), axis.identity, 36, enabled) { update(axis.copy(identity = it)) }
        CompanyField(label("Axis code (for example size or color)", "رمز المحور — مثل المقاس أو اللون"), axis.code, 256, enabled) { update(axis.copy(code = it)) }
        OutlinedTextField(axis.values, { if (it.length <= LocalCompanyProductDraftProfile.MAX_AXIS_VALUES * 257 - 1)
                update(axis.copy(values = it, selected = axis.selected.takeIf { selected -> selected in it.lines() } ?: "")) },
            label = { Text(label("Declared values, one per line (up to ${LocalCompanyProductDraftProfile.MAX_AXIS_VALUES})",
                "القيم المعلنة، واحدة لكل سطر (حتى ${LocalCompanyProductDraftProfile.MAX_AXIS_VALUES})")) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            axis.values.lines().filter { it.isNotEmpty() }.take(LocalCompanyProductDraftProfile.MAX_AXIS_VALUES).forEach { choice ->
                FilterChip(axis.selected == choice, { update(axis.copy(selected = choice)) }, enabled = enabled,
                    label = { Text(choice) }, modifier = Modifier.touchTarget())
            }
        }
        OutlinedButton({ edit(value.copy(variantAxes = value.variantAxes.filterIndexed { i, _ -> i != index })) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(label("Remove axis", "إزالة المحور"))
        }
    }
    OutlinedButton({ edit(value.copy(variantAxes = value.variantAxes + CompanyVariantAxisEditor())) },
        enabled = enabled && value.variantAxes.size < 16, modifier = Modifier.touchTarget()) { Text(label("Add variant axis", "إضافة محور متغير")) }
    if (value.defineProduct) {
        CompanyCheck(label("Declare allowed variant combinations", "عرّف مجموعات المتغيرات المسموح بها"),
            value.declareAllowedVariantCombinations, enabled) { edit(value.copy(declareAllowedVariantCombinations = it)) }
        if (value.declareAllowedVariantCombinations) {
            Notice(label("Select one value per axis and add the complete combination. Repeat for each allowed combination, then select this SKU's combination. Every listed combination and this SKU's selection must use the declared axes and values.",
                "اختر قيمة لكل محور وأضف المجموعة الكاملة. كرر لكل مجموعة مسموح بها، ثم اختر مجموعة هذا الصنف. يجب أن تستخدم كل مجموعة مدرجة واختيار الصنف المحاور والقيم المعلنة."))
            val selectedCombination = value.selectedVariantCombination()
            OutlinedButton({ selectedCombination?.let { row ->
                edit(value.copy(allowedVariantCombinations = value.allowedVariantCombinations + listOf(row.toList())))
            } }, enabled = enabled && selectedCombination != null &&
                value.allowedVariantCombinations.size < LocalCompanyProductDraftProfile.MAX_ALLOWED_VARIANT_COMBINATIONS &&
                value.allowedVariantCombinations.none { row -> row.associate { it.axisId to it.value } == selectedCombination.associate { it.axisId to it.value } },
                modifier = Modifier.touchTarget()) { Text(label("Add selected allowed combination", "أضف المجموعة المختارة إلى المسموح")) }
            value.allowedVariantCombinations.forEachIndexed { index, row ->
                Text(label("Allowed combination ${index + 1}", "المجموعة المسموح بها ${index + 1}"))
                if (row.isEmpty()) Text(label("Simple product: no variant axes", "منتج بسيط: بلا محاور متغيرات"))
                row.forEach { choice -> IdentifierText(label("Axis / allowed value", "المحور / القيمة المسموح بها"),
                    "${choice.axisId.value} / ${choice.value.original}") }
                OutlinedButton({ edit(value.copy(allowedVariantCombinations = value.allowedVariantCombinations.filterIndexed { i, _ -> i != index })) },
                    enabled = enabled, modifier = Modifier.touchTarget()) { Text(label("Remove allowed combination", "أزل المجموعة المسموح بها")) }
            }
            if (!value.allowedVariantCombinationsComplete) Notice(label("To review this product, keep at least one complete allowed combination and select one of the listed combinations for this SKU.",
                "لمراجعة هذا المنتج، احتفظ بمجموعة مسموح بها كاملة واحدة على الأقل واختر للصنف إحدى المجموعات المدرجة."), caution = true)
        }
        value.productAttributes.forEachIndexed { index, attribute ->
            fun update(changed: CompanyProductAttributeEditor) = edit(value.copy(productAttributes = value.productAttributes.mapIndexed { i, old -> if (i == index) changed else old }))
            CompanyField(label("Attribute key", "مفتاح السمة"), attribute.key, 256, enabled) { update(attribute.copy(key = it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanyAttributeType.entries.forEach { type -> FilterChip(attribute.type == type, { update(attribute.copy(type = type, value = "")) },
                    enabled = enabled, label = { Text(type.name) }, modifier = Modifier.touchTarget()) }
            }
            if (attribute.type == CompanyAttributeType.FLAG) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("true", "false").forEach { flag -> FilterChip(attribute.value == flag, { update(attribute.copy(value = flag)) },
                    enabled = enabled, label = { Text(flag) }, modifier = Modifier.touchTarget()) }
            } else CompanyField(label("Exact attribute value", "قيمة السمة الدقيقة"), attribute.value, 256, enabled,
                decimal = attribute.type == CompanyAttributeType.DECIMAL) { update(attribute.copy(value = it)) }
            OutlinedButton({ edit(value.copy(productAttributes = value.productAttributes.filterIndexed { i, _ -> i != index })) }, enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(label("Remove attribute", "إزالة السمة"))
            }
        }
        OutlinedButton({ edit(value.copy(productAttributes = value.productAttributes + CompanyProductAttributeEditor())) },
            enabled = enabled && value.productAttributes.size < 32, modifier = Modifier.touchTarget()) { Text(label("Add typed attribute", "إضافة سمة محددة النوع")) }
    }
    CompanyField(label("Unique SKU code (ASCII; preserve identifiers)", "رمز صنف فريد بحروف ASCII — مع حفظ المعرفات"), value.code, 96, enabled) { edit(value.copy(code = it)) }
    OutlinedTextField(value.barcodes, { if (it.length <= 4127) edit(value.copy(barcodes = it)) },
        label = { Text(label("Barcodes, one per line (optional; preserve leading zeros)", "باركود واحد بكل سطر — اختياري مع حفظ الأصفار الأولى")) },
        enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
    Notice(label("Enter exact unit identities and codes. Reusing a known unit requires its exact definition; a typed identity or code is not authority. The Master checks signed native and activated history. Prices are per base unit; conversion alone never posts stock or changes a price.",
        "أدخل معرفات الوحدات ورموزها الدقيقة. تتطلب إعادة استخدام وحدة معروفة تعريفها الدقيق؛ إدخال المعرف أو الرمز لا يمنح صلاحية. يتحقق الجهاز الرئيسي من السجل الأصلي الموقع والمفعّل. الأسعار لكل وحدة أساسية؛ ولا يرحّل التحويل وحده مخزونًا أو يغير سعرًا."), caution = true)
    CompanyField(label("Exact base-unit identity (lowercase UUID)", "معرف الوحدة الأساسية الدقيق — UUID صغير الأحرف"),
        value.baseUnitIdentity, 36, enabled) { edit(value.copy(baseUnitIdentity = it)) }
    CompanyField(label("Exact base-unit code", "رمز الوحدة الأساسية الدقيق"), value.baseUnitCode, 256, enabled) { edit(value.copy(baseUnitCode = it)) }
    Text(label("Explicit unit dimension", "البُعد الصريح للوحدة"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UnitDimension.entries.forEach { dimension ->
            FilterChip(value.unitDimension == dimension, { edit(value.copy(unitDimension = dimension)) },
                enabled = enabled && (value.tracking != LocalCompanyStockTracking.SERIAL || dimension == UnitDimension.COUNT),
                label = { Text(unitDimensionText(dimension, language)) }, modifier = Modifier.touchTarget())
        }
    }
    Text(label("Base-unit maximum decimal precision", "أقصى دقة عشرية للوحدة الأساسية"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..6).forEach { scale ->
            FilterChip(value.baseMaximumScale == scale, { edit(value.copy(baseMaximumScale = scale)) },
                enabled = enabled && scale >= value.quantityScale, label = { Text(scale.toString()) }, modifier = Modifier.touchTarget())
        }
    }
    CompanyCheck(label("Add a same-dimension pack/source conversion (optional)", "إضافة تحويل عبوة أو وحدة مصدر ضمن البُعد نفسه — اختياري"),
        value.packEnabled, enabled) { edit(value.copy(packEnabled = it)) }
    if (value.packEnabled) {
        CompanyField(label("Exact source-unit identity (different from base)", "معرف وحدة المصدر الدقيق — مختلف عن الأساس"),
            value.packUnitIdentity, 36, enabled) { edit(value.copy(packUnitIdentity = it)) }
        CompanyField(label("Exact source-unit code", "رمز وحدة المصدر الدقيق"), value.packUnitCode, 256, enabled) { edit(value.copy(packUnitCode = it)) }
        Text(label("Source-unit maximum decimal precision (same dimension as base)", "أقصى دقة عشرية لوحدة المصدر — نفس بُعد الأساس"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { scale ->
                FilterChip(value.packMaximumScale == scale, { edit(value.copy(packMaximumScale = scale)) }, enabled = enabled,
                    label = { Text(scale.toString()) }, modifier = Modifier.touchTarget())
            }
        }
        CompanyField(label("Exact conversion identity — new version 0", "معرف التحويل الدقيق — مراجعة جديدة 0"),
            value.conversionIdentity, 36, enabled) { edit(value.copy(conversionIdentity = it)) }
        CompanyField(label("Base units per source unit: positive integer numerator", "وحدات الأساس لكل وحدة مصدر: بسط صحيح موجب"),
            value.conversionNumerator, 19, enabled) { edit(value.copy(conversionNumerator = it)) }
        CompanyField(label("Positive integer denominator", "مقام صحيح موجب"), value.conversionDenominator, 19, enabled) { edit(value.copy(conversionDenominator = it)) }
        Notice(label("Exact rational conversion only. No cross-dimension evidence is inferred; actual entered quantities must fit the signed precision without rounding.",
            "تحويل كسري دقيق فقط. لا يُستنتج دليل بين أبعاد مختلفة؛ ويجب أن تتوافق الكميات المدخلة مع الدقة الموقعة دون تقريب."), caution = true)
    }
    Text(label("Stock tracking (cannot be casually changed after publication)", "تتبع المخزون — لا يمكن تغييره دون ضوابط بعد الإصدار"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalCompanyStockTracking.entries.forEach { tracking ->
            FilterChip(value.tracking == tracking,
                { edit(value.copy(tracking = tracking, requiresExpiry = value.requiresExpiry && tracking == LocalCompanyStockTracking.LOT)) },
                enabled = enabled && (tracking != LocalCompanyStockTracking.SERIAL || value.quantityScale == 0) && when (value.template) {
                    CompanySkuTemplate.RETAIL -> true
                    CompanySkuTemplate.INGREDIENT -> tracking != LocalCompanyStockTracking.SERIAL
                    CompanySkuTemplate.FOOD_OUTPUT -> tracking == LocalCompanyStockTracking.QUANTITY
                },
                label = { Text(when (tracking) {
                    LocalCompanyStockTracking.QUANTITY -> label("Quantity", "الكمية")
                    LocalCompanyStockTracking.LOT -> label("Lot / batch", "الدفعة")
                    LocalCompanyStockTracking.SERIAL -> label("Serial number", "الرقم التسلسلي")
                }) }, modifier = Modifier.touchTarget())
        }
    }
    Text(label("Base-unit decimal places (0 = whole units; serials require 0)", "المنازل العشرية للوحدة الأساسية — 0 للوحدات الكاملة، وهو مطلوب للأرقام التسلسلية"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..6).forEach { scale ->
            FilterChip(value.quantityScale == scale, { edit(value.copy(quantityScale = scale)) },
                enabled = enabled && value.baseMaximumScale?.let { scale <= it } == true &&
                    (value.tracking != LocalCompanyStockTracking.SERIAL || scale == 0),
                label = { Text(scale.toString()) }, modifier = Modifier.touchTarget())
        }
    }
    if (value.tracking == LocalCompanyStockTracking.LOT) {
        CompanyCheck(label("Require expiry for every received lot", "تاريخ الصلاحية مطلوب لكل دفعة مستلمة"),
            value.requiresExpiry, enabled) { edit(value.copy(requiresExpiry = it)) }
    }
    CompanyField(label("EGP unit price (exact, no rounding)", "سعر الوحدة بالجنيه — دقيق دون تقريب"), value.price, 64, enabled, decimal = true) { edit(value.copy(price = it)) }
    CompanyField(label("Explicit tax category / classification", "الفئة أو التصنيف الضريبي الصريح"), value.taxCategory, 128, enabled) { edit(value.copy(taxCategory = it, taxConfirmed = false)) }
    CompanyField(label("Explicit tax percentage (enter 0 only if justified)", "النسبة الضريبية الصريحة — أدخل 0 فقط بمبرر"), value.taxPercent, 64, enabled, decimal = true) { edit(value.copy(taxPercent = it, taxConfirmed = false)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(value.inclusion == LocalCompanyPriceTaxInclusion.INCLUDED,
            { edit(value.copy(inclusion = LocalCompanyPriceTaxInclusion.INCLUDED, taxConfirmed = false)) }, enabled = enabled,
            label = { Text(label("Price includes tax", "السعر شامل الضريبة")) }, modifier = Modifier.touchTarget())
        FilterChip(value.inclusion == LocalCompanyPriceTaxInclusion.EXCLUDED,
            { edit(value.copy(inclusion = LocalCompanyPriceTaxInclusion.EXCLUDED, taxConfirmed = false)) }, enabled = enabled,
            label = { Text(label("Price excludes tax", "السعر غير شامل الضريبة")) }, modifier = Modifier.touchTarget())
    }
    if (value.template == CompanySkuTemplate.RETAIL) CompanyCheck(label("Purchasable / available to authorized receiving setup", "قابل للشراء والإعداد للاستلام المصرح به"), value.canPurchase, enabled) { edit(value.copy(canPurchase = it)) }
    if (value.template == CompanySkuTemplate.RETAIL) {
        CompanyCheck(label("Include an exact company commercial return promise in this signed SKU (optional)",
            "تضمين وعد إرجاع تجاري دقيق للشركة في هذا الصنف الموقع — اختياري"),
            value.returnPromiseEnabled, enabled) { edit(value.copy(returnPromiseEnabled = it)) }
        if (value.returnPromiseEnabled) {
            Notice(label("Enter only the actual commercial promise you intend the company head to sign. No legal periods, eligibility, waiver or delivery dates are inferred. New normalized sales preserve it; legacy quotes cannot silently discard it.",
                "أدخل فقط الوعد التجاري الفعلي الذي سيوقعه رئيس الشركة. لا تُستنتج مدد قانونية أو أهلية أو تنازل أو تواريخ تسليم. تحفظه المبيعات الجديدة المطبّعة؛ ولا يجوز لعروض البيع القديمة إسقاطه ضمنيًا."), caution = true)
            CompanyField(label("Commercial policy identity (canonical lowercase UUID)", "معرف السياسة التجارية — UUID صغير الأحرف"),
                value.returnPolicyId, 36, enabled) { edit(value.copy(returnPolicyId = it)) }
            CompanyField(label("Exact commercial policy revision", "مراجعة السياسة التجارية الدقيقة"),
                value.returnPolicyRevision, 19, enabled) { edit(value.copy(returnPolicyRevision = it)) }
            CompanyField(label("Return category code", "رمز فئة الإرجاع"), value.returnCategory, 128, enabled) { edit(value.copy(returnCategory = it)) }
            Notice(label("Leave unspecified periods blank; they stay unqualified. Enter whole days without signs, spaces or leading zeroes. Enter the defective-goods receipt window and resolution together. No periods, legal dates or eligibility are inferred from the English or Arabic terms.",
                "اترك المدد غير المحددة فارغة؛ وتظل غير مؤهلة. أدخل أيامًا صحيحة دون إشارات أو مسافات أو أصفار بادئة. أدخل مهلة استلام السلع المعيبة ومدة تسويتها معًا. لا تُستنتج مدد أو تواريخ قانونية أو أهلية من الشروط الإنجليزية أو العربية."), caution = true)
            CompanyOptionalDaysField(label("Change-of-mind receipt window (days, optional; at least 14)",
                "مهلة تغيير الرأي من الاستلام — أيام اختيارية، 14 على الأقل"), value.changeOfMindReceiptWindowDays, enabled,
                !validOptionalReturnPromiseDays(value.changeOfMindReceiptWindowDays, 14)) {
                edit(value.copy(changeOfMindReceiptWindowDays = it))
            }
            val defectivePairIncomplete = value.defectiveReceiptWindowDays.isEmpty() != value.defectiveResolutionDays.isEmpty()
            CompanyOptionalDaysField(label("Defective-goods receipt window (days, optional; at least 30)",
                "مهلة السلع المعيبة من الاستلام — أيام اختيارية، 30 على الأقل"), value.defectiveReceiptWindowDays, enabled,
                defectivePairIncomplete || !validOptionalReturnPromiseDays(value.defectiveReceiptWindowDays, 30)) {
                edit(value.copy(defectiveReceiptWindowDays = it))
            }
            CompanyOptionalDaysField(label("Defective-goods resolution (days, optional; 1–7)",
                "تسوية السلع المعيبة — أيام اختيارية، من 1 إلى 7"), value.defectiveResolutionDays, enabled,
                defectivePairIncomplete || !validOptionalReturnPromiseDays(value.defectiveResolutionDays, 1, 7)) {
                edit(value.copy(defectiveResolutionDays = it))
            }
            OutlinedTextField(value.returnEnglishTerms, { if (it.length <= 4_096) edit(value.copy(returnEnglishTerms = it)) },
                label = { Text(label("Exact English commercial terms", "الشروط التجارية الإنجليزية الدقيقة")) },
                enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
            OutlinedTextField(value.returnArabicTerms, { if (it.length <= 4_096) edit(value.copy(returnArabicTerms = it)) },
                label = { Text(label("Exact Arabic commercial terms", "الشروط التجارية العربية الدقيقة")) },
                enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
        }
    }
    CompanyCheck(label("I explicitly selected the appropriate tax category, percentage and price inclusion. This is not fiscal certification.",
        "اخترت صراحةً الفئة الضريبية والنسبة وتضمين الضريبة المناسبين. هذا ليس اعتمادًا ضريبيًا."), value.taxConfirmed, enabled) { edit(value.copy(taxConfirmed = it)) }
}

@Composable
private fun CompanySkuPreview(value: LocalCompanySkuPreview) {
    val language = LocalAppLanguage.current
    fun label(english: String, arabic: String): String = language.text(LocalText(english, arabic))
    Text("${value.englishName} / ${value.arabicName}", style = MaterialTheme.typography.titleMedium)
    Text(label("Code: ${value.code}. EGP ${value.unitPriceEgp} per base unit. Purchasable: ${value.canPurchase}.",
        "الرمز: ${value.code}. ${value.unitPriceEgp} جنيه لكل وحدة أساسية. قابل للشراء: ${value.canPurchase}."))
    Text(label("Tax category ${value.taxCategory}; ${value.taxPercent}%; ${value.taxInclusion.name}. Effective from ${value.effectiveFrom.canonicalString()} (master observation).",
        "الفئة الضريبية ${value.taxCategory}؛ ${value.taxPercent}٪؛ ${value.taxInclusion.name}. ساري من ${value.effectiveFrom.canonicalString()} — رصد الجهاز الرئيسي."))
    if (value.barcodes.isEmpty()) Text(label("Barcodes: none.", "الباركود: لا يوجد."))
    else value.barcodes.forEach { IdentifierText(label("Barcode", "الباركود"), it) }
    val tracking = when (value.tracking) {
        LocalCompanyStockTracking.QUANTITY -> label("Quantity", "الكمية")
        LocalCompanyStockTracking.LOT -> label("Lot / batch", "الدفعة")
        LocalCompanyStockTracking.SERIAL -> label("Serial number", "الرقم التسلسلي")
    }
    if (value.fulfillment == LocalCompanySkuFulfillment.RETAIL && value.canSell) Text(label("Catalog revision ${value.catalogRevision.value}; SKU revision ${value.skuRevision.value}; price/tax revisions 0. Tracking: $tracking; precision ${value.quantityScale}; expiry required: ${value.requiresExpiry}. Retail fulfillment, active/sellable.",
        "مراجعة الكتالوج ${value.catalogRevision.value}؛ مراجعة الصنف ${value.skuRevision.value}؛ مراجعتا السعر والضريبة 0. التتبع: $tracking؛ الدقة ${value.quantityScale}؛ الصلاحية مطلوبة: ${value.requiresExpiry}. تنفيذ تجزئة، ونشط وقابل للبيع."))
    if (value.fulfillment != LocalCompanySkuFulfillment.RETAIL || !value.canSell) Text(label(
        "Catalog revision ${value.catalogRevision.value}; genuinely signed SKU revision ${value.skuRevision.value}. ${value.fulfillment.name}; sellable ${value.canSell}; purchasable ${value.canPurchase}; tracking $tracking; precision ${value.quantityScale}; expiry ${value.requiresExpiry}. Stock remains unchanged.",
        "مراجعة الكتالوج ${value.catalogRevision.value}؛ مراجعة الصنف الموقعة فعلًا ${value.skuRevision.value}. ${value.fulfillment.name}؛ قابل للبيع ${value.canSell}؛ قابل للشراء ${value.canPurchase}؛ التتبع $tracking؛ الدقة ${value.quantityScale}؛ الصلاحية ${value.requiresExpiry}. يظل المخزون دون تغيير."))
    value.productDefinition?.let { product ->
        Text(label("Signed product revision ${product.revision.value}: ${product.definition.englishName} / ${product.definition.arabicName}",
            "مراجعة المنتج الموقعة ${product.revision.value}: ${product.definition.englishName} / ${product.definition.arabicName}"))
        product.definition.categoryIds.forEach { IdentifierText(label("Product category", "فئة المنتج"), it.value) }
        product.definition.variantAxes.forEach { axis ->
            IdentifierText(label("Product axis / declared values", "محور المنتج / القيم المعلنة"), "${axis.id.value} / ${axis.code.original} / ${axis.values.joinToString { it.original }}")
        }
        product.definition.allowedVariantCombinations?.forEachIndexed { index, row ->
            Text(label("Allowed combination ${index + 1}", "المجموعة المسموح بها ${index + 1}"))
            if (row.isEmpty()) Text(label("Simple product: no variant axes", "منتج بسيط: بلا محاور متغيرات"))
            row.forEach { choice -> IdentifierText(label("Axis / allowed value", "المحور / القيمة المسموح بها"),
                "${choice.axisId.value} / ${choice.value.original}") }
        } ?: Text(label("Allowed combinations: recorded by the signed SKUs; no separate matrix declared.",
            "المجموعات المسموح بها: مسجلة في الأصناف الموقعة؛ لم تُعرّف مصفوفة منفصلة."))
        product.definition.attributes.forEach { attribute ->
            val exact = when (val body = attribute.value) {
                is CatalogAttributeValue.Text -> body.value.original
                is CatalogAttributeValue.Decimal -> body.value.canonicalString()
                is CatalogAttributeValue.Flag -> body.value.toString()
            }
            Text("${attribute.key.original}: $exact")
        }
        value.variantValues.orEmpty().forEach { choice -> IdentifierText(label("Selected variant", "المتغير المختار"), "${choice.axisId.value} / ${choice.value.original}") }
    } ?: Notice(label("Legacy original: no signed product version. Product authority is unavailable, not inferred from SKU/catalog revision.",
        "إصدار قديم: لا مراجعة منتج موقعة. صلاحية المنتج غير متاحة، ولا تُستنتج من مراجعة الصنف أو الكتالوج."), caution = true)
    if (value.unitDefinitions == null) Notice(label("Legacy original: unit definitions were not signed. No dimension or conversion authority is inferred.",
        "إصدار أصلي قديم: لم تُوقَّع تعريفات الوحدات. لا يُستنتج بُعد أو صلاحية تحويل."), caution = true)
    value.unitDefinitions.orEmpty().forEach { unit ->
        IdentifierText(label("Signed unit identity / exact code", "معرف الوحدة الموقع أو رمزها الدقيق"), "${unit.id.value} / ${unit.code.original}")
        Text(label("Dimension: ${unitDimensionText(unit.dimension, language)}; maximum precision: ${unit.maximumScale}.",
            "البُعد: ${unitDimensionText(unit.dimension, language)}؛ أقصى دقة: ${unit.maximumScale}."))
    }
    value.conversions.forEach { conversion ->
        IdentifierText(label("Exact conversion identity / revision", "معرف التحويل الدقيق أو مراجعته"), "${conversion.id.value} / ${conversion.revision.value}")
        IdentifierText(label("Source → base unit", "وحدة المصدر إلى الوحدة الأساسية"), "${conversion.sourceUnitId.value} → ${conversion.targetUnitId.value}")
        Text(label("Exact base units per source unit: ${conversion.rate.canonicalString()}.",
            "وحدات الأساس الدقيقة لكل وحدة مصدر: ${conversion.rate.canonicalString()}."))
    }
    value.returnPromise?.let { promise ->
        Text(label("Exact company commercial return promise — review before head signing", "وعد الإرجاع التجاري الدقيق للشركة — راجعه قبل توقيع الرئيس"),
            style = MaterialTheme.typography.titleSmall)
        IdentifierText(label("Policy / revision / category", "السياسة أو المراجعة أو الفئة"),
            "${promise.policyId.value} / ${promise.policyRevision.value} / ${promise.categoryCode}")
        if (promise.englishTerms.isNotEmpty()) Text(promise.englishTerms)
        if (promise.arabicTerms.isNotEmpty()) Text(promise.arabicTerms)
        IdentifierText(label("Change-of-mind receipt window (days)", "مهلة تغيير الرأي من الاستلام — أيام"),
            promise.changeOfMindReceiptWindowDays?.toString() ?: label("Unspecified", "غير محدد"))
        IdentifierText(label("Defective-goods receipt window (days)", "مهلة السلع المعيبة من الاستلام — أيام"),
            promise.defectiveReceiptWindowDays?.toString() ?: label("Unspecified", "غير محدد"))
        IdentifierText(label("Defective-goods resolution (days)", "تسوية السلع المعيبة — أيام"),
            promise.defectiveResolutionDays?.toString() ?: label("Unspecified", "غير محدد"))
        Notice(label("These exact terms are signed with this SKU. Unspecified periods stay unqualified. No legal dates or return eligibility are guessed; customer-receipt evidence remains separate.",
            "تُوقَّع هذه الشروط الدقيقة مع هذا الصنف. تظل المدد غير المحددة غير مؤهلة. لا تُخمَّن تواريخ قانونية أو أهلية إرجاع؛ ويظل دليل استلام العميل منفصلًا."), caution = true)
    }
    IdentifierText(label("Publication", "الإصدار"), value.publicationId.value)
    IdentifierText(label("SKU / product", "الصنف أو المنتج"), "${value.skuId.value} / ${value.productId.value}")
    IdentifierText(label("Base-unit identity", "معرف الوحدة الأساسية"), value.baseUnitId.value)
    IdentifierText(label("Price rule / tax policy", "قاعدة السعر أو السياسة الضريبية"), "${value.priceRuleId.value} / ${value.taxPolicyId.value}")
}

private fun unitDimensionText(value: UnitDimension, language: AppLanguage): String = language.text(when (value) {
    UnitDimension.COUNT -> LocalText("Count", "العدد")
    UnitDimension.MASS -> LocalText("Mass", "الكتلة")
    UnitDimension.VOLUME -> LocalText("Volume", "الحجم")
    UnitDimension.LENGTH -> LocalText("Length", "الطول")
    UnitDimension.AREA -> LocalText("Area", "المساحة")
    UnitDimension.DURATION -> LocalText("Duration", "المدة")
})

@Composable
private fun CompanyField(title: String, content: String, bound: Int, enabled: Boolean,
    decimal: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(content, { if (it.length <= bound) change(it) }, label = { Text(title) }, enabled = enabled,
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CompanyOptionalDaysField(title: String, content: String, enabled: Boolean, invalid: Boolean,
    change: (String) -> Unit) {
    OutlinedTextField(content, { if (it.length <= 64) change(it) }, label = { Text(title) }, enabled = enabled,
        isError = invalid, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CompanyCheck(label: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().touchTarget().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = changed),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f))
    }
}
