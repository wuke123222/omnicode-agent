package dev.omnicode.util

import com.google.gson.JsonParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {
    @Test
    fun `parseObject accepts an object`() {
        assertEquals("ok", Json.parseObject("{\"status\":\"ok\"}").get("status").asString)
    }

    @Test
    fun `parseObject rejects null and scalar values with a stable parse error`() {
        assertFailsWith<JsonParseException> { Json.parseObject("null") }
        assertFailsWith<JsonParseException> { Json.parseObject("[1, 2]") }
    }
}
