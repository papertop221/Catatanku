package com.catatanku.app.ui

import android.os.Build
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catatanku.app.R

private val Light = lightColorScheme(
    primary = Color(0xFF191918), onPrimary = Color(0xFFFBFBFA),
    primaryContainer = Color(0xFFEAE9E5), onPrimaryContainer = Color(0xFF191918),
    secondary = Color(0xFF5F5E5A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAE9E5), onSecondaryContainer = Color(0xFF191918),
    tertiary = Color(0xFF8A5200), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBEACC), onTertiaryContainer = Color(0xFF5A3300),
    background = Color(0xFFFBFBFA), onBackground = Color(0xFF191918),
    surface = Color(0xFFFBFBFA), onSurface = Color(0xFF191918),
    surfaceVariant = Color(0xFFF1F0ED), onSurfaceVariant = Color(0xFF5F5E5A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F5F2),
    surfaceContainer = Color(0xFFF1F0ED), surfaceContainerHigh = Color(0xFFEAE9E5),
    surfaceContainerHighest = Color(0xFFE3E2DD),
    outline = Color(0xFFB5B4AE), outlineVariant = Color(0xFFE6E5E1),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF601410),
    inverseSurface = Color(0xFF2B2B29), inverseOnSurface = Color(0xFFF4F3F0), inversePrimary = Color.White,
    surfaceTint = Color.Transparent, scrim = Color.Black,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFECECE9), onPrimary = Color(0xFF111110),
    primaryContainer = Color(0xFF2A2A28), onPrimaryContainer = Color(0xFFECECE9),
    secondary = Color(0xFFA6A5A0), onSecondary = Color(0xFF111110),
    secondaryContainer = Color(0xFF2A2A28), onSecondaryContainer = Color(0xFFECECE9),
    tertiary = Color(0xFFF0B450), onTertiary = Color(0xFF3A2400),
    tertiaryContainer = Color(0xFF3D2C0C), onTertiaryContainer = Color(0xFFFBE0A8),
    background = Color.Black, onBackground = Color(0xFFECECE9),
    surface = Color.Black, onSurface = Color(0xFFECECE9),
    surfaceVariant = Color(0xFF1A1A19), onSurfaceVariant = Color(0xFFA6A5A0),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF161615),
    surfaceContainer = Color(0xFF1A1A19), surfaceContainerHigh = Color(0xFF222221),
    surfaceContainerHighest = Color(0xFF2C2C2A),
    outline = Color(0xFF5E5D58), outlineVariant = Color(0xFF2A2A28),
    error = Color(0xFFF28B82), onError = Color(0xFF601410),
    errorContainer = Color(0xFF5C1A15), onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFECECE9), inverseOnSurface = Color(0xFF191918), inversePrimary = Color(0xFF191918),
    surfaceTint = Color.Transparent, scrim = Color.Black,
)

@Composable
private fun scheme(dynamic: Boolean): ColorScheme {
    val dark = isSystemInDarkTheme()
    if (!dynamic || Build.VERSION.SDK_INT < 31) return if (dark) Dark else Light
    val ctx = LocalContext.current
    return if (dark) dynamicDarkColorScheme(ctx).copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black)
    else dynamicLightColorScheme(ctx)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun noteFont(weight: FontWeight) = Font(
    R.font.atkinson_hyperlegible_next, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)
val NoteFont = FontFamily(noteFont(FontWeight.Normal), noteFont(FontWeight.Medium), noteFont(FontWeight.SemiBold))

object NoteType {
    val title = TextStyle(fontFamily = NoteFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp)
    val body = TextStyle(fontFamily = NoteFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)
    val rowTitle = TextStyle(fontFamily = NoteFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val rowSnippet = TextStyle(fontFamily = NoteFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
}

private val Type = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.1.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodySmall = bodySmall.copy(lineHeight = 17.sp, letterSpacing = 0.1.sp),
        labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    )
}

val SmallShape = RoundedCornerShape(8.dp)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun IncognitoKeyboardHost(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            val wrapped = PlatformTextInputMethodRequest { outAttrs ->
                val ic = request.createInputConnection(outAttrs)
                outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                ic
            }
            nextHandler.startInputMethod(wrapped)
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

@Composable
fun CatatankuTheme(dynamic: Boolean = false, incognitoKeyboard: Boolean = true, content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = scheme(dynamic),
    typography = Type,
    shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = SmallShape, medium = SmallShape,
        large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(16.dp),
    ),
) {
    IncognitoKeyboardHost(incognitoKeyboard, content)
}

object AppIcons {
    private fun icon(name: String, vararg strokes: String, fills: List<String> = emptyList()) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            fills.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
            strokes.forEach {
                addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()

    private fun dot(x: Float, y: Float, r: Float) = "M${x - r} ${y}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private const val BELL = "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15z"
    private const val PIN = "M9 3.5h6l-.8 6.2 3.3 3.3v1.5h-11V13l3.3-3.3z"

    val Plus = icon("plus", "M12 5v14M5 12h14")
    val Search = icon("search", "M17 10.5a6.5 6.5 0 1 1-13 0a6.5 6.5 0 0 1 13 0z", "M15.3 15.3L20 20")
    val Close = icon("close", "M6 6l12 12M18 6L6 18")
    val Back = icon("back", "M20 12H4M10 6l-6 6 6 6")
    val More = icon("more", fills = listOf(dot(12f, 5f, 1.6f), dot(12f, 12f, 1.6f), dot(12f, 19f, 1.6f)))
    val ChevronDown = icon("chevron-down", "M6.5 9.5L12 15l5.5-5.5")
    val ChevronUp = icon("chevron-up", "M6.5 14.5L12 9l5.5 5.5")
    val ChevronRight = icon("chevron-right", "M9.5 6.5L15 12l-5.5 5.5")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val Bell = icon("bell", BELL, "M10 20.5a2 2 0 0 0 4 0")
    val BellFilled = icon("bell-filled", BELL, "M10 20.5a2 2 0 0 0 4 0", fills = listOf(BELL))
    val Pin = icon("pin", PIN, "M12 14.5V21")
    val PinFilled = icon("pin-filled", PIN, "M12 14.5V21", fills = listOf(PIN))
    val Folder = icon("folder", "M3.5 7A1.5 1.5 0 0 1 5 5.5h4.2l2 2.5H19a1.5 1.5 0 0 1 1.5 1.5v8A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z")
    val CheckBox = icon("checkbox", "M6 4.5h12A1.5 1.5 0 0 1 19.5 6v12a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 18V6A1.5 1.5 0 0 1 6 4.5z", "M8.5 12.2l2.5 2.5 4.7-5.2")
    val Bullets = icon("bullets", "M9 7h11M9 12h11M9 17h11", fills = listOf(dot(4.8f, 7f, 1.2f), dot(4.8f, 12f, 1.2f), dot(4.8f, 17f, 1.2f)))
    val Archive = icon("archive", "M4.5 8.5v10A1.5 1.5 0 0 0 6 20h12a1.5 1.5 0 0 0 1.5-1.5v-10", "M3 4.5h18v4H3z", "M10 12.5h4")
    val Pen = icon("pen", "M4 20l1-4.5L16.5 4a2.1 2.1 0 0 1 3 3L8 18.5z", "M14.5 6L18 9.5")
    val Clock = icon("clock", "M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 0 1 17 0z", "M12 7.5V12l3 2")
    val Lock = icon("lock", "M7 11h10a1.5 1.5 0 0 1 1.5 1.5v6A1.5 1.5 0 0 1 17 20H7a1.5 1.5 0 0 1-1.5-1.5v-6A1.5 1.5 0 0 1 7 11z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    val Shield = icon("shield", "M12 3.5l7 3v5.5c0 4.5-3 7.8-7 9.5-4-1.7-7-5-7-9.5V6.5z")
    val Undo = icon("undo", "M9 8L5 12l4 4", "M5 12h9a5 5 0 1 1 0 10h-2")
    val Redo = icon("redo", "M15 8l4 4-4 4", "M19 12h-9a5 5 0 1 0 0 10h2")
    val Image = icon("image", "M4.5 6A1.5 1.5 0 0 1 6 4.5h12A1.5 1.5 0 0 1 19.5 6v12a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 18z", "M4.5 16l4.5-4.5 3.5 3.5 2.5-2.5 4.5 4.5", fills = listOf(dot(9f, 8.5f, 1.4f)))
    val Mic = icon("mic", "M12 3.5a3 3 0 0 1 3 3V12a3 3 0 0 1-6 0V6.5a3 3 0 0 1 3-3z", "M6.5 11.5a5.5 5.5 0 0 0 11 0", "M12 17v3.5M9 20.5h6")
    val Play = icon("play", "M8 6l10 6-10 6z", fills = listOf("M8 6l10 6-10 6z"))
    val Stop = icon("stop", "M7 7h10v10H7z", fills = listOf("M7 7h10v10H7z"))
}
