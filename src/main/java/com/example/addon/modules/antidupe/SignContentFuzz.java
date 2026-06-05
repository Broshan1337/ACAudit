package com.example.addon.modules.antidupe;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.crash.PreStress;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * AUDIT: Sign Content Fuzz (sign-shop / sign-parsing plugins)
 *
 * PLATFORM: Paper-specific (sign-shop / economy plugins; ChestShop, QuickShop,
 * Essentials signs, etc.).
 *
 * Sign-based shop and economy plugins parse the text a player writes on a sign
 * (SignChangeEvent / the sign's stored lines) — a price line, a quantity, an item
 * name. Those lines arrive in UpdateSignC2SPacket as raw strings and feed the same
 * fragile parsers EconFuzz targets, but via a path many plugins forget to validate
 * as carefully as commands. This module sends fuzzed sign lines (the dangerous
 * numeric/format classes) to the sign you are editing and watches the response.
 *
 *   What it exploits: a sign-shop plugin trusting sign-line text (price/qty) without
 *     the same validation it (maybe) applies to its commands.
 *   Patch signal (any well-implemented plugin): validate sign-line input identically
 *     to command input — reject non-finite/negative/over-range prices, parse with a
 *     fixed locale, and bound line length.
 *
 * Open a sign editor first (right-click a sign you placed); this updates THAT sign.
 * Run on YOUR server only.
 */
public class SignContentFuzz extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<EconValues.Category> valueSet = sgGeneral.add(new EnumSetting.Builder<EconValues.Category>()
        .name("value-set")
        .description("Which class of dangerous values to put on the sign. SIGN (default) = the curated short set; pick BIG_SCIENTIFIC / INTEGER_LONG / UNICODE_DIGITS / etc. to target a class, or ALL for everything.")
        .defaultValue(EconValues.Category.SIGN).build()
    );

    private final Setting<String> line0 = sgGeneral.add(new StringSetting.Builder()
        .name("line-0").description("Sign line 1 template ({n}=fuzz value). ChestShop expects owner/blank here.")
        .defaultValue("").build()
    );
    private final Setting<String> line1 = sgGeneral.add(new StringSetting.Builder()
        .name("line-1").description("Sign line 2 template ({n}=fuzz value). Often the quantity.")
        .defaultValue("{n}").build()
    );
    private final Setting<String> line2 = sgGeneral.add(new StringSetting.Builder()
        .name("line-2").description("Sign line 3 template ({n}=fuzz value). Often the price (B/S).")
        .defaultValue("B {n} S {n}").build()
    );
    private final Setting<String> line3 = sgGeneral.add(new StringSetting.Builder()
        .name("line-3").description("Sign line 4 template ({n}=fuzz value). Often the item.")
        .defaultValue("DIAMOND").build()
    );
    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks between sign updates.")
        .defaultValue(15).range(1, 100).sliderRange(2, 40).build()
    );
    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop").description("Restart the value list instead of disabling at the end.")
        .defaultValue(false).build()
    );

    private final PreStress preStress = new PreStress(sgGeneral);
    private final DupeObserver obs = new DupeObserver(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.")
        .defaultValue(true).build()
    );
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print summary on deactivate.")
        .defaultValue(true).build()
    );

    private String[] values = EconValues.SIGN;
    private int index = 0, timer = 0, ticksActive = 0, packetsSent = 0;

    public SignContentFuzz() {
        super(AddonTemplate.DUPE_CATEGORY, "sign-content-fuzz",
            "Sends fuzzed sign-line text (dangerous numeric/format values) to the sign you are editing. Tests sign-shop/economy plugins' sign-text parsing.");
    }

    @Override
    public void onActivate() {
        ticksActive = 0; packetsSent = 0; index = 0; timer = 0;
        values = EconValues.forCategory(valueSet.get());
        info("Value-set: %s (%d values).", valueSet.get(), values.length);
        obs.onActivate(); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        obs.tick();
        if (timer > 0) { timer--; return; }

        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            warning("Look at the sign you are editing.");
            timer = delayTicks.get();
            return;
        }
        BlockPos pos = hit.getBlockPos();
        String n = values[index % values.length];
        mc.player.networkHandler.sendPacket(new UpdateSignC2SPacket(pos, true,
            line0.get().replace("{n}", n), line1.get().replace("{n}", n),
            line2.get().replace("{n}", n), line3.get().replace("{n}", n)));
        info("[%d/%d] sign @ %s line value '%s'", (index % values.length) + 1, values.length, pos.toShortString(), n);
        packetsSent++;
        obs.markFired();

        index++;
        timer = delayTicks.get();
        if (index >= values.length) {
            if (loop.get()) index = 0;
            else toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d sign updates sent.", ticksActive, packetsSent);
            obs.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { obs.survey(event.packet, l -> info("%s", l)); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        obs.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
