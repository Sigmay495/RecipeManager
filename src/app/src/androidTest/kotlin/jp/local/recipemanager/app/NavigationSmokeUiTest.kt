package jp.local.recipemanager.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import jp.local.recipemanager.MainActivity
import org.junit.Rule
import org.junit.Test

class NavigationSmokeUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun allMainFeaturesOpenWithoutNetworkOrServer() {
        composeRule.onNode(hasText("献立") and hasClickAction()).performClick()
        composeRule.onNodeWithText("1週間の献立を自動提案").assertExists()

        composeRule.onNode(hasText("買い物") and hasClickAction()).performClick()
        composeRule.onNodeWithText("買い物材料リスト").assertExists()
        composeRule.onNodeWithText("献立から生成").assertExists()

        composeRule.onNode(hasText("その他") and hasClickAction()).performClick()
        composeRule.onNodeWithText("データ管理").performClick()
        composeRule.onNodeWithText("バックアップ").assertExists()
        composeRule.onNodeWithText("復元ファイルを選択").assertExists()
    }
}
