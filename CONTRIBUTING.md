# Contributing to ACAudit

ACAudit is a **defensive, own-server** security-audit tool. Everything here exists to find vulnerabilities so they can be **patched** — not to attack servers you do not own. Two parts of ACAudit are designed to be extended by the community **without touching code**: the plugin-specific payload library and the saved packet-sequence library.

## 1. Plugin-specific payload library

The plugin-aware fuzzer (`plugin-fuzz`) is driven by a data file, not hardcoded payloads.

- **Bundled default:** `src/main/resources/assets/acaudit/plugin_payloads.json`
- **User/community override:** drop an edited copy at `config/acaudit/plugin_payloads.json` in your Minecraft directory. Entries are merged **by plugin `id`** (your file overrides the bundled entry with the same id, and adds new ones). No rebuild required — restart the game or toggle the module.

### Schema

```jsonc
{
  "version": 1,
  "plugins": [
    {
      "id": "litebans",                       // unique id
      "namespaces": ["litebans"],             // command namespaces (essentials:, cmi:) used for auto-detect
      "storage": "MYSQL",                     // FLATFILE | H2 | MYSQL | SQLITE | MIXED  (decides which payload types can work)
      "disclosureUrl": "https://gitlab.com/ruany/LiteBans/-/issues",
      "fixExample": "Use PreparedStatement; bind the reason parameter.",
      "commands": [
        {
          "template": "ban TestUser123 {x}",  // {x} = injection point; TestUser123 = replaced by the test-target setting
          "arg": "reason",
          "surface": "SQL",                   // SQL | DISPLAYED | PERMISSION
          "notes": "Reason stored in litebans_bans.",
          "payloads": [
            {
              "type": "SQLI",                 // SQLI | FORMAT | LENGTH | SPECIAL
              "text": "' OR '1'='1",
              "destructive": false,           // true = stacked/data-changing (gated off by default)
              "delayMs": 0,                   // >0 = time-based (gated off by default); expected delay in ms
              "note": "quote breakout",
              "vulnerableSignal": "SQL error echoed, or ban stored malformed"
            }
          ]
        }
      ]
    }
  ]
}
```

### Rules for good contributions

1. **Be honest about storage.** SQLi only works where SQL exists. If a plugin stores the targeted data in flat files (e.g. EssentialsX userdata), set `storage: "FLATFILE"` and contribute `FORMAT`/`LENGTH`/`SPECIAL` payloads instead — the fuzzer **skips SQLi on FLATFILE** targets on purpose.
2. **Char-filter safe.** Every payload must be ASCII-printable. Minecraft's command filter kicks on chars below `0x20`, `0x7F`, or the section sign — those test the server filter, not the plugin.
3. **Quality over quantity.** A few payloads that match the plugin's exact command syntax and DB dialect beat a large generic list.
4. **Document each payload** with `note`, `vulnerableSignal`, and a per-plugin `disclosureUrl` + `fixExample`.
5. **Safe defaults.** Mark anything that changes/deletes data `destructive: true`, and anything that intentionally lags the server with `delayMs > 0`. Both are off unless the auditor explicitly enables them.

## 2. Saved packet sequences

`packet-inspector` can save captured C2S sequences to `config/acaudit/sequences/*.json` (hybrid format: human-readable fields + base64 wire bytes). These are shareable as plain files (export = the file; import = point the library screen at a file).

Each sequence is self-documenting: `name`, `description`, `mcVersion`, `platform`, `detectedPlugins`, `tags`, `expectedVulnerable`, `expectedPatched`.

### Responsible-disclosure review (mandatory)

A sequence that demonstrates a **specific, unpatched vulnerability in a widely-used plugin must not be published** until the maintainer has had a reasonable chance to fix it. When contributing a sequence to the shared repository:

- Confirm the targeted plugin/version is **already patched**, or the finding is **already public**, before submitting.
- Include the patch signal / fix in `description` or `expectedPatched`, exactly like every other ACAudit module.
- Sequences that map AC behavior or test generic interactions (no unpatched-vuln demonstration) are always fine.

## Building

```
export JAVA_HOME=/path/to/jdk
./gradlew build --offline
```

The same standard applies to every contribution: document **what it tests, what a vulnerable response looks like, what a hardened response looks like, and the fix.**
