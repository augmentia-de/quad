package de.augmentia.quad.core.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import de.augmentia.quad.core.capability.CapabilitySearch;
import de.augmentia.quad.core.capability.CapabilitySearchAgent;
import de.augmentia.quad.core.capability.skill.SkillRegistry;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Aggregating standard tool provider (SPI).
 *
 * <p>Instead of pulling in concrete Builtin/Sandbox classes directly (these were moved
 * into their own modules during modularization (Level 06): {@code quad-tool-builtin},
 * {@code quad-sandbox-docker}), this provider delegates to all {@link BuiltInToolProvider}
 * instances discovered via CDI and only adds the core's own
 * {@link CapabilitySearchTool} (when a {@link ChatModel} can be resolved).
 *
 * <p>MCP-Tools (names starting with {@code mcp_}) are not included here - they are
 * registered separately by {@code quad-quarkus} via the MCP-Manager (existing behavior).
 */
@ApplicationScoped
public class StandardToolProvider implements BuiltInToolProvider {

    @Inject Instance<BuiltInToolProvider> toolProviders;
    @Inject Instance<ChatModel> chatModelInstance;
    @Inject Instance<CapabilitySearch> capabilitySearchInstance;
    @Inject Instance<SkillRegistry> skillRegistryInstance;

    @Override
    public String providerName() {
        return "standard";
    }

    @Override
    public List<ToolMethod> getTools() {
        List<ToolMethod> tools = new ArrayList<>();
        if (toolProviders != null) {
            for (BuiltInToolProvider provider : toolProviders) {
                // providerName() == "standard" is this bean itself (present as a ClientProxy) -
                // otherwise infinite recursion over getTools().
                if (providerName().equals(provider.providerName())) {
                    continue;
                }
                for (ToolMethod tm : provider.getTools()) {
                    String name = tm.spec().name();
                    if (name != null && name.startsWith("mcp_")) {
                        continue; // MCP is registered separately via McpManagerService
                    }
                    tools.add(tm);
                }
            }
        }

        if (chatModelInstance != null && chatModelInstance.isResolvable()) {
            ChatModel chatModel = chatModelInstance.get();
            CapabilitySearch capSearch = capabilitySearchInstance != null
                && capabilitySearchInstance.isResolvable() ? capabilitySearchInstance.get() : null;
            SkillRegistry skillRegistry = skillRegistryInstance != null
                && skillRegistryInstance.isResolvable() ? skillRegistryInstance.get() : null;
            CapabilitySearchAgent searchAgent = new CapabilitySearchAgent(chatModel);
            tools.add(new CapabilitySearchTool(capSearch, searchAgent, skillRegistry, 20, 3));
        }

        return List.copyOf(tools);
    }

    public Optional<ToolMethod> getToolByName(String name) {
        return getTools().stream()
            .filter(tool -> tool.spec().name().equals(name))
            .findFirst();
    }
}