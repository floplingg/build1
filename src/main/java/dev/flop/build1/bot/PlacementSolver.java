package dev.flop.build1.bot;

import dev.flop.build1.Config;
import dev.flop.build1.mixin.BlockItemInvoker;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Works out HOW to click so a block ends up exactly as the schematic wants it.
 *
 * It asks the real item what it would place (BlockItem#getPlacementState via a mixin),
 * so wall signs vs standing signs, wall torches, banners, skulls, buttons, stairs, doors etc.
 * all come out the same way the server will compute them.
 *
 * Blatant: any click point + any rotation (+ air place).
 * Closet: only points the crosshair can really see from where you stand, looked at naturally.
 */
public final class PlacementSolver {

    /** sneak = hold crouch while clicking (clicked block has a GUI, e.g. sign on a chest). */
    public record Plan(BlockHitResult hit, float yaw, float pitch, boolean sneak) {}

    /** Properties that decide whether a placed block "counts" as correct. Neighbour-driven ones are ignored. */
    private static final List<Property<?>> IMPORTANT = List.of(
            BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.HALF,
            BlockStateProperties.SLAB_TYPE, BlockStateProperties.ATTACH_FACE, BlockStateProperties.ROTATION_16,
            BlockStateProperties.ORIENTATION, BlockStateProperties.VERTICAL_DIRECTION, BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.BED_PART, BlockStateProperties.DOUBLE_BLOCK_HALF);

    /** "Click again to add one more" properties. */
    private static final List<IntegerProperty> COUNTS = List.of(
            BlockStateProperties.LAYERS, BlockStateProperties.PICKLES, BlockStateProperties.CANDLES,
            BlockStateProperties.EGGS, BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT);

    // ------------------------------------------------------------------ matching

    /** Is the real block already what the schematic wants? */
    public static boolean satisfied(BlockState real, BlockState want) {
        if (want.isAir()) return true;
        if (real.getBlock() != want.getBlock()) return false;
        for (Property<?> p : IMPORTANT) if (want.hasProperty(p) && real.hasProperty(p) && !same(real, want, p)) return false;
        for (IntegerProperty p : COUNTS) if (want.hasProperty(p) && real.hasProperty(p) && real.getValue(p) < want.getValue(p)) return false;
        return true;
    }

    /** Same block but needs another click on itself (half slab -> double, 1 candle -> 3 candles, snow layers...). */
    public static boolean needsStacking(BlockState real, BlockState want) {
        if (real.getBlock() != want.getBlock()) return false;
        if (want.hasProperty(BlockStateProperties.SLAB_TYPE) && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                && real.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE) return true;
        for (IntegerProperty p : COUNTS) if (want.hasProperty(p) && real.hasProperty(p) && real.getValue(p) < want.getValue(p)) return true;
        return false;
    }

    /** Does a freshly placed state count as progress toward want? */
    private static boolean placesAs(BlockState got, BlockState want, BlockState real) {
        if (got == null || got.getBlock() != want.getBlock()) return false;
        for (Property<?> p : IMPORTANT) {
            if (!want.hasProperty(p) || !got.hasProperty(p)) continue;
            if (p == BlockStateProperties.SLAB_TYPE && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) continue;
            if (!same(got, want, p)) return false;
        }
        if (real.getBlock() == want.getBlock()) {
            // stacking must actually add something
            if (got.equals(real)) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> boolean same(BlockState a, BlockState b, Property<T> p) {
        return a.getValue(p).equals(b.getValue(p));
    }

    /** Blocks we never place directly (they come with their other half, or have no item). */
    public static boolean skip(BlockState want) {
        Block b = want.getBlock();
        if (want.isAir() || b instanceof LiquidBlock || b instanceof PistonHeadBlock || b instanceof MovingPistonBlock) return true;
        if (b.asItem() == Items.AIR) return true;
        if (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        if (want.hasProperty(BlockStateProperties.BED_PART) && want.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return true;
        return false;
    }

    /** Clicking these would open a GUI / toggle them instead of placing. */
    public static boolean interactive(ClientLevel lvl, BlockPos pos, BlockState s) {
        Block b = s.getBlock();
        return b instanceof BaseEntityBlock || s.getMenuProvider(lvl, pos) != null
                || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof BedBlock
                || b instanceof NoteBlock || b instanceof RepeaterBlock || b instanceof ComparatorBlock
                || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof CakeBlock
                || b instanceof BellBlock || b instanceof DaylightDetectorBlock || b instanceof RedStoneOreBlock
                || b instanceof CandleBlock || b instanceof ComposterBlock || b instanceof RespawnAnchorBlock
                || b instanceof FlowerPotBlock || b instanceof CaveVinesBlock || b instanceof CaveVinesPlantBlock
                || b instanceof RedStoneWireBlock || b instanceof ChiseledBookShelfBlock;
    }

    // ------------------------------------------------------------------ simulation

    /** What would right-clicking `hit` with `stack` at this rotation place? null = nothing / somewhere else. */
    public static BlockState simulate(BlockPos pos, ItemStack stack, BlockHitResult hit, float yaw, float pitch, boolean sneak) {
        if (!(stack.getItem() instanceof BlockItem bi)) return null;
        LocalPlayer p = Bot.mc().player;
        float oy = p.getYRot(), op = p.getXRot();
        boolean os = p.isShiftKeyDown();
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setShiftKeyDown(sneak);
        try {
            BlockPlaceContext ctx = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hit);
            BlockPlaceContext c = bi.updatePlacementContext(ctx);
            if (c == null || !c.canPlace() || !c.getClickedPos().equals(pos)) return null;
            return ((BlockItemInvoker) (Object) bi).build1$getPlacementState(c);
        } catch (Throwable t) {
            return null;
        } finally {
            p.setYRot(oy);
            p.setXRot(op);
            p.setShiftKeyDown(os);
        }
    }

    /** Would this exact click (as it really happens) give us the block we want? */
    public static boolean clickWorks(BlockPos pos, BlockState want, ItemStack stack, BlockHitResult hit, float yaw, float pitch, boolean sneak) {
        BlockState real = Bot.mc().level.getBlockState(pos);
        return placesAs(simulate(pos, stack, hit, yaw, pitch, sneak), want, real);
    }

    public static BlockHitResult raycast(Vec3 eye, float yaw, float pitch, double reach) {
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        Vec3 to = eye.add(dir.scale(reach));
        return Bot.mc().level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, Bot.mc().player));
    }

    // ------------------------------------------------------------------ solving

    public static Plan solve(BlockPos pos, BlockState want, ItemStack stack) {
        return solve(pos, want, stack, Bot.eye(), Config.closet());
    }

    public static Plan solve(BlockPos pos, BlockState want, ItemStack stack, Vec3 eye, boolean legit) {
        ClientLevel lvl = Bot.mc().level;
        double reach = Bot.reach();
        BlockState real = lvl.getBlockState(pos);
        boolean stacking = needsStacking(real, want);
        if (!stacking && !real.canBeReplaced()) return null;

        // Things we can click: neighbours (normal placement) or the block itself (stacking).
        List<BlockPos> clickables = new ArrayList<>();
        List<BlockPos> guiBlocks = new ArrayList<>(); // need crouching, so try them last
        if (stacking) clickables.add(pos);
        else {
            for (Direction d : Direction.values()) {
                BlockPos nb = pos.relative(d);
                BlockState ns = lvl.getBlockState(nb);
                if (ns.canBeReplaced() || ns.getShape(lvl, nb).isEmpty()) continue;
                if (interactive(lvl, nb, ns)) guiBlocks.add(nb); else clickables.add(nb);
            }
        }

        for (BlockPos cp : clickables) {
            Plan p = legit ? solveLegit(pos, want, stack, eye, reach, cp, real, false) : solveBlatant(pos, want, stack, eye, reach, cp, real, false);
            if (p != null) return p;
        }
        for (BlockPos cp : guiBlocks) {
            Plan p = legit ? solveLegit(pos, want, stack, eye, reach, cp, real, true) : solveBlatant(pos, want, stack, eye, reach, cp, real, true);
            if (p != null) return p;
        }
        // Blatant: air place (nothing to click on). Click the empty spot itself.
        if (!legit && !stacking && clickables.isEmpty() && guiBlocks.isEmpty() && Config.airPlace) {
            Vec3 c = Vec3.atCenterOf(pos);
            if (eye.distanceTo(c) <= reach) {
                Direction face = Bot.faceToward(pos);
                BlockHitResult hit = new BlockHitResult(c, face, pos, false);
                for (float[] rot : rotations(Bot.anglesTo(eye, c), want))
                    if (clickWorks(pos, want, stack, hit, rot[0], rot[1], false)) return new Plan(hit, rot[0], rot[1], false);
            }
        }
        return null;
    }

    /** Closet: look at a visible point, raycast exactly like the crosshair, accept only what that click gives. */
    private static Plan solveLegit(BlockPos pos, BlockState want, ItemStack stack, Vec3 eye, double reach, BlockPos cp, BlockState real, boolean sneak) {
        if (sneak) eye = eye.add(0, -0.35, 0); // crouching lowers the eyes
        for (Vec3 pt : samplePoints(cp, pos, eye)) {
            if (eye.distanceTo(pt) > reach) continue;
            float[] a = Bot.anglesTo(eye, pt);
            BlockHitResult hit = raycast(eye, a[0], a[1], reach + 0.5);
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(cp)) continue;
            if (eye.distanceTo(hit.getLocation()) > reach) continue;
            if (clickWorks(pos, want, stack, hit, a[0], a[1], sneak)) return new Plan(hit, a[0], a[1], sneak);
        }
        return null;
    }

    /** Blatant: try face points and every useful rotation. */
    private static Plan solveBlatant(BlockPos pos, BlockState want, ItemStack stack, Vec3 eye, double reach, BlockPos cp, BlockState real, boolean sneak) {
        List<Direction> faces = new ArrayList<>();
        if (cp.equals(pos)) { for (Direction d : Direction.values()) faces.add(d); }
        else {
            for (Direction d : Direction.values()) if (cp.relative(d).equals(pos)) faces.add(d);
        }
        for (Direction face : faces) {
            for (Vec3 hv : facePoints(cp, face, cp.equals(pos) ? real.getShape(Bot.mc().level, pos) : null)) {
                if (eye.distanceTo(hv) > reach) continue;
                BlockHitResult hit = new BlockHitResult(hv, face, cp, false);
                for (float[] rot : rotations(Bot.anglesTo(eye, hv), want)) {
                    if (clickWorks(pos, want, stack, hit, rot[0], rot[1], sneak)) return new Plan(hit, rot[0], rot[1], sneak);
                }
            }
        }
        return null;
    }

    /** 5 points on a face: centre then near the four edges (top/bottom halves, door hinges...). */
    private static List<Vec3> facePoints(BlockPos cp, Direction face, VoxelShape shape) {
        AABB box = shape != null && !shape.isEmpty() ? shape.bounds().move(cp) : new AABB(cp);
        Vec3 c = box.getCenter();
        double hx = box.getXsize() / 2, hy = box.getYsize() / 2, hz = box.getZsize() / 2;
        Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 fc = c.add(n.x * hx, n.y * hy, n.z * hz);
        List<Vec3> out = new ArrayList<>();
        out.add(fc);
        double k = 0.3;
        if (face.getAxis() == Direction.Axis.Y) {
            out.add(fc.add(hx * 2 * k, 0, 0)); out.add(fc.add(-hx * 2 * k, 0, 0));
            out.add(fc.add(0, 0, hz * 2 * k)); out.add(fc.add(0, 0, -hz * 2 * k));
        } else {
            out.add(fc.add(0, hy * 2 * k, 0)); out.add(fc.add(0, -hy * 2 * k, 0));
            if (face.getAxis() == Direction.Axis.X) { out.add(fc.add(0, 0, hz * 2 * k)); out.add(fc.add(0, 0, -hz * 2 * k)); }
            else { out.add(fc.add(hx * 2 * k, 0, 0)); out.add(fc.add(-hx * 2 * k, 0, 0)); }
        }
        return out;
    }

    /** Points worth looking at to click block cp (for placing into pos), on faces turned toward the eye. */
    private static List<Vec3> samplePoints(BlockPos cp, BlockPos pos, Vec3 eye) {
        ClientLevel lvl = Bot.mc().level;
        VoxelShape shape = lvl.getBlockState(cp).getShape(lvl, cp);
        AABB box = shape.isEmpty() ? new AABB(cp) : shape.bounds().move(cp);
        List<Vec3> out = new ArrayList<>();
        double[] g = {0.5, 0.2, 0.8};
        for (Direction face : Direction.values()) {
            if (!cp.equals(pos) && !cp.relative(face).equals(pos)) continue; // must click the face touching pos
            Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            double fx = face.getStepX() > 0 ? box.maxX : face.getStepX() < 0 ? box.minX : 0;
            double fy = face.getStepY() > 0 ? box.maxY : face.getStepY() < 0 ? box.minY : 0;
            double fz = face.getStepZ() > 0 ? box.maxZ : face.getStepZ() < 0 ? box.minZ : 0;
            // face must point at us
            Vec3 faceCenter = new Vec3(face.getStepX() != 0 ? fx : box.getCenter().x,
                    face.getStepY() != 0 ? fy : box.getCenter().y, face.getStepZ() != 0 ? fz : box.getCenter().z);
            if (eye.subtract(faceCenter).dot(n) <= 0.01) continue;
            for (double a : g) for (double b : g) {
                double x, y, z;
                switch (face.getAxis()) {
                    case X -> { x = fx; y = Mth.lerp(a, box.minY, box.maxY); z = Mth.lerp(b, box.minZ, box.maxZ); }
                    case Y -> { y = fy; x = Mth.lerp(a, box.minX, box.maxX); z = Mth.lerp(b, box.minZ, box.maxZ); }
                    default -> { z = fz; x = Mth.lerp(a, box.minX, box.maxX); y = Mth.lerp(b, box.minY, box.maxY); }
                }
                // aim a hair inside the face so the ray really lands on it
                out.add(new Vec3(x, y, z).subtract(n.scale(0.01)));
            }
        }
        return out;
    }

    /** Natural aim first, then cardinal yaws with level / down / up pitch, plus all 16 sign/banner angles if needed. */
    private static List<float[]> rotations(float[] aim, BlockState want) {
        List<float[]> out = new ArrayList<>();
        out.add(aim);
        float[] yaws = {aim[0], 0f, 90f, 180f, -90f};
        float[] pitches = {aim[1], 0f, 80f, -80f};
        for (float y : yaws) for (float pt : pitches) out.add(new float[]{y, pt});
        if (want.hasProperty(BlockStateProperties.ROTATION_16)) {
            for (int i = 0; i < 16; i++) {
                float y = Mth.wrapDegrees(i * 22.5f - 180f);
                out.add(new float[]{y, aim[1]});
                out.add(new float[]{y, 0f});
            }
        }
        return out;
    }

    private PlacementSolver() {}
}
