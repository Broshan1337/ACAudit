package com.example.addon.modules.injection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUDIT helper: fingerprints SQL/database error text leaked to the client.
 *
 * When a plugin lets a database error reach the player (chat / kick reason), the
 * text usually names the driver, the engine, or the SQL syntax — which is the
 * strongest client-side signal of SQL injection there is (near-proof). This matches
 * the common public error shapes per engine, plus generic JDBC / stack-trace markers.
 *
 * Heuristic, like {@code AcSignalParser}: a hit is a strong SIGNAL to confirm against
 * the plugin's logs, not a courtroom proof. Sibling of the AC-signal parser.
 */
public final class SqlErrorFingerprint {
    private SqlErrorFingerprint() {}

    public record Hit(boolean found, String engine, String match) {
        public static final Hit NONE = new Hit(false, null, null);
    }

    /** engine -> identifying lowercase substrings. */
    private static final Map<String, List<String>> ENGINES = new LinkedHashMap<>();
    static {
        ENGINES.put("MySQL/MariaDB", List.of(
            "you have an error in your sql syntax", "mysqlsyntaxerrorexception", "com.mysql",
            "org.mariadb", "mariadb", "check the manual that corresponds to your", "mysql_fetch",
            "unknown column", "table", "doesn't exist", "extractvalue", "updatexml"));
        ENGINES.put("SQLite", List.of(
            "org.sqlite", "sqlite_error", "sqliteexception", "no such table", "sqlite3", "near \""));
        ENGINES.put("PostgreSQL", List.of(
            "org.postgresql", "psqlexception", "pg_", "syntax error at or near", "invalid input syntax"));
        ENGINES.put("SQLServer", List.of(
            "com.microsoft.sqlserver", "sqlserverexception", "incorrect syntax near", "unclosed quotation mark"));
        ENGINES.put("Oracle", List.of("ora-0", "oracle.jdbc", "quoted string not properly terminated"));
        ENGINES.put("MongoDB", List.of("mongoerror", "com.mongodb", "mongoexception", "$where", "bson"));
        ENGINES.put("Generic JDBC/JVM", List.of(
            "java.sql.sqlexception", "java.sql.", "jdbc", "preparedstatement", "sqlexception",
            "at java.", "at com.", "caused by:", "nested exception"));
    }

    /** Classify a server-originated string. */
    public static Hit scan(String text) {
        if (text == null) return Hit.NONE;
        String s = text.toLowerCase();
        if (s.isBlank()) return Hit.NONE;
        for (Map.Entry<String, List<String>> e : ENGINES.entrySet())
            for (String token : e.getValue())
                if (s.contains(token)) return new Hit(true, e.getKey(), token);
        return Hit.NONE;
    }
}
