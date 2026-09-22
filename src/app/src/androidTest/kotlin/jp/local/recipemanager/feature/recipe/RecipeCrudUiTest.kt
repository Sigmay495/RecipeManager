package jp.local.recipemanager.feature.recipe

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import jp.local.recipemanager.MainActivity
import org.junit.Rule
import org.junit.Test

class RecipeCrudUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun registersRecipeAndShowsDetail() {
        val recipeName = "UIテスト用みそ汁${System.nanoTime()}"
        composeRule.onNode(hasText("レシピ") and hasClickAction()).performClick()
        composeRule.onNodeWithText("新規登録", useUnmergedTree = true).performClick()
        composeRule.onNode(hasSetTextAction() and hasText("料理名（必須）")).performTextInput(recipeName)
        composeRule.onNode(hasSetTextAction() and hasText("材料名")).performTextInput("みそ")
        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("保存"))
        composeRule.onNodeWithText("保存", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(recipeName).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(recipeName).assertExists()
        composeRule.onNodeWithText("編集").assertExists()
        composeRule.onNodeWithText("削除").assertExists()

        composeRule.onNode(hasText("編集") and hasClickAction()).performScrollTo().performClick()
        val editedName = "${recipeName}改"
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement(editedName)
        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("保存"))
        composeRule.onNodeWithText("保存", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(editedName).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNode(hasText("削除") and hasClickAction()).performScrollTo().performClick()
        composeRule.onAllNodes(hasText("削除") and hasClickAction()).onLast().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("復元").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("復元") and hasClickAction()).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("編集").fetchSemanticsNodes().isNotEmpty() }
    }
}
