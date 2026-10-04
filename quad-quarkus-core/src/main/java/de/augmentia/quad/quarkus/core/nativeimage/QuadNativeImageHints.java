package de.augmentia.quad.quarkus.core.nativeimage;

import de.augmentia.quad.core.tool.ReflectiveToolMethod;
import de.augmentia.quad.core.tool.builtin.ApplyPatchTool;
import de.augmentia.quad.core.tool.builtin.FindTool;
import de.augmentia.quad.core.tool.builtin.GrepTool;
import de.augmentia.quad.core.tool.builtin.LsTool;
import de.augmentia.quad.core.tool.builtin.MultiEditTool;
import de.augmentia.quad.core.tool.builtin.ReadFileTool;
import de.augmentia.quad.core.tool.builtin.WebFetchTool;
import de.augmentia.quad.core.tool.builtin.WebSearchTool;
import de.augmentia.quad.core.tool.builtin.WriteTool;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image registration for the reflective tool invocation.
 *
 * <p>The tool wiring in the core is entirely reflective:
 * {@code ToolCatalog} and {@code QuadToolRegistry.registerFromAgent} iterate over
 * {@code getDeclaredMethods()}, filter on the {@code @Tool} annotation and
 * call the method later via {@code Method.invoke()}. In a GraalVM image
 * this reflection metadata binding only exists if it was registered —
 * without it {@code getDeclaredMethods()} returns nothing and the tool catalog stays empty.
 *
 * <p>Deliberately a separate class with class-literal references: that keeps the list of
 * tools in one place and makes a missing entry visible in review, instead of
 * hiding it in a configuration file.
 *
 * <p><b>Remaining gap:</b> agent classes with their own {@code @Tool} methods are
 * application-specific and lie outside this module. The application registers them
 * itself, e.g. {@code @RegisterForReflection(targets = MyAgent.class)}.
 */
@RegisterForReflection(
    targets = {
        ReadFileTool.class,
        WriteTool.class,
        MultiEditTool.class,
        ApplyPatchTool.class,
        GrepTool.class,
        FindTool.class,
        LsTool.class,
        WebSearchTool.class,
        WebFetchTool.class,
        ReflectiveToolMethod.class
    },
    methods = true)
public final class QuadNativeImageHints {

    private QuadNativeImageHints() {
    }
}