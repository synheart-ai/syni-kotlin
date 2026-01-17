package com.syni.sdk.schema

import android.content.Context
import com.syni.sdk.core.SyniError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Validates JSON output against schemas and loads GBNF grammars.
 */
class SchemaValidator(
    private val context: Context,
    private val schemasDir: String?,
    private val grammarsDir: String?
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val schemas = mutableMapOf<String, JSONSchema>()
    private val grammars = mutableMapOf<String, Grammar>()

    init {
        // Load built-in schemas and grammars
        loadBuiltInSchemas()
        loadBuiltInGrammars()

        // Load custom schemas and grammars from directories
        schemasDir?.let { loadSchemasFromDirectory(it) }
        grammarsDir?.let { loadGrammarsFromDirectory(it) }
    }

    /**
     * Get a schema by ID.
     */
    fun getSchema(schemaId: String): JSONSchema? = schemas[schemaId]

    /**
     * Get a grammar by ID.
     */
    fun getGrammar(grammarId: String): Grammar? = grammars[grammarId]

    /**
     * Register a custom schema.
     */
    fun registerSchema(schema: JSONSchema) {
        schemas[schema.id] = schema
    }

    /**
     * Register a custom grammar.
     */
    fun registerGrammar(grammar: Grammar) {
        grammars[grammar.id] = grammar
    }

    /**
     * Validate JSON text against a schema.
     *
     * @param jsonText The JSON text to validate
     * @param schemaId The schema ID to validate against
     * @return ValidationResult with success status and any errors
     */
    fun validate(jsonText: String, schemaId: String): ValidationResult {
        val schema = schemas[schemaId]
            ?: return ValidationResult(
                isValid = false,
                errors = listOf("Schema not found: $schemaId")
            )

        return try {
            val jsonElement = json.parseToJsonElement(jsonText)
            validateElement(jsonElement, schema.rootProperty, "root")
        } catch (e: Exception) {
            ValidationResult(
                isValid = false,
                errors = listOf("Failed to parse JSON: ${e.message}")
            )
        }
    }

    /**
     * Validate a JsonElement against a schema.
     */
    fun validate(element: JsonElement, schemaId: String): ValidationResult {
        val schema = schemas[schemaId]
            ?: return ValidationResult(
                isValid = false,
                errors = listOf("Schema not found: $schemaId")
            )

        return validateElement(element, schema.rootProperty, "root")
    }

    /**
     * Attempt to parse JSON and repair common issues.
     */
    fun attemptJSONRepair(text: String): String? {
        // First, try to extract JSON from the text
        val extracted = extractJSON(text) ?: return null

        return try {
            // Validate it parses
            json.parseToJsonElement(extracted)
            extracted
        } catch (e: Exception) {
            // Try common repairs
            repairJSON(extracted)
        }
    }

    /**
     * Create a fallback JSON response for a schema.
     */
    fun createFallbackJSON(schemaId: String): JsonElement {
        val schema = schemas[schemaId] ?: return JsonObject(emptyMap())
        return createDefaultValue(schema.rootProperty)
    }

    /**
     * Get all available schema IDs.
     */
    fun availableSchemas(): Set<String> = schemas.keys.toSet()

    /**
     * Get all available grammar IDs.
     */
    fun availableGrammars(): Set<String> = grammars.keys.toSet()

    // --- Private implementation ---

    private fun validateElement(
        element: JsonElement,
        property: SchemaProperty,
        path: String
    ): ValidationResult {
        val errors = mutableListOf<String>()

        // Check type
        val actualType = getJsonType(element)
        if (actualType != property.type && property.type != JSONType.ANY) {
            // Allow null if nullable
            if (!(element is JsonNull && property.nullable)) {
                errors.add("$path: expected ${property.type}, got $actualType")
                return ValidationResult(false, errors)
            }
        }

        // Type-specific validation
        when (property.type) {
            JSONType.OBJECT -> {
                if (element is JsonObject) {
                    // Check required properties
                    property.required?.forEach { requiredProp ->
                        if (!element.containsKey(requiredProp)) {
                            errors.add("$path: missing required property '$requiredProp'")
                        }
                    }

                    // Validate each property
                    property.properties?.forEach { (propName, propSchema) ->
                        element[propName]?.let { propValue ->
                            val result = validateElement(propValue, propSchema, "$path.$propName")
                            errors.addAll(result.errors)
                        }
                    }
                }
            }

            JSONType.ARRAY -> {
                if (element is JsonArray) {
                    // Check min/max items
                    property.minItems?.let { min ->
                        if (element.size < min) {
                            errors.add("$path: array has ${element.size} items, minimum is $min")
                        }
                    }
                    property.maxItems?.let { max ->
                        if (element.size > max) {
                            errors.add("$path: array has ${element.size} items, maximum is $max")
                        }
                    }

                    // Validate items
                    property.items?.let { itemSchema ->
                        element.forEachIndexed { index, item ->
                            val result = validateElement(item, itemSchema, "$path[$index]")
                            errors.addAll(result.errors)
                        }
                    }
                }
            }

            JSONType.STRING -> {
                if (element is JsonPrimitive && element.isString) {
                    val value = element.content

                    property.minLength?.let { min ->
                        if (value.length < min) {
                            errors.add("$path: string length ${value.length} is less than minimum $min")
                        }
                    }
                    property.maxLength?.let { max ->
                        if (value.length > max) {
                            errors.add("$path: string length ${value.length} exceeds maximum $max")
                        }
                    }
                    property.enum?.let { allowed ->
                        if (value !in allowed) {
                            errors.add("$path: '$value' is not in allowed values: $allowed")
                        }
                    }
                    property.pattern?.let { pattern ->
                        if (!Regex(pattern).matches(value)) {
                            errors.add("$path: '$value' does not match pattern '$pattern'")
                        }
                    }
                }
            }

            JSONType.NUMBER, JSONType.INTEGER -> {
                if (element is JsonPrimitive) {
                    val value = element.doubleOrNull ?: element.longOrNull?.toDouble()
                    if (value != null) {
                        property.minimum?.let { min ->
                            if (value < min) {
                                errors.add("$path: $value is less than minimum $min")
                            }
                        }
                        property.maximum?.let { max ->
                            if (value > max) {
                                errors.add("$path: $value exceeds maximum $max")
                            }
                        }
                    }
                }
            }

            JSONType.BOOLEAN, JSONType.NULL, JSONType.ANY -> {
                // No additional validation needed
            }
        }

        return ValidationResult(errors.isEmpty(), errors)
    }

    private fun getJsonType(element: JsonElement): JSONType = when (element) {
        is JsonObject -> JSONType.OBJECT
        is JsonArray -> JSONType.ARRAY
        is JsonNull -> JSONType.NULL
        is JsonPrimitive -> when {
            element.isString -> JSONType.STRING
            element.booleanOrNull != null -> JSONType.BOOLEAN
            element.intOrNull != null || element.longOrNull != null -> JSONType.INTEGER
            element.floatOrNull != null || element.doubleOrNull != null -> JSONType.NUMBER
            else -> JSONType.STRING
        }
    }

    private fun createDefaultValue(property: SchemaProperty): JsonElement {
        if (property.nullable && property.default == null) {
            return JsonNull
        }

        property.default?.let { return it }

        return when (property.type) {
            JSONType.OBJECT -> {
                val props = mutableMapOf<String, JsonElement>()
                property.properties?.forEach { (name, prop) ->
                    props[name] = createDefaultValue(prop)
                }
                JsonObject(props)
            }
            JSONType.ARRAY -> JsonArray(emptyList())
            JSONType.STRING -> JsonPrimitive(property.enum?.firstOrNull() ?: "")
            JSONType.NUMBER -> JsonPrimitive(property.minimum ?: 0.0)
            JSONType.INTEGER -> JsonPrimitive((property.minimum?.toInt()) ?: 0)
            JSONType.BOOLEAN -> JsonPrimitive(false)
            JSONType.NULL -> JsonNull
            JSONType.ANY -> JsonNull
        }
    }

    private fun extractJSON(text: String): String? {
        // Try to find JSON object
        val objectMatch = Regex("""\{[\s\S]*\}""").find(text)
        if (objectMatch != null) {
            return objectMatch.value
        }

        // Try to find JSON array
        val arrayMatch = Regex("""\[[\s\S]*\]""").find(text)
        if (arrayMatch != null) {
            return arrayMatch.value
        }

        return null
    }

    private fun repairJSON(jsonText: String): String? {
        var repaired = jsonText

        // Fix unquoted keys
        repaired = repaired.replace(Regex("""(\{|,)\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*:""")) { match ->
            "${match.groupValues[1]}\"${match.groupValues[2]}\":"
        }

        // Fix single quotes to double quotes
        repaired = repaired.replace("'", "\"")

        // Fix trailing commas
        repaired = repaired.replace(Regex(""",\s*([\]}])"""), "$1")

        return try {
            json.parseToJsonElement(repaired)
            repaired
        } catch (e: Exception) {
            null
        }
    }

    private fun loadBuiltInSchemas() {
        // Keyboard schema
        schemas["keyboard.v1"] = JSONSchema(
            id = "keyboard.v1",
            version = "1.0",
            description = "Schema for keyboard suggestion responses",
            rootProperty = SchemaProperty(
                type = JSONType.OBJECT,
                properties = mapOf(
                    "suggestions" to SchemaProperty(
                        type = JSONType.ARRAY,
                        items = SchemaProperty(type = JSONType.STRING),
                        minItems = 0,
                        maxItems = 10
                    ),
                    "confidence" to SchemaProperty(
                        type = JSONType.NUMBER,
                        minimum = 0.0,
                        maximum = 1.0,
                        nullable = true
                    )
                ),
                required = listOf("suggestions")
            )
        )

        // Life coach schema
        schemas["life.coach.v1"] = JSONSchema(
            id = "life.coach.v1",
            version = "1.0",
            description = "Schema for life coach responses",
            rootProperty = SchemaProperty(
                type = JSONType.OBJECT,
                properties = mapOf(
                    "advice" to SchemaProperty(
                        type = JSONType.STRING,
                        minLength = 1
                    ),
                    "sentiment" to SchemaProperty(
                        type = JSONType.STRING,
                        enum = listOf("positive", "neutral", "supportive", "encouraging")
                    ),
                    "follow_up_questions" to SchemaProperty(
                        type = JSONType.ARRAY,
                        items = SchemaProperty(type = JSONType.STRING),
                        minItems = 0,
                        maxItems = 5,
                        nullable = true
                    ),
                    "confidence" to SchemaProperty(
                        type = JSONType.NUMBER,
                        minimum = 0.0,
                        maximum = 1.0,
                        nullable = true
                    )
                ),
                required = listOf("advice", "sentiment")
            )
        )
    }

    private fun loadBuiltInGrammars() {
        // Keyboard grammar (GBNF format)
        grammars["keyboard.v1"] = Grammar(
            id = "keyboard.v1",
            version = "1.0",
            content = """
                root ::= "{" ws "\"suggestions\"" ws ":" ws suggestions ws confidence? "}"
                suggestions ::= "[" ws (string (ws "," ws string)*)? ws "]"
                confidence ::= "," ws "\"confidence\"" ws ":" ws number
                string ::= "\"" [^"\\]* "\""
                number ::= [0-9]+ ("." [0-9]+)?
                ws ::= [ \t\n\r]*
            """.trimIndent()
        )

        // Life coach grammar
        grammars["life.coach.v1"] = Grammar(
            id = "life.coach.v1",
            version = "1.0",
            content = """
                root ::= "{" ws advice ws "," ws sentiment ws followup? confidence? "}"
                advice ::= "\"advice\"" ws ":" ws string
                sentiment ::= "\"sentiment\"" ws ":" ws sentiment_value
                sentiment_value ::= "\"positive\"" | "\"neutral\"" | "\"supportive\"" | "\"encouraging\""
                followup ::= "," ws "\"follow_up_questions\"" ws ":" ws "[" ws (string (ws "," ws string)*)? ws "]"
                confidence ::= "," ws "\"confidence\"" ws ":" ws number
                string ::= "\"" [^"\\]* "\""
                number ::= [0-9]+ ("." [0-9]+)?
                ws ::= [ \t\n\r]*
            """.trimIndent()
        )
    }

    private fun loadSchemasFromDirectory(dirPath: String) {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return

        dir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val content = file.readText()
                val schema = json.decodeFromString<JSONSchema>(content)
                schemas[schema.id] = schema
            } catch (e: Exception) {
                // Log error but continue loading other schemas
            }
        }
    }

    private fun loadGrammarsFromDirectory(dirPath: String) {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return

        dir.listFiles { file -> file.extension == "gbnf" }?.forEach { file ->
            try {
                val id = file.nameWithoutExtension
                val content = file.readText()
                grammars[id] = Grammar(id = id, version = "1.0", content = content)
            } catch (e: Exception) {
                // Log error but continue loading other grammars
            }
        }
    }
}

/**
 * Result of schema validation.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)

/**
 * JSON Schema definition.
 */
@Serializable
data class JSONSchema(
    val id: String,
    val version: String,
    val description: String = "",
    @SerialName("root")
    val rootProperty: SchemaProperty
)

/**
 * A property in a JSON schema.
 */
@Serializable
data class SchemaProperty(
    val type: JSONType,
    val properties: Map<String, SchemaProperty>? = null,
    val items: SchemaProperty? = null,
    val required: List<String>? = null,
    val nullable: Boolean = false,
    val default: JsonElement? = null,
    val enum: List<String>? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val pattern: String? = null
)

/**
 * JSON types.
 */
@Serializable
enum class JSONType {
    @SerialName("object") OBJECT,
    @SerialName("array") ARRAY,
    @SerialName("string") STRING,
    @SerialName("number") NUMBER,
    @SerialName("integer") INTEGER,
    @SerialName("boolean") BOOLEAN,
    @SerialName("null") NULL,
    @SerialName("any") ANY
}

/**
 * A GBNF grammar for constrained generation.
 */
@Serializable
data class Grammar(
    val id: String,
    val version: String,
    val content: String
)
