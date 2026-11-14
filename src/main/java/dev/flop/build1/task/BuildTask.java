package dev.flop.build1.task;

import dev.flop.build1.Build1Client;
import dev.flop.build1.Config;
import dev.flop.build1.LitematicaBridge;
import dev.flop.build1.LitematicaBridge.Box;
import dev.flop.build1.bot.*;
import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the selected Litematica placement bottom-up, layer by layer.
 * Each tick: place what's in reach; otherwise walk to the nearest unfinished block.
 */
public final class BuildTask implements Task {

    private record Work(BlockPos pos, BlockState want, boolean breakIt) {}

    private enum Result { PLACED, WAIT, MISSING, NO_PLAN }

    private String placementName;
    private Box box;
    private Level schem;
    private int layer;
    private int pass = 1;
    private final List<Work> work = new ArrayList<>();
    private final Map<Long, Integer> skipUntil = new HashMap<>();
    private final Map<Long, Integer> attempts = new HashMap<>();
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private final Walker walker = new Walker();
    private final Breaker breaker = new Breaker();
    private int t, cooldown, lastPlan = -100, placed, broken, wrong, unknown, leftovers;
    private BlockPos walkTarget;
    private boolean finished;
    private String state = "starting";

    public static BuildTask create() {
        if (!LitematicaBridge.installed()) { Build1Client.msg("§cLitematica isn't installed."); return null; }
        LitematicaBridge.Placement pl = LitematicaBridge.selectedPlacement();
        Level w = LitematicaBridge.schematicWorld();
        if (pl == null || w == null) {
            Build1Client.msg("§cNo schematic placement found. Load one in Litematica (and select it in the placements menu).");
            return null;
        }
        BuildTask b = new BuildTask();
        b.placementName = pl.name();
        b.box = pl.box();
        b.schem = w;
        b.layer = pl.box().min().getY();
        Build1Client.msg("Building §e" + pl.name() + "§r " + pl.box());
        return b;
    }

    @Override public String name() { return "Build " + placementName; }
    @Override public boolean finished() { return finished; }

    @Override public void stop() {
        walker.stop();
        breaker.reset();
    }

    @Override public void tick() {
        t++;
        var mc = Bot.mc();
        LocalPlayer p = mc.player;
        if (t % 100 == 0) refreshPlacement();
        if (finished) return;
        if (t % 10 == 1 || work.isEmpty()) scan();

        // 1) keep breaking whatever we're breaking
        if (breaker.target() != null) {
            BlockPos bp = breaker.target();
            if (!Safety.inReach(bp) || breaker.ticks() > 400) { breaker.reset(); skip(bp, 200); }
            else {
                state = "breaking";
                if (breaker.tick(bp)) { broken++; cooldown = Config.actionDelay; scan(); }
                return;
            }
        }
        if (cooldown > 0) { cooldown--; if (walker.busy()) walker.tick(); return; }

        // 2) act on anything in reach
        Vec3 eye = Bot.eye();
        List<Work> near = new ArrayList<>();
        for (Work w : work) {
            if (skipped(w.pos)) continue;
            if (eye.distanceTo(Vec3.atCenterOf(w.pos)) <= Bot.reach() + 0.5) near.add(w);
        }
        near.sort(Comparator.comparingDouble(w -> eye.distanceToSqr(Vec3.atCenterOf(w.pos))));
        int done = 0, tries = 0;
        for (Work w : near) {
            if (tries++ > 12) break;
            if (w.breakIt) {
                if (!Safety.inReach(w.pos) || !Safety.safeToBreak(w.pos, Config.avoidLava)) { skip(w.pos, 100); continue; }
                walker.stop();
                state = "breaking";
                breaker.tick(w.pos);
                return;
            }
            Result r = place(w);
            if (r == Result.PLACED) {
                placed++;
                done++;
                work.remove(w);
                int a = attempts.merge(w.pos.asLong(), 1, Integer::sum);
                skip(w.pos, a >= 3 ? 200 : 10); // re-check after server confirms; give up for a while if it keeps failing
                if (done >= Config.blocksPerTick) break;
            } else if (r == Result.WAIT) {
                cooldown = 1;
                return;
            } else {
                skip(w.pos, r == Result.MISSING ? 100 : 40);
            }
        }
        if (done > 0) { state = "placing"; cooldown = Config.actionDelay; return; }

        // 3) walk to the nearest unfinished block
        if (walker.busy()) {
            state = "walking";
            walker.tick();
            if (walker.status() == Walker.Status.FAILED && walkTarget != null) skip(walkTarget, 100);
            return;
        }
        if (walker.status() == Walker.Status.FAILED && walkTarget != null) { skip(walkTarget, 100); walker.stop(); }
        Work target = null;
        double bd = Double.MAX_VALUE;
        for (Work w : work) {
            if (skipped(w.pos)) continue;
            double d = p.position().distanceToSqr(Vec3.atCenterOf(w.pos));
            if (d < bd) { bd = d; target = w; }
        }
        if (target == null) {
            // blocks we just placed are briefly skipped while the server confirms them: wait, don't move on
            for (Work w : work) {
                Integer u = skipUntil.get(w.pos.asLong());
                if (u != null && u - t <= 15) { state = "confirming"; return; }
            }
            nextLayer();
            return;
        }
        if (!Config.walk) { state = "waiting (walking off)"; for (Work w : work) if (!skipped(w.pos)) skip(w.pos, 40); return; }
        if (t - lastPlan < 5) return;
        lastPlan = t;
        walkTarget = target.pos;
        state = "pathing";
        List<PathFinder.Step> path = PathFinder.find(p.blockPosition(), new ReachGoal(target.pos, Bot.reach() - 0.4),
                Config.pillar && Walker.hasScaffold(), pos -> false);
        if (path == null) { skip(target.pos, 200); return; }
        walker.start(path);
        if (walker.status() == Walker.Status.DONE) skip(target.pos, 40); // already "there" but couldn't place
        walker.tick();
    }

    private Result place(Work w) {
        var mc = Bot.mc();
        LocalPlayer p = mc.player;
        if (Safety.intersectsPlayer(w.pos)) return Result.NO_PLAN;
        Item item = w.want.getBlock().asItem();
        int slot = Bot.find(item);
        if (slot < 0) {
            if (Bot.creativeGive(item)) return Result.WAIT;
            return Result.MISSING;
        }
        ItemStack stack = p.getInventory().getItem(slot);
        PlacementSolver.Plan plan = PlacementSolver.solve(w.pos, w.want, stack);
        if (plan == null) return Result.NO_PLAN;
        if (!Bot.hold(slot)) return Result.WAIT;
        walker.stop();
        Bot.faceNow(plan.yaw(), plan.pitch());
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, plan.hit());
        p.swing(InteractionHand.MAIN_HAND);
        return Result.PLACED;
    }

    private void scan() {
        work.clear();
        missing.clear();
        wrong = 0;
        unknown = 0;
        ClientLevel lvl = Bot.mc().level;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        boolean creative = Bot.mc().player.getAbilities().instabuild;
        Map<Item, Boolean> have = new HashMap<>();
        for (int x = box.min().getX(); x <= box.max().getX(); x++) {
            for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
                m.set(x, layer, z);
                if (!lvl.hasChunkAt(m)) { unknown++; continue; }
                BlockState want = schem.getBlockState(m);
                BlockState real = lvl.getBlockState(m);
                if (want.isAir()) {
                    if (Config.clearExtra && !Breaker.gone(real) && !real.canBeReplaced() && real.getDestroySpeed(lvl, m) >= 0)
                        work.add(new Work(m.immutable(), want, true));
                    continue;
                }
                if (PlacementSolver.skip(want) || PlacementSolver.satisfied(real, want)) continue;
                boolean completingSlab = real.getBlock() == want.getBlock();
                if (real.canBeReplaced() || completingSlab) {
                    work.add(new Work(m.immutable(), want, false));
                    Item it = want.getBlock().asItem();
                    if (!creative && !have.computeIfAbsent(it, i -> Bot.find(i) >= 0)) missing.merge(it, 1, Integer::sum);
                }
                else if (Config.replaceWrong && real.getDestroySpeed(lvl, m) >= 0) work.add(new Work(m.immutable(), want, true));
                else wrong++;
            }
        }
        // drop stale skip entries
        skipUntil.values().removeIf(v -> v < t);
    }

    private void nextLayer() {
        leftovers += work.size();
        layer++;
        work.clear();
        if (layer > box.max().getY()) {
            if (leftovers > 0 && pass < 3) {
                pass++;
                leftovers = 0;
                layer = box.min().getY();
                skipUntil.clear();
                attempts.clear();
                Build1Client.msg("Pass " + pass + ": going back over blocks that couldn't be placed yet.");
            } else {
                finished = true;
                walker.stop();
                String extra = leftovers > 0 ? " §e" + leftovers + " blocks couldn't be placed (missing items / no support / unreachable)." : "";
                Build1Client.msg("§aBuild finished§r: placed " + placed + " blocks." + extra);
            }
        }
        scan();
    }

    private void refreshPlacement() {
        LitematicaBridge.Placement pl = LitematicaBridge.selectedPlacement();
        Level w = LitematicaBridge.schematicWorld();
        if (w != null) schem = w;
        if (pl == null) { Build1Client.msg("§cSchematic placement was unloaded, stopping."); finished = true; stop(); return; }
        if (!pl.box().equals(box)) {
            box = pl.box();
            placementName = pl.name();
            layer = Math.max(box.min().getY(), Math.min(layer, box.max().getY()));
        }
    }

    private boolean skipped(BlockPos p) {
        Integer u = skipUntil.get(p.asLong());
        return u != null && u > t;
    }

    private void skip(BlockPos p, int ticks) { skipUntil.put(p.asLong(), t + ticks); }

    @Override public List<String> hud() {
        List<String> l = new ArrayList<>();
        l.add("§eBuilding§r " + placementName + (pass > 1 ? " §7(pass " + pass + ")" : ""));
        l.add("Layer " + (layer - box.min().getY() + 1) + "/" + box.sizeY() + " §7(y=" + layer + ")§r  left: " + work.size());
        l.add("Placed " + placed + (broken > 0 ? "  broken " + broken : "") + "  §7" + state);
        if (wrong > 0) l.add("§c" + wrong + " wrong blocks in layer§7 (turn on Replace wrong)");
        if (unknown > 0) l.add("§7" + unknown + " positions in unloaded chunks");
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("§cMissing: §r");
            int i = 0;
            for (var e : missing.entrySet()) {
                if (i++ == 3) { sb.append("…"); break; }
                sb.append(new ItemStack(e.getKey()).getHoverName().getString()).append(" x").append(e.getValue()).append("  ");
            }
            l.add(sb.toString());
        }
        return l;
    }
}
