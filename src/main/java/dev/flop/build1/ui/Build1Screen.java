package dev.flop.build1.ui;

import dev.flop.build1.Build1Client;
import dev.flop.build1.Config;
import dev.flop.build1.LitematicaBridge;
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
        int y = 44;

        // --- Builder column ---
        btn(left, y, cw, () -> "§lBuild schematic", () -> { Build1Client.startBuild(); onClose(); });
        btn(left, y + 24, cw, () -> "Replace wrong: " + onOff(Config.replaceWrong), () -> Config.replaceWrong = !Config.replaceWrong);
        btn(left, y + 48, cw, () -> "Clear extra blocks: " + onOff(Config.clearExtra), () -> Config.clearExtra = !Config.clearExtra);
        btn(left, y + 72, cw, () -> "Pillar up: " + onOff(Config.pillar), () -> Config.pillar = !Config.pillar);
        btn(left, y + 96, cw, () -> "Blocks/tick: " + Config.blocksPerTick, () -> Config.blocksPerTick = Config.blocksPerTick % 4 + 1);

        // --- Miner column ---
        btn(right, y, cw, () -> "§lMine area", () -> { Build1Client.startMine(); onClose(); });
        btn(right, y + 24, 73, () -> "Corner 1", () -> Build1Client.setCorner(1, null));
        btn(right + 77, y + 24, 73, () -> "Corner 2", () -> Build1Client.setCorner(2, null));
        btn(right, y + 48, cw, () -> "Avoid lava: " + onOff(Config.avoidLava), () -> Config.avoidLava = !Config.avoidLava);
        btn(right, y + 72, cw, () -> "Clear corners", () -> { Build1Client.corner1 = null; Build1Client.corner2 = null; });
        btn(right, y + 96, cw, () -> "Low-health pause: " + (Config.minHealth == 0 ? "§cOFF" : (Config.minHealth / 2) + "♥"),
                () -> Config.minHealth = Config.minHealth >= 16 ? 0 : Config.minHealth + 4);

        // --- shared ---
        int y2 = y + 128;
        btn(left, y2, cw, () -> "Walk / pathfind: " + onOff(Config.walk), () -> Config.walk = !Config.walk);
        btn(right, y2, cw, () -> "Delay: " + Config.actionDelay + " tick" + (Config.actionDelay == 1 ? "" : "s"),
                () -> Config.actionDelay = (Config.actionDelay + 1) % 6);
        btn(left, y2 + 24, cw, () -> "Swap slot: " + (Config.hotbarSlot + 1), () -> Config.hotbarSlot = (Config.hotbarSlot + 1) % 9);
        btn(right, y2 + 24, cw, () -> "HUD: " + onOff(Config.hud), () -> Config.hud = !Config.hud);

        int y3 = y2 + 56;
        btn(left, y3, cw, () -> Build1Client.paused() ? "Resume" : "Pause", Build1Client::togglePause);
        btn(right, y3, cw, () -> "§cStop", () -> Build1Client.stop(true));
        btn(width / 2 - 50, y3 + 28, 100, () -> "Done", this::onClose);
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
    }

    @Override
    public void onClose() {
        Config.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
