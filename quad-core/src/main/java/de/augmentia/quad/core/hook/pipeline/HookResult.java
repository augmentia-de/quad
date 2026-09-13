package de.augmentia.quad.core.hook.pipeline;

/**
 * Sealed result type returned by hooks to continue, cancel, modify, or retry.
 */
public sealed interface HookResult
    permits HookResult.Continue, HookResult.Cancel, HookResult.Modify, HookResult.Retry {

    record Continue() implements HookResult {
        public static final Continue INSTANCE = new Continue();
    }

    record Cancel(String reason) implements HookResult {}

    record Modify<T>(T value) implements HookResult {}

    record Retry(String reason) implements HookResult {}
}
