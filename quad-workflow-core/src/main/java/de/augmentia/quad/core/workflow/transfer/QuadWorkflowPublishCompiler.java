package de.augmentia.quad.core.workflow.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns a v2 workflow <em>draft</em> into a <em>published</em> document:
 * resolves every {@code agentRef} against a registry, fills/repairs
 * {@code agentRef.contentHash} with the hash of the resolved agent, sets
 * {@code status: published}, computes the envelope {@code contentHash}, and validates the
 * result against the v2 schema.
 *
 * <p>The resolved agent configuration is hashed but <b>not</b> written into the definition
 * (it stays content-addressed). A run pins the resolved configuration in its execution
 * snapshot — see the run-state layer.</p>
 *
 * <p>Fail-closed: an unresolvable reference or any schema violation throws
 * {@link QuadWorkflowPublishException}.</p>
 */
public final class QuadWorkflowPublishCompiler {

    /** Resolves an agent reference (e.g. {@code agents/extractor@1.3.0}) to its definition. */
    @FunctionalInterface
    public interface AgentResolver extends Function<String, Optional<JsonNode>> {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Publishes a v2 draft, resolving agent refs through the given resolver. */
    public JsonNode publish(JsonNode v2Draft, AgentResolver resolver) {
        if (v2Draft == null || v2Draft.isNull()) {
            throw new QuadWorkflowPublishException("v2 draft is null");
        }
        if (resolver == null) {
            throw new QuadWorkflowPublishException("agent resolver is required");
        }
        ObjectNode doc = (ObjectNode) v2Draft.deepCopy();

        for (ObjectNode workflow : workflowsOf(doc)) {
            workflow.put("status", "published");
            for (JsonNode node : workflow.path("nodes")) {
                if (!node.isObject() || !"agent".equals(node.path("type").asText())) {
                    continue;
                }
                ObjectNode agentRef = (ObjectNode) node.get("agent");
                if (agentRef == null) {
                    throw new QuadWorkflowPublishException(
                            "agent node '" + node.path("id").asText() + "' has no agent reference");
                }
                String ref = agentRef.path("ref").asText(null);
                if (ref == null || ref.isBlank()) {
                    throw new QuadWorkflowPublishException(
                            "agent node '" + node.path("id").asText() + "' has a blank agent ref");
                }
                JsonNode resolved = resolver.apply(ref).orElseThrow(() ->
                        new QuadWorkflowPublishException("Unresolved agent reference: " + ref));
                agentRef.put("contentHash", "sha256:" + sha256Hex(canonical(resolved)));
            }
        }

        doc.put("contentHash", "sha256:" + sha256Hex(canonical(contentForHash(doc))));

        List<String> issues = QuadWorkflowSchemaValidator.validateV2(doc);
        if (!issues.isEmpty()) {
            throw new QuadWorkflowPublishException(
                    "Published document does not match the v2 schema: " + String.join("; ", issues));
        }
        return doc;
    }

    /** The parts of the envelope that the content hash covers. */
    private static JsonNode contentForHash(ObjectNode doc) {
        ObjectNode content = MAPPER.createObjectNode();
        if (doc.has("mainWorkflow")) content.set("mainWorkflow", doc.get("mainWorkflow"));
        if (doc.has("subWorkflows")) content.set("subWorkflows", doc.get("subWorkflows"));
        if (doc.has("agents")) content.set("agents", doc.get("agents"));
        return content;
    }

    private static List<ObjectNode> workflowsOf(ObjectNode doc) {
        List<ObjectNode> list = new ArrayList<>();
        if (doc.path("mainWorkflow").isObject()) {
            list.add((ObjectNode) doc.get("mainWorkflow"));
        }
        if (doc.path("subWorkflows").isArray()) {
            for (JsonNode wf : doc.get("subWorkflows")) {
                if (wf.isObject()) list.add((ObjectNode) wf);
            }
        }
        return list;
    }

    // ── canonical hashing ────────────────────────────────────────────────

    /** Deterministic canonical JSON (object keys sorted) used for content hashing. */
    static String canonical(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isObject()) {
            sb.append('{');
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            boolean first = true;
            for (String key : keys) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(key)).append(':');
                writeCanonical(node.get(key), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) sb.append(',');
                first = false;
                writeCanonical(child, sb);
            }
            sb.append(']');
        } else {
            sb.append(node.toString());
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
