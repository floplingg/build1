package dev.flop.build1.bot;

import dev.flop.build1.Config;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Materials chests: added by hand (menu / command) or found automatically —
 * any chest, barrel or shulker box with a sign on or next to it that says "material(s)".
 */
public final class ChestRegistry {
    private static final Set<BlockPos> manual = new LinkedHashSet<>();
    private static final Set<BlockPos> found = new LinkedHashSet<>();

    public static void load() {
        manual.clear();
        for (String part : Config.chests.split(";")) {
            String[] xyz = part.trim().split(",");
            if (xyz.length != 3) continue;
            try { manual.add(new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()))); }
            catch (NumberFormatException ignored) {}
        }
    }

    private static void save() {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : manual) sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(';');
        Config.chests = sb.toString();
        Config.save();
    }

    public static boolean isContainer(BlockState s) {
        Block b = s.getBlock();
        return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
    }

    /** Toggle the looked-at container. Returns true if it is now a materials chest. */
    public static boolean toggle(BlockPos pos) {
        if (manual.remove(pos)) { save(); return false; }
        manual.add(pos.immutable());
        save();
        return true;
    }

    public static void clear() { manual.clear(); found.clear(); save(); }

    /** Look for "materials" signs around center. */
    public static int scan(BlockPos center, int radius) {
        ClientLevel lvl = Bot.mc().level;
        found.clear();
        int cr = (radius >> 4) + 1;
        ChunkPos c = new ChunkPos(center);
        for (int dx = -cr; dx <= cr; dx++) for (int dz = -cr; dz <= cr; dz++) {
            if (!lvl.hasChunk(c.x + dx, c.z + dz)) continue;
            for (BlockEntity be : lvl.getChunk(c.x + dx, c.z + dz).getBlockEntities().values()) {
                if (!(be instanceof SignBlockEntity sign) || !mentionsMaterials(sign)) continue;
                BlockPos sp = sign.getBlockPos();
                if (sp.distSqr(center) > (double) radius * radius) continue;
                BlockState ss = lvl.getBlockState(sp);
                // wall sign: the block it hangs on first
                if (ss.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    BlockPos behind = sp.relative(ss.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
                    if (isContainer(lvl.getBlockState(behind))) { found.add(behind.immutable()); continue; }
                }
                for (Direction d : Direction.values()) {
                    BlockPos n = sp.relative(d);
                    if (isContainer(lvl.getBlockState(n))) found.add(n.immutable());
                }
            }
        }
        return found.size();
    }

    private static boolean mentionsMaterials(SignBlockEntity s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(s.getFrontText().getMessage(i, false).getString()).append(' ');
            sb.append(s.getBackText().getMessage(i, false).getString()).append(' ');
        }
        return sb.toString().toLowerCase(Locale.ROOT).contains("material");
    }

    /** All known chests that still exist, nearest first. */
    public static List<BlockPos> all() {
        ClientLevel lvl = Bot.mc().level;
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new LinkedHashSet<>(manual);
        seen.addAll(found);
        for (BlockPos p : seen) if (!lvl.hasChunkAt(p) || isContainer(lvl.getBlockState(p))) out.add(p);
        BlockPos me = Bot.mc().player.blockPosition();
        out.sort((a, b) -> Double.compare(a.distSqr(me), b.distSqr(me)));
        return out;
    }

    public static boolean isManual(BlockPos p) { return manual.contains(p); }
    public static int count() { return all().size(); }

    private ChestRegistry() {}
}
