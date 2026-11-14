package dev.flop.build1.bot;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class Safety {

    /** Would breaking pos be a bad idea (drop into lava / a deep hole, or open up lava)? */
    public static boolean safeToBreak(BlockPos pos, boolean avoidLava) {
        ClientLevel lvl = Bot.mc().level;
        LocalPlayer p = Bot.mc().player;
        if (avoidLava) {
            for (Direction d : Direction.values()) {
                if (d == Direction.DOWN) continue;
                if (lvl.getFluidState(pos.relative(d)).is(FluidTags.LAVA)) return false;
            }
        }
        // Is this block holding us up?
        AABB bb = p.getBoundingBox();
        boolean underUs = pos.getY() + 1 <= p.getY() + 0.01 && pos.getY() + 1 >= p.getY() - 0.6
                && bb.maxX > pos.getX() && bb.minX < pos.getX() + 1 && bb.maxZ > pos.getZ() && bb.minZ < pos.getZ() + 1;
        if (!underUs) return true;
        for (int k = 1; k <= 4; k++) {
            BlockPos q = pos.below(k);
            BlockState s = lvl.getBlockState(q);
            if (s.getFluidState().is(FluidTags.LAVA)) return false;
            if (!s.getCollisionShape(lvl, q).isEmpty()) return k <= 4; // fall of <= 3 blocks
            if (s.getFluidState().is(FluidTags.WATER)) return true;
        }
        return false;
    }

    public static boolean inReach(BlockPos pos) {
        return Bot.eye().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= Bot.reach();
    }

    public static boolean intersectsPlayer(BlockPos pos) {
        return Bot.mc().player.getBoundingBox().intersects(new AABB(pos));
    }

    private Safety() {}
}
