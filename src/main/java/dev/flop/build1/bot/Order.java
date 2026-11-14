package dev.flop.build1.bot;

import dev.flop.build1.LitematicaBridge.Box;
import net.minecraft.core.BlockPos;

/**
 * Serpentine ("lawnmower") order: the area is cut into 4-wide strips along its long side;
 * go up strip 1, step over, come back down strip 2, step over, up strip 3...
 */
public final class Order {
    public static final int STRIP = 4;

    /** Lower key = do first. topDown = miner (highest layer first). */
    public static long key(BlockPos p, Box box, boolean topDown) {
        boolean runAlongX = box.sizeX() > box.sizeZ();
        int across = runAlongX ? p.getZ() - box.min().getZ() : p.getX() - box.min().getX();
        int along = runAlongX ? p.getX() - box.min().getX() : p.getZ() - box.min().getZ();
        int len = runAlongX ? box.sizeX() : box.sizeZ();
        int strip = across / STRIP;
        int inStrip = across % STRIP;
        int a = (strip % 2 == 0) ? along : (len - 1 - along);
        if (strip % 2 == 1) inStrip = STRIP - 1 - inStrip;
        long layer = topDown ? box.max().getY() - p.getY() : p.getY() - box.min().getY();
        return layer * 4_000_000_000L + strip * 2_000_000L + a * 8L + inStrip;
    }

    private Order() {}
}
