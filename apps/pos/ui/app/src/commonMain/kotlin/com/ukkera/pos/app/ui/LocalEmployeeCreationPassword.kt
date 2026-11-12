package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.LocalAdministrationController
import com.ukkera.pos.app.LocalAdministrationWorkspace
import com.ukkera.pos.app.navigation.NavigationToken

@Composable
internal fun LocalEmployeeCreationPassword(controller: LocalAdministrationController,
    state: LocalAdministrationWorkspace, token: NavigationToken) {
    if (!state.creation.passwordEntryOpen || state.working || !state.creation.canSubmit) return
    val selected = state.creation.displayed ?: return
    val original = selected.original
    val requested = remember(original) { original.copyRequestedDefinition() }
    if (!requested.credentialWasProvided) return
    // Transient Compose/IME strings cannot be guaranteed physically erased. No saved state,
    // journal or controller stores them; every transferred mutable buffer is wiped.
    var password by remember(original) { mutableStateOf("") }
    var confirmation by remember(original) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val first = remember(original) { FocusRequester() }
    val valid = password.length in 4..1024 && password == confirmation
    fun clear() { password = ""; confirmation = "" }
    fun confirm() {
        if (!valid) return
        val input = password.toCharArray()
        clear()
        try { controller.submitCreation(token, input) } finally { input.fill('\u0000') }
    }
    DisposableEffect(original) { onDispose { clear() } }
    LaunchedEffect(original) { first.requestFocus() }
    AlertDialog(onDismissRequest = { clear(); controller.closeCreationPasswordEntry(token) },
        title = { Text(passwordText("Confirm first employee submission", "تأكيد الإرسال الأول لإنشاء الموظف")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReviewField(passwordText("Exact employee login", "معرّف دخول الموظف الدقيق"), requested.definition.login.value)
                ReviewField(passwordText("Original request", "الطلب الأصلي"), original.requestId.value)
                Text(passwordText("The password is not saved for retry. Once dispatch is attempted, use original-result recovery. Creation does not sign in the employee.",
                    "لا تُحفظ كلمة المرور لإعادة المحاولة. بعد محاولة الإرسال، استخدم استعادة نتيجة الطلب الأصلي. لا يؤدي الإنشاء إلى تسجيل دخول الموظف."))
                OutlinedTextField(password, { if (it.length <= 1024) password = it }, singleLine = true,
                    label = { Text(passwordText("Initial password (4–1024 characters)", "كلمة المرور الأولى (4–1024 محرفًا)")) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().focusRequester(first),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
                OutlinedTextField(confirmation, { if (it.length <= 1024) confirmation = it }, singleLine = true,
                    label = { Text(passwordText("Repeat initial password", "تكرار كلمة المرور الأولى")) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }))
                if (confirmation.isNotEmpty() && password != confirmation) Notice(passwordText("Passwords do not match.", "كلمتا المرور غير متطابقتين."), caution = true)
            }
        }, confirmButton = { Button(onClick = { confirm() }, enabled = valid, modifier = Modifier.touchTarget()) {
            Text(passwordText("Confirm this original once", "تأكيد هذا الطلب الأصلي مرة واحدة"))
        } }, dismissButton = { TextButton(onClick = { clear(); controller.closeCreationPasswordEntry(token) }, modifier = Modifier.touchTarget()) {
            Text(passwordText("Close entry; keep the prepared request", "إغلاق الإدخال؛ الاحتفاظ بالطلب المحضّر"))
        } })
}

@Composable
private fun passwordText(english: String, arabic: String): String =
    if (LocalAppLanguage.current == AppLanguage.ARABIC) arabic else english
