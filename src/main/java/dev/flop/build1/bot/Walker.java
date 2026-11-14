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

/** Follows a PathFinder path by holding movement keys and steering the camera. */
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
    private float walkPitch = 10f;

    public Status status() { return status; }
    public boolean busy() { return status == Status.WALKING; }
    public int remaining() { return path == null ? 0 : path.size() - idx; }

    public void start(List<PathFinder.Step> p) {
        path = p;
        idx = 0;
        stuck = 0;
        lastDist = Double.MAX_VALUE;
        walkPitch = 4f + Bot.rng().nextFloat() * 14f;
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

    public static boolean hasScaffold() {
        return scaffoldSlot() >= 0 || Bot.mc().player.getAbilities().instabuild;
    }

    public void tick() {
        if (status != Status.WALKING) return;
        LocalPlayer p = Bot.mc().player;
        if (idx >= path.size()) { finish(); return; }
        PathFinder.Step step = path.get(idx);
        BlockPos node = step.pos();
        Vec3 pos = p.position();
        double dx = node.getX() + 0.5 - pos.x, dz = node.getZ() + 0.5 - pos.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double dy = node.getY() - pos.y;

        if (step.pillar()) { pillar(p, node); return; }

        if (horiz < (Config.closet() ? 0.4 : 0.3) && Math.abs(dy) < 0.6 && (p.onGround() || p.isInWater())) {
            idx++;
            stuck = 0;
            lastDist = Double.MAX_VALUE;
            if (idx >= path.size()) finish();
            return;
        }
        // Steer toward a point a little further along the path so turns are rounder.
        Vec3 aimAt = new Vec3(node.getX() + 0.5, 0, node.getZ() + 0.5);
        if (Config.closet() && idx + 1 < path.size() && horiz < 1.2 && !path.get(idx + 1).pillar()) {
            BlockPos nx = path.get(idx + 1).pos();
            aimAt = aimAt.lerp(new Vec3(nx.getX() + 0.5, 0, nx.getZ() + 0.5), 0.5);
        }
        float yaw = (float) (Math.atan2(aimAt.z - pos.z, aimAt.x - pos.x) * 180.0 / Math.PI) - 90f;
        boolean facing;
        if (Config.closet()) {
            Rotator.aim(yaw, walkPitch);
            facing = Math.abs(Mth.wrapDegrees(p.getYRot() - yaw)) < 50;
        } else {
            Bot.setRotation(yaw, 10f);
            facing = true;
        }
        boolean jump = (dy > 0.5 && horiz < 1.6) || p.isInWater();
        boolean forward = horiz > 0.12 && facing;
        boolean sprint = forward && !jump && straightAhead() >= 4;
        Bot.keys(forward, jump, false, sprint);

        double dist = horiz + Math.abs(dy);
        if (dist < lastDist - 0.05) { lastDist = dist; stuck = 0; } else stuck++;
        if (stuck > (Config.closet() ? 70 : 50) || horiz > 4 || dy < -4.5) fail();
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

    private void pillar(LocalPlayer p, BlockPos node) {
        BlockPos under = node.below(); // the block we need to create
        var lvl = Bot.mc().level;
        if (!lvl.getBlockState(under).canBeReplaced()) {
            Bot.keys(false, false, false);
            if (p.onGround() && p.getY() >= node.getY() - 0.1) { idx++; stuck = 0; if (idx >= path.size()) finish(); }
            else if (++stuck > 40) fail();
            return;
        }
        BlockPos support = under.below();
        Vec3 top = Bot.faceCenter(support, Direction.UP);
        float downPitch = Config.closet() ? 82f + Bot.rng().nextFloat() * 6f : 90f;
        if (Config.closet()) Rotator.aim(p.getYRot(), downPitch); else Bot.setRotation(p.getYRot(), 90f);

        int slot = scaffoldSlot();
        if (slot < 0) {
            if (!Bot.creativeGive(Items.DIRT)) { fail(); return; }
        } else if (p.getInventory().getSelectedSlot() != slot && slot < 9) {
            p.getInventory().setSelectedSlot(slot);
        } else if (slot >= 9) {
            Bot.hold(slot);
            return;
        }
        if (Config.closet() && !Rotator.settled()) { Bot.keys(false, false, false); return; }
        if (p.onGround()) { Bot.keys(false, true, false); if (++stuck > 60) fail(); return; }
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

    private void finish() {
        status = Status.DONE;
        Bot.releaseKeys();
    }

    private void fail() {
        status = Status.FAILED;
        Bot.releaseKeys();
    }
}
