package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.agent.runtime.SandboxClient;
import de.augmentia.quad.core.tool.sandbox.BashSandboxTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Stellt die {@code executeBash}-Sandbox-Tool aus {@code quad-sandbox-docker} als
 * {@link BuiltInToolProvider} via CDI bereit (Stufe 06: Modularisierung).
 *
 * <p>Der Core-{@code StandardToolProvider} aggregiert alle {@link BuiltInToolProvider}
 * per CDI; dieses Modul liefert das Docker-Sandbox-Tool.
 */
@ApplicationScoped
public class SandboxToolsProvider implements BuiltInToolProvider {

    @Inject SandboxClient sandboxClient;
    @Inject ToolArgsMapper argsMapper;

    @Override
    public String providerName() {
        return "sandbox";
    }

    @Override
    public List<ToolMethod> getTools() {
        return List.of(new ReflectiveToolMethod(findMethod(BashSandboxTool.class, "executeBash"),
            new BashSandboxTool(sandboxClient), argsMapper));
    }

    private static java.lang.reflect.Method findMethod(Class<?> clazz, String methodName) {
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        throw new RuntimeException("Method not found: " + methodName);
    }
}