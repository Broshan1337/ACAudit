package com.example.addon.audit;

import java.util.List;

/**
 * Structured responsible-disclosure metadata for an audit vector.
 *
 * <p>Every ACAudit module that demonstrates a real server-side weakness should expose a
 * {@code static final Disclosure} so the finding a server owner reads is not just "it
 * fired" but the four things they actually need: which plugins are exposed, the code
 * pattern that causes it, the one-line fix, and how serious/accessible it is. Making it a
 * typed field (rather than only Javadoc prose) lets reports and the audit pipeline collate
 * a "what to fix" section automatically, and lets CONTRIBUTING enforce it on new modules.
 *
 * @param vulnerableWhen the plugin types / code shapes most commonly vulnerable
 * @param rootCause      the specific pattern that creates the vulnerability
 * @param fix            a concrete fix a plugin developer can apply immediately
 * @param severity       how serious a successful exploit is against production
 * @param access         what the attacker needs — e.g. "any player, no op, no items"
 */
public record Disclosure(String vulnerableWhen, String rootCause, String fix, Severity severity, String access) {

    /** Human-readable lines for chat / report output. */
    public List<String> lines() {
        return List.of(
            "  Disclosure — severity " + severity + " · access: " + access,
            "    vulnerable when: " + vulnerableWhen,
            "    root cause:      " + rootCause,
            "    fix:             " + fix);
    }

    /** Compact one-line form for dense listings. */
    public String oneLine() {
        return "[" + severity + "] " + vulnerableWhen + " — fix: " + fix + " (" + access + ")";
    }
}
