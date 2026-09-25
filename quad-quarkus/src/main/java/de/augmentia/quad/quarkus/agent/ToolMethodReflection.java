package de.augmentia.quad.quarkus.agent;

import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.tool.builtin.ApplyPatchTool;
import de.augmentia.quad.core.tool.builtin.FindTool;
import de.augmentia.quad.core.tool.builtin.GrepTool;
import de.augmentia.quad.core.tool.builtin.LsTool;
import de.augmentia.quad.core.tool.builtin.MultiEditTool;
import de.augmentia.quad.core.tool.builtin.ReadFileTool;
import de.augmentia.quad.core.tool.builtin.WebFetchTool;
import de.augmentia.quad.core.tool.builtin.WebSearchTool;
import de.augmentia.quad.core.tool.builtin.WriteTool;
import de.augmentia.quad.core.tool.sandbox.BashSandboxTool;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import de.augmentia.quad.core.session.Access;
import de.augmentia.quad.core.session.SessionStateSnapshot;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-Image: ReflectiveToolMethod findet Tool-Methoden per
 * Class#getDeclaredMethods/getMethod. Ohne Registrierung liefert die
 * Reflection im Native-Image nichts -> "Method not found". Dieser Registrar
 * meldet alle per @Tool/Methoden-Reflexion instanziierten Klassen an.
 */
@RegisterForReflection(targets = {
        Tool.class,
        ToolArgsMapper.class,
        ReadFileTool.class,
        WebSearchTool.class,
        FindTool.class,
        GrepTool.class,
        LsTool.class,
        WriteTool.class,
        WebFetchTool.class,
        MultiEditTool.class,
        ApplyPatchTool.class,
        BashSandboxTool.class,
        JdkHttpClientBuilderFactory.class,
        JdkHttpClientBuilder.class,
        JdkHttpClient.class,
        PropertyNamingStrategies.class,
        PropertyNamingStrategies.SnakeCaseStrategy.class,
        PropertyNamingStrategies.KebabCaseStrategy.class,
        PropertyNamingStrategies.UpperCamelCaseStrategy.class,
        SessionStateSnapshot.class,
        SessionStateSnapshot.SagaStepSnapshot.class,
        SessionStateSnapshot.GrantedDirSnapshot.class,
        SessionStateSnapshot.MemorySnapshot.class,
        Access.class
})
public class ToolMethodReflection {
}