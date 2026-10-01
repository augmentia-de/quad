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
 * Native-Image-Registrierung für die reflektive Tool-Invocation.
 *
 * <p>Der Werkzeug-Aufbau im Kern ist vollständig reflektiv:
 * {@code ToolCatalog} und {@code QuadToolRegistry.registerFromAgent} iterieren über
 * {@code getDeclaredMethods()}, filtern auf die {@code @Tool}-Annotation und
 * rufen die Methode später über {@code Method.invoke()} auf. In einem GraalVM-Image
 * existiert diese Reflection-Metadaten-Anbindung nur, wenn sie registriert wurde —
 * ohne sie liefert {@code getDeclaredMethods()} nichts und der Tool-Katalog bleibt leer.
 *
 * <p>Bewusst eine eigene Klasse mit Class-Literal-Referenzen: das hält die Liste der
 * Tools an einer Stelle und macht einen fehlenden Eintrag beim Review sichtbar, statt
 * ihn in einer Konfigurationsdatei zu verstecken.
 *
 * <p><b>Restlücke:</b> Agenten-Klassen mit eigenen {@code @Tool}-Methoden sind
 * anwendungsspezifisch und liegen außerhalb dieses Moduls. Sie registriert die App
 * selbst, z. B. {@code @RegisterForReflection(targets = MyAgent.class)}.
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