package de.augmentia.quad.quarkus.itest;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared support for E2E tests that run against a *live* backend (plain REST, no Quarkus test
 * container, no dummy agents).
 *
 * <p>Configuration (system properties / env, in priority order):
 * <ul>
 *   <li>{@code QUAD_BASE_URL} / {@code quad.base.url} — backend base URL (default {@code http://localhost:8086})</li>
 *   <li>{@code QUARKUS_DATASOURCE_JDBC_URL} / {@code quad.db.url} — SQLite JDBC URL to the SAME
 *       database file the running backend uses (default {@code jdbc:sqlite:&lt;repo&gt;/data/quad.db},
 *       resolved relative to the project root the backend reads by default)</li>
 * </ul>
 *
 * <p>The tests require the backend to already be running (start it with
 * {@code ./mvnw quarkus:dev} or run the Quarkus app with its main class), otherwise
 * {@link #requireBackendUp()} fails fast.</p>
 *
 * <p>Note: Maven Surefire runs tests with the working directory set to the module base dir
 * ({@code &lt;repo&gt;/quad-quarkus}). The backend's default database lives at the *project root*
 * ({@code &lt;repo&gt;/data/quad.db}), so the default DB URL is resolved relative to the project root
 * (one level above the module dir) to match the running backend. Set {@code quad.db.url} explicitly
 * if your backend uses a different location.</p>
 */
final class E2eBackendSupport {

    private E2eBackendSupport() {}

    static final String BASE_URL = firstNonBlank(
        System.getenv("QUAD_BASE_URL"),
        System.getProperty("quad.base.url"),
        "http://localhost:8086");

    static final String DB_URL = resolveDbUrl();
    static {
        // The quarkus-jdbc-sqlite extension strips the DriverManager ServiceLoader
        // registration; the direct JDBC connections here (DriverManager) need it explicitly.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not on test classpath", e);
        }
        System.err.println("[E2eBackendSupport] baseUrl=" + BASE_URL + " dbUrl=" + DB_URL);
    }

    private static String resolveDbUrl() {
        String env = System.getenv("QUARKUS_DATASOURCE_JDBC_URL");
        String prop = System.getProperty("quad.db.url");
        if (env != null && !env.isBlank()) return env;
        if (prop != null && !prop.isBlank()) return prop;
        // Surefire CWD = module base dir (<repo>/quad-quarkus). Default DB = <repo>/data/quad.db.
        try {
            java.io.File repoRoot = new java.io.File("..").getAbsoluteFile().getCanonicalFile();
            return "jdbc:sqlite:" + new java.io.File(repoRoot, "data/quad.db").getPath();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot resolve project root for DB path", e);
        }
    }

    static final RestAssuredConfig TIMEOUT = RestAssuredConfig.config()
        .httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.socket.timeout", 300000)
            .setParam("http.connection.timeout", 10000));

    /** Configures RestAssured to target the live backend. */
    static void configureClient() {
        RestAssured.baseURI = BASE_URL;
    }

    /** Fails fast with a helpful message if the backend is not reachable. */
    static void requireBackendUp() {
        try {
            given().config(TIMEOUT).get("/api/ui/dynamic/ping")
                .then().statusCode(200);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Backend not reachable at " + BASE_URL + ". Start the backend (main class / "
                + "'mvn quarkus:dev') before running this E2E test. " + e.getMessage(), e);
        }
    }

    /** Self-check: the resolved DB must contain the tables the tests assert on. */
    static void requireDatabase() {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            java.util.Set<String> tables = new java.util.HashSet<>();
            try (ResultSet rs = c.getMetaData().getTables(null, null, "%", null)) {
                while (rs.next()) tables.add(rs.getString("TABLE_NAME").toLowerCase());
            }
            for (String t : List.of("agents", "workflows", "workflow_state")) {
                if (!tables.contains(t)) {
                    throw new IllegalStateException(
                        "Table '" + t + "' not found in " + DB_URL + ". The E2E test DB must be the SAME "
                        + "database the running backend writes to. If the backend uses a different "
                        + "location, set -Dquad.db.url=jdbc:sqlite:/path/to/quad.db (mirroring "
                        + "QUARKUS_DATASOURCE_JDBC_URL of the running backend).");
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot open the E2E test database at " + DB_URL
                + ". Make sure it matches the running backend's datasource. " + e.getMessage(), e);
        }
    }

    /** Polls a workflow run via REST until it reaches a terminal state, then returns it. */
    static Map<String, Object> waitForRun(String runId, int maxSeconds) throws Exception {
        for (int i = 0; i < maxSeconds; i++) {
            Thread.sleep(1000);
            Map<String, Object> run = given().when().get("/api/ui/runs/" + runId)
                .then().statusCode(200).extract().as(Map.class);
            String st = String.valueOf(run.get("status"));
            if ("completed".equals(st) || "failed".equals(st)) return run;
        }
        throw new AssertionError("Workflow run " + runId + " did not finish within " + maxSeconds + "s");
    }

    /** True if a row exists matching {@code where} with the first parameter. */
    static boolean rowExists(String table, String where, String param) {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE " + where)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new IllegalStateException("DB query failed for table " + table + " at " + DB_URL, e);
        }
    }

    static void assertRowExists(String table, String where, String param, String msg) {
        assertTrue(rowExists(table, where, param), msg);
    }

    static void assertRowAbsent(String table, String where, String param, String msg) {
        assertTrue(!rowExists(table, where, param), msg);
    }

    static Map<String, String> workflowRow(String id) {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT name, nodes, edges FROM workflows WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return Map.of("name", rs.getString("name"),
                    "nodes", rs.getString("nodes"), "edges", rs.getString("edges"));
            }
        } catch (Exception e) {
            throw new IllegalStateException("DB query failed for workflows at " + DB_URL, e);
        }
    }

    /** Reads the persisted run row from {@code workflow_state} (replaces the old {@code runs} table). */
    static Map<String, String> runRow(String id) {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT workflow_id, status FROM workflow_state WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return Map.of("workflow_id", rs.getString("workflow_id"), "status", rs.getString("status"));
            }
        } catch (Exception e) {
            throw new IllegalStateException("DB query failed for workflow_state at " + DB_URL, e);
        }
    }

    static long countRunsForWorkflow(String workflowId) {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM workflow_state WHERE workflow_id = ?")) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("DB count failed at " + DB_URL, e);
        }
    }

    static Map<String, String> requireRow(Map<String, String> row, String what) {
        assertNotNull(row, what + " not found in DB at " + DB_URL);
        return row;
    }

    /**
     * Seeds a session row (with a valid AgentSessionState JSON) directly in the DB so that
     * session-scoped GDPR / granted-directory endpoints work WITHOUT needing the LLM to run execute().
     */
    static void seedSession(String id, String cwd) {
        String stateJson =
            "{\"sessionId\":\"" + id + "\",\"tenantId\":null,\"findings\":[],\"currentProject\":null,"
            + "\"cwdStack\":[\"" + cwd + "\"],\"cwdRoot\":\"" + cwd + "\",\"sagaLog\":[],\"sagaFailed\":false,"
            + "\"lastToolNames\":[],\"grantedDirs\":[],\"memory\":{\"summary\":null,\"maxMessages\":40}}";
        String sql = "INSERT OR REPLACE INTO sessions (id, created_at, last_seen, task, result, memory_summary, state_json) "
            + "VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'seed', 'ok', NULL, ?)";
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, stateJson);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("seedSession failed at " + DB_URL, e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
