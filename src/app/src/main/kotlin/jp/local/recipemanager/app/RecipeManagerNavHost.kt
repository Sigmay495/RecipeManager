package jp.local.recipemanager.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import jp.local.recipemanager.feature.recipe.RecipeDetailScreen
import jp.local.recipemanager.feature.recipe.RecipeEditScreen
import jp.local.recipemanager.feature.recipe.RecipeListScreen
import jp.local.recipemanager.feature.recipe.RecipeViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.local.recipemanager.RecipeManagerApplication
import jp.local.recipemanager.feature.recipeimport.RecipeImportScreen
import jp.local.recipemanager.feature.recipeimport.RecipeImportViewModel
import jp.local.recipemanager.feature.menu.*
import java.time.LocalDate
import jp.local.recipemanager.feature.shopping.ShoppingScreen
import jp.local.recipemanager.feature.shopping.ShoppingViewModel
import jp.local.recipemanager.feature.backup.*

@Composable
fun RecipeManagerNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    recipeViewModel: RecipeViewModel,
) {
    val application = LocalContext.current.applicationContext as RecipeManagerApplication
    val menuViewModel: MenuViewModel = viewModel(factory = MenuViewModel.factory(application))
    val shoppingViewModel: ShoppingViewModel = viewModel(factory = ShoppingViewModel.factory(application))
    val backupViewModel: BackupViewModel = viewModel(factory = BackupViewModel.factory(application))
    NavHost(navController, AppDestination.HOME.route, Modifier.padding(contentPadding)) {
        composable(AppDestination.HOME.route) { HomeScreen(backupViewModel, { navController.navigate(AppDestination.MENU_PLAN.route) }, { navController.navigate(AppDestination.DATA_MANAGEMENT.route) }) }
        composable(AppDestination.MENU_PLAN.route) {
            MenuPlanScreen(menuViewModel, { navController.navigate("menu/no-meal?date=$it") }, { navController.navigate("menu/detail/$it") })
        }
        composable(AppDestination.SHOPPING_LIST.route) { ShoppingScreen(shoppingViewModel) }
        composable(AppDestination.MORE.route) { MoreScreen { navController.navigate(it.route) } }

        composable(AppDestination.RECIPE_LIST.route) {
            RecipeListScreen(
                viewModel = recipeViewModel,
                onOpen = { navController.navigate("recipes/detail/$it") },
                onAdd = { navController.navigate("recipes/edit") },
            )
        }
        composable(
            route = "recipes/detail/{recipeId}",
            arguments = listOf(navArgument("recipeId") { type = NavType.StringType }),
        ) { entry ->
            RecipeDetailScreen(
                recipeId = requireNotNull(entry.arguments?.getString("recipeId")),
                viewModel = recipeViewModel,
                onBack = navController::popBackStack,
                onEdit = { navController.navigate("recipes/edit?recipeId=$it") },
            )
        }
        composable(
            route = "recipes/edit?recipeId={recipeId}",
            arguments = listOf(navArgument("recipeId") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            RecipeEditScreen(
                recipeId = entry.arguments?.getString("recipeId").orEmpty().ifBlank { null },
                viewModel = recipeViewModel,
                onBack = navController::popBackStack,
                onSaved = { id ->
                    navController.navigate("recipes/detail/$id") { popUpTo(AppDestination.RECIPE_LIST.route) }
                },
            )
        }

        composable(AppDestination.RECIPE_IMPORT.route) {
            val importViewModel: RecipeImportViewModel = viewModel(factory = RecipeImportViewModel.factory(application))
            RecipeImportScreen(importViewModel, navController::popBackStack)
        }

        composable("menu/detail/{date}", arguments = listOf(navArgument("date") { type = NavType.StringType })) { entry ->
            MenuDetailScreen(LocalDate.parse(requireNotNull(entry.arguments?.getString("date"))), menuViewModel, navController::popBackStack)
        }
        composable(AppDestination.MENU_HISTORY.route) { MenuHistoryScreen(menuViewModel, navController::popBackStack) }
        composable("menu/no-meal?date={date}", arguments = listOf(navArgument("date") { type = NavType.StringType; defaultValue = "" })) { entry ->
            NoMealDayScreen(entry.arguments?.getString("date")?.takeIf(String::isNotBlank)?.let(LocalDate::parse), menuViewModel, navController::popBackStack)
        }
        composable(AppDestination.DATA_MANAGEMENT.route) { DataManagementScreen(backupViewModel, navController::popBackStack) }
        composable(AppDestination.SETTINGS.route) { SettingsScreen(backupViewModel, navController::popBackStack) }
    }
}

@Composable
private fun PlaceholderScreen(title: String, onNavigate: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(title)
        if (onNavigate != null) Button(onClick = onNavigate, modifier = Modifier.padding(top = 16.dp)) { Text("関連画面を開く") }
    }
}

@Composable
private fun MoreScreen(onNavigate: (AppDestination) -> Unit) {
    val destinations = listOf(AppDestination.MENU_HISTORY, AppDestination.RECIPE_IMPORT, AppDestination.DATA_MANAGEMENT, AppDestination.SETTINGS)
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("その他")
        destinations.forEach { destination -> TextButton(onClick = { onNavigate(destination) }) { Text(destination.label) } }
    }
}
