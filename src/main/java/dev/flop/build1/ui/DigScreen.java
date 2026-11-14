package dev.flop.build1.ui;

import dev.flop.build1.Build1Client;
import dev.flop.build1.Config;
import dev.flop.build1.Config.DigShape;
import dev.flop.build1.LitematicaBridge;
import dev.flop.build1.LitematicaBridge.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Beginner-friendly digging: pick Hole or Tunnel, set the size with +/-, and see the exact
 * area outlined in the world before you press Dig. Corners mode is there for custom areas.
 */
public class DigScreen extends Screen {
    private final Screen parent;
    private final List<Runnable> refreshers = new ArrayList<>();

    public DigScreen(Screen parent) {
        super(Component.literal("Dig Out"));
        this.parent = parent;
    }

    private Button btn(int x, int y, int w, Supplier<String> label, Runnable action) {
        Button b = Button.builder(Component.literal(label.get()), bb -> { action.run(); refresh(); }).bounds(x, y, w, 20).build();
        refreshers.add(() -> b.setMessage(Component.literal(label.get())));
        return addRenderableWidget(b);
    }

    private void refresh() { refreshers.forEach(Runnable::run); Preview.show(box(), 200); }

    private void stepper(int x, int y, String name, Supplier<Integer> get, java.util.function.IntConsumer set, int min, int max) {
        btn(x, y, 20, () -> "-", () -> set.accept(Math.max(min, get.get() - (hasStep() ? 5 : 1))));
        btn(x + 22, y, 106, () -> name + ": §e" + get.get(), () -> {});
        btn(x + 130, y, 20, () -> "+", () -> set.accept(Math.min(max, get.get() + (hasStep() ? 5 : 1))));
    }

    private boolean hasStep() { return false; }

    /** The area that will be dug with the current settings. */
    public static Box box() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return null;
        if (Config.digShape == DigShape.CORNERS) return Build1Client.mineBox();
        Direction f = mc.player.getDirection();
        Direction right = f.getClockWise();
        BlockPos feet = mc.player.blockPosition();
        int w = Config.digWidth, l = Config.digLength;
        BlockPos start = feet.relative(f, 1).relative(right, -(w - 1) / 2);
        BlockPos far = start.relative(f, l - 1).relative(right, w - 1);
        if (Config.digShape == DigShape.HOLE) {
            return Box.of(start.below(1), far.below(Config.digDepth));
        }
        return Box.of(start, far.above(Config.digHeight - 1));
    }

    @Override
    protected void init() {
        refreshers.clear();
        Preview.setSticky(true);
        int cx = width / 2, x = cx - 152, y = 40;

        btn(x, y, 98, () -> (Config.digShape == DigShape.HOLE ? "§a> " : "") + "Hole (down)", () -> { Config.digShape = DigShape.HOLE; rebuildWidgets(); });
        btn(x + 102, y, 100, () -> (Config.digShape == DigShape.TUNNEL ? "§a> " : "") + "Tunnel (forward)", () -> { Config.digShape = DigShape.TUNNEL; rebuildWidgets(); });
        btn(x + 206, y, 98, () -> (Config.digShape == DigShape.CORNERS ? "§a> " : "") + "Custom corners", () -> { Config.digShape = DigShape.CORNERS; rebuildWidgets(); });
        y += 30;

        if (Config.digShape == DigShape.CORNERS) {
            btn(x, y, 150, () -> "Corner 1: " + (Build1Client.corner1 == null ? "§7not set" : "§f" + Build1Client.corner1.toShortString()),
                    () -> Build1Client.setCorner(1, null));
            btn(x + 154, y, 150, () -> "Corner 2: " + (Build1Client.corner2 == null ? "§7not set" : "§f" + Build1Client.corner2.toShortString()),
                    () -> Build1Client.setCorner(2, null));
            btn(x, y + 22, 304, () -> "Clear corners", () -> { Build1Client.corner1 = null; Build1Client.corner2 = null; });
            y += 50;
        } else {
            stepper(x, y, "Width", () -> Config.digWidth, v -> Config.digWidth = v, 1, 64);
            stepper(x + 154, y, "Length", () -> Config.digLength, v -> Config.digLength = v, 1, 128);
            if (Config.digShape == DigShape.HOLE) stepper(x, y + 22, "Depth", () -> Config.digDepth, v -> Config.digDepth = v, 1, 64);
            else stepper(x, y + 22, "Height", () -> Config.digHeight, v -> Config.digHeight = v, 1, 16);
            y += 50;
        }

        btn(x, y, 150, () -> "Avoid lava: " + (Config.avoidLava ? "§aON" : "§cOFF"), () -> Config.avoidLava = !Config.avoidLava);
        btn(x + 154, y, 150, () -> "Inventory full: " + (Config.fullInv == Config.FullInv.PAUSE ? "§ePause" : "§bDrop junk"),
                () -> Config.fullInv = Config.fullInv == Config.FullInv.PAUSE ? Config.FullInv.DROP_JUNK : Config.FullInv.PAUSE);
        btn(x, y + 22, 150, () -> "Mode: " + (Config.closet() ? "§aCloset" : "§cBlatant"),
                () -> Config.mode = Config.closet() ? Config.Mode.BLATANT : Config.Mode.CLOSET);
        btn(x + 154, y + 22, 150, () -> "Low-health pause: " + (Config.minHealth == 0 ? "§cOFF" : (Config.minHealth / 2) + "♥"),
                () -> Config.minHealth = Config.minHealth >= 16 ? 0 : Config.minHealth + 4);
        y += 56;

        btn(x, y, 304, () -> {
            Box b = box();
            return b == null ? "§7Set the area first" : "§l§aDig " + b.sizeX() + "×" + b.sizeY() + "×" + b.sizeZ() + " §r§7(" + b.volume() + " blocks)";
        }, () -> {
            Box b = box();
            if (b == null) return;
            Config.save();
            Preview.setSticky(false);
            minecraft.setScreen(null);
            Build1Client.startMine(b);
        });
        btn(cx - 50, y + 26, 100, () -> "Back", this::onClose);
        Preview.show(box(), 200);
    }

    @Override
    public void tick() {
        super.tick();
        Preview.show(box(), 40); // follows you as you turn, for the quick shapes
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = width / 2;
        g.drawCenteredString(font, "§lDig Out", cx, 8, 0xFFFFFFFF);
        String help = switch (Config.digShape) {
            case HOLE -> "Digs a hole starting 1 block in front of you, going down. Face where you want it.";
            case TUNNEL -> "Digs a tunnel starting 1 block in front of you, at your feet level.";
            case CORNERS -> Build1Client.mineBox() != null && (Build1Client.corner1 == null || Build1Client.corner2 == null)
                    ? "Using your Litematica area selection." : "Look at a block, press Corner 1; look at another, press Corner 2.";
        };
        g.drawCenteredString(font, "§7" + help, cx, 22, 0xFFFFFFFF);
        String warn = warning();
        if (warn != null) g.drawCenteredString(font, warn, cx, height - 20, 0xFFFFFFFF);
        g.drawCenteredString(font, "§7The outline in the world shows exactly what will be dug.", cx, height - 32, 0xFFFFFFFF);
    }

    /** Friendly heads-up about tools / space. */
    private String warning() {
        if (minecraft.player == null) return null;
        Inventory inv = minecraft.player.getInventory();
        boolean pick = false;
        int free = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) { free++; continue; }
            if (s.getDestroySpeed(Blocks.STONE.defaultBlockState()) > 1.5f) pick = true;
        }
        if (!pick && !minecraft.player.getAbilities().instabuild) return "§eNo pickaxe in your inventory - stone will take forever.";
        if (free < 4) return "§eOnly " + free + " free inventory slots - set \"Inventory full\" to Drop junk, or empty some.";
        return null;
    }

    @Override
    public void onClose() {
        Preview.setSticky(false);
        Config.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
