package de.augmentia.quad.core.scope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FieldExtractorTest {

    record Details(String city, String country) {}
    record TaskAnalysis(String complexity, List<String> recommendedTools,
                        Details details, Nested nested) {}
    record Nested(int depth) {}

    private static final TaskAnalysis ANALYSIS = new TaskAnalysis("complex",
        List.of("find_files", "grep_search"), new Details("Berlin", "DE"), new Nested(3));

    @Test
    void extractDirectField() {
        String complexity = FieldExtractor.extract(ANALYSIS, "complexity");
        assertEquals("complex", complexity);
    }

    @Test
    void extractNestedRecordField() {
        String city = FieldExtractor.extract(ANALYSIS, "details.city");
        assertEquals("Berlin", city);
        String country = FieldExtractor.extract(ANALYSIS, "details.country");
        assertEquals("DE", country);
    }

    @Test
    void extractListIndex() {
        String first = FieldExtractor.extract(ANALYSIS, "recommendedTools[0]");
        assertEquals("find_files", first);
        String second = FieldExtractor.extract(ANALYSIS, "recommendedTools[1]");
        assertEquals("grep_search", second);
    }

    @Test
    void extractDeeplyNested() {
        int depth = FieldExtractor.extract(ANALYSIS, "nested.depth");
        assertEquals(3, depth);
    }

    @Test
    void extractUnknownFieldReturnsNull() {
        assertNull(FieldExtractor.extract(ANALYSIS, "unknown"));
        assertNull(FieldExtractor.extract(ANALYSIS, "details.unknown"));
    }

    @Test
    void extractOutOfBoundsIndexReturnsNull() {
        assertNull(FieldExtractor.extract(ANALYSIS, "recommendedTools[5]"));
    }

    @Test
    void extractFromMap() {
        var map = Map.of("a", Map.of("b", "value"));
        String value = FieldExtractor.extract(map, "a.b");
        assertEquals("value", value);
    }

    @Test
    void extractFromPojoViaGetter() {
        class Pojo {
            String getX() { return "pojo-value"; }
        }
        String x = FieldExtractor.extract(new Pojo(), "x");
        assertEquals("pojo-value", x);
        assertNull(FieldExtractor.extract(new Pojo(), "missing"));
    }

    @Test
    void extractNullSafe() {
        assertNull(FieldExtractor.extract(null, "anything"));
        assertNull(FieldExtractor.extract(ANALYSIS, null));
        assertNull(FieldExtractor.extract(ANALYSIS, "  "));
    }

    @Test
    void extractTextRendersValue() {
        assertEquals("complex", FieldExtractor.extractText(ANALYSIS, "complexity"));
        assertEquals("3", FieldExtractor.extractText(ANALYSIS, "nested.depth"));
        assertEquals("", FieldExtractor.extractText(ANALYSIS, "unknown"));
    }
}