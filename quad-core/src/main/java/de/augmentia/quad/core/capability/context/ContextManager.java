package de.augmentia.quad.core.capability.context;

import dev.langchain4j.data.message.SystemMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Context block system for prompt engineering: Static, Dynamic, and Protected blocks.
 * <p>
 * Source: Python {@code src/quad/runtime/context.py}, {@code src/quad/context_blocks/__init__.py}
 */
public class ContextManager {

    public sealed interface ContextBlock permits StaticBlock, DynamicBlock, ProtectedBlock {
        String key();
        String render();
        boolean isProtected();
        boolean isPrefix();
    }

    public record StaticBlock(String key, String content, boolean prefix) implements ContextBlock {
        public StaticBlock(String key, String content) {
            this(key, content, true);
        }
        @Override
        public String render() {
            return "[" + key + "]: " + content;
        }
        @Override
        public boolean isProtected() {
            return false;
        }
        @Override
        public boolean isPrefix() {
            return prefix;
        }
    }

    public record DynamicBlock(String key, Supplier<String> supplier, boolean prefix)
            implements ContextBlock {
        public DynamicBlock(String key, Supplier<String> supplier) {
            this(key, supplier, false);
        }
        @Override
        public String render() {
            String value = supplier != null ? supplier.get() : null;
            return "[" + key + "]: " + (value != null ? value : "");
        }
        @Override
        public boolean isProtected() {
            return false;
        }
        @Override
        public boolean isPrefix() {
            return prefix;
        }
    }

    public record ProtectedBlock(String key, String content) implements ContextBlock {
        @Override
        public String render() {
            return "[PROTECTED:" + key + "]: " + content;
        }
        @Override
        public boolean isProtected() {
            return true;
        }
        @Override
        public boolean isPrefix() {
            return true;
        }
    }

    private final Map<String, ContextBlock> blocks = new LinkedHashMap<>();

    public void addBlock(ContextBlock block) {
        Objects.requireNonNull(block, "block");
        blocks.put(block.key(), block);
    }

    /**
     * Sets a static block. Protected blocks cannot be overwritten.
     */
    public void set(String key, String value) {
        guardNotProtected(key);
        blocks.put(key, new StaticBlock(key, value));
    }

    public void remove(String key) {
        guardNotProtected(key);
        blocks.remove(key);
    }

    public ContextBlock get(String key) {
        return blocks.get(key);
    }

    /**
     * {@code true} if at least one block is registered.
     */
    public boolean hasBlocks() {
        return !blocks.isEmpty();
    }

    /**
     * All blocks (including protected) in insertion order.
     */
    public List<ContextBlock> allBlocks() {
        return new ArrayList<>(blocks.values());
    }

    /**
     * Only non-protected blocks — analogous to Python {@code Context.keys()}.
     */
    public List<ContextBlock> userBlocks() {
        return blocks.values().stream()
                .filter(b -> !b.isProtected())
                .toList();
    }

    /**
     * Renders the context blocks as a SystemMessage. Protected blocks are visible
     * in the system prompt but cannot be overwritten or deleted.
     * Prefix blocks (cacheable prefix) appear before suffix blocks.
     *
     * @param basePrompt the base system prompt
     * @param blockOrder optional render order; {@code null} = insertion order
     */
    public SystemMessage renderSystemMessage(String basePrompt, List<String> blockOrder) {
        StringBuilder sb = new StringBuilder();
        if (basePrompt != null) {
            sb.append(basePrompt);
        }
        sb.append("\n\n=== CONTEXT ===\n");

        List<ContextBlock> ordered = resolveOrder(blockOrder);
        for (ContextBlock block : ordered) {
            sb.append(block.render()).append("\n");
        }
        return SystemMessage.from(sb.toString().trim());
    }

    private List<ContextBlock> resolveOrder(List<String> blockOrder) {
        if (blockOrder != null) {
            List<ContextBlock> ordered = new ArrayList<>();
            for (String key : blockOrder) {
                ContextBlock block = blocks.get(key);
                if (block != null) {
                    ordered.add(block);
                }
            }
            return ordered;
        }
        List<ContextBlock> prefix = new ArrayList<>();
        List<ContextBlock> suffix = new ArrayList<>();
        for (ContextBlock block : blocks.values()) {
            (block.isPrefix() ? prefix : suffix).add(block);
        }
        prefix.addAll(suffix);
        return prefix;
    }

    private void guardNotProtected(String key) {
        ContextBlock existing = blocks.get(key);
        if (existing != null && existing.isProtected()) {
            throw new UnsupportedOperationException(
                    "Context block '" + key + "' is protected and cannot be modified");
        }
    }
}
