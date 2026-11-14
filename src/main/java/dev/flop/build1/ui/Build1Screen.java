package dev.flop.build1.ui;

import dev.flop.build1.Build1Client;
import dev.flop.build1.Config;
import dev.flop.build1.LitematicaBridge;
import dev.flop.build1.bot.ChestRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** One simple menu: start build / mine, corners, and the handful of settings that matter. */
public class Build1Screen extends Screen {
    private final Screen parent;
    private final List<Runnable> refreshers = new ArrayList<>();

    public Build1Screen(Screen parent) {
        super(Component.literal("Build1"));
        this.parent = parent;
    }

    private Button btn(int x, int y, int w, Supplier<String> label, Runnable action) {
        Button b = Button.builder(Component.literal(label.get()), bb -> { action.run(); refresh(); }).bounds(x, y, w, 20).build();
        refreshers.add(() -> b.setMessage(Component.literal(label.get())));
        return addRenderableWidget(b);
    }

    private void refresh() { refreshers.forEach(Runnable::run); }

    private static String onOff(boolean b) { return b ? "§aON" : "§cOFF"; }

    @Override
    protected void init() {
        refreshers.clear();
        int cw = 150, gap = 6;
        int left = width / 2 - cw - gap / 2, right = width / 2 + gap / 2;
        int y = 46;

        // --- mode, full width ---
        btn(left, y, cw * 2 + gap, () -> "Mode: " + (Config.closet()
                        ? "§aCloset§r §7(looks like a player)" : "§cBlatant§r §7(fast, obvious)"),
                () -> Config.mode = Config.closet() ? Config.Mode.BLATANT : Config.Mode.CLOSET);
        y += 26;

        // --- Builder column ---
        btn(left, y, cw, () -> "§lBuild schematic", () -> { Build1Client.startBuild(); onClose(); });
        btn(left, y + 22, cw, () -> "Replace wrong: " + onOff(Config.replaceWrong), () -> Config.replaceWrong = !Config.replaceWrong);
        btn(left, y + 44, cw, () -> "Clear extra blocks: " + onOff(Config.clearExtra), () -> Config.clearExtra = !Config.clearExtra);
        btn(left, y + 66, cw, () -> "Follow Litematica layers: " + onOff(Config.followLayers), () -> Config.followLayers = !Config.followLayers);
        btn(left, y + 88, cw, () -> "Pillar up: " + onOff(Config.pillar), () -> Config.pillar = !Config.pillar);
        btn(left, y + 110, cw, () -> Config.closet() ? "§8Air place (blatant only)" : "Air place: " + onOff(Config.airPlace),
                () -> Config.airPlace = !Config.airPlace);

        // --- Miner column ---
        btn(right, y, cw, () -> "§lDig out...", () -> minecraft.setScreen(new DigScreen(this)));
        btn(right, y + 22, 73, () -> "Corner 1", () -> Build1Client.setCorner(1, null));
        btn(right + 77, y + 22, 73, () -> "Corner 2", () -> Build1Client.setCorner(2, null));
        btn(right, y + 44, cw, () -> "Clear corners", () -> { Build1Client.corner1 = null; Build1Client.corner2 = null; });
        btn(right, y + 66, cw, () -> "Avoid lava: " + onOff(Config.avoidLava), () -> Config.avoidLava = !Config.avoidLava);
        btn(right, y + 88, cw, () -> "Low-health pause: " + (Config.minHealth == 0 ? "§cOFF" : (Config.minHealth / 2) + "♥"),
                () -> Config.minHealth = Config.minHealth >= 16 ? 0 : Config.minHealth + 4);
        btn(right, y + 110, cw, () -> "Sign text from schematic: " + onOff(Config.signText), () -> Config.signText = !Config.signText);

        // --- Survival ---
        int y2 = y + 140;
        btn(left, y2, cw, () -> "Survival restock: " + onOff(Config.survival), () -> Config.survival = !Config.survival);
        btn(right, y2, cw, () -> "Refill check: every " + Config.restockMinutes + " min",
                () -> Config.restockMinutes = Config.restockMinutes >= 10 ? 1 : Config.restockMinutes + 1);
        btn(left, y2 + 22, cw, () -> "Add/remove chest (crosshair)", Build1Client::toggleChest);
        btn(right, y2 + 22, cw, () -> "Find \"materials\" signs", () -> {
            int n = ChestRegistry.scan(minecraft.player.blockPosition(), 48);
            Build1Client.msg("Found " + n + " chest(s) by sign. Total: " + ChestRegistry.count());
        });

        // --- shared ---
        int y3 = y2 + 50;
        btn(left, y3, cw, () -> "Walk / pathfind: " + onOff(Config.walk), () -> Config.walk = !Config.walk);
        btn(right, y3, cw, () -> "Delay: " + Config.actionDelay + " tick" + (Config.actionDelay == 1 ? "" : "s"),
                () -> Config.actionDelay = (Config.actionDelay + 1) % 6);
        btn(left, y3 + 22, cw, () -> Config.closet() ? "§8Blocks/tick: 1 (closet)" : "Blocks/tick: " + Config.blocksPerTick,
                () -> Config.blocksPerTick = Config.blocksPerTick % 4 + 1);
        btn(right, y3 + 22, 73, () -> "Swap slot: " + (Config.hotbarSlot + 1), () -> Config.hotbarSlot = (Config.hotbarSlot + 1) % 9);
        btn(right + 77, y3 + 22, 73, () -> "HUD: " + onOff(Config.hud), () -> Config.hud = !Config.hud);

        int y4 = y3 + 50;
        btn(left, y4, cw, () -> Build1Client.paused() ? "Resume" : "Pause", Build1Client::togglePause);
        btn(right, y4, cw, () -> "§cStop", () -> Build1Client.stop(true));
        btn(width / 2 - 50, y4 + 24, 100, () -> "Done", this::onClose);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = width / 2;
        g.drawCenteredString(font, "§lBuild1", cx, 10, 0xFFFFFFFF);
        String status = Build1Client.running() ? (Build1Client.paused() ? "§6Paused: " : "§aRunning: ") + Build1Client.taskName() : "§7Idle";
        g.drawCenteredString(font, status, cx, 22, 0xFFFFFFFF);

        String lit;
        if (!LitematicaBridge.installed()) lit = "§cLitematica not installed";
        else {
            var pl = LitematicaBridge.selectedPlacement();
            lit = pl == null ? "§7No placement loaded" : "§f" + pl.name() + " §7" + pl.box().sizeX() + "x" + pl.box().sizeY() + "x" + pl.box().sizeZ();
        }
        g.drawCenteredString(font, lit, cx - 78, 34, 0xFFFFFFFF);
        var box = Build1Client.mineBox();
        String area = box == null ? "§7No area set" : "§f" + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ()
                + (Build1Client.corner1 != null && Build1Client.corner2 != null ? "" : " §7(Litematica selection)");
        g.drawCenteredString(font, area, cx + 78, 34, 0xFFFFFFFF);
        if (Config.survival) g.drawCenteredString(font, "§7Materials chests: " + ChestRegistry.count(), cx, 46 + 26 + 132, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Config.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
