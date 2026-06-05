package com.example.addon.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * One audit finding for one vector (or combo / scenario step).
 *
 * Carries the structured fields a reader (or a tool) needs to triage —
 * vector, category, severity, a short verdict word, and free-form detail lines.
 * Rendered three ways by {@link AuditReport}: human text, shareable markdown,
 * and machine JSON for regression diffing.
 *
 * The {@code verdict} is the one-word headline:
 *   PASS · FAIL · DISCONNECTED · DEGRADED · DUPLICATED · LOST · SILENT-ACCEPT ·
 *   DETECTED · UNDETECTED · RAN · SKIP · PLANNED · INFO
 */
public record Finding(
    String vector,
    String category,     // CRASH | DUPE | MOVEMENT | ECON | COMBO | PLATFORM | INFO
    Severity severity,
    String verdict,
    List<String> details
) {
    public Finding {
        details = details == null ? new ArrayList<>() : new ArrayList<>(details);
    }

    public static Finding of(String vector, String category, Severity sev, String verdict, String... details) {
        return new Finding(vector, category, sev, verdict, List.of(details));
    }

    /** Single text line: "[HIGH] vector  (CATEGORY)  UNDETECTED". */
    public String textLine() {
        return String.format("%-10s %-34s %-9s %s", severity.tag(), vector, category, verdict);
    }

    /** Markdown table row. */
    public String markdownRow() {
        String det = details.isEmpty() ? "" : String.join("; ", details).replace("|", "\\|");
        return String.format("| %s | `%s` | %s | %s | %s |", severity.name(), vector, category, verdict, det);
    }

    /** Stable key for regression diffing (one finding per vector+verdict-kind). */
    public String key() {
        return category + "/" + vector;
    }
}
