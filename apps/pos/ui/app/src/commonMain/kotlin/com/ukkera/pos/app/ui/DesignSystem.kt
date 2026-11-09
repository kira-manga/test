package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ukkera.pos.app.navigation.AppArea

internal val Forest = Color(0xFF143E34)
internal val Mint = Color(0xFFE7F2EA)
internal val Muted = Color(0xFF52645C)
internal val Outline = Color(0xFFD9E2DA)
internal val Paper = Color(0xFFF5F7F3)
internal val AmberSurface = Color(0xFFFFF1D2)
internal val AmberText = Color(0xFF715000)

@Composable
internal fun PosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF176B50), onPrimary = Color.White,
            primaryContainer = Mint, onPrimaryContainer = Forest,
            secondary = Color(0xFF50664E), onSecondary = Color.White,
            background = Paper, onBackground = Color(0xFF192D24),
            surface = Color.White, onSurface = Color(0xFF192D24),
            surfaceVariant = Mint, onSurfaceVariant = Muted,
            outline = Color(0xFF78867E), outlineVariant = Outline,
            error = Color(0xFF9E2723),
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 43.sp, fontWeight = FontWeight.SemiBold),
            headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        ),
        shapes = MaterialTheme.shapes.copy(medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)),
        content = content,
    )
}

@Composable
internal fun SectionHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
internal fun StatusPill(text: String, caution: Boolean = false) {
    Surface(color = if (caution) AmberSurface else Mint, shape = RoundedCornerShape(8.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium, color = if (caution) AmberText else Forest)
    }
}

@Composable
internal fun Notice(text: String, modifier: Modifier = Modifier, caution: Boolean = false) {
    Surface(modifier.fillMaxWidth(), color = if (caution) AmberSurface else Mint,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (caution) Color(0xFFE4D3AB) else Outline)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PosIcon(Glyph.INFO, tint = if (caution) AmberText else Forest)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (caution) AmberText else Forest)
        }
    }
}

@Composable
internal fun Modifier.touchTarget(): Modifier = sizeIn(minWidth = 48.dp, minHeight = 48.dp).visibleKeyboardFocus()

/** Two-tone focus ring remains visible on both the white workspace and dark navigation rail. */
@Composable
internal fun Modifier.visibleKeyboardFocus(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }.drawWithContent {
        drawContent()
        if (focused) {
            val inset = 3.dp.toPx()
            val radius = CornerRadius(11.dp.toPx())
            val bounds = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f))
            drawRoundRect(Color.White, Offset(inset, inset), bounds, radius, style = Stroke(5.dp.toPx()))
            drawRoundRect(Forest, Offset(inset, inset), bounds, radius, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
internal fun areaTitle(area: AppArea): String = copy(when (area) {
    AppArea.START -> Copy.CHOOSE_WORKSPACE
    AppArea.SIGN_IN -> Copy.SIGN_IN
    AppArea.SESSION -> Copy.SESSION_ACCESS
    AppArea.CASHIER -> Copy.CASHIER
    AppArea.ORDERS -> Copy.ORDERS
    AppArea.DRAWER -> Copy.DRAWER
    AppArea.STOCK -> Copy.STOCK
    AppArea.REPORTS -> Copy.REPORTS
    AppArea.SETUP -> Copy.SETUP
    AppArea.CATALOG -> Copy.CATALOG
    AppArea.PEOPLE -> Copy.PEOPLE
    AppArea.PURCHASING -> Copy.PURCHASING
    AppArea.DEVICES -> Copy.DEVICES
    AppArea.COMPANY_SETTINGS -> Copy.COMPANY_SETTINGS
})

internal fun areaGlyph(area: AppArea): Glyph = when (area) {
    AppArea.CASHIER -> Glyph.BAG
    AppArea.ORDERS -> Glyph.ORDER
    AppArea.DRAWER -> Glyph.WALLET
    AppArea.STOCK, AppArea.CATALOG -> Glyph.BOX
    AppArea.REPORTS -> Glyph.CHART
    AppArea.PEOPLE, AppArea.SIGN_IN -> Glyph.PERSON
    AppArea.SESSION -> Glyph.LOCK
    AppArea.PURCHASING -> Glyph.BAG
    else -> Glyph.SETTINGS
}

internal enum class Glyph { BAG, ORDER, WALLET, BOX, CHART, SETTINGS, PERSON, SEARCH, PLUS, MINUS, BACK, EXIT, INFO, CHECK, MENU, LOCK, REFRESH }

/** Small code-native icon set; no font glyph tricks, bitmap downloads or image-cache identity. */
private val vectors: Map<Glyph, ImageVector> = Glyph.entries.associateWith { glyph ->
    ImageVector.Builder(glyph.name, 24.dp, 24.dp, 24f, 24f, autoMirror = glyph == Glyph.BACK || glyph == Glyph.EXIT).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (glyph) {
                Glyph.BAG -> { moveTo(4f,7f); lineTo(20f,7f); lineTo(19f,21f); lineTo(5f,21f); close(); moveTo(8f,8f); lineTo(8f,6f); curveTo(8f,1f,16f,1f,16f,6f); lineTo(16f,8f) }
                Glyph.ORDER -> { moveTo(6f,3f); lineTo(18f,3f); lineTo(18f,21f); lineTo(6f,21f); close(); moveTo(9f,8f); lineTo(15f,8f); moveTo(9f,12f); lineTo(15f,12f); moveTo(9f,16f); lineTo(13f,16f) }
                Glyph.WALLET -> { moveTo(20f,7f); lineTo(4f,7f); lineTo(4f,20f); lineTo(20f,20f); close(); moveTo(4f,7f); lineTo(4f,4f); lineTo(17f,4f); lineTo(17f,7f); moveTo(20f,11f); lineTo(15f,11f); lineTo(15f,16f); lineTo(20f,16f); moveTo(17f,13.5f); lineTo(17.1f,13.5f) }
                Glyph.BOX -> { moveTo(3f,7f); lineTo(12f,3f); lineTo(21f,7f); lineTo(21f,17f); lineTo(12f,22f); lineTo(3f,17f); close(); moveTo(3f,7f); lineTo(12f,12f); lineTo(21f,7f); moveTo(12f,12f); lineTo(12f,22f); moveTo(8f,5f); lineTo(17f,10f) }
                Glyph.CHART -> { moveTo(4f,3f); lineTo(4f,20f); lineTo(21f,20f); moveTo(8f,16f); lineTo(8f,12f); moveTo(13f,16f); lineTo(13f,6f); moveTo(18f,16f); lineTo(18f,9f) }
                Glyph.SETTINGS -> { moveTo(5f,3f); lineTo(5f,8f); moveTo(5f,12f); lineTo(5f,21f); moveTo(12f,3f); lineTo(12f,13f); moveTo(12f,17f); lineTo(12f,21f); moveTo(19f,3f); lineTo(19f,6f); moveTo(19f,10f); lineTo(19f,21f); moveTo(2f,10f); lineTo(8f,10f); moveTo(9f,15f); lineTo(15f,15f); moveTo(16f,8f); lineTo(22f,8f) }
                Glyph.PERSON -> { circle(12f,7f,4f); moveTo(4f,21f); curveTo(4f,11f,20f,11f,20f,21f) }
                Glyph.SEARCH -> { circle(10f,10f,6f); moveTo(14.5f,14.5f); lineTo(21f,21f) }
                Glyph.PLUS -> { moveTo(12f,5f); lineTo(12f,19f); moveTo(5f,12f); lineTo(19f,12f) }
                Glyph.MINUS -> { moveTo(5f,12f); lineTo(19f,12f) }
                Glyph.BACK -> { moveTo(14f,5f); lineTo(7f,12f); lineTo(14f,19f); moveTo(7f,12f); lineTo(21f,12f) }
                Glyph.EXIT -> { moveTo(10f,4f); lineTo(4f,4f); lineTo(4f,20f); lineTo(10f,20f); moveTo(10f,12f); lineTo(22f,12f); moveTo(17f,7f); lineTo(22f,12f); lineTo(17f,17f) }
                Glyph.INFO -> { circle(12f,12f,9f); moveTo(12f,11f); lineTo(12f,17f); moveTo(12f,7f); lineTo(12f,7.1f) }
                Glyph.CHECK -> { moveTo(4f,12f); lineTo(9f,17f); lineTo(20f,6f) }
                Glyph.MENU -> { moveTo(4f,6f); lineTo(20f,6f); moveTo(4f,12f); lineTo(20f,12f); moveTo(4f,18f); lineTo(20f,18f) }
                Glyph.LOCK -> { moveTo(6f,10f); lineTo(18f,10f); lineTo(18f,21f); lineTo(6f,21f); close(); moveTo(8f,10f); lineTo(8f,6f); curveTo(8f,1f,16f,1f,16f,6f); lineTo(16f,10f); moveTo(12f,14f); lineTo(12f,17f) }
                Glyph.REFRESH -> { moveTo(20f,9f); curveTo(18f,2f,7f,1f,4f,9f); moveTo(20f,4f); lineTo(20f,9f); lineTo(15f,9f); moveTo(4f,15f); curveTo(6f,22f,17f,23f,20f,15f); moveTo(4f,20f); lineTo(4f,15f); lineTo(9f,15f) }
            }
        }
    }.build()
}

private fun PathBuilder.circle(x: Float, y: Float, radius: Float) {
    moveTo(x + radius, y)
    arcTo(radius, radius, 0f, true, true, x - radius, y)
    arcTo(radius, radius, 0f, true, true, x + radius, y)
    close()
}

@Composable
internal fun PosIcon(glyph: Glyph, description: String? = null, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(vectors.getValue(glyph), description, modifier.size(22.dp), tint = tint)
}
