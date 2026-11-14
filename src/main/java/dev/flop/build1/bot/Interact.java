package dev.flop.build1.bot;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Blocks whose state you set by right-clicking after placing them:
 * open fence gates / doors / trapdoors, repeater delay, comparator mode, note block pitch,
 * inverted daylight detector, levers.
 */
public final class Interact {

    public static boolean needs(BlockState real, BlockState want) {
        if (real.getBlock() != want.getBlock()) return false;
        Block b = want.getBlock();
        if (b instanceof FenceGateBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock) {
            if (want.is(Blocks.IRON_DOOR) || want.is(Blocks.IRON_TRAPDOOR)) return false;
            if (real.hasProperty(BlockStateProperties.POWERED) && real.getValue(BlockStateProperties.POWERED)) return false; // redstone decides
            return real.getValue(BlockStateProperties.OPEN) != want.getValue(BlockStateProperties.OPEN);
        }
        if (b instanceof RepeaterBlock) return !real.getValue(BlockStateProperties.DELAY).equals(want.getValue(BlockStateProperties.DELAY));
        if (b instanceof ComparatorBlock) return real.getValue(BlockStateProperties.MODE_COMPARATOR) != want.getValue(BlockStateProperties.MODE_COMPARATOR);
        if (b instanceof NoteBlock) return !real.getValue(BlockStateProperties.NOTE).equals(want.getValue(BlockStateProperties.NOTE));
        if (b instanceof DaylightDetectorBlock) return real.getValue(BlockStateProperties.INVERTED) != want.getValue(BlockStateProperties.INVERTED);
        if (b instanceof LeverBlock) return real.getValue(BlockStateProperties.POWERED) != want.getValue(BlockStateProperties.POWERED);
        return false;
    }

    /** Is clicking with this face / look direction going to do the right thing? */
    private static boolean clickOk(BlockState real, Direction face, float yaw) {
        Block b = real.getBlock();
        if (b instanceof NoteBlock && face == Direction.UP) return false; // top face can place on top instead
        if (b instanceof FenceGateBlock && !real.getValue(BlockStateProperties.OPEN)) {
            // opening from the "back" swings it the other way (and flips its facing)
            Direction look = Direction.fromYRot(yaw);
            return look != real.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        return true;
    }

    public static PlacementSolver.Plan plan(BlockPos pos, boolean legit) {
        var lvl = Bot.mc().level;
        BlockState real = lvl.getBlockState(pos);
        Vec3 eye = Bot.eye();
        double reach = Bot.reach();
        var shape = real.getShape(lvl, pos);
        AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
        if (!legit) {
            Direction face = Bot.faceToward(pos);
            if (real.getBlock() instanceof NoteBlock && face == Direction.UP) face = Direction.Plane.HORIZONTAL.getRandomDirection(net.minecraft.util.RandomSource.create());
            Vec3 c = Bot.faceCenter(pos, face);
            if (eye.distanceTo(c) > reach + 0.5) return null;
            float[] a = Bot.anglesTo(eye, c);
            float yaw = a[0];
            if (real.getBlock() instanceof FenceGateBlock) yaw = real.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
            return new PlacementSolver.Plan(new BlockHitResult(c, face, pos, false), yaw, a[1], false);
        }
        List<Vec3> pts = new ArrayList<>();
        double[] g = {0.5, 0.3, 0.7};
        for (double x : g) for (double y : g) for (double z : g)
            pts.add(new Vec3(Mth.lerp(x, box.minX, box.maxX), Mth.lerp(y, box.minY, box.maxY), Mth.lerp(z, box.minZ, box.maxZ)));
        for (Vec3 pt : pts) {
            if (eye.distanceTo(pt) > reach + 0.3) continue;
            float[] a = Bot.anglesTo(eye, pt);
            BlockHitResult hit = PlacementSolver.raycast(eye, a[0], a[1], reach + 0.5);
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
            if (eye.distanceTo(hit.getLocation()) > reach) continue;
            if (!clickOk(real, hit.getDirection(), a[0])) continue;
            return new PlacementSolver.Plan(hit, a[0], a[1], false);
        }
        return null;
    }

    /** Re-check the real crosshair hit right before clicking (closet). */
    public static boolean hitOk(BlockPos pos, BlockHitResult hit, float yaw) {
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return false;
        return clickOk(Bot.mc().level.getBlockState(pos), hit.getDirection(), yaw);
    }

    private Interact() {}
}
