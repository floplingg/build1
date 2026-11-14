package dev.flop.build1.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Small A* over standing positions. Moves: walk (incl. diagonals), step up 1, drop up to 3,
 * and pillar (jump + place a block under yourself) using whatever item the pillar rule allows.
 */
public final class PathFinder {

    /** pillarItem = what to place under ourselves for a pillar step (null for normal steps). */
    public record Step(BlockPos pos, boolean pillar, Item pillarItem) {}

    public interface Goal {
        boolean reached(BlockPos feet);
        double heuristic(BlockPos feet);
    }

    private record Node(BlockPos pos, double g, double f, Node parent, Item pillar) {}

    private static final int MAX_NODES = 8000;

    /**
     * @param pillarRule feet position -> item to pillar with there (null = can't pillar there); rule may be null
     * @param avoidFeet  positions we must not end up standing in (blocks we still have to place)
     */
    public static List<Step> find(BlockPos start, Goal goal, Function<BlockPos, Item> pillarRule, Predicate<BlockPos> avoidFeet) {
        ClientLevel lvl = Bot.mc().level;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        open.add(new Node(start, 0, goal.heuristic(start), null, null));
        best.put(start.asLong(), 0.0);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node n = open.poll();
            if (n.g > best.getOrDefault(n.pos.asLong(), Double.MAX_VALUE) + 1e-9) continue;
            if (goal.reached(n.pos) && !avoidFeet.test(n.pos)) return build(n);
            for (Node m : neighbours(lvl, n, pillarRule, avoidFeet)) {
                double old = best.getOrDefault(m.pos.asLong(), Double.MAX_VALUE);
                if (m.g < old) {
                    best.put(m.pos.asLong(), m.g);
                    open.add(new Node(m.pos, m.g, m.g + goal.heuristic(m.pos) * 1.1, n, m.pillar));
                }
            }
        }
        return null;
    }

    private static List<Step> build(Node n) {
        List<Step> out = new ArrayList<>();
        for (Node c = n; c.parent != null; c = c.parent) out.add(new Step(c.pos, c.pillar != null, c.pillar));
        Collections.reverse(out);
        return out;
    }

    private static List<Node> neighbours(ClientLevel lvl, Node n, Function<BlockPos, Item> pillarRule, Predicate<BlockPos> avoid) {
        List<Node> out = new ArrayList<>(12);
        BlockPos p = n.pos;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos m = p.relative(d);
            if (!lvl.hasChunkAt(m)) continue;
            // standing in a block we still need to place costs a lot (we'd be in the way)
            double pen = avoid.test(m) ? 6 : 0;
            if (body(lvl, m)) {
                if (standable(lvl, m)) {
                    out.add(new Node(m, n.g + 1 + pen, 0, n, null));
                } else {
                    for (int k = 1; k <= 3; k++) { // drop down
                        BlockPos f = m.below(k);
                        if (!feetOk(lvl, f) || !headOk(lvl, f.above())) break;
                        if (standable(lvl, f)) { out.add(new Node(f, n.g + 1 + k * 0.5 + (avoid.test(f) ? 6 : 0), 0, n, null)); break; }
                    }
                }
            } else if (headOk(lvl, p.above(2)) && body(lvl, m.above()) && standable(lvl, m.above())) {
                out.add(new Node(m.above(), n.g + 2 + (avoid.test(m.above()) ? 6 : 0), 0, n, null)); // step / jump up
            }
        }
        int[][] diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] dd : diag) {
            BlockPos m = p.offset(dd[0], 0, dd[1]);
            if (!lvl.hasChunkAt(m)) continue;
            if (body(lvl, m) && standable(lvl, m) && body(lvl, p.offset(dd[0], 0, 0)) && body(lvl, p.offset(0, 0, dd[1]))) {
                out.add(new Node(m, n.g + 1.42 + (avoid.test(m) ? 6 : 0), 0, n, null));
            }
        }
        if (pillarRule != null && headOk(lvl, p.above(2)) && lvl.getBlockState(p).canBeReplaced()) {
            Item it = pillarRule.apply(p);
            if (it != null) out.add(new Node(p.above(), n.g + (avoid.test(p) ? 2.5 : 6), 0, n, it)); // cheap if we're filling a build block
        }
        return out;
    }

    /** Feet + head space free. */
    public static boolean body(ClientLevel lvl, BlockPos feet) {
        return feetOk(lvl, feet) && headOk(lvl, feet.above());
    }

    private static boolean hazard(BlockState s) {
        if (!s.getFluidState().isEmpty() && s.getFluidState().is(FluidTags.LAVA)) return true;
        Block b = s.getBlock();
        return b instanceof BaseFireBlock || b instanceof WebBlock || b instanceof SweetBerryBushBlock
                || b instanceof PowderSnowBlock || b instanceof MagmaBlock || b instanceof CactusBlock;
    }

    /** Feet can be in it: empty, or very low (carpet, snow layer, pressure plate...). */
    public static boolean feetOk(ClientLevel lvl, BlockPos pos) {
        BlockState s = lvl.getBlockState(pos);
        if (hazard(s)) return false;
        VoxelShape c = s.getCollisionShape(lvl, pos);
        return c.isEmpty() || c.max(Direction.Axis.Y) <= 0.2;
    }

    public static boolean headOk(ClientLevel lvl, BlockPos pos) {
        BlockState s = lvl.getBlockState(pos);
        return !hazard(s) && s.getCollisionShape(lvl, pos).isEmpty();
    }

    /** Kept for callers that just want "can I walk through this block". */
    public static boolean passable(ClientLevel lvl, BlockPos pos) { return headOk(lvl, pos); }

    /** Something solid to stand on below the feet (or a low block like carpet at the feet). */
    public static boolean standable(ClientLevel lvl, BlockPos feet) {
        BlockState at = lvl.getBlockState(feet);
        if (!at.getCollisionShape(lvl, feet).isEmpty()) return true; // carpet etc: we stand on it
        BlockPos below = feet.below();
        BlockState s = lvl.getBlockState(below);
        Block b = s.getBlock();
        if (b instanceof MagmaBlock || b instanceof CactusBlock || b instanceof CampfireBlock) return false;
        if (b instanceof FenceBlock || b instanceof WallBlock || b instanceof FenceGateBlock) return false;
        return !s.getCollisionShape(lvl, below).isEmpty() || lvl.getFluidState(feet).is(FluidTags.WATER);
    }

    private PathFinder() {}
}
