package jp.local.recipemanager.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RecipeColorScheme = lightColorScheme(
    primary = Color(0xFF8C4A3C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFF77574F),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFF8F6),
)

@Composable
fun RecipeManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RecipeColorScheme,
        content = content,
    )
}

