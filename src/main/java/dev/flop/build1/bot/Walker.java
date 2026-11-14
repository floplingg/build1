package dev.flop.build1.bot;

import dev.flop.build1.Config;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Follows a PathFinder path. Uses "pure pursuit": always steer at a point a little way ahead
 * on the path, and count a node as done once we've passed it (not only when we stand dead on it).
 * That stops the old overshoot -> turn around -> overshoot back-and-forth.
 */
public final class Walker {
    public enum Status { IDLE, WALKING, DONE, FAILED }

    /** Cheap blocks used to pillar up, in order of preference. */
    public static final Item[] SCAFFOLD = {Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE,
            Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.DEEPSLATE, Items.TUFF, Items.SANDSTONE,
            Items.END_STONE, Items.BLACKSTONE, Items.OAK_PLANKS, Items.SPRUCE_PLANKS};

    private List<PathFinder.Step> path;
    private int idx;
    private int stuck;
    private double lastDist = Double.MAX_VALUE;
    private Status status = Status.IDLE;
    private float pitchBias;

    public Status status() { return status; }
    public boolean busy() { return status == Status.WALKING; }
    public int remaining() { return path == null ? 0 : path.size() - idx; }

    public void start(List<PathFinder.Step> p) {
        path = p;
        idx = 0;
        stuck = 0;
        lastDist = Double.MAX_VALUE;
        pitchBias = (Bot.rng().nextFloat() - 0.5f) * 8f;
        status = p == null ? Status.FAILED : (p.isEmpty() ? Status.DONE : Status.WALKING);
    }

    public void stop() {
        path = null;
        status = Status.IDLE;
        Bot.releaseKeys();
    }

    public static int scaffoldSlot() {
        for (Item it : SCAFFOLD) {
            int s = Bot.find(it);
            if (s >= 0) return s;
        }
        return -1;
    }

    public static Item scaffoldItem() {
        int s = scaffoldSlot();
        if (s >= 0) return Bot.mc().player.getInventory().getItem(s).getItem();
        return Bot.mc().player.getAbilities().instabuild ? Items.DIRT : null;
    }

    private static Vec3 center(BlockPos b) { return new Vec3(b.getX() + 0.5, b.getY(), b.getZ() + 0.5); }

    public void tick() {
        if (status != Status.WALKING) return;
        LocalPlayer p = Bot.mc().player;
        if (idx >= path.size()) { finish(); return; }
        PathFinder.Step step = path.get(idx);
        if (step.pillar()) { pillar(p, step); return; }

        Vec3 pos = p.position();
        BlockPos node = step.pos();
        Vec3 nc = center(node);
        double horiz = Math.hypot(nc.x - pos.x, nc.z - pos.z);
        double dy = node.getY() - pos.y;
        boolean grounded = p.onGround() || p.isInWater();

        // node reached, or passed (we're beyond it in the direction of the next node)
        boolean passed = false;
        if (idx + 1 < path.size() && !path.get(idx + 1).pillar() && horiz < 1.1 && Math.abs(dy) < 0.6) {
            Vec3 nn = center(path.get(idx + 1).pos());
            double sx = nn.x - nc.x, sz = nn.z - nc.z;
            passed = (pos.x - nc.x) * sx + (pos.z - nc.z) * sz > 0;
        }
        if ((horiz < 0.35 && Math.abs(dy) < 0.6 && grounded) || passed) {
            idx++;
            stuck = 0;
            lastDist = Double.MAX_VALUE;
            if (idx >= path.size()) { finish(); return; }
            if (path.get(idx).pillar()) { Bot.keys(false, false, false); return; }
            node = path.get(idx).pos();
            nc = center(node);
            horiz = Math.hypot(nc.x - pos.x, nc.z - pos.z);
            dy = node.getY() - pos.y;
        }

        // pure pursuit: steer at a point ~1.3 blocks ahead along the path (just the node near the end)
        Vec3 steer = lookAhead(pos, 1.3);
        float yaw = (float) (Math.atan2(steer.z - pos.z, steer.x - pos.x) * 180.0 / Math.PI) - 90f;
        boolean facing;
        if (Config.closet()) {
            // gaze a few blocks ahead at the ground, like a person walking
            Vec3 gaze = lookAhead(pos, 4.0);
            float pitch = Mth.clamp(Bot.anglesTo(Bot.eye(), new Vec3(gaze.x, gaze.y + 0.4, gaze.z))[1] + pitchBias, -10f, 45f);
            Rotator.aim(yaw, pitch);
            facing = Math.abs(Mth.wrapDegrees(p.getYRot() - yaw)) < 55;
        } else {
            Bot.setRotation(yaw, 10f);
            facing = true;
        }
        boolean jump = (dy > 0.5 && horiz < 1.7) || (p.isInWater() && dy > -0.5);
        boolean last = idx == path.size() - 1;
        boolean forward = facing && (horiz > (last ? 0.2 : 0.05));
        boolean sprint = forward && !jump && !last && straightAhead() >= 4;
        Bot.keys(forward, jump, false, sprint);

        double dist = horiz + Math.abs(dy);
        if (dist < lastDist - 0.04) { lastDist = dist; stuck = 0; } else stuck++;
        if (stuck > (Config.closet() ? 60 : 45) || horiz > 4.5 || dy < -4.5) fail();
    }

    /** Point `dist` blocks further along the path from the current node. */
    private Vec3 lookAhead(Vec3 from, double dist) {
        Vec3 prev = new Vec3(from.x, path.get(idx).pos().getY(), from.z);
        double left = dist;
        for (int i = idx; i < path.size(); i++) {
            if (path.get(i).pillar()) return center(path.get(Math.max(idx, i - 1)).pos());
            Vec3 c = center(path.get(i).pos());
            double seg = Math.hypot(c.x - prev.x, c.z - prev.z);
            if (seg >= left && seg > 1e-6) return prev.add(c.subtract(prev).scale(left / seg));
            left -= seg;
            prev = c;
        }
        return prev;
    }

    /** How many upcoming steps continue in the same flat direction. */
    private int straightAhead() {
        if (idx + 1 >= path.size()) return 0;
        BlockPos a = path.get(idx).pos(), b = path.get(idx + 1).pos();
        int sx = b.getX() - a.getX(), sz = b.getZ() - a.getZ();
        int n = 1;
        for (int i = idx + 1; i + 1 < path.size(); i++) {
            BlockPos c = path.get(i).pos(), d = path.get(i + 1).pos();
            if (d.getX() - c.getX() != sx || d.getZ() - c.getZ() != sz || d.getY() != c.getY() || path.get(i + 1).pillar()) break;
            n++;
        }
        return n;
    }

    private void pillar(LocalPlayer p, PathFinder.Step step) {
        BlockPos node = step.pos();
        BlockPos under = node.below(); // the block we need to create (where our feet are now)
        var lvl = Bot.mc().level;
        if (!lvl.getBlockState(under).canBeReplaced()) {
            Bot.keys(false, false, false);
            if (p.onGround() && p.getY() >= node.getY() - 0.1) { idx++; stuck = 0; if (idx >= path.size()) finish(); }
            else if (++stuck > 40) fail();
            return;
        }
        // centre ourselves on the column first, or the jump lands us next to it
        double ox = under.getX() + 0.5 - p.getX(), oz = under.getZ() + 0.5 - p.getZ();
        BlockPos support = under.below();
        Vec3 top = Bot.faceCenter(support, Direction.UP);
        float downPitch = Config.closet() ? 82f + Bot.rng().nextFloat() * 6f : 90f;
        if (Config.closet()) Rotator.aim(p.getYRot(), downPitch); else Bot.setRotation(p.getYRot(), 90f);

        Item item = step.pillarItem() != null ? step.pillarItem() : scaffoldItem();
        if (item == null) { fail(); return; }
        int slot = Bot.find(item);
        if (slot < 0) {
            if (!Bot.creativeGive(item)) { fail(); return; }
            return;
        }
        if (!Bot.hold(slot)) return;
        if (Config.closet() && !Rotator.settled()) { Bot.keys(false, false, false); return; }
        if (p.onGround()) {
            if (ox * ox + oz * oz > 0.06) { Bot.keys(false, false, false); nudge(p, ox, oz); if (++stuck > 60) fail(); return; }
            Bot.keys(false, true, false);
            if (++stuck > 80) fail();
            return;
        }
        Bot.keys(false, false, false);
        if (p.getY() < under.getY() + 1.05) return; // not high enough yet
        if (!(p.getMainHandItem().getItem() instanceof BlockItem)) return;

        BlockHitResult hit;
        if (Config.closet()) {
            hit = PlacementSolver.raycast(Bot.eye(), p.getYRot(), p.getXRot(), Bot.reach());
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(support) || hit.getDirection() != Direction.UP) return;
        } else {
            float[] a = Bot.anglesTo(Bot.eye(), top);
            Bot.faceNow(a[0], a[1]);
            hit = new BlockHitResult(top, Direction.UP, support, false);
        }
        Bot.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** Small step toward the column centre while looking down: walk with the keys relative to our yaw. */
    private static void nudge(LocalPlayer p, double ox, double oz) {
        double yawRad = Math.toRadians(p.getYRot());
        double fwdX = -Math.sin(yawRad), fwdZ = Math.cos(yawRad);
        double rightX = -fwdZ, rightZ = fwdX;
        double f = ox * fwdX + oz * fwdZ, r = ox * rightX + oz * rightZ;
        Bot.strafe(f > 0.1, f < -0.1, r < -0.1, r > 0.1, true); // sneak so we can't slip off
    }

    private void finish() {
        status = Status.DONE;
        Bot.releaseKeys();
    }

    private void fail() {
        status = Status.FAILED;
        Bot.releaseKeys();
    }
}
