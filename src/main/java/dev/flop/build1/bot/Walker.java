package dev.flop.build1.bot;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
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

    public Status status() { return status; }
    public boolean busy() { return status == Status.WALKING; }
    public int remaining() { return path == null ? 0 : path.size() - idx; }

    public void start(List<PathFinder.Step> p) {
        path = p;
        idx = 0;
        stuck = 0;
        lastDist = Double.MAX_VALUE;
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
        if (idx >= path.size()) { status = Status.DONE; Bot.releaseKeys(); return; }
        PathFinder.Step step = path.get(idx);
        BlockPos node = step.pos();
        Vec3 pos = p.position();
        double dx = node.getX() + 0.5 - pos.x, dz = node.getZ() + 0.5 - pos.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double dy = node.getY() - pos.y;

        if (step.pillar()) {
            pillar(p, node);
            return;
        }

        if (horiz < 0.3 && Math.abs(dy) < 0.6 && (p.onGround() || p.isInWater())) {
            idx++;
            stuck = 0;
            lastDist = Double.MAX_VALUE;
            if (idx >= path.size()) { status = Status.DONE; Bot.releaseKeys(); }
            return;
        }
        // steer
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        Bot.setRotation(yaw, 10f);
        boolean jump = (dy > 0.5 && horiz < 1.6) || p.isInWater();
        boolean forward = horiz > 0.12;
        // sneak when close to the target so we don't overshoot off edges
        Bot.keys(forward, jump, false);

        double dist = horiz + Math.abs(dy);
        if (dist < lastDist - 0.05) { lastDist = dist; stuck = 0; } else stuck++;
        if (stuck > 50 || horiz > 4 || dy < -4.5) fail();
    }

    private void pillar(LocalPlayer p, BlockPos node) {
        BlockPos under = node.below(); // the block we need to create
        var lvl = Bot.mc().level;
        if (!lvl.getBlockState(under).canBeReplaced()) {
            // already placed: wait to land on it
            Bot.keys(false, false, false);
            if (p.onGround() && p.getY() >= node.getY() - 0.1) { idx++; stuck = 0; if (idx >= path.size()) { status = Status.DONE; Bot.releaseKeys(); } }
            else if (++stuck > 40) fail();
            return;
        }
        Bot.setRotation(p.getYRot(), 90f);
        if (p.onGround()) { Bot.keys(false, true, false); stuck++; if (stuck > 40) fail(); return; }
        Bot.keys(false, false, false);
        if (p.getY() < under.getY() + 1.05) return; // not high enough yet
        int slot = scaffoldSlot();
        if (slot < 0) {
            if (!Bot.creativeGive(Items.DIRT)) { fail(); return; }
        } else if (!Bot.hold(slot)) return;
        if (!(p.getMainHandItem().getItem() instanceof BlockItem)) return;
        BlockPos support = under.below();
        Vec3 hv = Bot.faceCenter(support, Direction.UP);
        float[] a = Bot.anglesTo(Bot.eye(), hv);
        Bot.faceNow(a[0], a[1]);
        Bot.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hv, Direction.UP, support, false));
        p.swing(InteractionHand.MAIN_HAND);
    }

    private void fail() {
        status = Status.FAILED;
        Bot.releaseKeys();
    }
}
