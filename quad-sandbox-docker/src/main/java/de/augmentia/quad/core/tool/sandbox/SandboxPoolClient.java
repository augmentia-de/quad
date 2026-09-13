package de.augmentia.quad.core.tool.sandbox;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.WorkspaceResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executes a bash command inside an isolated Docker container.
 * <p>
 * The session workspace is mounted read-write as {@code /workspace} and used
 * as the working directory. The container runs with no network access
 * ({@code --network none}) and a bounded memory limit, and is removed after
 * execution ({@code --rm}). Output is captured with a bounded ring buffer to
 * avoid unbounded memory usage.
 * <p>
 * When Docker is unavailable or the sandbox is disabled, this falls back to a
 * clearly labelled stub result so callers never hang, but no real host command
 * is executed.
 */
@ApplicationScoped
public class SandboxPoolClient {

    private static final Logger log = LoggerFactory.getLogger(SandboxPoolClient.class);

    private static final int MAX_LINES = 300;
    private static final int MAX_BYTES = 30_720;
    private static final String DOCKERFILE_RESOURCE = "/Dockerfile.runner";

    private final SandboxConfig config;
    private final int poolSize;
    private final java.util.concurrent.ExecutorService executor;

    private volatile boolean imageReady = false;

    @Inject
    WorkspaceResolver workspaceResolver;

    @Inject
    CurrentSession currentSession;

    public SandboxPoolClient() {
        this(new SandboxConfig(true, "quad-runner:latest", "512m", 120_000, true));
    }

    public SandboxPoolClient(SandboxConfig config) {
        this(config, 4);
    }

    public SandboxPoolClient(SandboxConfig config, int poolSize) {
        this.config = config;
        this.poolSize = poolSize;
        this.executor = java.util.concurrent.Executors.newFixedThreadPool(poolSize);
    }

    public int getPoolSize() {
        return poolSize;
    }

    public String run(String script) {
        return run(script, null);
    }

    public String run(String script, String sessionId) {
        if (!config.isEnabled()) {
            return "[sandbox disabled] command not executed: " + trunc(script, 200);
        }
        String effectiveSession = sessionId != null ? sessionId : currentSessionId();

        // Ensure the session workspace exists so the bind mount works.
        Path workspace = workspaceDir(effectiveSession);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            log.warn("Could not create sandbox workspace {}: {}", workspace, e.getMessage());
        }

        ensureImage();
        String containerName = "quad-sandbox-" + UUID.randomUUID();

        Process process = null;
        try {
            var cmd = new ArrayList<String>();
            cmd.add("docker");
            cmd.add("run");
            cmd.add("--rm");
            cmd.add("--name");
            cmd.add(containerName);
            cmd.add("-v");
            cmd.add(workspace.toAbsolutePath().toString() + ":/workspace");
            cmd.add("-w");
            cmd.add("/workspace");
            if (config.noNetwork()) {
                cmd.add("--network");
                cmd.add("none");
            }
            cmd.add("--memory");
            cmd.add(config.memory());
            cmd.add(config.image());
            cmd.add("sh");
            cmd.add("-c");
            cmd.add(script);

            log.debug("Sandbox run session={} container={}", effectiveSession, containerName);
            var pb = new ProcessBuilder(cmd);
            process = pb.start();

            List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
            AtomicLong totalBytes = new AtomicLong(0);
            CountDownLatch streamsDone = new CountDownLatch(2);

            Process finalProcess = process;
            Thread stdoutThread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(finalProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                        long currentBytes = totalBytes.addAndGet(line.length() + 1);
                        while (lines.size() > MAX_LINES || currentBytes > MAX_BYTES * 2) {
                            if (!lines.isEmpty()) {
                                String removed = lines.remove(0);
                                currentBytes = totalBytes.addAndGet(-(removed.length() + 1));
                            } else {
                                break;
                            }
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    streamsDone.countDown();
                }
            }, "sandbox-stdout-" + containerName);

            Thread stderrThread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(finalProcess.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add("[stderr] " + line);
                    }
                } catch (IOException ignored) {
                } finally {
                    streamsDone.countDown();
                }
            }, "sandbox-stderr-" + containerName);

            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
            stdoutThread.start();
            stderrThread.start();

            long startTime = System.currentTimeMillis();
            long timeoutMs = config.timeoutMs();
            boolean finished;
            do {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    cleanupContainer(process, containerName);
                    return "Error: Command timed out after " + (timeoutMs / 1000) + " seconds\n" + join(lines);
                }
                finished = streamsDone.await(50, TimeUnit.MILLISECONDS);
            } while (!finished);

            int exitCode = process.waitFor();
            String result = join(lines);
            if (exitCode != 0) {
                result += "\n\nCommand exited with code " + exitCode;
                log.debug("Sandbox run ERROR exitCode={} session={}", exitCode, effectiveSession);
                return result;
            }
            return trunc(result, 500);
        } catch (IOException | InterruptedException e) {
            log.debug("Sandbox run FAILED session={}: {}", effectiveSession, e.getMessage());
            return "Error executing command in sandbox: " + e.getMessage();
        }
    }

    private String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private void ensureImage() {
        if (imageReady) {
            return;
        }
        synchronized (SandboxPoolClient.class) {
            if (imageReady) {
                return;
            }
            if (!imageExists()) {
                buildImage();
            }
            imageReady = true;
        }
    }

    private boolean imageExists() {
        try {
            var proc = new ProcessBuilder("docker", "image", "inspect", config.image())
                    .redirectErrorStream(true).start();
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void buildImage() {
        log.info("Sandbox image {} not found - building from embedded Dockerfile.runner", config.image());
        try (InputStream in = getClass().getResourceAsStream(DOCKERFILE_RESOURCE)) {
            if (in == null) {
                log.warn("Embedded {} not found on classpath - cannot auto-build", DOCKERFILE_RESOURCE);
                return;
            }
            Path tempDir = Files.createTempDirectory("quad-runner-");
            Path dockerfile = tempDir.resolve("Dockerfile.runner");
            Files.copy(in, dockerfile);

            var pb = new ProcessBuilder("docker", "build", "-f", dockerfile.toString(), "-t", config.image(), tempDir.toString());
            pb.inheritIO();
            var proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                log.warn("Auto-build of sandbox image {} failed with exit code {}", config.image(), exitCode);
            } else {
                log.info("Successfully built sandbox image {}", config.image());
            }
        } catch (Exception e) {
            log.warn("Failed to auto-build sandbox image {}", config.image(), e);
        }
    }

    private void cleanupContainer(Process process, String containerName) {
        if (process != null) {
            process.destroyForcibly();
        }
        try {
            new ProcessBuilder("docker", "stop", "-t", "2", containerName).start();
        } catch (IOException e) {
            log.warn("Failed to stop sandbox container explicitly: {}", containerName, e);
        }
    }

    private String currentSessionId() {
        AgentSessionState state = currentSession != null ? currentSession.get() : CurrentSession.getCurrent();
        return state != null ? state.getSessionId() : "default";
    }

    private Path workspaceDir(String sessionId) {
        String id = sessionId != null ? sessionId : "default";
        return Path.of(WorkspaceResolver.resolve(id));
    }

    private static String trunc(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
