package com.example.addon;

import com.example.addon.commands.CommandExample;
import com.example.addon.hud.HudExample;
import com.example.addon.modules.ModuleExample;
import com.example.addon.modules.antidupe.AuctionRace;
import com.example.addon.modules.antidupe.ContainerExploit;
import com.example.addon.modules.antidupe.DropPickupDupe;
import com.example.addon.modules.antidupe.EconFuzz;
import com.example.addon.modules.antidupe.InteractionFlood;
import com.example.addon.modules.antidupe.SellRace;
import com.example.addon.modules.antidupe.SlotExploit;
import com.example.addon.modules.crash.*;
import com.example.addon.modules.movement.AirJump;
import com.example.addon.modules.movement.AntiSetback;
import com.example.addon.modules.movement.Bhop;
import com.example.addon.modules.movement.Blink;
import com.example.addon.modules.movement.HighJump;
import com.example.addon.modules.movement.InstantStep;
import com.example.addon.modules.movement.LowHopFly;
import com.example.addon.modules.movement.NoFall;
import com.example.addon.modules.movement.NoSlow;
import com.example.addon.modules.movement.OmniSprint;
import com.example.addon.modules.movement.PacketStep;
import com.example.addon.modules.movement.PingSpoof;
import com.example.addon.modules.movement.ResetVL;
import com.example.addon.modules.movement.Speed;
import com.example.addon.modules.movement.StealthFly;
import com.example.addon.modules.movement.Timer;
import com.example.addon.modules.movement.VelocityExploit;
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
    public static final HudGroup HUD_GROUP         = new HudGroup("Example");

    @Override
    public void onInitialize() {
        LOG.info("Initializing ACAudit");

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

        // --- Anti-dupe ---
        Modules.get().add(new SlotExploit());
        Modules.get().add(new InteractionFlood());
        Modules.get().add(new DropPickupDupe());
        Modules.get().add(new ContainerExploit());
        Modules.get().add(new EconFuzz());
        Modules.get().add(new SellRace());
        Modules.get().add(new AuctionRace());

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

        // --- Test harness ---
        Modules.get().add(new ServerHealthMonitor());
        Modules.get().add(new SoakTest());

        Commands.add(new CommandExample());
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(MOVEMENT_CATEGORY);
        Modules.registerCategory(DUPE_CATEGORY);
        Modules.registerCategory(CRASH_CATEGORY);
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
