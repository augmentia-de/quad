package de.augmentia.quad.core.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QdrantCapabilityIndexTest {

    private ObjectMapper objectMapper;
    private QdrantCapabilityIndex index;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        index = new QdrantCapabilityIndex(objectMapper);
    }

    @Test
    void shouldIndexCapability() {
        Capability capability = new Capability(
            "test-capability",
            "Test description",
            "com.example.Method",
            "builtin",
            "local",
            Set.of("tenant-1"),
            0.85
        );

        assertDoesNotThrow(() -> index.index(capability));
    }

    @Test
    void shouldRemoveCapability() {
        Capability capability = new Capability("remove-me", "desc", "ref", "src", "type", Set.of(), 0.5);

        index.index(capability);
        assertDoesNotThrow(() -> index.remove("remove-me"));
    }

    @Test
    void shouldSearchAllCapabilities() {
        Capability cap1 = new Capability("cap-1", "desc 1", "ref1", "src1", "type", Set.of(), 0.9);
        Capability cap2 = new Capability("cap-2", "desc 2", "ref2", "src2", "type", Set.of(), 0.8);

        index.index(cap1);
        index.index(cap2);

        List<Capability> results = index.search("test", 10);

        assertEquals(2, results.size());
    }

    @Test
    void shouldSearchWithTenantFilter() {
        Capability cap1 = new Capability("cap-1", "desc", "ref", "src", "type", Set.of("tenant-a"), 0.9);
        Capability cap2 = new Capability("cap-2", "desc", "ref", "src", "type", Set.of("tenant-b"), 0.8);
        Capability cap3 = new Capability("cap-3", "desc", "ref", "src", "type", Set.of(), 0.7);

        index.index(cap1);
        index.index(cap2);
        index.index(cap3);

        List<Capability> resultsTenantA = index.search("test", 10, "tenant-a");
        List<Capability> resultsTenantB = index.search("test", 10, "tenant-b");
        List<Capability> resultsNoTenant = index.search("test", 10, "tenant-c");

        assertTrue(resultsTenantA.stream().anyMatch(c -> c.name().equals("cap-1")));
        assertTrue(resultsTenantB.stream().anyMatch(c -> c.name().equals("cap-2")));
        assertTrue(resultsNoTenant.stream().anyMatch(c -> c.name().equals("cap-3")));
    }

    @Test
    void shouldLimitResults() {
        for (int i = 0; i < 5; i++) {
            index.index(new Capability("cap-" + i, "desc", "ref", "src", "type", Set.of(), (double) i));
        }

        List<Capability> results = index.search("test", 2);

        assertEquals(2, results.size());
    }

    @Test
    void shouldClearAllCapabilities() {
        index.index(new Capability("cap-1", "desc", "ref", "src", "type", Set.of(), 0.5));
        index.index(new Capability("cap-2", "desc", "ref", "src", "type", Set.of(), 0.6));

        index.clear();

        List<Capability> results = index.search("test", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldHandleEmptyIndex() {
        List<Capability> results = index.search("nonexistent", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldHandleSearchWithNullTenant() {
        index.index(new Capability("cap-universal", "desc", "ref", "src", "type", Set.of(), 0.5));

        List<Capability> results = index.search("test", 10, null);
        assertTrue(results.stream().anyMatch(c -> c.name().equals("cap-universal")));
    }
}