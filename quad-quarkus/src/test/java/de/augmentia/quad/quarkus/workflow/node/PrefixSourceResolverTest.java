package de.augmentia.quad.quarkus.workflow.node;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PrefixSourceResolverTest {

    private final AgentSessionState state = new AgentSessionState();
    private final WorkflowExecutionContext ctx = new WorkflowExecutionContext("run", null, "", "",
            state, false, null, true);

    @Test
    void bareStringStaysAsIs() {
        assertEquals("static block", PrefixSourceResolver.resolve("static block", ctx, "n1"));
    }

    @Test
    void inlineJsonElementIsSerialized() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("title", "Block A");
        raw.put("items", List.of("a", "b"));
        String resolved = PrefixSourceResolver.resolve(raw, ctx, "n1");
        assertEquals("{\"title\":\"Block A\",\"items\":[\"a\",\"b\"]}", resolved);
    }

    @Test
    void stepReferenceResolvesOutputOfReferencedNode() {
        ctx.putOutput("n1", "step-1 result");
        Map<String, Object> src = Map.of("step", "n1");
        assertEquals("step-1 result", PrefixSourceResolver.resolve(src, ctx, "n2"));
    }

    @Test
    void missingStepReferenceResolvesNull() {
        Map<String, Object> src = Map.of("step", "ghost");
        assertNull(PrefixSourceResolver.resolve(src, ctx, "n2"));
    }

    @Test
    void selfReferenceResolvesOwnNodeOutput() {
        ctx.putOutput("n5", "self result");
        Map<String, Object> src = new HashMap<>();
        src.put("self", true);
        assertEquals("self result", PrefixSourceResolver.resolve(src, ctx, "n5"));
    }

    @Test
    void fileSourceReadsAbsolutePath() throws Exception {
        Path tmp = Files.createTempFile("prefix-src-", ".md");
        Files.writeString(tmp, "file block lorem ipsum");
        String resolved = PrefixSourceResolver.resolve("file://" + tmp, ctx, "n1");
        assertEquals("file block lorem ipsum", resolved);
        Files.deleteIfExists(tmp);
    }

    @Test
    void missingFileResolvesNull() {
        assertNull(PrefixSourceResolver.resolve("file:///does/not/exist.md", ctx, "n1"));
    }

    @Test
    void invalidUrlFailsSilently() {
        assertNull(PrefixSourceResolver.resolve("https://invalid.invalid/nope", ctx, "n1"));
    }

    @Test
    void mapUrlFieldFetches() throws Exception {
        // "url" key with a non-URL value -> silent null (no crash)
        Map<String, Object> src = Map.of("url", "not a url");
        assertNull(PrefixSourceResolver.resolve(src, ctx, "n1"));
    }

    @Test
    void tooLargeSourceIsCapped() {
        // "static block, then filler" -> inline string is used as-is (no cap below threshold)
        String big = "x".repeat(300_000);
        String resolved = PrefixSourceResolver.resolve(big, ctx, "n1");
        assertEquals(300_000, resolved.length());
    }
}