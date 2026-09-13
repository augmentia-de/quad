package de.augmentia.quad.core.observability;

import de.augmentia.quad.core.events.AgentEvent;
import de.augmentia.quad.core.events.AgentFinishedEvent;
import de.augmentia.quad.core.events.AgentStartedEvent;
import de.augmentia.quad.core.events.ToolExecutionFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingHook {

    private static final Logger log = LoggerFactory.getLogger(LoggingHook.class);

    public void onEvent(AgentEvent event) {
        if (event instanceof AgentStartedEvent s) {
            log.info("Agent startet: session={} prompt='{}'", s.sessionId(), s.initialPrompt());
        } else if (event instanceof AgentFinishedEvent f) {
            log.info("Agent beendet: session={} result='{}'",
                f.sessionId(), f.finalAnswer());
        } else if (event instanceof ToolExecutionFinishedEvent t) {
            if (t.isError()) {
                log.warn("Tool fehlgeschlagen: session={} tool={} error='{}'",
                    t.sessionId(), t.toolName(), t.result());
            } else {
                log.info("Tool ok: session={} tool={} result='{}'",
                    t.sessionId(), t.toolName(), t.result());
            }
        }
    }
}
