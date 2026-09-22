package jp.local.recipemanager.app

enum class AppDestination(
    val route: String,
    val label: String,
    val topLevel: Boolean = false,
) {
    HOME("home", "ホーム", true),
    MENU_PLAN("menu/plan", "献立", true),
    RECIPE_LIST("recipes", "レシピ", true),
    SHOPPING_LIST("shopping", "買い物", true),
    MORE("more", "その他", true),
    RECIPE_DETAIL("recipes/detail", "レシピ詳細"),
    RECIPE_EDIT("recipes/edit", "レシピ編集"),
    RECIPE_IMPORT("recipes/import", "レシピImport"),
    MENU_DETAIL("menu/detail", "献立詳細"),
    MENU_HISTORY("menu/history", "献立履歴"),
    NO_MEAL_DAY("menu/no-meal", "ご飯不要日"),
    DATA_MANAGEMENT("settings/data", "データ管理"),
    SETTINGS("settings", "設定"),
    ;

    companion object {
        val topLevelDestinations: List<AppDestination> = entries.filter(AppDestination::topLevel)
    }
}

