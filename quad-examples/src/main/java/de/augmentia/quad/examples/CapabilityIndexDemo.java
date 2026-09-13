package de.augmentia.quad.examples;

import de.augmentia.quad.core.capability.Capability;
import de.augmentia.quad.core.capability.CapabilityIndex;
import de.augmentia.quad.core.capability.HnswCapabilityIndex;
import de.augmentia.quad.core.capability.QdrantCapabilityIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;

/**
 * Demonstrates capability indexing with multiple backend implementations.
 */
public class CapabilityIndexDemo {

    public static void main(String... args) {
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("=== Capability Index Demo ===\n");

        System.out.println("1. In-Memory Index (HnswCapabilityIndex):");
        demoIndex(new HnswCapabilityIndex(), mapper, "in-memory");

        System.out.println("\n2. PostgreSQL Index (PgVectorCapabilityIndex - requires DataSource):");
        System.out.println("   (Commented out - requires database connection)");
        // demoIndex(new PgVectorCapabilityIndex(dataSource, mapper, "capability_index"), mapper, "postgres");

        System.out.println("\n3. Qdrant Index (QdrantCapabilityIndex):");
        demoIndex(new QdrantCapabilityIndex(mapper), mapper, "in-memory-qdrant");

        System.out.println("\n=== Tenant Filtering Demo ===");
        demoTenantFiltering(new HnswCapabilityIndex(), mapper);
    }

    private static void demoIndex(CapabilityIndex index, ObjectMapper mapper, String name) {
        Capability cap1 = new Capability("read_file", "Read files from disk", "ReadFileTool::read", "builtin", "FILE", Set.of(), 0.9);
        Capability cap2 = new Capability("web_search", "Search the web", "WebSearchTool::search", "builtin", "WEB", Set.of("tenant-a", "tenant-b"), 0.8);

        index.index(cap1);
        index.index(cap2);

        System.out.println("   Indexed 2 capabilities");
        List<Capability> results = index.search("read", 10);
        System.out.println("   Search 'read': " + results.size() + " result(s)");
    }

    private static void demoTenantFiltering(HnswCapabilityIndex index, ObjectMapper mapper) {
        Capability publicCap = new Capability("public_tool", "Publicly available tool", "Tool::method", "builtin", "PUBLIC", Set.of(), 0.5);
        Capability restrictedCap = new Capability("secret_tool", "Secret internal tool", "InternalTool::method", "builtin", "SECRET", Set.of("tenant-a"), 0.9);

        index.clear();
        index.index(publicCap);
        index.index(restrictedCap);

        List<Capability> all = index.search("tool", 10, null);
        List<Capability> tenantAResults = index.search("tool", 10, "tenant-a");
        List<Capability> tenantBResults = index.search("tool", 10, "tenant-b");

        System.out.println("   Total capabilities: " + all.size());
        System.out.println("   Tenant A can access: " + tenantAResults.size() + " (including tenant-specific)");
        System.out.println("   Tenant B can access: " + tenantBResults.size() + " (only public)");
    }
}