package de.augmentia.quad.core.config;

import de.augmentia.quad.core.events.AgentFinishedEvent;
import dev.langchain4j.model.output.structured.Description;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StructuredOutputConfigTest {

    record SimpleRecord(String name, int age) {}
    record NestedRecord(String id, Details details) {}
    record Details(String city, String country) {}
    record ListRecord(List<String> tags) {}
    record DateRecord(LocalDate created, LocalDateTime updated) {}
    record BoolRecord(boolean active, Double score) {}
    enum Priority { LOW, MEDIUM, HIGH }
    record EnumRecord(Priority priority) {}
    record OptionalRecord(Optional<String> note) {}
    record DescriptionRecord(
        @Description("Name der Stadt") String city,
        @Description("Temperatur in Grad Celsius") double temperature) {}
    record ListOfRecords(List<NestedRecord> items) {}

    @Test
    void staticModelCreatesConfig() {
        var config = StructuredOutputConfig.staticModel(SimpleRecord.class);
        assertFalse(config.dynamicSchema());
        assertEquals(SimpleRecord.class, config.outputClass());
        assertNull(config.jsonSchema());
        assertNotNull(config.forcePrompt());
        assertTrue(config.isEnabled());
    }

    @Test
    void staticModelWithCustomForcePrompt() {
        var config = StructuredOutputConfig.staticModel(SimpleRecord.class, "Custom prompt");
        assertEquals("Custom prompt", config.forcePrompt());
    }

    @Test
    void dynamicSchemaCreatesConfig() {
        var schema = "{\"type\": \"object\"}";
        var config = StructuredOutputConfig.dynamicSchema(schema);
        assertTrue(config.dynamicSchema());
        assertNull(config.outputClass());
        assertEquals(schema, config.jsonSchema());
        assertTrue(config.isEnabled());
    }

    @Test
    void dynamicSchemaWithCustomForcePrompt() {
        var config = StructuredOutputConfig.dynamicSchema("{\"type\": \"object\"}", "Force it!");
        assertEquals("Force it!", config.forcePrompt());
    }

    @Test
    void isEnabledReturnsFalseForNull() {
        assertFalse(new StructuredOutputConfig(false, null, null, "prompt").isEnabled());
        assertFalse(new StructuredOutputConfig(true, null, null, "prompt").isEnabled());
        assertFalse(new StructuredOutputConfig(true, null, "", "prompt").isEnabled());
    }

    @Test
    void effectiveSchemaGeneratesFromRecord() {
        var config = StructuredOutputConfig.staticModel(SimpleRecord.class);
        var schema = config.effectiveSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"name\""));
        assertTrue(schema.contains("\"age\""));
        assertTrue(schema.contains("integer"));
        assertTrue(schema.contains("\"required\":[\"name\",\"age\"]"),
            "record components must be required: " + schema);
    }

    @Test
    void effectiveJsonSchemaCarriesSchemaName() {
        var config = StructuredOutputConfig.staticModel(SimpleRecord.class);
        var schema = config.effectiveJsonSchema();
        assertNotNull(schema);
        assertEquals("SimpleRecord", schema.name());
        assertNotNull(schema.rootElement());
    }

    @Test
    void effectiveSchemaHandlesNestedRecords() {
        var config = StructuredOutputConfig.staticModel(NestedRecord.class);
        var schema = config.effectiveSchema();
        assertTrue(schema.contains("\"id\""));
        assertTrue(schema.contains("\"details\""));
        assertTrue(schema.contains("\"city\""));
        assertTrue(schema.contains("\"country\""));
        assertTrue(schema.contains("\"required\":[\"id\",\"details\"]"), schema);
        assertTrue(schema.contains("\"required\":[\"city\",\"country\"]"), schema);
    }

    @Test
    void effectiveSchemaRequiresListOfRecordFields() {
        var config = StructuredOutputConfig.staticModel(ListOfRecords.class);
        var schema = config.effectiveSchema();
        assertTrue(schema.contains("\"required\":[\"items\"]"), schema);
    }

    @Test
    void effectiveSchemaSkipsOptionalFromRequired() {
        var config = StructuredOutputConfig.staticModel(OptionalRecord.class);
        var schema = config.effectiveSchema();
        assertTrue(schema.contains("\"required\":[]"), schema);
    }

    @Test
    void effectiveSchemaHandlesListFields() {
        var config = StructuredOutputConfig.staticModel(ListRecord.class);
        var schema = config.effectiveSchema();
        assertTrue(schema.contains("\"tags\""));
        assertTrue(schema.contains("\"array\""));
        assertTrue(schema.contains("\"string\""));
    }

    @Test
    void effectiveSchemaHandlesDateFields() {
        var config = StructuredOutputConfig.staticModel(DateRecord.class);
        var schema = config.effectiveSchema();
        assertTrue(schema.contains("\"date\""));
        assertTrue(schema.contains("\"date-time\""));
        assertFalse(schema.contains("year"), "LocalDate must not be mapped to an object");
        assertFalse(schema.contains("nano"), "LocalDateTime must not be mapped to an object");
    }

    @Test
    void effectiveSchemaHandlesBoolAndNumberFields() {
        var config = StructuredOutputConfig.staticModel(BoolRecord.class);
        var schema = config.effectiveSchema();
        assertTrue(schema.contains("\"boolean\""));
        assertTrue(schema.contains("\"number\""));
    }

    @Test
    void effectiveSchemaHandlesEnums() {
        var config = StructuredOutputConfig.staticModel(EnumRecord.class);
        var schema = config.effectiveSchema();
        assertTrue(schema.contains("\"enum\""));
        assertTrue(schema.contains("LOW"));
        assertTrue(schema.contains("MEDIUM"));
        assertTrue(schema.contains("HIGH"));
    }

    @Test
    void effectiveSchemaHandlesOptional() {
        var config = StructuredOutputConfig.staticModel(OptionalRecord.class);
        var schema = config.effectiveSchema();
        assertTrue(schema.contains("\"note\""), "Optional field must be present as property");
    }

    @Test
    void effectiveSchemaHandlesListOfRecords() {
        var config = StructuredOutputConfig.staticModel(ListOfRecords.class);
        var schema = config.effectiveSchema();
        assertTrue(schema.contains("\"items\""));
        assertTrue(schema.contains("\"array\""));
        assertTrue(schema.contains("\"city\""));
    }

    @Test
    void effectiveSchemaHonorsDescriptions() {
        var config = StructuredOutputConfig.staticModel(DescriptionRecord.class);
        var schema = config.effectiveSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("Name der Stadt"));
        assertTrue(schema.contains("Temperatur in Grad Celsius"));
    }

    @Test
    void effectiveSchemaReturnsRawForDynamic() {
        var raw = "{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}}}";
        var config = StructuredOutputConfig.dynamicSchema(raw);
        assertEquals(raw, config.effectiveSchema());
    }

    @Test
    void effectiveSchemaReturnsNullForNullOutputClass() {
        var config = new StructuredOutputConfig(false, null, null, "prompt");
        assertNull(config.effectiveSchema());
    }

    @Test
    void effectiveJsonSchemaForDynamicWrapsRaw() {
        var raw = "{\"type\": \"object\"}";
        var config = StructuredOutputConfig.dynamicSchema(raw);
        var schema = config.effectiveJsonSchema();
        assertNotNull(schema);
        assertEquals("structured", schema.name());
    }

    @Test
    void backwardCompatibleConstructors() {
        var event = new AgentFinishedEvent(
            "s1", java.time.Instant.now(), "answer");
        assertNull(event.structuredOutput());
        assertEquals("answer", event.finalAnswer());
    }
}