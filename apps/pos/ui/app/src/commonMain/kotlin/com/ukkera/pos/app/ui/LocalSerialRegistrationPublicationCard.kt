package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.*
import kotlin.time.Instant

internal class LocalSerialRegistrationEntryDrafts {
    val lifetime = LocalConfigurationEntryLifetime()
    val editor = mutableStateOf(SerialRegistrationEditor())
    val original = mutableStateOf<DomainId?>(null)
    val approvalHeld = mutableStateOf(false)
    val hasDraft get() = editor.value.dirty || original.value != null
    fun retireAttachment() { lifetime.retire() }
    fun clear(denied: Boolean = false) {
        try { lifetime.retire(denied) }
        finally { editor.value = SerialRegistrationEditor(); original.value = null; approvalHeld.value = false }
    }
}

internal data class SerialRegistrationEditor(
    val registrationId: String = "", val skuId: String = "", val serialCode: String = "",
    val serialId: DomainId = DomainId.random(), val publicationId: DomainId = DomainId.random(),
    val from: String = "", val until: String = "", val source: LocalSerialRegistrationAuthoringSource? = null,
    val frozen: LocalSerialRegistrationDraft? = null,
) {
    val dirty get() = registrationId.isNotEmpty() || skuId.isNotEmpty() || serialCode.isNotEmpty() || from.isNotEmpty() || until.isNotEmpty() || frozen != null
    fun draft(): LocalSerialRegistrationDraft = frozen ?: requireNotNull(source).let { source ->
        require(source.registrationId == DomainId(registrationId) && source.skuId == DomainId(skuId))
        LocalSerialRegistrationDraft(publicationId, source.registrationId, requireNotNull(source.nextRevision), source.previous,
            source.previousDefinition?.serialId ?: serialId, serialCode, source.skuId, source.skuPublicationId,
            source.skuPublicationDigest, UtcInstant(Instant.fromEpochMilliseconds(requireNotNull(from.toLongOrNull()))),
            until.takeIf { it.isNotEmpty() }?.let { UtcInstant(Instant.fromEpochMilliseconds(requireNotNull(it.toLongOrNull()))) })
    }
}

@Composable
private fun SerialPublicationPreview(preview: LocalSerialPublicationPreview) {
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String) = language.text(LocalText(en, ar))
    when (preview) {
        is LocalSerialPublicationPreview.Authority -> {
            val value = preview.definition
            Text(label("Permanent sole exact-code authority (no stock effect)", "صلاحية دائمة وحيدة للرموز الدقيقة (لا أثر على المخزون)"))
            Text(label("Company / branch: ${value.company.companyId.value} / ${value.origin.originalScope.branchId.value}", "الشركة / الفرع: ${value.company.companyId.value} / ${value.origin.originalScope.branchId.value}"))
            Text(label("Authority / publication: ${value.authorityId.value} / ${value.publicationId.value}", "الصلاحية / المنشور: ${value.authorityId.value} / ${value.publicationId.value}"))
            Text(label("Original issuer: ${value.origin.originalIssuerId.value}; head: ${value.origin.headAuthorityId.value}", "المصدر الأصلي: ${value.origin.originalIssuerId.value}؛ رئيس الشركة: ${value.origin.headAuthorityId.value}"))
            Text(label("Original allocation: ${value.origin.originalAllocationId.value}; ${value.origin.originalAllocationDigest.value}", "التخصيص الأصلي: ${value.origin.originalAllocationId.value}؛ ${value.origin.originalAllocationDigest.value}"))
            Text(label("Root fingerprint: ${value.origin.rootFingerprint}", "بصمة الجذر: ${value.origin.rootFingerprint}"))
            Text(label("Founding / root / root key: ${value.origin.foundingActionId.value} / ${value.origin.rootId.value} / ${value.origin.rootKeyId.value}", "التأسيس / الجذر / مفتاح الجذر: ${value.origin.foundingActionId.value} / ${value.origin.rootId.value} / ${value.origin.rootKeyId.value}"))
            Text(label("Root document digest: ${value.origin.rootDocumentDigest.value}", "بصمة وثيقة الجذر: ${value.origin.rootDocumentDigest.value}"))
            Text(label("Head enrollment / issuer key: ${value.origin.headEnrollmentId.value} / ${value.origin.originalIssuerKeyId.value}", "تسجيل الرئيس / مفتاح المصدر: ${value.origin.headEnrollmentId.value} / ${value.origin.originalIssuerKeyId.value}"))
            Text(label("Issuer delegation digest: ${value.origin.originalIssuerDelegationDigest.value}", "بصمة تفويض المصدر: ${value.origin.originalIssuerDelegationDigest.value}"))
            Text(label("Permanent purpose: ${value.purpose}", "الغرض الدائم: ${value.purpose}"))
        }
        is LocalSerialPublicationPreview.Registration -> {
            val value = preview.definition
            Text(label("Company / branch: ${value.company.companyId.value} / ${value.scope.branchId.value}", "الشركة / الفرع: ${value.company.companyId.value} / ${value.scope.branchId.value}"))
            Text(label("Registration / publication / revision: ${value.registrationId.value} / ${value.publicationId.value} / ${value.revision.value}", "التسجيل / المنشور / المراجعة: ${value.registrationId.value} / ${value.publicationId.value} / ${value.revision.value}"))
            Text(label("Exact serial / code: ${value.serialId.value} / ${value.serialCode}", "المسلسل / الرمز الدقيق: ${value.serialId.value} / ${value.serialCode}"))
            Text(label("SKU / publication / digest: ${value.skuId.value} / ${value.skuPublicationId.value} / ${value.skuPublicationDigest.value}", "الصنف / المنشور / البصمة: ${value.skuId.value} / ${value.skuPublicationId.value} / ${value.skuPublicationDigest.value}"))
            Text(label("Authority / publication: ${value.serialAuthority.authorityId.value} / ${value.serialAuthority.publicationId.value}", "الصلاحية / المنشور: ${value.serialAuthority.authorityId.value} / ${value.serialAuthority.publicationId.value}"))
            Text(label("Authority content / origin digests: ${value.serialAuthority.contentDigest.value} / ${value.serialAuthority.issuerOriginDigest.value}", "بصمتا محتوى الصلاحية / أصلها: ${value.serialAuthority.contentDigest.value} / ${value.serialAuthority.issuerOriginDigest.value}"))
            Text(label("Allocation / digest: ${value.allocationId.value} / ${value.allocationDigest.value}", "التخصيص / البصمة: ${value.allocationId.value} / ${value.allocationDigest.value}"))
            Text(label("Valid from (UTC milliseconds): ${value.validFrom.value.toEpochMilliseconds()}; exclusive end: ${value.validUntil?.value?.toEpochMilliseconds() ?: "none"}", "صالح من (مللي ثانية UTC): ${value.validFrom.value.toEpochMilliseconds()}؛ النهاية غير الشاملة: ${value.validUntil?.value?.toEpochMilliseconds() ?: "بلا نهاية"}"))
            Text(label("Exact predecessor: ${value.previous?.let { "${it.publicationId.value} / ${it.revision.value} / ${it.contentDigest.value}" } ?: "genesis"}", "السلف الدقيق: ${value.previous?.let { "${it.publicationId.value} / ${it.revision.value} / ${it.contentDigest.value}" } ?: "بداية"}"))
        }
    }
}

@Composable
internal fun LocalSerialRegistrationPublicationCard(runtime: UkkeraRuntime, token: NavigationToken,
    drafts: LocalSerialRegistrationEntryDrafts, parentReady: Boolean, mayStartNew: Boolean,
    employeeAccessDenied: () -> Unit, guardChanged: (Boolean, Boolean) -> Unit,
    activationStaged: (NavigationToken) -> Unit) {
    val session = runtime.localSessionFor(token) ?: return
    val attachment = runtime.presentation.collectAsState().value.localAttachment
    val identity = drafts.lifetime.identity
    val gateway = remember(session, attachment) { session.companyCatalog.serialRegistrations }
    val language = LocalAppLanguage.current
    fun label(en: String, ar: String) = language.text(LocalText(en, ar))
    if (!gateway.configured) return
    val scope = rememberCoroutineScope()
    val latestToken by rememberUpdatedState(token)
    val latestParent by rememberUpdatedState(parentReady)
    val latestStart by rememberUpdatedState(mayStartNew)
    val latestDenied by rememberUpdatedState(employeeAccessDenied)
    val latestGuard by rememberUpdatedState(guardChanged)
    val latestStaged by rememberUpdatedState(activationStaged)
    var alive by remember(identity) { mutableStateOf(true) }
    var work by remember(identity) { mutableStateOf<Job?>(null) }
    var working by remember(identity) { mutableStateOf(false) }
    var status by remember(identity) { mutableStateOf<LocalSerialPublicationStatus?>(null) }
    var review by remember(identity) { mutableStateOf<LocalSerialPublicationReview?>(null) }
    var completed by remember(identity) { mutableStateOf<LocalSerialPublicationCompleted?>(null) }
    var login by remember(identity) { mutableStateOf("") }
    var secret by remember(identity) { mutableStateOf("") }
    var confirmed by remember(identity) { mutableStateOf(false) }
    var refreshPending by remember(identity) { mutableStateOf(false) }
    var notice by remember(identity) { mutableStateOf<LocalText?>(null) }
    var page by remember(identity) { mutableStateOf(0) }
    var editor by drafts.editor
    var original by drafts.original
    var held by drafts.approvalHeld
    // Our own leave-guard updates change navigation revision without replacing this entry.
    fun owns() = alive && drafts.lifetime.isCurrent(identity) && runtime.ownsLocalAttachment(session, latestToken, attachment)
    fun ready() = owns() && runtime.navigator.isCurrent(latestToken) && latestParent && !working
    fun failure(problem: LocalServiceProblem, native: SelfOwnedInstallationFailureV1? = null) {
        secret = ""; confirmed = false
        if (problem == LocalServiceProblem.DENIED && native != SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED) { latestDenied(); return }
        if (problem == LocalServiceProblem.SESSION_ENDED) runtime.localSessionEnded(session)
        notice = LocalText("Serial publication unavailable: $problem. Recover the original after uncertain signing; do not replace it.", "منشور المسلسل غير متاح: $problem. استعد الأصل بعد التوقيع غير المؤكد؛ لا تستبدله.")
    }
    fun adopt(result: LocalSerialPublicationResult, expected: DomainId? = null) {
        val id = when (result) {
            is LocalSerialPublicationResult.Review -> result.review.action.originalActionId
            is LocalSerialPublicationResult.Completed -> result.publication.originalActionId
            is LocalSerialPublicationResult.Unavailable -> null
        }
        if (expected != null && id != null && expected != id) { failure(LocalServiceProblem.UNAVAILABLE); return }
        val preview = when (result) {
            is LocalSerialPublicationResult.Review -> result.review.preview
            is LocalSerialPublicationResult.Completed -> result.publication.preview
            is LocalSerialPublicationResult.Unavailable -> null
        }
        val company = when (preview) {
            is LocalSerialPublicationPreview.Authority -> preview.definition.company.companyId
            is LocalSerialPublicationPreview.Registration -> preview.definition.company.companyId
            null -> null
        }
        if (company != null && company != session.display.companyId ||
            preview is LocalSerialPublicationPreview.Registration && preview.definition.scope.branchId != session.display.branchId) {
            failure(LocalServiceProblem.UNAVAILABLE); return
        }
        if (result is LocalSerialPublicationResult.Review) {
            val action = result.review.action
            val exact = runCatching { when (action.proposal.kind) {
                CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 -> LocalSerialPublicationPreview.Authority(action.readNativeSerialAuthorityDefinition())
                CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> LocalSerialPublicationPreview.Registration(action.readNativeSerialRegistrationDefinition())
                else -> null
            } }.getOrNull()
            if (exact == null || exact != preview) { failure(LocalServiceProblem.UNAVAILABLE); return }
        }
        when (result) {
            is LocalSerialPublicationResult.Review -> {
                original = id; review = result.review; completed = null
                login = result.review.action.headLogin; secret = ""; confirmed = false; held = false
            }
            is LocalSerialPublicationResult.Completed -> {
                original = id; completed = result.publication; review = null
                login = ""; secret = ""; confirmed = false; held = true
            }
            is LocalSerialPublicationResult.Unavailable -> failure(result.problem, result.nativeReason)
        }
    }
    fun run(body: suspend () -> Unit) {
        if (!ready()) return
        working = true
        latestGuard(drafts.hasDraft || review != null, true)
        work = scope.launch(start = CoroutineStart.LAZY) {
            try { body(); currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (owns()) failure(LocalServiceProblem.UNAVAILABLE) }
            finally { if (owns()) {
                working = false; work = null; latestGuard(drafts.hasDraft || review != null, false)
                if (refreshPending) { refreshPending = false; latestStaged(runtime.navigator.token()) }
            } }
        }.also { it.start() }
    }
    fun retire() {
        alive = false; val job = work; work = null
        secret = ""; login = ""; confirmed = false; review = null; completed = null; status = null; notice = null; working = false; refreshPending = false
        job?.cancel()
    }
    DisposableEffect(session, token.entryId, attachment, identity) {
        val release = drafts.lifetime.attach(identity, ::retire)
        onDispose { val wasCurrent = owns(); release(); retire(); if (wasCurrent) latestGuard(drafts.hasDraft, false) }
    }
    SideEffect { if (owns()) latestGuard(drafts.hasDraft || review != null, working) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label("Original local serial registration", "تسجيل المسلسل المحلي الأصلي"), style = MaterialTheme.typography.titleMedium)
        Text(label("Signing does not activate or receive stock. The Master must independently qualify originals, current SKU, permissions and time; unqualified time blocks activation/use.", "التوقيع لا يفعل التسجيل ولا يستلم المخزون. يجب على الرئيسي تأهيل الأصول والصنف الحالي والصلاحيات والوقت مستقلاً؛ الوقت غير المؤهل يمنع التفعيل والاستخدام."))
        notice?.let { Text(language.text(it)) }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (review == null && completed == null && original == null) {
            OutlinedButton(onClick = { run {
                val result = gateway.status(); currentCoroutineContext().ensureActive()
                if (owns()) when (result) {
                    is LocalSerialPublicationStatusResult.Available -> { status = result.status; page = 0; drafts.lifetime.accessObserved() }
                    is LocalSerialPublicationStatusResult.Unavailable -> failure(result.problem)
                }
            } }, enabled = ready()) { Text(label("Read retained originals", "قراءة الأصول المحفوظة")) }
            OutlinedButton(onClick = { run { val result = gateway.prepareAuthority(); if (owns()) adopt(result) } }, enabled = ready() && latestStart && !drafts.lifetime.accessDenied && !editor.dirty) {
                Text(label("Review the sole original company authority", "مراجعة صلاحية الشركة الأصلية الوحيدة"))
            }
            OutlinedTextField(editor.registrationId, { if (ready() && editor.frozen == null) editor = editor.copy(registrationId = it, source = null) }, label = { Text(label("Registration ID", "معرف التسجيل")) }, enabled = ready() && editor.frozen == null)
            OutlinedTextField(editor.skuId, { if (ready() && editor.frozen == null) editor = editor.copy(skuId = it, source = null) }, label = { Text(label("Current serial-tracked SKU ID", "معرف صنف تتبع المسلسل الحالي")) }, enabled = ready() && editor.frozen == null)
            OutlinedButton(onClick = { run {
                val input = editor
                val result = gateway.status(DomainId(input.registrationId), DomainId(input.skuId))
                currentCoroutineContext().ensureActive()
                if (owns() && editor == input) when (result) {
                    is LocalSerialPublicationStatusResult.Available -> {
                        val source = result.status.source ?: return@run
                        if (source.registrationId != DomainId(input.registrationId) || source.skuId != DomainId(input.skuId)) { failure(LocalServiceProblem.UNAVAILABLE); return@run }
                        editor = input.copy(source = source, serialCode = source.previousDefinition?.serialCode ?: input.serialCode)
                        status = result.status; drafts.lifetime.accessObserved()
                    }
                    is LocalSerialPublicationStatusResult.Unavailable -> failure(result.problem)
                }
            } }, enabled = ready() && latestStart && !drafts.lifetime.accessDenied && editor.frozen == null) { Text(label("Load exact native/current SKU and predecessor", "تحميل الصنف الحالي والأصل والسلف الدقيق")) }
            editor.source?.let { source ->
                Text(label("Pinned SKU publication: ${source.skuPublicationId.value}; next revision: ${source.nextRevision?.value}", "منشور الصنف المثبت: ${source.skuPublicationId.value}؛ المراجعة التالية: ${source.nextRevision?.value}"))
            }
            OutlinedTextField(editor.serialCode, { if (ready() && editor.frozen == null && editor.source?.previous == null) editor = editor.copy(serialCode = it) }, label = { Text(label("Exact serial code (case and zeros preserved)", "رمز المسلسل الدقيق (مع حفظ الحالة والأصفار)")) }, enabled = ready() && editor.frozen == null && editor.source?.previous == null)
            OutlinedTextField(editor.from, { if (ready() && editor.frozen == null) editor = editor.copy(from = it) }, label = { Text(label("Valid from: UTC epoch milliseconds", "صالح من: مللي ثانية UTC")) }, enabled = ready() && editor.frozen == null)
            OutlinedTextField(editor.until, { if (ready() && editor.frozen == null) editor = editor.copy(until = it) }, label = { Text(label("Exclusive end: UTC milliseconds (optional)", "النهاية غير الشاملة: مللي ثانية UTC (اختيارية)")) }, enabled = ready() && editor.frozen == null)
            Button(onClick = { run {
                val draft = try { editor.draft() } catch (_: IllegalArgumentException) { failure(LocalServiceProblem.INVALID_INPUT); return@run }
                editor = editor.copy(frozen = draft)
                val result = gateway.prepare(draft); currentCoroutineContext().ensureActive()
                if (owns()) {
                    if (result is LocalSerialPublicationResult.Unavailable && result.unsignedInputMayChange && original == null)
                        editor = editor.copy(frozen = null)
                    adopt(result)
                }
            } }, enabled = ready() && latestStart && !drafts.lifetime.accessDenied && editor.source?.nextRevision != null) { Text(label("Prepare exact registration review", "إعداد مراجعة التسجيل الدقيق")) }
            if (editor.frozen == null) OutlinedButton(onClick = { if (ready()) editor = SerialRegistrationEditor() }, enabled = ready()) { Text(label("Discard unsigned input", "تجاهل الإدخال غير الموقع")) }
            status?.references?.drop(page * 8)?.take(8)?.forEach { ref ->
                OutlinedButton(onClick = { run { original = ref.originalActionId; held = ref.phase != CompanyBusinessPublicationPhase.PREPARED; val result = gateway.review(ref.originalActionId); if (owns()) adopt(result, ref.originalActionId) } }, enabled = ready()) {
                    Text("${ref.kind} / ${ref.entityId.value} / ${ref.phase}")
                }
            }
            if (page > 0) OutlinedButton(onClick = { if (ready()) page-- }, enabled = ready()) { Text(label("Previous", "السابق")) }
            if ((page + 1) * 8 < (status?.references?.size ?: 0)) OutlinedButton(onClick = { if (ready()) page++ }, enabled = ready()) { Text(label("Next", "التالي")) }
        }
        review?.let { selected ->
            Text(label("Original action / signing key: ${selected.action.originalActionId.value} / ${selected.action.signingKeyId.value}", "العملية الأصلية / مفتاح التوقيع: ${selected.action.originalActionId.value} / ${selected.action.signingKeyId.value}"))
            Text(label("Content digest / root fingerprint: ${selected.action.proposal.contentDigest.value} / ${selected.action.rootFingerprint}", "بصمة المحتوى / بصمة الجذر: ${selected.action.proposal.contentDigest.value} / ${selected.action.rootFingerprint}"))
            SerialPublicationPreview(selected.preview)
            OutlinedTextField(login, { if (ready() && !held) login = it }, label = { Text(label("Head login", "اسم دخول رئيس الشركة")) }, enabled = ready() && !held)
            OutlinedTextField(secret, { if (ready() && !held) secret = it }, label = { Text(label("Head password (never saved)", "كلمة مرور رئيس الشركة (لا تحفظ)")) }, visualTransformation = PasswordVisualTransformation(), enabled = ready() && !held)
            Row { Checkbox(confirmed, { if (ready() && !held) confirmed = it }); Text(label("I reviewed this exact immutable publication", "راجعت هذا المنشور الدقيق غير القابل للتغيير")) }
            Button(onClick = { run {
                held = true; val buffer = secret.toCharArray(); secret = ""; confirmed = false
                try { val result = gateway.approve(selected, login, buffer); currentCoroutineContext().ensureActive(); if (owns()) adopt(result, selected.action.originalActionId) }
                finally { buffer.fill('\u0000') }
            } }, enabled = ready() && !held && confirmed && secret.isNotEmpty()) { Text(label("Approve exact original", "اعتماد الأصل الدقيق")) }
        }
        original?.let { id ->
            OutlinedButton(onClick = { run { val result = gateway.review(id); if (owns()) adopt(result, id) } }, enabled = ready()) { Text(label("Re-read original $id", "إعادة قراءة الأصل $id")) }
            OutlinedButton(onClick = { run { val result = gateway.completed(id); if (owns()) adopt(result, id) } }, enabled = ready()) { Text(label("Recover original completion only", "استعادة اكتمال الأصل فقط")) }
        }
        completed?.let { selected ->
            SerialPublicationPreview(selected.preview)
            if (selected.preview is LocalSerialPublicationPreview.Registration) Button(onClick = { run {
                val result = gateway.stageActivation(selected.originalActionId); currentCoroutineContext().ensureActive()
                if (owns()) when (result) {
                    is LocalStageResult.Staged -> {
                        if (result.operation.operationId != OperationId(selected.originalActionId.value) || result.operation.action != LocalAction.SERIAL_REGISTRATION_ACTIVATE) { failure(LocalServiceProblem.UNAVAILABLE); return@run }
                        original = null; editor = SerialRegistrationEditor(); review = null; completed = null
                        notice = LocalText("Exact activation saved. Explicitly review and submit in saved requests; no stock was received.", "حُفظ التفعيل الدقيق. راجعه وأرسله صراحةً في الطلبات المحفوظة؛ لم يُستلم مخزون.")
                        refreshPending = true
                    }
                    is LocalStageResult.Uncertain -> {
                        if (result.operationId != OperationId(selected.originalActionId.value)) failure(LocalServiceProblem.UNAVAILABLE)
                        else {
                            failure(result.accessProblem ?: LocalServiceProblem.STORAGE_UNCERTAIN)
                            if (result.accessProblem == null) refreshPending = true
                        }
                    }
                    is LocalStageResult.NotStaged -> failure(result.problem)
                }
            } }, enabled = ready()) { Text(label("Save separate exact activation", "حفظ التفعيل الدقيق المنفصل")) }
            else OutlinedButton(onClick = { if (ready()) { original = null; completed = null; editor = SerialRegistrationEditor(); held = false } }, enabled = ready()) { Text(label("Continue to registration", "المتابعة إلى التسجيل")) }
        }
    }
}
