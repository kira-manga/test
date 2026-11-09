package com.ukkera.pos.app.independent.credentialentry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalClientCleanupStatus
import com.ukkera.pos.app.LocalEnrollmentMode
import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.LocalEnrollmentStatus
import com.ukkera.pos.app.LocalEnrollmentUiGateway
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SignInFailure
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.EnrollmentScreen
import com.ukkera.pos.app.ui.LocalAppLanguage
import com.ukkera.pos.app.ui.PosTheme
import com.ukkera.pos.app.ui.SignInScreen
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real entry composition and a credential-free refusing boundary; no accepted session fixture. */
abstract class CredentialEntryRenderedHarness {
    @get:Rule val ui = createComposeRule()
    private var active by mutableStateOf<Fixture?>(null)

    @Before fun composeEntryHost() {
        ui.setContent {
            active?.let { fixture ->
                CompositionLocalProvider(
                    LocalAppLanguage provides fixture.language,
                    LocalLayoutDirection provides fixture.language.direction,
                ) {
                    PosTheme {
                        Box(Modifier.fillMaxSize()) {
                            key(fixture) {
                                if (fixture.mode == EntryMode.SIGN_IN)
                                    SignInScreen(fixture.runtime, fixture.token, company = false)
                                else EnrollmentScreen(fixture.runtime, fixture.token)
                            }
                        }
                    }
                }
            }
        }
    }

    @After fun closeEntry() {
        val previous = ui.runOnIdle { active.also { active = null } }
        if (previous != null) ui.runOnIdle {
            previous.statusScope.cancel()
            previous.runtime.close()
        }
    }

    protected fun show(mode: EntryMode, language: AppLanguage, vector: String): Fixture {
        closeEntry()
        val fixture = ui.runOnIdle {
            val gateway = RefusingGateways(mode.supportsName)
            val runtime = UkkeraRuntime(AppDependencies(
                branchGateway = gateway,
                enrollmentGateway = gateway,
                platform = PlatformCapabilities(PlatformFamily.DESKTOP),
            ))
            runtime.changeLanguage(language)
            if (mode == EntryMode.SIGN_IN) runtime.openSignIn(company = false, runtime.navigator.token())
            else runtime.openEnrollment(runtime.navigator.token())
            Fixture(runtime, runtime.navigator.token(), gateway, mode, language, vector)
        }
        ui.runOnIdle { active = fixture }
        ui.runOnIdle {
            if (mode != EntryMode.SIGN_IN) {
                assertEquals(mode.supportsName, fixture.runtime.presentation.value.enrollment.status?.supportsDisplayName,
                    fixture.message)
                assertFalse(fixture.runtime.presentation.value.enrollment.busy, fixture.message)
            }
        }
        return fixture
    }

    protected fun replace(fixture: Fixture, field: Field, value: String) {
        field(fixture, field).performTextReplacement(value)
    }

    protected fun fill(fixture: Fixture, input: Input) {
        replace(fixture, Field.ACCOUNT, input.account)
        if (fixture.mode.supportsName) replace(fixture, Field.NAME, input.name)
        replace(fixture, Field.PASSWORD, input.password)
    }

    protected fun field(fixture: Fixture, field: Field): SemanticsNodeInteraction =
        ui.onNode(hasText(fixture.labels.field(field)) and hasSetTextAction())
            .performScrollTo().assertIsDisplayed().assertIsEnabled()

    protected fun button(fixture: Fixture): SemanticsNodeInteraction =
        ui.onNode(hasText(fixture.labels.submit) and hasClickAction())
            .performScrollTo().assertIsDisplayed()

    protected fun confirmation(fixture: Fixture): SemanticsNodeInteraction =
        ui.onNode(hasText(fixture.labels.confirmation) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .performScrollTo().assertIsDisplayed().assertIsEnabled()

    protected fun assertConfirmation(fixture: Fixture, checked: Boolean) {
        assertEquals(if (checked) ToggleableState.On else ToggleableState.Off,
            confirmation(fixture).fetchSemanticsNode().config[SemanticsProperties.ToggleableState], fixture.message)
    }

    protected fun confirm(fixture: Fixture) {
        val target = confirmation(fixture)
        if (target.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] != ToggleableState.On)
            target.performClick()
        assertConfirmation(fixture, checked = true)
    }

    /** InputText is the raw edit; EditableText and the layout expose its visual transformation. */
    protected fun assertRaw(fixture: Fixture, field: Field, expected: String, units: Int = expected.length) {
        val raw = assertNotNull(field(fixture, field).fetchSemanticsNode().config.getOrNull(SemanticsProperties.InputText),
            "${fixture.message}: missing raw field observation").text
        assertEquals(units, raw.length, "${fixture.message}: $field UTF-16 count")
        assertTrue(raw == expected, "${fixture.message}: $field exact edit comparison")
    }

    protected fun assertPresented(fixture: Fixture, field: Field, expected: String, units: Int = expected.length) {
        assertRaw(fixture, field, expected, units)
        val target = field(fixture, field)
        val visual = assertNotNull(target.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText),
            "${fixture.message}: missing rendered field observation").text
        if (field == Field.PASSWORD) {
            assertTrue(target.fetchSemanticsNode().config.contains(SemanticsProperties.Password), fixture.message)
            if (expected.isEmpty()) assertTrue(visual.isEmpty(), fixture.message)
            else {
                assertTrue(visual.isNotEmpty(), "${fixture.message}: mask must render")
                assertFalse(visual == expected, "${fixture.message}: plaintext must not render")
                assertFalse(visual.any { it in expected }, "${fixture.message}: no supplied password code unit may render")
            }
        } else assertTrue(visual == expected, "${fixture.message}: ordinary field presentation comparison")
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(layouts), "${fixture.message}: missing text layout result")
        }
        assertTrue(layouts.any { it.layoutInput.text.text == visual },
            "${fixture.message}: the painted layout must contain the field presentation")
    }

    protected fun assertInputRetained(fixture: Fixture, input: Input) {
        assertRaw(fixture, Field.ACCOUNT, input.account)
        if (fixture.mode.supportsName) assertRaw(fixture, Field.NAME, input.name)
        assertPresented(fixture, Field.PASSWORD, input.password)
    }

    protected fun assertBoundsError(fixture: Fixture, field: Field, expected: Boolean) {
        assertEquals(expected, field(fixture, field).fetchSemanticsNode().config.contains(SemanticsProperties.Error),
            "${fixture.message}: $field rendered invalid condition")
    }

    private fun entryLimitNotice() = SemanticsMatcher("entry-limit notice") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { value ->
            listOf("128", "256", "120").all { value.text.contains(it) }
        } == true
    }

    /** Wording is not frozen; check the stated limits and entry/no-request concepts. */
    protected fun assertInvalidNotice(fixture: Fixture) {
        val target = ui.onNode(entryLimitNotice()).performScrollTo().assertIsDisplayed()
        val text = target.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text }
        if (fixture.language == AppLanguage.ENGLISH) {
            val lower = text.lowercase()
            assertTrue(lower.contains("account") && lower.contains("password") && lower.contains("display name"), fixture.message)
            assertTrue(lower.contains("request") && lower.contains("sent") &&
                (lower.contains("no request") || lower.contains("not sent")), fixture.message)
            assertTrue(lower.contains("empty") && lower.contains("supported"), fixture.message)
            assertFalse(listOf("document", "rejected", "refused").any { lower.contains(it) }, fixture.message)
        } else {
            assertTrue(text.contains("الحساب") && text.contains("كلمة المرور") && text.contains("الاسم"), fixture.message)
            assertTrue(text.contains("طلب") && text.contains("لم") &&
                (text.contains("يُرسل") || text.contains("يرسل")), fixture.message)
            assertTrue(text.contains("فارغ") && text.contains("دعم"), fixture.message)
            assertFalse(listOf("مستند", "وثيقة", "رفض").any { text.contains(it) }, fixture.message)
        }
    }

    protected fun assertNoInvalidNotice() = ui.onAllNodes(entryLimitNotice()).assertCountEquals(0)

    protected fun attempt(fixture: Fixture, action: SubmitAction) {
        if (action == SubmitAction.BUTTON) button(fixture).performTouchInput { click(center) }
        else field(fixture, Field.PASSWORD).also {
            assertEquals(ImeAction.Done, it.fetchSemanticsNode().config[SemanticsProperties.ImeAction], fixture.message)
        }.performImeAction()
    }

    protected fun assertNoSubmission(fixture: Fixture) = ui.runOnIdle {
        assertEquals(0, fixture.gateway.signIns, fixture.message)
        assertEquals(0, fixture.gateway.enrollments, fixture.message)
        assertEquals(0, fixture.gateway.submittedIdentities.size, fixture.message)
        assertEquals(0, fixture.gateway.lookups, fixture.message)
        assertNull(fixture.runtime.presentation.value.enrollment.requestId, fixture.message)
        assertEntryHasNoSession(fixture)
    }

    protected fun expectRefusal(fixture: Fixture, input: Input, explicitDeviceMove: Boolean = true) = ui.runOnIdle {
        fixture.gateway.expected = input
        fixture.gateway.expectedMove = explicitDeviceMove
    }

    protected fun assertOneRefusal(fixture: Fixture) = ui.runOnIdle {
        assertEquals(if (fixture.mode == EntryMode.SIGN_IN) 1 else 0, fixture.gateway.signIns, fixture.message)
        assertEquals(if (fixture.mode == EntryMode.SIGN_IN) 0 else 1, fixture.gateway.enrollments, fixture.message)
        assertEquals(if (fixture.mode == EntryMode.SIGN_IN) 0 else 1, fixture.gateway.submittedIdentities.size, fixture.message)
        assertEquals(0, fixture.gateway.lookups, fixture.message)
        assertTrue(assertNotNull(fixture.gateway.comparison, fixture.message).all { it },
            "${fixture.message}: gateway exact-value comparisons")
        if (fixture.mode == EntryMode.SIGN_IN)
            assertEquals(SignInFailure.DENIED, fixture.runtime.presentation.value.signInFailure, fixture.message)
        else assertEquals(LocalEnrollmentResult.Rejected(LocalServiceProblem.DENIED),
            fixture.runtime.presentation.value.enrollment.result, fixture.message)
        assertFalse(fixture.runtime.presentation.value.signingIn, fixture.message)
        assertFalse(fixture.runtime.presentation.value.enrollment.busy, fixture.message)
        assertEntryHasNoSession(fixture)
    }

    private fun assertEntryHasNoSession(fixture: Fixture) {
        assertNull(fixture.runtime.presentation.value.display, fixture.message)
        assertNull(fixture.runtime.presentation.value.localAttachment, fixture.message)
        val navigator = fixture.runtime.navigator
        assertTrue(navigator.isCurrent(fixture.token), "${fixture.message}: original entry token remains current")
        val current = navigator.token()
        assertEquals(fixture.token.rootGeneration, current.rootGeneration, fixture.message)
        assertEquals(fixture.token.area, current.area, fixture.message)
        assertEquals(fixture.token.entryId, current.entryId, fixture.message)
        assertEquals(fixture.token.revision, current.revision, fixture.message)
        assertEquals(if (fixture.mode == EntryMode.SIGN_IN) SecurityRoot.BRANCH_SIGN_IN else SecurityRoot.LOCAL_ENROLLMENT,
            fixture.runtime.navigator.state.value.root, fixture.message)
        assertNull(fixture.runtime.navigator.checkpoint(), fixture.message)
    }

    protected fun reloadNameSupport(fixture: Fixture, supported: Boolean) {
        val job = ui.runOnIdle {
            fixture.gateway.supportsName = supported
            fixture.statusScope.launch { fixture.runtime.loadEnrollmentStatus(fixture.token) }
        }
        ui.runOnIdle {
            assertTrue(job.isCompleted, "${fixture.message}: bounded immediate status reply")
            assertEquals(supported, fixture.runtime.presentation.value.enrollment.status?.supportsDisplayName, fixture.message)
            assertFalse(fixture.runtime.presentation.value.enrollment.busy, fixture.message)
        }
    }

    protected fun contexts(modes: List<EntryMode>, action: (EntryMode, AppLanguage) -> Unit) {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC))
            for (mode in modes) action(mode, language)
    }

    protected enum class EntryMode(val supportsName: Boolean) {
        SIGN_IN(false), ENROLLMENT_SUPPORTED(true), ENROLLMENT_UNSUPPORTED(false);
        val fields: List<Field> get() = if (supportsName) listOf(Field.ACCOUNT, Field.PASSWORD, Field.NAME)
            else listOf(Field.ACCOUNT, Field.PASSWORD)
    }
    protected enum class Field { ACCOUNT, PASSWORD, NAME }
    protected enum class SubmitAction { BUTTON, IME }
    protected data class Input(val account: String, val password: String, val name: String = "") {
        fun value(field: Field): String = when (field) { Field.ACCOUNT -> account; Field.PASSWORD -> password; Field.NAME -> name }
        fun change(field: Field, value: String): Input = when (field) {
            Field.ACCOUNT -> copy(account = value); Field.PASSWORD -> copy(password = value); Field.NAME -> copy(name = value)
        }
    }
    protected fun minimum(mode: EntryMode) = Input("a", "p", if (mode.supportsName) "n" else "")
    protected fun upperBoundary(mode: EntryMode) = Input("a".repeat(128), "p".repeat(256), if (mode.supportsName) "n".repeat(120) else "")
    protected fun whitespace(mode: EntryMode) = Input(" cedar.fx ", " ", if (mode.supportsName) " Cedar " else "")
    protected fun decomposed(mode: EntryMode) = Input("e\u0301", "e\u0301", if (mode.supportsName) "e\u0301" else "")
    protected fun rockets(count: Int) = "\uD83D\uDE80".repeat(count)

    protected class Fixture(val runtime: UkkeraRuntime, val token: NavigationToken, val gateway: RefusingGateways,
        val mode: EntryMode, val language: AppLanguage, vector: String) {
        val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val labels = Labels(mode, language)
        val message = "$vector / $mode / $language"
    }

    /** Values are compared during the call. Only comparison outcomes and request IDs are retained. */
    protected class RefusingGateways(var supportsName: Boolean) : BranchUiGateway, LocalEnrollmentUiGateway {
        override val configured = true
        var signIns = 0
        var enrollments = 0
        var lookups = 0
        val submittedIdentities = mutableListOf<DomainId>()
        var expected: Input? = null
        var expectedMove = false
        var comparison: List<Boolean>? = null
        override suspend fun cleanupStatus() = LocalClientCleanupStatus(available = true)
        override suspend fun status() = LocalEnrollmentStatus(LocalEnrollmentMode.LOCAL_QUALIFICATION_ONLY,
            available = true, supportsDisplayName = supportsName)
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            signIn(account, secret, explicitDeviceMove = false)
        override suspend fun signIn(account: String, secret: CharArray, explicitDeviceMove: Boolean): SignInResult<BranchUiSession> {
            signIns++
            comparison = listOf(expected?.account == account, passwordMatches(secret), explicitDeviceMove == expectedMove)
            return SignInResult.Rejected(SignInFailure.DENIED)
        }
        override suspend fun enroll(requestId: DomainId, account: String, displayName: String, password: CharArray): LocalEnrollmentResult {
            enrollments++
            submittedIdentities += requestId
            comparison = listOf(expected?.account == account, passwordMatches(password), expected?.name == displayName)
            return LocalEnrollmentResult.Rejected(LocalServiceProblem.DENIED)
        }
        override suspend fun lookup(requestId: DomainId): LocalEnrollmentResult {
            lookups++
            return LocalEnrollmentResult.Rejected(LocalServiceProblem.DENIED)
        }
        private fun passwordMatches(password: CharArray): Boolean {
            val value = expected?.password ?: return false
            return password.size == value.length && password.indices.all { password[it] == value[it] }
        }
    }

    /** Current strings locate controls; they are not frozen message-wording expectations. */
    protected class Labels(private val mode: EntryMode, private val language: AppLanguage) {
        private fun text(english: String, arabic: String) = if (language == AppLanguage.ENGLISH) english else arabic
        fun field(field: Field) = when (field) {
            Field.ACCOUNT -> text("Account", "الحساب")
            Field.PASSWORD -> text("Password", "كلمة المرور")
            Field.NAME -> text("Display name", "الاسم المعروض")
        }
        val submit get() = if (mode == EntryMode.SIGN_IN) text("Sign in", "تسجيل الدخول")
            else text("Create the authorized local account", "إنشاء الحساب المحلي المصرح به")
        val confirmation get() = if (mode == EntryMode.SIGN_IN)
            text("Replace this account’s other sessions with a session on this device. This does not discard pending business operations.",
                "استبدال الجلسات الأخرى لهذا الحساب بجلسة على هذا الجهاز. لا يؤدي ذلك إلى حذف العمليات التجارية المعلّقة.")
            else text("I understand the stated provisioning scope and explicitly request this local account.",
                "أفهم نطاق التهيئة الموضح وأطلب إنشاء هذا الحساب المحلي صراحة.")
    }
}
