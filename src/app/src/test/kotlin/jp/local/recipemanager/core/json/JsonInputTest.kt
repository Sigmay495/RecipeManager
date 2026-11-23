package jp.local.recipemanager.core.json

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal

class JsonInputTest {
    @Test fun readsUnknownLengthStreamsAndAcceptsExactLimit() {
        val source = CountingInput(37, chunk = 3)
        assertEquals(37, JsonInput.read(source, 37).size)
        assertEquals(37, source.consumed)
    }

    @Test fun stopsOnFirstExcessByteWithoutDrainingProvider() {
        val source = CountingInput(1_000_000, chunk = 8192)
        assertThrows(JsonSizeException::class.java) { JsonInput.read(source, 37) }
        assertEquals(38, source.consumed)
    }

    @Test fun productionLimitsAcceptBoundaryAndRejectOneByteMore() {
        for (limit in listOf(JsonInput.IMPORT_LIMIT, JsonInput.BACKUP_LIMIT)) {
            assertEquals(limit, JsonInput.read(CountingInput(limit), limit).size)
            val source = CountingInput(limit + 100)
            assertThrows(JsonSizeException::class.java) { JsonInput.read(source, limit) }
            assertEquals(limit + 1, source.consumed)
        }
    }

    @Test fun handlesZeroLengthProviderReadsWithoutSpinning() {
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), JsonInput.read(source, 3))
        val excess = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        assertThrows(JsonSizeException::class.java) { JsonInput.read(excess, 3) }
    }

    @Test fun readFailurePropagatesWithoutReturningPartialData() {
        val source = object : InputStream() {
            override fun read(): Int = throw IOException("provider disconnected")
        }
        val error = assertThrows(IOException::class.java) { JsonInput.read(source, 20) }
        assertEquals("provider disconnected", error.message)
    }

    @Test fun callerRetainsStreamOwnership() {
        var closed = false
        val stream = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closed = true; super.close() }
        }
        JsonInput.read(stream, 1)
        assertFalse(closed)
        stream.close()
        assertTrue(closed)
    }

    @Test fun utf8JapaneseBomAndDecimalPrecisionSurvive() {
        val bytes = "\uFEFF{\"name\":\"材料\",\"amount\":0.12345678901234567890123456789}".toByteArray()
        val root = JsonInput.parse(bytes, 1000)
        assertEquals("材料", root.path("name").asText())
        assertEquals(BigDecimal("0.12345678901234567890123456789"), root.path("amount").decimalValue())
    }

    @Test fun duplicateKeysAndTrailingDocumentsAreRejected() {
        listOf("{\"data\":1,\"data\":2}", "{\"data\":{\"x\":1,\"x\":2}}", "{} {}", "{} null", "{} 1").forEach { json ->
            assertThrows("Must reject $json", IOException::class.java) { JsonInput.parse(json.toByteArray(), 1000) }
        }
    }

    @Test fun malformedUtf8Utf16AndEmptyInputAreRejected() {
        listOf(byteArrayOf(0xC3.toByte(), 0x28), "{}".toByteArray(Charsets.UTF_16), byteArrayOf(), "  \n".toByteArray()).forEach { bytes ->
            assertThrows(IOException::class.java) { JsonInput.parse(bytes, 1000) }
        }
    }

    @Test fun byteArrayParserCannotBypassSizeLimit() {
        assertThrows(JsonSizeException::class.java) { JsonInput.parse("{}".toByteArray(), 1) }
        assertTrue(JsonInput.parse("{}".toByteArray(), 2).isObject)
    }

    @Test fun allowsOnlyEmbeddedSchemaReferencesIncludingNestedArrays() {
        val mapper = JsonInput.mapper()
        JsonInput.requireLocalReferences(mapper.readTree("""{"allOf":[{"${'$'}ref":"#/${'$'}defs/local"}]}"""))
        for (key in listOf("\$ref", "\$dynamicRef", "\$recursiveRef")) {
            for (ref in listOf("https://example.invalid/schema", "file:///secret", "other.json", "//example.invalid")) {
                val schema = mapper.createObjectNode().putArray("allOf").addObject().put(key, ref)
                assertThrows(IllegalArgumentException::class.java) { JsonInput.requireLocalReferences(schema) }
            }
        }
    }

    private class CountingInput(private val size: Int, private val chunk: Int = 8192) : InputStream() {
        var consumed = 0
            private set
        override fun read(): Int = if (consumed == size) -1 else { consumed++; 0x20 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed == size) return -1
            val count = minOf(size - consumed, len, chunk)
            b.fill(0x20, off, off + count)
            consumed += count
            return count
        }
    }
}
