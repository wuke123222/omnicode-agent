package dev.omnicode.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal fun objectSchema(
    required: List<String> = emptyList(),
    properties: JsonObject.() -> Unit,
): JsonObject = JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply(properties))
    add("required", JsonArray().apply { required.forEach(::add) })
    addProperty("additionalProperties", false)
}

internal fun JsonObject.stringProperty(name: String, description: String) {
    add(name, JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
    })
}

internal fun JsonObject.booleanProperty(name: String, description: String, default: Boolean) {
    add(name, JsonObject().apply {
        addProperty("type", "boolean")
        addProperty("description", description)
        addProperty("default", default)
    })
}

internal fun JsonObject.integerProperty(name: String, description: String, default: Int, minimum: Int, maximum: Int) {
    add(name, JsonObject().apply {
        addProperty("type", "integer")
        addProperty("description", description)
        addProperty("default", default)
        addProperty("minimum", minimum)
        addProperty("maximum", maximum)
    })
}

internal fun JsonObject.stringArrayProperty(name: String, description: String) {
    add(name, JsonObject().apply {
        addProperty("type", "array")
        addProperty("description", description)
        add("items", JsonObject().apply { addProperty("type", "string") })
        addProperty("minItems", 1)
    })
}
