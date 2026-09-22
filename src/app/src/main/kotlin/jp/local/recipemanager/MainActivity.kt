package jp.local.recipemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import jp.local.recipemanager.app.RecipeManagerApp
import jp.local.recipemanager.app.theme.RecipeManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecipeManagerTheme {
                RecipeManagerApp()
            }
        }
    }
}

