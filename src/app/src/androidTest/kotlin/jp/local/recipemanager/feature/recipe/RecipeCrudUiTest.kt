package jp.local.recipemanager.feature.recipe

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import jp.local.recipemanager.MainActivity
import org.junit.Rule
import org.junit.Test

class RecipeCrudUiTest {
    @get:Rule val composeRule=createAndroidComposeRule<MainActivity>()
    @Test fun registersRecipeAndShowsDetail() {
        val recipeName="UIテスト用みそ汁${System.nanoTime()}"
        composeRule.waitUntil(15000) { composeRule.onAllNodes(hasText("レシピ") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("レシピ") and hasClickAction()).performClick()
        composeRule.onNodeWithText("レシピを登録").performClick()
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText("料理名").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("料理名").performTextReplacement(recipeName)
        composeRule.onNodeWithText("材料を検索・追加").performScrollTo().performClick()
        composeRule.onNodeWithText("材料名・別名").performTextInput("みそ")
        composeRule.onNodeWithText("みそ（内蔵）").performClick()
        composeRule.onNodeWithText("内容を確認して保存").performScrollTo().performClick()
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText("レシピ詳細").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(recipeName).assertExists()
        composeRule.onNodeWithText("編集").performScrollTo().performClick()
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText("料理名").fetchSemanticsNodes().isNotEmpty() }
        val editedName="${recipeName}改"
        composeRule.onNodeWithText("料理名").performTextReplacement(editedName)
        composeRule.onNodeWithText("内容を確認して保存").performScrollTo().performClick()
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText("レシピ詳細").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(editedName).assertExists()
        composeRule.onNodeWithText("参照を確認して削除").performScrollTo().performClick()
        composeRule.onNodeWithText("取り消す").performClick()
        composeRule.onNodeWithText(editedName).assertExists()
        composeRule.onNodeWithText("参照を確認して削除").performScrollTo().performClick()
        composeRule.onNodeWithText("確認して実行").performClick()
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText("レシピを登録").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("料理名・材料名で検索").performTextInput(editedName)
        composeRule.onNodeWithText("検索結果 0件").assertExists()
    }
}
