package de.augmentia.quad.core.observability;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class FileLlmLogger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileLlmLogger.class);

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    static final long MAX_FILE_SIZE = 2L * 1024 * 1024;
    static final int MAX_FILES = 10;

    private final String basePath;
    private final String extension;
    private final Path dir;
    private final Path originalPath;
    private int currentIndex;
    private FileChannel channel;
    private long currentSize;
    private long callIndex;

    public FileLlmLogger(Path logPath) {
        this.originalPath = logPath;
        String name = logPath.toString();
        int dot = name.lastIndexOf('.');
        this.basePath = (dot > 0) ? name.substring(0, dot) : name;
        this.extension = (dot > 0) ? name.substring(dot) : "";
        this.dir = logPath.getParent();
        this.currentIndex = 0;
        openChannel(true);
        write("=== LLM-Call-Log gestartet: " + now() + " ===\n");
    }

    private Path fileForIndex(int index) {
        return Path.of(basePath + "." + index + extension);
    }

    private void openChannel(boolean truncate) {
        try {
            if (dir != null && !Files.exists(dir))
                Files.createDirectories(dir);
            var path = fileForIndex(currentIndex);
            var opts = truncate
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND};
            this.channel = FileChannel.open(path, opts);
            this.currentSize = truncate ? 0 : channel.size();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open LLM log: " + fileForIndex(currentIndex), e);
        }
    }

    private void closeChannel() {
        try {
            if (channel != null && channel.isOpen())
                channel.close();
        } catch (IOException e) {
            log.warn("LLM-Log close error: {}", e.getMessage());
        }
    }

    private void rotateIfNeeded(int newBytes) {
        if (currentSize + newBytes > MAX_FILE_SIZE) {
            closeChannel();
            currentIndex = (currentIndex + 1) % MAX_FILES;
            openChannel(true);
        }
    }

    public synchronized void log(ChatRequest request, ChatResponse response, long durationMs) {
        callIndex++;
        var sb = new StringBuilder();
        sb.append("--- Call #").append(callIndex)
            .append(" (").append(now()).append(", ").append(durationMs).append("ms) ---\n");
        sb.append("REQUEST:\n");
        for (var msg : request.messages()) {
            var role = msg.type() != null ? msg.type().name() : "UNKNOWN";
            var text = switch (msg) {
                case dev.langchain4j.data.message.UserMessage um -> um.singleText();
                case dev.langchain4j.data.message.AiMessage am ->
                    am.text() != null ? am.text() : "(tool-request: " + am.toolExecutionRequests() + ")";
                case dev.langchain4j.data.message.SystemMessage sm -> sm.text();
                case dev.langchain4j.data.message.ToolExecutionResultMessage tr ->
                    "[tool-result] " + tr.toolName() + ": " + tr.text();
                default -> msg.toString();
            };
            sb.append("  [").append(role).append("] ").append(text).append("\n");
        }
        var ai = response.aiMessage();
        sb.append("RESPONSE:\n");
        sb.append("  finishReason=").append(response.finishReason())
            .append(", tokens=");
        if (response.tokenUsage() != null)
            sb.append(response.tokenUsage().inputTokenCount()).append(" in / ")
                .append(response.tokenUsage().outputTokenCount()).append(" out");
        else
            sb.append("?");
        sb.append("\n");
        if (ai.text() != null)
            sb.append("  text: ").append(ai.text()).append("\n");
        if (ai.toolExecutionRequests() != null && !ai.toolExecutionRequests().isEmpty()) {
            sb.append("  tools:\n");
            for (var req : ai.toolExecutionRequests()) {
                sb.append("    - ").append(req.name())
                    .append("(").append(req.arguments()).append(")\n");
            }
        }
        sb.append("\n");

        var text = sb.toString();
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        rotateIfNeeded(bytes.length);
        write(bytes);
    }

    private void write(byte[] bytes) {
        try {
            var buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining())
                channel.write(buf);
            channel.force(false);
        } catch (IOException e) {
            log.warn("LLM-Log write error: {}", e.getMessage());
        }
    }

    private void write(String text) {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String now() {
        return FMT.format(Instant.now());
    }

    @Override
    public synchronized void close() {
        try {
            write("=== LLM-Call-Log beendet: " + now() + " ===\n");
            closeChannel();
        } catch (Exception e) {
            log.warn("LLM-Log close error: {}", e.getMessage());
        }
    }

    public Path path() {
        return originalPath;
    }
}
