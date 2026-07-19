package dev.omnicode.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object Json {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseObject(value: String): JsonObject = JsonParser.parseString(value).asJsonObject

    fun stringify(value: JsonElement): String = gson.toJson(value)
}
