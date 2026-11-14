package dev.flop.build1.bot;

import dev.flop.build1.Config;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Mines one block at a time with the best tool.
 * Blatant: snaps and mines through anything. Closet: turns to a visible spot, and only mines
 * while the crosshair is really on the block.
 */
public final class Breaker {
    /** Read by MinecraftMixin to stop vanilla cancelling our mining each tick. */
    public static volatile boolean active;

    private BlockPos target;
    private Vec3 aimPoint;
    private int ticks, blockedTicks, wait;

    public BlockPos target() { return target; }
    public int ticks() { return ticks; }
    /** Closet: ticks the crosshair has been blocked by something else. */
    public int blocked() { return blockedTicks; }

    public static boolean gone(BlockState s) {
        return s.isAir() || s.liquid();
    }

    /** Continue breaking pos. Returns true once it's gone. */
    public boolean tick(BlockPos pos) {
        var mc = Bot.mc();
        BlockState s = mc.level.getBlockState(pos);
        if (gone(s)) { reset(); return true; }
        if (!pos.equals(target)) {
            if (active) mc.gameMode.stopDestroyBlock();
            active = false;
            target = pos.immutable();
            ticks = 0;
            blockedTicks = 0;
            aimPoint = Config.closet() ? visiblePoint(pos) : null;
            wait = pickTool(s) ? 0 : (Config.closet() ? 3 + Bot.rng().nextInt(3) : 1);
        }
        if (wait > 0) { wait--; return false; }

        Direction face;
        if (Config.closet()) {
            if (aimPoint == null) aimPoint = visiblePoint(pos);
            if (aimPoint == null) { blockedTicks++; return false; }
            float[] a = Bot.anglesTo(Bot.eye(), aimPoint);
            Rotator.aim(a[0], a[1]);
            if (!Rotator.settled() && !active) return false;
            LocalPlayer p = mc.player;
            BlockHitResult hit = PlacementSolver.raycast(Bot.eye(), p.getYRot(), p.getXRot(), Bot.reach() + 0.5);
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
                blockedTicks++;
                if (blockedTicks % 10 == 0) aimPoint = visiblePoint(pos);
                return false;
            }
            blockedTicks = 0;
            face = hit.getDirection();
        } else {
            face = Bot.faceToward(pos);
            float[] a = Bot.anglesTo(Bot.eye(), Bot.faceCenter(pos, face));
            Bot.faceNow(a[0], a[1]);
        }
        active = true;
        mc.gameMode.continueDestroyBlock(pos, face);
        mc.player.swing(InteractionHand.MAIN_HAND);
        ticks++;
        return gone(mc.level.getBlockState(pos));
    }

    public void reset() {
        var mc = Bot.mc();
        if (active && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        active = false;
        target = null;
        aimPoint = null;
        ticks = 0;
        blockedTicks = 0;
    }

    /** A point on pos the crosshair can actually reach from here, or null. */
    public static Vec3 visiblePoint(BlockPos pos) {
        var lvl = Bot.mc().level;
        Vec3 eye = Bot.eye();
        var shape = lvl.getBlockState(pos).getShape(lvl, pos);
        AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
        List<Vec3> pts = new ArrayList<>();
        pts.add(box.getCenter());
        double[] g = {0.5, 0.25, 0.75};
        for (double a : g) for (double b : g) for (double c : g)
            pts.add(new Vec3(box.minX + (box.maxX - box.minX) * a, box.minY + (box.maxY - box.minY) * b, box.minZ + (box.maxZ - box.minZ) * c));
        double reach = Bot.reach();
        for (Vec3 pt : pts) {
            if (eye.distanceTo(pt) > reach + 0.3) continue;
            float[] r = Bot.anglesTo(eye, pt);
            BlockHitResult hit = PlacementSolver.raycast(eye, r[0], r[1], reach + 0.5);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && eye.distanceTo(hit.getLocation()) <= reach)
                return pt.add((Bot.rng().nextDouble() - 0.5) * 0.04, (Bot.rng().nextDouble() - 0.5) * 0.04, (Bot.rng().nextDouble() - 0.5) * 0.04);
        }
        return null;
    }

    /** Selects the fastest safe tool. Returns true if it's in hand right now. */
    private static boolean pickTool(BlockState s) {
        LocalPlayer p = Bot.mc().player;
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = score(inv.getItem(inv.getSelectedSlot()), s);
        for (int i = 0; i < 36; i++) {
            double sc = score(inv.getItem(i), s);
            if (sc > bestScore + 1e-6) { bestScore = sc; best = i; }
        }
        if (best < 0) return true;
        boolean now = Bot.hold(best);
        return now && !Config.closet();
    }

    private static double score(ItemStack st, BlockState s) {
        if (st.isEmpty()) return 1.0;
        if (st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() < 6) return -1; // don't break tools
        double sp = st.getDestroySpeed(s);
        if (s.requiresCorrectToolForDrops() && st.isCorrectToolForDrops(s)) sp *= 2;
        return sp;
    }
}
