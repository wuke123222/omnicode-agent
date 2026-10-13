package dev.omnicode.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

object Json {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Parse a JSON object without leaking Gson's JsonNull/ClassCastException boundary.
     * Provider and MCP responses are untrusted; callers can catch one stable parse exception
     * rather than accidentally stopping an Agent run with a raw `JsonNull cannot be cast` error.
     */
    fun parseObject(value: String): JsonObject {
        val parsed = JsonParser.parseString(value)
        if (!parsed.isJsonObject) throw JsonParseException("Expected a JSON object")
        return parsed.asJsonObject
    }

    fun stringify(value: JsonElement): String = gson.toJson(value)
}
