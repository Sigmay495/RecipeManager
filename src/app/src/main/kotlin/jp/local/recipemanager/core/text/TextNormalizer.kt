package jp.local.recipemanager.core.text

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {
    fun normalize(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, "")

    private val WHITESPACE = Regex("[\\s\\u3000]+")
}
