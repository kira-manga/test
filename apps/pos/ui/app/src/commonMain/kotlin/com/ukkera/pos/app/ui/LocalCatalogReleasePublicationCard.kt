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
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationPhase
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** The entry retains exact unsigned choices and original IDs, never credentials or native reviews. */
internal class LocalCatalogReleaseEntryDrafts {
    val lifetime = LocalConfigurationEntryLifetime()
    val editor = mutableStateOf<CatalogReleaseEditor?>(null)
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

/** Revision and predecessor are explicit retained observations, not editable branch/SKU counters. */
internal data class CatalogReleaseEditor(
    val publicationId: DomainId = DomainId.random(),
    val catalogId: String = "",
    val observedRevision: Revision? = null,
    val previous: LocalCatalogReleasePublicationReference? = null,
    val members: List<LocalCatalogReleaseMemberInput> = emptyList(),
    val frozenDraft: LocalCatalogReleasePublicationDraft? = null,
) {
    fun toDraft(): LocalCatalogReleasePublicationDraft = frozenDraft?.snapshot() ?: LocalCatalogReleasePublicationDraft(
        publicationId, DomainId(catalogId), requireNotNull(observedRevision), previous, members.toList())
}

@Composable
private fun CatalogReleaseMember(member: LocalCatalogReleaseMemberInput, position: Int) {
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String) = language.text(LocalText(en, ar))
    Text(label("Member $position — SKU ${member.skuId.value}; revision ${member.skuRevision.value}",
        "العضو $position — الصنف ${member.skuId.value}؛ المراجعة ${member.skuRevision.value}"))
    Text(label("SKU publication: ${member.publicationId.value}", "منشور الصنف: ${member.publicationId.value}"))
    Text(label("Exact SKU canonical-content SHA-256: ${member.contentDigest.value}",
        "بصمة محتوى الصنف المعياري الدقيق SHA-256: ${member.contentDigest.value}"))
    Text(label("Product: ${member.productId.value}; independent revision: ${member.productRevision.value}",
        "المنتج: ${member.productId.value}؛ مراجعته المستقلة: ${member.productRevision.value}"))
}

@Composable
private fun CatalogReleasePreview(preview: LocalCatalogReleasePublicationPreview) {
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String) = language.text(LocalText(en, ar))
    val value = preview.definition
    Text(label("Exact complete retained catalog-release manifest", "قائمة إصدار الكتالوج الكاملة الدقيقة المحفوظة"),
        style = MaterialTheme.typography.titleSmall)
    Text(label("Company: ${preview.companyId.value}", "الشركة: ${preview.companyId.value}"))
    Text(label("Stable catalog: ${value.catalogId.value}", "الكتالوج الثابت: ${value.catalogId.value}"))
    Text(label("Release publication: ${value.publicationId.value}; independent revision: ${value.revision.value}",
        "منشور الإصدار: ${value.publicationId.value}؛ مراجعته المستقلة: ${value.revision.value}"))
    value.previous?.let { previous ->
        Text(label("Previous catalog: ${previous.catalogId.value}; publication: ${previous.publicationId.value}; revision: ${previous.revision.value}",
            "كتالوج السلف: ${previous.catalogId.value}؛ المنشور: ${previous.publicationId.value}؛ المراجعة: ${previous.revision.value}"))
        Text(label("Previous exact canonical-content SHA-256: ${previous.contentDigest.value}",
            "بصمة محتوى السلف المعياري الدقيق SHA-256: ${previous.contentDigest.value}"))
    } ?: Text(label("Actual native revision-0 genesis; no predecessor", "بداية أصلية فعلية بمراجعة ٠؛ لا سلف"))
    Text(label("All ${value.members.size} ordered members follow; none is inferred or omitted.",
        "تليها جميع الأعضاء المرتبين وعددهم ${value.members.size}؛ لا يُستنتج أو يُحذف أي عضو."))
    value.members.forEachIndexed { index, member -> CatalogReleaseMember(member, index + 1) }
}

private fun CompanyBusinessPublicationPhase.releaseLabel(): LocalText = when (this) {
    CompanyBusinessPublicationPhase.PREPARED -> LocalText("Prepared", "اقتراح محفوظ")
    CompanyBusinessPublicationPhase.SIGNING_INTENT -> LocalText("Signing outcome uncertain", "نتيجة التوقيع غير مؤكدة")
    CompanyBusinessPublicationPhase.COMPLETED -> LocalText("Signing completed", "اكتمل التوقيع")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalCatalogReleasePublicationCard(runtime: UkkeraRuntime, token: NavigationToken,
    drafts: LocalCatalogReleaseEntryDrafts, parentReady: Boolean, mayStartNew: Boolean,
    guardChanged: (draft: Boolean, working: Boolean) -> Unit, employeeAccessDenied: () -> Unit,
    activationStaged: (NavigationToken) -> Unit) {
    val session = runtime.localSessionFor(token) ?: return
    val presentation by runtime.presentation.collectAsState()
    val attachment = presentation.localAttachment
    val identity = drafts.lifetime.identity
    val gateway = remember(session, attachment) { session.companyCatalog.releases }
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String) = language.text(LocalText(en, ar))
    if (!gateway.configured) {
        Notice(label("Native company catalog-release authoring is unavailable. No SKU publication, branch cursor, unsigned manifest or employee-selected signing key is accepted as a release.",
            "إنشاء إصدار كتالوج الشركة الأصلي غير متاح. لا يُقبل منشور صنف أو مؤشر فرع أو قائمة غير موقعة أو مفتاح توقيع يختاره الموظف كإصدار."), caution = true)
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
    var status by remember(session, token.entryId, attachment, identity) { mutableStateOf<LocalCatalogReleasePublicationStatus?>(null) }
    var statusRequested by remember(session, token.entryId, attachment, identity) { mutableStateOf(drafts.lifetime.accessDenied) }
    var review by remember(session, token.entryId, attachment, identity) { mutableStateOf<LocalCatalogReleasePublicationReview?>(null) }
    var completed by remember(session, token.entryId, attachment, identity) { mutableStateOf<LocalCatalogReleasePublicationCompleted?>(null) }
    var problem by remember(session, token.entryId, attachment, identity) {
        mutableStateOf<LocalServiceProblem?>(LocalServiceProblem.DENIED.takeIf { drafts.lifetime.accessDenied })
    }
    var headLogin by remember(session, token.entryId, attachment, identity) { mutableStateOf("") }
    var headSecret by remember(session, token.entryId, attachment, identity) { mutableStateOf("") }
    var exactApproved by remember(session, token.entryId, attachment, identity) { mutableStateOf(false) }
    var page by remember(session, token.entryId, attachment, identity) { mutableStateOf(0) }
    var memberPage by remember(session, token.entryId, attachment, identity) { mutableStateOf(0) }
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
    fun adopt(result: LocalCatalogReleasePublicationResult, expectedOriginal: DomainId? = null,
        expectedDraft: LocalCatalogReleasePublicationDraft? = null) {
        val preview = when (result) {
            is LocalCatalogReleasePublicationResult.Review -> result.review.preview
            is LocalCatalogReleasePublicationResult.Completed -> result.publication.preview
            is LocalCatalogReleasePublicationResult.Unavailable -> null
        }
        val actionId = when (result) {
            is LocalCatalogReleasePublicationResult.Review -> result.review.action.originalActionId
            is LocalCatalogReleasePublicationResult.Completed -> result.publication.originalActionId
            is LocalCatalogReleasePublicationResult.Unavailable -> null
        }
        if (preview != null && (preview.companyId != session.display.companyId ||
                expectedDraft != null && preview.definition != expectedDraft ||
                expectedOriginal != null && actionId != expectedOriginal)) {
            unavailable(LocalServiceProblem.UNAVAILABLE); return
        }
        if (result is LocalCatalogReleasePublicationResult.Review) {
            val proposal = result.review.action.proposal
            val definition = result.review.preview.definition
            if (proposal.kind != CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 ||
                proposal.company.companyId != result.review.preview.companyId ||
                proposal.publicationId != definition.publicationId || proposal.entityId != definition.catalogId) {
                unavailable(LocalServiceProblem.UNAVAILABLE); return
            }
        }
        when (result) {
            is LocalCatalogReleasePublicationResult.Review -> {
                original = result.review.action.originalActionId
                review = result.review; completed = null; editor = null
                headLogin = result.review.action.headLogin; headSecret = ""; exactApproved = false
                // Only successful native PREPARED re-review permits another explicit approval.
                approvalHeld = false
            }
            is LocalCatalogReleasePublicationResult.Completed -> {
                original = result.publication.originalActionId
                completed = result.publication; review = null; editor = null
                headLogin = ""; headSecret = ""; exactApproved = false; approvalHeld = true
                notice = LocalText("Company release signing is complete. This signing/recovery step did not stage or submit branch activation; review any existing saved activation separately.",
                    "اكتمل توقيع إصدار الشركة. لم تحفظ أو ترسل خطوة التوقيع أو الاستعادة هذه طلب تفعيل للفرع؛ راجع أي طلب تفعيل محفوظ قائم على حدة.")
            }
            is LocalCatalogReleasePublicationResult.Unavailable -> unavailable(result.problem, result.nativeReason)
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
    fun readStatus(catalogId: DomainId? = null, bindEditor: Boolean = false) {
        if (runAction {
            val result = gateway.status(catalogId)
            currentCoroutineContext().ensureActive()
            if (owns()) when (result) {
                is LocalCatalogReleasePublicationStatusResult.Available -> {
                    val references = result.status.references
                    val source = result.status.source
                    if (references.size > 256 || references.any { it.kind != CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 } ||
                        references.map { it.originalActionId }.distinct().size != references.size ||
                        references.map { it.publicationId }.distinct().size != references.size ||
                        (catalogId == null) != (source == null) || source != null && (source.catalogId != catalogId ||
                            source.members.size > 256 || source.members.map { it.member.skuId }.distinct().size != source.members.size ||
                            source.members.map { it.member.publicationId }.distinct().size != source.members.size)) {
                        status = null; unavailable(LocalServiceProblem.UNAVAILABLE)
                    } else {
                        status = result.status; page = 0; memberPage = 0; drafts.lifetime.accessObserved()
                        val input = editor
                        if (bindEditor && source != null && input != null && input.frozenDraft == null && input.catalogId == source.catalogId.value) {
                            // Explicit source loading starts a new unsaved selection. It cannot
                            // change the immutable proposal after native retention was requested.
                            editor = input.copy(observedRevision = source.nextRevision, previous = source.previous, members = emptyList())
                            notice = LocalText("Exact native sequence and protected member choices loaded. Select 1–64 members in the intended order; none was selected automatically.",
                                "قُرئت السلسلة الأصلية الدقيقة وخيارات الأعضاء المحمية. اختر من ١ إلى ٦٤ عضوًا بالترتيب المقصود؛ لم يُختر أي عضو تلقائيًا.")
                        }
                    }
                }
                is LocalCatalogReleasePublicationStatusResult.Unavailable -> {
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
            Text(label("Company catalog-release publication", "منشور إصدار كتالوج الشركة"), style = MaterialTheme.typography.titleMedium)
            Notice(label("A release is a separately signed complete ordered manifest of exact completed SKU/product coordinates. Signing is not activation. Activation changes no SKU, assortment, stock, purchasing allowance, invoice or money, and does not qualify purchasing or arrival policy.",
                "الإصدار قائمة كاملة مرتبة وموقعة منفصلًا بإحداثيات الأصناف والمنتجات المكتملة الدقيقة. التوقيع ليس تفعيلًا. لا يغيّر التفعيل صنفًا أو تشكيلة أو مخزونًا أو سماح شراء أو فاتورة أو مالًا، ولا يؤهل سياسة شراء أو وصول."), caution = true)
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            problem?.let { Notice(label("Operation unavailable: ${it.name}. Recover the original action; do not assume it was cancelled.",
                "العملية غير متاحة: ${it.name}. استعد الإجراء الأصلي؛ لا تفترض إلغاءه."), caution = true) }
            notice?.let { Notice(language.text(it), caution = true) }
            OutlinedButton(onClick = { readStatus() }, enabled = ready(), modifier = Modifier.touchTarget()) {
                Text(label("Refresh current access / retained release originals", "تحديث الصلاحيات الحالية / أصول الإصدارات المحفوظة"))
            }
            if (editor == null && review == null && completed == null) {
                Button(onClick = {
                    if (ready() && editor == null && review == null && completed == null) {
                        editor = CatalogReleaseEditor(); original = null; approvalHeld = false; notice = null; syncGuard()
                    }
                }, enabled = ready(), modifier = Modifier.touchTarget()) {
                    Text(label("Enter a catalog ID to inspect release authoring", "إدخال معرف كتالوج لفحص إنشاء إصدار"))
                }
            }
            editor?.let { input ->
                fun editingCurrent(): Boolean = ready() && editor === input && input.frozenDraft == null
                val editable = editingCurrent()
                Notice(label("Entering a catalog ID is only unsigned input, not permission. Loading its exact source and retaining or signing a release each require current protected access.",
                    "إدخال معرف كتالوج مجرد مدخل غير موقع، وليس صلاحية. تتطلب قراءة مصدره الدقيق وحفظ إصدار أو توقيعه صلاحيات محمية حالية."), caution = true)
                OutlinedTextField(input.catalogId, onValueChange = {
                    if (editingCurrent() && it.length <= 36) {
                        editor = input.copy(catalogId = it, observedRevision = null, previous = null, members = emptyList())
                        status = status?.copy(source = null); syncGuard()
                    }
                }, enabled = editable, singleLine = true,
                    label = { Text(label("Stable existing/new catalog ID — explicit UUID", "معرف كتالوج ثابت قائم أو جديد — UUID صريح")) },
                    modifier = Modifier.fillMaxWidth())
                Text(label("Original release publication: ${input.publicationId.value}", "منشور الإصدار الأصلي: ${input.publicationId.value}"))
                OutlinedButton(onClick = {
                    if (editingCurrent()) {
                        val id = try { DomainId(input.catalogId) } catch (_: IllegalArgumentException) { unavailable(LocalServiceProblem.INVALID_INPUT); null }
                        if (id != null) readStatus(id, bindEditor = true)
                    }
                }, enabled = editable, modifier = Modifier.touchTarget()) {
                    Text(label("Load exact native release sequence and current member choices", "قراءة سلسلة الإصدار الأصلية الدقيقة وخيارات الأعضاء الحالية"))
                }
                input.observedRevision?.let { revision ->
                    Text(label("Independent next release revision: ${revision.value}", "مراجعة الإصدار المستقلة التالية: ${revision.value}"))
                    input.previous?.let { previous -> Text(label(
                        "Exact predecessor: ${previous.publicationId.value} / ${previous.revision.value} / ${previous.contentDigest.value}",
                        "السلف الدقيق: ${previous.publicationId.value} / ${previous.revision.value} / ${previous.contentDigest.value}")) }
                        ?: Text(label("Authenticated native genesis; no predecessor", "بداية أصلية موثقة؛ لا سلف"))
                }
                val source = status?.source?.takeIf { it.catalogId.value == input.catalogId }
                if (source != null && input.frozenDraft == null) {
                    source.active?.let { active -> Text(label(
                        "Current active release: ${active.publicationId.value}; release revision ${active.revision.value}; storage ${source.activeStorageRevision?.value}",
                        "الإصدار النشط الحالي: ${active.publicationId.value}؛ مراجعة الإصدار ${active.revision.value}؛ التخزين ${source.activeStorageRevision?.value}")) }
                        ?: Notice(label(if (source.activeStorageRevision == null)
                            "The protected read proved no active release pointer. Signing still does not activate this draft."
                        else "The retained active pointer exists, but its members no longer match current SKUs. This read supplies no current release content.",
                            if (source.activeStorageRevision == null) "أثبتت القراءة المحمية عدم وجود مؤشر إصدار نشط. لا يزال التوقيع لا يفعّل هذه المسودة."
                            else "يوجد مؤشر نشط محفوظ، لكن أعضاءه لم يعودوا يطابقون الأصناف الحالية. لا توفر هذه القراءة محتوى إصدار حاليًا."), caution = true)
                    Text(label("Only authenticated completed originals matching protected current SKU/product details are offered.",
                        "تُعرض فقط الأصول المكتملة الموثقة المطابقة لتفاصيل الأصناف والمنتجات الحالية المحمية."))
                    source.members.drop(memberPage * 8).take(8).forEach { choice ->
                        val selected = input.members.any { it.skuId == choice.member.skuId }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(language.text(choice.skuNames) + " · " + choice.skuCode)
                            Text(label("Product: ", "المنتج: ") + language.text(choice.productNames))
                            CatalogReleaseMember(choice.member, source.members.indexOf(choice) + 1)
                            OutlinedButton(onClick = {
                                if (editingCurrent() && status?.source === source &&
                                    input.members.none { it.skuId == choice.member.skuId } && input.members.size < 64) {
                                    editor = input.copy(members = input.members.toList() + choice.member); syncGuard()
                                }
                            }, enabled = editable && !selected && input.members.size < 64, modifier = Modifier.touchTarget()) {
                                Text(label(if (selected) "Already selected" else "Append this EXACT member",
                                    if (selected) "مختار بالفعل" else "إلحاق هذا العضو الدقيق"))
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (memberPage > 0) OutlinedButton(onClick = { if (ready()) memberPage-- }, enabled = ready(), modifier = Modifier.touchTarget()) {
                            Text(label("Previous member choices", "خيارات الأعضاء السابقة"))
                        }
                        if ((memberPage + 1) * 8 < source.members.size) OutlinedButton(onClick = { if (ready()) memberPage++ }, enabled = ready(), modifier = Modifier.touchTarget()) {
                            Text(label("Next member choices", "خيارات الأعضاء التالية"))
                        }
                    }
                    if (source.members.isEmpty()) Notice(label("No qualified current member is available. Missing or legacy provenance is not a new product, release source or grant.",
                        "لا يتوفر عضو حالي مؤهل. الإثبات المفقود أو القديم ليس منتجًا جديدًا أو مصدر إصدار أو صلاحية."), caution = true)
                }
                Text(label("Selected complete manifest — ${input.members.size}/64 in this exact order", "القائمة الكاملة المختارة — ${input.members.size}/٦٤ بهذا الترتيب الدقيق"))
                input.members.forEachIndexed { index, member ->
                    CatalogReleaseMember(member, index + 1)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (editingCurrent() && index > 0) {
                                val ordered = input.members.toMutableList()
                                ordered[index] = ordered[index - 1]; ordered[index - 1] = member
                                editor = input.copy(members = ordered.toList()); syncGuard()
                            }
                        }, enabled = editable && index > 0, modifier = Modifier.touchTarget()) { Text(label("Move earlier", "نقل إلى موضع سابق")) }
                        OutlinedButton(onClick = {
                            if (editingCurrent()) { editor = input.copy(members = input.members.filterIndexed { position, _ -> position != index }); syncGuard() }
                        }, enabled = editable, modifier = Modifier.touchTarget()) { Text(label("Remove member", "إزالة العضو")) }
                    }
                }
                Button(onClick = {
                    if (ready() && editor === input && (input.frozenDraft != null || latestMayStart && status?.mayPrepare == true)) {
                        val draft = try { input.toDraft() } catch (_: IllegalArgumentException) { unavailable(LocalServiceProblem.INVALID_INPUT); null }
                        if (draft != null) {
                            editor = input.copy(frozenDraft = draft.snapshot())
                            runAction {
                                val result = gateway.prepare(draft)
                                currentCoroutineContext().ensureActive()
                                if (owns()) adopt(result, expectedDraft = draft)
                            }
                        }
                    }
                }, enabled = ready() && (input.frozenDraft != null || mayStartNew && status?.mayPrepare == true &&
                    input.observedRevision != null && input.members.size in 1..64), modifier = Modifier.touchTarget()) {
                    Text(label(if (input.frozenDraft != null) "Retry the SAME exact proposal / recover review" else "Retain exact complete release and review",
                        if (input.frozenDraft != null) "إعادة الاقتراح الدقيق نفسه / استعادة المراجعة" else "حفظ الإصدار الكامل الدقيق ومراجعته"))
                }
                if (input.frozenDraft != null) Notice(label("The release, sequence, predecessor and ordered members are frozen after retention was requested. No new publication, revision or replacement member is selected on retry.",
                    "جُمد الإصدار والسلسلة والسلف والأعضاء المرتبون بعد طلب الحفظ. لا يُختار منشور أو مراجعة أو عضو بديل عند إعادة المحاولة."), caution = true)
                OutlinedButton(onClick = { if (ready() && editor === input) { editor = null; syncGuard() } }, enabled = ready(), modifier = Modifier.touchTarget()) {
                    Text(label("Dismiss unsigned input; preserve any native retained proposal", "إغلاق المدخلات غير الموقعة مع حفظ أي اقتراح أصلي محفوظ"))
                }
            }
            original?.let { id ->
                Text(label("Original native action: ${id.value}", "الإجراء الأصلي: ${id.value}"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (original == id) runAction {
                        val result = gateway.review(id); currentCoroutineContext().ensureActive(); if (owns()) adopt(result, expectedOriginal = id)
                    } }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Re-read original review", "قراءة المراجعة الأصلية")) }
                    OutlinedButton(onClick = { if (original == id) runAction {
                        val result = gateway.completed(id); currentCoroutineContext().ensureActive(); if (owns()) adopt(result, expectedOriginal = id)
                    } }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Recover original signing output", "استعادة نتيجة التوقيع الأصلية")) }
                }
            }
            review?.let { exact ->
                CatalogReleasePreview(exact.preview)
                Text(label("Company / head: ${exact.action.companyName} / ${exact.action.headName}",
                    "الشركة / الرئيس: ${exact.action.companyName} / ${exact.action.headName}"))
                Text(label("Original root fingerprint: ${exact.action.rootFingerprint}", "بصمة الجذر الأصلي: ${exact.action.rootFingerprint}"))
                Text(label("Exact release content digest: ${exact.action.proposal.contentDigest.value}", "بصمة محتوى الإصدار الدقيق: ${exact.action.proposal.contentDigest.value}"))
                if (approvalHeld) Notice(label("Approval was attempted. Recover or re-read the SAME native action before any further signing; no failure proves noncompletion.",
                    "جرت محاولة الموافقة. استعد الإجراء الأصلي نفسه أو أعد قراءته قبل أي توقيع آخر؛ لا يثبت الفشل عدم اكتماله."), caution = true)
                OutlinedTextField(headLogin, { if (ready() && review === exact && !approvalHeld && it.length <= 96) headLogin = it }, enabled = ready() && !approvalHeld,
                    singleLine = true, label = { Text(label("Original company-head login", "دخول رئيس الشركة الأصلي")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(headSecret, { if (ready() && review === exact && !approvalHeld && it.length <= 4096) headSecret = it }, enabled = ready() && !approvalHeld,
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text(label("Head password — transient", "كلمة مرور الرئيس — مؤقتة")) }, modifier = Modifier.fillMaxWidth())
                ConfirmRow(label("I reviewed ALL exact ordered member coordinates and the independent predecessor; signing does not activate or submit this release",
                    "راجعت جميع إحداثيات الأعضاء المرتبين الدقيقة والسلف المستقل؛ لا يفعّل التوقيع هذا الإصدار أو يرسله"), exactApproved, ready() && !approvalHeld) {
                    if (ready() && review === exact && !approvalHeld) exactApproved = it
                }
                Button(onClick = {
                    if (ready() && review === exact && !approvalHeld && exactApproved && headLogin.isNotBlank() && headSecret.isNotEmpty()) {
                        val login = headLogin; val secret = headSecret.toCharArray(); headSecret = ""; exactApproved = false; approvalHeld = true
                        runAction(secret) {
                            val result = gateway.approve(exact, login, secret)
                            currentCoroutineContext().ensureActive()
                            if (owns()) adopt(result, expectedOriginal = exact.action.originalActionId, expectedDraft = exact.preview.definition)
                        }
                    }
                }, enabled = ready() && !approvalHeld && exactApproved && headLogin.isNotBlank() && headSecret.isNotEmpty(), modifier = Modifier.touchTarget()) {
                    Text(label("Authenticate head and sign EXACT complete release", "توثيق الرئيس وتوقيع الإصدار الكامل الدقيق"))
                }
            }
            completed?.let { output ->
                CatalogReleasePreview(output.preview)
                Text(label("Original root fingerprint: ${output.rootFingerprint}", "بصمة الجذر الأصلي: ${output.rootFingerprint}"))
                Button(onClick = { if (completed === output) runAction {
                    val result = gateway.stageActivation(output.originalActionId)
                    currentCoroutineContext().ensureActive()
                    if (owns()) when (result) {
                        is LocalStageResult.Staged -> {
                            if (result.operation.action != LocalAction.CATALOG_RELEASE_ACTIVATE ||
                                result.operation.operationId != OperationId(output.originalActionId.value)) {
                                unavailable(LocalServiceProblem.UNAVAILABLE); return@runAction
                            }
                            review = null; completed = null; notice = LocalText("Original activation request saved. Review and explicitly submit it under saved requests; nothing was submitted automatically.",
                                "حُفظ طلب التفعيل الأصلي. راجعه وأرسله صراحةً ضمن الطلبات المحفوظة؛ لم يُرسل أي طلب تلقائيًا.")
                            refreshPending = true
                        }
                        is LocalStageResult.Uncertain -> {
                            if (result.operationId != OperationId(output.originalActionId.value)) {
                                unavailable(LocalServiceProblem.UNAVAILABLE); return@runAction
                            }
                            review = null; completed = null; notice = LocalText("Staging is uncertain. Recover the original saved operation ${result.operationId.value}; never replace its activation CAS, release or identity.",
                                "حفظ الطلب غير مؤكد. استعد العملية الأصلية ${result.operationId.value}؛ لا تستبدل إصدار التفعيل المتوقع أو الإصدار أو الهوية.")
                            refreshPending = true
                        }
                        is LocalStageResult.NotStaged -> unavailable(result.problem)
                    }
                } }, enabled = ready(), modifier = Modifier.touchTarget()) {
                    Text(label("Save separate exact release activation request", "حفظ طلب تفعيل الإصدار الدقيق المنفصل"))
                }
            }
            if (editor == null && review == null && completed == null) status?.references?.let { references ->
                references.drop(page * 8).take(8).forEach { ref ->
                    OutlinedButton(onClick = {
                        if (ready() && editor == null && review == null && completed == null && status?.references?.contains(ref) == true) {
                            // The selected actual native identity survives an unavailable review.
                            original = ref.originalActionId
                            approvalHeld = ref.phase != CompanyBusinessPublicationPhase.PREPARED
                            headLogin = ""; headSecret = ""; exactApproved = false; notice = null
                            runAction {
                                val result = gateway.review(ref.originalActionId)
                                currentCoroutineContext().ensureActive()
                                if (owns()) adopt(result, expectedOriginal = ref.originalActionId)
                            }
                        }
                    }, enabled = ready(), modifier = Modifier.touchTarget()) {
                        Text(label("Catalog ${ref.entityId.value} · publication ${ref.publicationId.value} · ",
                            "الكتالوج ${ref.entityId.value} · المنشور ${ref.publicationId.value} · ") + language.text(ref.phase.releaseLabel()))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page > 0) OutlinedButton(onClick = { if (ready()) page-- }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Previous releases", "الإصدارات السابقة")) }
                    if ((page + 1) * 8 < references.size) OutlinedButton(onClick = { if (ready()) page++ }, enabled = ready(), modifier = Modifier.touchTarget()) { Text(label("Next releases", "الإصدارات التالية")) }
                }
            }
        }
    }
}
