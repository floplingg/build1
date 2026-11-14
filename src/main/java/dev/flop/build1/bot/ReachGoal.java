package dev.flop.build1.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Stand somewhere the target block is within reach (and we aren't standing inside it). */
public record ReachGoal(BlockPos target, double reach) implements PathFinder.Goal {
    private static double eyeDist(BlockPos feet, BlockPos t) {
        return new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5).distanceTo(Vec3.atCenterOf(t));
    }

    @Override public boolean reached(BlockPos feet) {
        return !feet.equals(target) && !feet.above().equals(target) && eyeDist(feet, target) <= reach;
    }

    @Override public double heuristic(BlockPos feet) {
        return Math.max(0, eyeDist(feet, target) - reach);
    }
}
