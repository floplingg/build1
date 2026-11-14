package dev.flop.build1.bot;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Stand somewhere the block is not just in reach, but can actually be placed correctly from (right side, visible face). */
public final class PlaceGoal implements PathFinder.Goal {
    private final ReachGoal reach;
    private final BlockPos pos;
    private final BlockState want;
    private final ItemStack stack;
    private final boolean legit;
    private final Map<Long, Boolean> cache = new HashMap<>();

    public PlaceGoal(BlockPos pos, BlockState want, ItemStack stack, double reachDist, boolean legit) {
        this.reach = new ReachGoal(pos, reachDist);
        this.pos = pos;
        this.want = want;
        this.stack = stack;
        this.legit = legit;
    }

    @Override public boolean reached(BlockPos feet) {
        if (!reach.reached(feet)) return false;
        return cache.computeIfAbsent(feet.asLong(), k -> {
            Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
            return PlacementSolver.solve(pos, want, stack, eye, legit) != null;
        });
    }

    @Override public double heuristic(BlockPos feet) { return reach.heuristic(feet); }
}
