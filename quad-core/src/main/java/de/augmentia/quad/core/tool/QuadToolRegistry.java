package de.augmentia.quad.core.tool;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.annotation.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class QuadToolRegistry {
    private static final Logger log = Logger.getLogger(QuadToolRegistry.class);
    private final Map<String, ToolMethod> tools = new LinkedHashMap<>();
    private final Map<Class<?>, List<String>> agentTools = new HashMap<>();
    private final ToolArgsMapper argsMapper;

    public QuadToolRegistry() {
        this.argsMapper = null;
    }

    public QuadToolRegistry(ToolArgsMapper argsMapper) {
        this.argsMapper = argsMapper;
    }

    @Inject
    public QuadToolRegistry(ToolArgsMapper argsMapper, Instance<BuiltInToolProvider> providers) {
        this.argsMapper = argsMapper;
        Set<String> registered = new HashSet<>();
        for (BuiltInToolProvider provider : providers) {
            for (ToolMethod tm : provider.getTools()) {
                if (registered.add(tm.spec().name())) {
                    register(tm.spec().name(), tm);
                } else {
                    log.warn("Tool name collision, provider '" + provider.providerName() + "' skipped: " + tm.spec().name());
                }
            }
        }
    }

    public void register(String name, ToolMethod method) {
        tools.put(name, method);
    }

    public void registerFromAgent(Agent agent) {
        for (Method m : agent.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                String name = m.getName();
                ToolMethod tm = new ReflectiveToolMethod(m, agent, argsMapper);
                tools.put(name, tm);
                agentTools.computeIfAbsent(agent.getClass(), k -> new ArrayList<>()).add(name);
            }
        }
    }

    public List<ToolMethod> getStaticToolsForAgent(Agent agent) {
        List<String> names = agentTools.get(agent.getClass());
        if (names == null) return List.of();
        return names.stream().map(tools::get).filter(Objects::nonNull).toList();
    }

    public void registerBuiltIn(BuiltInToolProvider provider) {
        for (ToolMethod tm : provider.getTools()) {
            tools.put(tm.spec().name(), tm);
        }
    }

    public void remove(String name) {
        tools.remove(name);
        agentTools.values().forEach(list -> list.remove(name));
    }

    public void notifyToolSetChanged(Set<String> added, Set<String> removed) {
        // CDI Event<ToolSetChangedEvent> would be fired here; kept minimal for now.
    }

    public ToolMethod get(String name) {
        return tools.get(name);
    }

    public List<ToolMethod> getByNames(Collection<String> names) {
        return names.stream().map(tools::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public List<ToolMethod> getAll() {
        return List.copyOf(tools.values());
    }

    public QuadToolRegistry withOnly(Set<String> names) {
        QuadToolRegistry filtered = new QuadToolRegistry(argsMapper);
        for (String name : names) {
            ToolMethod tm = tools.get(name);
            if (tm != null) filtered.register(name, tm);
        }
        return filtered;
    }

    public List<?> getSpecifications() {
        return tools.values().stream().map(ToolMethod::spec).toList();
    }

}
