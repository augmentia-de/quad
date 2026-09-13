package de.augmentia.quad.core.capability;

import de.augmentia.quad.core.capability.skill.SkillRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Router for dynamic tool discovery and capability search.
 * Connects CapabilitySearch with ToolSpecification for intelligent tool selection.
 */
public class CapabilitySearchRouter {
    
    private static final Logger log = Logger.getLogger(CapabilitySearchRouter.class);
    
    private final CapabilitySearch capabilitySearch;
    private final double defaultMinScore;
    private final int defaultMaxResults;
    private final SkillRegistry skillRegistry;
    
    public CapabilitySearchRouter(CapabilitySearch capabilitySearch) {
        this(capabilitySearch, 0.70, 10, null);
    }
    
    public CapabilitySearchRouter(CapabilitySearch capabilitySearch, double defaultMinScore, int defaultMaxResults, SkillRegistry skillRegistry) {
        this.capabilitySearch = Objects.requireNonNull(capabilitySearch, "capabilitySearch must not be null");
        this.defaultMinScore = defaultMinScore;
        this.defaultMaxResults = defaultMaxResults;
        this.skillRegistry = skillRegistry;
    }
    
    /**
     * Searches and resolves relevant tools based on the prompt query and context.
     *
     * @param query the user prompt or semantic task description
     * @return List of matched ToolSpecification instances
     */
    public List<ToolSpecification> findRelevantTools(String query) {
        return findRelevantTools(query, "default");
    }
    
    /**
     * Searches and resolves relevant tools based on the prompt query and tenant context.
     *
     * @param query the user prompt or semantic task description
     * @param tenantId the tenant identifier for filtering
     * @return List of matched ToolSpecification instances
     */
    public List<ToolSpecification> findRelevantTools(String query, String tenantId) {
        if (query == null || query.isBlank()) {
            log.warn("Empty query provided to CapabilitySearchRouter. Returning empty list.");
            return List.of();
        }
        
        try {
            List<Capability> capabilities = capabilitySearch.search(query, defaultMaxResults, tenantId);
            
            return capabilities.stream()
                    .filter(c -> c.score() >= defaultMinScore)
                    .map(this::capabilityToToolSpecification)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Error during tool routing for query: " + query, e);
            return List.of();
        }
    }
    
    private ToolSpecification capabilityToToolSpecification(Capability capability) {
        if (capability.methodRef() == null || capability.methodRef().isBlank()) {
            return null;
        }
        
        try {
            return ToolSpecification.builder()
                    .name(capability.name())
                    .description(capability.description())
                    .build();
        } catch (Exception e) {
            log.error("Failed to convert capability to tool specification: " + capability.name(), e);
            return null;
        }
    }
}