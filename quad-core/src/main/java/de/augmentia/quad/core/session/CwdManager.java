package de.augmentia.quad.core.session;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Current Workflow Directory (CWD) — nested session isolation.
 * Inspired by DSH packages/core/session/src/index.ts and the
 * strands-agents implementation.
 */
public class CwdManager {

    private final Deque<String> cwdStack = new ConcurrentLinkedDeque<>();
    private String rootCwd;

    public CwdManager() {
        this(".");
    }

    public CwdManager(String rootCwd) {
        this.rootCwd = rootCwd != null ? rootCwd : ".";
        this.cwdStack.push(this.rootCwd);
    }

    public synchronized CwdManager push(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            throw new IllegalArgumentException("CWD must not be null or empty");
        }
        cwdStack.push(cwd);
        return this;
    }

    public synchronized CwdManager pop() {
        if (cwdStack.size() > 1) {
            cwdStack.pop();
        }
        return this;
    }

    public synchronized String current() {
        return cwdStack.peek();
    }

    public synchronized String root() {
        return rootCwd;
    }

    public synchronized int depth() {
        return cwdStack.size() - 1;
    }

    public synchronized boolean isRoot() {
        return cwdStack.size() <= 1;
    }

    public synchronized CwdManager reset() {
        cwdStack.clear();
        cwdStack.push(rootCwd);
        return this;
    }

    /** Returns a shallow copy of the CWD stack (for serialization) */
    public synchronized List<String> stackCopy() {
        return List.copyOf(cwdStack);
    }

    /**
     * Restores a secured stack — the counterpart to {@link #stackCopy()}.
     *
     * <p>{@code stackCopy()} returns the stack from the front: index 0 is the current
     * directory, the last element the root. That is why it is rebuilt from the back.
     *
     * <p>Empty or blank entries are skipped: a secured state containing an empty path must not
     * cause a {@link ConcurrentLinkedDeque} error in tool execution.
     */
    public synchronized CwdManager restore(List<String> stack) {
        cwdStack.clear();
        if (stack != null) {
            for (int i = stack.size() - 1; i >= 0; i--) {
                String cwd = stack.get(i);
                if (cwd != null && !cwd.isBlank()) {
                    cwdStack.push(cwd);
                }
            }
        }
        if (cwdStack.isEmpty()) {
            cwdStack.push(rootCwd != null && !rootCwd.isBlank() ? rootCwd : ".");
        }
        return this;
    }
}
