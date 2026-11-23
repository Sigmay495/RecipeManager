package jp.local.recipemanager.core.json

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Reads provider streams without trusting SIZE/length or buffering an oversized file. */
object JsonInput {
    const val IMPORT_LIMIT = 5 * 1024 * 1024
    const val BACKUP_LIMIT = 50 * 1024 * 1024

    fun read(stream: InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            // Read only enough to identify the first extra byte, including at exact limit.
            val requested = minOf(buffer.size.toLong(), limit.toLong() - total + 1).toInt()
            val count = stream.read(buffer, 0, requested)
            if (count < 0) break
            if (count == 0) {
                // A provider returning zero must not create an endless busy loop.
                val next = stream.read()
                if (next < 0) break
                if (total == limit) throw JsonSizeException(limit)
                output.write(next)
                total++
            } else {
                if (count > limit - total) throw JsonSizeException(limit)
                output.write(buffer, 0, count)
                total += count
            }
        }
        return output.toByteArray()
    }

    fun mapper(): ObjectMapper = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun parse(bytes: ByteArray, limit: Int, mapper: ObjectMapper = mapper()): JsonNode {
        if (bytes.size > limit) throw JsonSizeException(limit)
        val json = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (e: CharacterCodingException) {
            throw IOException("JSONファイルはUTF-8で保存してください", e)
        }
        val root = mapper.readTree(json)
        if (root == null || root.isMissingNode) throw IOException("JSONファイルが空です")
        return root
    }

    /** Local schema documents may use #/$defs references; never resolve external URLs. */
    fun requireLocalReferences(schema: JsonNode) {
        when {
            schema.isObject -> {
                listOf("\$ref", "\$dynamicRef", "\$recursiveRef").forEach { key ->
                    schema.get(key)?.let { ref ->
                        require(ref.isTextual && ref.asText().startsWith("#")) {
                            "Schemaの外部参照は使用できません"
                        }
                    }
                }
                schema.elements().forEachRemaining(::requireLocalReferences)
            }
            schema.isArray -> schema.forEach(::requireLocalReferences)
        }
    }
}

class JsonSizeException(val limit: Int) : IOException("ファイルサイズは${limit / (1024 * 1024)}MiB以内にしてください")
