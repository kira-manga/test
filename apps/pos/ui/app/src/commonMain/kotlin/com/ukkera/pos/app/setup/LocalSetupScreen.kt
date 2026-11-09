package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.ScopeDetails
import com.ukkera.pos.app.ui.GrantDetails
import com.ukkera.pos.app.ui.LocalAppLanguage
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.app.ui.PosTheme
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.touchTarget
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId

/**
 * Real setup commands and honest bounded progress. No training/demo completion, automatic
 * employee login or receipt-as-readiness shortcut. Password text is transient Compose
 * input only: never rememberSaveable, a route, state flow, saved-state handle or a log.
 */
@Composable
public fun LocalSetupScreen(
    controller: LocalSetupController,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val view by controller.state.collectAsState()
    DisposableEffect(controller) { onDispose { controller.lockPresentation() } }
    if (!view.locked && view.phase == LocalSetupPhase.EMPLOYEE_ENROLLMENT) {
        LocalSetupEmployeeEnrollmentScreen(controller, view, language, onLanguageChange, modifier, onBack)
        return
    }
    if (!view.locked && view.phase == LocalSetupPhase.EMPLOYEE_POLICY) {
        LocalSetupEmployeePolicyScreen(controller, view, language, onLanguageChange, modifier, onBack)
        return
    }
    if (!view.locked && view.phase == LocalSetupPhase.DEVICE_ADMISSION) {
        LocalSetupDeviceAdmissionScreen(controller, view, language, onLanguageChange, modifier, onBack)
        return
    }
    if (!view.locked && view.phase == LocalSetupPhase.FRESH_DEVICE_PAIRING) {
        LocalSetupFreshDevicePairingScreen(controller, view, language, onLanguageChange, modifier, onBack)
        return
    }
    val definition = view.definitionReview
    if (!view.locked && view.phase == LocalSetupPhase.FIRST_ACCOUNT_REVIEW && definition != null) {
        LocalSetupDefinitionReviewScreen(definition, language, modifier,
            enabled = view.work == null, onSubmit = controller::installFirstAccount,
            onLock = controller::lockPresentation, onRefresh = controller::refreshOriginalStatus,
            onLanguageChange = onLanguageChange, onBack = onBack?.let { back ->
                { controller.lockPresentation(); back() }
            })
        return
    }
    CompositionLocalProvider(LocalAppLanguage provides language, LocalLayoutDirection provides language.direction) {
        PosTheme {
            Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                .widthIn(max = 960.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (onBack != null) {
                    OutlinedButton(onClick = { controller.lockPresentation(); onBack() }, modifier = Modifier.touchTarget()) {
                        Text(t("Device choices", "خيارات الجهاز"))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onLanguageChange(language.toggle()) }, modifier = Modifier.touchTarget()) {
                        Text(if (language == AppLanguage.ENGLISH) "العربية" else "English")
                    }
                    if (!view.locked && view.phase != LocalSetupPhase.CLOSED) {
                        OutlinedButton(onClick = controller::lockPresentation, modifier = Modifier.touchTarget()) {
                            Text(t("Lock setup", "قفل الإعداد"))
                        }
                    }
                }
                SectionHeading(t("Set up local Ukkera POS", "إعداد نقطة بيع عُكّرة المحلية"),
                    t("Local authority, branch installation, and employee sign-in are separate steps.",
                        "السلطة المحلية وتثبيت الفرع وتسجيل دخول الموظف خطوات منفصلة."))
                Notice(t("Central services remain held. A local setup does not require an Internet checkout lease.",
                    "الخدمات المركزية معلّقة. لا يتطلب الإعداد المحلي تصريح بيع عبر الإنترنت."))
                Notice(t("This authority/preparation view is not branch go-live. Activity configuration, local policy, catalog, counted opening balances, recovery access and the applicable device checks remain separate required work.",
                    "عرض السلطة والتحضير هذا ليس تشغيلًا فعليًا للفرع. تظل إعدادات النشاط والسياسة المحلية والكتالوج والأرصدة الافتتاحية المعدودة ووصول الاستعادة وفحوص الأجهزة اللازمة عملًا مطلوبًا منفصلًا."))
                view.work?.let {
                    CircularProgressIndicator()
                    Text(workText(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                view.problem?.let {
                    Notice(problemText(it), caution = true,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
                view.nativeProblem?.let {
                    Notice(t("Original native diagnostic: ", "سبب التعذر الأصلي: ") + problemText(it), caution = true)
                }
                if (view.locked) {
                    Notice(t("Setup is locked. Already admitted work keeps its original native owner. Unlock only to review that same retained setup.",
                        "الإعداد مقفل. يستمر العمل المقبول بمالكه الأصلي. افتح القفل لمراجعة الإعداد المحفوظ نفسه فقط."))
                    Button(onClick = controller::resumePresentation, modifier = Modifier.fillMaxWidth().touchTarget(),
                        enabled = view.work == null && view.phase != LocalSetupPhase.CLOSING && view.phase != LocalSetupPhase.CLOSED) {
                        Text(t("Review setup again", "مراجعة الإعداد مجددًا"))
                    }
                } else {
                    val editable = view.work == null && view.problem != LocalSetupProblem.CLOSE_UNCONFIRMED
                    if (view.phase == LocalSetupPhase.INSTALLATION_RETAINED || view.lan.role != null ||
                        view.lan.hostAvailable || view.lan.clientAvailable) LocalSetupLanSection(controller, view)
                    when (view.phase) {
                        LocalSetupPhase.CHOOSE_ROUTE -> {
                            Notice(t("Create a NEW local company only when that is the intended action. Original recovery never falls back to a new company because files are missing.",
                                "أنشئ شركة محلية جديدة فقط عندما يكون ذلك مقصودًا. لا تتحول استعادة الأصل إلى إنشاء شركة بسبب فقد الملفات."))
                            Button(onClick = controller::chooseNewLocalCompany, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Create a new local company", "إنشاء شركة محلية جديدة"))
                            }
                            OutlinedButton(onClick = controller::inspectOriginalCompany, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Resume this device's original company setup", "متابعة إعداد الشركة الأصلي لهذا الجهاز"))
                            }
                            OutlinedButton(onClick = controller::inspectOriginalInstallation, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Inspect this device's original installation", "فحص التثبيت الأصلي لهذا الجهاز"))
                            }
                            OutlinedButton(onClick = controller::openOriginalFirstAccount, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Continue the original first-account installation", "متابعة تثبيت الحساب الأول الأصلي"))
                            }
                            OutlinedButton(onClick = controller::chooseFreshDeviceTarget, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Prepare this new device as a pairing target", "تحضير هذا الجهاز الجديد هدفًا للاقتران"))
                            }
                            OutlinedButton(onClick = controller::inspectOriginalFreshDeviceTarget, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Recover this device's original pairing target", "استعادة هدف الاقتران الأصلي لهذا الجهاز"))
                            }
                            Notice(t("Joining an existing company or enrolling an additional client requires its genuine enrollment path; this is not a replacement company creator.",
                                "يتطلب الانضمام إلى شركة قائمة أو تسجيل جهاز عميل إضافي مسار التسجيل الأصلي؛ ولا يستبدله إنشاء شركة جديدة."))
                        }
                        LocalSetupPhase.NEW_COMPANY_DETAILS -> CompanyInputs(editable, controller::prepareNewLocalCompany)
                        LocalSetupPhase.COMPANY_REVIEW -> view.companyReview?.let {
                            CompanyReview(it, editable, controller::completeNewLocalCompany)
                        }
                        LocalSetupPhase.ORIGINAL_COMPANY_PENDING -> {
                            Notice(t("The original company key preparation is retained. Only the native owner can continue those same key intents. No replacement company, key ID or password is supplied here.",
                                "تحضير مفاتيح الشركة الأصلي محفوظ. وحده المالك الأصلي يستطيع متابعة نوايا المفاتيح نفسها. لا تُقدّم هنا شركة بديلة أو معرّف مفتاح بديل أو كلمة مرور."))
                            Button(onClick = controller::resumeOriginalCompany, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Continue only original key preparation", "متابعة تحضير المفاتيح الأصلي فقط"))
                            }
                        }
                        LocalSetupPhase.COMPANY_RETAINED -> view.company?.let {
                            CompanyDetails(it)
                            BranchInputs(editable, controller::prepareOriginalBranch)
                        }
                        LocalSetupPhase.BRANCH_REVIEW -> view.branchReview?.let {
                            BranchReview(it, view.company?.installerLogin.orEmpty(), editable, controller::issueOriginalBranch)
                        }
                        LocalSetupPhase.ORIGINAL_PREPARATION_RETAINED -> {
                            view.branchReview?.let { BranchDetails(it) }
                            Notice(t("The original native preparation is retained. This is not an installed branch, employee account or master admission.",
                                "حُفظ التحضير الأصلي للبيئة الأصلية. هذا لا يعني تثبيت الفرع أو إنشاء موظف أو قبول جهاز رئيسي."))
                            Button(onClick = controller::prepareOriginalNativeTarget, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Prepare only the original target", "تحضير الهدف الأصلي فقط"))
                            }
                        }
                        LocalSetupPhase.ORIGINAL_TARGET_RETAINED -> {
                            view.native?.let { NativeDetails(it) }
                            Notice(t("The original native target exists. Continue to choose the first employee's exact permissions and review the complete installation before supplying either password.",
                                "الهدف الأصلي موجود. تابع لاختيار صلاحيات الموظف الأول بدقة ومراجعة التثبيت الكامل قبل إدخال أي من كلمتي المرور."))
                            Button(onClick = controller::openOriginalFirstAccount, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Define the first employee account", "تعريف حساب الموظف الأول"))
                            }
                            OutlinedButton(onClick = controller::refreshOriginalStatus,
                                enabled = editable && view.problem != LocalSetupProblem.CLOSE_UNCONFIRMED,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Read original installation status", "قراءة حالة التثبيت الأصلي"))
                            }
                        }
                        LocalSetupPhase.ORIGINAL_RECEIPT_FOUND -> {
                            view.receipt?.let {
                                ReviewField(t("Original request", "الطلب الأصلي"), it.originalRequestId.value)
                                ReviewField(t("Issued attempt", "المحاولة الصادرة"), it.issuedAttemptId.value)
                                ReviewField(t("Installation", "التثبيت"), it.installationId.value)
                                ReviewField(t("Branch", "الفرع"), it.branchId.value)
                                ReviewField(t("Complete definition digest", "بصمة التعريف الكامل"), it.definitionDigest)
                                ReviewField(t("Receipt digest", "بصمة الإيصال"), it.receiptDigest)
                                ReviewField(t("Installation genesis", "أصل سجل التثبيت"), it.genesisDigest)
                            }
                            Notice(t("An original receipt was found. It is historical data, not settlement, acknowledgement, a session or permission to run the master.",
                                "عُثر على الإيصال الأصلي. هو بيانات تاريخية وليس تسوية أو إقرارًا أو جلسة دخول أو تصريحًا لتشغيل الجهاز الرئيسي."))
                            InstallationHeld()
                            Notice(t("To reopen this existing branch, explicitly close this historical reader and verify the same original installation's full history. This does not enroll client journals or sign in; missing or held history remains unavailable.",
                                "لإعادة فتح هذا الفرع الموجود، أغلق قارئ السجل التاريخي صراحةً وتحقق من السجل الكامل للتثبيت الأصلي نفسه. لا تسجّل هذه الخطوة سجلات العميل ولا تدخل موظفًا؛ وتبقى السجلات المفقودة أو غير المحسومة غير متاحة."), caution = true)
                            OutlinedButton(onClick = controller::openOriginalFirstAccount, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Open and verify the same original installation", "فتح التثبيت الأصلي نفسه والتحقق منه"))
                            }
                            OutlinedButton(onClick = controller::refreshOriginalStatus, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Re-read the original status", "إعادة قراءة الحالة الأصلية"))
                            }
                        }
                        LocalSetupPhase.FIRST_ACCOUNT_DETAILS -> view.native?.let { native ->
                            LocalSetupFirstAccountInputs(native, view.firstAccountHeadLogin.orEmpty(), editable,
                                controller::prepareFirstAccount)
                        }
                        LocalSetupPhase.FIRST_ACCOUNT_REVIEW -> Notice(t("The complete original review is unavailable. Read the original state again; do not submit a replacement definition.",
                            "المراجعة الأصلية الكاملة غير متاحة. أعد قراءة الحالة الأصلية؛ لا ترسل تعريفًا بديلًا."), caution = true)
                        LocalSetupPhase.INSTALLATION_ACK_PENDING -> {
                            Notice(t("The original database write and native settlement are retained, but the company acknowledgement is missing. Finish only that original acknowledgement; do not create another account or resubmit either password. After a restart, this option requires a complete scan and actual close of the original settled installation history.",
                                "كتابة قاعدة البيانات وتسويتها الأصلية محفوظتان، لكن إقرار الشركة مفقود. أكمل ذلك الإقرار الأصلي فقط؛ لا تنشئ حسابًا آخر ولا تعِد إرسال أي من كلمتي المرور. بعد إعادة التشغيل، يتطلب هذا الخيار فحص سجل التثبيت الأصلي المستقر كاملًا وإغلاق قارئه فعليًا."), caution = true)
                            Button(onClick = controller::resumeOriginalInstallationAcknowledgement, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Finish the original acknowledgement — no SQL replay", "إكمال الإقرار الأصلي — دون تكرار الكتابة"))
                            }
                            OutlinedButton(onClick = controller::refreshOriginalStatus, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Close this owner and fully rescan the original", "إغلاق هذا المالك وفحص الأصل كاملًا"))
                            }
                            Notice(t("If this owner's acknowledgement attempt is unavailable, an explicit rescan first waits for its actual close. An unconfirmed close keeps setup blocked; it never opens a replacement writer or recreates the installation.",
                                "إذا تعذرت محاولة الإقرار لدى هذا المالك، تنتظر إعادة الفحص الصريحة إغلاقه الفعلي أولًا. يبقى الإعداد محظورًا إن لم يُؤكَّد الإغلاق؛ ولا يُفتح كاتب بديل ولا يُعاد إنشاء التثبيت."))
                            Notice(t("Leaving this screen only locks it. An unsettled or inconsistent original write still requires reconciliation and cannot use this acknowledgement-only path. This is not complete restart recovery or POS readiness.",
                                "مغادرة الشاشة تقفلها فقط. لا تزال الكتابة الأصلية غير المستقرة أو غير المتسقة تحتاج إلى تسوية، ولا يمكنها استخدام هذا المسار المخصص للإقرار فقط. هذا ليس استردادًا كاملًا لإعادة التشغيل أو جاهزية لنقطة البيع."))
                        }
                        LocalSetupPhase.INSTALLATION_RETAINED -> {
                            view.receipt?.let {
                                ReviewField(t("Installation", "التثبيت"), it.installationId.value)
                                ReviewField(t("Original request", "الطلب الأصلي"), it.originalRequestId.value)
                                ReviewField(t("Complete definition digest", "بصمة التعريف الكامل"), it.definitionDigest)
                                ReviewField(t("Receipt digest", "بصمة الإيصال"), it.receiptDigest)
                            }
                            if (view.localClientConnected) {
                                Notice(t("The original device and first employee journals are connected to the local service. Return to the entry screen and choose Employee sign-in; use the account you created, not the company-head login.",
                                    "تم توصيل سجلات الجهاز الأصلي والموظف الأول بالخدمة المحلية. ارجع إلى شاشة البداية واختر تسجيل دخول الموظف، ثم استخدم الحساب الذي أنشأته وليس حساب رئيس الشركة."))
                            } else {
                                Notice(t("The installation is retained, but no employee is signed in. Enroll the original client journals once, or reopen the same existing journals after restart. Reopening never creates missing data.",
                                    "التثبيت محفوظ، لكن لم يسجل أي موظف الدخول. سجّل سجلات العميل الأصلي مرة واحدة، أو أعد فتح السجلات نفسها بعد إعادة التشغيل. إعادة الفتح لا تنشئ بيانات مفقودة."), caution = true)
                                if (view.firstClientEnrollmentAvailable) Button(onClick = controller::enrollOriginalLocalClient,
                                    enabled = editable, modifier = Modifier.fillMaxWidth().touchTarget()) {
                                    Text(t("Enroll this original client once", "تسجيل هذا العميل الأصلي مرة واحدة"))
                                }
                                if (view.firstClientEnrollmentContinuationAvailable) {
                                    OutlinedButton(onClick = controller::continueOriginalLocalClientEnrollment,
                                        enabled = editable, modifier = Modifier.fillMaxWidth().touchTarget()) {
                                        Text(t("Continue initial client enrollment", "متابعة التسجيل الأولي للعميل"))
                                    }
                                    Notice(t("Continuation checks the completed, never-used credential journal and starts the first employee journal only if it was never started. It cannot repair partial keys/files or replace lost work. If both journals already exist, use Reopen instead.",
                                        "تتحقق المتابعة من اكتمال سجل بيانات الدخول وعدم استخدامه، وتبدأ سجل الموظف الأول فقط إن لم يبدأ إنشاؤه سابقًا. لا تصلح المفاتيح أو الملفات الجزئية ولا تستبدل العمل المفقود. إذا كان السجلان موجودين بالفعل، فاستخدم إعادة الفتح."), caution = true)
                                }
                                OutlinedButton(onClick = controller::reconnectOriginalLocalClient,
                                    enabled = editable && view.localClientConnectionAvailable,
                                    modifier = Modifier.fillMaxWidth().touchTarget()) {
                                    Text(t("Reopen existing client journals", "إعادة فتح سجلات العميل الموجودة"))
                                }
                                OutlinedButton(onClick = controller::recoverOriginalLocalClientWrites,
                                    enabled = editable && view.localClientConnectionAvailable,
                                    modifier = Modifier.fillMaxWidth().touchTarget()) {
                                    Text(t("Recover an interrupted client journal write", "استعادة كتابة منقطعة في سجل العميل"))
                                }
                                Notice(t("Recovery requires the exact saved ciphertext and its existing native successor marker. It finishes only that journal write, never a new payment, key, account or sign-in. Missing or conflicting evidence remains held.",
                                    "تتطلب الاستعادة البيانات المشفرة المحفوظة نفسها وعلامتها الأصلية اللاحقة الموجودة. تُكمل كتابة السجل فقط، ولا تنشئ دفعة أو مفتاحًا أو حسابًا أو تسجيل دخول جديدًا. تبقى الأدلة المفقودة أو المتعارضة غير محسومة."), caution = true)
                            }
                            if (view.localClientConnected) OutlinedButton(onClick = controller::openEmployeeEnrollment,
                                enabled = editable, modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Define / recover a head-approved local employee", "تعريف / استعادة موظف محلي معتمد من الرئيس"))
                            }
                            if (view.localClientConnected) OutlinedButton(onClick = controller::openEmployeePolicies,
                                enabled = editable, modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Change / recover a complete employee policy", "تغيير / استعادة سياسة موظف كاملة"))
                            }
                            if (view.localClientConnected) OutlinedButton(onClick = controller::openDeviceAdmissions,
                                enabled = editable, modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Review / admit a retained original device", "مراجعة / قبول جهاز أصلي محفوظ"))
                            }
                            if (view.localClientConnected) OutlinedButton(onClick = controller::openFreshDevicePairing,
                                enabled = editable, modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Pair a new target through this original Master", "اقتران هدف جديد عبر هذا الجهاز الرئيسي الأصلي"))
                            }
                            Notice(t("This connection uses the original device. Further employees require their own complete head-approved definition and private work journal. Pairing transfer leads to separate retained device review. LAN attachment supplies transport only; remote employee authentication, broader recovery and platform qualification remain separate work.",
                                "يستخدم الاتصال الجهاز الأصلي. يحتاج كل موظف إضافي إلى تعريف كامل معتمد من الرئيس وسجل عمل خاص به. يقود نقل الاقتران إلى مراجعة الجهاز المحفوظ المنفصلة. يوفّر اتصال الشبكة المحلية النقل فقط؛ ويظل التحقق من الموظف البعيد والاستعادة الأوسع وتأهيل المنصة عملًا منفصلًا."), caution = true)
                            OutlinedButton(onClick = controller::refreshOriginalStatus, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(if (view.lan.role == LocalSetupLanRole.HOST)
                                    t("Read this same retained installation status", "قراءة حالة التثبيت المحفوظ نفسه")
                                    else t("Close and verify the same original installation again", "إغلاق التثبيت الأصلي نفسه والتحقق منه مجددًا"))
                            }
                        }
                        LocalSetupPhase.EMPLOYEE_ENROLLMENT -> Unit // The dedicated full-policy screen above owns this route.
                        LocalSetupPhase.EMPLOYEE_POLICY -> Unit // The separate complete replacement review owns this route.
                        LocalSetupPhase.DEVICE_ADMISSION -> Unit // The retained-original device workflow above owns this route.
                        LocalSetupPhase.FRESH_DEVICE_PAIRING -> Unit
                        LocalSetupPhase.ORIGINAL_UNRESOLVED -> {
                            Notice(t("Original state remains unresolved. Do not delete files, recreate journals, change the company root or retry a money outcome under a new ID.",
                                "تظل الحالة الأصلية غير محسومة. لا تحذف الملفات أو تعيد إنشاء السجلات أو تغير أصل الشركة أو تكرر نتيجة مالية بمعرّف جديد."), caution = true)
                            OutlinedButton(onClick = controller::refreshOriginalStatus, enabled = editable,
                                modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Inspect the same original again", "فحص الأصل نفسه مجددًا"))
                            }
                        }
                        LocalSetupPhase.CLOSING -> Notice(t("Waiting for actual native-owner close. No replacement owner is opened meanwhile.",
                            "بانتظار الإغلاق الفعلي للمالك الأصلي. لن يُفتح مالك بديل أثناء ذلك."))
                        LocalSetupPhase.CLOSED -> Notice(t("This setup owner is closed.", "أُغلق مالك الإعداد هذا."))
                    }
                    if (view.phase in setOf(LocalSetupPhase.COMPANY_REVIEW, LocalSetupPhase.COMPANY_RETAINED,
                            LocalSetupPhase.BRANCH_REVIEW, LocalSetupPhase.ORIGINAL_COMPANY_PENDING,
                            LocalSetupPhase.ORIGINAL_PREPARATION_RETAINED, LocalSetupPhase.FIRST_ACCOUNT_DETAILS)) {
                        OutlinedButton(onClick = controller::refreshOriginalStatus, enabled = editable,
                            modifier = Modifier.fillMaxWidth().touchTarget()) {
                            Text(t("Read the retained original state again", "قراءة الحالة الأصلية المحفوظة مجددًا"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanyInputs(enabled: Boolean, prepare: (LocalSetupNewCompanyInput) -> Unit) {
    var company by remember { mutableStateOf("") }
    var installerName by remember { mutableStateOf("") }
    var installerLogin by remember { mutableStateOf("") }
    var headName by remember { mutableStateOf("") }
    var headLogin by remember { mutableStateOf("") }
    var protection by remember { mutableStateOf<SelfOwnedKeyProtectionV1?>(null) }
    var invalid by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose {
        company = ""; installerName = ""; installerLogin = ""; headName = ""; headLogin = ""
    } }
    SectionHeading(t("Name the company and both people", "تسمية الشركة والشخصين"))
    SetupInput(company, { company = it }, t("Company name", "اسم الشركة"), enabled, 160)
    SetupInput(installerName, { installerName = it }, t("Installer name", "اسم مسؤول التثبيت"), enabled, 160)
    SetupInput(installerLogin, { installerLogin = it }, t("Installer login", "معرّف مسؤول التثبيت"), enabled, 64)
    SetupInput(headName, { headName = it }, t("Company head name", "اسم رئيس الشركة"), enabled, 128)
    SetupInput(headLogin, { headLogin = it }, t("Company head login", "معرّف رئيس الشركة"), enabled, 64)
    Notice(t("Installer and head are independent local human roles. Neither creates a cashier session. Enter exact lowercase Latin login identifiers; do not reuse an employee login by assumption.",
        "مسؤول التثبيت ورئيس الشركة دوران بشريان محليان مستقلان. لا ينشئ أي منهما جلسة كاشير. أدخل المعرّفات اللاتينية الصغيرة بدقة؛ ولا تفترض أنها معرّفات الموظفين."))
    Text(t("Choose key protection explicitly", "اختر حماية المفاتيح صراحةً"), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        SelfOwnedKeyProtectionV1.entries.forEach { choice ->
            Row(Modifier.fillMaxWidth().touchTarget().selectable(selected = protection == choice,
                enabled = enabled, role = Role.RadioButton, onClick = { protection = choice }),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioButton(selected = protection == choice, onClick = null, enabled = enabled)
                Text(protectionText(choice), modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
    Notice(t("A hardware request is never silently downgraded. Unsupported native profiles remain unavailable.",
        "لن يُخفّض طلب الحماية العتادية تلقائيًا. تبقى البيئات الأصلية غير المدعومة غير متاحة."))
    if (invalid) Notice(problemText(LocalSetupProblem.INVALID_INPUT), caution = true)
    Button(onClick = {
        invalid = false
        try {
            prepare(LocalSetupNewCompanyInput(company, installerName, installerLogin,
                headName, headLogin, requireNotNull(protection)))
        } catch (_: IllegalArgumentException) { invalid = true }
    }, enabled = enabled && company.isNotBlank() && installerName.isNotBlank() &&
        headName.isNotBlank() && installerLogin.isNotEmpty() && headLogin.isNotEmpty() && protection != null,
        modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Prepare and review the original company", "تحضير الشركة الأصلية ومراجعتها"))
    }
}

@Composable
private fun CompanyReview(
    review: LocalSetupCompanyReview,
    enabled: Boolean,
    complete: (DomainId, CharArray, CharArray) -> Unit,
) {
    var installerSecret by remember(review.reviewId) { mutableStateOf("") }
    var headSecret by remember(review.reviewId) { mutableStateOf("") }
    var confirmed by remember(review.reviewId) { mutableStateOf(false) }
    DisposableEffect(review.reviewId) { onDispose { installerSecret = ""; headSecret = ""; confirmed = false } }
    SectionHeading(t("Confirm this original company", "تأكيد هذه الشركة الأصلية"))
    ReviewField(t("Company", "الشركة"), review.companyName, code = false)
    ReviewField(t("Installer", "مسؤول التثبيت"), review.installerName, code = false)
    ReviewField(t("Installer login", "معرّف مسؤول التثبيت"), review.installerLogin)
    ReviewField(t("Head", "رئيس الشركة"), review.headName, code = false)
    ReviewField(t("Head login", "معرّف الرئيس"), review.headLogin)
    ReviewField(t("Organization", "المؤسسة"), review.organizationId.value)
    ReviewField(t("Company ID", "معرّف الشركة"), review.companyId.value)
    ReviewField(t("Root fingerprint", "بصمة أصل الشركة"), review.rootFingerprint)
    ReviewField(t("Head authority", "سلطة الرئيس"), review.headAuthorityId.value)
    ReviewField(t("Head enrollment", "تسجيل الرئيس"), review.headEnrollmentId.value)
    Text(protectionText(review.protection))
    review.purposes.sortedBy { it.name }.forEach {
        Text(when (it) {
            LocalCompanyHeadPurposeV1.POLICY_PUBLISH -> t("Head purpose: publish local policy", "غرض الرئيس: نشر السياسة المحلية")
            LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE ->
                t("Head purpose: approve the complete original installation", "غرض الرئيس: اعتماد التثبيت الأصلي الكامل")
        })
    }
    Notice(t("Both head purposes last until locally disabled. These are separate from installer and employee authority.",
        "يستمر غرضا الرئيس حتى تعطيلهما محليًا. وهما منفصلان عن صلاحية مسؤول التثبيت والموظف."))
    fun submit() {
        if (!enabled || !confirmed || installerSecret.length !in 12..256 || headSecret.length !in 12..256) return
        val installer = installerSecret.toCharArray()
        val head = try { headSecret.toCharArray() }
        catch (failure: Throwable) { installer.fill('\u0000'); throw failure }
        installerSecret = ""; headSecret = ""; confirmed = false
        try { complete(review.reviewId, installer, head) }
        finally { installer.fill('\u0000'); head.fill('\u0000') }
    }
    SetupSecret(installerSecret, { installerSecret = it }, t("New installer password", "كلمة مرور جديدة لمسؤول التثبيت"), enabled)
    SetupSecret(headSecret, { headSecret = it }, t("Independent new head password", "كلمة مرور جديدة مستقلة للرئيس"), enabled, ::submit)
    Consent(t("I reviewed this original root, both people, protection and both head purposes.",
        "راجعت هذا الأصل والشخصين والحماية وغرضي الرئيس."), confirmed, enabled) { confirmed = it }
    Button(onClick = ::submit, enabled = enabled && confirmed && installerSecret.length in 12..256 && headSecret.length in 12..256,
        modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Retain this local company and head", "حفظ هذه الشركة المحلية ورئيسها"))
    }
}

@Composable
private fun CompanyDetails(company: LocalSetupCompanyView) {
    SectionHeading(t("Local company retained", "حُفظت الشركة المحلية"))
    ReviewField(t("Company", "الشركة"), company.companyName, code = false)
    ReviewField(t("Root fingerprint", "بصمة أصل الشركة"), company.rootFingerprint)
    ReviewField(t("Installer", "مسؤول التثبيت"), company.installerName, code = false)
    ReviewField(t("Installer login", "معرّف مسؤول التثبيت"), company.installerLogin)
    ReviewField(t("Head", "رئيس الشركة"), company.headName, code = false)
    ReviewField(t("Head login", "معرّف الرئيس"), company.headLogin)
    ReviewField(t("Head authority", "سلطة الرئيس"), company.headAuthorityId.value)
    ReviewField(t("Head enrollment", "تسجيل الرئيس"), company.headEnrollmentId.value)
    ReviewField(t("Retained head account status", "حالة حساب الرئيس المحفوظة"),
        if (company.headEnabled) t("Enabled", "مفعّل") else t("Disabled", "معطّل"), code = false)
    LocalCompanyHeadPurposeV1.entries.forEach { purpose ->
        ReviewField(when (purpose) {
            LocalCompanyHeadPurposeV1.POLICY_PUBLISH -> t("Head purpose: publish local policy", "غرض الرئيس: نشر السياسة المحلية")
            LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE ->
                t("Head purpose: approve complete original installation", "غرض الرئيس: اعتماد التثبيت الأصلي الكامل")
        }, if (purpose in company.enabledHeadPurposes) t("Enabled", "مفعّل") else t("Disabled", "معطّل"), code = false)
    }
    Text(protectionText(company.protection))
    Notice(t("This is not yet a branch installation or an employee account. The first employee needs a separate login and password and the complete approved local policy.",
        "هذا ليس تثبيتًا للفرع أو حساب موظف بعد. يحتاج الموظف الأول إلى معرّف وكلمة مرور منفصلين وإلى السياسة المحلية الكاملة المعتمدة."))
}

@Composable
private fun BranchInputs(enabled: Boolean, prepare: (String) -> Unit) {
    var branch by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { branch = "" } }
    SetupInput(branch, { branch = it }, t("Original branch name", "اسم الفرع الأصلي"), enabled, 160)
    Button(onClick = { prepare(branch) }, enabled = enabled && branch.isNotBlank(),
        modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Prepare the original branch allocation", "تحضير تخصيص الفرع الأصلي"))
    }
}

@Composable
private fun BranchDetails(review: LocalSetupBranchReview) {
    SectionHeading(t("Original branch allocation", "تخصيص الفرع الأصلي"))
    ReviewField(t("Branch name", "اسم الفرع"), review.branchName, code = false)
    ReviewField(t("Root fingerprint", "بصمة أصل الشركة"), review.rootFingerprint)
    ReviewField(t("Organization", "المؤسسة"), review.organizationId.value)
    ReviewField(t("Company", "الشركة"), review.companyId.value)
    ReviewField(t("Branch", "الفرع"), review.branchId.value)
    ReviewField(t("Allocation", "التخصيص"), review.allocationId.value)
    ReviewField(t("Installation", "التثبيت"), review.installationId.value)
    ReviewField(t("Device", "الجهاز"), review.deviceId.value)
    ReviewField(t("First employee ID", "معرّف الموظف الأول"), review.firstActorId.value)
    Text(protectionText(review.protection))
}

@Composable
private fun BranchReview(review: LocalSetupBranchReview, installerLogin: String, enabled: Boolean,
    issue: (DomainId, CharArray) -> Unit) {
    var secret by remember(review.reviewId) { mutableStateOf("") }
    var confirmed by remember(review.reviewId) { mutableStateOf(false) }
    DisposableEffect(review.reviewId) { onDispose { secret = ""; confirmed = false } }
    BranchDetails(review)
    ReviewField(t("Installer login", "معرّف مسؤول التثبيت"), installerLogin)
    fun submit() {
        if (!enabled || !confirmed || secret.length !in 12..256) return
        val owned = secret.toCharArray()
        secret = ""; confirmed = false
        try { issue(review.reviewId, owned) }
        finally { owned.fill('\u0000') }
    }
    SetupSecret(secret, { secret = it }, t("Fresh installer password", "كلمة مرور مسؤول التثبيت مجددًا"), enabled, ::submit)
    Consent(t("Prepare only these original branch/device IDs; this does not approve the full installation.",
        "تحضير معرّفات الفرع والجهاز الأصلية هذه فقط؛ وهذا لا يعتمد التثبيت الكامل."), confirmed, enabled) { confirmed = it }
    Button(onClick = ::submit, enabled = enabled && confirmed && secret.length in 12..256,
        modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Retain original preparation", "حفظ التحضير الأصلي"))
    }
}

@Composable
private fun NativeDetails(value: LocalSetupNativeView) {
    SectionHeading(t("Original native material retained", "حُفظت المواد الأصلية للبيئة الأصلية"))
    ReviewField(t("Branch name", "اسم الفرع"), value.branchName, code = false)
    ReviewField(t("Branch", "الفرع"), value.branchId.value)
    ReviewField(t("Installation", "التثبيت"), value.installationId.value)
    ReviewField(t("Device", "الجهاز"), value.deviceId.value)
    ReviewField(t("First employee ID", "معرّف الموظف الأول"), value.firstActorId.value)
    ReviewField(t("Target signing fingerprint", "بصمة توقيع الهدف"), value.targetFingerprint)
    ReviewField(t("Original native binding", "الربط الأصلي للبيئة الأصلية"), value.nativeBindingDigest)
    Text(protectionText(value.protection))
}

@Composable
private fun InstallationHeld() {
    Notice(t("Full installation remains held: original complete definition, fresh head approval, target proof, durable SQL/history settlement and acknowledgement, original client journals and real service admission must all be joined. No business UI is enabled by this status.",
        "التثبيت الكامل معلّق: يلزم ربط التعريف الأصلي الكامل واعتماد الرئيس الجديد وإثبات الهدف وتسوية قاعدة البيانات والسجل الدائمة والإقرار وسجلات العميل الأصلية والقبول الفعلي للخدمة. هذه الحالة لا تفعّل واجهة الأعمال."), caution = true)
}

/**
 * Complete typed D7 DATA review. Only a real controller binding offers separate password
 * submission; an unbound review remains read-only. No checkbox/digest manufactures approval.
 */
@Composable
public fun LocalSetupDefinitionReviewScreen(
    review: LocalSetupDefinitionReview,
    language: AppLanguage,
    modifier: Modifier = Modifier,
    enabled: Boolean = false,
    onSubmit: ((DomainId, CharArray, CharArray) -> Unit)? = null,
    onLock: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onLanguageChange: ((AppLanguage) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val definition = review.snapshot.definition
    val account = definition.firstAccount
    val policy = account.policy
    val admission = definition.masterAdmission
    val coordinates = definition.allocation.coordinates
    val human = definition.human
    val grants = policy.grants
    CompositionLocalProvider(LocalAppLanguage provides language, LocalLayoutDirection provides language.direction) {
        PosTheme {
            LazyColumn(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    onBack?.let { back -> OutlinedButton(onClick = back, modifier = Modifier.touchTarget()) {
                        Text(t("Device choices", "خيارات الجهاز"))
                    } }
                    onLock?.let { lock -> OutlinedButton(onClick = lock, modifier = Modifier.touchTarget()) {
                        Text(t("Lock setup", "قفل الإعداد"))
                    } }
                    onLanguageChange?.let { change -> OutlinedButton(onClick = { change(language.toggle()) }, modifier = Modifier.touchTarget()) {
                        Text(if (language == AppLanguage.ENGLISH) "العربية" else "English")
                    } }
                    SectionHeading(t("Review the COMPLETE original installation", "مراجعة التثبيت الأصلي الكامل"),
                        t("All grants, limits, configuration and device admission are shown. No hidden administrator preset.",
                            "تظهر جميع الصلاحيات والحدود والإعدادات وقبول الجهاز. لا يوجد إعداد مدير مخفي."))
                    ReviewField(t("Definition digest", "بصمة التعريف"), review.snapshot.definitionDigest.value)
                    ReviewField(t("Allocation digest", "بصمة التخصيص"), review.snapshot.allocationDigest.value)
                    ReviewField(t("Original target key digest", "بصمة مفتاح الهدف الأصلي"), review.snapshot.originalTargetKeyDigest.value)
                    ScopeDetails(coordinates.scope)
                    ReviewField(t("Original request", "الطلب الأصلي"), definition.originalRequestId.value)
                    ReviewField(t("Authority epoch", "حقبة السلطة"), definition.authorityEpoch.value.toString())
                    ReviewField(t("Allocation", "التخصيص"), coordinates.allocationId.value)
                    ReviewField(t("Installation", "التثبيت"), coordinates.installationId.value)
                    ReviewField(t("Assignment", "التعيين"), coordinates.assignmentId.value)
                    ReviewField(t("Device", "الجهاز"), coordinates.deviceId.value)
                    ReviewField(t("Storage instance", "نسخة التخزين"), coordinates.storageInstanceId.value)
                    ReviewField(t("Storage key ID", "معرّف مفتاح التخزين"), coordinates.storageKeyId.value)
                    ReviewField(t("Pepper key ID", "معرّف مفتاح حماية كلمة المرور"), coordinates.pepperKeyId.value)
                    ReviewField(t("Target signing key ID", "معرّف مفتاح توقيع الهدف"), coordinates.targetSigningKeyId.value)
                    ReviewField(t("Native allocation binding", "الربط الأصلي للتخصيص"), definition.allocation.originalNativeBindingDigest.value)
                    ReviewField(t("P1 root document digest", "بصمة وثيقة أصل P1"), definition.allocation.p1RootDocumentDigest.value)
                    ReviewField(t("P1 delegation digest", "بصمة تفويض P1"), definition.allocation.p1DelegationDigest.value)
                    ReviewField(t("P1 preparation digest", "بصمة تحضير P1"), definition.allocation.p1PreparationDigest.value)
                    ReviewField(t("Target public key", "المفتاح العام للهدف"), publicHex(definition.allocation.copyTargetPublicKey()))
                    ReviewField(t("Root ID", "معرّف الأصل"), human.rootId.value)
                    ReviewField(t("Root key ID", "معرّف مفتاح الأصل"), human.rootKeyId.value)
                    ReviewField(t("Human root digest", "بصمة أصل السلطة البشرية"), human.rootDocumentDigest.value)
                    ReviewField(t("Founding action", "إجراء التأسيس"), human.foundingActionId.value)
                    ReviewField(t("Founding body digest", "بصمة مضمون التأسيس"), human.foundingBodyDigest.value)
                    ReviewField(t("Head authority", "سلطة الرئيس"), human.headAuthorityId.value)
                    ReviewField(t("Head enrollment", "تسجيل الرئيس"), human.headEnrollmentId.value)
                    ReviewField(t("Head installation key ID", "معرّف مفتاح اعتماد التثبيت للرئيس"), human.headInstallationKeyId.value)
                    ReviewField(t("Head installation public key", "المفتاح العام لاعتماد التثبيت للرئيس"),
                        publicHex(human.copyHeadInstallationPublicKey()))
                    SectionHeading(t("Separate first employee", "الموظف الأول المنفصل"))
                    ReviewField(t("Independent company head login", "معرّف رئيس الشركة المستقل"), review.headLogin)
                    LocalSetupAccountIdentityReview(account)
                }
                itemsIndexed(grants) { index, grant -> GrantDetails(index, grant) }
                item {
                    LocalSetupAccountConfigurationReview(account)
                    SectionHeading(t("Complete master-device admission", "قبول الجهاز الرئيسي بالكامل"))
                    ScopeDetails(admission.scope)
                    ReviewField(t("Device", "الجهاز"), admission.deviceId.value)
                    ReviewField(t("Admission revision", "مراجعة القبول"), admission.revision.value.toString())
                    ReviewField(t("Requested admission", "القبول المطلوب"), if (admission.admitted) t("Yes", "نعم") else t("No", "لا"), code = false)
                    ReviewField(t("Public key identifier", "معرّف المفتاح العام"), admission.publicKeyIdentifier ?: t("Absent", "غير موجود"))
                    ReviewField(t("Public fingerprint", "البصمة العامة"), admission.publicFingerprint ?: t("Absent", "غير موجودة"))
                    ReviewField(t("Declared capability codes", "رموز القدرات المعلنة"),
                        admission.capabilities.sorted().joinToString("\n").ifEmpty { t("None", "لا شيء") })
                    Notice(t("Capability descriptions and public fingerprints are not current transport or master admission.",
                        "أوصاف القدرات والبصمات العامة ليست قبولًا حاليًا للاتصال أو الجهاز الرئيسي."))
                    if (onSubmit == null) Notice(t("Read-only installation data; no native submission is bound to this screen.",
                        "بيانات تثبيت للقراءة فقط؛ لا يوجد إرسال أصلي مرتبط بهذه الشاشة."))
                    else FirstAccountSubmission(review, enabled, onSubmit)
                    onRefresh?.let { refresh -> OutlinedButton(onClick = refresh, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().touchTarget()) {
                        Text(t("Discard an unsubmitted draft / reread the retained original", "تجاهل مسودة غير مرسلة / إعادة قراءة الأصل المحفوظ"))
                    } }
                }
            }
        }
    }
}

@Composable
internal fun SetupInput(value: String, change: (String) -> Unit, label: String, enabled: Boolean, maximum: Int) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, { if (it.length <= maximum) change(it) }, label = { Text(label) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
}

@Composable
internal fun SetupSecret(value: String, change: (String) -> Unit, label: String, enabled: Boolean,
    submit: (() -> Unit)? = null) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, { if (it.length <= 256) change(it) }, label = { Text(label) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
            imeAction = if (submit == null) ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { submit?.invoke() }))
}

@Composable
internal fun Consent(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().touchTarget().toggleable(value = checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = change), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

private fun publicHex(bytes: ByteArray): String = try {
    val alphabet = "0123456789abcdef"
    buildString(bytes.size * 2) { bytes.forEach { val n = it.toInt() and 255; append(alphabet[n ushr 4]); append(alphabet[n and 15]) } }
} finally { bytes.fill(0) }

@Composable
internal fun t(english: String, arabic: String): String =
    if (LocalAppLanguage.current == AppLanguage.ARABIC) arabic else english

@Composable
private fun protectionText(value: SelfOwnedKeyProtectionV1): String = when (value) {
    SelfOwnedKeyProtectionV1.OS_ISOLATED -> t("OS-isolated AndroidKeyStore (software permitted)", "AndroidKeyStore معزول بالنظام (يسمح بالتنفيذ البرمجي)")
    SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> t("Secure hardware required", "عتاد آمن مطلوب")
    SelfOwnedKeyProtectionV1.STRONGBOX -> t("StrongBox required", "StrongBox مطلوب")
}

@Composable
internal fun workText(work: LocalSetupWork): String = when (work) {
    LocalSetupWork.OPENING_LOCAL_AUTHORITY -> t("Opening the local authority owner…", "فتح مالك السلطة المحلية…")
    LocalSetupWork.PREPARING_COMPANY -> t("Retaining the original company action…", "حفظ إجراء الشركة الأصلي…")
    LocalSetupWork.RETAINING_COMPANY -> t("Retaining independent installer/head credentials…", "حفظ بيانات دخول مستقلة للمثبت والرئيس…")
    LocalSetupWork.READING_ORIGINAL_COMPANY -> t("Reading only the original local company…", "قراءة الشركة المحلية الأصلية فقط…")
    LocalSetupWork.RESUMING_ORIGINAL_COMPANY -> t("Continuing only the original company key intents…", "متابعة نوايا مفاتيح الشركة الأصلية فقط…")
    LocalSetupWork.INSPECTING_LOCAL_AUTHORITY -> t("Reading retained company state…", "قراءة حالة الشركة المحفوظة…")
    LocalSetupWork.PREPARING_BRANCH -> t("Reserving the original branch IDs…", "حجز معرّفات الفرع الأصلية…")
    LocalSetupWork.RETAINING_PREPARATION -> t("Retaining original native preparation…", "حفظ التحضير الأصلي للبيئة الأصلية…")
    LocalSetupWork.PREPARING_ORIGINAL_TARGET -> t("Preparing only the original native target…", "تحضير الهدف الأصلي فقط…")
    LocalSetupWork.READING_ORIGINAL_RECEIPT -> t("Reading the original history and receipt…", "قراءة السجل والإيصال الأصليين…")
    LocalSetupWork.CLOSING_NATIVE_OWNER -> t("Waiting for actual native close…", "بانتظار الإغلاق الفعلي…")
    LocalSetupWork.OPENING_ORIGINAL_INSTALLATION -> t("Opening only the original installation owner…", "فتح مالك التثبيت الأصلي فقط…")
    LocalSetupWork.REVIEWING_FIRST_ACCOUNT -> t("Checking the complete first-account definition…", "فحص تعريف الحساب الأول الكامل…")
    LocalSetupWork.INSTALLING_FIRST_ACCOUNT -> t("Awaiting original approval, durable installation and acknowledgement…", "بانتظار الاعتماد الأصلي والتثبيت الدائم والإقرار…")
    LocalSetupWork.ACKNOWLEDGING_ORIGINAL_INSTALLATION -> t("Completing only the original company acknowledgement…", "إكمال إقرار الشركة الأصلي فقط…")
    LocalSetupWork.CONNECTING_ORIGINAL_LOCAL_CLIENT -> t("Opening protected original client journals…", "فتح سجلات العميل الأصلي المحمية…")
    LocalSetupWork.READING_EMPLOYEE_ENROLLMENT -> t("Reading the exact retained employee original…", "قراءة الأصل المحفوظ للموظف بعينه…")
    LocalSetupWork.RETAINING_EMPLOYEE_ENROLLMENT -> t("Retaining the employee's original request identity…", "حفظ هوية طلب الموظف الأصلي…")
    LocalSetupWork.REVIEWING_EMPLOYEE_ENROLLMENT -> t("Retaining the complete employee definition for review…", "حفظ تعريف الموظف الكامل للمراجعة…")
    LocalSetupWork.APPROVING_EMPLOYEE_ENROLLMENT -> t("Awaiting approval of this exact employee definition…", "بانتظار اعتماد تعريف الموظف هذا بعينه…")
    LocalSetupWork.ENROLLING_EMPLOYEE_ACCOUNT -> t("Awaiting the original account result…", "بانتظار نتيجة الحساب الأصلي…")
    LocalSetupWork.CONNECTING_EMPLOYEE_INTENT_JOURNAL -> t("Opening this employee's private work journal…", "فتح سجل عمل الموظف الخاص…")
    LocalSetupWork.READING_EMPLOYEE_POLICY -> t("Reading retained original employee policies…", "قراءة سياسات الموظفين الأصلية المحفوظة…")
    LocalSetupWork.PREPARING_EMPLOYEE_POLICY -> t("Preparing this complete original policy for review…", "تحضير هذه السياسة الأصلية كاملةً للمراجعة…")
    LocalSetupWork.APPROVING_EMPLOYEE_POLICY -> t("Awaiting named-head approval of this exact original policy…", "بانتظار اعتماد الرئيس المسمّى لهذه السياسة الأصلية بعينها…")
    LocalSetupWork.ACTIVATING_EMPLOYEE_POLICY -> t("Awaiting the original complete-policy activation…", "بانتظار تفعيل السياسة الكاملة الأصلي…")
    LocalSetupWork.LOOKING_UP_EMPLOYEE_POLICY -> t("Looking up only the original policy outcome…", "الاستعلام عن نتيجة السياسة الأصلية فقط…")
    LocalSetupWork.READING_DEVICE_ADMISSION -> t("Reading retained original native device actions…", "قراءة إجراءات الأجهزة الأصلية المحفوظة…")
    LocalSetupWork.APPROVING_DEVICE_ADMISSION -> t("Awaiting named-head approval of this exact device original…", "بانتظار اعتماد الرئيس المسمّى لأصل الجهاز هذا بعينه…")
    LocalSetupWork.ADMITTING_DEVICE -> t("Awaiting this original device admission and native settlement…", "بانتظار قبول هذا الجهاز الأصلي وتسويته الأصلية…")
    LocalSetupWork.LOOKING_UP_DEVICE_ADMISSION -> t("Looking up only this original device outcome…", "الاستعلام عن نتيجة هذا الجهاز الأصلي فقط…")
    LocalSetupWork.READING_FRESH_DEVICE_PAIRING -> t("Reading the exact original pairing context…", "قراءة سياق الاقتران الأصلي الدقيق…")
    LocalSetupWork.PREPARING_FRESH_DEVICE_TARGET -> t("Retaining intent and preparing the new physical target…", "حفظ النية وتحضير الهدف الفعلي الجديد…")
    LocalSetupWork.ACQUIRING_FRESH_DEVICE_SDK -> t("Acquiring this target's original SDK identity…", "الحصول على هوية SDK الأصلية لهذا الهدف…")
    LocalSetupWork.PREPARING_FRESH_DEVICE_CHALLENGE -> t("Preparing the exact original Master challenge…", "تحضير تحدي الجهاز الرئيسي الأصلي الدقيق…")
    LocalSetupWork.RESPONDING_FRESH_DEVICE_CHALLENGE -> t("Producing only the original target possession response…", "إنتاج استجابة حيازة المفتاح الأصلية للهدف فقط…")
    LocalSetupWork.PROVING_FRESH_DEVICE_TARGET -> t("Asking the original Master to verify this target proof…", "طلب التحقق من إثبات هذا الهدف من الجهاز الرئيسي الأصلي…")
    LocalSetupWork.IMPORTING_FRESH_DEVICE_DOCUMENT -> t("Reading the complete pairing document for review…", "قراءة مستند الاقتران الكامل للمراجعة…")
    LocalSetupWork.RETIRING_UNSIGNED_DEVICE_PAIRING -> t("Retiring the exact known-unsigned pairing…", "إنهاء الاقتران المعروف دون توقيع بعينه…")
    LocalSetupWork.CONTINUING_UNSIGNED_DEVICE_PAIRING -> t("Allocating the next original target response…", "تخصيص استجابة الهدف الأصلية التالية…")
    LocalSetupWork.EXPORTING_FRESH_DEVICE_DOCUMENT -> t("Exporting the exact retained original document…", "تصدير المستند الأصلي المحفوظ بعينه…")
    LocalSetupWork.VERIFYING_APPROVED_PAIRING_MASTER -> t("Verifying the selected original Master and retaining its exact approval…", "التحقق من الجهاز الرئيسي الأصلي المختار وحفظ اعتماده الدقيق…")
    LocalSetupWork.STARTING_LAN_HOST -> t("Starting the retained Master's explicit LAN Host…", "بدء مضيف الشبكة المحلية الصريح للجهاز الرئيسي المحفوظ…")
    LocalSetupWork.CONNECTING_LAN_CLIENT -> t("Transferring this original target and connecting the selected LAN Client…", "نقل هذا الهدف الأصلي ووصل عميل الشبكة المحلية المختار…")
    LocalSetupWork.READING_LAN_ATTACHMENT -> t("Reobserving only this retained LAN attachment…", "رصد اتصال الشبكة المحلية المحفوظ هذا فقط مجددًا…")
    LocalSetupWork.CLOSING_LAN_ATTACHMENT -> t("Waiting for this LAN attachment's actual close…", "بانتظار الإغلاق الفعلي لاتصال الشبكة المحلية هذا…")
}

@Composable
internal fun problemText(problem: LocalSetupProblem): String = when (problem) {
    LocalSetupProblem.BUSY -> t("Original work is still owned or busy; no duplicate was started.", "العمل الأصلي لا يزال مملوكًا أو مشغولًا؛ لم يبدأ عمل مكرر.")
    LocalSetupProblem.INVALID_INPUT -> t("Check the exact input. Invalid or missing values are refused; entered text is not silently normalized.", "تحقق من المدخلات الدقيقة. تُرفض القيم المفقودة أو غير الصحيحة؛ ولا يُعدّل النص المدخل تلقائيًا.")
    LocalSetupProblem.AUTHENTICATION_REFUSED -> t("Local human authentication was refused.", "رُفض التحقق من هوية الشخص محليًا.")
    LocalSetupProblem.THROTTLED -> t("The protected local authentication delay remains in force.", "لا يزال تأخير التحقق المحلي المحمي ساريًا.")
    LocalSetupProblem.UNSUPPORTED_PLATFORM -> t("This native engineering profile is unsupported on this device.", "هذا الملف الهندسي الأصلي غير مدعوم على الجهاز.")
    LocalSetupProblem.INVALID_APPLICATION -> t("Installed application or native custody could not be confirmed.", "تعذر تأكيد التطبيق المثبت أو حيازة البيئة الأصلية.")
    LocalSetupProblem.USER_LOCKED -> t("Unlock the device's operating-system user first.", "افتح قفل مستخدم نظام التشغيل أولًا.")
    LocalSetupProblem.CAPACITY_EXHAUSTED -> t("Required durable capacity is unavailable. No reduced definition was substituted.", "السعة الدائمة المطلوبة غير متاحة. لم يُستبدل التعريف بتعريف ناقص.")
    LocalSetupProblem.ORIGINAL_ACTION_REQUIRED -> t("The exact retained original action is required; do not create a replacement.", "يلزم الإجراء الأصلي المحفوظ بعينه؛ لا تنشئ بديلًا.")
    LocalSetupProblem.ISSUANCE_WITHDRAWN -> t("New issuance was disabled. Existing evidence is preserved.", "عُطّل الإصدار الجديد. الأدلة الموجودة محفوظة.")
    LocalSetupProblem.STATE_UNCERTAIN -> t("Original state is uncertain; do not delete, reset or recreate it.", "الحالة الأصلية غير مؤكدة؛ لا تحذفها أو تصفّرها أو تعيد إنشاءها.")
    LocalSetupProblem.NATIVE_UNAVAILABLE -> t("The native owner is unavailable. No successful installation is claimed.", "المالك الأصلي غير متاح. لا يُدّعى نجاح التثبيت.")
    LocalSetupProblem.CLOSE_UNCONFIRMED -> t("Actual native close is unconfirmed; replacement ownership remains blocked.", "الإغلاق الفعلي غير مؤكد؛ تظل الملكية البديلة محجوبة.")
    LocalSetupProblem.HEAD_ENROLLMENT_REQUIRED -> t("The original independent head enrollment is required.", "يلزم تسجيل الرئيس المستقل الأصلي.")
    LocalSetupProblem.ORIGINAL_TARGET_OWNER_REQUIRED -> t("This step requires the genuine original target owner, not a separate provisioner.", "تتطلب الخطوة المالك الفعلي للهدف الأصلي، وليس مثبتًا منفصلًا.")
    LocalSetupProblem.FULL_INSTALLATION_HELD -> t("Full original installation and operational admission remain held.", "التثبيت الأصلي الكامل والقبول التشغيلي معلّقان.")
    LocalSetupProblem.RUNTIME_PUBLICATION_HELD -> t("The installation is retained, but no usable local client is currently published. Complete the explicit journal connection if available; held native history cannot be bypassed.",
        "التثبيت محفوظ، لكن لا يوجد عميل محلي متاح حاليًا. أكمل اتصال السجلات الصريح إن كان متاحًا؛ ولا يمكن تجاوز تعليق السجل الأصلي.")
    LocalSetupProblem.CLIENT_JOURNAL_UNAVAILABLE -> t("Client enrollment or existing journal verification did not complete. No empty replacement was created. Keep the original files and keys; interrupted or inconsistent state requires reconciliation.",
        "لم يكتمل تسجيل العميل أو التحقق من السجلات الموجودة. لم يُنشأ بديل فارغ. احتفظ بالملفات والمفاتيح الأصلية؛ فالحالة المنقطعة أو غير المتسقة تحتاج إلى تسوية.")
    LocalSetupProblem.ORIGINAL_KEY_PREPARATION_REQUIRED -> t("The original native key preparation must be continued explicitly; no replacement keys or IDs are allowed.", "يجب متابعة تحضير المفاتيح الأصلي صراحةً؛ ولا يُسمح بمفاتيح أو معرّفات بديلة.")
    LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED -> t("The local master was explicitly stopped. Reopen the original installation deliberately; no new master or employee session was started.",
        "أُوقف الجهاز الرئيسي المحلي صراحةً. أعد فتح التثبيت الأصلي بإجراء واضح؛ لم يبدأ جهاز رئيسي جديد أو جلسة موظف.")
    LocalSetupProblem.AUTOMATIC_REOPEN_UNAVAILABLE -> t("The saved startup or stop intent could not be verified. Keep the original storage and use explicit original-state recovery; no automatic repair was attempted.",
        "تعذر التحقق من نية التشغيل أو الإيقاف المحفوظة. احتفظ بالتخزين الأصلي واستخدم استرداد الحالة الأصلية صراحةً؛ لم تُجرَ محاولة إصلاح تلقائي.")
}
