package de.augmentia.quad.quarkus.contract;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A shared "unit of work" step model for Agent and Workflow (Spec §3.1).
 *
 * Deterministically numbered step within a run. Populated by both Workflow-Nodes
 * (level 3/6) and Agent-Steps (level 7), both mapped via the same persistence path
 * (StepSnapshotStore/TelemetryStore).
 *
 * Kotlin-portable (green): plain record without Quarkus binding (annotation is Reflection-only).
 */
@RegisterForReflection
public record StepRecord(
        /** Globally unique step id within a run (e.g. "node-<id>" / "agent-<n>"). */
        String id,
        /** Step kind: model-call | tool-call | node | edge | messaging. */
        String kind,
        /** Hash/Canonical of the input (idempotency, duplicate detection). */
        String inputHash,
        /** Status: pending | running | completed | failed | cancelled | timed_out | skipped. */
        String status,
        /** output / error / reasoning. */
        String outcome,
        /** Start timestamp (ISO-8601). */
        String startedAt,
        /** End timestamp (ISO-8601). */
        String finishedAt,
        /** Duration in ms. */
        long durationMs,
        /** iteration counter for Resume/Retry. */
        int attemptCount) {

    public static String STATUS_PENDING = "pending";
    public static String STATUS_RUNNING = "running";
    public static String STATUS_COMPLETED = "completed";
    public static String STATUS_FAILED = "failed";
    public static String STATUS_CANCELLED = "cancelled";
    public static String STATUS_TIMED_OUT = "timed_out";
    public static String STATUS_SKIPPED = "skipped";

    public static String KIND_MODEL_CALL = "model-call";
    public static String KIND_TOOL_CALL = "tool-call";
    public static String KIND_NODE = "node";
    public static String KIND_EDGE = "edge";
    public static String KIND_MESSAGING = "messaging";

    /** convenient factory value for an new, running Schritt. */
    public static StepRecord start(String id, String kind, String inputHash) {
        return new StepRecord(id, kind, inputHash, STATUS_RUNNING, null,
                Instant.now().toString(), null, 0L, 0);
    }
}