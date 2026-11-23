package jp.local.recipemanager.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val RecipeTypography = Typography().run { copy(
    bodyLarge = bodyLarge.copy(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=26.sp),
    bodyMedium = bodyMedium.copy(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=24.sp),
    bodySmall = bodySmall.copy(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=22.sp),
    titleLarge = titleLarge.copy(fontFamily=FontFamily.SansSerif,fontSize=24.sp,lineHeight=34.sp),
    titleMedium = titleMedium.copy(fontFamily=FontFamily.SansSerif,fontSize=18.sp,lineHeight=28.sp),
    labelLarge = labelLarge.copy(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=24.sp),
    labelMedium = labelMedium.copy(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=22.sp),
) }

private val RecipeColorScheme = lightColorScheme(
    primary = Color(0xFF8C4A3C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFF77574F),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFF8F6),
    onBackground = Color(0xFF241917),
    onSurface = Color(0xFF241917),
    onPrimaryContainer = Color(0xFF3A0905),
    surfaceVariant = Color(0xFFF7EAE6),
    onSurfaceVariant = Color(0xFF5B423B),
    outline = Color(0xFF8B716A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val RecipeDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB4A2), onPrimary = Color(0xFF561E13),
    primaryContainer = Color(0xFF71352A), onPrimaryContainer = Color(0xFFFFDAD2),
    background = Color(0xFF1C1412), onBackground = Color(0xFFF3DED8),
    surface = Color(0xFF1C1412), onSurface = Color(0xFFF3DED8),
    surfaceVariant = Color(0xFF30231F), onSurfaceVariant = Color(0xFFDBC1B8),
    outline = Color(0xFFA58C85), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

@Composable
fun RecipeManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) RecipeDarkColorScheme else RecipeColorScheme,
        typography = RecipeTypography,
        content = content,
    )
}

