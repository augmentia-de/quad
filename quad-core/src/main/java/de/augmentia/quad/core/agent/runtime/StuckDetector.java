package de.augmentia.quad.core.agent.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects a tool loop that is not getting anywhere — and only that.
 *
 * <p><b>Why, when there is a round limit anyway?</b> A round limit bounds the <em>number</em>
 * of steps. That is a proxy for cost, and a bad one: it punishes a legitimate task that needs
 * 20 rounds exactly as much as it punishes an agent that has been reading the same file for
 * eight rounds. Those two cases cost the same and are fundamentally different. One should run,
 * the other should die.
 *
 * <p>This detector tells them apart by the <em>shape</em> of the behaviour, not by its count.
 * It does not answer "how many rounds" but "has anything been added for several rounds".
 *
 * <h2>The three patterns</h2>
 * <ol>
 *   <li><b>Repetition.</b> The same tool with the same arguments twice in a row. The call
 *       yielded nothing that justifies repeating it — the result was either empty or identical,
 *       and the model did not use it as information.</li>
 *   <li><b>Cycle.</b> A call sequence that repeats: A, B, A, B. Each step has a different
 *       result than the last, and it still is not progress.</li>
 *   <li><b>Stall.</b> The tool inventory does not change, yet the model keeps working. This is
 *       the case that costs the most tokens and is the hardest to see: something happens,
 *       nothing just comes of it.</li>
 * </ol>
 *
 * <h2>What it does not do</h2>
 * <p>It does not count rounds and has no hard limit. A task that repeats while making
 * <em>progress</em> — {@code listFiles} twice because a file was read in between, or
 * {@code grep} with a narrower pattern — triggers no pattern. The detector is therefore not a
 * replacement for a round limit on unusable models, but its sharp part: it ends the cases in
 * which the limit was only an alibi.
 *
 * <p><b>Deliberate non-responsibility.</b> The detector sees only the
 * {@link ToolExecutionRequest}s, not the results. It cannot know whether a tool result was
 * useful — the model has it in the transcript, and a result that repeats is itself a valid
 * signal: the model read it and asked again. A detector that judged results would have to
 * judge them without the agent's working knowledge. It would produce false positives and
 * strangle legitimate repetitions.
 *
 * <p>The warmup phases are not incidental. Every pattern is only reported after
 * {@code minRoundsBeforeSignal} rounds, because the first rounds of a run legitimately look
 * alike: first orient, then read.
 */
public final class StuckDetector {

    /** Round from which a pattern is reported. Before that, a pattern counts as regular growth. */
    public static final int DEFAULT_MIN_ROUNDS_BEFORE_SIGNAL = 3;

    /** Window for the cycle comparison: this many rounds are checked for repetition. */
    public static final int DEFAULT_CYCLE_WINDOW = 4;

    /**
     * From round 2 an identical repetition is reported — without the warmup the other patterns
     * get.
     *
     * <p>The reason for the exception: the result of the first call sits in the transcript, one
     * word away. A second, identical call is therefore not information but a repetition — and
     * the run paid a model call for it. The patterns CYCLE and STALL need more data and get
     * away with the warmup.
     */
    public static final int MIN_ROUNDS_BEFORE_REPETITION = 2;

    private final int minRoundsBeforeSignal;
    private final int cycleWindow;

    /** Per round the signed tool calls, outermost round first. */
    private final List<String> transcript = new ArrayList<>();

    /** Every call signature this run has ever produced. */
    private final java.util.Set<String> seen = new java.util.HashSet<>();

    /** How many rounds in a row without a single new signature. */
    private int stalledRounds;

    public StuckDetector() {
        this(DEFAULT_MIN_ROUNDS_BEFORE_SIGNAL, DEFAULT_CYCLE_WINDOW);
    }

    public StuckDetector(int minRoundsBeforeSignal, int cycleWindow) {
        if (minRoundsBeforeSignal < 1) {
            throw new IllegalArgumentException(
                    "minRoundsBeforeSignal must be >= 1, got: " + minRoundsBeforeSignal);
        }
        if (cycleWindow < 2) {
            throw new IllegalArgumentException("cycleWindow must be >= 2, got: " + cycleWindow);
        }
        this.minRoundsBeforeSignal = minRoundsBeforeSignal;
        this.cycleWindow = cycleWindow;
    }

    /**
     * Called by the detector for a completed round.
     *
     * @param calls  the tool calls of this round in execution order; empty when the model
     *               answered without a tool
     * @return the detected pattern, or {@code null} when the round shows progress
     */
    public Pattern observe(List<ToolExecutionRequest> calls) {
        List<String> signature = signatures(calls);
        transcript.add(0, String.join("|", signature));

        // A stall means: the model no longer asks anything it has not already asked.
        //
        // <b>Not</b> "the tool inventory stays the same". That would be the obvious counter
        // and it would be wrong: 25 rounds of the same two tools with <em>different</em>
        // arguments are a list walked file by file, and that is progress. Measurement is on
        // the arguments, not on the tools.
        boolean producedNew = signature.stream().anyMatch(s -> seen.add(s));
        if (producedNew) {
            stalledRounds = 0;
        } else {
            stalledRounds++;
        }

        int rounds = transcript.size();
        if (!signature.isEmpty() && rounds >= MIN_ROUNDS_BEFORE_REPETITION
                && Objects.equals(transcript.get(0), transcript.get(1))) {
            return new Pattern(Kind.REPETITION,
                    "Round " + rounds + ": exactly the same tool calls as in the round before — "
                            + describe(0, 1),
                    rounds);
        }

        if (rounds < minRoundsBeforeSignal) {
            return null; // warmup: the first rounds legitimately look alike
        }

        if (rounds >= cycleWindow && cycleRepeats()) {
            return new Pattern(Kind.CYCLE,
                    "Round " + rounds + ": the last " + cycleWindow + " rounds repeat — "
                            + describe(0, cycleWindow),
                    rounds);
        }

        if (stalledRounds >= minRoundsBeforeSignal) {
            return new Pattern(Kind.STALL,
                    "for " + stalledRounds + " rounds the run has produced no new tool call — "
                            + "it only repeats what already ran",
                    rounds);
        }

        return null;
    }

    /** Resets the detector, so that one instance belongs to exactly one run. */
    public void reset() {
        transcript.clear();
        seen.clear();
        stalledRounds = 0;
    }

    /**
     * Does the window repeat? The current round is compared against a window shifted by half
     * the cycle length — so A B A B matches, but X A B A does not, where X is a one-off step.
     */
    private boolean cycleRepeats() {
        int half = cycleWindow / 2;
        for (int offset = 0; offset + cycleWindow <= transcript.size(); offset += half) {
            boolean equal = true;
            for (int i = 0; i + half < cycleWindow; i++) {
                if (!Objects.equals(transcript.get(i), transcript.get(i + half))) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return true;
            }
        }
        return false;
    }

    private static List<String> signatures(List<ToolExecutionRequest> calls) {
        List<String> signatures = new ArrayList<>(calls.size());
        for (var r : calls) {
            signatures.add(r.name() + "(" + r.arguments() + ")");
        }
        return signatures;
    }

    private String describe(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < transcript.size(); i++) {
            if (i > from) {
                sb.append(" / ");
            }
            sb.append(transcript.get(i).isEmpty() ? "(no tool)" : transcript.get(i));
        }
        return sb.toString();
    }

    /** The three patterns that mean no progress. */
    public enum Kind {
        /** Identical call in a row. */
        REPETITION,
        /** The call sequence repeats. */
        CYCLE,
        /** The tool inventory does not change, the run keeps going. */
        STALL
    }

    /**
     * A detected stall.
     *
     * @param kind     which pattern
     * @param reason   plain text for humans and logs — not for the model
     * @param round    in which round it was noticed
     */
    public record Pattern(Kind kind, String reason, int round) {

        /**
         * The marker that goes into the workflow output.
         *
         * <p>Same reasoning as {@code AgentRuntime.ITERATION_LIMIT_MARKER}: the text goes into
         * the output, and a workflow checks conditions like {@code contains:OK} against it. An
         * agent that is stuck must not be indistinguishable from one that delivered a finished
         * answer. An {@code [INCOMPLETE_...]} cannot be a domain output, because a domain
         * output does not begin with it.
         */
        public static final String MARKER = "[INCOMPLETE: tool loop";

        public String marker() {
            return MARKER + " — " + kind + "]:\n" + reason;
        }
    }
}