package dev.flop.build1.bot;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Works out HOW to place a block so it ends up in the exact orientation the schematic wants.
 * It tries every neighbour face / hit height / look direction, asks the block itself what
 * state it would become (getStateForPlacement), and picks the first combo that matches.
 * Natural aim (looking straight at the face you click) is always tried first.
 */
public final class PlacementSolver {

    public record Plan(BlockHitResult hit, float yaw, float pitch) {}

    /** Properties that decide whether a placed block "counts" as correct. Neighbour-driven ones are ignored. */
    private static final List<Property<?>> IMPORTANT = List.of(
            BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.HALF,
            BlockStateProperties.SLAB_TYPE, BlockStateProperties.ATTACH_FACE, BlockStateProperties.ROTATION_16,
            BlockStateProperties.ORIENTATION, BlockStateProperties.VERTICAL_DIRECTION, BlockStateProperties.DOOR_HINGE);

    /** Is the real block already what the schematic wants? */
    public static boolean satisfied(BlockState real, BlockState want) {
        if (want.isAir()) return true;
        if (real.getBlock() != want.getBlock()) return false;
        for (Property<?> p : IMPORTANT) {
            if (want.hasProperty(p) && real.hasProperty(p) && !same(real, want, p)) return false;
        }
        return true;
    }

    /** Placement-time match: like satisfied, but a half slab counts toward a double slab. */
    private static boolean placesAs(BlockState got, BlockState want) {
        if (got.getBlock() != want.getBlock()) return false;
        for (Property<?> p : IMPORTANT) {
            if (p == BlockStateProperties.SLAB_TYPE && want.hasProperty(p) && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) continue;
            if (want.hasProperty(p) && got.hasProperty(p) && !same(got, want, p)) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> boolean same(BlockState a, BlockState b, Property<T> p) {
        return a.getValue(p).equals(b.getValue(p));
    }

    /** Blocks we never try to place directly (they come with their other half, or can't be placed as items). */
    public static boolean skip(BlockState want) {
        Block b = want.getBlock();
        if (want.isAir() || b instanceof LiquidBlock || b instanceof PistonHeadBlock || b instanceof MovingPistonBlock) return true;
        if (b.asItem() == net.minecraft.world.item.Items.AIR) return true;
        if (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        if (want.hasProperty(BlockStateProperties.BED_PART) && want.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return true;
        return false;
    }

    /** Clicking these would open a GUI / toggle them instead of placing. */
    private static boolean interactive(ClientLevel lvl, BlockPos pos, BlockState s) {
        Block b = s.getBlock();
        return b instanceof BaseEntityBlock || s.getMenuProvider(lvl, pos) != null
                || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof BedBlock
                || b instanceof NoteBlock || b instanceof RepeaterBlock || b instanceof ComparatorBlock
                || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof CakeBlock
                || b instanceof BellBlock || b instanceof DaylightDetectorBlock || b instanceof RedStoneOreBlock
                || b instanceof CandleBlock || b instanceof ComposterBlock || b instanceof RespawnAnchorBlock;
    }

    public static Plan solve(BlockPos pos, BlockState want, ItemStack stack) {
        var mc = Bot.mc();
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        Vec3 eye = Bot.eye();
        double reach = Bot.reach();
        BlockState real = lvl.getBlockState(pos);

        // Completing a double slab: click the existing half from the open side.
        if (real.getBlock() == want.getBlock() && want.hasProperty(BlockStateProperties.SLAB_TYPE)
                && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE && real.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            boolean bottom = real.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.BOTTOM;
            Direction face = bottom ? Direction.UP : Direction.DOWN;
            Vec3 hv = Vec3.atCenterOf(pos);
            if (eye.distanceTo(hv) > reach) return null;
            float[] a = Bot.anglesTo(eye, hv);
            return new Plan(new BlockHitResult(hv, face, pos, false), a[0], a[1]);
        }
        if (!real.canBeReplaced()) return null;

        float origYaw = p.getYRot(), origPitch = p.getXRot();
        try {
            for (Direction d : Direction.values()) {
                BlockPos nb = pos.relative(d);
                BlockState ns = lvl.getBlockState(nb);
                if (ns.canBeReplaced() || ns.getShape(lvl, nb).isEmpty() || interactive(lvl, nb, ns)) continue;
                Direction face = d.getOpposite();
                Vec3 base = Bot.faceCenter(nb, face);
                List<Vec3> hits = new ArrayList<>();
                hits.add(base);
                if (face.getAxis().isHorizontal()) {
                    hits.add(base.add(0, 0.3, 0));
                    hits.add(base.add(0, -0.3, 0));
                }
                for (Vec3 hv : hits) {
                    if (eye.distanceTo(hv) > reach) continue;
                    float[] aim = Bot.anglesTo(eye, hv);
                    BlockHitResult hit = new BlockHitResult(hv, face, nb, false);
                    for (float[] rot : rotations(aim)) {
                        p.setYRot(rot[0]);
                        p.setXRot(rot[1]);
                        BlockPlaceContext ctx = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hit);
                        if (!ctx.getClickedPos().equals(pos) || !ctx.canPlace()) continue;
                        BlockState got;
                        try { got = want.getBlock().getStateForPlacement(ctx); } catch (Throwable t) { got = null; }
                        if (got != null && placesAs(got, want) && got.canSurvive(lvl, pos)) {
                            return new Plan(hit, rot[0], rot[1]);
                        }
                    }
                }
            }
            return null;
        } finally {
            p.setYRot(origYaw);
            p.setXRot(origPitch);
        }
    }

    /** Natural aim first, then cardinal yaws with level / down / up pitch. */
    private static List<float[]> rotations(float[] aim) {
        List<float[]> out = new ArrayList<>();
        out.add(aim);
        float[] yaws = {aim[0], 0f, 90f, 180f, -90f};
        float[] pitches = {aim[1], 0f, 80f, -80f};
        for (float y : yaws) for (float pt : pitches) out.add(new float[]{y, pt});
        return out;
    }

    private PlacementSolver() {}
}
