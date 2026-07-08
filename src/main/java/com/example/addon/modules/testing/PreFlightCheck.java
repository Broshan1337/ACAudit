package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import com.example.addon.audit.DetectedPlugins;
import com.example.addon.modules.antidupe.GuiState;
import com.example.addon.modules.crash.ServerHealthMonitor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * AUDIT: Pre-Flight Checklist (run this FIRST, before any audit session)
 *
 * <p>An audit run is only as trustworthy as the context it runs in. A flood vector
 * that "passes" on a server whose TPS was already on the floor tells you nothing; a
 * dupe vector that needs an open shop GUI is wasted if none is open; a Vault-economy
 * probe is inert on a server with no economy plugin. This module gathers that context
 * in one place and tells you, in plain English, what is and isn't worth running right
 * now — so the operator isn't reading noise.
 *
 * <p>It aggregates signals the project already collects rather than re-detecting them:
 * <ul>
 *   <li>server brand / platform from {@code platform-probe} (Paper vs Spigot vs Folia
 *       changes which vectors are meaningful),</li>
 *   <li>detected plugins from {@link DetectedPlugins} (populated by command-fingerprint
 *       and the injection/plugin probes),</li>
 *   <li>a live TPS baseline from {@code server-health-monitor} (auto-enabled here) so you
 *       know the server is healthy BEFORE you start attacking it,</li>
 *   <li>an inventory scan flagging which item-gated modules you can actually run,</li>
 *   <li>whether a container / shop GUI is currently open.</li>
 * </ul>
 * It then prints a recommended test order and a rough Auto-Audit-Runner runtime estimate.
 *
 * <p>This is a READ-ONLY module — it sends nothing to the server. It only reports.
 * Enable {@code platform-probe} before joining for the brand to be captured. Own server only.
 */
public class PreFlightCheck extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> baselineTicks = sgGeneral.add(new IntSetting.Builder()
        .name("baseline-ticks").description("Ticks to sample TPS before reporting the baseline (20 ticks ~= 1s).")
        .defaultValue(60).range(20, 400).sliderRange(20, 200).build());
    private final Setting<Integer> perModuleSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("est-seconds-per-module").description("Rough seconds/module used for the Auto-Audit-Runner runtime estimate.")
        .defaultValue(12).range(2, 120).sliderRange(4, 40).build());
    private final Setting<Boolean> autoEnableHealth = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-enable-health").description("Auto-enable server-health-monitor to capture a TPS baseline.")
        .defaultValue(true).build());

    private ServerHealthMonitor health;
    private int ticks = 0;
    private boolean reported = false;

    public PreFlightCheck() {
        super(AddonTemplate.TESTING_CATEGORY, "pre-flight-check",
            "Read-only. Reports platform, detected plugins, TPS baseline, runnable item/GUI-gated modules, a recommended test order and a runtime estimate. Run FIRST.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        reported = false;
        health = Modules.get().get(ServerHealthMonitor.class);
        if (autoEnableHealth.get() && health != null && !health.isActive()) health.toggle();
        info("Pre-flight check running — sampling TPS for ~%d ticks before the report...", baselineTicks.get());
    }

    @meteordevelopment.orbit.EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        if (mc.player == null || reported) return;
        ticks++;
        boolean warm = health != null && health.isActive() && health.isWarm();
        if (ticks >= baselineTicks.get() && (warm || ticks >= baselineTicks.get() * 3)) {
            report();
            reported = true;
        }
    }

    private void report() {
        info("========== ACAudit Pre-Flight ==========");
        reportPlatform();
        reportPlugins();
        reportHealth();
        reportInventory();
        reportGui();
        reportOrderAndEstimate();
        info("========================================");
        info("Pre-flight complete — you can disable this module now.");
    }

    // --- platform ---
    private String platformClass = "unknown";

    private void reportPlatform() {
        Module pp = Modules.get().get("platform-probe");
        String brand = null, platform = null;
        if (pp instanceof PlatformProbe p) { brand = p.getBrand(); platform = p.getPlatform(); }
        if (brand == null || platform == null) {
            warning("Platform: UNKNOWN — enable 'platform-probe' before joining (or cross a dimension) to capture the brand.");
        } else {
            info("Platform: %s", platform);
            platformClass = platform.toLowerCase();
        }
        if (platformClass.contains("vanilla") || platformClass.contains("fabric") || platformClass.contains("forge"))
            warning("  -> No Bukkit plugins here: economy/dupe/injection plugin vectors are largely INERT. Movement/crash/packet vectors still apply.");
        if (platformClass.contains("spigot") || platformClass.contains("bukkit"))
            info("  -> No Paper packet-limiter: raw-flood vectors are NOT throttled at the network stage (a flood 'passing' here is real, not the limiter).");
        if (platformClass.contains("folia"))
            info("  -> Folia regionized threading: cross-region and async-race vectors are REAL here — prioritise folia-cross-region and the *-race suite.");
    }

    // --- plugins ---
    private boolean hasEconomy = false;

    private void reportPlugins() {
        Map<String, DetectedPlugins.Confidence> plugins = DetectedPlugins.snapshot();
        if (plugins.isEmpty()) {
            info("Plugins: none fingerprinted yet — run 'command-fingerprint' (and rejoin) first for economy/shop detection.");
            return;
        }
        info("Plugins fingerprinted (%d):", plugins.size());
        for (Map.Entry<String, DetectedPlugins.Confidence> e : plugins.entrySet()) {
            info("  - %s (%s)", e.getKey(), e.getValue());
            String k = e.getKey().toLowerCase();
            if (k.contains("vault") || k.contains("econ") || k.contains("shop") || k.contains("essentials")
                || k.contains("market") || k.contains("auction")) hasEconomy = true;
            String dupeClasses = knownDupeClasses(k);
            if (dupeClasses != null) info("      known dupe-class(es): %s", dupeClasses);
        }
        if (hasEconomy)
            info("  -> Economy stack present: econ-fuzz, sell-race, vault-value-probe, econ-gui-overload, scheduler-soak, chest-shop-race are IN SCOPE.");
    }

    /**
     * Maps a detected plugin (by keyword) to the dupe/exploit classes it is historically exposed to
     * and the ACAudit modules that test them. A LOCAL map (no external DB) — a reviewable, offline
     * "which known vectors apply to this stack" hint. Returns null when nothing specific matches.
     */
    private String knownDupeClasses(String k) {
        if (k.contains("essentials"))
            return "kit/relog save-on-quit (relog-dupe), /pay + sell race (sell-race, econ-fuzz), enderchest desync (enderchest-desync)";
        if (k.contains("shop") || k.contains("chestshop") || k.contains("quickshop"))
            return "buy/sell GUI + close race (chest-shop-race, gui-close-anomaly, econ-gui-overload), rate-limit gap (rate-gap-probe)";
        if (k.contains("auction") || k.contains("ah") || k.contains("market"))
            return "listing/claim race (auction-race), close-during-transaction (gui-close-anomaly DURING_TRANSACTION)";
        if (k.contains("vault") || k.contains("econ"))
            return "value overflow/precision (vault-value-probe, econ-fuzz), transaction storm (econ-gui-overload, scheduler-soak)";
        if (k.contains("crate") || k.contains("key"))
            return "crate result race (crate-result-race), menu-resurrection (gui-close-anomaly MENU_RESURRECTION)";
        if (k.contains("trade") || k.contains("villager") || k.contains("merchant"))
            return "trade select/restock race (merchant-trade-fuzz, trade-shop-overlap)";
        if (k.contains("protect") || k.contains("claim") || k.contains("region") || k.contains("guard") || k.contains("core"))
            return "plugin-vs-Paper rate gap on block actions (rate-gap-probe), unloaded-chunk interact (unloaded-chunk-interact)";
        if (k.contains("bungee") || k.contains("velocity") || k.contains("proxy"))
            return "proxy plugin-message trust incl. cross-player hijack (plugin-message-probe), cookie/transfer (cookie-probe)";
        return null;
    }

    // --- health ---
    private double baselineTps = -1;

    private void reportHealth() {
        if (health == null || !health.isActive()) {
            warning("TPS baseline: server-health-monitor not running — cannot confirm the server is healthy before testing.");
            return;
        }
        if (!health.isWarm()) {
            warning("TPS baseline: not warm yet (no time-update sampled). Re-run pre-flight after a few seconds.");
            return;
        }
        baselineTps = health.getTps();
        int ping = health.getPing();
        info("TPS baseline: ~%.1f | ping %dms", baselineTps, ping);
        if (baselineTps < 15.0)
            error("  -> TPS is ALREADY %.1f before any test. Results will be unreliable — let the server recover first.", baselineTps);
        else if (baselineTps < 19.0)
            warning("  -> TPS is %.1f (slightly degraded). Prefer PROBE-mode / low intensities so you can attribute drops to your test.", baselineTps);
        else
            info("  -> Healthy. Any sustained TPS drop during a test is attributable to that test.");
    }

    // --- inventory ---
    private void reportInventory() {
        if (mc.player == null) return;
        Set<String> flags = new LinkedHashSet<>();
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(s.getItem());
            String path = id.getPath();
            if (path.contains("shulker_box")) flags.add("shulker-race / nested-container vectors (shulker box present)");
            if (path.equals("writable_book") || path.equals("written_book") || path.equals("book_and_quill"))
                flags.add("payload-flood BOOK half + nasty-text (book present)");
            if (path.contains("bundle")) flags.add("bundle-dupe (bundle present)");
            if (path.equals("ender_chest")) flags.add("ender-chest-desync (ender chest present)");
            if (path.contains("anvil")) flags.add("anvil-name-injection / anvil-grindstone-race (anvil present)");
            if (path.contains("sign")) flags.add("sign-content-fuzz (sign present)");
            if (path.equals("name_tag")) flags.add("name-tag rename vectors (name tag present)");
        }
        boolean riding = mc.player.getVehicle() != null;
        if (riding) flags.add("vehicle-move / mount-crash / vehicle-sim-gap (currently riding)");
        if (flags.isEmpty()) {
            info("Inventory: no item-gated modules unlocked by current items. (Item-independent vectors still run.)");
        } else {
            info("Inventory unlocks:");
            for (String f : flags) info("  - %s", f);
        }
    }

    private void reportGui() {
        if (GuiState.containerOpen(mc))
            info("GUI: a container/shop GUI is OPEN (syncId %d) — GUI-gated vectors (gui-close-anomaly, gui-action-rotator, slot-* races, econ-gui-overload) can run now.",
                mc.player.currentScreenHandler.syncId);
        else
            info("GUI: none open — open your shop/container GUI before running GUI-gated modules (or they will no-op / hit your own inventory).");
    }

    // --- order + estimate ---
    private void reportOrderAndEstimate() {
        info("Recommended order (safest first):");
        info("  1) platform-probe + command-fingerprint (context) -> 2) server-health-monitor baseline (done above)");
        info("  3) passive monitors (packet-cadence/check-rate/correction-timing) -> 4) movement + dupe probes");
        if (hasEconomy) info("  5) economy/injection (econ-fuzz, sql-injection-fuzz, plugin-fuzz) while watching TPS");
        info("  %d) CRASH category LAST, in PROBE mode, with server-health-monitor watching (auto-abort on TPS floor).", hasEconomy ? 6 : 5);
        if (platformClass.contains("vanilla") || platformClass.contains("fabric") || platformClass.contains("forge"))
            info("  NOTE: skip the economy/injection step on this platform (no Bukkit plugins).");

        int total = Modules.get().getAll().size();
        long modules = Modules.get().getAll().stream()
            .filter(m -> m.category == AddonTemplate.MOVEMENT_CATEGORY || m.category == AddonTemplate.DUPE_CATEGORY
                || m.category == AddonTemplate.CRASH_CATEGORY || m.category == AddonTemplate.TESTING_CATEGORY
                || m.category == AddonTemplate.INJECTION_CATEGORY)
            .count();
        long est = modules * perModuleSeconds.get();
        info("Auto-Audit-Runner rough estimate: ~%d audit modules x %ds ~= %d min %d s (serial; parallel groups are faster).",
            modules, perModuleSeconds.get(), est / 60, est % 60);
        if (total > modules) info("  (%d total modules registered, %d in audit categories.)", total, modules);
    }

    @Override
    public void onDeactivate() { }
}
