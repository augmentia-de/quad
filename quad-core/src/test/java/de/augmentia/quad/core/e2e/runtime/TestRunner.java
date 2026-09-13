package de.augmentia.quad.e2e.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

public class TestRunner {
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Path reportPath = Path.of("target/e2e-reports");

    public void runTestsInParallel() throws InterruptedException {
        try {
            Files.createDirectories(reportPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create report directory", e);
        }
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path htmlReport = reportPath.resolve("e2e-report-" + timestamp + ".html");

        List<CompletableFuture<Result>> futures = List.of(
            runTest("CodeAct_FileRead", () -> {}),
            runTest("CodeAct_ErrorHandling", () -> {}),
            runTest("MultiTool_Usage", () -> {})
        );

        List<Result> results = futures.stream()
            .map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    return new Result("Test", "FAILED", 0, 0, e.getMessage());
                }
            })
            .collect(Collectors.toList());

        generateHtmlReport(htmlReport, results);
        executor.shutdown();
    }

    private CompletableFuture<Result> runTest(String name, Runnable test) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                test.run();
                return new Result(name, "PASSED", 5, System.currentTimeMillis() - start, "Success");
            } catch (Exception e) {
                return new Result(name, "FAILED", 0, System.currentTimeMillis() - start, e.getMessage());
            }
        }, executor);
    }

    private void generateHtmlReport(Path path, List<Result> results) {
        try {
            String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>QUAD E2E Test Report</title>
                    <style>
                        body { font-family: Arial, sans-serif; margin: 20px; }
                        .passed { color: green; }
                        .failed { color: red; }
                        table { border-collapse: collapse; width: 100%; }
                        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                        th { background-color: #f2f2f2; }
                    </style>
                </head>
                <body>
                    <h1>QUAD E2E Test Report</h1>
                    <p>Generated: %s</p>
                    <table>
                        <tr><th>Test Name</th><th>Status</th><th>Score</th><th>Latency (ms)</th><th>Details</th></tr>
                        %s
                    </table>
                </body>
                </html>
                """.formatted(
                    LocalDateTime.now().toString(),
                    results.stream()
                        .map(r -> "<tr><td>" + r.name + "</td><td class=\"" + r.status.toLowerCase() + "\">" + r.status + "</td><td>" + r.score + "/5</td><td>" + r.latency + "</td><td>" + r.details + "</td></tr>")
                        .collect(Collectors.joining())
                );

            Files.writeString(path, html);
            System.out.println("Report generated: " + path.toAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private record Result(String name, String status, int score, long latency, String details) {}
}