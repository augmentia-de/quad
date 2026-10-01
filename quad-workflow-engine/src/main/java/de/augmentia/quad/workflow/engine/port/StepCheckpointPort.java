package de.augmentia.quad.workflow.engine.port;

import java.util.Optional;

/**
 * Port fuer Checkpoints, mit denen ein Lauf nach einem Neustart fortgesetzt wird.
 *
 * <p>{@code RunStateWriter} und {@code WorkflowEngine} griffen direkt auf
 * {@code StepSnapshotStore} zu. Ohne Port haette die Engine den
 * Persistenzmechanismus des Deployments gekannt - inklusive Tabellennamen und
 * {@code Optional}-Signatur.
 *
 * <p>Implementierung: {@code StepCheckpointAdapter} in quad-quarkus.
 */
public interface StepCheckpointPort {

    /**
     * Sichert den Fortschritt eines Laufs.
     *
     * @return {@code true}, wenn der Snapshot geschrieben wurde
     */
    boolean save(String runId, int stepIndex, String runStatus,
                 String snapshotJson, String memorySummary);

    /**
     * Liest den juengsten Snapshot eines Laufs fuer Resume/Crash-Recovery.
     *
     * <p>Gab es keinen, ist das Ergebnis {@link Optional#empty()} - kein Fehler.
     */
    Optional<Checkpoint> loadLast(String runId);

    /** Teil eines Checkpoints; vom Adapter aus dem Store-Snapshot erzeugt. */
    record Checkpoint(String runId, int stepIndex, String stepStatus,
                      String environmentState, String memorySummary) {

        /** Fortsetzbarer Zustand fuer die Engine; {@code null}, wenn nichts gespeichert war. */
        public String environment() {
            return environmentState;
        }
    }
}