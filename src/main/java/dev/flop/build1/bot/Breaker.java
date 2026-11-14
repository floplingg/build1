package dev.flop.build1.bot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Mines one block at a time with the best tool, facing it properly. */
public final class Breaker {
    /** Read by MinecraftMixin to stop vanilla cancelling our mining each tick. */
    public static volatile boolean active;

    private BlockPos target;
    private int ticks;
    private int waitSwap;

    public BlockPos target() { return target; }
    public int ticks() { return ticks; }

    public static boolean gone(BlockState s) {
        return s.isAir() || s.liquid();
    }

    /** Continue breaking pos. Returns true once it's gone. */
    public boolean tick(BlockPos pos) {
        var mc = Bot.mc();
        BlockState s = mc.level.getBlockState(pos);
        if (gone(s)) { reset(); return true; }
        if (!pos.equals(target)) {
            if (active) mc.gameMode.stopDestroyBlock();
            target = pos.immutable();
            ticks = 0;
            if (pickTool(s)) waitSwap = 0; else { waitSwap = 1; }
        }
        if (waitSwap-- > 0) return false;
        Direction face = Bot.faceToward(pos);
        float[] a = Bot.anglesTo(Bot.eye(), Bot.faceCenter(pos, face));
        Bot.faceNow(a[0], a[1]);
        active = true;
        mc.gameMode.continueDestroyBlock(pos, face);
        mc.player.swing(InteractionHand.MAIN_HAND);
        ticks++;
        return gone(mc.level.getBlockState(pos));
    }

    public void reset() {
        var mc = Bot.mc();
        if (active && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        active = false;
        target = null;
        ticks = 0;
    }

    /** Selects the fastest safe tool. Returns true if it's in hand right now. */
    private static boolean pickTool(BlockState s) {
        LocalPlayer p = Bot.mc().player;
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = score(inv.getItem(inv.getSelectedSlot()), s);
        for (int i = 0; i < 36; i++) {
            double sc = score(inv.getItem(i), s);
            if (sc > bestScore + 1e-6) { bestScore = sc; best = i; }
        }
        if (best < 0) return true;
        return Bot.hold(best);
    }

    private static double score(ItemStack st, BlockState s) {
        if (st.isEmpty()) return 1.0;
        if (st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() < 6) return -1; // don't break tools
        double sp = st.getDestroySpeed(s);
        if (s.requiresCorrectToolForDrops() && st.isCorrectToolForDrops(s)) sp *= 2;
        return sp;
    }
}
