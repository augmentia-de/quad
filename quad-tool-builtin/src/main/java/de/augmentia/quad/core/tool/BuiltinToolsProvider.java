package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.tool.builtin.ApplyPatchTool;
import de.augmentia.quad.core.tool.builtin.FindTool;
import de.augmentia.quad.core.tool.builtin.GrepTool;
import de.augmentia.quad.core.tool.builtin.LsTool;
import de.augmentia.quad.core.tool.builtin.MultiEditTool;
import de.augmentia.quad.core.tool.builtin.ReadFileTool;
import de.augmentia.quad.core.tool.builtin.WebFetchTool;
import de.augmentia.quad.core.tool.builtin.WebSearchTool;
import de.augmentia.quad.core.tool.builtin.WriteTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides the 9 file/web built-in tools from {@code quad-tool-builtin} as
 * {@link BuiltInToolProvider} via CDI (Stage 06: modularization).
 *
 * <p>Previously, {@code StandardToolProvider} (in quad-core) registered these tools directly;
 * after extraction, the Core-Provider acts as a pure aggregator over
 * {@link jakarta.enterprise.inject.Instance}{@code <BuiltInToolProvider>} and this module
 * delivers the actual tools via CDI discovery.
 */
@ApplicationScoped
public class BuiltinToolsProvider implements BuiltInToolProvider {

    @Inject ReadFileTool readFileTool;
    @Inject WebSearchTool webSearchTool;
    @Inject FindTool findTool;
    @Inject GrepTool grepTool;
    @Inject LsTool lsTool;
    @Inject WriteTool writeTool;
    @Inject WebFetchTool webFetchTool;
    @Inject MultiEditTool multiEditTool;
    @Inject ApplyPatchTool applyPatchTool;
    @Inject ToolArgsMapper argsMapper;

    @Override
    public String providerName() {
        return "builtin";
    }

    @Override
    public List<ToolMethod> getTools() {
        List<ToolMethod> tools = new ArrayList<>();
        tools.add(createToolMethod(readFileTool, "readFile"));
        tools.add(createToolMethod(webSearchTool, "webSearch"));
        tools.add(createToolMethod(findTool, "findFiles"));
        tools.add(createToolMethod(grepTool, "grepSearch"));
        tools.add(createToolMethod(lsTool, "listDirectory"));
        tools.add(createToolMethod(writeTool, "writeFile"));
        tools.add(createToolMethod(webFetchTool, "webfetch"));
        tools.add(createToolMethod(multiEditTool, "multiEdit"));
        tools.add(createToolMethod(applyPatchTool, "applyPatch"));
        return List.copyOf(tools);
    }

    private ToolMethod createToolMethod(Object beanInstance, String methodName) {
        Method method = findMethod(beanInstance.getClass(), methodName);
        return new ReflectiveToolMethod(method, beanInstance, argsMapper);
    }

    private Method findMethod(Class<?> clazz, String methodName) {
        // Walk up the hierarchy from the base tool class so that a CDI client
        // proxy (whose annotation-less override is returned first by
        // getDeclaredMethods()) never hides the real annotated method.
        Method annotated = null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    if (m.getAnnotation(Tool.class) != null) {
                        return m;
                    }
                    if (annotated == null) {
                        annotated = m;
                    }
                }
            }
        }
        if (annotated != null) {
            return annotated;
        }
        try {
            return clazz.getMethod(methodName);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Method not found: " + methodName, e);
        }
    }
}