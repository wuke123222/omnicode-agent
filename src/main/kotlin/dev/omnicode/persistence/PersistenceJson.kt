package dev.omnicode.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.time.Instant

internal object PersistenceJson {
    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .registerTypeAdapter(Instant::class.java, InstantAdapter)
        .create()

    private object InstantAdapter : JsonSerializer<Instant>, JsonDeserializer<Instant> {
        override fun serialize(
            source: Instant,
            typeOfSource: Type,
            context: JsonSerializationContext,
        ): JsonElement = JsonPrimitive(source.toString())

        override fun deserialize(
            json: JsonElement,
            typeOfTarget: Type,
            context: JsonDeserializationContext,
        ): Instant = try {
            Instant.parse(json.asString)
        } catch (error: RuntimeException) {
            throw JsonParseException("Invalid instant", error)
        }
    }
}
