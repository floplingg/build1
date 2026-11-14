package dev.flop.build1.task;

import dev.flop.build1.Build1Client;
import dev.flop.build1.Config;
import dev.flop.build1.LitematicaBridge.Box;
import dev.flop.build1.bot.*;
import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Clears every block in a box, top layer first, walking around as needed. */
public final class MineTask implements Task {

    private final Box box;
    private int layer;
    private final List<BlockPos> work = new ArrayList<>();
    private final Map<Long, Integer> skipUntil = new HashMap<>();
    private final Walker walker = new Walker();
    private final Breaker breaker = new Breaker();
    private int t, cooldown, lastPlan = -100, mined, unsafe, unknown, leftovers, pass = 1;
    private BlockPos walkTarget;
    private boolean finished;
    private String state = "starting";

    private int total = -1, startTick;
    private int junkCooldown;

    /** Stuff that's fine to throw away when the inventory is full. */
    private static final java.util.Set<net.minecraft.world.item.Item> JUNK = java.util.Set.of(
            net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.COBBLED_DEEPSLATE,
            net.minecraft.world.item.Items.DIRT, net.minecraft.world.item.Items.GRAVEL, net.minecraft.world.item.Items.NETHERRACK,
            net.minecraft.world.item.Items.ANDESITE, net.minecraft.world.item.Items.DIORITE, net.minecraft.world.item.Items.GRANITE,
            net.minecraft.world.item.Items.TUFF, net.minecraft.world.item.Items.CALCITE, net.minecraft.world.item.Items.BLACKSTONE,
            net.minecraft.world.item.Items.BASALT, net.minecraft.world.item.Items.END_STONE, net.minecraft.world.item.Items.FLINT,
            net.minecraft.world.item.Items.SANDSTONE, net.minecraft.world.item.Items.ROTTEN_FLESH);

    public MineTask(Box box) {
        this.box = box;
        this.layer = box.max().getY();
        // count what's actually there, for a progress %
        var lvl = Bot.mc().level;
        int n = 0;
        if (box.volume() <= 600_000) {
            for (BlockPos b : BlockPos.betweenClosed(box.min(), box.max())) {
                BlockState s = lvl.getBlockState(b);
                if (!Breaker.gone(s) && s.getDestroySpeed(lvl, b) >= 0) n++;
            }
            total = n;
        }
        Build1Client.msg("Digging " + box.sizeX() + "×" + box.sizeY() + "×" + box.sizeZ()
                + (total >= 0 ? " — " + total + " blocks to mine." : "") + " §7Press X to stop.");
    }

    @Override public String name() { return "Mine area"; }
    @Override public boolean finished() { return finished; }
    @Override public void stop() { walker.stop(); breaker.reset(); Rotator.clear(); Bot.releaseKeys(); }

    @Override public void tick() {
        t++;
        LocalPlayer p = Bot.mc().player;
        if (Bot.mc().screen != null && Config.closet()) { state = "paused (menu open)"; Bot.releaseKeys(); return; }
        if (startTick == 0) startTick = t;
        if (t % 100 == 1) dev.flop.build1.ui.Preview.show(box, 120);
        if (inventoryFull()) return;
        if (t % 10 == 1 || work.isEmpty()) scan();
        if (finished) return;

        if (breaker.target() != null) {
            BlockPos bp = breaker.target();
            if (!Safety.inReach(bp) || breaker.ticks() > 600 || breaker.blocked() > 40 || !Safety.safeToBreak(bp, Config.avoidLava)) {
                breaker.reset();
                Rotator.clear();
                skip(bp, 200);
            } else {
                state = "mining";
                if (breaker.tick(bp)) { mined++; work.remove(bp); cooldown = Bot.actionPause(); }
                return;
            }
        }
        if (cooldown > 0) { cooldown--; if (walker.busy()) walker.tick(); return; }

        // in reach: in serpentine order (highest layer first so we never undercut ourselves)
        Vec3 eye = Bot.eye();
        BlockPos pick = null;
        long bestKey = Long.MAX_VALUE;
        for (BlockPos b : work) {
            if (skipped(b)) continue;
            if (eye.distanceTo(Vec3.atCenterOf(b)) > Bot.reach()) continue;
            long k = Order.key(b, box, true);
            if (k < bestKey && Safety.safeToBreak(b, Config.avoidLava)
                    && (!Config.closet() || Breaker.visiblePoint(b) != null)) { bestKey = k; pick = b; }
        }
        if (pick != null) {
            walker.stop();
            state = "mining";
            if (breaker.tick(pick)) { mined++; work.remove(pick); Rotator.clear(); cooldown = Bot.actionPause(); }
            return;
        }

        if (walker.busy()) {
            state = "walking";
            walker.tick();
            if (walker.status() == Walker.Status.FAILED && walkTarget != null) skip(walkTarget, 100);
            return;
        }
        if (walker.status() == Walker.Status.FAILED && walkTarget != null) { skip(walkTarget, 100); walker.stop(); }

        BlockPos target = null;
        long bk = Long.MAX_VALUE;
        for (BlockPos b : work) {
            if (skipped(b)) continue;
            long k = Order.key(b, box, true);
            if (k < bk) { bk = k; target = b; }
        }
        if (target == null) { nextLayer(); return; }
        if (!Config.walk) { state = "waiting (walking off)"; for (BlockPos b : work) skip(b, 40); return; }
        if (t - lastPlan < 5) return;
        lastPlan = t;
        walkTarget = target;
        state = "pathing";
        Rotator.clear();
        List<PathFinder.Step> path = PathFinder.find(p.blockPosition(), new ReachGoal(target, Bot.reach() - 0.5),
                null, pos -> false);
        if (path == null) { skip(target, 200); return; }
        walker.start(path);
        if (walker.status() == Walker.Status.DONE) skip(target, 40);
        walker.tick();
    }

    /** No room left for drops: drop junk or pause (beginner setting). Returns true while handling it. */
    private boolean inventoryFull() {
        var p = Bot.mc().player;
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) return false;
        if (junkCooldown > 0) { junkCooldown--; return true; }
        if (Config.fullInv == Config.FullInv.DROP_JUNK) {
            for (int i = 0; i < 36; i++) {
                if (!JUNK.contains(inv.getItem(i).getItem())) continue;
                if (Config.closet() && !Bot.still()) { walker.stop(); breaker.reset(); return true; }
                int menuSlot = i < 9 ? 36 + i : i; // inventory menu: 9-35 main, 36-44 hotbar
                Bot.mc().gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot, 1,
                        net.minecraft.world.inventory.ClickType.THROW, p);
                state = "dropping junk";
                junkCooldown = Config.closet() ? 4 + Bot.rng().nextInt(4) : 1;
                return true;
            }
        }
        Build1Client.msg("§eInventory full§r — empty it (or set \"Inventory full: Drop junk\" in Dig Out) and press Resume.");
        Build1Client.setPaused(true);
        return true;
    }

    private void scan() {
        work.clear();
        unsafe = 0;
        unknown = 0;
        ClientLevel lvl = Bot.mc().level;
        // Current layer plus a few above it (leftovers we can now reach).
        for (int y = Math.min(box.max().getY(), layer + 4); y >= layer; y--) {
            for (int x = box.min().getX(); x <= box.max().getX(); x++) {
                for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
                    BlockPos b = new BlockPos(x, y, z);
                    if (!lvl.hasChunkAt(b)) { unknown++; continue; }
                    BlockState s = lvl.getBlockState(b);
                    if (Breaker.gone(s) || s.getDestroySpeed(lvl, b) < 0) continue;
                    if (y > layer && skipped(b)) continue;
                    work.add(b);
                }
            }
        }
        skipUntil.values().removeIf(v -> v < t);
    }

    private void nextLayer() {
        leftovers += work.size();
        layer--;
        if (layer < box.min().getY()) {
            if (leftovers > 0 && pass < 2) {
                pass++;
                leftovers = 0;
                layer = box.max().getY();
                skipUntil.clear();
            } else {
                finished = true;
                stop();
                Build1Client.msg("§aArea mined§r: " + mined + " blocks." + (leftovers > 0 ? " §e" + leftovers + " skipped (lava / unsafe / unreachable)." : ""));
                return;
            }
        }
        scan();
    }

    private boolean skipped(BlockPos p) {
        Integer u = skipUntil.get(p.asLong());
        return u != null && u > t;
    }

    private void skip(BlockPos p, int ticks) { skipUntil.put(p.asLong(), t + ticks); }

    @Override public List<String> hud() {
        List<String> l = new ArrayList<>();
        l.add("§eMining§r §7[" + Config.mode.name().toLowerCase() + "]§r " + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ() + (pass > 1 ? " §7(pass " + pass + ")" : ""));
        l.add("Layer " + (box.max().getY() - layer + 1) + "/" + box.sizeY() + " §7(y=" + layer + ")§r  left: " + work.size());
        String pct = "";
        if (total > 0) {
            int p = Math.min(100, mined * 100 / Math.max(1, total));
            pct = " §a" + p + "%";
            int el = t - startTick;
            if (mined > 5 && el > 0) {
                int etaSec = (int) ((long) (total - mined) * el / mined / 20);
                pct += " §7~" + (etaSec >= 60 ? etaSec / 60 + "m " : "") + etaSec % 60 + "s left";
            }
        }
        l.add("Mined " + mined + pct + "  §7" + state);
        if (unknown > 0) l.add("§7" + unknown + " positions in unloaded chunks");
        return l;
    }
}
