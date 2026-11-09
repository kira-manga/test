package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.SignInFailure
import com.ukkera.pos.app.LOCAL_CREDENTIAL_ACCOUNT_MAX_LENGTH
import com.ukkera.pos.app.LOCAL_CREDENTIAL_DISPLAY_NAME_MAX_LENGTH
import com.ukkera.pos.app.LOCAL_CREDENTIAL_SECRET_MAX_LENGTH
import com.ukkera.pos.app.LocalEnrollmentMode
import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.localEnrollmentInputSupported
import com.ukkera.pos.app.localSignInInputSupported
import com.ukkera.pos.app.navigation.NavigationToken
import kotlinx.coroutines.launch

@Composable
internal fun WelcomeScreen(runtime: UkkeraRuntime, token: NavigationToken, onLocalSetupRequest: (() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth.value / LocalDensity.current.fontScale >= 1000
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 40.dp else 20.dp), contentAlignment = Alignment.TopCenter) {
            if (wide) {
                Row(Modifier.widthIn(max = 1240.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    WelcomeHero(Modifier.weight(1f))
                    WorkspaceChoices(runtime, token, Modifier.weight(1f), onLocalSetupRequest)
                }
            } else {
                Column(Modifier.widthIn(max = 680.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    WelcomeHero(Modifier.fillMaxWidth())
                    WorkspaceChoices(runtime, token, Modifier.fillMaxWidth(), onLocalSetupRequest)
                }
            }
        }
    }
}

@Composable
private fun WelcomeHero(modifier: Modifier) {
    Surface(modifier, color = Forest, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            PosIcon(Glyph.BAG, tint = Color(0xFFE5F0C7))
            Text(copy(Copy.WELCOME), style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(copy(Copy.WELCOME_BODY), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD6E9DC))
            HorizontalDivider(color = Color(0xFF567268))
            Text(copy(Copy.FEATURE_READINESS), style = MaterialTheme.typography.labelLarge, color = Color(0xFFE5F0C7))
            HeroReadiness(Copy.NATIVE_SHELL_READY, Copy.INTERACTIVE_TRAINING, complete = true)
            HeroReadiness(Copy.GATEWAY_NEEDED, Copy.INTEGRATION_REQUIRED)
            HeroReadiness(Copy.PHYSICAL_NEEDED, Copy.NOT_VERIFIED)
        }
    }
}

@Composable
private fun HeroReadiness(title: Copy, state: Copy, complete: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PosIcon(if (complete) Glyph.CHECK else Glyph.INFO, tint = Color(0xFFE5F0C7))
        Column {
            Text(copy(title), style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text(copy(state), style = MaterialTheme.typography.bodySmall, color = Color(0xFFC5DBCF))
        }
    }
}

@Composable
private fun WorkspaceChoices(runtime: UkkeraRuntime, token: NavigationToken, modifier: Modifier, onLocalSetupRequest: (() -> Unit)?) {
    val presentation by runtime.presentation.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeading(copy(Copy.CHOOSE_WORKSPACE))
        Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(copy(Copy.BRANCH_SIGN_IN), style = MaterialTheme.typography.titleLarge)
                Text(copy(Copy.BRANCH_AUTH_HELP), style = MaterialTheme.typography.bodyMedium, color = Muted)
                Button(onClick = { runtime.openSignIn(company = false, token) }, modifier = Modifier.fillMaxWidth().touchTarget(), shape = RoundedCornerShape(12.dp)) {
                    PosIcon(Glyph.PERSON, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(copy(Copy.SIGN_IN))
                }
                Notice(if (LocalAppLanguage.current == AppLanguage.ARABIC)
                    "أنشئ الموظف أو استعد طلبه الأصلي في إعداد الجهاز: التعريف الكامل واعتماد رئيس الشركة وسجل العمل الخاص قبل تسجيل الدخول."
                    else "Create an employee or recover the original enrollment in device setup: complete definition, company-head approval and a private work journal before sign-in.")
                onLocalSetupRequest?.let { setup ->
                    OutlinedButton(onClick = { if (!runtime.closed && runtime.navigator.isCurrent(token)) setup() },
                        modifier = Modifier.fillMaxWidth().touchTarget(), shape = RoundedCornerShape(12.dp)) {
                        Text(if (LocalAppLanguage.current == AppLanguage.ARABIC) "إنشاء / استعادة موظف في إعداد الجهاز"
                            else "Create / recover employee in device setup")
                    }
                }
            }
        }
        if (runtime.dependencies.centralServicesEnabled) {
            OutlinedButton(onClick = { runtime.openSignIn(company = true, token) }, modifier = Modifier.fillMaxWidth().touchTarget(), shape = RoundedCornerShape(12.dp)) {
                Text(copy(Copy.COMPANY_SIGN_IN))
            }
            Text(copy(Copy.COMPANY_AUTH_HELP), style = MaterialTheme.typography.bodySmall, color = Muted)
        } else Notice(copy(Copy.CENTRAL_HELD))
        Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StatusPill(copy(Copy.TRAINING), caution = true)
                Text(copy(Copy.TRAINING_HELP), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { runtime.openTraining(company = false, token) }, modifier = Modifier.fillMaxWidth().touchTarget(), shape = RoundedCornerShape(12.dp)) {
                    PosIcon(Glyph.BAG, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(copy(Copy.START_TRAINING))
                }
                OutlinedButton(onClick = { runtime.openTraining(company = true, token) }, modifier = Modifier.fillMaxWidth().touchTarget(), shape = RoundedCornerShape(12.dp)) {
                    Text(copy(Copy.TRAINING_COMPANY))
                }
            }
        }
        Notice(copy(Copy.BOOTSTRAP_NOTICE))
        if (presentation.locallyLocked) Notice(copy(Copy.LOCK_NOTICE))
        if (presentation.revocationPending) Notice(copy(Copy.REVOCATION_PENDING), caution = true)
        presentation.sessionProblem?.let { Notice(localProblem(it), caution = true) }
        LockedClientCleanup(runtime, token)
    }
}

@Composable
internal fun SignInScreen(runtime: UkkeraRuntime, token: NavigationToken, company: Boolean) {
    val presentation by runtime.presentation.collectAsState()
    val configured = if (company) runtime.dependencies.centralServicesEnabled && runtime.dependencies.centralGateway.configured else runtime.dependencies.branchGateway.configured
    val secretMaxLength = if (company) LOCAL_CREDENTIAL_SECRET_MAX_LENGTH else runtime.dependencies.branchGateway.signInSecretMaxLength
    var account by remember { mutableStateOf("") }
    // Deliberately not rememberSaveable, not in a route or persistent ViewModel/SavedStateHandle.
    var secret by remember { mutableStateOf("") }
    var explicitDeviceMove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    DisposableEffect(Unit) { onDispose { secret = ""; account = ""; explicitDeviceMove = false } }
    fun submit() {
        val currentSecretMaxLength = if (company) LOCAL_CREDENTIAL_SECRET_MAX_LENGTH else runtime.dependencies.branchGateway.signInSecretMaxLength
        if (!configured || presentation.signingIn || !localSignInInputSupported(account, secret.length, currentSecretMaxLength)) return
        val input = secret.toCharArray()
        val replaceSessions = explicitDeviceMove
        secret = ""
        explicitDeviceMove = false
        // Local identifiers are exact/case-sensitive. Never trim or normalize another account.
        runtime.launchSignIn(scope, account, input, token, explicitDeviceMove = !company && replaceSessions)
    }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                PosIcon(if (company) Glyph.SETTINGS else Glyph.PERSON)
                SectionHeading(copy(if (company) Copy.COMPANY_SIGN_IN else Copy.BRANCH_SIGN_IN), copy(if (company) Copy.COMPANY_AUTH_HELP else Copy.BRANCH_AUTH_HELP))
                if (!configured) Notice(copy(Copy.AUTH_UNAVAILABLE), caution = true)
                OutlinedTextField(value = account, onValueChange = { if (it != account) { account = it; explicitDeviceMove = false } }, label = { Text(copy(Copy.ACCOUNT)) },
                    isError = account.length > LOCAL_CREDENTIAL_ACCOUNT_MAX_LENGTH,
                    enabled = configured && !presentation.signingIn, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
                OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text(copy(Copy.PASSWORD)) },
                    isError = secret.length > secretMaxLength,
                    enabled = configured && !presentation.signingIn, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.fillMaxWidth())
                if (account.length > LOCAL_CREDENTIAL_ACCOUNT_MAX_LENGTH || secret.length > secretMaxLength)
                    Notice(copy(if (secretMaxLength == 1024) Copy.REMOTE_SIGN_IN_ENTRY_INVALID else Copy.CREDENTIAL_ENTRY_INVALID), caution = true)
                presentation.signInFailure?.let { failure ->
                    Notice(copy(when (failure) {
                        SignInFailure.DENIED -> Copy.AUTH_DENIED
                        SignInFailure.RETRY_LATER -> Copy.LOCAL_RETRY_LATER
                        SignInFailure.NOT_CONFIGURED -> Copy.AUTH_UNAVAILABLE
                        SignInFailure.UNAVAILABLE -> Copy.AUTH_NETWORK
                        SignInFailure.DEVICE_MOVE_REQUIRED -> Copy.DEVICE_MOVE_REQUIRED
                        SignInFailure.SESSION_LIMIT -> Copy.SESSION_LIMIT
                        SignInFailure.AUTHENTICATION_UNRESOLVED -> Copy.AUTHENTICATION_UNRESOLVED
                        SignInFailure.STORAGE_UNCERTAIN -> Copy.LOCAL_STORAGE_UNCERTAIN
                    }), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, caution = true)
                }
                if (!company) ConfirmRow(copy(Copy.EXPLICIT_DEVICE_MOVE), explicitDeviceMove, configured && !presentation.signingIn) {
                    explicitDeviceMove = it
                }
                Button(onClick = ::submit, enabled = configured && !presentation.signingIn && localSignInInputSupported(account, secret.length, secretMaxLength),
                    modifier = Modifier.fillMaxWidth().touchTarget(), shape = RoundedCornerShape(12.dp)) {
                    Text(copy(if (presentation.signingIn) Copy.SIGNING_IN else Copy.SIGN_IN))
                }
                if (presentation.signingIn) CircularProgressIndicator()
                if (!company) LockedClientCleanup(runtime, token)
            }
        }
    }
}

@Composable
internal fun EnrollmentScreen(runtime: UkkeraRuntime, token: NavigationToken) {
    val presentation by runtime.presentation.collectAsState()
    val state = presentation.enrollment
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var account by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var approved by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { secret = ""; account = ""; displayName = ""; approved = false } }
    LaunchedEffect(runtime, token.rootGeneration, token.entryId) { runtime.loadEnrollmentStatus(token) }
    val status = state.status
    val available = status?.available == true && status.mode != LocalEnrollmentMode.DISABLED
    val editable = available && !state.busy && state.requestId == null && state.result != LocalEnrollmentResult.Created
    val supportsDisplayName = status?.supportsDisplayName == true
    fun submit() {
        if (!editable || !approved || !localEnrollmentInputSupported(account, displayName, secret.length, supportsDisplayName)) return
        val password = secret.toCharArray()
        secret = ""
        runtime.launchEnrollment(scope, account, displayName, password, approved, token)
    }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SectionHeading(copy(Copy.ENROLLMENT_TITLE), copy(Copy.ENROLLMENT_HELP))
                if (state.busy) {
                    CircularProgressIndicator()
                    Text(copy(Copy.ENROLLMENT_BUSY), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (state.statusUnavailable) {
                    Notice(copy(Copy.AUTH_NETWORK), caution = true)
                    OutlinedButton(onClick = { scope.launch { runtime.loadEnrollmentStatus(token) } }, enabled = !state.busy,
                        modifier = Modifier.touchTarget()) { Text(copy(Copy.REFRESH)) }
                }
                if (status != null) {
                    Notice(copy(when (status.mode) {
                        LocalEnrollmentMode.DISABLED -> Copy.ENROLLMENT_DISABLED
                        LocalEnrollmentMode.LOCAL_QUALIFICATION_ONLY -> Copy.ENROLLMENT_QUALIFICATION
                        LocalEnrollmentMode.OWNER_AUTHORIZED_LOCAL -> Copy.ENROLLMENT_OWNER_AUTHORIZED
                    }), caution = true)
                    status.context?.let { Text(language.text(it), style = MaterialTheme.typography.bodyMedium) }
                    if (!available && status.mode != LocalEnrollmentMode.DISABLED) Notice(copy(Copy.ENROLLMENT_DISABLED), caution = true)
                }
                state.requestId?.let { IdentifierText(copy(Copy.ENROLLMENT_REQUEST_ID), it.value) }
                when (val result = state.result) {
                    LocalEnrollmentResult.Created -> {
                        Notice(copy(Copy.ENROLLMENT_CREATED), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Button(onClick = { runtime.openSignIn(company = false, token) }, modifier = Modifier.fillMaxWidth().touchTarget()) {
                            Text(copy(Copy.BRANCH_SIGN_IN))
                        }
                    }
                    LocalEnrollmentResult.Unresolved -> {
                        Notice(copy(Copy.ENROLLMENT_UNKNOWN), caution = true, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                        Button(onClick = { scope.launch { runtime.recoverEnrollment(token) } }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(copy(Copy.ENROLLMENT_RECOVER)) }
                    }
                    is LocalEnrollmentResult.Rejected -> Notice(localProblem(result.problem), caution = true)
                    null -> Unit
                }
                if (available && state.requestId == null && state.result != LocalEnrollmentResult.Created) {
                    OutlinedTextField(value = account, onValueChange = { if (it != account) { account = it; approved = false } },
                        isError = account.length > LOCAL_CREDENTIAL_ACCOUNT_MAX_LENGTH,
                        enabled = editable, label = { Text(copy(Copy.ACCOUNT)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
                    // A capability change must not hide a nonempty draft that the user must clear.
                    if (supportsDisplayName || displayName.isNotEmpty()) OutlinedTextField(value = displayName,
                        onValueChange = { if (it != displayName) { displayName = it; approved = false } },
                        isError = displayName.length > LOCAL_CREDENTIAL_DISPLAY_NAME_MAX_LENGTH || !supportsDisplayName && displayName.isNotEmpty(),
                        enabled = editable, label = { Text(copy(Copy.DISPLAY_NAME)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
                    if (!supportsDisplayName) Notice(copy(Copy.ENROLLMENT_IDENTIFIER_ONLY))
                    OutlinedTextField(value = secret, onValueChange = { if (it != secret) { secret = it; approved = false } },
                        isError = secret.length > LOCAL_CREDENTIAL_SECRET_MAX_LENGTH,
                        enabled = editable, label = { Text(copy(Copy.PASSWORD)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }))
                    if (account.length > LOCAL_CREDENTIAL_ACCOUNT_MAX_LENGTH || secret.length > LOCAL_CREDENTIAL_SECRET_MAX_LENGTH ||
                        displayName.length > LOCAL_CREDENTIAL_DISPLAY_NAME_MAX_LENGTH || !supportsDisplayName && displayName.isNotEmpty())
                        Notice(copy(Copy.CREDENTIAL_ENTRY_INVALID), caution = true)
                    ConfirmRow(copy(Copy.ENROLLMENT_CONSENT), approved, editable) { approved = it }
                    Button(onClick = ::submit, enabled = editable && approved &&
                        localEnrollmentInputSupported(account, displayName, secret.length, supportsDisplayName),
                        modifier = Modifier.fillMaxWidth().touchTarget(), shape = RoundedCornerShape(12.dp)) { Text(copy(Copy.ENROLLMENT_CREATE)) }
                }
            }
        }
    }
}

/** Locked-screen data is limited to availability/counts, never previous actors or credentials. */
@Composable
private fun LockedClientCleanup(runtime: UkkeraRuntime, token: NavigationToken) {
    val presentation by runtime.presentation.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(runtime, token.rootGeneration, token.entryId) { runtime.loadCleanupStatus(token) }
    if (!runtime.dependencies.branchGateway.configured) return
    if (presentation.cleanupNeedsAttention) Notice(copy(Copy.CLEANUP_ATTENTION), caution = true)
    val status = presentation.cleanupStatus
    if (status?.authenticationUnresolved == true) Notice(copy(Copy.AUTH_CLEANUP_UNRESOLVED), caution = true)
    if (status?.clear == true) Text(copy(Copy.SESSION_CLEANUP_CLEAR), style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    else OutlinedButton(onClick = { scope.launch { runtime.loadCleanupStatus(token, retry = true) } },
        enabled = !presentation.checkingCleanup && !presentation.signingIn, modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(copy(Copy.RETRY_SESSION_CLEANUP))
    }
    if (presentation.checkingCleanup) CircularProgressIndicator()
}
