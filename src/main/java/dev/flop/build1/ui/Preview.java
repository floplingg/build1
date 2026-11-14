package dev.flop.build1.ui;

import dev.flop.build1.LitematicaBridge.Box;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;

/** Outlines an area in the world with particles, so you can see exactly what will be dug. */
public final class Preview {
    private static Box box;
    private static int until, t;
    private static boolean sticky;

    /** Show for a number of ticks (or keep showing while sticky). */
    public static void show(Box b, int ticks) { box = b; until = t + ticks; }
    public static void setSticky(boolean s) { sticky = s; }
    public static Box box() { return box; }

    public static void tick(Minecraft mc) {
        t++;
        if (box == null || mc.level == null) return;
        if (t > until && !sticky) { box = null; return; }
        if (t % 6 != 0) return;
        double x0 = box.min().getX(), y0 = box.min().getY(), z0 = box.min().getZ();
        double x1 = box.max().getX() + 1, y1 = box.max().getY() + 1, z1 = box.max().getZ() + 1;
        double perim = 4 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
        double step = Math.max(0.5, perim / 360.0);
        double[][] corners = {{x0, y0, z0}, {x1, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x0, y1, z1}, {x1, y1, z1}};
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            double[] a = corners[e[0]], b = corners[e[1]];
            double len = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]) + Math.abs(b[2] - a[2]);
            for (double d = 0; d <= len + 1e-6; d += step) {
                double k = len == 0 ? 0 : d / len;
                mc.level.addParticle(ParticleTypes.END_ROD, a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k, 0, 0, 0);
            }
        }
    }

    private Preview() {}
}
