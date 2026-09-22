package jp.local.recipemanager.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import jp.local.recipemanager.RecipeManagerApplication
import jp.local.recipemanager.feature.recipe.RecipeViewModel

@Composable
fun RecipeManagerApp() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as RecipeManagerApplication
    val recipeViewModel: RecipeViewModel = viewModel(factory = RecipeViewModel.factory(application))
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestination.topLevelDestinations.forEach { destination ->
                    val selected = if (destination == AppDestination.RECIPE_LIST) {
                        currentDestination?.route?.startsWith("recipes") == true
                    } else {
                        currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    }

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(AppDestination.HOME.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(destination.label.take(1)) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            RecipeManagerNavHost(
                navController = navController,
                contentPadding = PaddingValues(),
                recipeViewModel = recipeViewModel,
            )
        }
    }
}
