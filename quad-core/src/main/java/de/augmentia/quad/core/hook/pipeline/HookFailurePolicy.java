package de.augmentia.quad.core.hook.pipeline;

/**
 * Policy for handling hook execution failures.
 */
public enum HookFailurePolicy {
    /** Abort the whole hook chain if any hook throws. */
    CHAIN_ABORT,
    /** Isolate hook failures — a failing hook is skipped, the chain continues. */
    ISOLATE
}
