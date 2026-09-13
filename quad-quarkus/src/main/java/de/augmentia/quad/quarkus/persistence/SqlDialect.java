package de.augmentia.quad.quarkus.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Database dialect abstraction for portal UPSERT SQLs.
 * Reads {@code quarkus.datasource.db-kind} and generates the correct SQL syntax
 * for SQLite (INSERT OR REPLACE), H2 (MERGE INTO KEY) and PostgreSQL (ON CONFLICT DO UPDATE).
 */
@ApplicationScoped
public class SqlDialect {

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "sqlite")
    String dbKind;

    /** Test hook: allows unit tests without CDI. */
    public void setDbKind(String dbKind) {
        this.dbKind = dbKind;
    }

    /**
     * Generates an INSERT or UPDATE statement for the given table.
     *
     * @param table         table name
     * @param columns       column names (all, including PK)
     * @param pkColumn      primary key column(s), comma-separated
     * @param updateColumns comma-separated columns for ON CONFLICT/MERGE-Update
     *                      (only relevant for PostgreSQL; H2 automatically updates all non-key columns)
     * @return SQL string with {@code ?}-placeholders
     */
    public String upsert(String table, String columns, String pkColumn, String updateColumns) {
        String placeholders = Arrays.stream(columns.split(","))
            .map(c -> "?").collect(Collectors.joining(", "));
        return switch (dbKind) {
            case "sqlite" ->
                "INSERT OR REPLACE INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
            case "h2" ->
                "MERGE INTO " + table + " (" + columns + ") KEY(" + pkColumn + ") VALUES (" + placeholders + ")";
            case "postgresql" ->
                "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ") " +
                "ON CONFLICT(" + pkColumn + ") DO UPDATE SET " + updateColumns;
            default ->
                throw new UnsupportedOperationException("Unsupported database: " + dbKind);
        };
    }

    public String dbKind() { return dbKind; }
    public boolean isSqlite() { return "sqlite".equals(dbKind); }
    public boolean isH2() { return "h2".equals(dbKind); }
    public boolean isPostgresql() { return "postgresql".equals(dbKind); }
}
