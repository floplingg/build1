package dev.flop.build1;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.flop.build1.LitematicaBridge.Box;
import dev.flop.build1.bot.Bot;
import dev.flop.build1.task.BuildTask;
import dev.flop.build1.task.MineTask;
import dev.flop.build1.task.Task;
import dev.flop.build1.ui.Build1Screen;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class Build1Client implements ClientModInitializer {
    public static final String MOD_ID = "build1";

    public static KeyMapping menuKey, stopKey, pauseKey, pos1Key, pos2Key;

    private static Task task;
    private static boolean paused;
    public static BlockPos corner1, corner2;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.build1.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_B, cat));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.build1.stop", InputConstants.Type.KEYSYM, InputConstants.KEY_X, cat));
        pauseKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.build1.pause", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));
        pos1Key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.build1.pos1", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));
        pos2Key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.build1.pos2", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));

        ClientTickEvents.END_CLIENT_TICK.register(Build1Client::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> stop(false));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!Config.hud || task == null || mc.options.hideGui) return;
            List<String> lines = new java.util.ArrayList<>(task.hud());
            if (paused) lines.add(0, "§6PAUSED");
            int w = 0;
            for (String s : lines) w = Math.max(w, mc.font.width(s));
            int x = 4, y = 4;
            g.fill(x - 2, y - 2, x + w + 2, y + lines.size() * 10, 0x90000000);
            for (String s : lines) { g.drawString(mc.font, s, x, y, 0xFFFFFFFF, true); y += 10; }
        });

        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(ClientCommandManager.literal("build1")
                .executes(c -> { openMenu(); return 1; })
                .then(ClientCommandManager.literal("build").executes(c -> { startBuild(); return 1; }))
                .then(ClientCommandManager.literal("mine").executes(c -> { startMine(); return 1; }))
                .then(ClientCommandManager.literal("stop").executes(c -> { stop(true); return 1; }))
                .then(ClientCommandManager.literal("pause").executes(c -> { togglePause(); return 1; }))
                .then(ClientCommandManager.literal("pos1").executes(c -> { setCorner(1, null); return 1; })
                        .then(xyz(1)))
                .then(ClientCommandManager.literal("pos2").executes(c -> { setCorner(2, null); return 1; })
                        .then(xyz(2)))
                .then(ClientCommandManager.literal("clear").executes(c -> { corner1 = corner2 = null; msg("Miner corners cleared."); return 1; }))));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource, ?> xyz(int which) {
        return ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(c -> {
                                    setCorner(which, new BlockPos(IntegerArgumentType.getInteger(c, "x"),
                                            IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")));
                                    return 1;
                                })));
    }

    private static void tick(Minecraft mc) {
        while (menuKey.consumeClick()) openMenu();
        while (stopKey.consumeClick()) if (task != null) stop(true);
        while (pauseKey.consumeClick()) togglePause();
        while (pos1Key.consumeClick()) setCorner(1, null);
        while (pos2Key.consumeClick()) setCorner(2, null);

        if (task == null || mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (paused) return;
        if (!mc.player.isAlive()) { stop(true); return; }
        if (Config.minHealth > 0 && mc.player.getHealth() <= Config.minHealth && !mc.player.getAbilities().invulnerable) {
            msg("§cHealth low — paused. Heal up and resume from the menu.");
            setPaused(true);
            return;
        }
        try {
            task.tick();
        } catch (Throwable t) {
            msg("§cBuild1 crashed in its task and stopped: " + t);
            t.printStackTrace();
            stop(false);
            return;
        }
        if (task != null && task.finished()) { task.stop(); task = null; Bot.releaseKeys(); }
    }

    // ---------- actions (used by menu, keys and commands) ----------

    public static void openMenu() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (!(mc.screen instanceof Build1Screen)) mc.setScreen(new Build1Screen(null)); });
    }

    public static void startBuild() {
        stop(false);
        BuildTask b = BuildTask.create();
        if (b != null) { task = b; paused = false; }
    }

    public static void startMine() {
        stop(false);
        Box box = mineBox();
        if (box == null) {
            msg("§cSet two corners first (menu → Corner 1/2 at crosshair, /build1 pos1 & pos2), or make an area selection in Litematica.");
            return;
        }
        if (box.volume() > 2_000_000) { msg("§cThat area is huge (" + box.volume() + " blocks). Pick something smaller than 2M."); return; }
        task = new MineTask(box);
        paused = false;
    }

    /** Our own corners win; otherwise Litematica's current area selection. */
    public static Box mineBox() {
        if (corner1 != null && corner2 != null) return Box.of(corner1, corner2);
        return LitematicaBridge.areaSelection();
    }

    public static void stop(boolean announce) {
        if (task != null) {
            task.stop();
            if (announce) msg("Stopped.");
        }
        task = null;
        paused = false;
        Bot.releaseKeys();
    }

    public static void togglePause() { if (task != null) setPaused(!paused); }

    public static void setPaused(boolean p) {
        paused = p;
        if (p && task != null) { task.stop(); Bot.releaseKeys(); }
    }

    public static boolean running() { return task != null; }
    public static boolean paused() { return paused; }
    public static String taskName() { return task == null ? "idle" : task.name(); }

    public static void setCorner(int which, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (pos == null) {
            HitResult hr = mc.hitResult;
            if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) pos = bhr.getBlockPos();
            else if (mc.player != null) pos = mc.player.blockPosition();
            else return;
        }
        if (which == 1) corner1 = pos.immutable(); else corner2 = pos.immutable();
        msg("Miner corner " + which + " = " + pos.toShortString()
                + (corner1 != null && corner2 != null ? "  §7area " + Box.of(corner1, corner2) : ""));
    }

    public static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) mc.gui.getChat().addMessage(Component.literal("§b[Build1]§r " + s));
    }
}
