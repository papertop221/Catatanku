package com.catatanku.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tema minimalis: permukaan netral hangat, tinta sebagai warna utama, dan SATU aksen (amber)
 * yang dipakai hanya untuk AI — sehingga apa pun yang berwarna amber berarti "ini AI".
 */
private val Light = lightColorScheme(
    primary = Color(0xFF1C1C1A), onPrimary = Color(0xFFFBFBF9),
    primaryContainer = Color(0xFFE9E7E0), onPrimaryContainer = Color(0xFF1C1C1A),
    secondary = Color(0xFF55544F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E7E0), onSecondaryContainer = Color(0xFF1C1C1A),
    tertiary = Color(0xFF8F5200), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE8C8), onTertiaryContainer = Color(0xFF5C3400),
    background = Color(0xFFFBFBF9), onBackground = Color(0xFF1C1C1A),
    surface = Color(0xFFFBFBF9), onSurface = Color(0xFF1C1C1A),
    surfaceVariant = Color(0xFFF1F0EB), onSurfaceVariant = Color(0xFF6A6964),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F5F1),
    surfaceContainer = Color(0xFFF1F0EB), surfaceContainerHigh = Color(0xFFEBE9E3),
    surfaceContainerHighest = Color(0xFFE5E3DC),
    outline = Color(0xFFB9B7AF), outlineVariant = Color(0xFFE3E1DA),
    error = Color(0xFFB3261E), onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFEDEDE9), onPrimary = Color(0xFF151514),
    primaryContainer = Color(0xFF2E2E2B), onPrimaryContainer = Color(0xFFEDEDE9),
    secondary = Color(0xFFBDBCB5), onSecondary = Color(0xFF151514),
    secondaryContainer = Color(0xFF2E2E2B), onSecondaryContainer = Color(0xFFEDEDE9),
    tertiary = Color(0xFFF2B84B), onTertiary = Color(0xFF3A2400),
    tertiaryContainer = Color(0xFF4A3410), onTertiaryContainer = Color(0xFFFBE0A8),
    background = Color(0xFF121211), onBackground = Color(0xFFEDEDE9),
    surface = Color(0xFF121211), onSurface = Color(0xFFEDEDE9),
    surfaceVariant = Color(0xFF1E1E1C), onSurfaceVariant = Color(0xFFA5A49D),
    surfaceContainerLowest = Color(0xFF0D0D0C), surfaceContainerLow = Color(0xFF191918),
    surfaceContainer = Color(0xFF1E1E1C), surfaceContainerHigh = Color(0xFF282826),
    surfaceContainerHighest = Color(0xFF323230),
    outline = Color(0xFF6E6D67), outlineVariant = Color(0xFF30302D),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
)

private val Type = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(lineHeight = 26.sp, letterSpacing = 0.1.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 21.sp, letterSpacing = 0.1.sp),
        labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    )
}

@Composable
fun CatatankuTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) Dark else Light,
    typography = Type,
    shapes = Shapes(
        small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
    ),
    content = content,
)

/** Ikon garis sendiri (24dp) agar tidak perlu pustaka ikon besar maupun emoji. */
object AppIcons {
    private fun icon(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()

    val Sparkle = icon("sparkle",
        "M11 3l1.7 5.1c.3.9 1 1.6 1.9 1.9L19.7 11.7l-5.1 1.7c-.9.3-1.6 1-1.9 1.9L11 20.4l-1.7-5.1c-.3-.9-1-1.6-1.9-1.9L2.3 11.7l5.1-1.7c.9-.3 1.6-1 1.9-1.9z",
        "M19 2l.7 2.1c.1.4.4.7.8.8L22.6 5.6l-2.1.7c-.4.1-.7.4-.8.8L19 9.2l-.7-2.1c-.1-.4-.4-.7-.8-.8L15.4 5.6l2.1-.7c.4-.1.7-.4.8-.8z")
    val Folder = icon("folder",
        "M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2zM4 18V8h16v10H4z")
    val Pin = icon("pin",
        "M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z")
    val PinOutline = icon("pin-outline",
        "M14 4v5c0 1.12.37 2.16 1 3H9c.65-.86 1-1.9 1-3V4h4m3-2H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3V4h1c.55 0 1-.45 1-1s-.45-1-1-1z")
    val CheckBox = icon("checkbox",
        "M19 5v14H5V5h14m0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z",
        "M10.3 16.4l-3.7-3.7 1.4-1.4 2.3 2.3 5.3-5.3 1.4 1.4z")
    val Bullets = icon("bullets",
        "M4 10.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm0-6c-.83 0-1.5.67-1.5 1.5S3.17 7.5 4 7.5 5.5 6.83 5.5 6 4.83 4.5 4 4.5zm0 12c-.83 0-1.5.68-1.5 1.5s.68 1.5 1.5 1.5 1.5-.68 1.5-1.5-.67-1.5-1.5-1.5zM7 19h14v-2H7v2zm0-6h14v-2H7v2zm0-8v2h14V5H7z")
    val Chevron = icon("chevron", "M7.4 8.6L12 13.2l4.6-4.6L18 10l-6 6-6-6z")
    val Bell = icon("bell",
        "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z")
    val BellOutline = icon("bell-outline",
        "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.9 2 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.64 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2zm-2 1H8v-6c0-2.48 1.51-4.5 4-4.5s4 2.02 4 4.5v6z")
    val Lock = icon("lock",
        "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z")
}
