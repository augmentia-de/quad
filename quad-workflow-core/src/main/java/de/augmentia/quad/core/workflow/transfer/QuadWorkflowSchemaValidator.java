package de.augmentia.quad.core.workflow.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deterministic schema validator for the QUAD workflow export/import.
 *
 * <p>Validates documents against the embedded JSON schemas
 * ({@code quad.workflow.v1.schema.json} and {@code quad.workflow.v2.schema.json},
 * JSON-Schema-Draft-2020-12). It deliberately implements only the keyword subset that the
 * schemas actually use (type, properties, required, additionalProperties, items, enum,
 * const, bounds/format checks, local {@code $ref} incl. {@code $defs}). The schema remains
 * the single source of truth for the rules — this interpreter reads the same file that any
 * full JSON-Schema tooling (e.g. UI/CLI) can also use.</p>
 *
 * <p>{@link #validate(JsonNode)} validates against the v1 schema (backward compatible);
 * {@link #validateV2(JsonNode)} validates against the v2 schema. {@code $ref} resolution is
 * relative to the schema that was passed in, so a document is always checked against exactly
 * one schema generation.</p>
 *
 * <p>The check is deterministic: same input ⇒ exactly the same error list.
 * {@code format: "date-time"} is checked tolerantly (RFC3339 with offset/Z
 * OR local timestamps as produced by {@code LocalDateTime.toString()}),
 * so that export/import roundtrips of both backends do not fail on timezone representation.
 */
public final class QuadWorkflowSchemaValidator {

    /** v1 schema file on the classpath (backward compatible default). */
    public static final String SCHEMA_V1_RESOURCE = "quad/schema/quad.workflow.v1.schema.json";

    /** v2 schema file on the classpath (typed, OWS-aligned). */
    public static final String SCHEMA_V2_RESOURCE = "quad/schema/quad.workflow.v2.schema.json";

    /** @deprecated use {@link #SCHEMA_V1_RESOURCE}. */
    @Deprecated
    public static final String SCHEMA_RESOURCE = SCHEMA_V1_RESOURCE;

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public static final int SUPPORTED_SCHEMA_VERSION_V2 = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QuadWorkflowSchemaValidator() {}

    /** Reads + parses the v1 schema from the classpath. */
    public static JsonNode loadSchema() {
        return loadSchema(SCHEMA_V1_RESOURCE);
    }

    /** Reads + parses a schema resource from the classpath. */
    public static JsonNode loadSchema(String resource) {
        try (InputStream in = QuadWorkflowSchemaValidator.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource not found: " + resource);
            }
            return MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load workflow schema: " + resource, e);
        }
    }

    public static boolean isSchemaVersionSupported(JsonNode doc) {
        return doc != null && doc.path("schemaVersion").asInt() == SUPPORTED_SCHEMA_VERSION;
    }

    public static boolean isSchemaVersionSupportedV2(JsonNode doc) {
        return doc != null && doc.path("schemaVersion").asInt() == SUPPORTED_SCHEMA_VERSION_V2;
    }

    /**
     * Validates a document against the v1 schema (backward compatible).
     *
     * @return empty list on success; otherwise deterministically ordered messages in the
     *         format {@code <JSON-Pointer>: <rule violation>}
     */
    public static List<String> validate(JsonNode doc) {
        return validate(doc, loadSchema(SCHEMA_V1_RESOURCE));
    }

    /** Validates a document against the v2 schema (typed, OWS-aligned). */
    public static List<String> validateV2(JsonNode doc) {
        return validate(doc, loadSchema(SCHEMA_V2_RESOURCE));
    }

    /** Validates against an explicitly passed schema (tests / future versions). */
    public static List<String> validate(JsonNode doc, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        if (schema == null || doc == null) {
            errors.add("$: missing document or schema");
            return errors;
        }
        apply(schema, schema, doc, "$", errors);
        return errors;
    }

    /**
     * @param root  the schema the document is validated against; {@code $ref} is resolved
     *              relative to it
     * @param schema the current (sub)schema
     */
    private static void apply(JsonNode root, JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema == null || schema.isNull() || schema.isBoolean()) {
            return;
        }
        if (schema.hasNonNull("$ref")) {
            JsonNode target = resolveRef(root, schema.get("$ref").asText());
            if (target == null) {
                fail(errors, path, "unresolved $ref " + schema.get("$ref").asText());
                return;
            }
            apply(root, target, value, path, errors);
            return; // Draft-2020-12: $ref ersetzt die Geschwister-Keywords
        }
        if (schema.has("allOf")) {
            for (JsonNode sub : schema.get("allOf")) {
                apply(root, sub, value, path, errors);
            }
        }
        if (schema.has("oneOf")) {
            applyOneOf(root, schema.get("oneOf"), value, path, errors);
        }
        if (schema.has("type")) {
            JsonNode types = schema.get("type");
            boolean ok = types.isArray()
                ? anyOf(types, t -> matchesType(t.asText(), value))
                : matchesType(types.asText(), value);
            if (!ok) {
                String wanted = types.isArray() ? types.toString() : types.asText();
                fail(errors, path, "expected type " + wanted + " but got " + typeName(value));
            }
        }
        if (schema.has("const")) {
            if (!schema.get("const").equals(value)) {
                fail(errors, path, "expected constant " + schema.get("const") + " but got " + value);
            }
        }
        if (schema.has("enum")) {
            boolean ok = false;
            for (JsonNode e : schema.get("enum")) {
                if (e.equals(value)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                fail(errors, path, "value not in enum " + schema.get("enum"));
            }
        }
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isObject() && schema.has("properties")) {
            JsonNode props = schema.get("properties");
            var fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                JsonNode propSchema = props.get(f.getKey());
                if (propSchema != null) {
                    apply(root, propSchema, f.getValue(), path + "." + f.getKey(), errors);
                }
            }
            if (schema.has("additionalProperties") && schema.get("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean()) {
                for (String key : iterableKeys(value)) {
                    if (!props.has(key)) {
                        fail(errors, path, "additional property '" + key + "' is not allowed");
                    }
                }
            }
        }
        if (value.isObject() && schema.has("required")) {
            for (JsonNode r : schema.get("required")) {
                String key = r.asText();
                if (!value.has(key) || value.get(key).isNull()) {
                    fail(errors, path, "missing required property '" + key + "'");
                }
            }
        }
        if (value.isArray() && schema.has("items")) {
            JsonNode items = schema.get("items");
            if (items.isObject()) {
                int i = 0;
                for (JsonNode item : value) {
                    apply(root, items, item, path + "[" + i + "]", errors);
                    i++;
                }
            } else if (items.isArray()) {
                int i = 0;
                for (JsonNode item : value) {
                    if (i < items.size()) {
                        apply(root, items.get(i), item, path + "[" + i + "]", errors);
                    }
                    i++;
                }
            }
        }
        applyNumeric(schema, value, path, errors);
        applyString(schema, value, path, errors);
        applyArray(schema, value, path, errors);
    }

    /**
     * Discriminated union (v2 node variants). Exactly one branch must validate without
     * errors. When none does, the branch with the fewest errors (lowest index on ties) is
     * surfaced so the message stays deterministic and close to the intended variant.
     */
    private static void applyOneOf(JsonNode root, JsonNode branches, JsonNode value, String path, List<String> errors) {
        int matching = 0;
        List<List<String>> branchErrors = new ArrayList<>();
        for (JsonNode sub : branches) {
            List<String> e = new ArrayList<>();
            apply(root, sub, value, path, e);
            branchErrors.add(e);
            if (e.isEmpty()) matching++;
        }
        if (matching > 1) {
            fail(errors, path, "matches more than one 'oneOf' variant");
        } else if (matching == 0 && !branchErrors.isEmpty()) {
            int best = 0;
            for (int i = 1; i < branchErrors.size(); i++) {
                if (branchErrors.get(i).size() < branchErrors.get(best).size()) {
                    best = i;
                }
            }
            errors.addAll(branchErrors.get(best));
        }
    }

    private static void applyNumeric(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isNumber()) return;
        double v = value.asDouble();
        if (schema.has("minimum") && v < schema.get("minimum").asDouble()) {
            fail(errors, path, "must be >= " + schema.get("minimum").asDouble());
        }
        if (schema.has("exclusiveMinimum") && v <= schema.get("exclusiveMinimum").asDouble()) {
            fail(errors, path, "must be > " + schema.get("exclusiveMinimum").asDouble());
        }
        if (schema.has("maximum") && v > schema.get("maximum").asDouble()) {
            fail(errors, path, "must be <= " + schema.get("maximum").asDouble());
        }
        if (schema.has("exclusiveMaximum") && v >= schema.get("exclusiveMaximum").asDouble()) {
            fail(errors, path, "must be < " + schema.get("exclusiveMaximum").asDouble());
        }
    }

    private static void applyString(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isTextual()) return;
        String s = value.asText();
        if (schema.has("minLength") && s.length() < schema.get("minLength").asInt()) {
            fail(errors, path, "must be at least " + schema.get("minLength").asInt() + " characters");
        }
        if (schema.has("maxLength") && s.length() > schema.get("maxLength").asInt()) {
            fail(errors, path, "must be at most " + schema.get("maxLength").asInt() + " characters");
        }
        if (schema.has("pattern")) {
            try {
                if (!Pattern.compile(schema.get("pattern").asText()).matcher(s).matches()) {
                    fail(errors, path, "does not match pattern " + schema.get("pattern").asText());
                }
            } catch (PatternSyntaxException ignored) {
                // deterministisch: ungueltiges Muster im Schema wird ignoriert
            }
        }
        if (schema.has("format") && "date-time".equals(schema.get("format").asText()) && !isLenientDateTime(s)) {
            fail(errors, path, "is not a valid date-time (RFC3339 or local timestamp)");
        }
    }

    private static void applyArray(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isArray()) return;
        int n = value.size();
        if (schema.has("minItems") && n < schema.get("minItems").asInt()) {
            fail(errors, path, "must contain at least " + schema.get("minItems").asInt() + " items");
        }
        if (schema.has("maxItems") && n > schema.get("maxItems").asInt()) {
            fail(errors, path, "must contain at most " + schema.get("maxItems").asInt() + " items");
        }
        if (schema.has("uniqueItems") && schema.get("uniqueItems").asBoolean()) {
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (value.get(i).equals(value.get(j))) {
                        fail(errors, path, "must contain unique items (duplicate at index " + i + " and " + j + ")");
                    }
                }
            }
        }
    }

    private static boolean anyOf(JsonNode types, Predicate<JsonNode> test) {
        for (JsonNode t : types) {
            if (test.test(t)) return true;
        }
        return false;
    }

    private static boolean matchesType(String type, JsonNode value) {
        if (value == null || value.isNull()) return "null".equals(type);
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static String typeName(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        if (value.isTextual()) return "string";
        if (value.isBoolean()) return "boolean";
        if (value.isIntegralNumber()) return "integer";
        if (value.isNumber()) return "number";
        return "unknown";
    }

    private static boolean isLenientDateTime(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            // RFC3339 (OffsetDateTime) — String "2026-09-08T10:12:13Z" oder mit +02:00
            OffsetDateTime.parse(s);
            return true;
        } catch (DateTimeParseException ignored) {
            // lokale Timestamps wie LocalDateTime.toString() ("2026-09-08T10:12:13.123")
        }
        try {
            java.time.LocalDateTime.parse(s);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static JsonNode resolveRef(JsonNode root, String ref) {
        if (ref == null || !ref.startsWith("#")) return null;
        String pointer = ref.substring(1); // "/$defs/agent"
        String[] parts = pointer.split("/");
        JsonNode cur = root;
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i];
            if (!seg.isEmpty()) {
                cur = cur == null ? null : cur.get(unescape(seg));
                if (cur == null) return null;
            }
        }
        return cur;
    }

    private static String unescape(String seg) {
        return seg.replace("~1", "/").replace("~0", "~");
    }

    private static void fail(List<String> errors, String path, String message) {
        String javaStyle = toJavaPointer(path);
        errors.add(javaStyle + ": " + message);
    }

    private static String toJavaPointer(String path) {
        if ("$".equals(path)) return "$";
        return path.replace("$", "");
    }

    private static List<String> iterableKeys(JsonNode obj) {
        List<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
