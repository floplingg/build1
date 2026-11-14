package dev.flop.build1.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Water: placing sources and waterlogging blocks with a water bucket, and refilling buckets.
 * Buckets are used with a plain right click, and the SERVER raycasts from your real rotation,
 * so in both modes we genuinely look at the right face.
 */
public final class Bucket {

    public record Aim(float yaw, float pitch) {}

    public static boolean isWaterSource(BlockState s) {
        return s.getBlock() == Blocks.WATER && s.getFluidState().isSource();
    }

    public static boolean wantsWaterlogged(BlockState want) {
        return want.hasProperty(BlockStateProperties.WATERLOGGED) && want.getValue(BlockStateProperties.WATERLOGGED);
    }

    public static boolean isWaterlogged(BlockState s) {
        return s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED);
    }

    /** Can we pour water straight into pos right now (it's air / flowing water, nothing with an outline)? */
    public static boolean canPour(BlockPos pos) {
        ClientLevel lvl = Bot.mc().level;
        BlockState s = lvl.getBlockState(pos);
        return (s.isAir() || (s.liquid() && s.getFluidState().is(FluidTags.WATER) && !s.getFluidState().isSource()))
                && s.getShape(lvl, pos).isEmpty();
    }

    /** Would the server's bucket raycast along (yaw,pitch) put water where we want? */
    public static boolean rayWorks(BlockPos pos, boolean waterlog, Vec3 eye, float yaw, float pitch) {
        BlockHitResult hit = PlacementSolver.raycast(eye, yaw, pitch, Bot.mc().player.blockInteractionRange());
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        ClientLevel lvl = Bot.mc().level;
        if (waterlog) return hit.getBlockPos().equals(pos);
        BlockPos hp = hit.getBlockPos();
        if (!hp.relative(hit.getDirection()).equals(pos)) return false;
        BlockState hs = lvl.getBlockState(hp);
        // clicking a waterloggable block would waterlog IT instead
        return !(hs.getBlock() instanceof LiquidBlockContainer) || isWaterlogged(hs);
    }

    /** A rotation that pours water into pos (or waterlogs the block at pos). */
    public static Aim solve(BlockPos pos, boolean waterlog, Vec3 eye) {
        ClientLevel lvl = Bot.mc().level;
        double reach = Math.min(Bot.reach(), Bot.mc().player.blockInteractionRange() - 0.1);
        List<BlockPos> targets = new ArrayList<>();
        if (waterlog) targets.add(pos);
        else for (Direction d : Direction.values()) {
            BlockPos nb = pos.relative(d);
            BlockState ns = lvl.getBlockState(nb);
            if (!ns.getShape(lvl, nb).isEmpty()) targets.add(nb);
        }
        for (BlockPos tp : targets) {
            BlockState ts = lvl.getBlockState(tp);
            AABB box = ts.getShape(lvl, tp).isEmpty() ? new AABB(tp) : ts.getShape(lvl, tp).bounds().move(tp);
            double[] g = {0.5, 0.25, 0.75};
            for (Direction face : Direction.values()) {
                if (!waterlog && !tp.relative(face).equals(pos)) continue;
                for (double a : g) for (double b : g) {
                    Vec3 pt = pointOn(box, face, a, b);
                    if (eye.distanceTo(pt) > reach) continue;
                    float[] r = Bot.anglesTo(eye, pt);
                    if (rayWorks(pos, waterlog, eye, r[0], r[1])) return new Aim(r[0], r[1]);
                }
            }
        }
        return null;
    }

    private static Vec3 pointOn(AABB b, Direction f, double u, double v) {
        double x, y, z;
        switch (f) {
            case UP -> { y = b.maxY - 0.01; x = b.minX + b.getXsize() * u; z = b.minZ + b.getZsize() * v; }
            case DOWN -> { y = b.minY + 0.01; x = b.minX + b.getXsize() * u; z = b.minZ + b.getZsize() * v; }
            case EAST -> { x = b.maxX - 0.01; y = b.minY + b.getYsize() * u; z = b.minZ + b.getZsize() * v; }
            case WEST -> { x = b.minX + 0.01; y = b.minY + b.getYsize() * u; z = b.minZ + b.getZsize() * v; }
            case SOUTH -> { z = b.maxZ - 0.01; x = b.minX + b.getXsize() * u; y = b.minY + b.getYsize() * v; }
            default -> { z = b.minZ + 0.01; x = b.minX + b.getXsize() * u; y = b.minY + b.getYsize() * v; }
        }
        return new Vec3(x, y, z);
    }

    // ---------------- refilling ----------------

    /** Nearest water source we're allowed to scoop from. */
    public static BlockPos findSource(BlockPos center, int r, Predicate<BlockPos> keep) {
        ClientLevel lvl = Bot.mc().level;
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) for (int dy = -6; dy <= 6; dy++) for (int dz = -r; dz <= r; dz++) {
            m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
            if (!lvl.hasChunkAt(m) || !isWaterSource(lvl.getBlockState(m)) || keep.test(m)) continue;
            // infinite-ish: prefer sources with another source next to them
            double d = m.distSqr(center);
            if (d < bd) { bd = d; best = m.immutable(); }
        }
        return best;
    }

    /** Rotation whose source-only raycast hits that water source. */
    public static Aim solveScoop(BlockPos src, Vec3 eye) {
        double reach = Math.min(Bot.reach(), Bot.mc().player.blockInteractionRange() - 0.1);
        double[] g = {0.5, 0.3, 0.7};
        for (double a : g) for (double b : g) for (double h : new double[]{0.8, 0.5, 0.2}) {
            Vec3 pt = new Vec3(src.getX() + a, src.getY() + h, src.getZ() + b);
            if (eye.distanceTo(pt) > reach) continue;
            float[] r = Bot.anglesTo(eye, pt);
            Vec3 to = eye.add(Vec3.directionFromRotation(r[1], r[0]).scale(Bot.mc().player.blockInteractionRange()));
            BlockHitResult hit = Bot.mc().level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, Bot.mc().player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(src)) return new Aim(r[0], r[1]);
        }
        return null;
    }

    private Bucket() {}
}
