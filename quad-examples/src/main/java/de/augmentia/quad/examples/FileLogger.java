package de.augmentia.quad.examples;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * File-based logging for agent hooks.
 * Writes all lifecycle events to a rotating log file.
 *
 * <h3>Usage</h3>
 * <pre>
 * FileLogger logger = new FileLogger("logs/orchestrator");
 * agent.addHook(new FileLoggingHook(logger));
 * </pre>
 */
public class FileLogger implements Closeable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path logDir;
    private final String baseName;
    private final DateTimeFormatter fileTs = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private BufferedWriter writer;
    private Path currentFile;

    public FileLogger(String dir) {
        this(dir, "agent");
    }

    public FileLogger(String dir, String baseName) {
        this.logDir = Path.of(dir);
        this.baseName = baseName;
        try {
            Files.createDirectories(logDir);
            openNewFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void openNewFile() throws IOException {
        if (writer != null) writer.close();
        currentFile = logDir.resolve(baseName + "-" + fileTs.format(LocalDateTime.now()) + ".log");
        writer = Files.newBufferedWriter(currentFile);
        writer.write("--- Log started " + LocalDateTime.now().format(TS) + " ---");
        writer.newLine();
        writer.flush();
    }

    public void log(String line) {
        try {
            writer.write(LocalDateTime.now().format(TS) + " " + line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("[FileLogger] write failed: " + e.getMessage());
        }
    }

    public void log(String tag, String line) {
        log("[" + tag + "] " + line);
    }

    public void logMultiLine(String tag, String content) {
        if (content == null) { log(tag, "null"); return; }
        for (String line : content.split("\n")) {
            log(tag, line);
        }
    }

    public Path getCurrentFile() {
        return currentFile;
    }

    @Override
    public void close() {
        try {
            if (writer != null) {
                writer.write("--- Log ended " + LocalDateTime.now().format(TS) + " ---");
                writer.newLine();
                writer.close();
            }
        } catch (IOException ignored) {}
    }
}
