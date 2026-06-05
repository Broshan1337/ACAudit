package com.example.addon.modules.testing;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;

/**
 * AUDIT: Platform Probe (server-stack context)
 *
 * PLATFORM: detects the platform itself.
 *
 * Every other module's result must be read in the context of WHAT the server is.
 * The server announces its brand on the minecraft:brand channel; this module
 * captures it and classifies the platform (Paper / Spigot / CraftBukkit / Folia /
 * Purpur / Fabric / Forge / vanilla), because the same vector behaves differently
 * per platform — e.g. Paper's packet-limiter throttles floods Spigot does not, and
 * Folia's regionized threading makes the async-race vectors real. It also watches
 * for disconnects whose reason indicates Paper's packet-limiter engaged, so a
 * "flood failed" elsewhere can be attributed to the limiter rather than to good
 * validation.
 *
 * Enable BEFORE joining (or across a dimension change) to reliably capture the
 * brand payload, which the server sends at join. Run on YOUR server.
 */
public class PlatformProbe extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce").description("Print the detected platform as soon as the brand is captured.")
        .defaultValue(true).build()
    );

    private String brand = null;
    private String platform = "unknown";

    public PlatformProbe() {
        super(AddonTemplate.TESTING_CATEGORY, "platform-probe",
            "Captures the server brand and classifies the platform (Paper/Spigot/Folia/Purpur/...). Context for every other audit result.");
    }

    @Override
    public void onActivate() { /* keep last capture across re-toggles for convenience */ }

    /** Captured server brand string, or null if not seen yet. */
    public String getBrand() { return brand; }
    /** Human platform classification ("unknown" until a brand is captured). */
    public String getPlatform() { return platform; }

    private String classify(String b) {
        String s = b.toLowerCase();
        if (s.contains("folia")) return "Folia (regionized threading — async-race vectors are REAL here)";
        if (s.contains("purpur")) return "Purpur (Paper fork — Paper protections apply)";
        if (s.contains("paper")) return "Paper (packet-limiter + built-in protections active)";
        if (s.contains("spigot")) return "Spigot (no packet-limiter; Bukkit events; fewer built-in dupe fixes)";
        if (s.contains("bukkit")) return "CraftBukkit (minimal protections)";
        if (s.contains("fabric")) return "Fabric (modded; no Bukkit plugins/Vault)";
        if (s.contains("forge") || s.contains("neoforge")) return "Forge/NeoForge (modded; no Bukkit plugins/Vault)";
        if (s.equals("vanilla")) return "vanilla (no plugins — most ACAudit plugin vectors are inert)";
        return "unrecognized brand";
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof CustomPayloadS2CPacket p && p.payload() instanceof BrandCustomPayload b) {
            brand = b.brand();
            platform = classify(brand);
            if (announce.get()) {
                info("Server brand: \"%s\"", brand);
                info("Platform: %s", platform);
            }
        } else if (event.packet instanceof DisconnectS2CPacket d) {
            String r = d.reason().getString().toLowerCase();
            if (r.contains("packet") || r.contains("spam") || r.contains("too many") || r.contains("limit"))
                warning("Disconnect looks like a packet-limiter/anti-spam kick: \"%s\"", d.reason().getString());
        }
    }

    @Override
    public void onDeactivate() {
        if (brand != null) info("Last detected: \"%s\" -> %s", brand, platform);
        else info("No brand captured (enable before joining / across a dimension change).");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { /* keep brand for the report */ }
}
