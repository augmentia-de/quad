package de.augmentia.quad.core.hitl.checkpoint;

import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConsoleChannel implements CheckpointChannel {

    private static final Logger log = LoggerFactory.getLogger(ConsoleChannel.class);
    private final Scanner scanner;

    public ConsoleChannel() {
        this.scanner = new Scanner(System.in);
    }

    @Override
    public void notify(Checkpoint checkpoint) {
        log.info("\n=== HUMAN IN THE LOOP ===");
        log.info("Checkpoint: {}", checkpoint.id());
        log.info("Tool: {}", checkpoint.toolName());
        log.info("Arguments: {}", checkpoint.arguments());
        log.info("Session: {}", checkpoint.sessionId());
        log.info("Approved/rejected via REST: POST /api/checkpoints/{}/approve or /reject", checkpoint.id());
        log.info("Or type 'y' to approve, anything else to reject:");
        System.out.print("> ");
        if (scanner.hasNextLine()) {
            var input = scanner.nextLine();
            if ("y".equalsIgnoreCase(input.trim())) {
                checkpoint.approve("Approved via console");
                log.info("Checkpoint {} approved.", checkpoint.id());
            } else {
                checkpoint.reject("Rejected via console: " + input);
                log.info("Checkpoint {} rejected.", checkpoint.id());
            }
        }
    }
}
