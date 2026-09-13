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
}
