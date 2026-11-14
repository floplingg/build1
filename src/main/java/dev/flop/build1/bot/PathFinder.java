package dev.flop.build1.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Small A* over standing positions. Moves: walk (incl. diagonals), step up 1, drop up to 3,
 * and optionally pillar (jump + place a block under yourself).
 */
public final class PathFinder {

    public record Step(BlockPos pos, boolean pillar) {}

    public interface Goal {
        boolean reached(BlockPos feet);
        double heuristic(BlockPos feet);
    }

    private record Node(BlockPos pos, double g, double f, Node parent, boolean pillar) {}

    private static final int MAX_NODES = 7000;

    public static List<Step> find(BlockPos start, Goal goal, boolean allowPillar, Predicate<BlockPos> avoid) {
        ClientLevel lvl = Bot.mc().level;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        Node s = new Node(start, 0, goal.heuristic(start), null, false);
        open.add(s);
        best.put(start.asLong(), 0.0);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node n = open.poll();
            if (goal.reached(n.pos)) return build(n);
            if (n.g > best.getOrDefault(n.pos.asLong(), Double.MAX_VALUE) + 1e-9) continue;
            for (Node m : neighbours(lvl, n, allowPillar, avoid)) {
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
        for (Node c = n; c.parent != null; c = c.parent) out.add(new Step(c.pos, c.pillar));
        Collections.reverse(out);
        return out;
    }

    private static List<Node> neighbours(ClientLevel lvl, Node n, boolean allowPillar, Predicate<BlockPos> avoid) {
        List<Node> out = new ArrayList<>(12);
        BlockPos p = n.pos;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos m = p.relative(d);
            if (!lvl.hasChunkAt(m) || avoid.test(m)) continue;
            if (body(lvl, m)) {
                if (standable(lvl, m)) {
                    out.add(new Node(m, n.g + 1, 0, n, false));
                } else {
                    // drop down
                    for (int k = 1; k <= 3; k++) {
                        BlockPos f = m.below(k);
                        if (!passable(lvl, f)) break;
                        if (standable(lvl, f)) { out.add(new Node(f, n.g + 1 + k * 0.5, 0, n, false)); break; }
                    }
                }
            } else if (passable(lvl, p.above(2)) && body(lvl, m.above()) && standable(lvl, m.above())) {
                out.add(new Node(m.above(), n.g + 2, 0, n, false)); // step / jump up
            }
        }
        // diagonals (flat only, both sides open so we don't clip corners)
        int[][] diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] dd : diag) {
            BlockPos m = p.offset(dd[0], 0, dd[1]);
            if (!lvl.hasChunkAt(m) || avoid.test(m)) continue;
            if (body(lvl, m) && standable(lvl, m) && body(lvl, p.offset(dd[0], 0, 0)) && body(lvl, p.offset(0, 0, dd[1]))) {
                out.add(new Node(m, n.g + 1.42, 0, n, false));
            }
        }
        if (allowPillar && passable(lvl, p.above(2)) && lvl.getBlockState(p).canBeReplaced() && !avoid.test(p.above())) {
            out.add(new Node(p.above(), n.g + 6, 0, n, true));
        }
        return out;
    }

    /** Feet + head space free. */
    public static boolean body(ClientLevel lvl, BlockPos feet) {
        return passable(lvl, feet) && passable(lvl, feet.above());
    }

    public static boolean passable(ClientLevel lvl, BlockPos pos) {
        BlockState s = lvl.getBlockState(pos);
        if (!s.getFluidState().isEmpty() && s.getFluidState().is(FluidTags.LAVA)) return false;
        Block b = s.getBlock();
        if (b instanceof BaseFireBlock || b instanceof WebBlock || b instanceof SweetBerryBushBlock
                || b instanceof PowderSnowBlock || b instanceof MagmaBlock || b instanceof CactusBlock) return false;
        return s.getCollisionShape(lvl, pos).isEmpty();
    }

    /** Something solid to stand on below the feet. */
    public static boolean standable(ClientLevel lvl, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState s = lvl.getBlockState(below);
        Block b = s.getBlock();
        if (b instanceof MagmaBlock || b instanceof CactusBlock || b instanceof CampfireBlock) return false;
        if (b instanceof FenceBlock || b instanceof WallBlock || b instanceof FenceGateBlock) return false;
        return !s.getCollisionShape(lvl, below).isEmpty() || lvl.getFluidState(feet).is(FluidTags.WATER);
    }

    private PathFinder() {}
}
