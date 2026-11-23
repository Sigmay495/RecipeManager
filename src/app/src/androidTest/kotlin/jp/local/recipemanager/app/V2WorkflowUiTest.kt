package jp.local.recipemanager.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import jp.local.recipemanager.MainActivity
import jp.local.recipemanager.app.theme.RecipeManagerTheme
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class V2WorkflowUiTest {
    @get:Rule val composeRule=createAndroidComposeRule<MainActivity>()

    @Test fun materialSelectionRecipeSaveAndDraftReturnWorkThroughVisibleControls() {
        val suffix=UUID.randomUUID().toString().take(8)
        val food="画面材料$suffix";val recipe="画面料理$suffix"
        composeRule.waitUntil(15000) { composeRule.onAllNodes(hasText("材料") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("材料") and hasClickAction()).performClick()
        composeRule.onNodeWithText("利用者材料を登録").performClick()
        composeRule.onNodeWithText("材料名").performTextInput(food)
        composeRule.onNodeWithText("材料を保存").performScrollTo().performClick()
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText("材料名・別名で検索").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("材料名・別名で検索").performTextInput(food)
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText(food).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("レシピ") and hasClickAction()).performClick()
        composeRule.onNodeWithText("レシピを登録").performClick()
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText("料理名").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("料理名").performTextReplacement(recipe)
        composeRule.onNodeWithText("材料を検索・追加").performScrollTo().performClick()
        composeRule.onNodeWithText("材料名・別名").performTextInput(food)
        composeRule.onNodeWithText("$food（利用者材料）").performClick()
        composeRule.onNodeWithText("分量（不明なら空欄）").performScrollTo().performTextInput("1.25")
        composeRule.onNodeWithText("内容を確認して保存").performScrollTo().performClick()
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText("レシピ詳細").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(recipe).assertExists()
        composeRule.onNodeWithText("編集").performScrollTo().performClick()
        composeRule.waitUntil(10000) { composeRule.onAllNodesWithText("料理名").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("下書きを保持して戻る").performScrollTo().performClick()
        composeRule.onNode(hasText("その他") and hasClickAction()).performClick()
        composeRule.onNodeWithText("保存中の下書き").performScrollTo().performClick()
        composeRule.onNodeWithText("「レシピ編集・$recipe」を再開").performScrollTo().performClick()
        composeRule.onNodeWithText(recipe).assertExists()
    }

    @Test fun navigationAndLongSettingsRemainReachableAtTwoHundredPercentText() {
        composeRule.activityRule.scenario.onActivity { activity->
            activity.setContent {
                val density=LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density,2f)) {
                    RecipeManagerTheme { RecipeManagerApp() }
                }
            }
        }
        composeRule.waitUntil(15000) { composeRule.onAllNodes(hasText("その他") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("その他") and hasClickAction()).performClick()
        composeRule.onNodeWithText("設定").performScrollTo().performClick()
        composeRule.onNode(hasText("その他") and hasClickAction()).assertIsSelected()
        composeRule.onNode(hasText("ホーム") and hasClickAction()).assertIsNotSelected()
        composeRule.onNodeWithText("バックアップ通知間隔：30日").performScrollTo().performClick()
        composeRule.onNodeWithText("60日").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("取り消す").performScrollTo().performClick()
        composeRule.onNodeWithText("端末のライト／ダーク設定と文字サイズに合わせて表示します。").performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasText("材料") and hasClickAction()).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("利用者材料を登録").performScrollTo().assertIsDisplayed()
    }
}
