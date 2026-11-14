package dev.flop.build1.task;

import dev.flop.build1.Build1Client;
import dev.flop.build1.Config;
import dev.flop.build1.LitematicaBridge;
import dev.flop.build1.LitematicaBridge.Box;
import dev.flop.build1.bot.*;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the selected Litematica placement bottom-up.
 * Works on a 3-layer window, in serpentine strips (up, over, down, over...).
 */
public final class BuildTask implements Task {

    private enum Kind { PLACE, BREAK, WATER, WATERLOG, INTERACT }

    private record Work(BlockPos pos, BlockState want, Kind kind, long key) {}

    private enum Result { DONE, AIMING, WAIT, MISSING, NO_PLAN }

    /** Something we're turning toward before clicking. */
    private static final class AimJob {
        Work w;
        PlacementSolver.Plan plan; // PLACE
        Bucket.Aim bucket;         // WATER / WATERLOG / scoop
        BlockPos scoop;            // refilling a bucket from this source
        int slot, ticks;
    }

    private static final int WINDOW = 3;

    private String placementName;
    private Box box;
    private Level schem;
    private int layer;
    private int pass = 1;
    private final List<Work> work = new ArrayList<>();
    private final Map<Long, Integer> skipUntil = new HashMap<>();
    private final Map<Long, Integer> attempts = new HashMap<>();
    private final Map<Long, Integer> noPlan = new HashMap<>();
    private final Map<Long, Integer> walkCount = new HashMap<>();
    private final Set<Long> pendingPlace = new HashSet<>();
    private int lastProgress, lastPlacedSeen;
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private final Walker walker = new Walker();
    private final Breaker breaker = new Breaker();
    private final Restocker restocker = new Restocker();
    private AimJob aim;
    private int t, cooldown, lastPlan = -100, placed, broken, wrong, unknown, leftovers, noSupport, unreachable;
    private int sneakRelease, nextRestock, waitingSince = -1;
    private BlockPos walkTarget;
    private boolean finished;
    private String state = "starting";
    private Predicate<BlockPos> layerFilter;

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
        b.layerFilter = Config.followLayers ? LitematicaBridge.layerFilter() : null;
        int found = ChestRegistry.scan(Bot.mc().player.blockPosition(), 48);
        Build1Client.msg("Building §e" + pl.name() + "§r " + pl.box() + " §7[" + Config.mode.name().toLowerCase() + "]"
                + (Config.survival ? " §7materials chests: " + ChestRegistry.count() + (found > 0 ? " (" + found + " found by sign)" : "") : ""));
        return b;
    }

    @Override public String name() { return "Build " + placementName; }
    @Override public boolean finished() { return finished; }

    @Override public void stop() {
        walker.stop();
        breaker.reset();
        restocker.stop();
        aim = null;
        Rotator.clear();
        Bot.releaseKeys();
    }

    // =====================================================================================
    @Override public void tick() {
        t++;
        var mc = Bot.mc();
        LocalPlayer p = mc.player;
        if (t % 100 == 0) refreshPlacement();
        if (finished) return;

        if (sneakRelease > 0 && --sneakRelease == 0) Bot.releaseKeys();

        if (restocker.active()) {
            state = restocker.status();
            if (restocker.tick()) afterRestock();
            return;
        }
        if (mc.screen != null && Config.closet()) { state = "paused (menu open)"; Bot.releaseKeys(); return; }
        if (t % 10 == 1 || work.isEmpty()) scan();
        if (finished) return;

        // 1) keep breaking whatever we're breaking
        if (breaker.target() != null) {
            BlockPos bp = breaker.target();
            if (!Safety.inReach(bp) || breaker.ticks() > 400 || breaker.blocked() > 40) { breaker.reset(); Rotator.clear(); skip(bp, 200); }
            else {
                state = "breaking";
                if (breaker.tick(bp)) { broken++; Rotator.clear(); cooldown = Bot.actionPause(); scan(); }
                return;
            }
        }

        // 2) finish turning toward what we're about to click
        if (aim != null) { tickAim(); return; }
        if (cooldown > 0) { cooldown--; if (walker.busy()) walker.tick(); return; }

        // watchdog: placing stopped but there's still work -> park the stuck blocks for a while
        if (placed != lastPlacedSeen) { lastPlacedSeen = placed; lastProgress = t; walkCount.clear(); }
        if (t - lastProgress > 900 && !work.isEmpty()) {
            for (Work w : work) skip(w.pos, 400);
            lastProgress = t;
            walker.stop();
            state = "stuck - skipping ahead";
        }

        // 3) act on what's in reach, in serpentine order
        Vec3 eye = Bot.eye();
        List<Work> near = new ArrayList<>();
        for (Work w : work) {
            if (skipped(w.pos)) continue;
            if (eye.distanceTo(Vec3.atCenterOf(w.pos)) <= Bot.reach() + 0.5) near.add(w);
        }
        near.sort(Comparator.comparingLong(Work::key));
        int done = 0, tries = 0;
        for (Work w : near) {
            if (tries++ > 12) break;
            Result r;
            if (w.kind == Kind.BREAK) {
                if (!Safety.inReach(w.pos) || !Safety.safeToBreak(w.pos, Config.avoidLava)) { skip(w.pos, 100); continue; }
                walker.stop();
                state = "breaking";
                breaker.tick(w.pos);
                return;
            }
            r = switch (w.kind) {
                case PLACE -> startPlace(w);
                case INTERACT -> startInteract(w);
                default -> startBucket(w);
            };
            if (r == Result.DONE) {
                placed++;
                done++;
                work.remove(w);
                afterAction(w);
                if (done >= Config.blocksPerTick) break;
            } else if (r == Result.AIMING) {
                walker.stop();
                state = "aiming";
                return;
            } else if (r == Result.WAIT) {
                cooldown = 1;
                return;
            } else {
                if (r == Result.NO_PLAN) {
                    noSupport++;
                    int n = noPlan.merge(w.pos.asLong(), 1, Integer::sum);
                    skip(w.pos, 40 * (1 << Math.min(n - 1, 3))); // 2s, 4s, 8s, 16s...
                } else skip(w.pos, 100);
            }
        }
        if (done > 0) { state = "placing"; cooldown = Bot.actionPause(); return; }

        // 4) walk on to the next unfinished block in order
        if (walker.busy()) {
            state = "walking";
            walker.tick();
            if (walker.status() == Walker.Status.FAILED && walkTarget != null) { skip(walkTarget, 100); unreachable++; }
            return;
        }
        if (walker.status() == Walker.Status.FAILED && walkTarget != null) { skip(walkTarget, 100); walker.stop(); }
        Work target = null;
        for (Work w : work) {
            if (skipped(w.pos)) continue;
            if (target == null || w.key < target.key) target = w;
        }
        if (target == null) { nothingLeft(); return; }
        if (!Config.walk) { state = "waiting (walking off)"; for (Work w : work) if (!skipped(w.pos)) skip(w.pos, 40); return; }
        if (t - lastPlan < 5) return;
        lastPlan = t;
        // walked to this one 3 times without placing anything in between: it's not working, move on
        if (walkCount.merge(target.pos.asLong(), 1, Integer::sum) > 3) {
            skip(target.pos, 600);
            unreachable++;
            walkCount.remove(target.pos.asLong());
            return;
        }
        walkTarget = target.pos;
        state = "pathing";
        PathFinder.Goal goal = new ReachGoal(target.pos, Bot.reach() - 0.4);
        if (target.kind == Kind.PLACE) {
            int slot = Bot.find(target.want.getBlock().asItem());
            if (slot >= 0) goal = new PlaceGoal(target.pos, target.want, p.getInventory().getItem(slot), Bot.reach() - 0.2, Config.closet());
        }
        Rotator.clear();
        // first try not to end up standing where a block still has to go; then allow it
        List<PathFinder.Step> path = PathFinder.find(p.blockPosition(), goal, this::pillarItem, pos -> pendingPlace.contains(pos.asLong()));
        if (path == null) path = PathFinder.find(p.blockPosition(), goal, this::pillarItem, pos -> false);
        if (path == null) { skip(target.pos, 200); unreachable++; return; }
        walker.start(path);
        if (walker.status() == Walker.Status.DONE) skip(target.pos, 40); // already "there" but couldn't place
        walker.tick();
    }

    private void afterAction(Work w) {
        int a = attempts.merge(w.pos.asLong(), 1, Integer::sum);
        skip(w.pos, a >= 3 ? 200 : 10); // re-check after the server confirms; back off if it keeps failing
    }

    // =====================================================================================
    // placing

    private Result startPlace(Work w) {
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
        if (!Config.closet() && !plan.sneak()) {
            walker.stop();
            Bot.faceNow(plan.yaw(), plan.pitch());
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, plan.hit());
            p.swing(InteractionHand.MAIN_HAND);
            return Result.DONE;
        }
        aim = new AimJob();
        aim.w = w;
        aim.plan = plan;
        aim.slot = p.getInventory().getSelectedSlot();
        return Result.AIMING;
    }

    private Result startBucket(Work w) {
        var mc = Bot.mc();
        LocalPlayer p = mc.player;
        int slot = Bot.find(Items.WATER_BUCKET);
        if (slot < 0) {
            if (Bot.creativeGive(Items.WATER_BUCKET)) return Result.WAIT;
            int empty = Bot.find(Items.BUCKET);
            if (empty < 0) return Result.MISSING;
            // refill: find water that isn't part of the build
            BlockPos src = Bucket.findSource(p.blockPosition(), 12, pp -> box.contains(pp) && Bucket.isWaterSource(schem.getBlockState(pp)));
            if (src == null) return Result.MISSING;
            Bucket.Aim a = Bucket.solveScoop(src, Bot.eye());
            if (a == null) { walkTo(src); return Result.WAIT; }
            if (!Bot.hold(empty)) return Result.WAIT;
            aim = new AimJob();
            aim.w = w;
            aim.bucket = a;
            aim.scoop = src;
            aim.slot = p.getInventory().getSelectedSlot();
            return Result.AIMING;
        }
        Bucket.Aim a = Bucket.solve(w.pos, w.kind == Kind.WATERLOG, Bot.eye());
        if (a == null) return Result.NO_PLAN;
        if (!Bot.hold(slot)) return Result.WAIT;
        aim = new AimJob();
        aim.w = w;
        aim.bucket = a;
        aim.slot = p.getInventory().getSelectedSlot();
        return Result.AIMING;
    }

    private Result startInteract(Work w) {
        PlacementSolver.Plan plan = Interact.plan(w.pos, Config.closet());
        if (plan == null) return Result.NO_PLAN;
        aim = new AimJob();
        aim.w = w;
        aim.plan = plan;
        aim.slot = Bot.mc().player.getInventory().getSelectedSlot();
        return Result.AIMING;
    }

    /** What to place under ourselves to climb at this spot: the build's own block if it belongs there, else scaffold. */
    private net.minecraft.world.item.Item pillarItem(BlockPos pos) {
        if (box.contains(pos)) {
            if (layerFilter != null && !layerFilter.test(pos)) return null;
            BlockState want = schem.getBlockState(pos);
            if (want.isAir() || PlacementSolver.oriented(want) || !want.isCollisionShapeFullBlock(schem, pos)
                    || want.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock
                    || want.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return null;
            Item it = want.getBlock().asItem();
            return Bot.find(it) >= 0 || Bot.mc().player.getAbilities().instabuild ? it : null;
        }
        return Config.pillar ? Walker.scaffoldItem() : null;
    }

    private void walkTo(BlockPos pos) {
        LocalPlayer p = Bot.mc().player;
        List<PathFinder.Step> path = PathFinder.find(p.blockPosition(), new ReachGoal(pos, Bot.reach() - 0.6), null, x -> false);
        if (path != null) { walker.start(path); walkTarget = pos; }
    }

    /** Turn toward the planned click, then click once the server has seen us looking there. */
    private void tickAim() {
        var mc = Bot.mc();
        LocalPlayer p = mc.player;
        AimJob a = aim;
        a.ticks++;
        BlockState real = mc.level.getBlockState(a.w.pos);
        boolean doneAlready = switch (a.w.kind) {
            case PLACE -> PlacementSolver.satisfied(real, a.w.want);
            case INTERACT -> !Interact.needs(real, a.w.want);
            case WATER -> Bucket.isWaterSource(real);
            case WATERLOG -> Bucket.isWaterlogged(real);
            default -> false;
        };
        if (doneAlready && a.scoop == null) { aim = null; Rotator.clear(); return; }
        if (a.ticks > 50) { skip(a.w.pos, 60); aim = null; Rotator.clear(); Bot.releaseKeys(); return; }
        if (p.getInventory().getSelectedSlot() != a.slot) p.getInventory().setSelectedSlot(a.slot);

        boolean sneak = a.plan != null && a.plan.sneak();
        float yaw = a.plan != null ? a.plan.yaw() : a.bucket.yaw();
        float pitch = a.plan != null ? a.plan.pitch() : a.bucket.pitch();
        Bot.keys(false, false, sneak);
        Rotator.aim(yaw, pitch);
        if (!Rotator.settled()) { state = "aiming"; return; }
        if (sneak && (!p.isShiftKeyDown() || a.ticks < 3)) { state = "crouching"; return; }

        Vec3 eye = Bot.eye();
        float ry = p.getYRot(), rp = p.getXRot();
        if (a.w.kind == Kind.INTERACT) {
            BlockHitResult hit;
            if (Config.closet()) {
                hit = PlacementSolver.raycast(eye, ry, rp, Bot.reach() + 0.5);
                if (!Interact.hitOk(a.w.pos, hit, ry)) {
                    if (a.ticks % 12 == 0) {
                        PlacementSolver.Plan np = Interact.plan(a.w.pos, true);
                        if (np == null) { skip(a.w.pos, 100); aim = null; Rotator.clear(); } else a.plan = np;
                    }
                    return;
                }
            } else {
                Bot.faceNow(a.plan.yaw(), a.plan.pitch());
                hit = a.plan.hit();
            }
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            p.swing(InteractionHand.MAIN_HAND);
            state = "setting block state";
            lastProgress = t;
            skip(a.w.pos, 6); // look again once the server answers (repeaters / note blocks need several clicks)
            aim = null;
            Rotator.clear();
            cooldown = Bot.actionPause();
            return;
        }
        if (a.plan != null) {
            ItemStack held = p.getMainHandItem();
            BlockHitResult hit;
            if (Config.closet()) {
                hit = PlacementSolver.raycast(eye, ry, rp, Bot.reach() + 0.5);
                if (hit.getType() != HitResult.Type.BLOCK || !PlacementSolver.clickWorks(a.w.pos, a.w.want, held, hit, ry, rp, sneak)) {
                    if (a.ticks % 12 == 0) replan(a);
                    return;
                }
            } else {
                Bot.faceNow(a.plan.yaw(), a.plan.pitch());
                hit = a.plan.hit();
            }
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            p.swing(InteractionHand.MAIN_HAND);
            placed++;
        } else {
            boolean ok = a.scoop != null ? Bucket.solveScoop(a.scoop, eye) != null : Bucket.rayWorks(a.w.pos, a.w.kind == Kind.WATERLOG, eye, ry, rp);
            if (!ok) {
                if (a.ticks % 12 == 0) {
                    Bucket.Aim na = a.scoop != null ? Bucket.solveScoop(a.scoop, eye) : Bucket.solve(a.w.pos, a.w.kind == Kind.WATERLOG, eye);
                    if (na == null) { skip(a.w.pos, 60); aim = null; Rotator.clear(); return; }
                    a.bucket = na;
                }
                return;
            }
            if (!Config.closet()) Bot.faceNow(ry, rp);
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            if (a.scoop == null) placed++;
        }
        state = a.scoop != null ? "filling bucket" : "placing";
        if (a.scoop == null) { work.remove(a.w); afterAction(a.w); }
        aim = null;
        Rotator.clear();
        if (sneak) sneakRelease = 2 + Bot.rng().nextInt(3);
        cooldown = Bot.actionPause();
    }

    private void replan(AimJob a) {
        ItemStack held = Bot.mc().player.getMainHandItem();
        PlacementSolver.Plan np = PlacementSolver.solve(a.w.pos, a.w.want, held);
        if (np == null) { skip(a.w.pos, 60); aim = null; Rotator.clear(); Bot.releaseKeys(); }
        else a.plan = np;
    }

    // =====================================================================================
    // scanning

    private void scan() {
        ClientLevel lvl = Bot.mc().level;
        boolean creative = Bot.mc().player.getAbilities().instabuild;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // slide the window up past layers that are completely done
        while (true) {
            work.clear();
            pendingPlace.clear();
            missing.clear();
            wrong = 0;
            unknown = 0;
            Map<Item, Boolean> have = new HashMap<>();
            for (int y = layer; y < layer + WINDOW && y <= box.max().getY(); y++) {
                for (int x = box.min().getX(); x <= box.max().getX(); x++) {
                    for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
                        m.set(x, y, z);
                        Work w = examine(lvl, m, creative, have);
                        if (w != null) work.add(w);
                        if (w != null && w.kind == Kind.PLACE) pendingPlace.add(w.pos.asLong());
                    }
                }
            }
            boolean bottomEmpty = true;
            for (Work w : work) if (w.pos.getY() == layer) { bottomEmpty = false; break; }
            if (bottomEmpty && layer < box.max().getY() && unknown == 0) { layer++; continue; }
            if (bottomEmpty && work.isEmpty() && layer >= box.max().getY() && unknown == 0) {
                // everything done (including the top layer): let nothingLeft()/nextLayer() finish up
            }
            break;
        }
        skipUntil.values().removeIf(v -> v < t);
    }

    private Work examine(ClientLevel lvl, BlockPos.MutableBlockPos m, boolean creative, Map<Item, Boolean> have) {
        if (!lvl.hasChunkAt(m)) { unknown++; return null; }
        if (layerFilter != null && !layerFilter.test(m)) return null;
        BlockState want = schem.getBlockState(m);
        BlockState real = lvl.getBlockState(m);
        long key = Order.key(m, box, false);

        if (Bucket.isWaterSource(want)) {
            if (Bucket.isWaterSource(real) || Bucket.isWaterlogged(real)) return null;
            if (Bucket.canPour(m)) { needItem(Items.WATER_BUCKET, creative, have, Items.BUCKET); return new Work(m.immutable(), want, Kind.WATER, key + 7); }
            if (Config.replaceWrong && !Breaker.gone(real) && real.getDestroySpeed(lvl, m) >= 0) return new Work(m.immutable(), want, Kind.BREAK, key);
            return null;
        }
        if (want.isAir() || want.liquid()) {
            if (want.isAir() && Config.clearExtra && !Breaker.gone(real) && !real.canBeReplaced() && real.getDestroySpeed(lvl, m) >= 0)
                return new Work(m.immutable(), want, Kind.BREAK, key);
            return null;
        }
        if (PlacementSolver.skip(want)) return null;
        if (PlacementSolver.satisfied(real, want)) {
            if (Interact.needs(real, want)) return new Work(m.immutable(), want, Kind.INTERACT, key + 5);
            if (Bucket.wantsWaterlogged(want) && !Bucket.isWaterlogged(real)) {
                needItem(Items.WATER_BUCKET, creative, have, Items.BUCKET);
                return new Work(m.immutable(), want, Kind.WATERLOG, key + 7);
            }
            return null;
        }
        if (real.canBeReplaced() || PlacementSolver.needsStacking(real, want)) {
            needItem(want.getBlock().asItem(), creative, have, null);
            return new Work(m.immutable(), want, Kind.PLACE, key);
        }
        if (Config.replaceWrong && real.getDestroySpeed(lvl, m) >= 0) return new Work(m.immutable(), want, Kind.BREAK, key);
        wrong++;
        return null;
    }

    private void needItem(Item it, boolean creative, Map<Item, Boolean> have, Item alt) {
        if (creative) return;
        boolean ok = have.computeIfAbsent(it, i -> Bot.find(i) >= 0);
        if (!ok && alt != null) ok = have.computeIfAbsent(alt, i -> Bot.find(i) >= 0);
        if (!ok) missing.merge(it, 1, Integer::sum);
    }

    /** Everything in the window is skipped. Restock, wait, or move up. */
    private void nothingLeft() {
        // just-placed blocks are briefly skipped while the server confirms them: wait
        for (Work w : work) {
            Integer u = skipUntil.get(w.pos.asLong());
            if (u != null && u - t <= 15) { state = "confirming"; return; }
        }
        if (!missing.isEmpty() && !Bot.mc().player.getAbilities().instabuild) {
            if (Config.survival && ChestRegistry.count() > 0) {
                if (t >= nextRestock) { startRestock(); return; }
                state = "waiting for materials (" + Math.max(0, (nextRestock - t) / 20) + "s)";
                return;
            }
            Build1Client.msg("§eOut of materials: §r" + missingText(5)
                    + (Config.survival ? " §7(no materials chests: put a sign saying \"materials\" on one, or add it in the menu)" : "")
                    + " — restock and press Resume.");
            Build1Client.setPaused(true);
            skipUntil.clear();
            return;
        }
        nextLayer();
    }

    private void nextLayer() {
        int bottomLeft = 0;
        for (Work w : work) if (w.pos.getY() == layer) bottomLeft++;
        leftovers += bottomLeft;
        layer++;
        work.clear();
        if (layer > box.max().getY()) {
            if (leftovers > 0 && pass < 3) {
                pass++;
                leftovers = 0;
                layer = box.min().getY();
                skipUntil.clear();
                attempts.clear();
                noPlan.clear();
                walkCount.clear();
                Build1Client.msg("Pass " + pass + ": going back over blocks that couldn't be placed yet.");
            } else {
                finished = true;
                stop();
                String extra = leftovers > 0 ? " §e" + leftovers + " blocks couldn't be placed (no support / unreachable / missing)." : "";
                Build1Client.msg("§aBuild finished§r: placed " + placed + " blocks." + extra);
                return;
            }
        }
        scan();
    }

    // =====================================================================================
    // survival restock

    private void startRestock() {
        Map<Item, Integer> need = remainingNeeds();
        if (need.isEmpty()) { nextRestock = t + Config.restockMinutes * 1200; return; }
        walker.stop();
        Rotator.clear();
        restocker.begin(need);
        if (!restocker.active()) nextRestock = t + Config.restockMinutes * 1200;
        else state = "restocking";
    }

    private void afterRestock() {
        nextRestock = t + Config.restockMinutes * 1200;
        skipUntil.clear();
        scan();
        if (restocker.taken() == 0) {
            if (waitingSince < 0) {
                Build1Client.msg("§eMaterials chests don't have: §r" + missingText(5) + " §7— waiting, checking again every "
                        + Config.restockMinutes + " min.");
                waitingSince = t;
            }
        } else {
            waitingSince = -1;
            Build1Client.msg("Restocked §a" + restocker.taken() + "§r items.");
        }
    }

    /** What's still needed for the rest of the build, minus what's in the inventory. */
    private Map<Item, Integer> remainingNeeds() {
        Map<Item, Integer> need = new HashMap<>();
        ClientLevel lvl = Bot.mc().level;
        int maxY = box.max().getY();
        long perLayer = (long) box.sizeX() * box.sizeZ();
        if (perLayer * (maxY - layer + 1) > 3_000_000) maxY = layer + (int) Math.max(1, 3_000_000 / perLayer) - 1;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = layer; y <= maxY; y++) for (int x = box.min().getX(); x <= box.max().getX(); x++) for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
            m.set(x, y, z);
            if (!lvl.hasChunkAt(m)) continue;
            if (layerFilter != null && !layerFilter.test(m)) continue;
            BlockState want = schem.getBlockState(m), real = lvl.getBlockState(m);
            if (Bucket.isWaterSource(want)) { if (!Bucket.isWaterSource(real)) need.merge(Items.WATER_BUCKET, 1, Integer::sum); continue; }
            if (want.isAir() || want.liquid() || PlacementSolver.skip(want)) continue;
            if (!PlacementSolver.satisfied(real, want)) need.merge(want.getBlock().asItem(), 1, Integer::sum);
            else if (Bucket.wantsWaterlogged(want) && !Bucket.isWaterlogged(real)) need.merge(Items.WATER_BUCKET, 1, Integer::sum);
        }
        need.replaceAll((it, n) -> n - Restocker.have(it));
        need.values().removeIf(n -> n <= 0);
        if (need.containsKey(Items.WATER_BUCKET) && Restocker.have(Items.BUCKET) == 0) need.put(Items.BUCKET, 1);
        return need;
    }

    // =====================================================================================

    private void refreshPlacement() {
        LitematicaBridge.Placement pl = LitematicaBridge.selectedPlacement();
        Level w = LitematicaBridge.schematicWorld();
        if (w != null) schem = w;
        if (Config.followLayers) layerFilter = LitematicaBridge.layerFilter();
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

    private String missingText(int max) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (var e : missing.entrySet()) {
            if (i++ == max) { sb.append("…"); break; }
            sb.append(new ItemStack(e.getKey()).getHoverName().getString()).append(" x").append(e.getValue()).append("  ");
        }
        return sb.toString().trim();
    }

    @Override public List<String> hud() {
        List<String> l = new ArrayList<>();
        l.add("§eBuilding§r " + placementName + " §7[" + Config.mode.name().toLowerCase() + "]" + (pass > 1 ? " §7(pass " + pass + ")" : ""));
        l.add("Layer " + (Math.min(layer, box.max().getY()) - box.min().getY() + 1) + "/" + box.sizeY() + " §7(y=" + layer + ")§r  left: " + work.size());
        l.add("Placed " + placed + (broken > 0 ? "  broken " + broken : "") + "  §7" + state);
        if (wrong > 0) l.add("§c" + wrong + " wrong blocks§7 (turn on Replace wrong)");
        if (unknown > 0) l.add("§7" + unknown + " positions in unloaded chunks");
        if (noSupport > 0 && t % 200 < 100) l.add("§7no-support/angle skips: " + noSupport + "  unreachable: " + unreachable);
        if (!missing.isEmpty()) l.add("§cMissing: §r" + missingText(3));
        return l;
    }
}
