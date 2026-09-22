package jp.local.recipemanager.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {
    @Test fun normalizesWidthCaseAndWhitespace() {
        assertEquals("abc玉ねぎ", TextNormalizer.normalize("  ＡＢＣ　玉ねぎ "))
    }
}
