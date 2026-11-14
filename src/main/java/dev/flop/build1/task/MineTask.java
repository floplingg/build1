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

    public MineTask(Box box) {
        this.box = box;
        this.layer = box.max().getY();
        Build1Client.msg("Mining area " + box + " — " + box.volume() + " blocks max.");
    }

    @Override public String name() { return "Mine area"; }
    @Override public boolean finished() { return finished; }
    @Override public void stop() { walker.stop(); breaker.reset(); }

    @Override public void tick() {
        t++;
        LocalPlayer p = Bot.mc().player;
        if (t % 10 == 1 || work.isEmpty()) scan();
        if (finished) return;

        if (breaker.target() != null) {
            BlockPos bp = breaker.target();
            if (!Safety.inReach(bp) || breaker.ticks() > 600 || !Safety.safeToBreak(bp, Config.avoidLava)) {
                breaker.reset();
                skip(bp, 200);
            } else {
                state = "mining";
                if (breaker.tick(bp)) { mined++; work.remove(bp); cooldown = Config.actionDelay; }
                return;
            }
        }
        if (cooldown > 0) { cooldown--; if (walker.busy()) walker.tick(); return; }

        // in reach: highest first (so we don't undercut ourselves), then nearest
        Vec3 eye = Bot.eye();
        BlockPos pick = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos b : work) {
            if (skipped(b)) continue;
            double d = eye.distanceTo(Vec3.atCenterOf(b));
            if (d > Bot.reach()) continue;
            double score = d - b.getY() * 0.01;
            if (score < bestScore && Safety.safeToBreak(b, Config.avoidLava)) { bestScore = score; pick = b; }
        }
        if (pick != null) {
            walker.stop();
            state = "mining";
            if (breaker.tick(pick)) { mined++; work.remove(pick); cooldown = Config.actionDelay; }
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
        double bd = Double.MAX_VALUE;
        for (BlockPos b : work) {
            if (skipped(b)) continue;
            double d = p.position().distanceToSqr(Vec3.atCenterOf(b));
            if (d < bd) { bd = d; target = b; }
        }
        if (target == null) { nextLayer(); return; }
        if (!Config.walk) { state = "waiting (walking off)"; for (BlockPos b : work) skip(b, 40); return; }
        if (t - lastPlan < 5) return;
        lastPlan = t;
        walkTarget = target;
        state = "pathing";
        List<PathFinder.Step> path = PathFinder.find(p.blockPosition(), new ReachGoal(target, Bot.reach() - 0.5),
                false, pos -> false);
        if (path == null) { skip(target, 200); return; }
        walker.start(path);
        if (walker.status() == Walker.Status.DONE) skip(target, 40);
        walker.tick();
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
        l.add("§eMining§r " + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ() + (pass > 1 ? " §7(pass " + pass + ")" : ""));
        l.add("Layer " + (box.max().getY() - layer + 1) + "/" + box.sizeY() + " §7(y=" + layer + ")§r  left: " + work.size());
        l.add("Mined " + mined + "  §7" + state);
        if (unknown > 0) l.add("§7" + unknown + " positions in unloaded chunks");
        return l;
    }
}
