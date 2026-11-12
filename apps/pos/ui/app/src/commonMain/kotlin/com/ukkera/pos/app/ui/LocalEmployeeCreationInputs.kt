package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.LocalAdministrationController
import com.ukkera.pos.app.LocalAdministrationWorkspace
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.setup.Consent
import com.ukkera.pos.app.setup.LocalSetupGrantInput
import com.ukkera.pos.app.setup.LocalSetupPermissionSets
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalEmployeeCreationConfigurationV1
import com.ukkera.pos.security.LocalEmployeeCreationContextV1
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Transient definition editing only. The protected service captures canonical bytes and the
 * controller retains the exact original before any journal preparation. Nothing is saveable. */
@Composable
internal fun LocalEmployeeCreationInputs(controller: LocalAdministrationController,
    state: LocalAdministrationWorkspace, token: NavigationToken) {
    val creation = state.creation
    val enabled = !state.working && !state.passwordEntryOpen && !creation.passwordEntryOpen
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        SectionHeading(creationInputText("Create a branch employee", "إنشاء موظف في الفرع"))
        val context = creation.context
        if (context != null) {
            EmployeeDefinitionInputs(context, enabled) { definition, withPassword ->
                controller.prepareCreation(token, definition, withPassword)
            }
        } else if (creation.original == null && creation.loaded && !creation.restricted && creation.problem == null) {
            var actor by remember(creation.target) { mutableStateOf("") }
            var invalid by remember(creation.target) { mutableStateOf(false) }
            Text(creationInputText("Read the current creation choices first. No employee, password or saved request is created by this read.",
                "اقرأ خيارات الإنشاء الحالية أولًا. لا تُنشئ هذه القراءة موظفًا أو كلمة مرور أو طلبًا محفوظًا."))
            if (creation.target == null) OutlinedTextField(actor, { if (it.length <= 36) { actor = it; invalid = false } },
                label = { Text(creationInputText("Employee UUID; leave empty for a new ID", "معرّف UUID للموظف؛ اتركه فارغًا لتوليد معرّف جديد")) },
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (invalid) Notice(creationInputText("Enter a valid employee UUID, or leave it empty.", "أدخل معرّف UUID صالحًا للموظف أو اتركه فارغًا."), caution = true)
            Button(onClick = {
                try { controller.beginCreation(token, actor.takeIf { it.isNotEmpty() }?.let(::DomainId)) }
                catch (_: IllegalArgumentException) { invalid = true }
            }, enabled = enabled && state.original == null,
                modifier = Modifier.touchTarget()) { Text(creationInputText("Read protected creation choices", "قراءة خيارات الإنشاء المحمية")) }
        }
        if (creation.target != null && creation.original == null) OutlinedButton(
            onClick = { controller.discardCreationInputs(token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(creationInputText("Discard unsaved definition", "تجاهل التعريف غير المحفوظ"))
        }
    }
}

@Composable
private fun EmployeeDefinitionInputs(context: LocalEmployeeCreationContextV1, enabled: Boolean,
    prepare: (LocalAccountProvisioning, Boolean) -> Unit) {
    var login by remember(context) { mutableStateOf("") }
    var grants by remember(context) { mutableStateOf(emptyList<PermissionGrant>()) }
    var profileIndex by remember(context) { mutableStateOf<Int?>(null) }
    var onward by remember(context) { mutableStateOf(emptySet<Int>()) }
    var withPassword by remember(context) { mutableStateOf(false) }
    var consent by remember(context) { mutableStateOf(false) }
    var invalid by remember(context) { mutableStateOf(false) }
    val profiles = remember(context) { context.authenticationChoices.copyProfiles() }
    ScopeDetails(context.scope)
    ReviewField(creationInputText("Original request", "الطلب الأصلي"), context.target.requestId.value)
    ReviewField(creationInputText("Employee ID", "معرّف الموظف"), context.target.actorId.value)
    ReviewField(creationInputText("Creator policy revision at read", "إصدار سياسة المنشئ عند القراءة"), context.policyRevision.value.toString())
    ReviewField(creationInputText("Current admitted devices; all are bound to this definition", "الأجهزة المقبولة حاليًا؛ ترتبط جميعها بهذا التعريف"),
        context.admittedDeviceIds.sortedBy { it.value }.joinToString("\n") { it.value })
    OutlinedTextField(login, { if (it.length <= 128) { login = it; consent = false; invalid = false } },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(creationInputText("Exact employee login", "معرّف دخول الموظف الدقيق")) })
    SectionHeading(creationInputText("Explicit requested grants", "الصلاحيات المطلوبة صراحة"))
    Notice(creationInputText("No grants are preselected. Each allowed grant must fit a current creator grant and its separate transfer ceiling. The Master checks again when creating the employee.",
        "لا توجد صلاحيات محددة مسبقًا. يجب أن تلائم كل صلاحية مسموحة صلاحية حالية للمنشئ وحد نقلها المنفصل. يعيد الجهاز الرئيسي الفحص عند إنشاء الموظف."))
    LocalSetupPermissionSets(context.scope, context.target.actorId, grants, enabled) { additions ->
        grants = grants + additions.filter { addition -> grants.none { it.permission == addition.permission } }
        consent = false
    }
    grants.forEachIndexed { index, grant ->
        GrantDetails(index, grant)
        OutlinedButton(onClick = { grants = grants.filterIndexed { i, _ -> i != index }; consent = false },
            enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(creationInputText("Remove grant ${index + 1}", "إزالة الصلاحية ${index + 1}"))
        }
    }
    LocalSetupGrantInput(context.scope, context.target.actorId, enabled && grants.size < 1024) {
        grants = grants + it; consent = false
    }
    if (grants.isEmpty()) Notice(creationInputText("No grants selected: this employee has no business-area access.",
        "لم تُحدَّد صلاحيات: لن يتمكن الموظف من دخول أقسام الأعمال."), caution = true)
    SectionHeading(creationInputText("Choose one complete authentication profile", "اختيار إعداد مصادقة كامل واحد"))
    profiles.forEachIndexed { index, profile ->
        CreationAuthenticationProfileDetails(index, profile)
        if (profile.namedRecipientId == null || profile.namedRecipientId == context.target.actorId) {
            Consent(creationInputText("Use this complete profile for this employee", "استخدام هذا الإعداد الكامل لهذا الموظف"),
                profileIndex == index, enabled) { checked -> profileIndex = if (checked) index else null; consent = false }
        } else Text(creationInputText("This profile names a different recipient and cannot configure this employee.",
            "يسمّي هذا الإعداد مستفيدًا آخر ولا يمكن استخدامه لإعداد هذا الموظف."))
        Consent(creationInputText("Include this exact choice in the employee's onward creation catalogue", "تضمين هذا الخيار الدقيق في قائمة الإنشاء التي يمكن للموظف تفويضها"),
            index in onward, enabled) { checked -> onward = if (checked) onward + index else onward - index; consent = false }
    }
    Notice(creationInputText("Onward choices preserve their named recipients and complete settings. They grant no account-administration or other action permission by themselves.",
        "تحتفظ خيارات التفويض اللاحق بالمستفيدين المسمّين والإعدادات الكاملة. لا تمنح بمفردها صلاحية إدارة الحسابات أو أي إجراء آخر."))
    Consent(creationInputText("Provide an initial password at the separate first-submit confirmation", "تقديم كلمة مرور أولى عند تأكيد الإرسال الأول المنفصل"),
        withPassword, enabled) { withPassword = it; consent = false }
    Text(creationInputText("The password is entered only after saving this definition. It is not retained for retry. Creation never signs in the employee automatically.",
        "تُدخل كلمة المرور فقط بعد حفظ هذا التعريف. لا تُحفظ لإعادة المحاولة. لا يؤدي الإنشاء إلى تسجيل دخول الموظف تلقائيًا."))
    if (invalid) Notice(creationInputText("Check the login and choose one complete authentication profile.",
        "تحقق من معرّف الدخول واختر إعداد مصادقة كاملًا واحدًا."), caution = true)
    Consent(creationInputText("I reviewed every requested grant, complete profile, onward choice and listed device", "راجعت كل صلاحية مطلوبة والإعداد الكامل وخيارات التفويض اللاحق والأجهزة المدرجة"),
        consent, enabled) { consent = it }
    Button(onClick = {
        try {
            val profile = profiles[checkNotNull(profileIndex)]
            val catalogue = onward.takeIf { it.isNotEmpty() }?.sorted()?.map { profiles[it] }?.let { LocalEmployeeCreationConfigurationV1.capture(it) }
            val policy = ActivatedLocalPolicy(context.scope, context.target.actorId, Revision(0), grants,
                profile.sessionPolicy, profile.throttlePolicy, profile.approvalPolicy, catalogue)
            val definition = LocalAccountProvisioning(context.target.actorId, LocalLoginIdentifier(login), policy,
                profile.tokenPolicy, context.admittedDeviceIds)
            consent = false; invalid = false
            prepare(definition, withPassword)
        } catch (_: IllegalArgumentException) { invalid = true }
        catch (_: IllegalStateException) { invalid = true }
    }, enabled = enabled && consent && profileIndex != null, modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(creationInputText("Capture and save this exact definition for review", "التقاط هذا التعريف الدقيق وحفظه للمراجعة"))
    }
}

@Composable
private fun creationInputText(english: String, arabic: String): String =
    if (LocalAppLanguage.current == AppLanguage.ARABIC) arabic else english
