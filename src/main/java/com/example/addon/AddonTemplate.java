package com.example.addon;

import com.example.addon.commands.CommandExample;
import com.example.addon.hud.HudExample;
import com.example.addon.modules.ModuleExample;
import com.example.addon.modules.antidupe.AnvilGrindstoneRace;
import com.example.addon.modules.antidupe.AuctionRace;
import com.example.addon.modules.antidupe.StationResultRace;
import com.example.addon.modules.antidupe.BeaconEffectRace;
import com.example.addon.modules.antidupe.DeathDropRace;
import com.example.addon.modules.antidupe.RevisionAbuse;
import com.example.addon.modules.antidupe.CreativeDupe;
import com.example.addon.modules.antidupe.EventBypassProbe;
import com.example.addon.modules.antidupe.SpectatorTransition;
import com.example.addon.modules.antidupe.TradeShopOverlap;
import com.example.addon.modules.antidupe.ChestShopRace;
import com.example.addon.modules.antidupe.DeathInventoryRace;
import com.example.addon.modules.antidupe.DragSplitRace;
import com.example.addon.modules.antidupe.HopperRace;
import com.example.addon.modules.antidupe.PhantomContainer;
import com.example.addon.modules.antidupe.ShiftClickRace;
import com.example.addon.modules.antidupe.AutoRaceOnOpen;
import com.example.addon.modules.antidupe.BundleDupe;
import com.example.addon.modules.antidupe.CloseClick;
import com.example.addon.modules.antidupe.ContainerExploit;
import com.example.addon.modules.antidupe.CraftGridRace;
import com.example.addon.modules.antidupe.CrafterDupe;
import com.example.addon.modules.antidupe.DropPickupDupe;
import com.example.addon.modules.antidupe.EconFuzz;
import com.example.addon.modules.antidupe.EnderChestDesync;
import com.example.addon.modules.antidupe.GuiClicker;
import com.example.addon.modules.antidupe.GuiDesync;
import com.example.addon.modules.antidupe.InteractionFlood;
import com.example.addon.modules.antidupe.InventorySweep;
import com.example.addon.modules.antidupe.ManualClick;
import com.example.addon.modules.antidupe.OffhandSwapSpam;
import com.example.addon.modules.antidupe.PortalDupe;
import com.example.addon.modules.antidupe.RelogDupe;
import com.example.addon.modules.antidupe.SellRace;
import com.example.addon.modules.antidupe.ShulkerRace;
import com.example.addon.modules.antidupe.SlotExploit;
import com.example.addon.modules.antidupe.SlotOverlay;
import com.example.addon.modules.antidupe.TwoWindowRace;
import com.example.addon.modules.crash.*;
import com.example.addon.modules.testing.*;
import com.example.addon.modules.movement.AirJump;
import com.example.addon.modules.movement.AntiSetback;
import com.example.addon.modules.movement.MomentumBreak;
import com.example.addon.modules.movement.GroundStateForge;
import com.example.addon.modules.movement.JumpArcForge;
import com.example.addon.modules.movement.ModelDrift;
import com.example.addon.modules.movement.BlockUpdateRace;
import com.example.addon.modules.movement.ChunkEdgeMove;
import com.example.addon.modules.movement.LegitVelocityLaunder;
import com.example.addon.modules.movement.PhysicsAnomaly;
import com.example.addon.modules.movement.TransactionTiming;
import com.example.addon.modules.movement.StateMachineFuzz;
import com.example.addon.modules.movement.PacketOrderSkew;
import com.example.addon.modules.movement.CombatStateProbe;
import com.example.addon.modules.movement.Bhop;
import com.example.addon.modules.movement.Blink;
import com.example.addon.modules.movement.ElytraExploit;
import com.example.addon.modules.movement.HighJump;
import com.example.addon.modules.movement.InstantStep;
import com.example.addon.modules.movement.LowHopFly;
import com.example.addon.modules.movement.NoFall;
import com.example.addon.modules.movement.NoSlow;
import com.example.addon.modules.movement.OmniSprint;
import com.example.addon.modules.movement.PacketStep;
import com.example.addon.modules.movement.Phase;
import com.example.addon.modules.movement.PingSpoof;
import com.example.addon.modules.movement.ResetVL;
import com.example.addon.modules.movement.RiptideLaunch;
import com.example.addon.modules.movement.Speed;
import com.example.addon.modules.movement.StealthFly;
import com.example.addon.modules.movement.Timer;
import com.example.addon.modules.movement.VehicleMove;
import com.example.addon.modules.movement.VelocityExploit;
import com.example.addon.modules.movement.UncertaintyFarm;
import com.example.addon.modules.movement.OffsetBoundary;
import com.example.addon.modules.movement.InputLaunder;
import com.example.addon.modules.movement.CompensationBoundary;
import com.example.addon.modules.movement.SimGapSuite;
import com.example.addon.modules.movement.EntityPushModel;
import com.example.addon.modules.movement.ReachWorldStateRace;
import com.example.addon.modules.movement.SetbackInterference;
import com.example.addon.modules.movement.TimerBalanceSoak;
import com.example.addon.modules.movement.AccumulatorSoak;
import com.example.addon.modules.movement.FPCover;
import com.example.addon.modules.movement.SourceAttribution;
import com.example.addon.modules.movement.LowTPSReach;
import com.example.addon.modules.movement.BaselinePoison;
import com.example.addon.modules.movement.VehicleSimGap;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY          = new Category("Example");
    public static final Category MOVEMENT_CATEGORY = new Category("AuditAC-Movement");
    public static final Category DUPE_CATEGORY     = new Category("AuditAC-Dupe");
    public static final Category CRASH_CATEGORY    = new Category("AuditAC-Crash");
    public static final Category TESTING_CATEGORY  = new Category("AuditAC-Testing");
    public static final HudGroup HUD_GROUP         = new HudGroup("Example");

    @Override
    public void onInitialize() {
        LOG.info("Initializing ACAudit");

        // Register an outbound custom-payload type so channel-flood can ship a
        // valid plugin-channel header with an arbitrary (malformed/oversized) body.
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
            .register(com.example.addon.modules.crash.MalformedPayload.ID,
                      com.example.addon.modules.crash.MalformedPayload.CODEC);

        Modules.get().add(new ModuleExample());

        // --- Movement ---
        Modules.get().add(new LowHopFly());
        Modules.get().add(new OmniSprint());
        Modules.get().add(new VelocityExploit());
        Modules.get().add(new PacketStep());
        Modules.get().add(new HighJump());
        Modules.get().add(new AirJump());
        Modules.get().add(new Bhop());
        Modules.get().add(new Speed());
        Modules.get().add(new InstantStep());
        Modules.get().add(new StealthFly());
        Modules.get().add(new Blink());
        Modules.get().add(new PingSpoof());
        Modules.get().add(new NoFall());
        Modules.get().add(new ResetVL());
        Modules.get().add(new AntiSetback());
        Modules.get().add(new Timer());
        Modules.get().add(new NoSlow());
        Modules.get().add(new VehicleMove());
        Modules.get().add(new ElytraExploit());
        Modules.get().add(new RiptideLaunch());
        Modules.get().add(new Phase());

        // --- Movement deep-coverage (physics-consistency / AC-model probes) ---
        Modules.get().add(new MomentumBreak());
        Modules.get().add(new GroundStateForge());
        Modules.get().add(new JumpArcForge());
        Modules.get().add(new ModelDrift());
        Modules.get().add(new BlockUpdateRace());
        Modules.get().add(new ChunkEdgeMove());
        Modules.get().add(new LegitVelocityLaunder());
        Modules.get().add(new PhysicsAnomaly());
        Modules.get().add(new TransactionTiming());
        Modules.get().add(new StateMachineFuzz());
        Modules.get().add(new PacketOrderSkew());
        Modules.get().add(new CombatStateProbe());

        // --- Movement deep-coverage (modern/predictive-AC: prediction, compensation, simulation gaps, soak, FP) ---
        Modules.get().add(new UncertaintyFarm());
        Modules.get().add(new OffsetBoundary());
        Modules.get().add(new InputLaunder());
        Modules.get().add(new CompensationBoundary());
        Modules.get().add(new SimGapSuite());
        Modules.get().add(new EntityPushModel());
        Modules.get().add(new ReachWorldStateRace());
        Modules.get().add(new SetbackInterference());
        Modules.get().add(new TimerBalanceSoak());
        Modules.get().add(new AccumulatorSoak());
        Modules.get().add(new FPCover());
        Modules.get().add(new SourceAttribution());
        Modules.get().add(new LowTPSReach());
        Modules.get().add(new BaselinePoison());
        Modules.get().add(new VehicleSimGap());

        // --- Anti-dupe ---
        Modules.get().add(new SlotExploit());
        Modules.get().add(new InteractionFlood());
        Modules.get().add(new DropPickupDupe());
        Modules.get().add(new ContainerExploit());
        Modules.get().add(new EconFuzz());
        Modules.get().add(new SellRace());
        Modules.get().add(new AuctionRace());
        Modules.get().add(new GuiClicker());
        Modules.get().add(new ManualClick());
        Modules.get().add(new TwoWindowRace());
        Modules.get().add(new CloseClick());
        Modules.get().add(new SlotOverlay());
        Modules.get().add(new ShulkerRace());
        Modules.get().add(new GuiDesync());
        Modules.get().add(new AutoRaceOnOpen());
        Modules.get().add(new InventorySweep());
        Modules.get().add(new OffhandSwapSpam());
        Modules.get().add(new ShiftClickRace());
        Modules.get().add(new DragSplitRace());
        Modules.get().add(new PhantomContainer());
        Modules.get().add(new DeathInventoryRace());
        Modules.get().add(new ChestShopRace());
        Modules.get().add(new HopperRace());
        Modules.get().add(new RelogDupe());
        Modules.get().add(new EnderChestDesync());
        Modules.get().add(new CrafterDupe());
        Modules.get().add(new CraftGridRace());
        Modules.get().add(new BundleDupe());
        Modules.get().add(new PortalDupe());
        Modules.get().add(new AnvilGrindstoneRace());
        // --- Deep-coverage dupe vectors (precise timing, state windows, new stations) ---
        Modules.get().add(new StationResultRace());
        Modules.get().add(new BeaconEffectRace());
        Modules.get().add(new DeathDropRace());
        Modules.get().add(new RevisionAbuse());
        Modules.get().add(new CreativeDupe());
        Modules.get().add(new EventBypassProbe());
        Modules.get().add(new SpectatorTransition());
        Modules.get().add(new TradeShopOverlap());

        // --- Crash / stability ---
        Modules.get().add(new PayloadFlood());
        Modules.get().add(new NbtBomb());
        Modules.get().add(new NanPosition());
        Modules.get().add(new ExtremeVelocity());
        Modules.get().add(new BlockInteractionSpam());
        Modules.get().add(new ArmAnimationFlood());
        Modules.get().add(new SellCommandFuzz());

        // --- Crash addon ports ---
        Modules.get().add(new PositionCrash());
        Modules.get().add(new BookCrash());
        Modules.get().add(new CompletionCrash());
        Modules.get().add(new ContainerCrash());
        Modules.get().add(new CreativeCrash());
        Modules.get().add(new EntityCrash());
        Modules.get().add(new ErrorCrash());
        Modules.get().add(new InteractCrash());
        Modules.get().add(new LecternCrash());
        Modules.get().add(new MessageLagger());
        Modules.get().add(new MovementCrash());
        Modules.get().add(new PacketSpammer());
        Modules.get().add(new SequenceCrash());
        Modules.get().add(new WindowCrash());
        Modules.get().add(new SnbtDepth());
        Modules.get().add(new StructureStringFlood());
        Modules.get().add(new BeaconCrash());
        Modules.get().add(new PassengerLoop());
        Modules.get().add(new ChunkBorderStress());
        Modules.get().add(new PortalSpam());
        Modules.get().add(new EntitySpam());
        Modules.get().add(new MountCrash());
        Modules.get().add(new ChannelFlood());
        Modules.get().add(new PacketOrderChaos());

        // --- Fast-action rate testers ---
        Modules.get().add(new FastMine());
        Modules.get().add(new FastUse());
        Modules.get().add(new FastAttack());

        // --- Deep-coverage vectors (asymmetric cost, state transitions, edge surfaces) ---
        Modules.get().add(new NbtQueryFlood());
        Modules.get().add(new TrackerThrash());
        Modules.get().add(new ChunkAckSpoof());
        Modules.get().add(new RespawnRace());
        Modules.get().add(new StatsRequestFlood());
        Modules.get().add(new ResourcePackDesync());
        Modules.get().add(new SpectatorTpEdge());
        Modules.get().add(new TransitionRace());

        // --- Testing tab: diagnostics, automated harness, experimental abuse ---
        Modules.get().add(new ServerHealthMonitor());
        Modules.get().add(new SoakTest());
        Modules.get().add(new ServerProbe());
        Modules.get().add(new StressRunner());
        Modules.get().add(new LagProfiler());
        Modules.get().add(new TypedBlink());
        Modules.get().add(new PacketReorder());
        Modules.get().add(new ConfirmDesync());
        Modules.get().add(new MetadataFlood());
        Modules.get().add(new CheckRateMonitor());
        Modules.get().add(new CorrectionTimingMonitor());
        Modules.get().add(new PacketCadenceMonitor());
        Modules.get().add(new CombatPatternMonitor());
        Modules.get().add(new CommandRateLimitProbe());
        Modules.get().add(new AutoAuditRunner());
        Modules.get().add(new ComboTest());
        Modules.get().add(new VectorMatrix());

        Commands.add(new CommandExample());
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(MOVEMENT_CATEGORY);
        Modules.registerCategory(DUPE_CATEGORY);
        Modules.registerCategory(CRASH_CATEGORY);
        Modules.registerCategory(TESTING_CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
